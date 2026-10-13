#requires -Version 7.2
[CmdletBinding()]
param([string] $AppPath, [string] $ServerPath,
    [Parameter(Mandatory)][string] $WinAppPath,
    [Parameter(Mandatory)][string] $OutputRoot, [switch] $PreflightOnly)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
Import-Module (Join-Path $PSScriptRoot 'RemoteResourceSmoke.Common.psm1') -Force
. (Get-RemoteSmokeFunctions)
$allowedArtifacts = @('report.json', 'events.jsonl', 'preflight.png', 'preflight-smoke.png', 'remote-image-ready.png')
$script:root = $null; $script:app = $null; $script:server = $null; $script:ownedHost = $null
$script:window = [IntPtr]::Zero; $imageWindow = [IntPtr]::Zero
$script:stage = 'output-validation'; $script:clock = [Diagnostics.Stopwatch]::StartNew()
$script:completed = [Collections.Generic.List[string]]::new()
$oldWorkflow = $env:WINAPP_UI_WORKFLOW_ID; $oldTelemetry = $env:WINAPP_CLI_TELEMETRY_OPTOUT
function Artifact-Path([string] $Name) {
    if ($Name -notin $allowedArtifacts) { Stop-Smoke 'artifact_name_not_allowed' }
    if ($Name.EndsWith('.png', [StringComparison]::Ordinal)) { return Join-Path (Join-Path $script:root 'screenshots') $Name }
    return Join-Path $script:root $Name
}
function Record-Observation([string] $Name, $Details) {
    $event = @{ observation = $Name; stage = $script:stage; elapsedMilliseconds = $script:clock.ElapsedMilliseconds; details = $Details }
    [IO.File]::AppendAllText((Artifact-Path 'events.jsonl'), ($event | ConvertTo-Json -Depth 4 -Compress) + "`n", [Text.UTF8Encoding]::new($false))
}
function Complete-Step([string] $Name, $Details = @{}) {
    $script:completed.Add($Name); Record-Observation $Name $Details
}
function Write-Report([string] $Status, [string] $Code) {
    $report = [ordered]@{ schemaVersion = 1; scope = $(if ($PreflightOnly) { 'preflight' } else { 'remote-image-smoke' });
        status = $Status; code = $Code; stage = $script:stage; completedSteps = @($script:completed);
        remoteImageVerified = ($Status -eq 'passed'); remoteVideoVerified = $false; elapsedMilliseconds = $script:clock.ElapsedMilliseconds }
    [IO.File]::WriteAllText((Artifact-Path 'report.json'), ($report | ConvertTo-Json -Depth 4) + "`n", [Text.UTF8Encoding]::new($false))
}
function Invoke-BoundedProcess([string] $Executable, [string[]] $Arguments, [int] $TimeoutMilliseconds) {
    $start = [Diagnostics.ProcessStartInfo]::new($Executable)
    $start.UseShellExecute = $false; $start.CreateNoWindow = $true
    $start.RedirectStandardOutput = $true; $start.RedirectStandardError = $true
    foreach ($argument in $Arguments) { $start.ArgumentList.Add($argument) }
    $process = [Diagnostics.Process]::Start($start)
    try {
        $stdout = $process.StandardOutput.ReadToEndAsync(); $stderr = $process.StandardError.ReadToEndAsync()
        if (-not $process.WaitForExit($TimeoutMilliseconds)) { $process.Kill($true); Stop-Smoke 'helper_process_timeout' }
        $raw = $stdout.GetAwaiter().GetResult(); $errorText = $stderr.GetAwaiter().GetResult()
        if ($raw.Length -gt 1048576 -or $errorText.Length -gt 1048576) { Stop-Smoke 'helper_output_too_large' }
        return @{ exitCode = $process.ExitCode; stdout = $raw }
    } finally { $process.Dispose() }
}
function Get-ServerRecents([string] $BaseAddress) {
    $uri = [Uri]::new($BaseAddress)
    if ($uri.Scheme -cne 'http' -or $uri.Host -cne '127.0.0.1' -or $uri.AbsolutePath -cne '/' -or $uri.Query -ne '' -or $uri.UserInfo -ne '') { Stop-Smoke 'loopback_recents_required' }
    $response = Invoke-WebRequest -Method Get -Uri ($BaseAddress + '/api/recent?limit=100') -TimeoutSec 3 -NoProxy
    if ($response.StatusCode -ne 200 -or $response.Content.Length -gt 1048576) { Stop-Smoke 'recent_read_contract_mismatch' }
    $entries = ConvertFrom-Json -InputObject $response.Content -AsHashtable -NoEnumerate
    if ($entries -isnot [array] -or $entries.Count -gt 100) { Stop-Smoke 'recent_array_contract_mismatch' }
    return ,$entries
}
function Send-OpenKey([IntPtr] $Window, [string] $Name) {
    if ([Fileway.PlayerSmoke.Native]::ProcessId($Window) -ne $script:app.Id) { Stop-Smoke 'open_window_owner_mismatch' }
    Act $Window ('^' + [regex]::Escape($Name) + '$') 'ListItem' 'select'
    $element = Control $Window ('^' + [regex]::Escape($Name) + '$') 'ListItem'
    $result = Invoke-WinApp @('ui', 'send-keys', 'enter', '--target', $element.selector, '-w', $Window.ToInt64().ToString(), '--via', 'send-input', '--json')
    if ($result.hwnd -ne $Window.ToInt64() -or $result.keys -cne 'enter' -or $result.via -cne 'send-input' -or
        $result.target -cne $element.selector -or $result.actionCount -ne 1 -or @($result.warnings).Count -ne 0) { Stop-Smoke 'image_open_keyboard_contract_mismatch' }
}
$exitCode = 1
try {
    $candidate = Assert-ExternalPath $OutputRoot; $reuse = $false
    if ([IO.Directory]::Exists($candidate)) {
        $existing = @(Get-ChildItem -Force -Recurse -LiteralPath $candidate)
        if ($existing.Count -gt 0) {
            $priorPath = Join-Path $candidate 'report.json'
            if ($PreflightOnly -or -not [IO.File]::Exists($priorPath)) { Stop-Smoke 'output_must_be_new_or_preflight' }
            $prior = Get-Content -Raw -LiteralPath $priorPath | ConvertFrom-Json -AsHashtable
            if ($prior.scope -cne 'preflight' -or $prior.status -cne 'environment-ready') { Stop-Smoke 'output_not_completed_preflight' }
            foreach ($item in $existing) {
                if ([IO.Path]::GetRelativePath($candidate, $item.FullName) -notin @('report.json', 'events.jsonl', 'screenshots', 'screenshots\preflight.png') -or
                    ($item.Attributes -band [IO.FileAttributes]::ReparsePoint)) { Stop-Smoke 'output_has_unexpected_artifact' }
            }
            $reuse = $true
        }
    }
    $script:root = $candidate; $null = New-Item -ItemType Directory -Path (Join-Path $candidate 'screenshots') -Force
    Write-Report 'running' 'starting'
    if (-not $IsWindows) { Stop-Smoke 'windows_required' -EnvironmentBlocked }
    $privateRoot = Assert-ExternalPath (Join-Path ([IO.Path]::GetTempPath()) ('FilewayRemoteImageSmoke-' + [Guid]::NewGuid().ToString('N')))
    $null = New-Item -ItemType Directory -Path $privateRoot
    $script:stage = 'true-preflight'
    $preflight = Join-Path $privateRoot 'preflight'
    $result = Invoke-BoundedProcess (Join-Path $PSHOME 'pwsh.exe') @('-NoProfile', '-File', (Join-Path $PSScriptRoot 'Test-PlayerSmoke.ps1'), '-PreflightOnly', '-WinAppPath', $WinAppPath, '-OutputRoot', $preflight) 60000
    if ($result.exitCode -ne 0) { Stop-Smoke 'true_preflight_failed' -EnvironmentBlocked:($result.exitCode -eq 2) }
    $report = Get-Content -LiteralPath (Join-Path $preflight 'report.json') -Raw | ConvertFrom-Json -AsHashtable
    if ($report.scope -cne 'preflight' -or $report.status -cne 'environment-ready' -or -not $report.checks.interactiveDesktop -or -not $report.checks.uia -or -not $report.checks.capture) { Stop-Smoke 'true_preflight_contract_mismatch' }
    [IO.File]::Copy((Join-Path $preflight 'screenshots\preflight.png'), (Artifact-Path $(if ($reuse) { 'preflight-smoke.png' } else { 'preflight.png' })), $false)
    Complete-Step 'interactive-uia-pixel-preflight'
    if ($PreflightOnly) { Write-Report 'environment-ready' 'preflight_ok'; $exitCode = 0 }
    else {
        foreach ($executable in @($AppPath, $ServerPath, $WinAppPath)) {
            if (-not [IO.Path]::IsPathFullyQualified($executable) -or -not [IO.File]::Exists($executable)) { Stop-Smoke 'built_executable_missing' }
        }
        Add-Type -TypeDefinition (Get-RemoteSmokeNativeSource)
        Add-Type -TypeDefinition (Get-RemoteImagePixelSource)
        $name = 'fileway-image-ci-' + [Guid]::NewGuid().ToString('N') + '.png'
        $remote = Join-Path $privateRoot 'remote'; $null = New-Item -ItemType Directory -Path $remote
        $script:stage = 'synthetic-image-fixture'
        $fixture = Invoke-BoundedProcess (Get-Command python -CommandType Application).Source @((Join-Path $PSScriptRoot 'New-RemoteImageSmokeFixture.py'), '--output', (Join-Path $remote $name)) 15000
        if ($fixture.exitCode -ne 0) { Stop-Smoke 'synthetic_fixture_failed' }
        $metadata = $fixture.stdout | ConvertFrom-Json -AsHashtable
        $hash = '5a05232336535f96c4c698007a8e562019af15240b901fc3ecd4908b16f6874c'
        if ($metadata.bytes -ne 461223 -or $metadata.width -ne 480 -or $metadata.height -ne 320 -or $metadata.sha256 -cne $hash -or
            (Get-FileHash -LiteralPath (Join-Path $remote $name)).Hash.ToLowerInvariant() -cne $hash) { Stop-Smoke 'synthetic_fixture_contract_mismatch' }
        Complete-Step 'stable-synthetic-image' @{ bytes = 461223; hashEqual = $true }
        $listener = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, 0)
        $listener.Start(); $port = $listener.LocalEndpoint.Port; $listener.Stop()
        $script:stage = 'real-server-start'
        $start = [Diagnostics.ProcessStartInfo]::new($ServerPath)
        $start.UseShellExecute = $false; $start.CreateNoWindow = $true; $start.WorkingDirectory = $privateRoot
        $start.RedirectStandardOutput = $true; $start.RedirectStandardError = $true
        foreach ($key in @($start.Environment.Keys)) { if ($key.StartsWith('FB_', [StringComparison]::OrdinalIgnoreCase)) { $null = $start.Environment.Remove($key) } }
        foreach ($argument in @('--noauth', '--username', 'fileway-ci', '--address', '127.0.0.1', '--port', $port.ToString(), '--root', $remote,
            '--database', (Join-Path $privateRoot 'server.db'), '--hlsCacheDir', (Join-Path $privateRoot 'hls'), '--disableExec', '--disableThumbnails', '--disablePreviewResize', '--log', '')) { $start.ArgumentList.Add($argument) }
        $script:server = [Diagnostics.Process]::Start($start)
        $serverOut = $script:server.StandardOutput.BaseStream.CopyToAsync([IO.Stream]::Null)
        $serverError = $script:server.StandardError.BaseStream.CopyToAsync([IO.Stream]::Null)
        $baseAddress = 'http://127.0.0.1:' + $port
        $null = Wait-Condition { try { (Invoke-WebRequest -Uri ($baseAddress + '/health') -TimeoutSec 2 -NoProxy).StatusCode -eq 200 } catch { $false } } 'real_server_not_ready' 30000
        Complete-Step 'real-server-ready'
        $baseline = Get-ServerRecents $baseAddress
        if ($baseline.Count -ne 0) { Stop-Smoke 'fresh_recent_baseline_not_empty' }
        $notBefore = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
        Complete-Step 'recent-baseline-empty' @{ count = 0 }
        $script:stage = 'native-connect'
        $env:WINAPP_UI_WORKFLOW_ID = [Guid]::NewGuid().ToString('N'); $env:WINAPP_CLI_TELEMETRY_OPTOUT = '1'
        $start = [Diagnostics.ProcessStartInfo]::new($AppPath); $start.UseShellExecute = $false; $start.WorkingDirectory = [IO.Path]::GetDirectoryName($AppPath)
        $start.Environment['FILEWAY_WINDOWS_DATA_ROOT'] = Join-Path $privateRoot 'client'
        $script:app = [Diagnostics.Process]::Start($start)
        $script:window = Wait-Condition { $windows = @([Fileway.PlayerSmoke.Native]::Windows($script:app.Id)); if ($windows.Count -eq 1) { $windows[0] } } 'main_window_not_observed' 30000
        $hostId = Wait-Condition { $ids = @([Fileway.PlayerSmoke.Native]::ChildHosts($script:app.Id)); if ($ids.Count -gt 1) { Stop-Smoke 'owned_host_ambiguous' }; if ($ids.Count -eq 1) { $ids[0] } } 'owned_host_not_observed' 30000
        $script:ownedHost = [Diagnostics.Process]::GetProcessById($hostId); $null = $script:ownedHost.Handle
        if (-not [IO.Path]::GetFullPath($script:ownedHost.MainModule.FileName).Equals((Join-Path ([IO.Path]::GetDirectoryName($AppPath)) 'fileway-host.exe'), [StringComparison]::OrdinalIgnoreCase) -or
            $script:ownedHost.StartTime -lt $script:app.StartTime) { Stop-Smoke 'owned_host_identity_mismatch' }
        $script:stage = 'connect-name'; Set-Value $script:window '^连接名称（可选）$' 'fileway-ci'
        $script:stage = 'connect-address'; Set-Value $script:window '^服务器地址$' $baseAddress
        $script:stage = 'connect-auth-expand'; Act $script:window '^认证方式$' 'ComboBox' 'expand'
        $script:stage = 'connect-auth-select'; Act $script:window '^无需认证$' 'ListItem' 'select'
        $script:stage = 'connect-persistence-off'; Act $script:window '^保持登录（仅在本机加密保存会话）$' 'CheckBox' 'toggle-off'
        $script:stage = 'connect-http-consent'; Act $script:window '^我了解 HTTP 会明文传输凭据和文件内容$' 'CheckBox' 'toggle-on'
        Reveal-ConnectionSubmit $script:window
        $script:stage = 'connect-submit'; Act $script:window '^连接并浏览$'
        $script:stage = 'image-row-open'; Send-OpenKey $script:window $name
        $imageWindow = Wait-Condition {
            $windows = @([Fileway.PlayerSmoke.Native]::Windows($script:app.Id) | Where-Object { $_ -ne $script:window -and [Fileway.PlayerSmoke.Native]::Title($_) -cin @(($name + ' · Fileway'), ($name + ' · 栖卷')) })
            if ($windows.Count -gt 1) { Stop-Smoke 'owned_image_window_ambiguous' }; if ($windows.Count -eq 1) { $windows[0] }
        } 'owned_image_window_not_observed' 30000
        if ([Fileway.PlayerSmoke.Native]::ProcessId($imageWindow) -ne $script:app.Id) { Stop-Smoke 'image_window_owner_mismatch' }
        $script:stage = 'image-decoded'
        $null = Wait-Condition { Has-Text $imageWindow ($name + ' · 480 × 320 · Ctrl + 滚轮可缩放') } 'remote_image_not_decoded' 30000
        $script:stage = 'image-owned-maximize'
        $maximize = Find-Control $imageWindow '^(最大化|Maximize)$' -Optional
        if ($maximize) { Act $imageWindow '^(最大化|Maximize)$' }
        else { $null = Control $imageWindow '^(还原|Restore)$' }
        Act $imageWindow '^适应窗口$'
        $script:stage = 'image-real-pixels'
        $image = Wait-Condition {
            $capture = [Fileway.PlayerSmoke.Native]::Capture($imageWindow)
            if ([FilewayRemoteImagePixels]::OrderedBandRows($capture.Pixels, $capture.Width, $capture.Height) -ge 32) { $capture }
        } 'decoded_rgb_pixels_not_observed' 15000
        $image.SavePng((Artifact-Path 'remote-image-ready.png'))
        Complete-Step 'native-remote-image-decoded-pixels' @{ orderedRgbRows = [FilewayRemoteImagePixels]::OrderedBandRows($image.Pixels, $image.Width, $image.Height); width = 480; height = 320 }
        $script:stage = 'server-recent-verification'
        $null = Wait-Condition { Test-RemoteImageRecentEntry (Get-ServerRecents $baseAddress) $name $notBefore } 'native_open_recent_not_recorded' 15000
        Complete-Step 'actual-server-recent-record' @{ baselineCount = 0; identityMatches = 1; wireIdentityEqual = $true; pathVerified = $true }
        $script:stage = 'image-close'
        Act $imageWindow '^(关闭|Close)$'
        $null = Wait-Condition { $imageWindow -notin @([Fileway.PlayerSmoke.Native]::Windows($script:app.Id)) } 'owned_image_close_timeout'
        Complete-Step 'owned-image-closed'
        $script:stage = 'application-shutdown'
        if (-not $script:app.CloseMainWindow()) { Stop-Smoke 'application_close_not_accepted' }
        if (-not $script:app.WaitForExit(30000)) { Stop-Smoke 'application_exit_timeout' }
        if ($script:app.ExitCode -ne 0) { Stop-Smoke 'application_exit_code_nonzero' }
        if (-not $script:ownedHost.WaitForExit(15000)) { Stop-Smoke 'owned_host_exit_timeout' }
        if ($script:ownedHost.ExitCode -ne 0) { Stop-Smoke 'owned_host_exit_code_nonzero' }
        Complete-Step 'application-and-host-shutdown' @{ appExitCode = 0; hostExitCode = 0 }
        Write-Report 'passed' 'remote_image_smoke_ok'; $exitCode = 0
    }
} catch {
    $code = 'unexpected_failure'; $status = 'failed'
    if ($_.Exception.Data.Contains('smokeCode')) { $code = [string]$_.Exception.Data['smokeCode']; $status = [string]$_.Exception.Data['smokeStatus'] }
    if ($script:root) { Write-Report $status $code }
    Write-Host ('Remote image smoke: ' + $status + ' (' + $code + ').')
    $exitCode = $(if ($status -eq 'environment-blocked') { 2 } else { 1 })
} finally {
    # Forced teardown retains a failed result; it is never accepted normal exit.
    foreach ($process in @($script:app, $script:ownedHost, $script:server)) {
        if ($process) { try { if (-not $process.HasExited) { $process.Kill($true); $null = $process.WaitForExit(5000) } } catch { }; $process.Dispose() }
    }
    $env:WINAPP_UI_WORKFLOW_ID = $oldWorkflow; $env:WINAPP_CLI_TELEMETRY_OPTOUT = $oldTelemetry
}
exit $exitCode
