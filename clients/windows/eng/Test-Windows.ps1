param(
    [ValidateSet('Debug', 'Release')][string] $Configuration = 'Debug',
    [string] $ToolsRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..\..\..\Fileway-windows-tools')),
    [string] $ArtifactsRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..\..\..\Fileway-windows-artifacts\local')),
    [string] $NuGetPackagesRoot,
    [string[]] $FilterClass
)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'Use-WindowsEnvironment.ps1') -ToolsRoot $ToolsRoot -ArtifactsRoot $ArtifactsRoot -NuGetPackagesRoot $NuGetPackagesRoot
$windowsRoot = Split-Path -Parent $PSScriptRoot
& (Join-Path $PSScriptRoot 'Build-GoHost.ps1') -Configuration $Configuration -ToolsRoot $ToolsRoot -ArtifactsRoot $ArtifactsRoot -NuGetPackagesRoot $NuGetPackagesRoot
$harnessProject = Join-Path $windowsRoot 'tests\Fileway.HostTestHarness\Fileway.HostTestHarness.csproj'
& dotnet build $harnessProject -c $Configuration -p:Platform=x64 --nologo
if ($LASTEXITCODE -ne 0) { throw 'Host test harness build failed.' }
$harnessTargetDirectory = (& dotnet msbuild $harnessProject -getProperty:TargetDir -p:Configuration=$Configuration -p:Platform=x64 -nologo).Trim()
if ($LASTEXITCODE -ne 0 -or -not [System.IO.Path]::IsPathFullyQualified($harnessTargetDirectory)) { throw 'Cannot resolve the external harness output.' }
$env:FILEWAY_TEST_HOST_EXE = Join-Path $env:FilewayArtifactsRoot "host\$Configuration\fileway-host.exe"
$env:FILEWAY_TEST_HOST_MANIFEST = Join-Path $env:FilewayArtifactsRoot "host\$Configuration\host-build.json"
$env:FILEWAY_TEST_HARNESS_EXE = Join-Path $harnessTargetDirectory 'Fileway.HostTestHarness.exe'
$env:FILEWAY_TEST_OUTPUT_ROOT = Join-Path $env:FilewayArtifactsRoot 'tests\infrastructure'
New-Item -ItemType Directory -Path $env:FILEWAY_TEST_OUTPUT_ROOT -Force | Out-Null
$testProject = Join-Path $windowsRoot 'tests\Fileway.Infrastructure.Tests\Fileway.Infrastructure.Tests.csproj'
Push-Location -LiteralPath $windowsRoot
try {
    if ($FilterClass) {
        & dotnet build $testProject -c $Configuration -p:Platform=x64 --nologo
        if ($LASTEXITCODE -ne 0) { throw 'Focused Windows test build failed.' }
        $testTargetDirectory = (& dotnet msbuild $testProject -getProperty:TargetDir -p:Configuration=$Configuration -p:Platform=x64 -nologo).Trim()
        if ($LASTEXITCODE -ne 0 -or -not [System.IO.Path]::IsPathFullyQualified($testTargetDirectory)) { throw 'Cannot resolve focused test output.' }
        $report = Join-Path $env:FILEWAY_TEST_OUTPUT_ROOT 'focused.xml'
        $testArguments = @()
        foreach ($className in $FilterClass) { $testArguments += @('-class', $className) }
        $testArguments += @('-result-xml', $report)
        & (Join-Path $testTargetDirectory 'Fileway.Infrastructure.Tests.exe') @testArguments
        if ($LASTEXITCODE -ne 0 -or -not (Test-Path -LiteralPath $report)) { throw 'Focused Windows tests failed.' }
        [xml]$results = Get-Content -Raw -LiteralPath $report
        $total = 0; $failed = 0; $errors = 0
        foreach ($assembly in $results.assemblies.assembly) {
            $total += [int]$assembly.total; $failed += [int]$assembly.failed; $errors += [int]$assembly.errors
        }
        if ($total -lt 1 -or $failed -ne 0 -or $errors -ne 0) { throw 'Focused tests did not produce a passing, nonempty result.' }
        Write-Output "Focused Windows tests passed: $total tests; $report"
        return
    }
    & dotnet test --project $testProject -c $Configuration -p:Platform=x64 --minimum-expected-tests 1 --results-directory $env:FILEWAY_TEST_OUTPUT_ROOT --report-trx --report-trx-filename infrastructure.trx --no-ansi --no-progress
    if ($LASTEXITCODE -ne 0) { throw 'Windows infrastructure tests failed.' }
}
finally { Pop-Location }
