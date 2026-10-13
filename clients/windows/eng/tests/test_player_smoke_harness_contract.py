"""Offline guards and optional existing-SDK checks for the native smoke helper.

The SDK check compiles the production C# decoder, PNG writer and shutdown-evidence
and debugger-evidence readers, but never calls Windows APIs. It uses no NuGet sources/packages and installs
nothing. Static guards do not establish a native PowerShell parse/pass; an optional
parser check runs only when PowerShell is already available.
"""
from __future__ import annotations

import ast
import hashlib
import importlib.util
import json
import os
import re
import shutil
import struct
import subprocess
import tempfile
import unittest
from unittest import mock
import zlib
from pathlib import Path

ENG = Path(__file__).resolve().parents[1]
HARNESS = ENG / "Test-PlayerSmoke.ps1"
SPEC = importlib.util.spec_from_file_location("smoke_fixture_for_harness", ENG / "New-PlayerSmokeFixture.py")
fixture = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(fixture)
DOTNET = os.environ.get("FILEWAY_SMOKE_DOTNET") or shutil.which("dotnet")


def source_parts():
    text = HARNESS.read_text(encoding="utf-8")
    native = re.search(r"\$nativeSource = @'\n(.*?)\n'@", text, re.S)
    if not native:
        raise ValueError("Missing in-memory native helper")
    return text, native.group(1), text[:native.start()] + text[native.end():]


