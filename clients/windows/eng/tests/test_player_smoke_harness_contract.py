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
            self.assertLess(block.index(entered), block.index(returned))
            if "Dispose();" in block:
                self.assertLess(block.index(entered), block.index("Dispose();"))
                self.assertLess(block.index("Dispose();"), block.index(returned))
            else:
                self.assertIn("only records the boundary", block)
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
        self.assertEqual(powershell.count("; lm o;"), 1)
        self.assertIn(".echo FILEWAY_CFG_PRE_CLOSE_MODULES_BEGIN; lm o; .echo FILEWAY_CFG_PRE_CLOSE_MODULES_END", powershell)
        self.assertNotIn("'lm o'", powershell)
        self.assertIn('"FILEWAY_CFG_PRE_CLOSE_MODULES_BEGIN"', native)

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
        self.assertIn("if ($region.State -eq 65536)", powershell)
        self.assertIn("ReadPreCloseMapping($StackLog, $log, $snapshot)", powershell)
        self.assertIn("throw PreCloseFailure(1)", native)
        self.assertIn("status = 2; reason = [int]$snapshotReason", powershell)
        self.assertIn("debugger_app_identity_changed", powershell)
        self.assertIn("ReadCandidateRegion(stackPath,regionPath).State!=0x10000", native)
        snapshot_export = powershell.split("Record-Observation 'shutdown-cfg-pre-close-module'", 1)[1].split("\n", 1)[0]
        self.assertNotIn("classification", snapshot_export)
        self.assertNotIn("address", snapshot_export.lower())

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

    def test_caller_diagnostic_is_dump_only_bound_and_does_not_promote_helper_argument(self):
        _, native, powershell = source_parts()
        capture = powershell.split("function Record-SafeCallerEvidence", 1)[1].split("function Record-SafeDebuggerEvidence", 1)[0]
        self.assertIn("[Fileway.PlayerSmoke.DebuggerEvidence]::CallerQuery($StackLog)", capture)
        self.assertIn("[Fileway.PlayerSmoke.DebuggerEvidence]::ReadCaller($StackLog, $log)", capture)
        self.assertIn("'-z', $Dump", capture)
        self.assertNotIn("'-p'", capture)
        self.assertIn("120000 - $Budget.ElapsedMilliseconds", capture)
        self.assertIn("30000", capture)
        self.assertIn("currentInstruction-callerRange[0]==offset", native)
        self.assertIn("(caller||dispatch)&&next!=expectedBase", native)
        self.assertIn("delta==0&&destination==expectedTarget", native)
        self.assertIn("candidate!=queriedCandidate", native)
        self.assertIn('" L0n16; .echo FILEWAY_CFG_CALLER_END"', native)
        self.assertIn("call.TargetSymbol==\"RtlpHandleInvalidUserCallTarget\"", native)
        self.assertIn("Record-SafeCallerEvidence $dump $log $symbols $analysisBudget", powershell)
        exported = capture.split("Record-Observation 'shutdown-cfg-caller'", 2)[2].split("foreach ($instruction", 1)[0]
        self.assertNotIn("HelperArgument", exported)
        self.assertNotIn("targetAddress", capture)
        self.assertNotIn("MEM_FREE", capture)
        self.assertNotIn("bu ", capture)
        self.assertNotIn("bp ", capture)
        self.assertIn(".dump /muF /o shutdown.dmp; gn", powershell)

    def test_saved_arguments_query_is_three_dump_slots_and_exports_only_fixed_integers(self):
        _, native, powershell = source_parts()
        capture = powershell.split('function Record-SafeSavedArguments', 1)[1].split('function Record-SafeCallerEvidence', 1)[0]
        self.assertIn("'-z', $Dump", capture)
        self.assertNotIn("'-p'", capture)
        self.assertIn('120000 - $Budget.ElapsedMilliseconds', capture)
        self.assertIn('SavedArgumentsQuery($StackLog, $CallerLog)', capture)
        self.assertIn('ReadSavedArguments($StackLog, $CallerLog, $log', capture)
        self.assertIn('" L0n3; .echo FILEWAY_CFG_SAVED_ARGS_END"', native)
        self.assertIn('values[0]!=candidate', native)
        self.assertIn('fault.CallerStackPointer!=queried.CallerStackPointer', native)
        self.assertIn('encodedValue!=allocation', native)
        self.assertIn('code.Count!=16', native)
        self.assertIn('ProcessId($Window) -eq $script:process.Id', powershell)
        self.assertIn('$script:process.StartTime.ToUniversalTime().Ticks -eq $script:appStartTicks', powershell)
        exported = capture.split("Record-Observation 'shutdown-cfg-saved-arguments'", 2)[2].split('\n', 1)[0]
        for field in ('status', 'targetMatches', 'windowRole', 'messageKind'):
            self.assertIn(field + ' = [int]', exported)
        for forbidden in ('address', 'HWND', 'candidate', 'register', 'targetValue', 'raw'):
            self.assertNotIn(forbidden, exported)
        self.assertNotIn('SetWindow', capture)
        self.assertNotIn('Invoke-WinApp', capture)

    def test_dispatch_probe_is_same_dump_bounded_and_serializes_symbol_ids(self):
        _, native, powershell = source_parts()
        capture = powershell.split('function Record-SafeDispatchEvidence', 1)[1].split('function Record-SafeDebuggerEvidence', 1)[0]
        self.assertIn("'-z', $Dump", capture)
        self.assertNotIn("'-p'", capture)
        self.assertNotIn('Invoke-WinApp', capture)
        self.assertIn('120000 - $Budget.ElapsedMilliseconds', capture)
        self.assertIn('ReadDispatch($StackLog, $log)', capture)
        self.assertIn('Record-SafeDispatchEvidence $dump $log $symbols $analysisBudget', powershell)
        self.assertIn('var caller=ReadCaller(stackPath,dispatchPath)', native)
        self.assertIn('ReadSavedArguments(stackPath,dispatchPath,dispatchPath,0,0,0)', native)
        self.assertIn('SavedArgumentsQuery($StackLog, $callerLog)', capture)
        self.assertIn('DispatchPcs', native)
        self.assertIn('Guid.TryParseExact', native)
        self.assertIn('symbolAddress!=range[0]+begin', native)
        self.assertIn('DispatchSymbolId(symbol)', native)
        self.assertIn('symbolId = [int]$frame.SymbolId', capture)
        self.assertIn('rsdsGuid = [string]$module.GuidHex', capture)
        self.assertIn('targetRva = [uint32]$instruction.TargetOffset', capture)
        self.assertIn("'-sins', '-noshell'", capture)
        self.assertIn('[Math]::Min(30000', capture)
        self.assertIn('$start.RedirectStandardOutput = $true; $start.RedirectStandardError = $true', capture)
        for forbidden in ('[string]$frame.Symbol', '[string]$instruction.TargetSymbol', 'Write-Host', 'Get-Credential', '.reload /i'):
            self.assertNotIn(forbidden, capture)

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


            caller_vectors = []
            caller_frames = tail_frames.replace("00000000`00000000 ntdll!RtlpHandleInvalidUserCallTarget", "00000000`00001308 ntdll!RtlpHandleInvalidUserCallTarget") + "02 00000000`00008040 00000000`00002100 ntdll+0x308\n"
            caller_prefix = helper_prefix + ("1100 4053 push rbx\n1102 4883ec30 sub rsp,30h\n"
                                              "1106 833d0000000000 cmp dword ptr [ntdll!PRIVATE_SECRET],0\n"
                                              "110d 488bd9 mov rbx,rcx\n") + helper_suffix
            caller_original = tail_header + caller_frames + "FILEWAY_CFG_MODULES_BEGIN\n" + tail_ranges + "FILEWAY_CFG_MODULES_END\n" + tail_context + caller_prefix + tail_body
            (root / "caller-original.log").write_text(caller_original, encoding="utf-8")
            caller_block = ("FILEWAY_CFG_CALLER_BEGIN\nntdll!PRIVATE_SECRET+0x1:\n"
                            "1300 488bc8 mov rcx,rax\n"
                            "1303 e8f8fdffff call ntdll!RtlpHandleInvalidUserCallTarget (00000000`00001100)\n"
                            "FILEWAY_CFG_CALLER_END\n")
            def caller_case(name, block=caller_block, original=caller_original, status=1, passes=1, saves=1, reject=False, stack="caller-original.log"):
                path = f"caller-{name}.log"
                (root / path).write_text(original + block, encoding="utf-8")
                caller_vectors.append(f'RejectCaller("{path}");' if reject else f'CheckCaller("{path}",{status},{passes},{saves},{json.dumps(stack)});')

            caller_case("rax")
            caller_case("other-register", block=caller_block.replace("rcx,rax", "rcx,r10"), passes=0)
            caller_case("unknown-opcode", block=caller_block.replace("mov rcx,rax", "unknownop PRIVATE_SECRET"), passes=0)
            caller_case("private-symbol", block=caller_block.replace("ntdll!RtlpHandleInvalidUserCallTarget (", "ntdll!PRIVATE_SECRET ("), passes=0)
            caller_case("wrong-helper-address", block=caller_block.replace("(00000000`00001100)", "(00000000`00001200)"), passes=0)
            caller_case("helper-interior", block=caller_block.replace("Target (", "Target+0x1 (").replace("(00000000`00001100)", "(00000000`00001101)"), passes=0)
            caller_case("jump", block=caller_block.replace(" call ", " jmp "), passes=0)
            caller_unproven = caller_original.replace("mov rbx,rcx", "mov rbx,rax")
            (root / "caller-unproven-original.log").write_text(caller_unproven, encoding="utf-8")
            caller_case("unproven-prologue", original=caller_unproven, saves=0, stack="caller-unproven-original.log")
            for name, block in {
                "gap": caller_block.replace("1303 e8", "1304 e8"),
                "wrong-end": caller_block.replace("e8f8fdffff", "e8f8fdff"),
                "outside-module": caller_block.replace("1300 488bc8", "0900 488bc8"),
                "past-return": caller_block.replace("FILEWAY_CFG_CALLER_END", "1308 90 nop\nFILEWAY_CFG_CALLER_END"),
                "missing-end": caller_block.replace("FILEWAY_CFG_CALLER_END", ""),
                "duplicate": caller_block * 2,
                "wrong-module-label": caller_block.replace("ntdll!PRIVATE_SECRET+0x1:", "USER32!PRIVATE_SECRET+0x1:"),
                "command-echo": caller_block.replace("FILEWAY_CFG_CALLER_BEGIN", "0:000> .echo FILEWAY_CFG_CALLER_BEGIN"),
                "too-many": "FILEWAY_CFG_CALLER_BEGIN\nntdll!PRIVATE_SECRET:\n" + "".join(f"{0x12f8 + i:x} 90 nop\n" for i in range(17)) + "FILEWAY_CFG_CALLER_END\n",
            }.items():
                caller_case(name, block=block, status=2, passes=0, saves=0)
            caller_case("absent", block="", status=0, passes=0, saves=0)
            for name, original in {
                "changed-context": caller_original.replace("rdx=0000000000004234", "rdx=0000000000004244"),
                "changed-module": caller_original.replace("1000 3000 ntdll", "0900 3000 ntdll"),
                "wrong-return-rva": caller_original.replace("ntdll+0x308", "ntdll+0x309"),
                "wrong-return-value": caller_original.replace("00000000`00001308 ntdll!Rtlp", "00000000`00001309 ntdll!Rtlp"),
                "wrong-frame-module": caller_original.replace("ntdll+0x308", "USER32+0x308"),
                "changed-prologue": caller_original.replace("mov rbx,rcx", "mov rbx,rax"),
            }.items():
                caller_case(name, original=original, reject=True)
                caller_vectors.append(f'CheckCallerQuery("caller-{name}.log",false);' if name not in ("changed-context", "changed-module", "changed-prologue") else "")
            (root / "other-caller-session").mkdir()
            (root / "other-caller-session/caller.log").write_text(caller_original + caller_block, encoding="utf-8")
            caller_vectors += ['RejectCaller("other-caller-session/caller.log");', 'CheckCallerQuery("caller-original.log",true);', 'CheckCallerQuery("tail-unloaded.log",false);']

            saved_vectors = []
            saved_stack = caller_original.replace('00000000`00001308', '00000000`00001338').replace('ntdll+0x308', 'ntdll+0x338').replace('00000000`00008040', '00000000`00800000')
            saved_ops = [('4153', 'push r11'), ('4152', 'push r10'), ('4151', 'push r9'), ('4150', 'push r8'),
                         ('51', 'push rcx'), ('52', 'push rdx'), ('50', 'push rax'), ('4881ec80000000', 'sub rsp,80h')]
            saved_ops += [('0f29' + f'{0x44 + i * 8:02x}' + '24' + f'{0x20 + i * 16:02x}',
                           f'movaps xmmword ptr [rsp+{0x20 + i * 16:x}h],xmm{i}') for i in range(6)]
            saved_ops += [('488bc8', 'mov rcx,rax'), ('e8c8fdffff', 'call ntdll!RtlpHandleInvalidUserCallTarget (00000000`00001100)')]
            saved_lines, saved_address = [], 0x1300
            for encoded, instruction in saved_ops:
                saved_lines.append(f'{saved_address:x} {encoded} {instruction}\n')
                saved_address += len(encoded) // 2
            self.assertEqual(saved_address, 0x1338)
            saved_caller = saved_stack + 'FILEWAY_CFG_CALLER_BEGIN\nntdll!PRIVATE_SECRET:\n' + ''.join(saved_lines) + 'FILEWAY_CFG_CALLER_END\n'
            (root / 'saved-stack.log').write_text(saved_stack, encoding='utf-8')
            (root / 'saved-caller.log').write_text(saved_caller, encoding='utf-8')
            saved_tuple = ('FILEWAY_CFG_SAVED_ARGS_BEGIN\n00800080 00000000`00004234 00000000`00000082\n'
                           '00800090 00000000`00600001\nFILEWAY_CFG_SAVED_ARGS_END\n')
            def saved_case(name, body=saved_tuple, prefix=saved_caller, status=1, matches=1, role=1, message=2,
                           aliases=(0x600001, 0x600002, 0x600003), reject=False):
                path = f'saved-{name}.log'
                (root / path).write_text(prefix + body, encoding='utf-8')
                saved_vectors.append(f'RejectSavedArguments("{path}");' if reject else
                                     f'CheckSavedArguments("{path}",{status},{matches},{role},{message},{aliases[0]},{aliases[1]},{aliases[2]});')

            saved_case('main-ncdestroy')
            saved_case('player-destroy', body=saved_tuple.replace('00000082', '00000002').replace('00600001', '00600002'), role=2, message=1)
            saved_case('hud-close', body=saved_tuple.replace('00000082', '00000010').replace('00600001', '00600003'), role=3, message=3)
            saved_case('unknown-window', body=saved_tuple.replace('00600001', '00700001'), role=0)
            saved_case('other-message', body=saved_tuple.replace('00000082', '00000123'), message=0)
            saved_case('ambiguous-role', aliases=(0x600001, 0x600001, 0x600003), role=0)
            saved_case('no-role', aliases=(0, 0, 0), role=0)
            saved_case('different-target', body=saved_tuple.replace('00004234', 'deadbeef'), status=0, matches=0, role=0, message=0)
            for name, body in {
                'gap': saved_tuple.replace('00800090', '00800098'),
                'wrong-base': saved_tuple.replace('00800080', '00800088'),
                'extra-qword': saved_tuple.replace('00800090 00000000`00600001', '00800090 00000000`00600001 00000000`deadbeef'),
                'missing-qword': saved_tuple.replace('00800090 00000000`00600001\n', ''),
                'unreadable': saved_tuple.replace('00000000`00600001', '????????`????????'),
                'duplicate-marker': saved_tuple * 2,
                'missing-end': saved_tuple.replace('FILEWAY_CFG_SAVED_ARGS_END', ''),
                'echo-marker': saved_tuple.replace('FILEWAY_CFG_SAVED_ARGS_BEGIN', '0:000> .echo FILEWAY_CFG_SAVED_ARGS_BEGIN'),
                'unterminated': saved_tuple.rstrip('\n'),
            }.items():
                saved_case(name, body=body, reject=True)
            saved_case('changed-context', prefix=saved_caller.replace('rdx=0000000000004234', 'rdx=0000000000004244'), reject=True)
            saved_case('changed-stack', prefix=saved_caller.replace('00000000`00800000', '00000000`00800010'), reject=True)
            saved_vectors.append('CheckSavedQuery("saved-stack.log","saved-caller.log",true);')
            (root / 'saved-caller-movups.log').write_text(saved_caller.replace('movaps', 'movups').replace('0f29', '0f11'), encoding='utf-8')
            saved_vectors.append('CheckSavedQuery("saved-stack.log","saved-caller-movups.log",true);')
            for name, stack_pointer in {'unaligned': '00000000`00800008', 'overflow': '00007fff`ffffff80'}.items():
                (root / f'saved-stack-{name}.log').write_text(saved_stack.replace('00000000`00800000', stack_pointer), encoding='utf-8')
                (root / f'saved-caller-{name}.log').write_text(saved_caller.replace('00000000`00800000', stack_pointer), encoding='utf-8')
                saved_vectors.append(f'CheckSavedQuery("saved-stack-{name}.log","saved-caller-{name}.log",false);')
            for name, changed in {
                'push': saved_caller.replace('4153 push r11', '4053 push r11'),
                'immediate-bytes': saved_caller.replace('4881ec80000000', '4881ec90000000'),
                'too-large': saved_caller.replace('4881ec80000000 sub rsp,80h', '4881ec00200000 sub rsp,2000h'),
                'too-small': saved_caller.replace('4881ec80000000 sub rsp,80h', '4881ec70000000 sub rsp,70h'),
                'unknown-store': saved_caller.replace('movaps xmmword ptr [rsp+20h],xmm0', 'unknownop PRIVATE_SECRET'),
                'store-bytes': saved_caller.replace('0f29442420', '0f29442430'),
                'store-register': saved_caller.replace('[rsp+20h],xmm0', '[rsp+20h],xmm1'),
                'store-overwrites-slot': saved_caller.replace('[rsp+70h],xmm5', '[rsp+80h],xmm5'),
            }.items():
                path = f'saved-layout-{name}.log'
                (root / path).write_text(changed, encoding='utf-8')
                saved_vectors.append(f'CheckSavedQuery("saved-stack.log","{path}",false);')
            (root / 'other-caller-session/saved.log').write_text(saved_caller + saved_tuple, encoding='utf-8')
            saved_vectors.append('RejectSavedArguments("other-caller-session/saved.log");')

            dispatch_vectors = []
            dispatch_frames = ('00000000`00030125 ntdll+0x338\n'
                               '03 00000000`00800010 00000000`00030225 USER32!DispatchHookW+0x25\n'
                               '04 00000000`00800020 00000000`00030325 USER32!CallHookWithSEH+0x25\n'
                               '05 00000000`00800030 00000000`00001100 USER32!__fnHkINLPCWPSTRUCT+0x25\n')
            dispatch_stack = saved_stack.replace('00000000`00002100 ntdll+0x338\n', dispatch_frames).replace(
                '1000 3000 ntdll (pdb symbols)\n', '1000 3000 ntdll (pdb symbols)\n30000 33000 USER32 (pdb symbols)\n100000 286000 Microsoft_UI_Input (pdb symbols)\n')
            dispatch_base = dispatch_stack + saved_caller[saved_caller.index('FILEWAY_CFG_CALLER_BEGIN'):]
            (root / 'dispatch-stack.log').write_text(dispatch_stack, encoding='utf-8')
            def dispatch_identity(module, low, size, stamp, guid, prefix):
                return (f'{prefix}BEGIN\nModule: {module}\nBase Address: {low:x}\nSize: {size:x}\nTime Stamp: {stamp:x} (reproducible hash)\n'
                        f'CODEVIEW 2f, 1234, 1234 RSDS - GUID: {{{guid}}}\nAge: 1, Pdb: C:\\PRIVATE_SECRET\\module.pdb\n'
                        f'Symbol Type: PDB - Symbols loaded successfully\n{prefix}END\n')
            dispatch_user32 = dispatch_identity('user32', 0x30000, 0x3000, 0x12345678, '11111111-2222-3333-4444-555555555555', 'FILEWAY_DISPATCH_USER32_ID_')
            dispatch_input = dispatch_identity('Microsoft.UI.Input', 0x100000, 0x186000, 0x726624e6, 'A5B1A2FA-1416-A17D-718A-F294946ACE1A', 'FILEWAY_DISPATCH_INPUT_ID_')
            def dispatch_frame(index, symbol, target):
                begin = 0x100 * (index - 2)
                address = 0x30000 + begin
                target_address = {'DispatchHookW': 0x30100, 'CallHookWithSEH': 0x30200, '__fnHkINLPCWPSTRUCT': 0x30300}[target]
                encoded = 'e8' + struct.pack('<i', target_address - (address + 0x25)).hex()
                nearest = f'({address:016x}) USER32!{symbol}+0x25 | ({address + 0x50:016x}) USER32!PRIVATE_SECRET\n'
                return (f'FILEWAY_DISPATCH_FRAME{index}_BEGIN\n' + nearest + nearest +
                        f'BeginAddress = {begin:016x}\nEndAddress = {begin + 0x50:016x}\nFILEWAY_DISPATCH_FRAME{index}_CODE_BEGIN\n'
                        f'USER32!{symbol}+0x1d:\n{address + 0x1d:x} 90 nop\n{address + 0x1e:x} 90 nop\n{address + 0x1f:x} 90 nop\n'
                        f'{address + 0x20:x} {encoded} call USER32!{target} ({target_address:016x})\n'
                        f'FILEWAY_DISPATCH_FRAME{index}_CODE_END\nFILEWAY_DISPATCH_FRAME{index}_END\n')
            dispatch_code = dispatch_frame(3, 'DispatchHookW', 'CallHookWithSEH') + dispatch_frame(4, 'CallHookWithSEH', '__fnHkINLPCWPSTRUCT') + dispatch_frame(5, '__fnHkINLPCWPSTRUCT', 'DispatchHookW')
            dispatch_valid = dispatch_base + saved_tuple + dispatch_user32 + dispatch_input + dispatch_code
            def dispatch_case(name, content=dispatch_valid, symbol=4, candidate=1, verified=True, identity=1, pdb=1, reject=False):
                path = f'dispatch-{name}.log'
                (root / path).write_text(content, encoding='utf-8')
                dispatch_vectors.append(f'RejectDispatch("{path}");' if reject else
                                        f'CheckDispatch("{path}",{symbol},{candidate},{str(verified).lower()},{identity},{pdb});')
            dispatch_case('valid')
            dispatch_case('unknown-public-symbol', content=dispatch_valid.replace('DispatchHookW', 'PRIVATE_SECRET'), symbol=0)
            dispatch_case('ordinal-symbol-name', content=dispatch_valid.replace('DispatchHookW', 'DispatchHookw'), symbol=0)
            dispatch_case('exports-only', content=dispatch_valid.replace(dispatch_user32, dispatch_user32.replace('Symbol Type: PDB', 'Symbol Type: EXPORT')), symbol=0, verified=False, pdb=0)
            dispatch_case('identity-absent', content=dispatch_valid.replace(dispatch_user32, ''), symbol=0, verified=False, identity=0, pdb=0)
            dispatch_case('different-input-guid', content=dispatch_valid.replace('A5B1A2FA-1416-A17D-718A-F294946ACE1A', '99999999-8888-7777-6666-555555555555'), candidate=0)
            dispatch_case('different-input-age', content=dispatch_valid.replace(dispatch_input, dispatch_input.replace('Age: 1,', 'Age: 2,')), candidate=0)
            dispatch_case('different-input-stamp', content=dispatch_valid.replace('726624e6', '726624e7'), candidate=0)
            dispatch_case('input-export-only', content=dispatch_valid.replace(dispatch_input, dispatch_input.replace('Symbol Type: PDB', 'Symbol Type: EXPORT')), candidate=0)
            dispatch_case('input-deferred', content=dispatch_valid.replace(dispatch_input, dispatch_input.replace('Symbol Type: PDB', 'Symbol Type: DEFERRED')), candidate=0)
            dispatch_case('input-age-zero', content=dispatch_valid.replace(dispatch_input, dispatch_input.replace('Age: 1,', 'Age: 0,')), candidate=0)
            unknown_target = dispatch_valid.replace('call USER32!CallHookWithSEH (0000000000030200)', 'call USER32!PRIVATE_SECRET (0000000000030200)')
            dispatch_case('unknown-direct-target', content=unknown_target)
            unresolved_target = dispatch_valid.replace('call USER32!CallHookWithSEH (0000000000030200)', 'call USER32!PRIVATE_SECRET (1122334455667788)')
            dispatch_case('unresolved-direct-target', content=unresolved_target)
            dispatch_vectors += ['CheckDispatchTarget("dispatch-valid.log",6,0x200,1);',
                                 'CheckDispatchTarget("dispatch-unknown-direct-target.log",0,0x200,1);',
                                 'CheckDispatchTarget("dispatch-unresolved-direct-target.log",0,0,0);']
            for name, content in {
                'wrong-module': dispatch_valid.replace('Module: user32', 'Module: KERNEL32'),
                'wrong-base': dispatch_valid.replace('Base Address: 30000', 'Base Address: 30010'),
                'wrong-size': dispatch_valid.replace('Size: 3000', 'Size: 3001'),
                'bad-guid': dispatch_valid.replace('11111111-2222-3333-4444-555555555555', 'PRIVATE_SECRET'),
                'bad-age': dispatch_valid.replace('Age: 1, Pdb:', 'Age: PRIVATE_SECRET, Pdb:'),
                'age-overflow': dispatch_valid.replace('Age: 1, Pdb:', 'Age: 4294967296, Pdb:'),
                'bad-base-format': dispatch_valid.replace('Base Address: 30000', 'Base Address: 3`0000'),
                'duplicate-module': dispatch_valid.replace('Module: user32', 'Module: user32\nModule: user32'),
                'duplicate-rsds': dispatch_valid.replace('Age: 1,', 'CODEVIEW 2f RSDS - GUID: {11111111-2222-3333-4444-555555555555}\nAge: 1,', 1),
                'malformed-rsds-after-valid': dispatch_valid.replace('Age: 1,', 'CODEVIEW PRIVATE_SECRET\nAge: 1,', 1),
                'malformed-age-after-valid': dispatch_valid.replace('Age: 1, Pdb:', 'Age: PRIVATE_SECRET\nAge: 1, Pdb:', 1),
                'duplicate-symbol-type': dispatch_valid.replace('Symbol Type: PDB', 'Symbol Type: EXPORT\nSymbol Type: PDB', 1),
                'duplicate-identity': dispatch_valid.replace(dispatch_user32, dispatch_user32 * 2),
                'identity-echo': dispatch_valid.replace('FILEWAY_DISPATCH_USER32_ID_BEGIN', '0:000> .echo FILEWAY_DISPATCH_USER32_ID_BEGIN'),
                'identity-truncated': dispatch_valid.replace('FILEWAY_DISPATCH_USER32_ID_END', ''),
                'wrong-function-start': dispatch_valid.replace('BeginAddress = 0000000000000100', 'BeginAddress = 0000000000000101'),
                'probe-outside-function': dispatch_valid.replace('EndAddress = 0000000000000150', 'EndAddress = 0000000000000125'),
                'duplicate-boundary': dispatch_valid.replace('BeginAddress = 0000000000000100', 'BeginAddress = 0000000000000100\nBeginAddress = 0000000000000100'),
                'malformed-boundary-after-valid': dispatch_valid.replace('BeginAddress = 0000000000000100', 'BeginAddress = 0000000000000100\nBeginAddress = PRIVATE_SECRET'),
                'function-range-outside-image': dispatch_valid.replace('EndAddress = 0000000000000150', 'EndAddress = 0000000000003001'),
                'function-range-overflow': dispatch_valid.replace('EndAddress = 0000000000000150', 'EndAddress = 0000000100000000'),
                'nearest-export': dispatch_valid.replace('(0000000000030100) USER32!DispatchHookW+0x25', '(0000000000030000) USER32!DispatchHookW+0x125'),
                'conflicting-nearest': dispatch_valid.replace('(0000000000030100) USER32!DispatchHookW+0x25', '(0000000000030100) USER32!PRIVATE_SECRET+0x25', 1),
                'malformed-nearest-after-valid': dispatch_valid.replace('(0000000000030100) USER32!DispatchHookW+0x25', '(PRIVATE_SECRET) USER32!DispatchHookW+0x25', 1),
                'wrong-nearest-module': dispatch_valid.replace('(0000000000030100) USER32!DispatchHookW+0x25', '(0000000000030100) KERNEL32!DispatchHookW+0x25'),
                'wrong-probe-displacement': dispatch_valid.replace('USER32!DispatchHookW+0x25 |', 'USER32!DispatchHookW+0x26 |'),
                'instruction-gap': dispatch_valid.replace('3011e 90 nop', '3011f 90 nop'),
                'wrong-call-end': dispatch_valid.replace('30120 e8db000000', '30120 e8db0000'),
                'not-a-call': dispatch_valid.replace('30120 e8db000000 call', '30120 e8db000000 jmp'),
                'outside-function-code': dispatch_valid.replace('3011d 90 nop', '300ff 90 nop'),
                'duplicate-code': dispatch_valid.replace('FILEWAY_DISPATCH_FRAME3_CODE_END', 'FILEWAY_DISPATCH_FRAME3_CODE_BEGIN\nFILEWAY_DISPATCH_FRAME3_CODE_END'),
                'unterminated-code': dispatch_valid.replace('FILEWAY_DISPATCH_FRAME3_CODE_END', ''),
                'duplicate-frame': dispatch_valid.replace('FILEWAY_DISPATCH_FRAME3_END', 'FILEWAY_DISPATCH_FRAME3_END\nFILEWAY_DISPATCH_FRAME3_END'),
                'unterminated-frame': dispatch_valid.replace('FILEWAY_DISPATCH_FRAME3_END', ''),
                'different-context': dispatch_valid.replace('rdx=0000000000004234', 'rdx=0000000000004244'),
                'different-saved-target': dispatch_valid.replace(saved_tuple, saved_tuple.replace('00004234', '00004244')),
                'missing-saved-tuple': dispatch_valid.replace(saved_tuple, ''),
                'duplicate-saved-tuple': dispatch_valid.replace(saved_tuple, saved_tuple * 2),
                'wrong-saved-slot': dispatch_valid.replace('00800080 00000000`00004234', '00800088 00000000`00004234'),
                'different-exception': dispatch_valid.replace('c0000409', '80000003'),
                'different-subcode': dispatch_valid.replace('Parameter[0]: a', 'Parameter[0]: 9'),
                'different-user32-image': dispatch_valid.replace('30000 33000 USER32', '30000 33010 USER32'),
                'different-frame-pc': dispatch_valid.replace('00000000`00030225 USER32!DispatchHookW', '00000000`00030226 USER32!DispatchHookW'),
            }.items():
                self.assertNotEqual(content, dispatch_valid, name + ': ineffective negative fixture')
                dispatch_case(name, content=content, reject=True)
            excessive_code = ''.join(f'{0x30113 + i:x} 90 nop\n' for i in range(13))
            dispatch_case('too-many-instructions', content=dispatch_valid.replace('3011d 90 nop\n3011e 90 nop\n3011f 90 nop\n', excessive_code), reject=True)
            dispatch_case('unterminated-log', content=dispatch_valid.rstrip('\n'), reject=True)
            (root / 'other-caller-session/dispatch.log').write_text(dispatch_valid, encoding='utf-8')
            dispatch_vectors += ['RejectDispatch("other-caller-session/dispatch.log");', 'CheckDispatchQuery("dispatch-stack.log",true);']
            # A known symbol's offset is not its module RVA: the query must still use 0x125.
            (root / 'dispatch-kind2-stack.log').write_text(dispatch_stack.replace('USER32!DispatchHookW+0x25', 'USER32!CallWindowProcW+0x25'), encoding='utf-8')
            dispatch_vectors.append('CheckDispatchQuery("dispatch-kind2-stack.log",true);')
            (root / 'dispatch-missing-frame.log').write_text(dispatch_stack.replace('04 00000000`00800020 00000000`00030325 USER32!CallHookWithSEH+0x25\n', ''), encoding='utf-8')
            dispatch_vectors.append('CheckDispatchQuery("dispatch-missing-frame.log",false);')
            (root / 'dispatch-frame-pc-outside.log').write_text(dispatch_stack.replace('00000000`00030125 ntdll+0x338', '00000000`00033000 ntdll+0x338'), encoding='utf-8')
            dispatch_vectors.append('CheckDispatchQuery("dispatch-frame-pc-outside.log",false);')

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

            (root / "region-bound-free.log").write_text(verified+free_shape, encoding="utf-8")
            snapshot_begin = "FILEWAY_CFG_PRE_CLOSE_MODULES_BEGIN\nstart             end                 module name\n"
            snapshot_end = "FILEWAY_CFG_PRE_CLOSE_MODULES_END\n"
            snapshot_known = "11223344`55667000 11223344`55668000 libmpv_2 (deferred)\n"
            def snapshot_case(name, text, expected=None, region="region-bound-free.log", stack="tail-unmapped.log"):
                path = f"pre-close-{name}.log"
                (root / path).write_text(text, encoding="utf-8")
                region_vectors.append(f'CheckPreClose("{stack}","{region}","{path}",{json.dumps(expected)});' if expected is not None
                                      else f'CheckPreClose("{stack}","{region}","{path}",null);')
            snapshot_case("known", snapshot_begin+snapshot_known+snapshot_end, "1|libmpv_2|1928")
            # A -cf command file echoes its command line. With one compound
            # command the prompt/echo precedes BEGIN, outside the strict section.
            command_echo = "0:000> .echo FILEWAY_CFG_PRE_CLOSE_MODULES_BEGIN; lm o; .echo FILEWAY_CFG_PRE_CLOSE_MODULES_END\n"
            snapshot_case("compound-command-echo", command_echo+snapshot_begin+snapshot_known+snapshot_end+"0:000> .echo FILEWAY_SHUTDOWN_DEBUGGER_READY\n", "1|libmpv_2|1928")

            snapshot_case("known-alias", snapshot_begin+snapshot_known.replace("libmpv_2", "libmpv-2.dll")+snapshot_end, "1|libmpv_2|1928")
            snapshot_case("unknown", snapshot_begin+snapshot_known.replace("libmpv_2", "PRIVATE_SECRET")+snapshot_end, "0||0")
            snapshot_case("private-path", snapshot_begin+snapshot_known.replace("libmpv_2", "C:\\private\\libmpv-2.dll")+snapshot_end, "0||0")
            snapshot_case("unknown-overlap", snapshot_begin+snapshot_known+snapshot_known.replace("libmpv_2", "PRIVATE_SECRET")+snapshot_end, "0||0")
            snapshot_case("known-overlap", snapshot_begin+snapshot_known+snapshot_known.replace("libmpv_2", "USER32")+snapshot_end, "0||0")
            snapshot_case("out-of-range", snapshot_begin+"1000 2000 libmpv_2 (deferred)\n"+snapshot_end, "0||0")
            for name, text in {
                "multiline-command-echo": snapshot_begin.split("\n",1)[0]+"\n0:000> lm o\n"+snapshot_begin.split("\n",1)[1]+snapshot_known+"0:000> .echo FILEWAY_CFG_PRE_CLOSE_MODULES_END\n"+snapshot_end,
                "missing-begin": snapshot_known+snapshot_end,
                "missing-end": snapshot_begin+snapshot_known,
                "missing-header": snapshot_begin.split("\n",1)[0]+"\n"+snapshot_known+snapshot_end,
                "duplicate-header": snapshot_begin+"start end module name\n"+snapshot_known+snapshot_end,
                "duplicate-known": snapshot_begin+snapshot_known*2+snapshot_end,
                "duplicate-begin": snapshot_begin+snapshot_begin+snapshot_known+snapshot_end,
                "duplicate-end": snapshot_begin+snapshot_known+snapshot_end*2,
                "multiple-blocks": (snapshot_begin+snapshot_known+snapshot_end)*2,
                "unloaded-block": snapshot_begin+"Unloaded modules:\n"+snapshot_known+snapshot_end,
                "truncated-end": snapshot_begin+snapshot_known+snapshot_end.rstrip("\n"),
                "malformed-range": snapshot_begin+snapshot_known.replace("55667000", "PRIVATE_SECRET")+snapshot_end,
                "malformed-backtick": snapshot_begin+snapshot_known.replace("11223344`55667000", "1122`334455667000")+snapshot_end,
                "reverse-range": snapshot_begin+snapshot_known.replace("55667000", "55669000")+snapshot_end,
                "oversized-range": snapshot_begin+"1 ffffffffffffffff ntdll (deferred)\n"+snapshot_end,
                "private-annotation": snapshot_begin+snapshot_known+"PRIVATE_SECRET\n"+snapshot_end,
                "oversized-line": snapshot_begin+snapshot_known+"x"*2049+"\n"+snapshot_end,
                "too-many-lines": "\n"*4096+snapshot_begin+snapshot_known+snapshot_end,
                "too-many-rows": snapshot_begin+snapshot_known.replace("libmpv_2","PRIVATE_SECRET")*513+snapshot_end,
                "empty": snapshot_begin+snapshot_end,
                "too-large": "x"*1048577,
            }.items():
                snapshot_case(name,text)
            snapshot_case("not-free",snapshot_begin+snapshot_known+snapshot_end,region="region-bound.log")
            for name, reason in (("missing-begin",4),("duplicate-header",5),("multiline-command-echo",6),("duplicate-known",8),("empty",9)):
                region_vectors.append(f'CheckPreClose("tail-unmapped.log","region-bound-free.log","pre-close-{name}.log",null,{reason});')

            snapshot_case("invalid-fault",snapshot_begin+snapshot_known+snapshot_end,stack="tail-wrong-subcode.log")
            (root / "other-session").mkdir()
            (root / "other-session/snapshot.log").write_text(snapshot_begin+snapshot_known+snapshot_end, encoding="utf-8")
            (root / "other-session/region.log").write_text(verified+free_shape, encoding="utf-8")
            region_vectors.append('CheckPreClose("tail-unmapped.log","region-bound-free.log","other-session/snapshot.log",null);')
            region_vectors.append('CheckPreClose("tail-unmapped.log","other-session/region.log","pre-close-known.log",null);')

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
 static void CheckCallerQuery(string path,bool expected) {
  string query=DebuggerEvidence.CallerQuery(path);
  if(expected?query!=".echo FILEWAY_CFG_CALLER_BEGIN; ub ntdll+0x308 L0n16; .echo FILEWAY_CFG_CALLER_END":query!=null)
   throw new Exception("incorrect or unbound caller query");
 }
 static void CheckCaller(string path,int status,int passes,int saves,string stack) {
  var caller=DebuggerEvidence.ReadCaller(stack,path);
  if(caller.Status!=status||caller.PassesRax!=passes||caller.HelperSavesRcx!=saves) throw new Exception(path+": incorrect caller evidence");
  if(status!=1&&caller.Instructions.Length!=0) throw new Exception("partial caller rows accepted");
  foreach(var row in caller.Instructions) {
   if(row.Offset>=0x1000||row.TargetSymbol.Contains("PRIVATE")||row.FirstRegister.Contains("SECRET")||row.SecondRegister.Contains("SECRET"))
    throw new Exception("raw or private caller data exported");
  }
  if(path=="caller-rax.log"&&(caller.Instructions.Length!=2||caller.Instructions[0].Offset!=0x300||caller.Instructions[1].Offset!=0x303))
   throw new Exception("caller instructions lost module-relative offsets");
  // The original helper parameter remains its own observation and mapping.
  var argument=DebuggerEvidence.Read("caller-original.log").HelperArgument;
  if(argument==null||argument.Mapping!=2||argument.Module!="libmpv_2"||argument.Offset!=0x234) throw new Exception("caller analysis changed helper mapping");
 }
 static void RejectCaller(string path) {
  bool rejected=false;
  try { DebuggerEvidence.ReadCaller("caller-original.log",path); } catch(InvalidDataException) { rejected=true; }
  if(!rejected) throw new Exception("unbound caller evidence accepted");
 }
 static void CheckSavedQuery(string stack,string caller,bool expected) {
  string query=DebuggerEvidence.SavedArgumentsQuery(stack,caller);
  if(expected?query!=".echo FILEWAY_CFG_SAVED_ARGS_BEGIN; dq 800080 L0n3; .echo FILEWAY_CFG_SAVED_ARGS_END":query!=null)
   throw new Exception("incorrect saved argument query");
 }
 static void CheckSavedArguments(string path,int status,int matches,int role,int message,long main,long player,long hud) {
  var evidence=DebuggerEvidence.ReadSavedArguments("saved-stack.log","saved-caller.log",path,main,player,hud);
  if(evidence.Status!=status||evidence.TargetMatches!=matches||evidence.WindowRole!=role||evidence.MessageKind!=message)
   throw new Exception(path+": incorrect bound saved arguments");
  var fields=evidence.GetType().GetFields();
  if(fields.Length!=4) throw new Exception("unexpected saved argument payload");
  foreach(var field in fields) if(field.FieldType!=typeof(int)) throw new Exception("raw saved argument exported");
 }
 static void RejectSavedArguments(string path) {
  bool rejected=false;
  try { DebuggerEvidence.ReadSavedArguments("saved-stack.log","saved-caller.log",path,0x600001,0x600002,0x600003); }
  catch(InvalidDataException) { rejected=true; }
  if(!rejected) throw new Exception("unbound or partial saved arguments accepted");
 }
 static void CheckDispatchQuery(string path,bool expected) {
  string query=DebuggerEvidence.DispatchQuery(path);
  if(!expected) { if(query!=null) throw new Exception("unbound dispatch query accepted"); return; }
  if(query==null||!query.Contains(".symopt+0x400")||!query.Contains("!lmi USER32")||!query.Contains("!lmi Microsoft_UI_Input")||
    !query.Contains(".fnent USER32+0x125")||!query.Contains("ub USER32+0x225 L0n12")||!query.Contains(".fnent USER32+0x325")||query.Contains("PRIVATE_SECRET"))
   throw new Exception("incorrect or non-RVA dispatch query");
 }
 static void CheckDispatch(string path,int firstSymbol,int candidate,bool verified,int identity,int pdb) {
  var evidence=DebuggerEvidence.ReadDispatch("dispatch-stack.log",path);
  if(evidence.Modules.Length!=2||evidence.Frames.Length!=3||evidence.InputCandidateMatches!=candidate) throw new Exception("wrong dispatch result shape");
  if(evidence.Modules[0].ModuleId!=1||evidence.Modules[0].Status!=identity||evidence.Modules[0].PdbLoaded!=pdb||evidence.Modules[1].ModuleId!=2)
   throw new Exception("wrong bound module identity");
  foreach(var module in evidence.Modules) {
   if(module.Status==1&&!System.Text.RegularExpressions.Regex.IsMatch(module.GuidHex,"^[0-9A-F]{32}$")) throw new Exception("invalid or private GUID output");
   if(module.Status==0&&(module.GuidHex!=""||module.Age!=0||module.ImageSize!=0||module.ImageStamp!=0||module.PdbLoaded!=0)) throw new Exception("unverified identity retained fields");
   foreach(var field in module.GetType().GetFields()) if(field.FieldType==typeof(string)&&field.Name!="GuidHex") throw new Exception("raw identity text exposed");
  }
  if(identity==1&&(evidence.Modules[0].GuidHex!="11111111222233334444555555555555"||evidence.Modules[0].Age!=1||evidence.Modules[0].ImageSize!=0x3000||evidence.Modules[0].ImageStamp!=0x12345678))
   throw new Exception("wrong reconstructed USER32 identity");
  if(candidate==1&&(evidence.Modules[1].GuidHex!="A5B1A2FA1416A17D718AF294946ACE1A"||evidence.Modules[1].Age!=1||evidence.Modules[1].ImageSize!=0x186000||evidence.Modules[1].ImageStamp!=0x726624e6||evidence.Modules[1].PdbLoaded!=1))
   throw new Exception("Input candidate identity was not exact");
  for(int i=0;i<3;i++) {
   var frame=evidence.Frames[i];
   if(frame.FrameIndex!=i+3||frame.ProbeRva!=(ulong)(0x125+i*0x100)||frame.Status!=(verified?1:0)||frame.ReturnPointMatched!=(verified?1:0)) throw new Exception("unbound dispatch frame");
   if(!verified&&(frame.SymbolId!=0||frame.Instructions.Length!=0)) throw new Exception("EXPORT/absent identity invented symbols");
   if(verified&&(frame.Instructions.Length!=4||frame.FunctionBeginRva!=(ulong)(0x100+i*0x100)||frame.FunctionEndRva!=(ulong)(0x150+i*0x100))) throw new Exception("wrong bounded instructions");
   if(i==0&&frame.SymbolId!=firstSymbol) throw new Exception("unknown public symbol was exported or misclassified");
   foreach(var row in frame.Instructions) {
    if(row.Offset>=0x3000||row.TargetOffset>=0x3000||row.TargetSymbol.Contains("PRIVATE_SECRET")||row.FirstRegister.Contains("PRIVATE_SECRET")||row.SecondRegister.Contains("PRIVATE_SECRET")) throw new Exception("private dispatch text or address exposed");
   }
  }
 }
 static void CheckDispatchTarget(string path,int symbol,uint rva,int decoded) {
  var evidence=DebuggerEvidence.ReadDispatch("dispatch-stack.log",path);
  var row=evidence.Frames[0].Instructions[3];
  if(DebuggerEvidence.DispatchSymbolId(row.TargetSymbol)!=symbol||row.TargetOffset!=rva||row.Decoded!=decoded)
   throw new Exception(path+": unbounded or missing direct target RVA");
  if(decoded==0&&(row.FirstKind!=0||row.SecondKind!=0||row.FirstRegister!=""||row.SecondRegister!=""||row.FirstDisplacement!=0||row.SecondDisplacement!=0))
   throw new Exception("undecoded dispatch instruction retained operands");
 }
 static void RejectDispatch(string path) {
  bool rejected=false;
  try { DebuggerEvidence.ReadDispatch("dispatch-stack.log",path); } catch(InvalidDataException error) {
   rejected=true; if(error.Message.Contains("PRIVATE_SECRET")) throw new Exception("private dispatch rejection text");
  }
  if(!rejected) throw new Exception(path+": inconsistent dispatch evidence accepted");
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

 static void CheckPreClose(string stack,string region,string snapshot,string expected,int reason=0) {
  bool rejected=false; string actual=null;
  try { var mapped=DebuggerEvidence.ReadPreCloseMapping(stack,region,snapshot); actual=mapped.Mapping+"|"+mapped.Module+"|"+mapped.Offset; }
  catch(InvalidDataException error) {
   rejected=true;
   if(reason!=0&&!Equals(error.Data["preCloseReason"],reason)) throw new Exception("incorrect pre-close reason");
   if(error.Message.Contains("PRIVATE_SECRET")||error.Message.Contains("11223344")||error.Message.Contains("private/")) throw new Exception("unsafe pre-close error");
  }
  if(expected==null?!rejected:rejected||actual!=expected) throw new Exception("incorrect pre-close mapping: "+snapshot);
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
 static void Main() { CheckMainWindowCandidate(); CheckCandidateClassifications(); CheckInternalTimings(); CheckDebuggerReady(); CheckDebuggerParameterMapping(); ''' + "".join(vectors + shutdown_vectors + debugger_vectors + helper_vectors + tail_vectors + caller_vectors + saved_vectors + dispatch_vectors + region_vectors) + '''
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
