#requires -Version 7.2
[CmdletBinding()]
param(
    [string] $AppPath,
    [string] $ServerPath,
    [Parameter(Mandatory)][string] $WinAppPath,
    [Parameter(Mandatory)][string] $OutputRoot,
    [switch] $PreflightOnly
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

# Actual v0.7.1 commands/envelopes, not guessed switches:
# https://github.com/microsoft/winappCli/blob/v0.7.1/docs/ui-automation.md
# No HTTP substitute performs a product operation. Only /health is probed here.
$allowedArtifacts = @('report.json', 'events.jsonl', 'preflight.png', 'preflight-smoke.png',
    'upload-confirm.png', 'upload-completed.png', 'download-confirm.png',
    'download-completed.png', 'download-conflict.png', 'upload-conflict.png', 'connect-failure.png',
    'connect-ready.png', 'transfer-center-uploads.png', 'transfer-center-downloads.png', 'upload-result-failure.png',
    'download-save-as-confirm.png', 'download-save-as-completed.png', 'download-save-as-menu-failure.png')
$script:root = $null
$script:app = $null
$script:server = $null
$script:ownedHost = $null
$script:window = [IntPtr]::Zero
$script:stage = 'output-validation'
$script:clock = [Diagnostics.Stopwatch]::StartNew()
$script:completed = [Collections.Generic.List[string]]::new()
$script:privateRoot = $null
$oldWorkflow = $env:WINAPP_UI_WORKFLOW_ID
$oldTelemetry = $env:WINAPP_CLI_TELEMETRY_OPTOUT

function Stop-Smoke([string] $Code, [switch] $EnvironmentBlocked) {
    $errorObject = [InvalidOperationException]::new($Code)
    $errorObject.Data['smokeCode'] = $Code
    $errorObject.Data['smokeStatus'] = $(if ($EnvironmentBlocked) { 'environment-blocked' } else { 'failed' })
    throw $errorObject
}
function Assert-ExternalPath([string] $Value) {
    if (-not [IO.Path]::IsPathFullyQualified($Value)) { Stop-Smoke 'absolute_path_required' }
    $path = [IO.Path]::GetFullPath($Value)
    $item = [IO.DirectoryInfo]::new($path)
    while ($null -ne $item) {
        if ($item.Exists -and ($item.Attributes -band [IO.FileAttributes]::ReparsePoint)) { Stop-Smoke 'reparse_path_rejected' }
        $marker = Join-Path $item.FullName '.git'
        if ([IO.File]::Exists($marker) -or [IO.File]::Exists((Join-Path $marker 'HEAD'))) { Stop-Smoke 'private_output_inside_git' }
        $item = $item.Parent
    }
    return $path
}
function Artifact-Path([string] $Name) {
    if ($Name -notin $allowedArtifacts) { Stop-Smoke 'artifact_name_not_allowed' }
    if ($Name.EndsWith('.png', [StringComparison]::Ordinal)) { return Join-Path (Join-Path $script:root 'screenshots') $Name }
    return Join-Path $script:root $Name
}
function Write-Report([string] $Status, [string] $Code) {
    $value = [ordered]@{ schemaVersion = 1; scope = $(if ($PreflightOnly) { 'preflight' } else { 'native-transfer-smoke' });
        status = $Status; code = $Code; stage = $script:stage; completedSteps = @($script:completed);
        nativeTransferVerified = ($Status -eq 'passed'); cancellationUiVerified = $false; unknownOutcomeUiVerified = $false; saveAsUiVerified = ($Status -eq 'passed');
        checksumUiVerified = $false; checksumUiExclusionReason = 'product_scope_removed';
        elapsedMilliseconds = $script:clock.ElapsedMilliseconds }
    [IO.File]::WriteAllText((Artifact-Path 'report.json'), ($value | ConvertTo-Json -Depth 4) + "`n", [Text.UTF8Encoding]::new($false))
}
function Complete-Step([string] $Name, $Details = @{}) {
    $script:completed.Add($Name)
    $value = [ordered]@{ step = $Name; status = 'passed'; elapsedMilliseconds = $script:clock.ElapsedMilliseconds; details = $Details }
    [IO.File]::AppendAllText((Artifact-Path 'events.jsonl'), ($value | ConvertTo-Json -Depth 4 -Compress) + "`n", [Text.UTF8Encoding]::new($false))
}
function Record-Observation([string] $Name, $Details) {
    $value = [ordered]@{ observation = $Name; stage = $script:stage; elapsedMilliseconds = $script:clock.ElapsedMilliseconds; details = $Details }
    [IO.File]::AppendAllText((Artifact-Path 'events.jsonl'), ($value | ConvertTo-Json -Depth 4 -Compress) + "`n", [Text.UTF8Encoding]::new($false))
}
function Observe-FailureShutdown {
    # These are the already captured exact App/Host process objects and handles.
    # A diagnostic normal-close attempt cannot change the original failed report.
    $details = [ordered]@{ closeRequested = 0; appExited = 0; appExitCode = 0; hostExited = 0; hostExitCode = 0; error = 0 }
    try {
        if (-not $script:app.HasExited) { $details.closeRequested = [int]$script:app.CloseMainWindow() }
        $details.appExited = [int]$script:app.WaitForExit(30000)
        if ($details.appExited) { $details.appExitCode = $script:app.ExitCode }
    } catch { $details.error = 1 }
    try {
        $details.hostExited = [int]$script:ownedHost.WaitForExit(15000)
        if ($details.hostExited) { $details.hostExitCode = $script:ownedHost.ExitCode }
    } catch { $details.error = 1 }
    return $details
}
function Wait-Condition([scriptblock] $Predicate, [string] $Code, [int] $TimeoutMilliseconds = 15000) {
    $watch = [Diagnostics.Stopwatch]::StartNew()
    while ($watch.ElapsedMilliseconds -lt $TimeoutMilliseconds) {
        if ($script:clock.Elapsed.TotalMinutes -gt 8) { Stop-Smoke 'journey_timeout' }
        if ($script:app -and $script:app.HasExited) { Stop-Smoke 'app_exited_early' }
        if ($script:server -and $script:server.HasExited) { Stop-Smoke 'server_exited_early' }
        $value = & $Predicate
        if ($value) { return $value }
        Start-Sleep -Milliseconds 150
    }
    Stop-Smoke $Code
}
function Invoke-WinApp([string[]] $Arguments) {
    $start = [Diagnostics.ProcessStartInfo]::new($WinAppPath)
    $start.UseShellExecute = $false; $start.CreateNoWindow = $true
    $start.RedirectStandardOutput = $true; $start.RedirectStandardError = $true
    foreach ($argument in $Arguments) { $start.ArgumentList.Add($argument) }
    $command = [Diagnostics.Process]::Start($start)
    try {
        $stdout = $command.StandardOutput.ReadToEndAsync(); $stderr = $command.StandardError.ReadToEndAsync()
        if (-not $command.WaitForExit(15000)) { $command.Kill($true); Stop-Smoke 'winapp_command_timeout' }
        $raw = $stdout.GetAwaiter().GetResult(); $errorText = $stderr.GetAwaiter().GetResult()
        if ($raw.Length -gt 1048576 -or $errorText.Length -gt 1048576) { Stop-Smoke 'winapp_output_too_large' }
        if ($command.ExitCode -ne 0) {
            $code = 'winapp_command_failed'
            try { $envelope = $errorText | ConvertFrom-Json -AsHashtable; if ($envelope.error.code -match '^[a-z_]{1,64}$') { $code = $envelope.error.code } } catch { }
            Stop-Smoke $code -EnvironmentBlocked:($code -in @('no_interactive_desktop', 'foreground_not_target', 'capture_failed', 'desktop_coordination_unavailable'))
        }
        try { return ($raw | ConvertFrom-Json -AsHashtable) } catch { Stop-Smoke 'winapp_json_contract_mismatch' }
    } finally { $command.Dispose() }
}
function Find-Elements($Value) {
    if ($Value -is [Collections.IDictionary]) {
        if ($Value.Contains('selector') -and $Value.Contains('type')) { $Value }
        foreach ($child in $Value.Values) { Find-Elements $child }
    } elseif ($Value -is [Collections.IEnumerable] -and $Value -isnot [string]) {
        foreach ($child in $Value) { Find-Elements $child }
    }
}
function Unique-Elements($Elements) {
    # inspect can repeat one element in its envelope and subtree. Identity is
    # the exact CLI selector, never a displayed name or a guessed coordinate.
    $seen = [Collections.Generic.Dictionary[string, object]]::new([StringComparer]::Ordinal)
    foreach ($element in $Elements) {
        if ($element.selector -isnot [string] -or [string]::IsNullOrWhiteSpace($element.selector)) { Stop-Smoke 'inspect_selector_missing' }
        $previous = $null
        if ($seen.TryGetValue($element.selector, [ref]$previous)) {
            foreach ($field in @('type', 'name', 'isEnabled', 'isOffscreen', 'automationId')) {
                if (-not [object]::Equals($previous[$field], $element[$field])) { Stop-Smoke 'inspect_selector_metadata_mismatch' }
            }
        } else {
            $seen.Add($element.selector, $element)
            $element
        }
    }
}
function Elements([IntPtr] $Window) {
    # A progress update can change metadata during one live inspection. Discard
    # that whole inconsistent tree; never merge identities or use partial output.
    for ($attempt = 1; $attempt -le 3; $attempt++) {
        try {
            $tree = Invoke-WinApp @('ui', 'inspect', '-w', $Window.ToInt64().ToString(), '--depth', '16', '--json')
            if (-not $tree.Contains('windows')) { Stop-Smoke 'inspect_windows_contract_missing' }
            $elements = @(Unique-Elements (Find-Elements $tree))
            return $elements
        } catch {
            if ($_.Exception.Data['smokeCode'] -cne 'inspect_selector_metadata_mismatch' -or $attempt -eq 3) { throw }
            Start-Sleep -Milliseconds 100
        }
    }
}
function Find-Control([IntPtr] $Window, [string] $NamePattern, [string] $Type = 'Button', [switch] $Optional) {
    $visible = @(Elements $Window | Where-Object { $_.isEnabled -and -not $_.isOffscreen })
    $matches = @($visible | Where-Object { $_.type -eq $Type -and $_.Contains('name') -and $_.name -match $NamePattern })
    if ($matches.Count -eq 0 -and $Optional) { return $null }
    if ($matches.Count -ne 1) {
        # Only numeric structure is retained: never UIA names, values, slugs or
        # the selector pattern, which can include a synthetic local filename.
        Record-Observation 'control-match-counts' ([ordered]@{
            matches = $matches.Count; uniqueSelectors = @($matches | ForEach-Object { $_.selector } | Sort-Object -Unique).Count;
            buttons = @($visible | Where-Object type -eq 'Button').Count;
            edits = @($visible | Where-Object type -eq 'Edit').Count;
            comboBoxes = @($visible | Where-Object type -eq 'ComboBox').Count;
            listItems = @($visible | Where-Object type -eq 'ListItem').Count;
            commonDialogFilenameIds = @($matches | Where-Object { $_.Contains('automationId') -and $_.automationId -ceq '1148' }).Count;
            commonDialogAcceptIds = @($matches | Where-Object { $_.Contains('automationId') -and $_.automationId -ceq '1' }).Count;
        })
        Stop-Smoke 'control_missing_or_ambiguous'
    }
    return $matches[0]
}
function Control([IntPtr] $Window, [string] $Pattern, [string] $Type = 'Button') {
    return Wait-Condition { Find-Control $Window $Pattern $Type -Optional } 'expected_control_not_observed'
}
function Act([IntPtr] $Window, [string] $Pattern, [string] $Type = 'Button', [string] $Action = 'invoke') {
    $element = Control $Window $Pattern $Type
    $result = Invoke-WinApp @('ui', 'invoke', $element.selector, '-w', $Window.ToInt64().ToString(), '--action', $Action, '--json')
    $performed = switch ($Action) { 'toggle-on' { @('toggle', 'none') } 'toggle-off' { @('toggle', 'none') } default { @($Action) } }
    if ($result.hwnd -ne $Window.ToInt64() -or $result.performedAction -notin $performed) {
        Record-Observation 'invoke-effect-mismatch' ([ordered]@{
            sameWindow = [int]($result.hwnd -eq $Window.ToInt64()); actualWindowZero = [int]($result.hwnd -eq 0);
            actualWindowOwned = [int]($script:app -and [Fileway.PlayerSmoke.Native]::ProcessId([IntPtr]$result.hwnd) -eq $script:app.Id);
            actualActionSelect = [int]($result.performedAction -ceq 'select'); actualActionInvoke = [int]($result.performedAction -ceq 'invoke');
            actualActionNone = [int]($result.performedAction -ceq 'none'); expectedActionMatches = [int]($result.performedAction -in $performed);
        })
        Stop-Smoke 'invoke_effect_contract_mismatch'
    }
}
function Set-Value([IntPtr] $Window, [string] $Pattern, [string] $Value) {
    $element = Control $Window $Pattern 'Edit'
    $null = Invoke-WinApp @('ui', 'set-value', $element.selector, $Value, '-w', $Window.ToInt64().ToString(), '--json')
    $null = Invoke-WinApp @('ui', 'wait-for', $element.selector, '-w', $Window.ToInt64().ToString(), '--value', $Value, '--timeout', '5000', '--json')
}
function Reveal-ConnectionSubmit([IntPtr] $Window) {
    # A short CI desktop can put this button below the connection ScrollViewer.
    # ScrollPattern walks to its nearest scrollable parent; the later Act still
    # requires a fresh unique, visible and enabled button before invoking it.
    $matches = @(Elements $Window | Where-Object { $_.type -eq 'Button' -and $_.Contains('name') -and $_.name -ceq '连接并浏览' -and $_.isEnabled })
    if ($matches.Count -ne 1) { Stop-Smoke 'connection_submit_scroll_target_ambiguous' }
    if ($matches[0].isOffscreen) {
        $result = Invoke-WinApp @('ui', 'scroll', $matches[0].selector, '-w', $Window.ToInt64().ToString(), '--to', 'bottom', '--json')
        if ($result.hwnd -ne $Window.ToInt64() -or $result.elementId -cne $matches[0].selector -or $result.to -cne 'bottom' -or
            ($result.Contains('direction') -and $null -ne $result.direction) -or
            ($result.Contains('wheel') -and $null -ne $result.wheel)) { Stop-Smoke 'connection_scroll_contract_mismatch' }
    }
}
function Activate-DownloadRecords([IntPtr] $Window) {
    # ToggleButton exposes TogglePattern, not InvokePattern. Use its real
    # keyboard activation so the product's Click navigation handler runs too.
    $element = Control $Window '^下载记录$'
    if (-not $element.Contains('toggleState') -or $element.toggleState -notin @('on', 'off')) { Stop-Smoke 'download_records_toggle_state_missing' }
    if ($element.toggleState -ceq 'off') {
        $result = Invoke-WinApp @('ui', 'send-keys', 'space', '--target', $element.selector, '-w', $Window.ToInt64().ToString(), '--via', 'send-input', '--json')
        if ($result.hwnd -ne $Window.ToInt64() -or $result.keys -cne 'space' -or $result.via -cne 'send-input' -or
            $result.target -cne $element.selector -or $result.actionCount -ne 1 -or @($result.warnings).Count -ne 0) { Stop-Smoke 'download_records_keyboard_contract_mismatch' }
    }
    $null = Wait-Condition {
        $current = Find-Control $Window '^下载记录$' -Optional
        if ($current -and $current.Contains('toggleState') -and $current.toggleState -ceq 'on') { $current }
    } 'download_records_not_selected'
}
function Has-Text([IntPtr] $Window, [string] $Text) {
    return @(Elements $Window | Where-Object { $_.type -eq 'Text' -and $_.Contains('name') -and $_.name -ceq $Text -and -not $_.isOffscreen }).Count -gt 0
}
function Wait-Text([string] $Text) {
    $null = Wait-Condition { Has-Text $script:window $Text } 'expected_stage_not_observed' 45000
}
function Capture([string] $Name) {
    # Uses the already tested no-clobber PNG writer and two-sided foreground/
    # foreign-window checks. Never capture the system FileOpenPicker.
    if ([Fileway.PlayerSmoke.Native]::ProcessId($script:window) -ne $script:app.Id) { Stop-Smoke 'capture_owner_mismatch' }
    [Fileway.PlayerSmoke.Native]::Capture($script:window).SavePng((Artifact-Path $Name))
}
function Find-TransferDialog($Elements, [string] $Title) {
    # UiElement.Name is nullable and omitted by the pinned JSON serializer.
    # Anonymous layout elements are not candidates under StrictMode.
    $matches = @($Elements | Where-Object { $_.Contains('name') -and $_.name -ceq $Title -and
        $_.type -in @('Window', 'Pane', 'Custom') -and -not $_.isOffscreen })
    if ($matches.Count -gt 1) { Stop-Smoke 'transfer_dialog_ambiguous' }
    if ($matches.Count -eq 1) { return $matches[0] }
    return $null
}
function Find-DialogClose($Tree) {
    $buttons = @(Unique-Elements (Find-Elements $Tree) | Where-Object { $_.type -eq 'Button' -and $_.Contains('name') -and
        $_.name -ceq '关闭' -and $_.isEnabled -and -not $_.isOffscreen })
    if ($buttons.Count -ne 1) { Stop-Smoke 'dialog_close_missing_or_ambiguous' }
    return $buttons[0]
}
function Close-TransferDialog([string] $Title) {
    # The frameless caption also has a Chinese "关闭" button. Constrain the
    # action to the actual ContentDialog subtree, never the whole main window.
    $dialog = Wait-Condition { Find-TransferDialog (Elements $script:window) $Title } 'transfer_dialog_not_observed'
    $tree = Invoke-WinApp @('ui', 'inspect', $dialog.selector, '-w', $script:window.ToInt64().ToString(), '--depth', '16', '--json')
    $button = Find-DialogClose $tree
    $result = Invoke-WinApp @('ui', 'invoke', $button.selector, '-w', $script:window.ToInt64().ToString(), '--action', 'invoke', '--json')
    if ($result.performedAction -ne 'invoke' -or $result.hwnd -ne $script:window.ToInt64()) { Stop-Smoke 'dialog_close_effect_mismatch' }
}
function Find-PickerAccept($Tree, [IntPtr] $Picker) {
    if (-not $Tree.Contains('windows')) { Stop-Smoke 'inspect_windows_contract_missing' }
    $ownedWindows = @($Tree.windows | Where-Object { $_.hwnd -eq $Picker.ToInt64() })
    if ($ownedWindows.Count -ne 1) { Stop-Smoke 'picker_inspection_window_ambiguous' }
    # IDOK=1 is the standard accept button, not shell/navigation buttons also
    # named Open. Restrict to this exact common-dialog HWND before matching.
    $matches = @(Unique-Elements (Find-Elements $ownedWindows[0].elements) | Where-Object {
        $_.type -eq 'Button' -and $_.Contains('name') -and $_.name -match '^(?:选择文件|Open(?:\(O\))?|打开(?:\(O\))?)$' -and
        $_.isEnabled -and -not $_.isOffscreen -and $_.Contains('automationId') -and $_.automationId -ceq '1'
    })
    if ($matches.Count -ne 1) { Stop-Smoke 'picker_accept_missing_or_ambiguous' }
    return $matches[0]
}
function Accept-UploadPicker([IntPtr] $Picker) {
    if ($Picker -eq $script:window -or [Fileway.PlayerSmoke.Native]::ProcessId($Picker) -ne $script:app.Id -or
        [Fileway.PlayerSmoke.Native]::Title($Picker) -notmatch '选择要上传的文件|Open|打开') { Stop-Smoke 'picker_owner_mismatch' }
    $tree = Invoke-WinApp @('ui', 'inspect', '-w', $Picker.ToInt64().ToString(), '--depth', '16', '--json')
    $element = Find-PickerAccept $tree $Picker
    $result = Invoke-WinApp @('ui', 'invoke', $element.selector, '-w', $Picker.ToInt64().ToString(), '--action', 'invoke', '--json')
    if ($result.hwnd -ne $Picker.ToInt64() -or $result.performedAction -ne 'invoke') { Stop-Smoke 'picker_accept_contract_mismatch' }
}
function Upload-File([string] $Path, [string] $ExpectedStage, [string] $Screenshot) {
    $script:stage = 'upload-open-command'
    Act $script:window '^上传文件$'
    $script:stage = 'upload-picker-window'
    $picker = Wait-Condition {
        $windows = @([Fileway.PlayerSmoke.Native]::Windows($script:app.Id) | Where-Object { $_ -ne $script:window -and [Fileway.PlayerSmoke.Native]::Title($_) -match '选择要上传的文件|Open|打开' })
        if ($windows.Count -gt 1) { Stop-Smoke 'picker_window_ambiguous' }
        if ($windows.Count -eq 1) { $windows[0] }
    } 'native_file_picker_not_observed'
    # Standard Common Item Dialog edit (1148) is discovered, not blind input.
    $script:stage = 'upload-picker-name'
    $edit = Control $picker '^(?:文件名(?:\(N\))?[:：]?|File name[:：]?)$' 'Edit'
    $null = Invoke-WinApp @('ui', 'set-value', $edit.selector, $Path, '-w', $picker.ToInt64().ToString(), '--json')
    $script:stage = 'upload-picker-open'
    Accept-UploadPicker $picker
    $script:stage = 'upload-confirmation'
    Wait-Text '确认上传目标'
    if (-not (Has-Text $script:window ([IO.Path]::GetFileName($Path)))) { Stop-Smoke 'upload_confirmation_name_mismatch' }
    if (-not (Has-Text $script:window '上传到：/')) { Stop-Smoke 'upload_confirmation_directory_mismatch' }
    if ($Screenshot -eq 'upload-completed.png') { Capture 'upload-confirm.png' }
    $script:stage = 'upload-submit'
    Act $script:window '^开始上传$'
    $script:stage = 'upload-result'
    Wait-Text $ExpectedStage
    Capture $Screenshot
    $script:stage = 'upload-close-detail'
    Close-TransferDialog '文件上传'
}
function Download-File([string] $Destination, [string] $ExpectedStage, [string] $Screenshot) {
    $script:stage = 'download-open-command'
    Act $script:window '^下载$'
    $script:stage = 'download-confirmation'
    Wait-Text '确认下载位置'
    if (-not (Has-Text $script:window ('保存到：' + $Destination))) { Stop-Smoke 'download_confirmation_destination_mismatch' }
    if ($Screenshot -eq 'download-completed.png') { Capture 'download-confirm.png' }
    $script:stage = 'download-submit'
    Act $script:window '^开始下载$'
    $script:stage = 'download-result'
    Wait-Text $ExpectedStage
    Capture $Screenshot
    $script:stage = 'download-close-detail'
    Close-TransferDialog '文件下载'
}
function Invoke-OwnedFileMenuItem([IntPtr] $Window, [ValidateSet('下载另存为…')][string] $MenuLabel) {
    if ([Fileway.PlayerSmoke.Native]::ProcessId($Window) -ne $script:app.Id) { Stop-Smoke 'save_as_window_owner_mismatch' }
    $element = Control $Window ('^' + [regex]::Escape($MenuLabel) + '$') 'MenuItem'
    # v0.7.1 ancestors inspection resolves the element's native top-level HWND
    # into windows[].hwnd. A WinUI flyout can own a separate popup HWND; do not
    # weaken the normal main-window Act contract or trust an invoke result first.
    $tree = Invoke-WinApp @('ui', 'inspect', $element.selector, '--ancestors', '-w', $Window.ToInt64().ToString(), '--json')
    if ($tree -isnot [Collections.IDictionary] -or -not $tree.Contains('windows') -or @($tree.windows).Count -ne 1) { Stop-Smoke 'save_as_menu_window_ambiguous' }
    $context = @($tree.windows)[0]
    if ($context -isnot [Collections.IDictionary] -or -not $context.Contains('hwnd') -or -not $context.Contains('elements') -or
        ($context.hwnd -isnot [long] -and $context.hwnd -isnot [int]) -or $context.hwnd -le 0) { Stop-Smoke 'save_as_menu_window_contract_mismatch' }
    $popup = [IntPtr]$context.hwnd
    $matches = @(Unique-Elements (Find-Elements $context.elements) | Where-Object { $_.selector -ceq $element.selector })
    if ($matches.Count -ne 1) { Stop-Smoke 'save_as_menu_target_missing_or_ambiguous' }
    foreach ($field in @('type', 'name', 'isEnabled', 'isOffscreen', 'automationId')) {
        if (-not [object]::Equals($matches[0][$field], $element[$field])) { Stop-Smoke 'save_as_menu_target_metadata_mismatch' }
    }
    if ([Fileway.PlayerSmoke.Native]::ProcessId($Window) -ne $script:app.Id -or
        [Fileway.PlayerSmoke.Native]::ProcessId($popup) -ne $script:app.Id -or
        -not [Fileway.PlayerSmoke.Native]::InFamily($popup, $Window)) { Stop-Smoke 'save_as_menu_popup_owner_mismatch' }
    $result = Invoke-WinApp @('ui', 'invoke', $element.selector, '-w', $popup.ToInt64().ToString(), '--action', 'invoke', '--json')
    if ($result.hwnd -ne $popup.ToInt64() -or $result.performedAction -cne 'invoke') { Stop-Smoke 'save_as_menu_invoke_contract_mismatch' }
}
function Invoke-SaveAsMenuItem([IntPtr] $Window) {
    Invoke-OwnedFileMenuItem $Window '下载另存为…'
}
function Open-FileContextMenu([IntPtr] $Window, [string] $RemoteName, [ValidateSet('download-save-as')][string] $StagePrefix) {
    if ([Fileway.PlayerSmoke.Native]::ProcessId($Window) -ne $script:app.Id) { Stop-Smoke 'save_as_window_owner_mismatch' }
    $script:stage = $StagePrefix + '-row-select'
    Act $Window ('^' + [regex]::Escape($RemoteName) + '$') 'ListItem' 'select'
    $element = Control $Window ('^' + [regex]::Escape($RemoteName) + '$') 'ListItem'
    # Standard ContextFlyout keyboard invocation; exact selector receives focus.
    # v0.7.1 documents f1-f16 and modifier combos, with guarded SendInput.
    $script:stage = $StagePrefix + '-menu-key'
    $result = Invoke-WinApp @('ui', 'send-keys', 'shift+f10', '--target', $element.selector, '-w', $Window.ToInt64().ToString(), '--via', 'send-input', '--json')
    if ($result.hwnd -ne $Window.ToInt64() -or $result.keys -cne 'shift+f10' -or $result.via -cne 'send-input' -or
        $result.target -cne $element.selector -or $result.actionCount -ne 1 -or @($result.warnings).Count -ne 0) { Stop-Smoke 'save_as_menu_keyboard_contract_mismatch' }
}
function Open-SaveAsMenu([IntPtr] $Window, [string] $RemoteName) {
    Open-FileContextMenu $Window $RemoteName 'download-save-as'
}
function Download-SaveAs([string] $RemoteName, [string] $LocalName, [string] $Destination) {
    $script:stage = 'download-save-as-menu'
    Open-SaveAsMenu $script:window $RemoteName
    $script:stage = 'download-save-as-menu-choice'
    Invoke-SaveAsMenuItem $script:window
    $script:stage = 'download-save-as-edit'
    Wait-Text '下载另存为'
    Set-Value $script:window '^下载另存为的本地文件名$' $LocalName
    $script:stage = 'download-save-as-prepare'
    Act $script:window '^核对保存位置$'
    $null = Control $script:window '^开始下载$'
    if (-not (Has-Text $script:window ('远端文件：' + $RemoteName)) -or
        -not (Has-Text $script:window ('本地文件名：' + $LocalName)) -or
        -not (Has-Text $script:window ('保存到：' + $Destination))) { Stop-Smoke 'save_as_confirmation_identity_mismatch' }
    Capture 'download-save-as-confirm.png'
    $script:stage = 'download-save-as-submit'
    Act $script:window '^开始下载$'
    $script:stage = 'download-save-as-result'
    Wait-Text '下载已完成'
    if (-not (Has-Text $script:window ('保存到：' + $Destination))) { Stop-Smoke 'save_as_result_destination_mismatch' }
    Capture 'download-save-as-completed.png'
    $script:stage = 'download-save-as-close-detail'
    Close-TransferDialog '文件下载'
}
function Assert-Payload([string] $Path, [string] $Hash) {
    if (-not [IO.File]::Exists($Path) -or ([IO.FileInfo]::new($Path)).Length -ne 1048576 -or
        (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash -cne $Hash) { Stop-Smoke 'synthetic_payload_mismatch' }
}

$exitCode = 1
try {
    $candidateRoot = Assert-ExternalPath $OutputRoot
    $reuse = $false
    if ([IO.Directory]::Exists($candidateRoot)) {
        $existing = @(Get-ChildItem -Force -Recurse -LiteralPath $candidateRoot)
        if ($existing.Count -gt 0) {
            $reportPath = Join-Path $candidateRoot 'report.json'
            if ($PreflightOnly -or -not [IO.File]::Exists($reportPath)) { Stop-Smoke 'output_directory_must_be_new_or_empty' }
            $prior = Get-Content -LiteralPath $reportPath -Raw | ConvertFrom-Json -AsHashtable
            if ($prior.scope -ne 'preflight' -or $prior.status -ne 'environment-ready') { Stop-Smoke 'output_is_not_completed_preflight' }
            foreach ($item in $existing) {
                if ([IO.Path]::GetRelativePath($candidateRoot, $item.FullName) -notin @('report.json', 'events.jsonl', 'screenshots', 'screenshots\preflight.png') -or
                    ($item.Attributes -band [IO.FileAttributes]::ReparsePoint)) { Stop-Smoke 'output_contains_unexpected_artifact' }
            }
            $reuse = $true
        }
    }
    $script:root = $candidateRoot
    $null = New-Item -ItemType Directory -Path (Join-Path $script:root 'screenshots') -Force
    Write-Report 'running' 'starting'
    if (-not $IsWindows) { Stop-Smoke 'windows_required' -EnvironmentBlocked }
    $script:privateRoot = Assert-ExternalPath (Join-Path ([IO.Path]::GetTempPath()) ('FilewayTransferSmoke-' + [Guid]::NewGuid().ToString('N')))
    $null = New-Item -ItemType Directory -Path $script:privateRoot
    $script:stage = 'preflight'
    # A child shell reuses the true tested UIA/three-color pixel preflight.
    $preflightRoot = Join-Path $script:privateRoot 'preflight'
    & (Join-Path $PSHOME 'pwsh.exe') -NoProfile -File (Join-Path $PSScriptRoot 'Test-PlayerSmoke.ps1') -PreflightOnly -WinAppPath $WinAppPath -OutputRoot $preflightRoot
    $preflightExit = $LASTEXITCODE
    if ($preflightExit -ne 0) { Stop-Smoke 'true_preflight_failed' -EnvironmentBlocked:($preflightExit -eq 2) }
    $preflightReport = Get-Content -LiteralPath (Join-Path $preflightRoot 'report.json') -Raw | ConvertFrom-Json -AsHashtable
    if ($preflightReport.scope -ne 'preflight' -or $preflightReport.status -ne 'environment-ready' -or
        -not $preflightReport.checks.interactiveDesktop -or -not $preflightReport.checks.uia -or -not $preflightReport.checks.capture) { Stop-Smoke 'true_preflight_contract_mismatch' }
    [IO.File]::Copy((Join-Path $preflightRoot 'screenshots\preflight.png'), (Artifact-Path $(if ($reuse) { 'preflight-smoke.png' } else { 'preflight.png' })), $false)
    Complete-Step 'interactive-uia-pixel-preflight'
    if ($PreflightOnly) { Write-Report 'environment-ready' 'preflight_ok'; $exitCode = 0 }
    else {
        foreach ($executable in @($AppPath, $ServerPath)) {
            if (-not [IO.Path]::IsPathFullyQualified($executable) -or -not [IO.File]::Exists($executable)) { Stop-Smoke 'built_executable_missing' }
        }
        # Reuse, do not duplicate or execute the player harness's native helper.
        $playerSource = [IO.File]::ReadAllText((Join-Path $PSScriptRoot 'Test-PlayerSmoke.ps1'))
        $nativeMatch = [regex]::Match($playerSource, '(?s)\$nativeSource = @''\r?\n(.*?)\r?\n''@')
        if (-not $nativeMatch.Success) { Stop-Smoke 'tested_native_helper_missing' }
        Add-Type -TypeDefinition $nativeMatch.Groups[1].Value
        # The only new native call resolves the actual same Known Folder as App.
        Add-Type -TypeDefinition @'
using System;
using System.Runtime.InteropServices;
public static class FilewayTransferDownloads {
    [DllImport("shell32.dll", ExactSpelling=true)] static extern int SHGetKnownFolderPath(ref Guid id,uint flags,IntPtr token,out IntPtr path);
    public static string Get() {
        Guid id=new Guid("374DE290-123F-4565-9164-39C4925E467B"); IntPtr path=IntPtr.Zero;
        try { if(SHGetKnownFolderPath(ref id,0x4000,IntPtr.Zero,out path)<0) throw new InvalidOperationException("downloads_unavailable"); return Marshal.PtrToStringUni(path); }
        finally { if(path!=IntPtr.Zero) Marshal.FreeCoTaskMem(path); }
    }
}
'@
        $downloads = Assert-ExternalPath ([FilewayTransferDownloads]::Get())
        if (-not [IO.Directory]::Exists($downloads)) { Stop-Smoke 'downloads_directory_unavailable' -EnvironmentBlocked }
        $name = 'fileway-ci-' + [Guid]::NewGuid().ToString('N') + '.bin'
        $destination = Join-Path $downloads $name
        $saveAsName = $name.Replace('.bin', '-save-as.bin'); $saveAsDestination = Join-Path $downloads $saveAsName
        if ([IO.File]::Exists($saveAsDestination) -or [IO.Directory]::Exists($saveAsDestination)) { Stop-Smoke 'save_as_destination_already_exists' }
        if ([IO.File]::Exists($destination) -or [IO.Directory]::Exists($destination)) { Stop-Smoke 'download_destination_already_exists' }
        $remote = Join-Path $script:privateRoot 'remote'; $inputs = Join-Path $script:privateRoot 'inputs'; $alternate = Join-Path $script:privateRoot 'alternate'
        foreach ($folder in @($remote, $inputs, $alternate)) { $null = New-Item -ItemType Directory -Path $folder }
        $payload = [byte[]]::new(1048576)
        for ($index = 0; $index -lt $payload.Length; $index++) { $payload[$index] = [byte]($index % 251) }
        $inputFile = Join-Path $inputs $name; $alternateFile = Join-Path $alternate $name; $remoteFile = Join-Path $remote $name
        [IO.File]::WriteAllBytes($inputFile, $payload)
        $payload[0] = 255; [IO.File]::WriteAllBytes($alternateFile, $payload)
        $expectedHash = (Get-FileHash -LiteralPath $inputFile -Algorithm SHA256).Hash
        $portListener = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, 0)
        $portListener.Start(); $port = $portListener.LocalEndpoint.Port; $portListener.Stop()
        $script:stage = 'real-server-start'
        $start = [Diagnostics.ProcessStartInfo]::new($ServerPath)
        $start.UseShellExecute = $false; $start.CreateNoWindow = $true; $start.WorkingDirectory = $script:privateRoot
        $start.RedirectStandardOutput = $true; $start.RedirectStandardError = $true
        foreach ($key in @($start.Environment.Keys)) { if ($key.StartsWith('FB_', [StringComparison]::OrdinalIgnoreCase)) { $null = $start.Environment.Remove($key) } }
        foreach ($argument in @('--noauth', '--username', 'fileway-ci', '--address', '127.0.0.1', '--port', $port.ToString(), '--root', $remote,
            '--database', (Join-Path $script:privateRoot 'server.db'), '--hlsCacheDir', (Join-Path $script:privateRoot 'hls'),
            '--disableExec', '--disableThumbnails', '--disablePreviewResize', '--log', '')) { $start.ArgumentList.Add($argument) }
        $script:server = [Diagnostics.Process]::Start($start)
        # Drain but never persist server stdout/stderr (quick setup can log an unused password).
        $serverOut = $script:server.StandardOutput.BaseStream.CopyToAsync([IO.Stream]::Null)
        $serverError = $script:server.StandardError.BaseStream.CopyToAsync([IO.Stream]::Null)
        $baseAddress = 'http://127.0.0.1:' + $port
        $null = Wait-Condition { try { (Invoke-WebRequest -Uri ($baseAddress + '/health') -TimeoutSec 2 -NoProxy).StatusCode -eq 200 } catch { $false } } 'real_server_not_ready' 30000
        Complete-Step 'real-server-ready'
        $script:stage = 'native-connect'
        $env:WINAPP_UI_WORKFLOW_ID = [Guid]::NewGuid().ToString('N'); $env:WINAPP_CLI_TELEMETRY_OPTOUT = '1'
        $start = [Diagnostics.ProcessStartInfo]::new($AppPath)
        $start.UseShellExecute = $false; $start.WorkingDirectory = [IO.Path]::GetDirectoryName($AppPath)
        $start.Environment['FILEWAY_WINDOWS_DATA_ROOT'] = Join-Path $script:privateRoot 'client'
        $script:app = [Diagnostics.Process]::Start($start)
        $script:window = Wait-Condition {
            $windows = @([Fileway.PlayerSmoke.Native]::Windows($script:app.Id))
            if ($windows.Count -eq 1) { $windows[0] }
        } 'native_main_window_not_observed' 30000
        $hostIds = @(Wait-Condition {
            $ids = @([Fileway.PlayerSmoke.Native]::ChildHosts($script:app.Id))
            if ($ids.Count -gt 1) { Stop-Smoke 'owned_host_not_uniquely_observed' }
            if ($ids.Count -eq 1) { $ids[0] }
        } 'owned_host_not_observed' 30000)
        $script:ownedHost = [Diagnostics.Process]::GetProcessById($hostIds[0]); $null = $script:ownedHost.Handle
        if (-not [IO.Path]::GetFullPath($script:ownedHost.MainModule.FileName).Equals((Join-Path ([IO.Path]::GetDirectoryName($AppPath)) 'fileway-host.exe'), [StringComparison]::OrdinalIgnoreCase) -or
            $script:ownedHost.StartTime -lt $script:app.StartTime) { Stop-Smoke 'owned_host_identity_mismatch' }
        $script:stage = 'connect-name'; Set-Value $script:window '^连接名称（可选）$' 'fileway-ci'
        $script:stage = 'connect-address'; Set-Value $script:window '^服务器地址$' $baseAddress
        $script:stage = 'connect-auth-expand'; Act $script:window '^认证方式$' 'ComboBox' 'expand'
        $script:stage = 'connect-auth-select'; Act $script:window '^无需认证$' 'ListItem' 'select'
        $script:stage = 'connect-persistence-off'; Act $script:window '^保持登录（仅在本机加密保存会话）$' 'CheckBox' 'toggle-off'
        $script:stage = 'connect-http-consent'; Act $script:window '^我了解 HTTP 会明文传输凭据和文件内容$' 'CheckBox' 'toggle-on'
        $script:stage = 'connect-submit-reveal'; Reveal-ConnectionSubmit $script:window
        $script:stage = 'connect-ready-capture'; Capture 'connect-ready.png'
        $script:stage = 'connect-submit'; Act $script:window '^连接并浏览$'
        $script:stage = 'connect-browser-ready'
        $null = Control $script:window '^上传文件$'
        Complete-Step 'native-noauth-connect'
        $script:stage = 'native-upload'
        Upload-File $inputFile '上传已完成' 'upload-completed.png'
        Assert-Payload $remoteFile $expectedHash
        Complete-Step 'native-upload-and-remote-bytes' @{ bytes = 1048576; hashEqual = $true }
        # Completion may race the directory reload; explicitly observe its row.
        $null = Wait-Condition { Find-Control $script:window ('^' + [regex]::Escape($name) + '$') 'ListItem' -Optional } 'uploaded_row_not_observed'
        Act $script:window ('^' + [regex]::Escape($name) + '$') 'ListItem' 'select'
        $script:stage = 'native-download'
        Download-File $destination '下载已完成' 'download-completed.png'
        Assert-Payload $destination $expectedHash
        Complete-Step 'native-download-and-local-bytes' @{ bytes = 1048576; hashEqual = $true }
        $script:stage = 'native-download-conflict'
        Download-File $destination '本地存在同名文件' 'download-conflict.png'
        Assert-Payload $destination $expectedHash
        Complete-Step 'native-download-conflict-preserved' @{ hashEqual = $true }
        $script:stage = 'native-download-save-as'
        Download-SaveAs $name $saveAsName $saveAsDestination
        Assert-Payload $saveAsDestination $expectedHash
        Assert-Payload $remoteFile $expectedHash
        Complete-Step 'native-download-save-as-and-local-bytes' @{ bytes = 1048576; hashEqual = $true; remotePreserved = $true }
        $script:stage = 'native-upload-conflict'
        Upload-File $alternateFile '目标存在同名项目' 'upload-conflict.png'
        Assert-Payload $remoteFile $expectedHash
        Complete-Step 'native-upload-conflict-preserved' @{ hashEqual = $true }
        $script:stage = 'transfer-payload-integrity'
        Assert-Payload $remoteFile $expectedHash
        Assert-Payload $destination $expectedHash
        Assert-Payload $saveAsDestination $expectedHash
        Complete-Step 'native-transfer-bytes-preserved' @{ hashEqual = $true; bytes = 1048576 }
        $script:stage = 'transfer-center-uploads'
        Act $script:window '^传输中心$'
        Wait-Text '上传已完成'; Wait-Text '目标存在同名项目'
        Capture 'transfer-center-uploads.png'
        $script:stage = 'transfer-center-downloads'
        Activate-DownloadRecords $script:window
        Wait-Text '下载已完成'; Wait-Text '本地存在同名文件'
        Capture 'transfer-center-downloads.png'
        Complete-Step 'native-transfer-center-terminal-records'
        $script:stage = 'application-shutdown'
        if (-not $script:app.CloseMainWindow()) { Stop-Smoke 'application_close_not_accepted' }
        if (-not $script:app.WaitForExit(30000)) { Stop-Smoke 'application_exit_timeout' }
        if ($script:app.ExitCode -ne 0) { Stop-Smoke 'application_exit_code_nonzero' }
        if (-not $script:ownedHost.WaitForExit(15000)) { Stop-Smoke 'owned_host_exit_timeout' }
        if ($script:ownedHost.ExitCode -ne 0) { Stop-Smoke 'owned_host_exit_code_nonzero' }
        Complete-Step 'application-and-host-shutdown' @{ appExitCode = 0; hostExitCode = 0 }
        Write-Report 'passed' 'transfer_smoke_ok'; $exitCode = 0
    }
}
catch {
    $failure = $_
    $status = 'failed'; $code = 'unexpected_harness_error'; $errorObject = $failure.Exception
    while ($errorObject -and -not $errorObject.Data.Contains('smokeCode')) { $errorObject = $errorObject.InnerException }
    if ($errorObject) { $status = $errorObject.Data['smokeStatus']; $code = $errorObject.Data['smokeCode'] }
    if ($script:stage -eq 'upload-result' -and $script:app -and -not $script:app.HasExited) {
        try {
            Record-Observation 'upload-result-known-status' ([ordered]@{
                completed = [int](Has-Text $script:window '上传已完成'); conflict = [int](Has-Text $script:window '目标存在同名项目');
                failed = [int](Has-Text $script:window '上传失败'); unknown = [int](Has-Text $script:window '上传结果待核实');
                uploading = [int](Has-Text $script:window '正在上传'); reconciling = [int](Has-Text $script:window '正在核实结果');
            })
        } catch { Record-Observation 'upload-result-status-read' @{ failed = 1 } }
        try { Capture 'upload-result-failure.png'; Record-Observation 'upload-result-failure-capture' @{ saved = 1 } }
        catch { Record-Observation 'upload-result-failure-capture' @{ saved = 0 } }
    }
    if ($code -eq 'unexpected_harness_error' -and $script:root -and [IO.Directory]::Exists((Join-Path $script:root 'screenshots'))) {
        # Fixed numeric categories only; no exception text, field name, path,
        # stack trace or runtime UIA data leaves the private process memory.
        Record-Observation 'harness-error-category' ([ordered]@{
            strictPropertyMissing = [int]($failure.FullyQualifiedErrorId -like 'PropertyNotFoundStrict*');
            strictVariableMissing = [int]($failure.FullyQualifiedErrorId -like 'VariableIsUndefined*');
            invalidArgument = [int]($failure.CategoryInfo.Category -eq [System.Management.Automation.ErrorCategory]::InvalidArgument);
        })
    }
    if ($script:root -and $script:app -and -not $script:app.HasExited -and $script:window -ne [IntPtr]::Zero -and
        ($script:stage -eq 'native-connect' -or $script:stage.StartsWith('connect-', [StringComparison]::Ordinal))) {
        # The only failure image is the synthetic, isolated App's connection
        # surface. The tested capture guard refuses foreign/obscuring windows.
        try { Capture 'connect-failure.png'; Record-Observation 'connect-failure-capture' @{ saved = 1 } }
        catch { Record-Observation 'connect-failure-capture' @{ saved = 0 } }
    }
    if ($script:root -and $script:app -and -not $script:app.HasExited -and $script:window -ne [IntPtr]::Zero -and
        $script:stage -in @('download-save-as-row-select', 'download-save-as-menu-key', 'download-save-as-menu-choice')) {
        try { Capture 'download-save-as-menu-failure.png'; Record-Observation 'save-as-menu-failure-capture' @{ saved = 1 } }
        catch { Record-Observation 'save-as-menu-failure-capture' @{ saved = 0 } }
    }
    if ($script:root -and [IO.Directory]::Exists((Join-Path $script:root 'screenshots'))) { Write-Report $status $code }
    if ($script:root -and $script:app -and $script:ownedHost -and [IO.Directory]::Exists((Join-Path $script:root 'screenshots'))) {
        Record-Observation 'failed-journey-shutdown-diagnostic' (Observe-FailureShutdown)
    }
    Write-Host "Transfer smoke $status at $($script:stage): $code"
    $exitCode = if ($status -eq 'environment-blocked') { 2 } else { 1 }
}
finally {
    # Forced teardown is never a successful application shutdown observation.
    foreach ($process in @($script:app, $script:ownedHost, $script:server)) {
        if ($process) {
            try { if (-not $process.HasExited) { $process.Kill($true); $null = $process.WaitForExit(5000) } } catch { }
            $process.Dispose()
        }
    }
    # Ephemeral CI owns these unique files. No recursive cleanup or wildcard
    # deletion: retain private failure state, and never touch other Downloads.
    $env:WINAPP_UI_WORKFLOW_ID = $oldWorkflow; $env:WINAPP_CLI_TELEMETRY_OPTOUT = $oldTelemetry
}
exit $exitCode
