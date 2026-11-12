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
$repo = Split-Path -Parent (Split-Path -Parent $windowsRoot)
$acePackage = Join-Path $repo 'server/frontend/node_modules/ace-builds'
$aceOutput = Join-Path $env:FilewayArtifactsRoot 'code-preview/ace'
New-Item -ItemType Directory -Path $aceOutput -Force | Out-Null
$aceModules = @('ace', 'theme-github_light_default', 'theme-tomorrow_night',
    'mode-csharp', 'mode-c_cpp', 'mode-golang', 'mode-json', 'mode-javascript', 'mode-typescript',
    'mode-python', 'mode-java', 'mode-kotlin', 'mode-rust', 'mode-sh', 'mode-batchfile',
    'mode-powershell', 'mode-sql', 'mode-html', 'mode-css', 'mode-xml', 'mode-yaml', 'mode-toml', 'mode-ini')
foreach ($module in $aceModules) {
    Copy-Item -LiteralPath (Join-Path $acePackage "src-noconflict/$module.js") -Destination $aceOutput -Force
}
Copy-Item -LiteralPath (Join-Path $acePackage 'LICENSE') -Destination (Join-Path $aceOutput 'LICENSE') -Force
$editorSource = Join-Path $windowsRoot 'editor'
$editorWork = Join-Path $env:FilewayArtifactsRoot 'markdown-editor-work'
New-Item -ItemType Directory -Path $editorWork -Force | Out-Null
foreach ($name in 'package.json', 'pnpm-lock.yaml', 'vite.config.js', 'index.html', 'editor.js', 'editor.css') {
    Copy-Item -LiteralPath (Join-Path $editorSource $name) -Destination $editorWork -Force
}
$editorPackageManager = (Get-Content -LiteralPath (Join-Path $editorSource 'package.json') -Raw | ConvertFrom-Json).packageManager
& corepack $editorPackageManager --dir $editorWork install --frozen-lockfile
if ($LASTEXITCODE -ne 0) { throw 'Markdown editor dependencies could not be restored.' }
& corepack $editorPackageManager --dir $editorWork exec vite build
if ($LASTEXITCODE -ne 0) { throw 'Markdown editor bundle failed.' }
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