def scaled_bgra(index, width=480, height=270):
    frame = fixture.render_frame(index)
    scale = min(width // fixture.WIDTH, height // fixture.HEIGHT)
    left = (width - fixture.WIDTH * scale) // 2
    top = (height - fixture.HEIGHT * scale) // 2
    pixels = bytearray(width * height * 4)
    for y in range(fixture.HEIGHT):
        row = bytearray()
        for x in range(fixture.WIDTH):
            offset = (fixture.HEIGHT - 1 - y) * fixture.STRIDE + x * 3
            row.extend((frame[offset:offset + 3] + b"\xff") * scale)
        for repeat in range(scale):
            offset = ((top + y * scale + repeat) * width + left) * 4
            pixels[offset:offset + len(row)] = row
    return pixels


class HarnessGuards(unittest.TestCase):
    def test_every_text_fixture_io_has_explicit_utf8(self):
        tree = ast.parse(Path(__file__).read_text(encoding="utf-8"))
        calls = [node for node in ast.walk(tree) if isinstance(node, ast.Call)
                 and isinstance(node.func, ast.Attribute) and node.func.attr in ("read_text", "write_text")]
        self.assertGreater(len(calls), 10)
        for call in calls:
            encoding = next((keyword.value for keyword in call.keywords if keyword.arg == "encoding"), None)
            self.assertIsInstance(encoding, ast.Constant, f"Implicit text encoding at line {call.lineno}")
            self.assertEqual(encoding.value, "utf-8", f"Non-UTF8 fixture at line {call.lineno}")

    def test_diagnostic_workflow_runs_both_cases_but_requires_both_outcomes(self):
        workflow = (ENG.parents[2] / ".github/workflows/windows-player-shutdown-diagnostics.yml").read_text(encoding="utf-8")
        self.assertEqual(workflow.count("id: build"), 1)
        self.assertEqual(workflow.count("continue-on-error: true"), 2)
        self.assertEqual(workflow.count("-ShutdownScenario MinimalPlayback"), 1)
        self.assertEqual(workflow.count("-ShutdownScenario MainWithOpenPlayer"), 1)
        self.assertIn("steps.case_a.outcome", workflow)
        self.assertIn("steps.case_b.outcome", workflow)
        self.assertIn("$env:CASE_A_OUTCOME -ne 'success' -or $env:CASE_B_OUTCOME -ne 'success'", workflow)
        self.assertEqual(workflow.count("!cancelled() && steps.build.outcome == 'success' && steps.fixture.outcome == 'success'"), 3)
        self.assertNotIn("steps.case_a.conclusion", workflow)
        self.assertNotIn("steps.case_b.conclusion", workflow)
        upload = workflow.split("- name: Preserve only allowlisted synthetic UI evidence", 1)[1]
        self.assertIn("/A-minimal/report.json", upload)
        self.assertIn("/B-open-player/report.json", upload)
        for private in (".dmp", "shutdown-stack.log", "shutdown-region.log", "symbols", "FilewayPlayerSmoke-"):
            self.assertNotIn(private, upload)

    def test_default_synthetic_byte_hash_is_the_only_accepted_media(self):
        digest = hashlib.sha256()
        class HashWriter:
            def write(self, block):
                digest.update(block)
        fixture.write_avi(HashWriter(), fixture.DEFAULT_SECONDS * fixture.FPS)
        text, _, _ = source_parts()
        self.assertIn("-cne '" + digest.hexdigest() + "'", text)
        self.assertIn("$manifest.frameCount -ne 2160", text)
        self.assertIn("[System.IO.FileShare]::Read)", text)

    def test_settings_flow_handles_adaptive_overflow_menu(self):
        _, _, powershell = source_parts()
        self.assertIn("Find-Control $hud '^播放速度(?: |$)' -Optional", powershell)
        self.assertIn("Invoke-Control $hud '^更多播放选项$'", powershell)
        self.assertIn("Invoke-Control $menu '^播放速度 · ' 'MenuItem'", powershell)
        self.assertIn("'more_menu_not_closed'", powershell)
        self.assertIn("Wait-Control $Window $NamePattern $Type", powershell)

    def test_no_assignment_to_readonly_powershell_automatic_variables(self):
        _, _, powershell = source_parts()
        reserved = r"(?:host|pid|pshome|psversiontable|iswindows|islinux|ismacos|psedition)"
        self.assertIsNone(re.search(r"\$" + reserved + r"\b\s*=", powershell, re.I))
        self.assertIsNone(re.search(r"foreach\s*\(\s*\$" + reserved + r"\b", powershell, re.I))

    def test_pass_is_after_explicit_application_and_host_exit_assertions(self):
        text, _, _ = source_parts()
        passed = text.index("status = 'passed'; code = 'smoke_ok'")
        for guard in ("application_exit_timeout", "application_exit_code_nonzero", "owned_host_exit_timeout", "owned_host_exit_code_nonzero"):
            self.assertLess(text.index(guard), passed)
        self.assertIn("scope = 'native-smoke'", text)
        self.assertIn("nativePlaybackVerified = $false", text)

    def test_exit_evidence_does_not_relax_shutdown_gate_or_copy_raw_logs(self):
        text, _, powershell = source_parts()
        self.assertIn("Record-SafeExitEvidence", powershell)
        self.assertIn("Stop-Smoke 'application_exit_code_nonzero'", powershell)
        self.assertIn("$observedId -ne $script:process.Id", powershell)
        self.assertNotIn("Record-Observation 'managed-failure' $failure", powershell)
        self.assertNotIn("Copy-Item", powershell)
        self.assertLess(text.index("Complete-Step 'close'\n    $null = Copy-SafeDiagnostics"), text.index("$script:stage = 'application-shutdown'"))

    def test_shutdown_evidence_exports_only_reconstructed_numeric_records(self):
        text, native, powershell = source_parts()
        evidence = powershell.split("function Record-SafeExitEvidence {", 1)[1].split("$exitCode = 1", 1)[0]
        self.assertIn("[Fileway.PlayerSmoke.ShutdownEvidence]::Read($script:privateRoot)", evidence)
        self.assertIn("Record-Observation 'shutdown-stage' @{", evidence)
        for field in ("schemaVersion = [int]$record.SchemaVersion", "stage = [int]$record.Stage", "uiThread = [int]$record.UiThread"):
            self.assertIn(field, evidence)
        self.assertNotIn("Record-Observation 'shutdown-stage' $record", evidence)
        self.assertIn('MaximumBytes=4096, MaximumLines=16, MaximumLineLength=256', native)
        self.assertIn('Path.Combine(directory,"shutdown.jsonl")', native)
        self.assertIn('new byte[MaximumBytes+1]', native)
        self.assertIn('while(count<bytes.Length)', native)
        self.assertIn('values.ContainsKey(key)', native)
        self.assertIn('StringComparer.Ordinal', native)
        self.assertIn('$start.Environment[\'FILEWAY_WINDOWS_DATA_ROOT\'] = $script:privateRoot', text)
        self.assertIn('$start.Environment[\'FILEWAY_PLAYER_RESPONSIVENESS_DIAGNOSTICS\'] = \'1\'', text)

    def test_shutdown_fault_retry_stays_bounded_and_process_matched(self):
        _, _, powershell = source_parts()
        evidence = powershell.split("function Record-SafeExitEvidence {", 1)[1].split("$exitCode = 1", 1)[0]
        self.assertIn("$attempt -lt 20 -and $faultWait.ElapsedMilliseconds -lt 5000", evidence)
        self.assertIn("-MaxEvents 16 -ErrorAction Stop", evidence)
        self.assertIn("Start-Sleep -Milliseconds 250", evidence)
        self.assertIn("if ($matchedFault) { break }", evidence)
        self.assertIn("$observedId -ne $script:process.Id", evidence)
        self.assertIn("StartTime = $script:process.StartTime", evidence)
        self.assertIn("$fields.AppName -ne [IO.Path]::GetFileName($AppPath)", evidence)
        self.assertNotIn("Set-ItemProperty", powershell)

    def test_closed_markers_wrap_original_cleanup_without_catching_its_failures(self):
        app = ENG.parent / "src" / "Fileway.App"
        main = (app / "MainWindow.xaml.cs").read_text(encoding="utf-8")
        chrome = (app / "Windowing" / "FramelessWindowChrome.cs").read_text(encoding="utf-8")
        self.assertIn("recordShutdownStages: true", main)
        self.assertIn("bool recordShutdownStages = false", chrome)
        for source, name, entered, returned in ((main, "MainWindow_Closed", "MainWindowClosedEntered", "MainWindowClosedReturned"),
                                                (chrome, "Window_Closed", "ChromeClosedEntered", "ChromeClosedReturned")):
            block = source.split(f"private void {name}(", 1)[1].split("\n    }", 1)[0]
            self.assertLess(block.index(entered), block.index("Dispose();"))
            self.assertLess(block.index("Dispose();"), block.index(returned))
            self.assertNotIn("catch", block)
        self.assertIn("stage>13", source_parts()[1])

    def test_debugger_is_explicit_isolated_bounded_and_preserves_nonzero_exit(self):
        text, native, powershell = source_parts()
        self.assertIn("[switch] $CaptureShutdownStack", powershell)
        self.assertIn("if (-not $CaptureShutdownStack) { return }", powershell)
        self.assertIn("Get-AuthenticodeSignature -LiteralPath $candidate", powershell)
        self.assertIn("$signature.Status -ne 'Valid'", powershell)
        self.assertIn("O=Microsoft Corporation", powershell)
        self.assertIn("$script:process.StartTime.ToUniversalTime().Ticks -ne $script:appStartTicks", powershell)
        self.assertIn("$script:process.MainModule.FileName", powershell)
        self.assertIn("'-p', $script:process.Id.ToString(), '-pd', '-G', '-sins', '-noshell'", powershell)
        self.assertIn(".dump /muF /o shutdown.dmp; gn", powershell)
        self.assertNotIn("; gh", powershell)
        self.assertIn("$watch.ElapsedMilliseconds -lt 10000", powershell)
        self.assertIn("$analysis.WaitForExit(120000)", powershell)
        self.assertIn("[Fileway.PlayerSmoke.DebuggerEvidence]::HasReadyMarker($log)", powershell)
        self.assertIn("ReadBounded(path,FileShare.ReadWrite)", native)
        self.assertNotIn("[IO.File]::ReadAllText($log)", powershell)
        self.assertIn("hresult = [int]$attachError.HResult", powershell)
        self.assertIn("srv*$symbols*https://msdl.microsoft.com/download/symbols", powershell)
        self.assertIn("u ntdll!RtlpHandleInvalidUserCallTarget L0n24", powershell)
        self.assertIn("system_ntdll_signature_invalid", powershell)
        self.assertIn("debugger_system_module_identity_mismatch", powershell)
        self.assertNotIn(".frame /r", powershell)
        self.assertIn("ub ntdll!RtlpHandleInvalidUserCallTarget+0x5d L0n4", powershell)
        self.assertEqual(powershell.count("!vprot @rdx;"), 1)
        self.assertNotIn("!address", powershell)
        self.assertNotRegex(powershell, r"!vprot[ \t]*;")
        self.assertIn("[Fileway.PlayerSmoke.DebuggerEvidence]::RegionProgress($log)", powershell)
        self.assertIn("status = 3; phase = [int]$phase", powershell)
        self.assertIn("status = 2; reason = [int]$reason", powershell)
        self.assertIn("$regionError.Data['regionReason'] -ge 2", powershell)
        self.assertIn("$regionError.Data['regionReason'] -le 7", powershell)
        self.assertIn("if ($fault.HelperArgument.Classification -eq 6) { Record-SafeCandidateRegion", powershell)
        self.assertIn("[Math]::Min(30000, [Math]::Max(0, 120000 - $Budget.ElapsedMilliseconds))", powershell)
        self.assertIn("queriedFault.HelperArgument.Classification!=6", native)
        self.assertIn("queryCandidate!=candidate", native)
        self.assertIn("return SingleRegionEvidence.Parse(region,candidate)", native)
        self.assertNotIn(".dump /muf", powershell)
        region_emission = powershell.split("Record-Observation 'shutdown-cfg-region'", 1)[1].split("\n", 1)[0]
        self.assertIn("state = [uint32]$region.State", region_emission)
        self.assertIn("type = [uint32]$region.Type", region_emission)
        self.assertIn("protection = [uint32]$region.Protection", region_emission)
        self.assertNotIn("address", region_emission.lower())
        self.assertIn("FILEWAY_CFG_MODULES_BEGIN; lm; .echo FILEWAY_CFG_MODULES_END", powershell)
        self.assertIn("FILEWAY_CFG_CONTEXT_BEGIN; .ecxr; .echo FILEWAY_CFG_CONTEXT_END", powershell)
        self.assertIn("normalShutdownVerified = (-not $script:shutdownDebuggerAttached)", powershell)
        self.assertIn("normalShutdownVerified = $false", powershell)
        start = powershell.index("$script:stage = 'application-shutdown'")
        self.assertLess(powershell.index("Complete-Step 'close'"), start)
        self.assertLess(powershell.index("Start-ShutdownDebugger", start), powershell.index("$script:process.CloseMainWindow()", start))
        for guard in ("text.Length>1048576", "lines.Length>4096", "raw.Length>2048", "index>23", "offset>uint.MaxValue"):
            self.assertIn(guard, native)
        self.assertIn("Array.Exists(Symbols,name=>name.Equals(symbol,StringComparison.Ordinal))", native)
        self.assertIn('symbol=""; offset=0; kind=0;', native)
        workflow = (ENG.parents[2] / ".github/workflows/windows-player-smoke.yml").read_text(encoding="utf-8")
        self.assertEqual(workflow.count("-CaptureShutdownStack"), 2)
        upload = workflow.split("- name: Preserve only allowlisted synthetic UI evidence", 1)[1]
        for private in (".dmp", "shutdown-stack.log", "symbols", "Fileway-private"):
            self.assertNotIn(private, upload)

    def test_internal_timings_use_existing_numeric_events_not_latency_acceptance(self):
        _, native, powershell = source_parts()
        self.assertIn("[Fileway.PlayerSmoke.ResponsivenessEvidence]::Pair($rows.ToArray())", powershell)
        self.assertIn("if (-not $script:responsivenessTimingsRecorded)", powershell)
        self.assertIn("Record-Observation 'player-internal-timing' @{", powershell)
        self.assertNotIn("Record-Observation 'player-internal-timing' $timing", powershell)
        self.assertIn("rows.Length>512", native)
        self.assertIn("row[0]-previous!=1", native)
        self.assertNotIn("latencyAccepted", powershell)
        app = (ENG.parent / "src/Fileway.App/MediaSurfaces/PlaybackResponsivenessDiagnostics.cs").read_text(encoding="utf-8")
        entries = [line.strip().rstrip(",") for line in app.split("internal enum PlaybackDiagnosticEvent", 1)[1].split("{", 1)[1].split("}", 1)[0].splitlines() if line.strip()]
        self.assertEqual(len(entries), 29)
        for index, name in {2:"FullscreenInput",3:"SettingsInput",5:"NativeFullscreenInput",6:"FullscreenUiEntered",8:"FullscreenPresenterEnd",18:"SettingsActivateEnd"}.items():
            self.assertEqual(entries[index], name)

    def test_shutdown_scenarios_are_explicit_and_cannot_grant_full_acceptance(self):
        _, native, powershell = source_parts()
        self.assertIn("[ValidateSet('Full', 'MinimalPlayback', 'MainWithOpenPlayer')]", powershell)
        self.assertIn("[string] $ShutdownScenario = 'Full'", powershell)
        full = powershell.split("if ($ShutdownScenario -ne 'MinimalPlayback') {", 1)[1].split("if ($ShutdownScenario -ne 'MainWithOpenPlayer') {", 1)[0]
        for name in ("settings-cycle-$iteration", "Complete-Step 'pause'", "Complete-Step 'resume'", "Complete-Step 'fullscreen-button-cycle'", "Complete-Step 'fullscreen-key-cycle'"):
            self.assertIn(name, full)
        self.assertIn("scope = 'shutdown-diagnostic'; scenario = $ShutdownScenario; status = 'passed'; code = 'shutdown_diagnostic_ok'; fullJourneyVerified = $false; nativePlaybackVerified = $false", powershell)
        accepted = powershell.index("code = 'shutdown_diagnostic_ok'")
        for name in ("application_exit_timeout", "application_exit_code_nonzero", "owned_host_exit_timeout", "owned_host_exit_code_nonzero"):
            self.assertLess(powershell.index(name), accepted)
        self.assertIn("if ($ShutdownScenario -eq 'Full') {\n        Write-Json 'report.json'", powershell)
        shutdown = powershell.split("$script:stage = 'application-shutdown'", 1)[1].split("$exitCode = 0", 1)[0]
        self.assertIn("[Fileway.PlayerSmoke.Native]::MainApplicationWindow($script:process.Id) -ne $main", shutdown)
        self.assertIn("[Fileway.PlayerSmoke.Native]::ProcessId($main) -ne [uint32]$script:process.Id", shutdown)
        self.assertIn("[Fileway.PlayerSmoke.Native]::PostMessageW($main, 0x0010", shutdown)
        self.assertIn("elseif (-not $script:process.CloseMainWindow())", shutdown)
        self.assertIn('foreach(var window in Windows(process))', native)
        self.assertIn('throw new InvalidOperationException("main_window_ambiguous")', native)
        self.assertIn('ProcessId(window)!=(uint)process', native)

    def test_artifact_allowlist_contains_only_named_json_and_png(self):
        text, _, _ = source_parts()
        block = re.search(r"\$allowedArtifacts = @\((.*?)\)\n", text, re.S).group(1)
        names = re.findall(r"'([^']+)'", block)
        self.assertEqual(len(names), len(set(names)))
        self.assertEqual(set(names) - {name for name in names if name.endswith(".png")},
                         {"report.json", "events.jsonl", "player-responsiveness.json"})
        self.assertTrue(all(Path(name).name == name for name in names))
        self.assertIn("Join-Path $script:root 'screenshots'", text)

    @unittest.skipUnless(shutil.which("pwsh"), "PowerShell unavailable; no installation attempted")
    def test_actual_script_parses_with_powershell(self):
        path = str(HARNESS).replace("'", "''")
        command = ("$t=$null;$e=$null;[void][System.Management.Automation.Language.Parser]::ParseFile('"
                   + path + "',[ref]$t,[ref]$e);if($e.Count){$e|%{Write-Error $_};exit 1}")
        result = subprocess.run([shutil.which("pwsh"), "-NoProfile", "-Command", command],
                                capture_output=True, text=True, timeout=30)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    @unittest.skipUnless(shutil.which("pwsh"), "PowerShell unavailable; no installation attempted")
    def test_actual_internal_timing_emission_is_numeric_and_once_under_strictmode(self):
        text, native, _ = source_parts()
        copy = "function Copy-SafeDiagnostics {" + text.split("function Copy-SafeDiagnostics {", 1)[1].split("function Assert-SdkDebugger", 1)[0]
        with tempfile.TemporaryDirectory(prefix="Fileway timing emission ") as temporary:
            root = Path(temporary)
            (root / "Native.cs").write_text(native, encoding="utf-8")
            (root / "diagnostics").mkdir()
            report = dict(Format=1, EventsRecorded=2, ContendedWrites=0, HitTests=0, SnapshotApplications=0,
                          ProbePending=False, ProbeAgeMicroseconds=0, Entries=[
                              dict(Sequence=1,ElapsedMicroseconds=100,Event=3,Value=3),
                              dict(Sequence=2,ElapsedMicroseconds=200,Event=18,Value=0)])
            (root / "diagnostics/player-responsiveness.json").write_text(json.dumps(report), encoding="utf-8")
            script = r'''$ErrorActionPreference='Stop'; Set-StrictMode -Version Latest
Add-Type -Path (Join-Path $PSScriptRoot 'Native.cs')
$script:privateRoot=$PSScriptRoot; $script:responsivenessTimingsRecorded=$false
$script:seen=[System.Collections.Generic.List[object]]::new()
function Write-Json([string]$Name,$Value) { if($Name -ne 'player-responsiveness.json'){throw 'wrong report'} }
function Record-Observation([string]$Name,$Value) { $script:seen.Add(@{name=$Name; value=$Value}) }
''' + copy + r'''
$null=Copy-SafeDiagnostics; $null=Copy-SafeDiagnostics
if($script:seen.Count -ne 2){throw 'duplicate timing emission'}
$row=$script:seen[0]
if($row.name -ne 'player-internal-timing' -or $row.value.Count -ne 6 -or $row.value.durationMicroseconds -ne 100){throw 'wrong timing payload'}
foreach($value in $row.value.Values){if($value -isnot [int] -and $value -isnot [long]){throw 'nonnumeric timing'}}
if($script:seen[1].value.status -ne 1 -or $script:seen[1].value.count -ne 1){throw 'wrong timing status'}
$report=Get-Content -Raw (Join-Path $PSScriptRoot 'diagnostics/player-responsiveness.json')|ConvertFrom-Json -AsHashtable
$report.Entries[1].Sequence=1
$report|ConvertTo-Json -Depth 8|Set-Content (Join-Path $PSScriptRoot 'diagnostics/player-responsiveness.json')
$script:seen.Clear(); $script:responsivenessTimingsRecorded=$false
$null=Copy-SafeDiagnostics
if($script:seen.Count -ne 1 -or $script:seen[0].name -ne 'player-internal-timing-result' -or $script:seen[0].value.status -ne 2){throw 'invalid timing accepted'}
'''
            script_path = root / "check.ps1"
            script_path.write_text(script, encoding="utf-8")
            result = subprocess.run([shutil.which("pwsh"), "-NoProfile", "-File", str(script_path)],
                                    cwd=root, capture_output=True, text=True, timeout=45)
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    @unittest.skipUnless(DOTNET, "Optional production C# checks require an already installed .NET SDK")
    # Emulate Windows legacy defaults even when this suite runs on Linux.
    @mock.patch("io.text_encoding", lambda encoding, *args: encoding or "cp1252")
    def test_production_decoder_png_writer_and_shutdown_reader_with_existing_sdk(self):
        with tempfile.TemporaryDirectory(prefix="Fileway Smoke CSharp ", dir=os.environ.get("FILEWAY_MPV_CHECK_TMP")) as temporary:
            root = Path(temporary)
            environment = dict(os.environ, DOTNET_CLI_HOME=str(root / "dotnet-home"), DOTNET_GENERATE_ASPNET_CERTIFICATE="false",
                               DOTNET_CLI_TELEMETRY_OPTOUT="1", DOTNET_NOLOGO="1", DOTNET_SKIP_FIRST_TIME_EXPERIENCE="1")
            sdk = subprocess.run([DOTNET, "--list-sdks"], check=True, capture_output=True, text=True, env=environment, timeout=30).stdout
            majors = [int(value) for value in re.findall(r"(?m)^(\d+)\.", sdk) if int(value) >= 6]
            if not majors:
                self.skipTest("No installed .NET 6+ SDK; no download attempted")
            (root / "Native.cs").write_text(source_parts()[1], encoding="utf-8")
            (root / "Offline.csproj").write_text('<Project Sdk="Microsoft.NET.Sdk"><PropertyGroup><TargetFramework>net' + str(max(majors))
                + '.0</TargetFramework><OutputType>Exe</OutputType><EnableNETAnalyzers>false</EnableNETAnalyzers></PropertyGroup></Project>', encoding="utf-8")
            (root / "NuGet.Config").write_text('<configuration><packageSources><clear /></packageSources></configuration>', encoding="utf-8")
            vectors = []
            for index in (0, 1, 12, 127, 128, 255, 256, 1439, 2159):
                path = root / f"frame-{index}.bgra"
                path.write_bytes(scaled_bgra(index))
                vectors.append(f'Check("{path.name}",480,270,{index});')
            (root / "letterbox.bgra").write_bytes(scaled_bgra(512, 800, 500))
            vectors.append('Check("letterbox.bgra",800,500,512);')
            pixels = scaled_bgra(128)
            # Damaged real-video guards, undecodable counter, and missing marker
            # must fail. A changing text label/black window is never accepted.
            for name, x, y in (("fiducial", 60, 111), ("counter", 150, 135), ("marker", 48, 195)):
                damaged = bytearray(pixels)
                offset = (y * 480 + x) * 4
                damaged[offset:offset + 3] = bytes((100, 100, 100))
                (root / (name + ".bgra")).write_bytes(damaged)
                vectors.append(f'Check("{name}.bgra",480,270,-1);')
            (root / "black.bgra").write_bytes(bytes(480 * 270 * 4))
            vectors.append('Check("black.bgra",480,270,-1);')
            row = '{"schemaVersion":1,"stage":1,"uiThread":0}'
            shutdown_vectors = []

            def shutdown_case(name, content, expected=None):
                directory = root / name / "diagnostics"
                directory.mkdir(parents=True)
                (directory / "shutdown.jsonl").write_bytes(content.encode("utf-8") if isinstance(content, str) else content)
                if expected is None:
                    shutdown_vectors.append(f'RejectShutdown("{name}");')
                else:
                    stages = ",".join(str(stage) for stage, _ in expected)
                    threads = ",".join(str(thread) for _, thread in expected)
                    shutdown_vectors.append(f'CheckShutdown("{name}",new int[]{{{stages}}},new int[]{{{threads}}});')

            all_stages = [(stage, stage % 2) for stage in range(1, 14)]
            shutdown_case("all-stages", "".join(json.dumps(dict(schemaVersion=1, stage=stage, uiThread=thread), separators=(",", ":"))
                                               + "\n" for stage, thread in all_stages), all_stages)
            shutdown_case("reordered-crlf", ' \t{ "uiThread" : 1, "stage" : 9, "schemaVersion" : 1 } \t\r\n', [(9, 1)])
            shutdown_case("capacity", (row + "\n") * 16, [(1, 0)] * 16)
            shutdown_case("byte-boundary", (row.ljust(255) + "\n") * 16, [(1, 0)] * 16)
            shutdown_case("line-boundary", row.ljust(256) + "\n", [(1, 0)])
            close_order = [(stage, 1) for stage in (*range(1, 9), 10, 11, 12, 13, 9)]
            shutdown_case("nested-close-order", "".join(json.dumps(dict(schemaVersion=1, stage=stage, uiThread=thread), separators=(",", ":"))
                                                     + "\n" for stage, thread in close_order), close_order)
            for name, content in {
                "empty": b"", "blank": "\n", "trailing-blank": row + "\n\n",
                "unterminated": row, "partial-final": row + '\n{"schemaVersion":1,',
                "seventeen-lines": (row + "\n") * 17, "large-file": (row.ljust(255) + "\n") * 16 + "\n",
                "large-line": row.ljust(257) + "\n", "bom": b"\xef\xbb\xbf" + row.encode() + b"\n",
                "invalid-utf8": b"\xff\n", "nonobject": "[]\n", "nested": '{"schemaVersion":1,"stage":{},"uiThread":0}\n',
                "private-field": row[:-1] + ',"path":"C:\\\\private"}\n',
                "missing-field": '{"schemaVersion":1,"stage":1}\n',
                "duplicate-field": '{"schemaVersion":1,"stage":1,"stage":2}\n',
                "extra-duplicate": row[:-1] + ',"stage":2}\n',
                "wrong-case": row.replace("stage", "Stage") + "\n",
                "escaped-key": row.replace("stage", r"\u0073tage") + "\n",
                "unknown-key": row.replace("stage", "other") + "\n",
                "leading-text": "private " + row + "\n", "trailing-text": row + " private\n",
                "vertical-tab": "\v" + row + "\n", "embedded-cr": row.replace("1,", "1\r,", 1) + "\n",
            }.items():
                shutdown_case(name, content)
            for key, bad_values in {"schemaVersion": ("0", "2", "10"), "stage": ("0", "14", "-1", '"1"', "true", "null", "1.0", "1e0", "+1", "01", "013", "99", "100"),
                                    "uiThread": ("2", "-1", '"0"', "false", "null", "0.0")}.items():
                current = "0" if key == "uiThread" else "1"
                for index, value in enumerate(bad_values):
                    shutdown_case(f"bad-{key}-{index}", row.replace(f'"{key}":{current}', f'"{key}":{value}') + "\n")
            # Even a valid prefix is discarded when any later record is invalid.
            shutdown_case("invalid-later-row", row + "\n" + row.replace('"uiThread":0', '"uiThread":true') + "\n")
            shutdown_vectors.append('CheckShutdown("missing",Array.Empty<int>(),Array.Empty<int>());')
            shutdown_vectors.append('bool relativeRejected=false; try { ShutdownEvidence.Read("relative"); } catch(InvalidDataException) { relativeRejected=true; } if(!relativeRejected) throw new Exception("relative data root accepted");')
            if os.name != "nt":
                # Windows link creation may need privileges; run portable link
                # rejection checks here without changing host security settings.
                (root / "linked-file" / "diagnostics").mkdir(parents=True)
                (root / "linked-file" / "diagnostics" / "shutdown.jsonl").symlink_to(root / "capacity" / "diagnostics" / "shutdown.jsonl")
                shutdown_vectors.append('RejectShutdown("linked-file");')
                (root / "linked-directory").mkdir()
                (root / "linked-directory" / "diagnostics").symlink_to(root / "capacity" / "diagnostics", target_is_directory=True)
                shutdown_vectors.append('RejectShutdown("linked-directory");')
                (root / "linked-root").symlink_to(root / "capacity", target_is_directory=True)
                shutdown_vectors.append('RejectShutdown("linked-root");')
            debugger_vectors = []
            header = "ExceptionCode: c0000409 (Security check failure or stack buffer overrun)\nParameter[0]: 0000000000000007\n"
            def debugger_case(name, content, expected=None, subcode=7):
                path = f"debugger-{name}.log"
                (root / path).write_bytes(content.encode("utf-8") if isinstance(content, str) else content)
                if expected is None:
                    debugger_vectors.append(f'RejectDebugger("{path}");')
                else:
                    debugger_vectors.append(f'CheckDebugger("{path}",{subcode},{json.dumps(expected)});')

            frame = "00 00000000`0000abcd 00000000`00002110 "
            debugger_case("known-symbol", header + frame + "KERNELBASE!RaiseFailFastException+0x5a\n", "0|KERNELBASE|RaiseFailFastException|90|2")
            debugger_case("known-zero-offset", header + frame + "ntdll!RtlFailFast2\n", "0|ntdll|RtlFailFast2|0|2")
            debugger_case("module-relative", header + frame + "Microsoft_UI_Xaml+0x1234\n", "0|Microsoft_UI_Xaml||4660|1")
            debugger_case("unknown-symbol", header + frame + "Microsoft_UI_Xaml!PrivateSyntheticSecret+0x10\n", "0|Microsoft_UI_Xaml||0|0")
            debugger_case("module-range", header + "ExceptionAddress: 00000000`00001123 (private text)\n" +
                          frame + "Microsoft_UI_Xaml!PrivateSyntheticSecret+0x10\n" +
                          "01 00000000`0000abdd 00000000`00000000 ntdll!PrivateOther+0x8\n" +
                          "00000000`00001000 00000000`00002000 Microsoft_UI_Xaml (pdb symbols) C:\\private\\path\n" +
                          "00000000`00002000 00000000`00003000 ntdll (pdb symbols) C:\\private\\path\n",
                          "0|Microsoft_UI_Xaml||291|1;1|ntdll||272|1")
            debugger_case("outside-range", header + "ExceptionAddress: 9999\n" + frame + "ntdll!Unlisted+0x9\n" +
                          "1000 2000 ntdll (deferred)\n", "0|ntdll||0|0")
            debugger_case("unknown-module", header + frame + "SyntheticPrivateModule!RtlFailFast2+0x1\n", "")
            debugger_case("empty-frames", header, "")
            debugger_case("missing-subcode", "ExceptionCode: c0000409\n", "", -1)
            debugger_case("bad-subcode", header.replace("0000000000000007", "ffffffffff"), "", -1)
            debugger_case("wrong-code", header.replace("c0000409", "c0000005"))
            debugger_case("duplicate-code", header + header)
            debugger_case("missing-code", frame + "ntdll!RtlFailFast2+0x1\n")
            debugger_case("duplicate-parameter", header + "Parameter[0]: a\n")
            debugger_case("parameter-count-mismatch", header + "NumberParameters: 2\n")
            debugger_case("too-many-parameters", header + "NumberParameters: 16\n")
            debugger_case("oversized-parameter", header + "Parameter[1]: 123456789abcdef01\n")
            debugger_case("parameter-hole", header + "NumberParameters: 2\nParameter[2]: 1234\n")
            debugger_case("negative-parameter-count", header + "NumberParameters: -1\n")
            debugger_case("unloaded-cap-exceeded", header + "Unloaded modules:\n" + "2000 3000 libmpv-2.dll\n" * 65)
            debugger_case("invalid-parameter-index", header + "Parameter[15]: 1234\n")
            debugger_case("parameter-private-text", header + "Parameter[1]: private\n")
            debugger_case("duplicate-module", header + "1000 2000 ntdll (deferred)\n1000 2000 ntdll (deferred)\n")
            debugger_case("duplicate-module-marker", header + "FILEWAY_CFG_MODULES_BEGIN\nFILEWAY_CFG_MODULES_BEGIN\nFILEWAY_CFG_MODULES_END\n")
            debugger_case("duplicate-module-end", header + "FILEWAY_CFG_MODULES_BEGIN\nFILEWAY_CFG_MODULES_END\nFILEWAY_CFG_MODULES_END\n")
            debugger_case("missing-module-end", header + "FILEWAY_CFG_MODULES_BEGIN\n1000 2000 ntdll (deferred)\n")
            debugger_case("missing-module-begin", header + "FILEWAY_CFG_MODULES_END\n")
            debugger_case("reversed-module-markers", header + "FILEWAY_CFG_MODULES_END\nFILEWAY_CFG_MODULES_BEGIN\n")
            debugger_case("anonymous-cap-exceeded", header + "FILEWAY_CFG_MODULES_BEGIN\n" + "1000 2000 PrivateLoaded (deferred)\n" * 513 + "FILEWAY_CFG_MODULES_END\n")
            debugger_case("all-unloaded-cap-exceeded", header + "FILEWAY_CFG_MODULES_BEGIN\nUnloaded modules:\n" + "1000 2000 PrivateUnloaded.dll\n" * 65 + "FILEWAY_CFG_MODULES_END\n")

            for index, target in enumerate(("ntdll!C:\\private+0x1", "ntdll!private/path+0x1", "ntdll!private=token+0x1",
                                             'ntdll!"private"+0x1', "ntdll!private%20name+0x1", "ntdll!private\u2603+0x1",
                                             "ntdll!RtlFailFast2+0x100000000", "ntdll!RtlFailFast2+0xZZ", "ntdll+0x1!bad",
                                             "ntdll!RtlFailFast2+0x1 C:\\private", "ntdll", "ntdll!")):
                debugger_case(f"bad-target-{index}", header + frame + target + "\n", "")
            debugger_case("raw-text", header + "private token path C:\\private\n", "")
            debugger_case("long-line", header + frame + "ntdll!" + "x" * 2048 + "\n", "")
            debugger_case("oversized", b"x" * 1048577)
            debugger_case("too-many-lines", header + "\n" * 4096)
            debugger_case("invalid-utf8", header.encode() + b"\xff")
            debugger_case("frame-cap", header + "".join(f"{i:02x} 0000abcd 00001111 ntdll+0x{i:x}\n" for i in range(32)),
                          ";".join(f"{i}|ntdll||{i}|1" for i in range(24)))
            debugger_case("nonsequential", header + "01 0000abcd 00001111 ntdll+0x1\n", "")
            debugger_case("inline-gap", header + frame + "ntdll!RtlFailFast2+0x1\n" +
                          "01 (Inline Function) --------`-------- Microsoft_UI_Xaml!SyntheticPrivateSymbol\n" +
                          "02 0000abcd 00001111 Microsoft_UI_Xaml!OtherPrivate+0x10\n" +
                          "00001000 00003000 Microsoft_UI_Xaml (pdb symbols)\n",
                          "0|ntdll|RtlFailFast2|1|2;2|Microsoft_UI_Xaml||0|0")
            debugger_case("duplicate-frame", header + frame + "ntdll+0x1\n" + frame + "ntdll+0x2\n", "0|ntdll||1|1")
            helper_vectors = []
            helper_prefix = "FILEWAY_CFG_HELPER_BEGIN\nntdll!RtlpHandleInvalidUserCallTarget:\n"
            helper_suffix = "FILEWAY_CFG_HELPER_END\n"
            def helper_case(name, block, expected_status=1, expected="", ranges="1000 3000 ntdll (pdb symbols)\n"):
                path = f"helper-{name}.log"
                (root / path).write_text(header + ranges + block, encoding="utf-8")
                helper_vectors.append(f'CheckHelper("{path}",{expected_status},{json.dumps(expected)});')

            # Real CDB-shaped code text; every string output is reconstructed from
            # a fixed enum. Values/bytes/addresses never become evidence fields.
            helper_case("register-flow", helper_prefix +
                        "00000000`00001100 488bd9 mov rbx,rcx\n"
                        "00000000`00001103 488bd3 mov rdx,rbx\n"
                        "00000000`00001106 b90a000000 mov ecx,0Ah\n"
                        "00000000`0000110b e8f0ffffff call ntdll!RtlFailFast2 (00000000`00002100)\n" + helper_suffix,
                        expected="0|0|mov|1|1|rbx|0|1|rcx|0||0;1|3|mov|1|1|rdx|0|1|rbx|0||0;2|6|mov|1|1|ecx|0|3||0||0;3|11|call|1|4||0|0||0|RtlFailFast2|0")
            helper_case("stack-flow", helper_prefix +
                        "1100 48895c2408 mov qword ptr [rsp+8],rbx\n"
                        "1105 488b4d20 mov rcx,qword ptr [rbp-20h]\n"
                        "1109 50 push rax\n110a 90 nop\n110b c3 ret\n" + helper_suffix,
                        expected="0|0|mov|1|2|rsp|8|1|rbx|0||0;1|5|mov|1|1|rcx|0|2|rbp|-32||0;2|9|push|1|1|rax|0|0||0||0;3|10|nop|1|0||0|0||0||0;4|11|ret|1|0||0|0||0||0")
            helper_case("branch", helper_prefix + "1100 7408 je ntdll!RtlpHandleInvalidUserCallTarget+0xa (00000000`0000110a)\n" + helper_suffix,
                        expected="0|0|je|1|4||0|0||0|RtlpHandleInvalidUserCallTarget|10")
            helper_case("indirect-register", helper_prefix + "1100 ffd0 call rax\n" + helper_suffix,
                        expected="0|0|call|1|1|rax|0|0||0||0")
            helper_case("immediate-private", helper_prefix + "1100 48b88877665544332211 mov rcx,1122334455667788h\n" + helper_suffix,
                        expected="0|0|mov|1|1|rcx|0|3||0||0")
            for index, operand in enumerate(("rcx,C:\\private\\secret", "rcx,PRIVATE_TOKEN", "rcx,rax,SECRET",
                                             'rcx,"sensitive"', "rcx,[1122334455667788h]", "rcx,qword ptr [rsp+1001h]",
                                             "rcx,qword ptr [rsi+8]", "rcx,rax ; private comment", "rcx,r\u2603x")):
                helper_case(f"unknown-operand-{index}", helper_prefix + f"1100 488bc8 mov {operand}\n" + helper_suffix,
                            expected="0|0|mov|0|0||0|0||0||0")
            for index, operand in enumerate(("ntdll!PrivateSecret (00000000`00002100)",
                                             "PrivateModule!RtlFailFast2 (00000000`00002100)",
                                             "ntdll!RtlFailFast2 (00000000`00009900)",
                                             "ntdll!RtlFailFast2+0x2000 (00000000`00001100)",
                                             "ntdll!RtlFailFast2 (private)", "qword ptr [1122334455667788h]")):
                helper_case(f"unknown-target-{index}", helper_prefix + f"1100 e800000000 call {operand}\n" + helper_suffix,
                            expected="0|0|call|0|0||0|0||0||0")
            helper_case("unknown-opcode", helper_prefix + "1100 90 private secret\n" + helper_suffix,
                        expected="0|0||0|0||0|0||0||0")
            maximum = "".join(f"{0x1100+i:x} 90 nop\n" for i in range(24))
            helper_case("capacity", helper_prefix + maximum + helper_suffix,
                        expected=";".join(f"{i}|{i}|nop|1|0||0|0||0||0" for i in range(24)))
            helper_case("absent", "", 0)
            for name, block in {
                "missing-end": helper_prefix + "1100 90 nop\n",
                "missing-begin": "1100 90 nop\n" + helper_suffix,
                "wrong-header": helper_prefix.replace("RtlpHandleInvalidUserCallTarget", "PrivateSecret") + "1100 90 nop\n" + helper_suffix,
                "no-code": helper_prefix + helper_suffix,
                "duplicate-begin": helper_prefix + helper_prefix + helper_suffix,
                "duplicate-end": helper_prefix + "1100 90 nop\n" + helper_suffix * 2,
                "bad-bytes": helper_prefix + "1100 zz nop\n" + helper_suffix,
                "odd-bytes": helper_prefix + "1100 901 nop\n" + helper_suffix,
                "oversized-instruction": helper_prefix + "1100 " + "90" * 16 + " nop\n" + helper_suffix,
                "address-gap": helper_prefix + "1100 90 nop\n1102 90 nop\n" + helper_suffix,
                "duplicate-address": helper_prefix + "1100 90 nop\n1100 90 nop\n" + helper_suffix,
                "outside-module": helper_prefix + "3100 90 nop\n" + helper_suffix,
                "cross-module-end": helper_prefix + "2fff 9090 nop\n" + helper_suffix,
                "too-many-instructions": helper_prefix + maximum + "1118 90 nop\n" + helper_suffix,
                "oversized-line": helper_prefix + "1100 90 mov " + "x" * 2048 + "\n" + helper_suffix,
                "command-echo": "0:000> .echo FILEWAY_CFG_HELPER_BEGIN\n1100 90 nop\n" + helper_suffix,
            }.items():
                helper_case(name, block, 2)
            helper_case("missing-module", helper_prefix + "1100 90 nop\n" + helper_suffix, 2, ranges="")

            tail_vectors = []
            tail_header = "ExceptionCode: c0000409\nNumberParameters: 1\nParameter[0]: a\n"
            tail_frames = ("00 00000000`00008000 00000000`0000115d ntdll!RtlFailFast2\n"
                           "01 00000000`00008020 00000000`00000000 ntdll!RtlpHandleInvalidUserCallTarget+0x5d\n")
            tail_context = ("FILEWAY_CFG_CONTEXT_BEGIN\n"
                            "rax=00000000deadbeef rbx=00000000cafebabe rcx=000000000000000a\n"
                            "rdx=0000000000004234 rsi=0000000000005678 rdi=000000000000abcd\n"
                            "FILEWAY_CFG_CONTEXT_END\n")
            tail_body = ("FILEWAY_CFG_TAIL_BEGIN\nntdll!RtlpHandleInvalidUserCallTarget+0x4e:\n"
                         "114e eb00 jmp ntdll!RtlpHandleInvalidUserCallTarget+0x50 (00000000`00001150)\n"
                         "1150 488bd3 mov rdx,rbx\n1153 b90a000000 mov ecx,0Ah\n"
                         "1158 e8a3000000 call ntdll!RtlFailFast2 (00000000`00001200)\nFILEWAY_CFG_TAIL_END\n")
            tail_prefix = helper_prefix + "1100 53 push rbx\n" + helper_suffix
            tail_ranges = "1000 3000 ntdll (pdb symbols)\nUnloaded modules:\n4000 5000 libmpv-2.dll\n"
            def tail_case(name, tail=tail_body, context=tail_context, frames=tail_frames, header=tail_header,
                          ranges=tail_ranges, prefix=tail_prefix, expected_status=1, expected_argument="", expected_count=4):
                path = f"tail-{name}.log"
                (root / path).write_text(header + frames + "FILEWAY_CFG_MODULES_BEGIN\n" + ranges + "FILEWAY_CFG_MODULES_END\n" + context + prefix + tail, encoding="utf-8")
                tail_vectors.append(f'CheckTail("{path}",{expected_status},{expected_count},{json.dumps(expected_argument)});')

            tail_case("unloaded", expected_argument="2|libmpv_2|564")
            tail_case("loaded", context=tail_context.replace("0000000000004234", "0000000000001234"), expected_argument="1|ntdll|564")
            tail_case("unmapped", context=tail_context.replace("0000000000004234", "1122334455667788"), expected_argument="0||0")
            tail_case("ambiguous", ranges=tail_ranges + "4200 4300 libwinpthread-1.dll\n", expected_argument="0||0")
            tail_case("unknown-loaded-overlap", ranges="1000 3000 ntdll (pdb symbols)\n4200 4300 PrivateLoaded (deferred)\nUnloaded modules:\n4000 5000 libmpv-2.dll\n", expected_argument="0||0")
            tail_case("unknown-unloaded-overlap", ranges=tail_ranges + "4200 4300 PrivateUnloaded.dll\n", expected_argument="0||0")
            tail_case("known-loaded-unknown-overlap", ranges="1000 3000 ntdll (pdb symbols)\n1200 1300 PrivateLoaded (deferred)\n", context=tail_context.replace("0000000000004234", "0000000000001234"), expected_argument="0||0")
            tail_case("unknown-only", ranges="1000 3000 ntdll (pdb symbols)\n4000 5000 PrivateLoaded (deferred)\n", expected_argument="0||0")
            tail_case("private-path-overlap", ranges="1000 3000 ntdll (pdb symbols)\n4000 5000 C:\\private\\name.dll (deferred)\nUnloaded modules:\n4000 5000 libmpv-2.dll\n", expected_argument="0||0")
            tail_case("duplicate-known-unloaded", ranges=tail_ranges + "4000 5000 libmpv-2.dll\n", expected_argument="0||0")
            tail_case("outside-marked-range-ignored", context="4200 4300 PrivateLoaded (deferred)\n" + tail_context, expected_argument="2|libmpv_2|564")
            tail_case("null", context=tail_context.replace("0000000000004234", "0000000000000000"), expected_argument="0||0")
            tail_case("unknown-unloaded-only", ranges="1000 3000 ntdll (pdb symbols)\nUnloaded modules:\n4000 5000 PrivateUnloaded.dll\n", expected_argument="0||0")
            tail_case("missing-context", context="")
            tail_case("missing-register", context=tail_context.replace("rdx=", "zzz="))
            tail_case("duplicate-context", context=tail_context * 2)
            tail_case("duplicate-register", context=tail_context.replace("FILEWAY_CFG_CONTEXT_END", "rdx=0000000000004234 rsi=0000000000005678 rdi=000000000000abcd\nFILEWAY_CFG_CONTEXT_END"))
            tail_case("missing-end-context", context=tail_context.replace("FILEWAY_CFG_CONTEXT_END", ""))
            tail_case("oversized-context", context=tail_context.replace("FILEWAY_CFG_CONTEXT_END", "\n" * 25 + "FILEWAY_CFG_CONTEXT_END"))
            tail_case("oversized-context-line", context=tail_context.replace("FILEWAY_CFG_CONTEXT_END", "x" * 2049 + "\nFILEWAY_CFG_CONTEXT_END"))
            tail_case("private-context", context=tail_context.replace("0000000000004234", "PRIVATE_SECRET_PATH"))
            tail_case("register-annotation", context=tail_context.replace("000000000000abcd", "000000000000abcd PRIVATE_SECRET"))
            tail_case("wrong-subcode", header=tail_header.replace("Parameter[0]: a", "Parameter[0]: 7"))
            tail_case("missing-count", header=tail_header.replace("NumberParameters: 1\n", ""))
            tail_case("wrong-frame", frames=tail_frames.replace("ntdll!RtlFailFast2", "ntdll!RtlFailFast2+0x1"))
            tail_case("wrong-caller-frame", frames=tail_frames.replace("+0x5d", "+0x5e"))
            tail_case("wrong-frame-module", frames=tail_frames.replace("ntdll!RtlFailFast2", "USER32!RtlFailFast2"))
            tail_case("wrong-source", tail=tail_body.replace("rdx,rbx", "rdx,rax"))
            tail_case("wrong-destination", tail=tail_body.replace("rdx,rbx", "rcx,rbx"))
            tail_case("wrong-code", tail=tail_body.replace("ecx,0Ah", "ecx,7"))
            tail_case("private-code", tail=tail_body.replace("ecx,0Ah", "ecx,SECRET"))
            tail_case("wrong-call", tail=tail_body.replace("ntdll!RtlFailFast2", "ntdll!PrivateFunction"))
            tail_case("call-offset", tail=tail_body.replace("ntdll!RtlFailFast2", "ntdll!RtlFailFast2+0x1"))
            tail_case("shifted-tail", tail=tail_body.replace("+0x4e:", "+0x4f:"), expected_status=2, expected_count=0)
            tail_case("wrong-end", tail=tail_body.replace("e8a3000000", "e8a30000"), expected_status=2, expected_count=0)
            tail_case("missing-prefix", prefix="", expected_status=2, expected_count=0)
            tail_case("missing-tail", tail="", expected_status=0, expected_count=0)
            tail_case("duplicate-tail", tail=tail_body * 2, expected_status=2, expected_count=0)
            tail_case("fifth-instruction", tail=tail_body.replace("FILEWAY_CFG_TAIL_END", "115d 90 nop\nFILEWAY_CFG_TAIL_END"), expected_status=2, expected_count=0)


            region_vectors = []
            region_begin, region_end = "FILEWAY_CFG_REGION_BEGIN\n", "FILEWAY_CFG_REGION_END\n"
            region_body = ("BaseAddress: 11223344`55667000\nAllocationBase: 11223344`55667000\n"
                           "AllocationProtect: 00000000\nRegionSize: 00000000`00001000\n"
                           "Type: 00020000MEM_PRIVATE\nState: 00001000MEM_COMMIT\nProtect: 00000020PAGE_EXECUTE_READ\n")
            def region_case(name, text, expected=None, candidate=0x1122334455667788):
                path = f"region-{name}.log"
                (root / path).write_text(text, encoding="utf-8")
                if expected is None:
                    region_vectors.append(f'RejectRegion("{path}",{candidate}UL);')
                else:
                    region_vectors.append(f'CheckRegion("{path}",{candidate}UL,{expected[0]}U,{expected[1]}U,{expected[2]}U);')
            full_region = region_begin + region_body + region_end
            region_case("private-execute", full_region, (0x1000,0x20000,0x20))
            region_case("spacing", full_region.replace("00020000MEM_", "00020000 MEM_").replace("00001000MEM_", "00001000  MEM_").replace("00000020PAGE_", "00000020\tPAGE_"), (0x1000,0x20000,0x20))
            region_case("image", full_region.replace("00020000MEM_PRIVATE", "01000000MEM_IMAGE"), (0x1000,0x1000000,0x20))
            region_case("mapped", full_region.replace("00020000MEM_PRIVATE", "00040000MEM_MAPPED"), (0x1000,0x40000,0x20))
            free = full_region.replace("00001000MEM_COMMIT", "00010000MEM_FREE").replace("00020000MEM_PRIVATE", "00000000").replace("00000020PAGE_EXECUTE_READ", "00000001PAGE_NOACCESS")
            region_case("free-undefined", free, (0x10000,0,0))
            region_case("free-zero", free.replace("00000001PAGE_NOACCESS", "00000000"), (0x10000,0,0))

            # Observed vprot FREE shape omits AllocationProtect and Type; the
            # undefined-field semantics come from MINIDUMP_MEMORY_INFO docs.
            free_shape = free.replace("AllocationProtect: 00000000\n", "").replace("Type: 00000000\n", "")
            region_case("free-observed-five-fields", free_shape, (0x10000,0,0))
            free_minimum = free_shape.replace("AllocationBase: 11223344`55667000\n", "").replace("Protect: 00000001PAGE_NOACCESS\n", "")
            region_case("free-minimum-defined-fields", free_minimum, (0x10000,0,0))
            region_case("free-shape-wrong-commit", free_shape.replace("00010000MEM_FREE", "00001000MEM_COMMIT"))
            region_case("free-shape-wrong-reserve", free_shape.replace("00010000MEM_FREE", "00002000MEM_RESERVE"))
            region_case("free-unknown-allocation", free_shape.replace("AllocationBase: 11223344`55667000", "AllocationBase: PRIVATE_SECRET"))
            region_case("free-unknown-type-present", free_shape.replace(region_end,"Type: 00000000PRIVATE_SECRET\n"+region_end))
            region_case("free-unknown-allocation-protect-present", free_shape.replace(region_end,"AllocationProtect: 00000000PRIVATE_SECRET\n"+region_end))
            region_case("free-duplicate-state", free_shape.replace(region_end,"State: 00010000MEM_FREE\n"+region_end))
            region_case("mixed-regions", free_shape+full_region)

            region_case("free-omitted", free.replace("Type: 00000000\n", "").replace("Protect: 00000001PAGE_NOACCESS\n", ""), (0x10000,0,0))
            reserve = full_region.replace("00001000MEM_COMMIT", "00002000MEM_RESERVE")
            region_case("reserved-undefined", reserve, (0x2000,0x20000,0))
            region_case("reserved-omitted", reserve.replace("Protect: 00000020PAGE_EXECUTE_READ\n", ""), (0x2000,0x20000,0))
            region_case("allocation-protection", full_region.replace("AllocationProtect: 00000000", "AllocationProtect: 00000040 PAGE_EXECUTE_READWRITE"), (0x1000,0x20000,0x20))
            for value, token in ((1,"NOACCESS"),(2,"READONLY"),(4,"READWRITE"),(8,"WRITECOPY"),(0x10,"EXECUTE"),(0x20,"EXECUTE_READ"),(0x40,"EXECUTE_READWRITE"),(0x80,"EXECUTE_WRITECOPY")):
                region_case(f"base-{value}", full_region.replace("00000020PAGE_EXECUTE_READ", f"{value:08X}PAGE_{token}"), (0x1000,0x20000,value))
            for value, tokens in ((0x104,"PAGE_READWRITE | PAGE_GUARD"),(0x204,"PAGE_NOCACHE|PAGE_READWRITE"),(0x440,"PAGE_EXECUTE_READWRITE|PAGE_WRITECOMBINE")):
                region_case(f"modifier-{value}", full_region.replace("00000020PAGE_EXECUTE_READ", f"{value:08x} {tokens}"), (0x1000,0x20000,value))
            for name, bad in {
                "missing-begin": region_body+region_end,
                "missing-end": region_begin+region_body,
                "duplicate-begin": region_begin+full_region,
                "duplicate-end": full_region+region_end,
                "duplicate-region": full_region+full_region,
                "reversed-markers": region_end+region_body+region_begin,
                "unterminated-end": full_region.rstrip("\n"),
                "debugger-error": region_begin+"Unable to read memory\n"+region_body+region_end,
                "unknown-field": full_region.replace(region_end, "Private secret: must not export\n"+region_end),
                "too-many-region-lines": region_begin+"\n"*65+region_body+region_end,
                "oversized-line": full_region.replace("Type: 00020000MEM_PRIVATE", "Type: "+"x"*2049),
                "too-many-lines": "\n"*4096+full_region,
                "oversized-text": "x"*1048577,
                "outside-low": full_region.replace("55667000", "55667789"),
                "outside-high-exclusive": full_region.replace("RegionSize: 00000000`00001000", "RegionSize: 00000000`00000788"),
                "zero-region": full_region.replace("RegionSize: 00000000`00001000", "RegionSize: 0"),
                "overflow-region": full_region.replace("RegionSize: 00000000`00001000", "RegionSize: ffffffff`ffffffff"),
                "private-address": full_region.replace("11223344`55667000", "C:\\private\\secret"),
                "oversized-address": full_region.replace("11223344`55667000", "112233445566778899"),
                "malformed-backtick": full_region.replace("11223344`55667000", "1122`334455667000"),
                "wrong-state-value": full_region.replace("00001000MEM_COMMIT", "00002000MEM_COMMIT"),
                "unknown-state": full_region.replace("MEM_COMMIT", "MEM_PRIVATE_SECRET"),
                "unknown-type": full_region.replace("MEM_PRIVATE", "MEM_PRIVATE_SECRET"),
                "wrong-type-value": full_region.replace("00020000MEM_PRIVATE", "00040000MEM_PRIVATE"),
                "zero-commit-type": full_region.replace("00020000MEM_PRIVATE", "00000000"),
                "reserve-missing-type": reserve.replace("Type: 00020000MEM_PRIVATE\n", ""),
                "free-unknown-protect": free.replace("00000001PAGE_NOACCESS", "PRIVATE_SECRET"),
                "too-many-more-info": full_region.replace(region_end, "More info: private\n"*9+region_end),
                "old-address-fields": full_region.replace("BaseAddress:", "Base Address:"),
                "private-allocation": full_region.replace("AllocationBase: 11223344`55667000", "AllocationBase: PRIVATE_SECRET"),
                "private-allocation-protect": full_region.replace("AllocationProtect: 00000000", "AllocationProtect: PRIVATE_SECRET"),
            }.items():
                region_case(name, bad)
            for field in ("BaseAddress", "AllocationBase", "AllocationProtect", "RegionSize", "State", "Type", "Protect"):
                line = next(line for line in region_body.splitlines(True) if line.startswith(field+":"))
                region_case("duplicate-"+field.replace(" ","-"), full_region.replace(region_end,line+region_end))
                region_case("missing-"+field.replace(" ","-"), full_region.replace(line,""))
            for index, value in enumerate(("00000000", "00000020 PAGE_EXECUTE_READ|PAGE_EXECUTE_READ", "00000024 PAGE_EXECUTE_READ|PAGE_READWRITE",
                "00000100 PAGE_GUARD", "00000101 PAGE_NOACCESS|PAGE_GUARD", "00000201 PAGE_NOACCESS|PAGE_NOCACHE",
                "00000401 PAGE_NOACCESS|PAGE_WRITECOMBINE", "00000304 PAGE_READWRITE|PAGE_GUARD|PAGE_NOCACHE",
                "00000504 PAGE_READWRITE|PAGE_GUARD|PAGE_WRITECOMBINE", "00000604 PAGE_READWRITE|PAGE_NOCACHE|PAGE_WRITECOMBINE",
                "40000020 PAGE_EXECUTE_READ|PAGE_TARGETS_INVALID", "00000020 PAGE_PRIVATE_SECRET", "00000040 PAGE_EXECUTE_READ",
                "00000020 PAGE_EXECUTE_READ ; private", "00000020PAGE_EXECUTE_READ|", "100000020 PAGE_EXECUTE_READ")):
                region_case(f"bad-protect-{index}", full_region.replace("00000020PAGE_EXECUTE_READ",value))
            region_case("zero-candidate", full_region, candidate=0)
            # Exercise the production file reader and revalidation, not a replica.
            verified = (root / "tail-unmapped.log").read_text(encoding="utf-8")
            (root / "region-bound.log").write_text(verified + full_region, encoding="utf-8")
            region_vectors.append('CheckBoundRegion("tail-unmapped.log","region-bound.log",true);')
            for name, changed in {
                "different-context": verified.replace("1122334455667788","1122334455667789"),
                "duplicate-context": verified + tail_context,
                "changed-helper-flow": verified.replace("rdx,rbx","rdx,rax"),
                "wrong-fault-frame": verified.replace("ntdll!RtlFailFast2\n","ntdll!RtlFailFast2+0x1\n"),
                "wrong-exception": verified.replace("c0000409","c0000005"),
                "wrong-subcode": verified.replace("Parameter[0]: a","Parameter[0]: 7"),
                "candidate-now-in-module": verified.replace("Unloaded modules:", "1122334455667000 1122334455668000 UnknownPrivate (deferred)\nUnloaded modules:"),
                "missing-query-validation": tail_context.replace("0000000000004234","1122334455667788"),
            }.items():
                path = f"region-bound-{name}.log"
                (root / path).write_text(changed+full_region, encoding="utf-8")
                region_vectors.append(f'CheckBoundRegion("tail-unmapped.log","{path}",false);')
            region_vectors.append('CheckBoundRegion("tail-wrong-source.log","region-bound.log",false);')
            region_vectors.append('CheckBoundRegion("tail-loaded.log","region-bound.log",false);')
            region_vectors.append('CheckBoundRegion("tail-loaded.log","region-bound.log",false,2);')
            region_vectors.append('CheckBoundRegion("tail-unmapped.log","region-bound-wrong-subcode.log",false,3);')
            region_vectors.append('CheckBoundRegion("tail-unmapped.log","region-bound-different-context.log",false,4);')
            for name, reason in (("missing-State",5),("overflow-region",6),("unknown-state",7)):
                region_vectors.append(f'CheckRegionReason("region-{name}.log",{reason});')


            progress_markers = ("FILEWAY_CFG_CONTEXT_BEGIN", "FILEWAY_CFG_CONTEXT_END", "FILEWAY_CFG_TAIL_BEGIN",
                                "FILEWAY_CFG_TAIL_END", "FILEWAY_CFG_REGION_BEGIN", "FILEWAY_CFG_REGION_END")
            def progress_case(name, content, expected):
                path = f"region-progress-{name}.log"
                (root / path).write_text(content, encoding="utf-8")
                region_vectors.append(f'CheckRegionProgress("{path}",{expected});')
            for index in range(7):
                progress_case(str(index), "\n".join(progress_markers[:index])+"\n", index)
            progress_case("private", "private SECRET 1122334455667788\n"+"\n".join(progress_markers)+"\n", 6)
            progress_case("echo", "0:000> .echo FILEWAY_CFG_CONTEXT_BEGIN\n", 0)
            progress_case("duplicate", progress_markers[0]+"\n"+progress_markers[0]+"\n", 0)
            progress_case("out-of-order", progress_markers[1]+"\n", 0)
            progress_case("missing-prior", progress_markers[0]+"\n"+progress_markers[2]+"\n", 0)
            progress_case("unterminated", progress_markers[0], 0)
            progress_case("long-unterminated", "\n".join(progress_markers)+"\n"+"x"*2049, 0)
            progress_case("long-line", "x"*2049+"\n"+"\n".join(progress_markers)+"\n", 0)
            progress_case("line-cap", "\n"*4096+"\n".join(progress_markers)+"\n", 0)


            (root / "Program.cs").write_text('''using System; using System.IO; using Fileway.PlayerSmoke;
class Program {
 static void Check(string path,int width,int height,int expected) {
  int actual=new Image(width,height,File.ReadAllBytes(path)).DecodeFrame(2160);
  if(actual!=expected) throw new Exception(path+": expected "+expected+", actual "+actual);
 }
 static void CheckShutdown(string root,int[] stages,int[] threads) {
  var records=ShutdownEvidence.Read(Path.GetFullPath(root));
  if(records.Length!=stages.Length) throw new Exception(root+": wrong record count");
  for(int index=0;index<records.Length;index++) {
   var record=records[index];
   if(record.SchemaVersion!=1||record.Stage!=stages[index]||record.UiThread!=threads[index]) throw new Exception(root+": wrong numeric payload");
   var fields=record.GetType().GetFields();
   if(fields.Length!=3) throw new Exception("unexpected evidence fields");
   foreach(var field in fields) if(field.FieldType!=typeof(int)) throw new Exception("nonnumeric evidence field");
  }
 }
 static void RejectShutdown(string root) {
  bool rejected=false;
  try { ShutdownEvidence.Read(Path.GetFullPath(root)); }
  catch(InvalidDataException) { rejected=true; }
  catch(System.Text.DecoderFallbackException) { rejected=true; }
  if(!rejected) throw new Exception(root+": invalid evidence accepted");
 }
 static void CheckDebugger(string path,int subcode,string expected) {
  var evidence=DebuggerEvidence.Read(path);
  if(evidence.ExceptionCode!=0xc0000409||evidence.FastFailSubcode!=subcode) throw new Exception(path+": wrong fault");
  var rows=new System.Collections.Generic.List<string>();
  foreach(var frame in evidence.Frames) rows.Add(frame.Index+"|"+frame.Module+"|"+frame.Symbol+"|"+frame.Offset+"|"+frame.OffsetKind);
  if(string.Join(";",rows)!=expected) throw new Exception(path+": wrong allowlisted frames "+string.Join(";",rows));
 }
 static void CheckHelper(string path,int status,string expected) {
  var fault=DebuggerEvidence.Read(path);
  if(fault.HelperStatus!=status) throw new Exception(path+": wrong helper status "+fault.HelperStatus);
  var rows=new System.Collections.Generic.List<string>();
  foreach(var row in fault.Instructions) rows.Add(row.Index+"|"+row.Offset+"|"+row.Opcode+"|"+row.Decoded+"|"+
   row.FirstKind+"|"+row.FirstRegister+"|"+row.FirstDisplacement+"|"+row.SecondKind+"|"+row.SecondRegister+"|"+row.SecondDisplacement+"|"+row.TargetSymbol+"|"+row.TargetOffset);
  if(string.Join(";",rows)!=expected) throw new Exception(path+": incorrect helper evidence "+string.Join(";",rows));
 }
 static void CheckTail(string path,int status,int count,string expectedArgument) {
  var fault=DebuggerEvidence.Read(path);
  if(fault.TailStatus!=status||fault.TailInstructions.Length!=count) throw new Exception(path+": incorrect tail status/count");
  string actual="";
  if(fault.HelperArgument!=null) actual=fault.HelperArgument.Mapping+"|"+fault.HelperArgument.Module+"|"+fault.HelperArgument.Offset;
  if(actual!=expectedArgument) throw new Exception(path+": incorrect candidate argument "+actual);
  if(count==4&&(fault.TailInstructions[0].Offset!=0x4e||fault.TailInstructions[3].Offset!=0x58)) throw new Exception("incorrect tail-relative offsets");
 }
 static void CheckCandidateClassifications() {
  foreach(var item in new (string Name,int Expected)[]{("unloaded",3),("loaded",2),("unmapped",6),("ambiguous",0),
    ("null",1),("unknown-only",4),("unknown-unloaded-only",5),("unknown-loaded-overlap",0),("unknown-unloaded-overlap",0),
    ("known-loaded-unknown-overlap",0),("private-path-overlap",0),("duplicate-known-unloaded",0)}) {
   var argument=DebuggerEvidence.Read("tail-"+item.Name+".log").HelperArgument;
   if(argument==null||argument.Classification!=item.Expected) throw new Exception("incorrect candidate classification "+item.Name);
   if(argument.Classification!=2&&argument.Classification!=3&&(argument.Mapping!=0||argument.Module!=""||argument.Offset!=0)) throw new Exception("nonunique candidate leaked mapping");
  }
 }
 static void RejectDebugger(string path) {
  bool rejected=false;
  try { DebuggerEvidence.Read(path); }
  catch(InvalidDataException) { rejected=true; }
  catch(System.Text.DecoderFallbackException) { rejected=true; }
  if(!rejected) throw new Exception(path+": invalid debugger evidence accepted");
 }
 static void CheckDebuggerParameterMapping() {
  const string header="ExceptionCode: c0000409\\nNumberParameters: 4\\nParameter[0]: a\\nParameter[1]: 1123\\nParameter[2]: 2234\\nParameter[3]: 9900\\n";
  var fault=DebuggerEvidence.Parse(header+"1000 2000 Microsoft_UI_Xaml (pdb symbols)\\nUnloaded modules:\\n2000 3000 libmpv-2.dll\\n9000 a000 PrivateModule\\n");
  if(fault.ParameterCount!=4||fault.Parameters.Length!=3||fault.FastFailSubcode!=10) throw new Exception("wrong parameter metadata");
  var loaded=fault.Parameters[0]; var unloaded=fault.Parameters[1]; var unknown=fault.Parameters[2];
  if(loaded.Index!=1||loaded.Mapping!=1||loaded.Module!="Microsoft_UI_Xaml"||loaded.Offset!=0x123) throw new Exception("wrong loaded module mapping");
  if(unloaded.Index!=2||unloaded.Mapping!=2||unloaded.Module!="libmpv_2"||unloaded.Offset!=0x234) throw new Exception("wrong unloaded module mapping");
  if(unknown.Index!=3||unknown.Mapping!=0||unknown.Module!=""||unknown.Offset!=0) throw new Exception("private parameter leaked");
  var ambiguous=DebuggerEvidence.Parse(header+"1000 3000 ntdll (pdb symbols)\\nUnloaded modules:\\n1000 3000 libmpv_2\\n");
  foreach(var parameter in ambiguous.Parameters) if(parameter.Mapping!=0||parameter.Module!=""||parameter.Offset!=0) throw new Exception("ambiguous target exported");
  var missing=DebuggerEvidence.Parse("ExceptionCode: c0000409\\nParameter[0]: a\\nParameter[1]: 1234\\n1000 2000 ntdll (pdb symbols)\\n");
  if(missing.ParameterCount!=-1||missing.Parameters.Length!=0) throw new Exception("missing parameter metadata invented a target");
  var privateAlias=DebuggerEvidence.Parse(header+"1000 2000 C:\\\\private\\\\ntdll.dll (pdb symbols)\\nUnloaded modules:\\n2000 3000 SyntheticPrivate.dll\\n");
  foreach(var parameter in privateAlias.Parameters) if(parameter.Mapping!=0||parameter.Module!=""||parameter.Offset!=0) throw new Exception("path or private module alias accepted");
  var hiddenOverlap=DebuggerEvidence.Parse(header+"1000 2000 Microsoft_UI_Xaml (pdb symbols)\\n1000 2000 PrivateLoaded (deferred)\\nUnloaded modules:\\n2000 3000 libmpv-2.dll\\n2000 3000 PrivateUnloaded.dll\\n");
  foreach(var parameter in hiddenOverlap.Parameters) if(parameter.Mapping!=0||parameter.Module!=""||parameter.Offset!=0) throw new Exception("anonymous overlap assigned a known module");
  var maximum="ExceptionCode: c0000409\\nNumberParameters: 15\\nParameter[0]: a\\n";
  for(int i=1;i<15;i++) maximum+="Parameter["+i+"]: "+(0x1000+i).ToString("x")+"\\n";
  var bounded=DebuggerEvidence.Parse(maximum+"1000 2000 msctf.dll (pdb symbols)\\n");
  if(bounded.Parameters.Length!=14) throw new Exception("maximum parameter count lost");
  foreach(var parameter in bounded.Parameters) if(parameter.Mapping!=1||parameter.Module!="msctf"||parameter.Offset!=(ulong)parameter.Index) throw new Exception("incorrect bounded mapping");
  var single=DebuggerEvidence.Parse("ExceptionCode: c0000409\\nNumberParameters: 1\\nParameter[0]: a\\n");
  if(single.ParameterCount!=1||single.Parameters.Length!=0) throw new Exception("invented invalid call target");
 }

 static void CheckRegionReason(string path,int reason) {
  bool rejected=false; try { SingleRegionEvidence.Parse(File.ReadAllText(path),0x1122334455667788); }
  catch(InvalidDataException error) { rejected=true; if(error.Message!="region_evidence_rejected"||!Equals(error.Data["regionReason"],reason)) throw new Exception("incorrect region reason"); }
  if(!rejected) throw new Exception("missing region rejection");
 }
 static void CheckRegionProgress(string path,int expected) {
  if(DebuggerEvidence.RegionProgress(path)!=expected) throw new Exception("incorrect region progress enum");
 }
 static void CheckRegion(string path,ulong candidate,uint state,uint type,uint protection) {
  var region=SingleRegionEvidence.Parse(File.ReadAllText(path),candidate);
  if(region.State!=state||region.Type!=type||region.Protection!=protection) throw new Exception("incorrect region enums");
  if(region.GetType().GetFields().Length!=3) throw new Exception("unexpected region fields");
  foreach(var field in region.GetType().GetFields()) if(field.FieldType!=typeof(uint)) throw new Exception("nonnumeric region field");
 }
 static void RejectRegion(string path,ulong candidate) {
  bool rejected=false; try { SingleRegionEvidence.Parse(File.ReadAllText(path),candidate); }
  catch(InvalidDataException error) { rejected=true; if(error.Message!="region_evidence_rejected") throw new Exception("unsafe region rejection"); }
  if(!rejected) throw new Exception("invalid region accepted: "+path);
 }
 static void CheckBoundRegion(string first,string second,bool expected,int reason=0) {
  bool accepted=false;
  try { var region=DebuggerEvidence.ReadCandidateRegion(first,second); accepted=region.State==0x1000&&region.Type==0x20000&&region.Protection==0x20; }
  catch(InvalidDataException error) {
   if(error.Message!="region_evidence_rejected"||!(error.Data["regionReason"] is int actual)||actual<2||actual>7) throw new Exception("unsafe region reason");
   if(reason!=0&&actual!=reason) throw new Exception("incorrect bound region reason");
  }
  if(accepted!=expected) throw new Exception("incorrect bound region validation: "+second);
 }

 static void CheckMainWindowCandidate() {
  foreach(string title in new string[]{"Fileway","栖卷","fileway","FILEWAY","private video · Fileway","Fileway playback controls","",null})
   foreach(bool owned in new[]{false,true}) foreach(bool hasVideo in new[]{false,true}) {
    bool expected=!owned&&!hasVideo&&(title=="Fileway"||title=="栖卷");
    if(Native.IsMainApplicationCandidate(title,owned,hasVideo)!=expected) throw new Exception("incorrect main-window classification");
   }
 }
 static void CheckInternalTimings() {
  long[] Row(long sequence,long time,int kind,long value=0) => new long[]{sequence,time,kind,value};
  string Read(params long[][] rows) {
   var values=ResponsivenessEvidence.Pair(rows); var text=new System.Collections.Generic.List<string>();
   foreach(var value in values) {
    foreach(var field in value.GetType().GetFields()) if(field.FieldType!=typeof(int)&&field.FieldType!=typeof(long)) throw new Exception("nonnumeric timing field");
    text.Add(value.Metric+"|"+value.StartEvent+"|"+value.Value+"|"+value.StartSequence+"|"+value.EndSequence+"|"+value.DurationMicroseconds);
   }
   return string.Join(";",text);
  }
  void Equal(string expected,params long[][] rows) { if(Read(rows)!=expected) throw new Exception("incorrect timing pair"); }
  void Reject(params long[][] rows) {
   bool rejected=false; try { ResponsivenessEvidence.Pair(rows); } catch(InvalidDataException) { rejected=true; }
   if(!rejected) throw new Exception("invalid timing data accepted");
  }
  Equal("1|3|6|1|3|150",Row(1,100,3,6),Row(2,101,4),Row(3,250,18));
  Equal("2|2|1|1|4|80;3|6|1|2|4|70",Row(1,100,2),Row(2,110,6,1),Row(3,120,7,1),Row(4,180,8,1));
  Equal("2|5|0|1|3|80;3|6|0|2|3|70",Row(1,100,5),Row(2,110,6),Row(3,180,8));
  Equal("3|6|1|7|8|100",Row(7,100,6,1),Row(8,200,8,1));
  Equal("",Row(1,100,2),Row(2,200,8,1)); // Missing UI-entry must not bridge an unrelated action.
  Equal("",Row(1,100,6,1),Row(2,200,8));
  Equal("",Row(1,100,2),Row(2,110,6,1),Row(3,200,8));
  Equal("",Row(1,100,3,3));
  Equal("",Row(1,200,18));
  Equal("",Row(1,100,3,3),Row(3,200,18)); // Lost ring entry.
  Equal("",Row(1,100,3,3),Row(2,120,3,3),Row(3,200,18));
  Equal("1|3|3|4|5|70",Row(1,100,3,3),Row(2,120,3,3),Row(3,200,18),Row(4,250,3,3),Row(5,320,18));
  Equal("3|6|1|3|4|60",Row(1,100,2),Row(2,110,5),Row(3,120,6,1),Row(4,180,8,1));
  Equal("",Row(1,100,2),Row(2,110,6,1),Row(3,120,6,1),Row(4,180,8,1));
  Equal("",Row(1,100,3,3),Row(2,90,18));
  Equal("1|3|3|10|11|0",Row(10,100,3,3),Row(11,100,18));
  Equal("",Row(1,100,2),Row(3,110,6),Row(5,200,8));
  Equal("1|3|3|1|3|100;2|2|1|2|5|150;3|6|1|4|5|30",Row(1,100,3,3),Row(2,110,2),Row(3,200,18),Row(4,230,6,1),Row(5,260,8,1));
  Reject(null); Reject(new long[3]); Reject(Row(0,0,3,3)); Reject(Row(1,-1,3,3));
  Reject(Row(1,100,3,0)); Reject(Row(1,100,3,9)); Reject(Row(1,100,2,1)); Reject(Row(1,100,5,1));
  Reject(Row(1,100,6,2)); Reject(Row(1,100,8,2)); Reject(Row(1,100,18,1)); Reject(Row(1,100,29));
  Reject(Row(1,100,3,3),Row(1,110,18)); Reject(Row(2,100,3,3),Row(1,110,18));
  Reject(Row(1,100,3,3),Row(2,110,18),Row(3,120,-1)); // No valid prefix on bad suffix.
  var capacity=new long[512][];
  for(int i=0;i<capacity.Length;i++) capacity[i]=Row(i+1,i,i%2==0?3:18,i%2==0?3:0);
  if(ResponsivenessEvidence.Pair(capacity).Length!=256) throw new Exception("timing capacity lost");
  Reject(new long[513][]);
 }
 static void CheckDebuggerReady() {
  const string path="shared-debugger-ready.log";
  using(var writer=new FileStream(path,FileMode.CreateNew,FileAccess.Write,FileShare.ReadWrite)) {
   var falseMarker=System.Text.Encoding.UTF8.GetBytes("0:000> .echo FILEWAY_SHUTDOWN_DEBUGGER_READY\\r\\n");
   writer.Write(falseMarker); writer.Flush();
   if(DebuggerEvidence.HasReadyMarker(path)) throw new Exception("command echo accepted as ready");
   var actualMarker=System.Text.Encoding.UTF8.GetBytes("FILEWAY_SHUTDOWN_DEBUGGER_READY");
   writer.Write(actualMarker); writer.Flush();
   if(DebuggerEvidence.HasReadyMarker(path)) throw new Exception("unterminated ready marker accepted");
   writer.Write(new byte[]{13,10}); writer.Flush();
   if(!DebuggerEvidence.HasReadyMarker(path)) throw new Exception("shared writer ready marker missing");
  }
 }
 static void Main() { CheckMainWindowCandidate(); CheckCandidateClassifications(); CheckInternalTimings(); CheckDebuggerReady(); CheckDebuggerParameterMapping(); ''' + "".join(vectors + shutdown_vectors + debugger_vectors + helper_vectors + tail_vectors + region_vectors) + '''
  new Image(480,270,File.ReadAllBytes("frame-128.bgra")).SavePng("verified.png");
  bool refused=false; try { new Image(480,270,File.ReadAllBytes("frame-128.bgra")).SavePng("verified.png"); } catch(IOException) { refused=true; }
  if(!refused) throw new Exception("PNG writer overwrote an existing destination");
  Console.WriteLine("14 pixel vectors and no-clobber PNG writer passed");
 }
}''', encoding="utf-8")
            self.assertIn("栖卷".encode("utf-8"), (root / "Program.cs").read_bytes())
            commands = ([DOTNET, "restore", "Offline.csproj", "--configfile", "NuGet.Config", "--verbosity", "quiet"],
                        [DOTNET, "run", "--project", "Offline.csproj", "--no-restore", "--verbosity", "quiet"])
            for command in commands:
                result = subprocess.run(command, cwd=root, env=environment, capture_output=True, text=True, timeout=90)
                self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            png = (root / "verified.png").read_bytes()
            self.assertEqual(png[:8], b"\x89PNG\r\n\x1a\n")
            offset, compressed = 8, bytearray()
            while offset < len(png):
                size = struct.unpack_from(">I", png, offset)[0]
                kind, block = png[offset + 4:offset + 8], png[offset + 8:offset + 8 + size]
                crc = struct.unpack_from(">I", png, offset + 8 + size)[0]
                self.assertEqual(crc, zlib.crc32(kind + block))
                if kind == b"IHDR":
                    self.assertEqual(struct.unpack(">IIBBBBB", block), (480, 270, 8, 2, 0, 0, 0))
                if kind == b"IDAT":
                    compressed.extend(block)
                offset += 12 + size
            raw = zlib.decompress(compressed)
            self.assertEqual(len(raw), 270 * (1 + 480 * 3))
            self.assertTrue(all(raw[y * (1 + 480 * 3)] == 0 for y in range(270)))
            source = scaled_bgra(128)
            expected = bytearray()
            for y in range(270):
                expected.append(0)
                for x in range(480):
                    position = (y * 480 + x) * 4
                    expected.extend(reversed(source[position:position + 3]))
            self.assertEqual(raw, expected)


if __name__ == "__main__":
    unittest.main()
