param(
    [Parameter(Mandatory)][string] $HarnessPath,
    [Parameter(Mandatory)][string] $RuntimeDirectory,
    [Parameter(Mandatory)][string] $RuntimeLockPath,
    [Parameter(Mandatory)][string] $FixturePath,
    [Parameter(Mandatory)][string] $OutputRoot
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
if (-not $IsWindows) { throw 'Progressive native playback verification requires Windows; it cannot be skipped.' }
foreach ($path in @($HarnessPath, $RuntimeDirectory, $RuntimeLockPath, $FixturePath, $OutputRoot)) {
    if (-not [IO.Path]::IsPathFullyQualified($path)) { throw 'All progressive verification paths must be absolute.' }
}
foreach ($path in @($HarnessPath, $RuntimeLockPath, $FixturePath, (Join-Path $RuntimeDirectory 'libmpv-2.dll'))) {
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { throw 'A required progressive native test input is missing.' }
}
if (Test-Path -LiteralPath $OutputRoot) { throw 'Progressive evidence output must be a fresh external directory.' }
$start = [Diagnostics.ProcessStartInfo]::new()
$start.FileName = $HarnessPath
$start.UseShellExecute = $false
$start.CreateNoWindow = $true
# ArgumentList preserves spaces and never invokes a shell. The process validates
# the exact locked DLL inventory, fixture bytes and safe external output before I/O.
foreach ($argument in @('--runtime', $RuntimeDirectory, '--runtime-lock', $RuntimeLockPath,
        '--fixture', $FixturePath, '--output-root', $OutputRoot)) {
    $start.ArgumentList.Add($argument)
}
$process = [Diagnostics.Process]::new()
$process.StartInfo = $start
try {
    if (-not $process.Start()) { throw 'Progressive native harness did not start.' }
    if (-not $process.WaitForExit(240000)) {
        # Kill only the exact process this invocation owns. A blocked real native
        # destroy never becomes a pass and is not worked around by changing CFG.
        $process.Kill($true)
        if (-not $process.WaitForExit(10000)) { throw 'The owned native verification process did not terminate.' }
        throw 'Progressive native verification exceeded its hard deadline.'
    }
    if ($process.ExitCode -ne 0) { throw 'Progressive native verification failed.' }
    $reportPath = Join-Path $OutputRoot 'report.json'
    if (-not (Test-Path -LiteralPath $reportPath -PathType Leaf)) { throw 'Progressive verification produced no report.' }
    $report = Get-Content -LiteralPath $reportPath -Raw | ConvertFrom-Json
    if ($report.schemaVersion -ne 1 -or $report.passed -ne $true -or
        $report.coverage -ne 'native-decoder-owned-progressive-read' -or
        $report.visiblePixelsVerified -ne $false -or $report.generalContainerPrefixSupportVerified -ne $false) {
        throw 'Progressive native verification report does not establish required decoder-only coverage.'
    }
    foreach ($proof in @('decodedBeforeComplete', 'boundaryWaitThenContinue', 'manualPausePreserved',
            'blockedReaderCanceled', 'nativeDestroyReturned', 'readerReleasedAfterDestroy',
            'writerSurvivedReaderClose', 'freshOwnedReaderWorked')) {
        if ($report.results.$proof -ne $true) { throw 'Progressive native verification is missing a required proof.' }
    }
    Write-Output 'Progressive native decoder verification passed. This does not verify visible UI pixels or arbitrary container prefix support.'
}
finally { $process.Dispose() }
