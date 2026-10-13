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

    def test_transfer_workflow_keeps_real_journey_without_playback_dependencies(self):
        for unused in ("vendor/mpv/", "pip install", "mpv-python", "-MediaRuntimeRoot", "-Publish"):
            self.assertNotIn(unused, WORKFLOW)
        for required in ("./eng/Build-Windows.ps1", "go build -o", "./eng/Test-TransferSmoke.ps1 -AppPath",
                         "./eng/Test-TransferSmoke.ps1 -PreflightOnly"):
            self.assertIn(required, WORKFLOW)
        player = (ENG.parents[2] / ".github" / "workflows" / "windows-player-smoke.yml").read_text(encoding="utf-8")
        for required in ("pip install --require-hashes", "vendor/mpv/resolve-runtime.py",
                         "vendor/mpv/probe-runtime.py", "-MediaRuntimeRoot"):
            self.assertIn(required, player)
        self.assertRegex(player, r"\./eng/Test-PlayerSmoke\.ps1[^\n]* -AppPath [^\n]* -MediaPath ")

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

    def test_all_nullable_name_predicates_are_guarded_and_error_categories_numeric(self):
        lines = SOURCE.splitlines()
        for index, line in enumerate(lines):
            if "$_.name" in line:
                self.assertTrue("Contains('name')" in line or
                                (line.strip().startswith("$_.name -ceq '关闭'") and "Contains('name')" in lines[index - 1]), line)
        categories = SOURCE.split("Record-Observation 'harness-error-category'", 1)[1].split("    }", 1)[0]
        for field in ("strictPropertyMissing", "strictVariableMissing", "invalidArgument"):
            self.assertRegex(categories, field + r"\s*= \[int\]")
        self.assertNotRegex(categories, r"(?:Message|StackTrace|ToString|name|path)\s*=")

    @unittest.skipUnless(shutil.which("pwsh"), "PowerShell unavailable; no installation attempted")
    def test_actual_nullable_name_and_dialog_helpers_under_strictmode(self):
        definitions = "function Find-Elements" + SOURCE.split("function Find-Elements", 1)[1].split("function Elements", 1)[0]
        for start, end in (("Find-Control", "Control"), ("Reveal-ConnectionSubmit", "Has-Text"),
                           ("Has-Text", "Wait-Text"), ("Find-TransferDialog", "Close-TransferDialog"),
                           ("Find-PickerAccept", "Accept-UploadPicker")):
            definitions += "function " + start + SOURCE.split("function " + start, 1)[1].split("function " + end, 1)[0]
        command = r"""
Set-StrictMode -Version Latest
function Stop-Smoke($Code) { throw $Code }
function Elements($Window) { $script:fixtures }
function Record-Observation($Name,$Details) { }
function Invoke-WinApp($Arguments) { throw 'unexpected_action' }
""" + definitions + r"""
$anonymous=@{selector='fixture-anonymous';type='Pane';isEnabled=$true;isOffscreen=$false}
$dialog=@{selector='fixture-dialog';type='Window';name='文件上传';isEnabled=$true;isOffscreen=$false}
$caption=@{selector='fixture-caption';type='Button';name='关闭';isEnabled=$true;isOffscreen=$false}
if ((Find-TransferDialog @($anonymous,$caption,$dialog) '文件上传').selector -cne 'fixture-dialog') { throw 'dialog_wrong' }
if ($null -ne (Find-TransferDialog @($anonymous,$caption) '文件上传')) { throw 'caption_selected_as_dialog' }
$second=$dialog.Clone();$second.selector='fixture-second';$code=$null
try { $null=Find-TransferDialog @($dialog,$second) '文件上传' } catch { $code=$_.Exception.Message }
if ($code -cne 'transfer_dialog_ambiguous') { throw 'dialog_ambiguity_ignored' }
$unnamedButton=$anonymous.Clone();$unnamedButton.type='Button';$unnamedButton.selector='fixture-anonymous-button'
if ((Find-DialogClose @{elements=@($anonymous,$unnamedButton,$caption)}).selector -cne 'fixture-caption') { throw 'close_wrong' }
$second=$caption.Clone();$second.selector='fixture-second';$code=$null
try { $null=Find-DialogClose @{elements=@($caption,$second)} } catch { $code=$_.Exception.Message }
if ($code -cne 'dialog_close_missing_or_ambiguous') { throw 'close_ambiguity_ignored' }
$unnamedEdit=$anonymous.Clone();$unnamedEdit.type='Edit';$unnamedEdit.selector='fixture-anonymous-edit'
$edit=@{selector='fixture-edit';type='Edit';name='valid';isEnabled=$true;isOffscreen=$false}
$unnamedText=$anonymous.Clone();$unnamedText.type='Text';$unnamedText.selector='fixture-anonymous-text'
$text=@{selector='fixture-text';type='Text';name='visible-text';isEnabled=$true;isOffscreen=$false}
$button=@{selector='fixture-connect';type='Button';name='连接并浏览';isEnabled=$true;isOffscreen=$false}
$script:fixtures=@($anonymous,$unnamedButton,$unnamedEdit,$unnamedText,$edit,$text,$button)
if ((Find-Control ([IntPtr]123) '^valid$' 'Edit').selector -cne 'fixture-edit') { throw 'control_wrong' }
if (-not (Has-Text ([IntPtr]123) 'visible-text')) { throw 'text_missing' }
Reveal-ConnectionSubmit ([IntPtr]123)
$accept=@{selector='fixture-accept';type='Button';name='Open';isEnabled=$true;isOffscreen=$false;automationId='1'}
$unnamedButton.automationId='1'
$tree=@{windows=@(@{hwnd=123;elements=@($unnamedButton,$accept)})}
if ((Find-PickerAccept $tree ([IntPtr]123)).selector -cne 'fixture-accept') { throw 'picker_wrong' }
"""
        result = subprocess.run([shutil.which("pwsh"), "-NoProfile", "-Command", command],
                                capture_output=True, text=True, timeout=30)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    def test_connection_failure_has_fixed_phase_and_numeric_diagnostics_only(self):
        for stage in ("connect-name", "connect-address", "connect-auth-expand", "connect-auth-select",
                      "connect-persistence-off", "connect-http-consent", "connect-submit-reveal", "connect-ready-capture", "connect-submit", "connect-browser-ready"):
            self.assertIn("$script:stage = '" + stage + "'", SOURCE)
        observation = SOURCE.split("Record-Observation 'control-match-counts'", 1)[1].split("Stop-Smoke", 1)[0]
        for field in ("matches", "uniqueSelectors", "buttons", "edits", "comboBoxes", "listItems",
                      "commonDialogFilenameIds", "commonDialogAcceptIds"):
            self.assertRegex(observation, field + r"\s*=.*\.Count")
        self.assertNotRegex(observation, r"(?:name|value|pattern|slug)\s*=")
        self.assertIn("Capture 'connect-failure.png'", SOURCE)
        self.assertIn("$script:stage.StartsWith('connect-'", SOURCE)
        self.assertIn("$script:app.HasExited", SOURCE)
        self.assertNotIn("Capture 'picker", SOURCE)

    def test_picker_diagnostics_do_not_choose_an_arbitrary_control(self):
        for stage in ("upload-open-command", "upload-picker-window", "upload-picker-name", "upload-picker-open",
                      "upload-confirmation", "upload-submit", "upload-result", "upload-close-detail",
                      "download-open-command", "download-confirmation", "download-submit", "download-result", "download-close-detail"):
            self.assertIn("$script:stage = '" + stage + "'", SOURCE)
        self.assertIn("$_.Contains('automationId')", SOURCE)
        self.assertIn("$_.automationId -ceq '1148'", SOURCE)
        self.assertIn("$_.automationId -ceq '1'", SOURCE)
        self.assertNotIn("$matches[0].automationId", SOURCE)
        self.assertNotIn("Capture 'upload-picker", SOURCE)

    def test_picker_accept_requires_owned_window_standard_id_and_matching_control(self):
        helper = SOURCE.split("function Find-PickerAccept", 1)[1].split("function Upload-File", 1)[0]
        for guard in ("$_.hwnd -eq $Picker.ToInt64()", "$ownedWindows.Count -ne 1", "$_.type -eq 'Button'",
                      "$_.isEnabled -and -not $_.isOffscreen", "$_.automationId -ceq '1'", "$matches.Count -ne 1",
                      "Native]::ProcessId($Picker) -ne $script:app.Id", "$result.hwnd -ne $Picker.ToInt64()"):
            self.assertIn(guard, helper)
        self.assertIn("Accept-UploadPicker $picker", SOURCE)
        self.assertNotIn("Act $picker", SOURCE)

    @unittest.skipUnless(shutil.which("pwsh"), "PowerShell unavailable; no installation attempted")
    def test_actual_picker_accept_helper_identity_and_scope_guards(self):
        traversal = "function Find-Elements" + SOURCE.split("function Find-Elements", 1)[1].split("function Elements", 1)[0]
        selector = "function Find-PickerAccept" + SOURCE.split("function Find-PickerAccept", 1)[1].split("function Accept-UploadPicker", 1)[0]
        command = "Set-StrictMode -Version Latest; function Stop-Smoke($Code) { throw $Code }; " + traversal + selector + r"""
$accept = @{selector='fixture-accept';type='Button';name='Open';isEnabled=$true;isOffscreen=$false;automationId='1'}
$other = $accept.Clone(); $other.selector='fixture-navigation'; $other.automationId='999'
$foreign = $accept.Clone(); $foreign.selector='fixture-foreign'
$tree = @{windows=@(@{hwnd=123;elements=@($other,$accept)}, @{hwnd=456;elements=@($foreign)})}
$selected = Find-PickerAccept $tree ([IntPtr]123)
if ($selected.selector -cne 'fixture-accept') { throw 'wrong_button_selected' }
$duplicate = $accept.Clone(); $duplicate.selector='fixture-second'
$tree.windows[0].elements=@($accept,$duplicate)
$code=$null
try { $null=Find-PickerAccept $tree ([IntPtr]123) } catch { $code=$_.Exception.Message }
if ($code -cne 'picker_accept_missing_or_ambiguous') { throw 'duplicate_accept_not_rejected' }
foreach ($field in @('type','name','isEnabled','isOffscreen','automationId')) {
    $invalid=$accept.Clone()
    $invalid[$field] = if ($field -in @('isEnabled','isOffscreen')) { -not $invalid[$field] } else { 'different' }
    $tree.windows[0].elements=@($other,$invalid); $code=$null
    try { $null=Find-PickerAccept $tree ([IntPtr]123) } catch { $code=$_.Exception.Message }
    if ($code -cne 'picker_accept_missing_or_ambiguous') { throw 'invalid_control_not_rejected' }
}
$conflict=$accept.Clone(); $conflict.automationId='999'
$tree.windows[0].elements=@($accept,$conflict); $code=$null
try { $null=Find-PickerAccept $tree ([IntPtr]123) } catch { $code=$_.Exception.Message }
if ($code -cne 'inspect_selector_metadata_mismatch') { throw 'conflicting_ids_not_rejected' }
$tree.windows=@(@{hwnd=456;elements=@($foreign)}); $code=$null
try { $null=Find-PickerAccept $tree ([IntPtr]123) } catch { $code=$_.Exception.Message }
if ($code -cne 'picker_inspection_window_ambiguous') { throw 'foreign_window_selected' }
$tree.windows=@(@{hwnd=123;elements=@($accept)}, @{hwnd=123;elements=@($accept)}); $code=$null
try { $null=Find-PickerAccept $tree ([IntPtr]123) } catch { $code=$_.Exception.Message }
if ($code -cne 'picker_inspection_window_ambiguous') { throw 'ambiguous_window_selected' }
"""
        result = subprocess.run([shutil.which("pwsh"), "-NoProfile", "-Command", command],
                                capture_output=True, text=True, timeout=30)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    def test_connection_and_terminal_center_captures_are_scoped_real_states(self):
        self.assertLess(SOURCE.index("Reveal-ConnectionSubmit $script:window"), SOURCE.index("Capture 'connect-ready.png'"))
        self.assertLess(SOURCE.index("Capture 'connect-ready.png'"), SOURCE.index("Act $script:window '^连接并浏览$'"))
        captures = SOURCE.split("$script:stage = 'transfer-center-uploads'", 1)[1].split("$script:stage = 'application-shutdown'", 1)[0]
        for label in ("上传已完成", "目标存在同名项目", "下载已完成", "本地存在同名文件"):
            self.assertIn("Wait-Text '" + label + "'", captures)
        self.assertLess(SOURCE.index("Complete-Step 'native-upload-conflict-preserved'"), SOURCE.index("Capture 'transfer-center-uploads.png'"))
        self.assertNotRegex(captures, r"(?:Resume|Reconcile|Cancel|恢复|核实|取消)")
        self.assertIn("'^传输中心$'", captures)
        self.assertIn("Activate-DownloadRecords $script:window", captures)

    def test_download_tab_uses_targeted_real_keyboard_and_observed_state(self):
        helper = SOURCE.split("function Activate-DownloadRecords", 1)[1].split("function Has-Text", 1)[0]
        for guard in ("Control $Window '^下载记录$'", "Contains('toggleState')", "'ui', 'send-keys', 'space', '--target', $element.selector",
                      "'--via', 'send-input'", "$result.actionCount -ne 1", "@($result.warnings).Count -ne 0", "$current.toggleState -ceq 'on'"):
            self.assertIn(guard, helper)
        self.assertNotIn("Act $script:window '^下载记录$'", SOURCE)
        self.assertNotRegex(helper, r"--at|--allow-system-keys|post-message")

    @unittest.skipUnless(shutil.which("pwsh"), "PowerShell unavailable; no installation attempted")
    def test_actual_download_tab_keyboard_helper_contract_and_effect(self):
        helper = "function Activate-DownloadRecords" + SOURCE.split("function Activate-DownloadRecords", 1)[1].split("function Has-Text", 1)[0]
        command = r"""
Set-StrictMode -Version Latest
function Stop-Smoke($Code) { throw $Code }
function Control($Window,$Pattern) { $script:fixture }
function Find-Control($Window,$Pattern,[switch]$Optional) { $script:fixture }
function Wait-Condition($Predicate,$Code) { $value=& $Predicate; if (-not $value) { Stop-Smoke $Code }; return $value }
function Invoke-WinApp($Arguments) {
    $script:calls.Add($Arguments)
    if ($script:setAfterInput) { $script:fixture.toggleState='on' }
    return $script:response
}
""" + helper + r"""
$script:calls=[Collections.Generic.List[object]]::new()
$script:fixture=@{selector='fixture-tab';toggleState='off'}
$script:response=@{hwnd=123;keys='space';via='send-input';target='fixture-tab';actionCount=1;warnings=@()}
$script:setAfterInput=$true
Activate-DownloadRecords ([IntPtr]123)
if ($script:calls.Count -ne 1 -or ($script:calls[0] -join '|') -cne 'ui|send-keys|space|--target|fixture-tab|-w|123|--via|send-input|--json') { throw 'keyboard_arguments_wrong' }
Activate-DownloadRecords ([IntPtr]123)
if ($script:calls.Count -ne 1) { throw 'selected_tab_activated_again' }
foreach ($field in @('hwnd','keys','via','target','actionCount','warnings')) {
    $script:fixture.toggleState='off'
    $script:response=@{hwnd=123;keys='space';via='send-input';target='fixture-tab';actionCount=1;warnings=@()}
    $script:response[$field]=switch ($field) { 'hwnd' { 999 }; 'actionCount' { 2 }; 'warnings' { @('fixture-warning') }; default { 'wrong' } }
    $code=$null
    try { Activate-DownloadRecords ([IntPtr]123) } catch { $code=$_.Exception.Message }
    if ($code -cne 'download_records_keyboard_contract_mismatch') { throw 'keyboard_response_not_rejected' }
}
$script:fixture=@{selector='fixture-tab'};$before=$script:calls.Count;$code=$null
try { Activate-DownloadRecords ([IntPtr]123) } catch { $code=$_.Exception.Message }
if ($code -cne 'download_records_toggle_state_missing' -or $script:calls.Count -ne $before) { throw 'missing_toggle_state_activated' }
$script:fixture=@{selector='fixture-tab';toggleState='off'}
$script:response=@{hwnd=123;keys='space';via='send-input';target='fixture-tab';actionCount=1;warnings=@()}
$script:setAfterInput=$false;$code=$null
try { Activate-DownloadRecords ([IntPtr]123) } catch { $code=$_.Exception.Message }
if ($code -cne 'download_records_not_selected') { throw 'unobserved_effect_accepted' }
"""
        result = subprocess.run([shutil.which("pwsh"), "-NoProfile", "-Command", command],
                                capture_output=True, text=True, timeout=30)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    def test_inspection_deduplicates_exact_selector_with_checked_metadata(self):
        helper = SOURCE.split("function Unique-Elements", 1)[1].split("function Elements", 1)[0]
        self.assertIn("[StringComparer]::Ordinal", helper)
        self.assertIn("TryGetValue($element.selector", helper)
        self.assertIn("'type', 'name', 'isEnabled', 'isOffscreen', 'automationId'", helper)
        self.assertIn("[object]::Equals($previous[$field], $element[$field])", helper)
        self.assertIn("'inspect_selector_metadata_mismatch'", helper)
        self.assertEqual(len(re.findall(r"Unique-Elements \(Find-Elements \$tree\)", SOURCE, re.I)), 2)
        self.assertIn("$matches.Count -ne 1", SOURCE)

    def test_connection_scroll_preserves_visible_action_guard(self):
        helper = SOURCE.split("function Reveal-ConnectionSubmit", 1)[1].split("function Has-Text", 1)[0]
        self.assertIn("$matches.Count -ne 1", helper)
        self.assertIn("$_.isEnabled", helper)
        self.assertIn("$matches[0].isOffscreen", helper)
        self.assertIn("'ui', 'scroll', $matches[0].selector", helper)
        self.assertIn("'--to', 'bottom'", helper)
        self.assertIn("'connection_scroll_contract_mismatch'", helper)
        self.assertNotIn("'invoke'", helper)
        self.assertLess(SOURCE.index("Reveal-ConnectionSubmit $script:window"),
                        SOURCE.index("Act $script:window '^连接并浏览$'"))
        self.assertIn("$_.isEnabled -and -not $_.isOffscreen", SOURCE)

    @unittest.skipUnless(shutil.which("pwsh"), "PowerShell unavailable; no installation attempted")
    def test_actual_connection_scroll_helper_guards(self):
        helper = "function Reveal-ConnectionSubmit" + SOURCE.split("function Reveal-ConnectionSubmit", 1)[1].split("function Has-Text", 1)[0]
        command = r"""
Set-StrictMode -Version Latest
function Stop-Smoke($Code) { throw $Code }
function Elements($Window) { $script:fixtures }
function Invoke-WinApp($Arguments) {
    $script:calls.Add($Arguments)
    return $script:response
}
""" + helper + r"""
$script:calls = [Collections.Generic.List[object]]::new()
$script:fixtures = @(@{selector='fixture-submit';type='Button';name='连接并浏览';isEnabled=$true;isOffscreen=$true})
$script:response = @{elementId='fixture-submit';hwnd=123;to='bottom'}
Reveal-ConnectionSubmit ([IntPtr]123)
if ($script:calls.Count -ne 1 -or ($script:calls[0] -join '|') -cne 'ui|scroll|fixture-submit|-w|123|--to|bottom|--json') { throw 'scroll_arguments_mismatch' }
$script:fixtures[0].isOffscreen = $false
Reveal-ConnectionSubmit ([IntPtr]123)
if ($script:calls.Count -ne 1) { throw 'visible_target_scrolled' }
$script:fixtures[0].isOffscreen = $true
foreach ($field in @('elementId', 'hwnd', 'to', 'direction', 'wheel')) {
    $script:response = @{elementId='fixture-submit';hwnd=123;to='bottom'}
    $script:response[$field] = if ($field -eq 'hwnd') { 999 } else { 'wrong' }
    $code = $null
    try { Reveal-ConnectionSubmit ([IntPtr]123) } catch { $code = $_.Exception.Message }
    if ($code -cne 'connection_scroll_contract_mismatch') { throw 'response_not_rejected' }
}
$script:fixtures += @(@{selector='fixture-second';type='Button';name='连接并浏览';isEnabled=$true;isOffscreen=$true})
$before = $script:calls.Count; $code = $null
try { Reveal-ConnectionSubmit ([IntPtr]123) } catch { $code = $_.Exception.Message }
if ($code -cne 'connection_submit_scroll_target_ambiguous' -or $script:calls.Count -ne $before) { throw 'ambiguous_target_scrolled' }
$script:fixtures = @(@{selector='fixture-disabled';type='Button';name='连接并浏览';isEnabled=$false;isOffscreen=$true})
$code = $null
try { Reveal-ConnectionSubmit ([IntPtr]123) } catch { $code = $_.Exception.Message }
if ($code -cne 'connection_submit_scroll_target_ambiguous' -or $script:calls.Count -ne $before) { throw 'disabled_target_scrolled' }
"""
        result = subprocess.run([shutil.which("pwsh"), "-NoProfile", "-Command", command],
                                capture_output=True, text=True, timeout=30)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

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
foreach ($field in @('type', 'name', 'isEnabled', 'isOffscreen', 'automationId')) {
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

    def test_failure_diagnostics_preserve_original_result_and_scoped_capture(self):
        self.assertIn("$script:stage -eq 'upload-result' -and $script:app -and -not $script:app.HasExited", SOURCE)
        self.assertIn("Capture 'upload-result-failure.png'", SOURCE)
        known = SOURCE.split("Record-Observation 'upload-result-known-status'", 1)[1].split("        } catch", 1)[0]
        for field in ("completed", "conflict", "failed", "unknown", "uploading", "reconciling"):
            self.assertRegex(known, field + r" = \[int\]\(Has-Text")
        helper = SOURCE.split("function Observe-FailureShutdown", 1)[1].split("function Wait-Condition", 1)[0]
        self.assertIn("$script:app.WaitForExit(30000)", helper)
        self.assertIn("$script:ownedHost.WaitForExit(15000)", helper)
        self.assertNotRegex(helper, r"Write-Report|Complete-Step|Kill|GetProcessById|Process\]::Start")
        self.assertLess(SOURCE.index("Write-Report $status $code"), SOURCE.index("(Observe-FailureShutdown)"))
        self.assertLess(SOURCE.index("(Observe-FailureShutdown)"), SOURCE.index("$process.Kill($true)"))

    @unittest.skipUnless(shutil.which("pwsh"), "PowerShell unavailable; no installation attempted")
    def test_actual_failure_shutdown_helper_reports_numeric_outcomes(self):
        helper = "function Observe-FailureShutdown" + SOURCE.split("function Observe-FailureShutdown", 1)[1].split("function Wait-Condition", 1)[0]
        command = "Set-StrictMode -Version Latest; " + helper + r"""
function New-FixtureProcess($Exited,$ExitCode,$Accepted,$WaitResult,$Throws) {
    $value=[pscustomobject]@{HasExited=$Exited;ExitCode=$ExitCode;Accepted=$Accepted;WaitResult=$WaitResult;Throws=$Throws;CloseCalls=0;Budgets=[Collections.Generic.List[int]]::new()}
    $value | Add-Member -MemberType ScriptMethod -Name CloseMainWindow -Value { $this.CloseCalls++; if ($this.Throws) { throw 'fixture-error' }; return $this.Accepted }
    $value | Add-Member -MemberType ScriptMethod -Name WaitForExit -Value { param($Budget);$this.Budgets.Add($Budget);return $this.WaitResult }
    return $value
}
$script:app=New-FixtureProcess $false -1073740791 $true $true $false
$script:ownedHost=New-FixtureProcess $false 0 $false $true $false
$result=Observe-FailureShutdown
if ($result.closeRequested -ne 1 -or $result.appExited -ne 1 -or $result.appExitCode -ne -1073740791 -or $result.hostExited -ne 1 -or $result.hostExitCode -ne 0 -or $result.error -ne 0) { throw 'fault_not_retained' }
if ($script:app.Budgets[0] -ne 30000 -or $script:ownedHost.Budgets[0] -ne 15000) { throw 'budgets_wrong' }
$script:app=New-FixtureProcess $false 0 $false $false $false
$script:ownedHost=New-FixtureProcess $false 0 $false $false $false
$result=Observe-FailureShutdown
if ($result.closeRequested -ne 0 -or $result.appExited -ne 0 -or $result.hostExited -ne 0 -or $result.appExitCode -ne 0 -or $result.hostExitCode -ne 0) { throw 'timeout_reported_as_exit' }
$script:app=New-FixtureProcess $true 0 $false $true $false
$script:ownedHost=New-FixtureProcess $true 0 $false $true $false
$result=Observe-FailureShutdown
if ($script:app.CloseCalls -ne 0 -or $result.appExited -ne 1 -or $result.hostExited -ne 1 -or $result.error -ne 0) { throw 'already_exited_wrong' }
$script:app=New-FixtureProcess $false 0 $true $true $true
$script:ownedHost=New-FixtureProcess $true 0 $false $true $false
$result=Observe-FailureShutdown
if ($result.error -ne 1 -or $result.hostExited -ne 1) { throw 'error_or_host_observation_lost' }
foreach ($value in $result.Values) { if ($value -isnot [int]) { throw 'nonnumeric_diagnostic' } }
"""
        result = subprocess.run([shutil.which("pwsh"), "-NoProfile", "-Command", command],
                                capture_output=True, text=True, timeout=30)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

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
