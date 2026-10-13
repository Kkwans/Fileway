"""Offline source guards; these do not claim a native transfer/UI pass.

When pwsh exists, parse the actual production script with its real parser.
No tool installation, network fixture or copy of product policy is used here.
"""
from pathlib import Path
import re
import shutil
import subprocess
import unittest

ENG = Path(__file__).resolve().parents[1]
SOURCE = (ENG / "Test-TransferSmoke.ps1").read_text(encoding="utf-8")
APP = ENG.parent / "src" / "Fileway.App"
WORKFLOW = (ENG.parents[2] / ".github" / "workflows" / "windows-transfer-smoke.yml").read_text(encoding="utf-8")


class TransferSmokeContract(unittest.TestCase):
    def test_public_parameters_are_fixed(self):
        params = SOURCE.split("$ErrorActionPreference", 1)[0]
        self.assertEqual(re.findall(r"\$(\w+)", params),
                         ["AppPath", "ServerPath", "WinAppPath", "OutputRoot", "PreflightOnly"])

    def test_true_preflight_precedes_server_or_app(self):
        preflight = SOURCE.index("-File (Join-Path $PSScriptRoot 'Test-PlayerSmoke.ps1') -PreflightOnly")
        self.assertLess(preflight, SOURCE.index("$script:server = [Diagnostics.Process]::Start"))
        self.assertIn("$preflightExit -ne 0", SOURCE)
        for check in ("checks.interactiveDesktop", "checks.uia", "checks.capture"):
            self.assertIn(check, SOURCE)

    def test_no_duplicate_capture_or_process_pinvoke(self):
        self.assertIn("Add-Type -TypeDefinition $nativeMatch.Groups[1].Value", SOURCE)
        self.assertEqual(SOURCE.count("[DllImport("), 1)
        self.assertIn("SHGetKnownFolderPath", SOURCE)
        self.assertIn("Native]::Capture($script:window).SavePng", SOURCE)

    def test_real_server_is_private_noauth_with_exec_disabled(self):
        for value in ("'--noauth'", "'--address', '127.0.0.1'", "'--disableExec'",
                      "'--root', $remote", "'--database'", "'--hlsCacheDir'",
                      "$start.Environment.Remove($key)", "CopyToAsync([IO.Stream]::Null)"):
            self.assertIn(value, SOURCE)
        self.assertEqual(SOURCE.count("Invoke-WebRequest"), 1)
        self.assertIn("$baseAddress + '/health'", SOURCE)
        self.assertNotIn("httptest", SOURCE)
        self.assertNotIn("/api/tus", SOURCE)
        self.assertNotIn("/api/raw", SOURCE)

    def test_artifacts_only_named_json_and_png(self):
        block = re.search(r"\$allowedArtifacts = @\((.*?)\)\n", SOURCE, re.S).group(1)
        names = re.findall(r"'([^']+)'", block)
        self.assertEqual(len(names), len(set(names)))
        self.assertTrue(all(re.fullmatch(r"[a-z-]+\.(?:json|jsonl|png)", name) for name in names))
        self.assertNotIn("Copy-Item", SOURCE)
        self.assertNotRegex(SOURCE, r"\$raw\s*\|\s*(?:Out-File|Set-Content|Add-Content)")
        self.assertNotIn("Write-Host $", SOURCE)

    def test_workflow_upload_is_exactly_the_script_allowlist(self):
        block = re.search(r"\$allowedArtifacts = @\((.*?)\)\n", SOURCE, re.S).group(1)
        names = re.findall(r"'([^']+)'", block)
        uploaded = re.findall(r"\$\{\{ runner\.temp \}\}/Fileway-transfer-evidence/(\S+)", WORKFLOW)
        expected = ["screenshots/" + name if name.endswith(".png") else name for name in names]
        self.assertEqual(uploaded, expected)
        self.assertNotIn("*", "\n".join(uploaded))

    def test_workflow_runs_guards_before_provisioning_and_on_guard_changes(self):
        command = "python -m unittest discover -s eng/tests -p test_transfer_smoke_harness_contract.py -v"
        self.assertIn(command, WORKFLOW)
        self.assertLess(WORKFLOW.index(command), WORKFLOW.index("- name: Install pinned isolated toolchains"))
        self.assertIn("if ($LASTEXITCODE -ne 0) { throw 'Transfer smoke offline guards failed.' }", WORKFLOW)
        self.assertIn("'clients/windows/eng/tests/test_transfer_smoke_harness_contract.py'", WORKFLOW)

    def test_output_ownership_is_validated_before_report_write(self):
        self.assertLess(SOURCE.index("'output_contains_unexpected_artifact'"),
                        SOURCE.index("$script:root = $candidateRoot"))
        self.assertIn("'private_output_inside_git'", SOURCE)
        self.assertIn("'reparse_path_rejected'", SOURCE)
        self.assertNotRegex(SOURCE, r"(?i)\b(?:Remove-Item|DeleteFile|DeleteDirectory)\b")

    def test_confirmations_and_real_statuses_match_product_source(self):
        self.assertIn('Label="下载"', (APP / "MainPage.xaml").read_text(encoding="utf-8"))
        for literal in ("确认下载位置", "保存到：", "开始下载"):
            self.assertIn(literal, SOURCE)
            self.assertIn(literal, (APP / "MainPage.Downloads.cs").read_text(encoding="utf-8"))
        for literal in ("下载已完成", "本地存在同名文件"):
            self.assertIn(literal, SOURCE)
            self.assertIn(literal, (APP / "DownloadPresentationPolicy.cs").read_text(encoding="utf-8"))
        for literal in ("上传已完成", "目标存在同名项目"):
            self.assertIn(literal, SOURCE)
            self.assertIn(literal, (APP / "UploadPresentationPolicy.cs").read_text(encoding="utf-8"))
        self.assertIn("'upload_confirmation_directory_mismatch'", SOURCE)
        self.assertIn("'download_confirmation_destination_mismatch'", SOURCE)

    def test_no_credentials_or_probabilistic_cancel_are_submitted(self):
        self.assertIn("'toggle-off'", SOURCE)
        self.assertIn("'CheckBox' 'toggle-on'", SOURCE)
        self.assertIn("cancellationUiVerified = $false", SOURCE)
        self.assertIn("unknownOutcomeUiVerified = $false", SOURCE)
        self.assertNotIn("UsernameBox", SOURCE)
        self.assertNotIn("PasswordBox", SOURCE)
        self.assertNotIn("'^取消下载$'", SOURCE)
        self.assertNotIn("'^核实本地结果$'", SOURCE)

    def test_dialog_close_is_scoped_away_from_frameless_caption(self):
        self.assertNotIn("Act $script:window '^关闭$'", SOURCE)
        self.assertIn("Close-TransferDialog '文件上传'", SOURCE)
        self.assertIn("Close-TransferDialog '文件下载'", SOURCE)
        self.assertIn("'ui', 'inspect', $dialog.selector", SOURCE)
        self.assertIn("$buttons.Count -ne 1", SOURCE)
        self.assertNotIn("'--root', $dialog.selector", SOURCE)

    def test_connection_failure_has_fixed_phase_and_numeric_diagnostics_only(self):
        for stage in ("connect-name", "connect-address", "connect-auth-expand", "connect-auth-select",
                      "connect-persistence-off", "connect-http-consent", "connect-submit", "connect-browser-ready"):
            self.assertIn("$script:stage = '" + stage + "'", SOURCE)
        observation = SOURCE.split("Record-Observation 'control-match-counts'", 1)[1].split("Stop-Smoke", 1)[0]
        for field in ("matches", "uniqueSelectors", "buttons", "edits", "comboBoxes", "listItems"):
            self.assertRegex(observation, field + r"\s*=.*\.Count")
        self.assertNotRegex(observation, r"(?:name|value|pattern|slug)\s*=")
        self.assertIn("Capture 'connect-failure.png'", SOURCE)
        self.assertIn("$script:stage.StartsWith('connect-'", SOURCE)
        self.assertIn("$script:app.HasExited", SOURCE)
        self.assertNotIn("Capture 'picker", SOURCE)

    def test_inspection_deduplicates_exact_selector_with_checked_metadata(self):
        helper = SOURCE.split("function Unique-Elements", 1)[1].split("function Elements", 1)[0]
        self.assertIn("[StringComparer]::Ordinal", helper)
        self.assertIn("TryGetValue($element.selector", helper)
        self.assertIn("'type', 'name', 'isEnabled', 'isOffscreen'", helper)
        self.assertIn("[object]::Equals($previous[$field], $element[$field])", helper)
        self.assertIn("'inspect_selector_metadata_mismatch'", helper)
        self.assertEqual(SOURCE.count("Unique-Elements (Find-Elements $tree)"), 2)
        self.assertIn("$matches.Count -ne 1", SOURCE)

    @unittest.skipUnless(shutil.which("pwsh"), "PowerShell unavailable; no installation attempted")
    def test_actual_inspection_helper_repeated_and_conflicting_elements(self):
        # Execute the production definitions, not a copied implementation.
        helpers = SOURCE.split("function Find-Elements", 1)[1].split("function Elements", 1)[0]
        command = "function Stop-Smoke($Code) { throw $Code }; function Find-Elements" + helpers + r"""
$one = @{ selector='fixture-a'; type='ListItem'; name='synthetic'; isEnabled=$true; isOffscreen=$false }
$same = @{ selector='fixture-a'; type='ListItem'; name='synthetic'; isEnabled=$true; isOffscreen=$false }
$tree = @{ windows=@(@{ children=@($one) }); repeated=@($same) }
$items = @(Unique-Elements (Find-Elements $tree))
if ($items.Count -ne 1 -or $items[0].selector -cne 'fixture-a') { throw 'repeat_not_collapsed' }
$different = @{ selector='fixture-b'; type='ListItem'; name='synthetic'; isEnabled=$true; isOffscreen=$false }
if (@(Unique-Elements @($one, $different)).Count -ne 2) { throw 'distinct_identity_collapsed' }
foreach ($field in @('type', 'name', 'isEnabled', 'isOffscreen')) {
    $conflict = $same.Clone()
    $conflict[$field] = if ($field -in @('isEnabled', 'isOffscreen')) { -not $conflict[$field] } else { 'different' }
    $code = $null
    try { $null = @(Unique-Elements @($one, $conflict)) } catch { $code = $_.Exception.Message }
    if ($code -cne 'inspect_selector_metadata_mismatch') { throw 'conflict_not_rejected' }
}
$invalid = $same.Clone(); $invalid.selector = ''
$code = $null
try { $null = @(Unique-Elements @($invalid)) } catch { $code = $_.Exception.Message }
if ($code -cne 'inspect_selector_missing') { throw 'invalid_identity_not_rejected' }
"""
        result = subprocess.run([shutil.which("pwsh"), "-NoProfile", "-Command", command],
                                capture_output=True, text=True, timeout=30)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    def test_conflict_asserts_same_bytes_after_real_ui_operation(self):
        self.assertEqual(SOURCE.count("Assert-Payload $remoteFile $expectedHash"), 2)
        self.assertEqual(SOURCE.count("Assert-Payload $destination $expectedHash"), 2)
        self.assertIn("$payload[0] = 255", SOURCE)
        self.assertIn("'download_destination_already_exists'", SOURCE)
        self.assertIn("[Guid]::NewGuid().ToString('N') + '.bin'", SOURCE)

    def test_success_requires_normal_app_and_owned_host_zero_exit(self):
        passed = SOURCE.index("Write-Report 'passed' 'transfer_smoke_ok'")
        for guard in ("application_exit_timeout", "application_exit_code_nonzero",
                      "owned_host_exit_timeout", "owned_host_exit_code_nonzero"):
            self.assertLess(SOURCE.index(guard), passed)
        self.assertIn("$script:ownedHost.StartTime -lt $script:app.StartTime", SOURCE)
        self.assertIn("'owned_host_identity_mismatch'", SOURCE)
        self.assertIn("'journey_timeout'", SOURCE)
        self.assertIn("'winapp_command_timeout'", SOURCE)
        self.assertGreater(SOURCE.index("$process.Kill($true)"), passed)

    @unittest.skipUnless(shutil.which("pwsh"), "PowerShell unavailable; no installation attempted")
    def test_actual_script_parses_with_powershell(self):
        path = str(ENG / "Test-TransferSmoke.ps1").replace("'", "''")
        command = ("$t=$null;$e=$null;[void][System.Management.Automation.Language.Parser]::ParseFile('"
                   + path + "',[ref]$t,[ref]$e);if($e.Count){$e|%{Write-Error $_};exit 1}")
        result = subprocess.run([shutil.which("pwsh"), "-NoProfile", "-Command", command],
                                capture_output=True, text=True, timeout=30)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)


if __name__ == "__main__":
    unittest.main()
