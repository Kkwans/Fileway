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
    'download-completed.png', 'download-conflict.png', 'upload-conflict.png', 'connect-failure.png')
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
        nativeTransferVerified = ($Status -eq 'passed'); cancellationUiVerified = $false; unknownOutcomeUiVerified = $false;
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
            foreach ($field in @('type', 'name', 'isEnabled', 'isOffscreen')) {
                if (-not [object]::Equals($previous[$field], $element[$field])) { Stop-Smoke 'inspect_selector_metadata_mismatch' }
            }
        } else {
            $seen.Add($element.selector, $element)
            $element
        }
    }
}
function Elements([IntPtr] $Window) {
    $tree = Invoke-WinApp @('ui', 'inspect', '-w', $Window.ToInt64().ToString(), '--depth', '16', '--json')
    if (-not $tree.Contains('windows')) { Stop-Smoke 'inspect_windows_contract_missing' }
    return @(Unique-Elements (Find-Elements $tree))
}
function Find-Control([IntPtr] $Window, [string] $NamePattern, [string] $Type = 'Button', [switch] $Optional) {
    $visible = @(Elements $Window | Where-Object { $_.isEnabled -and -not $_.isOffscreen })
    $matches = @($visible | Where-Object { $_.type -eq $Type -and $_.name -match $NamePattern })
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
    if ($result.hwnd -ne $Window.ToInt64() -or $result.performedAction -notin $performed) { Stop-Smoke 'invoke_effect_contract_mismatch' }
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
    $matches = @(Elements $Window | Where-Object { $_.type -eq 'Button' -and $_.name -ceq '连接并浏览' -and $_.isEnabled })
    if ($matches.Count -ne 1) { Stop-Smoke 'connection_submit_scroll_target_ambiguous' }
    if ($matches[0].isOffscreen) {
        $result = Invoke-WinApp @('ui', 'scroll', $matches[0].selector, '-w', $Window.ToInt64().ToString(), '--to', 'bottom', '--json')
        if ($result.hwnd -ne $Window.ToInt64() -or $result.elementId -cne $matches[0].selector -or $result.to -cne 'bottom' -or
            ($result.Contains('direction') -and $null -ne $result.direction) -or
            ($result.Contains('wheel') -and $null -ne $result.wheel)) { Stop-Smoke 'connection_scroll_contract_mismatch' }
    }
}
function Has-Text([IntPtr] $Window, [string] $Text) {
    return @(Elements $Window | Where-Object { $_.type -eq 'Text' -and $_.name -ceq $Text -and -not $_.isOffscreen }).Count -gt 0
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
function Close-TransferDialog([string] $Title) {
    # The frameless caption also has a Chinese "关闭" button. Constrain the
    # action to the actual ContentDialog subtree, never the whole main window.
    $dialog = Wait-Condition {
        $matches = @(Elements $script:window | Where-Object { $_.name -ceq $Title -and $_.type -in @('Window', 'Pane', 'Custom') -and -not $_.isOffscreen })
        if ($matches.Count -gt 1) { Stop-Smoke 'transfer_dialog_ambiguous' }
        if ($matches.Count -eq 1) { $matches[0] }
    } 'transfer_dialog_not_observed'
    $tree = Invoke-WinApp @('ui', 'inspect', $dialog.selector, '-w', $script:window.ToInt64().ToString(), '--depth', '16', '--json')
    $buttons = @(Unique-Elements (Find-Elements $tree) | Where-Object { $_.type -eq 'Button' -and $_.name -ceq '关闭' -and $_.isEnabled -and -not $_.isOffscreen })
    if ($buttons.Count -ne 1) { Stop-Smoke 'dialog_close_missing_or_ambiguous' }
    $result = Invoke-WinApp @('ui', 'invoke', $buttons[0].selector, '-w', $script:window.ToInt64().ToString(), '--action', 'invoke', '--json')
    if ($result.performedAction -ne 'invoke' -or $result.hwnd -ne $script:window.ToInt64()) { Stop-Smoke 'dialog_close_effect_mismatch' }
}
function Upload-File([string] $Path, [string] $ExpectedStage, [string] $Screenshot) {
    Act $script:window '^上传文件$'
    $picker = Wait-Condition {
        $windows = @([Fileway.PlayerSmoke.Native]::Windows($script:app.Id) | Where-Object { $_ -ne $script:window -and [Fileway.PlayerSmoke.Native]::Title($_) -match '选择要上传的文件|Open|打开' })
        if ($windows.Count -gt 1) { Stop-Smoke 'picker_window_ambiguous' }
        if ($windows.Count -eq 1) { $windows[0] }
    } 'native_file_picker_not_observed'
    # Standard Common Item Dialog edit (1148) is discovered, not blind input.
    $edit = Control $picker '^(?:文件名(?:\(N\))?[:：]?|File name[:：]?)$' 'Edit'
    $null = Invoke-WinApp @('ui', 'set-value', $edit.selector, $Path, '-w', $picker.ToInt64().ToString(), '--json')
    Act $picker '^(?:选择文件|Open(?:\(O\))?|打开(?:\(O\))?)$'
    Wait-Text '确认上传目标'
    if (-not (Has-Text $script:window ([IO.Path]::GetFileName($Path)))) { Stop-Smoke 'upload_confirmation_name_mismatch' }
    if (-not (Has-Text $script:window '上传到：/')) { Stop-Smoke 'upload_confirmation_directory_mismatch' }
    if ($Screenshot -eq 'upload-completed.png') { Capture 'upload-confirm.png' }
    Act $script:window '^开始上传$'
    Wait-Text $ExpectedStage
    Capture $Screenshot
    Close-TransferDialog '文件上传'
}
function Download-File([string] $Destination, [string] $ExpectedStage, [string] $Screenshot) {
    Act $script:window '^下载$'
    Wait-Text '确认下载位置'
    if (-not (Has-Text $script:window ('保存到：' + $Destination))) { Stop-Smoke 'download_confirmation_destination_mismatch' }
    if ($Screenshot -eq 'download-completed.png') { Capture 'download-confirm.png' }
    Act $script:window '^开始下载$'
    Wait-Text $ExpectedStage
    Capture $Screenshot
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
        $script:stage = 'native-upload-conflict'
        Upload-File $alternateFile '目标存在同名项目' 'upload-conflict.png'
        Assert-Payload $remoteFile $expectedHash
        Complete-Step 'native-upload-conflict-preserved' @{ hashEqual = $true }
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
    $status = 'failed'; $code = 'unexpected_harness_error'; $errorObject = $_.Exception
    while ($errorObject -and -not $errorObject.Data.Contains('smokeCode')) { $errorObject = $errorObject.InnerException }
    if ($errorObject) { $status = $errorObject.Data['smokeStatus']; $code = $errorObject.Data['smokeCode'] }
    if ($script:root -and $script:app -and -not $script:app.HasExited -and $script:window -ne [IntPtr]::Zero -and
        ($script:stage -eq 'native-connect' -or $script:stage.StartsWith('connect-', [StringComparison]::Ordinal))) {
        # The only failure image is the synthetic, isolated App's connection
        # surface. The tested capture guard refuses foreign/obscuring windows.
        try { Capture 'connect-failure.png'; Record-Observation 'connect-failure-capture' @{ saved = 1 } }
        catch { Record-Observation 'connect-failure-capture' @{ saved = 0 } }
    }
    if ($script:root -and [IO.Directory]::Exists((Join-Path $script:root 'screenshots'))) { Write-Report $status $code }
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
