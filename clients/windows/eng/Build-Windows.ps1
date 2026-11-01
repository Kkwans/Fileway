param(
    [ValidateSet('Debug', 'Release')][string] $Configuration = 'Debug',
    [string] $ToolsRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..\..\..\Fileway-windows-tools')),
    [string] $ArtifactsRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..\..\..\Fileway-windows-artifacts\local')),
    [string] $NuGetPackagesRoot,
    [string] $MediaRuntimeRoot,
    [switch] $Publish
)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'Use-WindowsEnvironment.ps1') -ToolsRoot $ToolsRoot -ArtifactsRoot $ArtifactsRoot -NuGetPackagesRoot $NuGetPackagesRoot
$windowsRoot = Split-Path -Parent $PSScriptRoot
& (Join-Path $PSScriptRoot 'Build-GoHost.ps1') -Configuration $Configuration -ToolsRoot $ToolsRoot -ArtifactsRoot $ArtifactsRoot -NuGetPackagesRoot $NuGetPackagesRoot
& (Join-Path $PSScriptRoot 'Build-GoServer.ps1') -Configuration $Configuration -ToolsRoot $ToolsRoot -ArtifactsRoot $ArtifactsRoot -NuGetPackagesRoot $NuGetPackagesRoot
$project = Join-Path $windowsRoot 'src\Fileway.App\Fileway.App.csproj'
if (-not $MediaRuntimeRoot) { $MediaRuntimeRoot = Join-Path $env:FilewayArtifactsRoot 'media-runtime\x64' }
$mediaProperty = "-p:FilewayMediaRuntimeRoot=$([System.IO.Path]::GetFullPath($MediaRuntimeRoot))"
$buildLog = Join-Path $env:FilewayArtifactsRoot "winui-$Configuration.binlog"
& dotnet build $project -c $Configuration -p:Platform=x64 $mediaProperty --nologo "-bl:$buildLog"
if ($LASTEXITCODE -ne 0) { throw 'Windows build failed. See the external binlog.' }
if ($Publish) {
    if (-not (Test-Path -LiteralPath (Join-Path $MediaRuntimeRoot 'libmpv-2.dll'))) { throw 'Preview publishing requires the pinned native media runtime. See vendor/mpv.' }
    & dotnet publish $project -c $Configuration -p:Platform=x64 $mediaProperty --no-build --nologo
    if ($LASTEXITCODE -ne 0) { throw 'Windows publish failed.' }
}
