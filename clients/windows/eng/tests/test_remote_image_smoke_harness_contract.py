"""Maintained source and actual helper tests, never a substitute native UI pass."""
from pathlib import Path
import re
import shutil
import subprocess
import unittest

ENG = Path(__file__).resolve().parents[1]
SCRIPT = ENG / 'Test-RemoteImageSmoke.ps1'
MODULE = ENG / 'RemoteResourceSmoke.Common.psm1'
SOURCE = SCRIPT.read_text(encoding='utf-8')
COMMON = MODULE.read_text(encoding='utf-8')
ROOT = ENG.parents[2]
PWSH = shutil.which('pwsh')

class RemoteImageSmokeContract(unittest.TestCase):
    def pwsh(self, text):
        command = "$ErrorActionPreference='Stop'; Set-StrictMode -Version Latest; Import-Module '" + str(MODULE).replace("'", "''") + "' -Force; " + text
        result = subprocess.run([PWSH, '-NoProfile', '-Command', command], capture_output=True, text=True, timeout=60)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    def test_public_signature(self):
        self.assertEqual(re.findall(r'\$(\w+)', SOURCE.split('$ErrorActionPreference', 1)[0]),
                         ['AppPath', 'ServerPath', 'WinAppPath', 'OutputRoot', 'PreflightOnly'])

    def test_only_trusted_top_level_functions_reused_without_journey_execution(self):
        self.assertIn('Parser]::ParseFile', COMMON)
        self.assertIn('$found[0].Parent -ne $ast.EndBlock', COMMON)
        self.assertIn('$found[0].Extent.Text', COMMON)
        self.assertIn('. (Get-RemoteSmokeFunctions)', SOURCE)
        self.assertNotIn('Invoke-Expression', SOURCE + COMMON)
        self.assertNotRegex(SOURCE + COMMON, r'(?m)^\. .*Test-(?:Transfer|Player)Smoke')
        self.assertIn("'Reveal-ConnectionSubmit'", COMMON)

    def test_preflight_before_fixture_server_and_app(self):
        call = SOURCE.index("'-File', (Join-Path $PSScriptRoot 'Test-PlayerSmoke.ps1'), '-PreflightOnly'")
        for value in ('New-RemoteImageSmokeFixture.py', '$script:server = [Diagnostics.Process]::Start', '$script:app = [Diagnostics.Process]::Start'):
            self.assertLess(call, SOURCE.index(value))
        self.assertIn('$result.exitCode -ne 0', SOURCE)
        for value in ('checks.interactiveDesktop', 'checks.uia', 'checks.capture'): self.assertIn(value, SOURCE)
        self.assertIn('helper_process_timeout', SOURCE)
        self.assertIn('$process.WaitForExit($TimeoutMilliseconds)', SOURCE)

    def test_no_new_pinvoke_or_private_exports(self):
        self.assertNotIn('DllImport', SOURCE + COMMON)
        self.assertIn('Get-RemoteSmokeNativeSource', SOURCE)
        self.assertIn('Native]::Capture($imageWindow)', SOURCE)
        self.assertNotIn('Copy-Item', SOURCE)
        self.assertNotRegex(SOURCE, r'(?i)Remove-Item|DeleteFile|DeleteDirectory')
        self.assertNotIn('Write-Host $', SOURCE)
        self.assertNotRegex(SOURCE, r'\$(?:raw|errorText|entries|baseline)\s*\|\s*(?:Out-File|Set-Content|Add-Content)')

    def test_exact_allowlist_and_no_reusing_foreign_output(self):
        names = re.findall(r"'([^']+)'", re.search(r'\$allowedArtifacts = @\((.*?)\)\n', SOURCE, re.S).group(1))
        self.assertEqual(names, ['report.json', 'events.jsonl', 'preflight.png', 'preflight-smoke.png', 'remote-image-ready.png'])
        self.assertLess(SOURCE.index('output_has_unexpected_artifact'), SOURCE.index('$script:root = $candidate'))
        self.assertIn('Assert-ExternalPath $OutputRoot', SOURCE)
        self.assertIn('$item.Attributes -band [IO.FileAttributes]::ReparsePoint', SOURCE)
        self.assertIn("$prior.scope -cne 'preflight'", SOURCE)

    def test_real_noauth_server_bound_to_private_loopback(self):
        for literal in ("'--noauth'", "'--address', '127.0.0.1'", "'--disableExec'", "'--disableThumbnails'", "'--disablePreviewResize'",
                        "'--root', $remote", "'--database', (Join-Path $privateRoot 'server.db')", '$start.Environment.Remove($key)', 'CopyToAsync([IO.Stream]::Null)'):
            self.assertIn(literal, SOURCE)
        self.assertNotIn('httptest', SOURCE)
        self.assertNotIn('-MediaRuntimeRoot', SOURCE)

    def test_http_only_get_recents_health_not_open_substitution(self):
        self.assertEqual(SOURCE.count('Invoke-WebRequest'), 2)
        self.assertIn('Invoke-WebRequest -Method Get', SOURCE)
        self.assertIn("'/api/recent?limit=100'", SOURCE)
        self.assertIn("'/health'", SOURCE)
        self.assertNotRegex(SOURCE, r'(?i)-Method\s+(?:Post|Put|Patch|Delete)')
        self.assertNotIn('/api/raw', SOURCE)
        self.assertIn('fresh_recent_baseline_not_empty', SOURCE)
        self.assertIn('Test-RemoteImageRecentEntry (Get-ServerRecents $baseAddress) $name $notBefore', SOURCE)
        for field in ('wirePath', 'pathVerified', 'accessedAt', 'isDir', 'id'): self.assertIn(field, COMMON)
        go = (ROOT / 'server/backend/recent/recent.go').read_text(encoding='utf-8')
        self.assertIn('time.Now().UnixMilli()', go)
        self.assertIn('20060102T150405.000000000', go)
        self.assertIn("'^[0-9]{8}T[0-9]{6}\\.[0-9]{9}-[a-f0-9]{16}$'", COMMON)

    def test_real_image_open_decode_pixels_and_caption_controls(self):
        for value in ("'ListItem' 'select'", "'send-keys', 'enter', '--target', $element.selector", "'--via', 'send-input'",
                      '$result.target -cne $element.selector', '@($result.warnings).Count -ne 0', 'image_window_owner_mismatch',
                      "' · 480 × 320 · Ctrl + 滚轮可缩放'", 'OrderedBandRows', "'^(最大化|Maximize)$'", "'^(关闭|Close)$'"):
            self.assertIn(value, SOURCE)
        self.assertNotIn('ui click', SOURCE)
        image = (ROOT / 'clients/windows/src/Fileway.App/Views/ImageWindow.cs').read_text(encoding='utf-8')
        self.assertLess(image.index('await source.SetBitmapAsync'), image.index('ConfirmOpenedAsync'))
        self.assertIn('480', SOURCE)
        self.assertLess(SOURCE.index('native-remote-image-decoded-pixels'), SOURCE.index('actual-server-recent-record'))

    def test_normal_owned_image_app_host_exit_required(self):
        for value in ('owned_host_identity_mismatch', '$script:ownedHost.StartTime -lt $script:app.StartTime', '$null = $script:ownedHost.Handle',
                      'owned_image_close_timeout', '$script:app.CloseMainWindow()', '$script:app.ExitCode -ne 0', '$script:ownedHost.ExitCode -ne 0'):
            self.assertIn(value, SOURCE)
        self.assertLess(SOURCE.index('application-and-host-shutdown'), SOURCE.index("Write-Report 'passed'"))
        self.assertIn('remoteVideoVerified = $false', SOURCE)
        self.assertIn('Forced teardown retains a failed result', SOURCE)

    def test_actual_workflow_fixed_allowlist_and_guard_order(self):
        workflow = (ROOT / '.github/workflows/windows-remote-image-smoke.yml').read_text(encoding='utf-8')
        uploaded = re.findall(r"\$\{\{ runner\.temp \}\}/Fileway-remote-image-evidence/(\S+)", workflow)
        self.assertEqual(uploaded, ['report.json', 'events.jsonl', 'screenshots/preflight.png', 'screenshots/preflight-smoke.png', 'screenshots/remote-image-ready.png'])
        for pattern in ('test_remote_image_fixture.py', 'test_remote_image_smoke_harness_contract.py'):
            command = 'python -m unittest discover -s eng/tests -p ' + pattern + ' -v'
            self.assertLess(workflow.index(command), workflow.index('Install pinned isolated toolchains'))
            self.assertIn('clients/windows/eng/tests/' + pattern, workflow)
        for value in ('go build -o', 'Test-RemoteImageSmoke.ps1 -AppPath', 'Test-RemoteImageSmoke.ps1 -PreflightOnly'):
            self.assertIn(value, workflow)
        for value in ('-MediaRuntimeRoot', '-Publish', 'pip install', 'vendor/mpv/'):
            self.assertNotIn(value, workflow)

    @unittest.skipUnless(PWSH, 'PowerShell unavailable; actual parser runs in Windows CI')
    def test_actual_parser_and_import_do_not_execute_journey(self):
        self.pwsh(r"""
$errors=$null;$tokens=$null
foreach($path in @('SCRIPT','MODULE')) {
    $null=[Management.Automation.Language.Parser]::ParseFile($path,[ref]$tokens,[ref]$errors)
    if($errors.Count){throw 'parse_failed'}
}
$definitions=Get-RemoteSmokeFunctions
if($definitions -isnot [scriptblock]) {throw 'helper_wrong_type'}
. $definitions
foreach($name in @('Stop-Smoke','Find-Control','Act','Reveal-ConnectionSubmit')) { $null=Get-Command $name }
if(Get-Variable app -Scope Script -ErrorAction SilentlyContinue) {throw 'journey_executed'}
""".replace('SCRIPT', str(SCRIPT).replace("'", "''")).replace('MODULE', str(MODULE).replace("'", "''")))

    @unittest.skipUnless(PWSH, 'PowerShell unavailable; actual caller-scope helper runs in Windows CI')
    def test_imported_wait_condition_reads_callers_script_state(self):
        self.pwsh(r"""
. (Get-RemoteSmokeFunctions)
$script:clock=[Diagnostics.Stopwatch]::StartNew()
$script:app=[pscustomobject]@{HasExited=$false};$script:server=$null
$value=Wait-Condition { 'observed' } 'unused_timeout' 1000
if($value -cne 'observed'){throw 'caller_state_not_read'}
$script:app.HasExited=$true;$code=$null
try{$null=Wait-Condition {$true} 'unused_timeout' 1000}catch{$code=$_.Exception.Message}
if($code -cne 'app_exited_early'){throw ('caller_state_not_guarded:'+$code)}
""")

    @unittest.skipUnless(PWSH, 'PowerShell unavailable; actual helper tests run in Windows CI')
    def test_actual_recent_projection_validates_server_identity_and_evidence(self):
        self.pwsh(r"""
$name='fileway-image-ci-' + ('a'*32) + '.png';$now=[DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
$entry=@{id='20261013T033000.123456789-0123456789abcdef';path=('/'+$name);name=$name;isDir=$false;accessedAt=$now;wirePath=('/'+$name);pathVerified=$true}
if(-not (Test-RemoteImageRecentEntry @($entry) $name ($now-1000))) {throw 'valid_entry_rejected'}
if(Test-RemoteImageRecentEntry @() $name $now) {throw 'empty_passed'}
if(Test-RemoteImageRecentEntry @($entry,$entry) $name $now) {throw 'duplicate_passed'}
foreach($field in @('id','name','isDir','accessedAt','wirePath','pathVerified')) {
    $bad=$entry.Clone();$bad.Remove($field)
    if(Test-RemoteImageRecentEntry @($bad) $name $now) {throw 'missing_field_passed'}
}
$badValues=@{id='invented-id';path='/foreign.png';name='foreign.png';isDir=$true;accessedAt=($now-10000);wirePath='/foreign.png';pathVerified=$false}
foreach($field in $badValues.Keys) {
    $bad=$entry.Clone();$bad[$field]=$badValues[$field]
    if(Test-RemoteImageRecentEntry @($bad) $name $now) {throw 'false_evidence_passed'}
}
foreach($field in @('name','wirePath','isDir','pathVerified','accessedAt')) {
    $bad=$entry.Clone();$bad[$field]=@($entry[$field])
    if(Test-RemoteImageRecentEntry @($bad) $name $now) {throw 'invalid_type_passed'}
}
$bad=$entry.Clone();$bad.accessedAt=$now+100000
if(Test-RemoteImageRecentEntry @($bad) $name $now) {throw 'future_passed'}
""")

    def test_python_resolution_uses_path_precedence_and_validates_single_executable(self):
        self.assertIn('Get-Command python -CommandType Application -TotalCount 1', SOURCE)
        self.assertIn('$commands.Count -ne 1', SOURCE)
        self.assertIn('[IO.Path]::IsPathFullyQualified($path)', SOURCE)
        self.assertIn('[IO.File]::Exists($path)', SOURCE)
        self.assertIn('Invoke-BoundedProcess (Resolve-PythonExecutable)', SOURCE)
        self.assertNotIn('(Get-Command python -CommandType Application).Source', SOURCE)

    @unittest.skipUnless(PWSH, 'PowerShell unavailable; actual resolver tests run in Windows CI')
    def test_actual_resolver_validates_result_and_requested_resolution_contract(self):
        start = SOURCE.index('function Resolve-PythonExecutable')
        helper = SOURCE[start:SOURCE.index('function Get-ServerRecents', start)]
        self.pwsh('. (Get-RemoteSmokeFunctions); ' + helper + r"""
$executable=Join-Path $PSHOME $(if($IsWindows){'pwsh.exe'}else{'pwsh'})
$script:results=@(@{Source=$executable});$script:requests=0
function Get-Command {param($Name,$CommandType,$TotalCount,$ErrorAction)
    if($Name -cne 'python' -or $CommandType -cne 'Application' -or $TotalCount -ne 1 -or $ErrorAction -cne 'SilentlyContinue'){throw 'wrong_resolution_contract'}
    $script:requests++;return $script:results
}
if((Resolve-PythonExecutable) -cne $executable){throw 'resolved_executable_wrong'}
foreach($shape in @('empty','duplicate','relative','missing')) {
    $script:results=switch($shape){
        'empty' {@()};'duplicate' {@(@{Source=$executable},@{Source=$executable})};
        'relative' {@(@{Source='relative.exe'})};'missing' {@(@{Source=(Join-Path ([IO.Path]::GetTempPath()) ('absent-' + [Guid]::NewGuid().ToString('N') + '.exe'))})}
    }
    $code=$null;try{$null=Resolve-PythonExecutable}catch{$code=$_.Exception.Message}
    $expected=if($shape -in @('empty','duplicate')){'python_executable_not_resolved'}else{'python_executable_invalid'}
    if($code -cne $expected){throw ('invalid_executable_accepted:'+$shape+':'+$code)}
}
if($script:requests -ne 5){throw 'not_all_resolution_checks_executed'}
""")

    @unittest.skipUnless(PWSH, 'PowerShell unavailable; actual process/fixture call runs in Windows CI')
    def test_actual_bounded_python_fixture_process(self):
        start = SOURCE.index('function Invoke-BoundedProcess')
        helper = SOURCE[start:SOURCE.index('function Get-ServerRecents', start)]
        fixture_script = str(ENG / 'New-RemoteImageSmokeFixture.py').replace("'", "''")
        self.pwsh('. (Get-RemoteSmokeFunctions); ' + helper + r"""
$directory=Join-Path ([IO.Path]::GetTempPath()) ('FilewayRemoteImageHelper-' + [Guid]::NewGuid().ToString('N'))
$null=New-Item -ItemType Directory -Path $directory
$path=Join-Path $directory 'synthetic.png'
try {
    $fixture=Invoke-BoundedProcess (Resolve-PythonExecutable) @('FIXTURE','--output',$path) 15000
    if($fixture.exitCode -ne 0){throw 'actual_fixture_process_failed'}
    $metadata=$fixture.stdout | ConvertFrom-Json -AsHashtable
    if($metadata.bytes -ne 461223 -or $metadata.sha256 -cne '5a05232336535f96c4c698007a8e562019af15240b901fc3ecd4908b16f6874c') {throw 'actual_fixture_metadata_wrong'}
    if((Get-FileHash -LiteralPath $path).Hash.ToLowerInvariant() -cne $metadata.sha256){throw 'actual_fixture_hash_wrong'}
} finally {
    # Only the exact unique fixture created by this test is removed.
    if([IO.File]::Exists($path)){[IO.File]::Delete($path)}
    [IO.Directory]::Delete($directory)
}
""".replace('FIXTURE', fixture_script))

    @unittest.skipUnless(PWSH, 'PowerShell unavailable; actual open helper runs in Windows CI')
    def test_actual_open_key_precise_target_and_response_guards(self):
        start = SOURCE.index('function Send-OpenKey')
        helper = SOURCE[start:SOURCE.index('$exitCode = 1', start)]
        self.pwsh('. (Get-RemoteSmokeFunctions); ' + helper + r"""
Add-Type 'namespace Fileway.PlayerSmoke { public static class Native { public static uint ProcessId(System.IntPtr w) { return 123; } } }'
$script:app=@{Id=123};$script:actions=0;$script:window=[IntPtr]7
function Act { param($Window,$Pattern,$Type,$Action) if($Window -ne 7 -or $Type -cne 'ListItem' -or $Action -cne 'select'){throw 'wrong_select'};$script:actions++ }
function Control { param($Window,$Pattern,$Type) if($Window -ne 7 -or $Type -cne 'ListItem'){throw 'wrong_target'};return @{selector='exact-owned-row'} }
$script:response=@{hwnd=7;keys='enter';via='send-input';target='exact-owned-row';actionCount=1;warnings=@()}
function Invoke-WinApp {param([string[]] $Arguments)
    if(($Arguments -join '|') -cne 'ui|send-keys|enter|--target|exact-owned-row|-w|7|--via|send-input|--json'){throw 'wrong_argv'}
    return $script:response
}
Send-OpenKey ([IntPtr]7) 'fileway-image-ci-synthetic.png'
if($script:actions -ne 1){throw 'select_missing'}
$badValues=@{hwnd=8;keys='space';via='post-message';target='foreign';actionCount=0;warnings=@('warning')}
$validResponse=$script:response.Clone();$rejected=0
# Hashtable literal key 'keys' shadows its Keys member. Enumerate fixed fields.
foreach($field in @('hwnd','keys','via','target','actionCount','warnings')){
    $script:response=$validResponse.Clone();$script:response[$field]=$badValues[$field];$code=$null
    try {Send-OpenKey ([IntPtr]7) 'fixture.png'} catch {$code=$_.Exception.Message}
    if($code -cne 'image_open_keyboard_contract_mismatch'){throw ('wrong_response_accepted:'+$field+':'+$code)}
    $rejected++
}
if($rejected -ne 6){throw 'negative_field_count_wrong'}
""")

    @unittest.skipUnless(PWSH, 'PowerShell unavailable; actual GET helper runs in Windows CI')
    def test_actual_recents_reader_rejects_nonloopback_and_preserves_empty_array(self):
        start = SOURCE.index('function Get-ServerRecents')
        helper = SOURCE[start:SOURCE.index('function Send-OpenKey', start)]
        self.pwsh('. (Get-RemoteSmokeFunctions); ' + helper + r"""
$script:requests=0
function Invoke-WebRequest {param($Method,$Uri,$TimeoutSec,[switch]$NoProxy)
    if($Method -cne 'Get' -or $Uri -cne 'http://127.0.0.1:12345/api/recent?limit=100' -or $TimeoutSec -ne 3 -or -not $NoProxy){throw 'wrong_request'}
    $script:requests++;return @{StatusCode=200;Content='[]'}
}
$entries=Get-ServerRecents 'http://127.0.0.1:12345'
if($entries -isnot [array] -or $entries.Count -ne 0 -or $script:requests -ne 1){throw 'empty_array_not_preserved'}
foreach($uri in @('https://127.0.0.1:12345','http://example.com','http://127.0.0.1:12345/private','http://127.0.0.1:12345?x=1','http://secret@127.0.0.1:12345')){
    $code=$null;try{$null=Get-ServerRecents $uri}catch{$code=$_.Exception.Message}
    if($code -cne 'loopback_recents_required'){throw 'private_request_not_rejected'}
}
if($script:requests -ne 1){throw 'unapproved_request_sent'}
""")

    @unittest.skipUnless(PWSH, 'PowerShell unavailable; actual managed pixel helper runs in Windows CI')
    def test_actual_pixel_policy_rejects_ui_noise_and_wrong_bands(self):
        self.pwsh(r"""
Add-Type -TypeDefinition (Get-RemoteImagePixelSource)
$width=160;$height=40;$pixels=[byte[]]::new($width*$height*4)
for($y=0;$y -lt $height;$y++) {for($x=0;$x -lt $width;$x++) {
    $offset=($y*$width+$x)*4
    $channel=if($x -lt 50){2}elseif($x -lt 100){1}else{0}
    $pixels[$offset+$channel]=255
}}
if([FilewayRemoteImagePixels]::OrderedBandRows($pixels,$width,$height) -ne 40) {throw 'real_rgb_rejected'}
if([FilewayRemoteImagePixels]::OrderedBandRows([byte[]]::new($pixels.Length),$width,$height) -ne 0) {throw 'blank_passed'}
for($i=0;$i -lt $pixels.Length;$i+=4) { $tmp=$pixels[$i];$pixels[$i]=$pixels[$i+2];$pixels[$i+2]=$tmp }
if([FilewayRemoteImagePixels]::OrderedBandRows($pixels,$width,$height) -ne 0) {throw 'bgr_passed'}
$rejected=$false;try{$null=[FilewayRemoteImagePixels]::OrderedBandRows([byte[]]::new(4),160,40)}catch{$rejected=$true}
if(-not $rejected){throw 'invalid_shape_passed'}
""")

if __name__ == '__main__': unittest.main()
