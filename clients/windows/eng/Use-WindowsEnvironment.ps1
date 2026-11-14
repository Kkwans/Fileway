param(
    [string] $ToolsRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..\..\..\Fileway-windows-tools')),
    [string] $ArtifactsRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..\..\..\Fileway-windows-artifacts\local')),
    [string] $NuGetPackagesRoot
)
$ErrorActionPreference = 'Stop'
$ToolsRoot = [System.IO.Path]::GetFullPath($ToolsRoot)
$dotnetDirectory = Join-Path $ToolsRoot 'dotnet-10.0.401'
$goDirectory = Join-Path $ToolsRoot 'go-1.26.6\go'
if (-not (Test-Path -LiteralPath (Join-Path $dotnetDirectory 'dotnet.exe'))) { throw 'Isolated .NET SDK 10.0.401 is missing.' }
if (-not (Test-Path -LiteralPath (Join-Path $goDirectory 'bin\go.exe'))) { throw 'Isolated Go 1.26.6 is missing.' }
$env:DOTNET_ROOT = $dotnetDirectory
$env:DOTNET_ROOT_X64 = $dotnetDirectory
$env:DOTNET_CLI_HOME = Join-Path $ToolsRoot 'state\dotnet-home'
$env:DOTNET_CLI_TELEMETRY_OPTOUT = '1'
$env:TESTINGPLATFORM_TELEMETRY_OPTOUT = '1'
$env:DOTNET_SKIP_FIRST_TIME_EXPERIENCE = '1'
$env:DOTNET_NOLOGO = '1'
$env:DOTNET_ADD_GLOBAL_TOOLS_TO_PATH = '0'
$env:WINAPP_CLI_TELEMETRY_OPTOUT = '1'
$env:NUGET_PACKAGES = if ([string]::IsNullOrWhiteSpace($NuGetPackagesRoot)) { Join-Path $ToolsRoot 'cache\nuget' } else { [System.IO.Path]::GetFullPath($NuGetPackagesRoot) }
$env:NUGET_HTTP_CACHE_PATH = Join-Path $ToolsRoot 'cache\nuget-http'
$env:GOPATH = Join-Path $ToolsRoot 'state\go'
$env:GOROOT = $goDirectory
$env:GOCACHE = Join-Path $ToolsRoot 'cache\go-build'
$env:GOMODCACHE = Join-Path $ToolsRoot 'cache\go-mod'
$env:GOTOOLCHAIN = 'local'
$env:FilewayArtifactsRoot = [System.IO.Path]::GetFullPath($ArtifactsRoot)
$env:FILEWAY_WINDOWS_DATA_ROOT = Join-Path $env:FilewayArtifactsRoot 'data'
$env:TEMP = Join-Path $env:FilewayArtifactsRoot 'temp'
$env:TMP = $env:TEMP
$env:PATH = "$dotnetDirectory;$(Join-Path $goDirectory 'bin');$(Join-Path $ToolsRoot 'winapp-0.7.1');$env:PATH"
foreach ($directory in @($env:DOTNET_CLI_HOME, $env:NUGET_PACKAGES, $env:NUGET_HTTP_CACHE_PATH, $env:GOPATH, $env:GOCACHE, $env:GOMODCACHE, $env:FILEWAY_WINDOWS_DATA_ROOT, $env:TEMP)) {
    New-Item -ItemType Directory -Path $directory -Force | Out-Null
}
# This script changes only the calling PowerShell process. It never updates the user/system environment.
