"""Offline guards and optional existing-SDK checks for the native smoke helper.

The SDK check compiles the production C# decoder, PNG writer and shutdown-evidence
and debugger-evidence readers, but never calls Windows APIs. It uses no NuGet sources/packages and installs
nothing. Static guards do not establish a native PowerShell parse/pass; an optional
parser check runs only when PowerShell is already available.
"""
from __future__ import annotations

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
        self.assertIn(".dump /mu /o shutdown.dmp; gn", powershell)
        self.assertNotIn("; gh", powershell)
        self.assertIn("$watch.ElapsedMilliseconds -lt 10000", powershell)
        self.assertIn("$analysis.WaitForExit(120000)", powershell)
        self.assertIn("[Fileway.PlayerSmoke.DebuggerEvidence]::HasReadyMarker($log)", powershell)
        self.assertIn("ReadBounded(path,FileShare.ReadWrite)", native)
        self.assertNotIn("[IO.File]::ReadAllText($log)", powershell)
        self.assertIn("hresult = [int]$attachError.HResult", powershell)
        self.assertIn("srv*$symbols*https://msdl.microsoft.com/download/symbols", powershell)
        self.assertIn("'.ecxr; .exr -1; kn 0n24; lm; q'", powershell)
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

    @unittest.skipUnless(DOTNET, "Optional production C# checks require an already installed .NET SDK")
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
                + '.0</TargetFramework><OutputType>Exe</OutputType><EnableNETAnalyzers>false</EnableNETAnalyzers></PropertyGroup></Project>')
            (root / "NuGet.Config").write_text('<configuration><packageSources><clear /></packageSources></configuration>')
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
  var maximum="ExceptionCode: c0000409\\nNumberParameters: 15\\nParameter[0]: a\\n";
  for(int i=1;i<15;i++) maximum+="Parameter["+i+"]: "+(0x1000+i).ToString("x")+"\\n";
  var bounded=DebuggerEvidence.Parse(maximum+"1000 2000 msctf.dll (pdb symbols)\\n");
  if(bounded.Parameters.Length!=14) throw new Exception("maximum parameter count lost");
  foreach(var parameter in bounded.Parameters) if(parameter.Mapping!=1||parameter.Module!="msctf"||parameter.Offset!=(ulong)parameter.Index) throw new Exception("incorrect bounded mapping");
  var single=DebuggerEvidence.Parse("ExceptionCode: c0000409\\nNumberParameters: 1\\nParameter[0]: a\\n");
  if(single.ParameterCount!=1||single.Parameters.Length!=0) throw new Exception("invented invalid call target");
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
 static void Main() { CheckDebuggerReady(); CheckDebuggerParameterMapping(); ''' + "".join(vectors + shutdown_vectors + debugger_vectors) + '''
  new Image(480,270,File.ReadAllBytes("frame-128.bgra")).SavePng("verified.png");
  bool refused=false; try { new Image(480,270,File.ReadAllBytes("frame-128.bgra")).SavePng("verified.png"); } catch(IOException) { refused=true; }
  if(!refused) throw new Exception("PNG writer overwrote an existing destination");
  Console.WriteLine("14 pixel vectors and no-clobber PNG writer passed");
 }
}''')
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
