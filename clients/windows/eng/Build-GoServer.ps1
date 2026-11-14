param(
    [ValidateSet('Debug', 'Release')][string] $Configuration = 'Debug',
    [string] $ServerVersion,
    [switch] $SkipWebBuild,
    [string] $ToolsRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..\..\..\Fileway-windows-tools')),
    [string] $ArtifactsRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..\..\..\Fileway-windows-artifacts\local')),
    [string] $NuGetPackagesRoot
)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'Use-WindowsEnvironment.ps1') -ToolsRoot $ToolsRoot -ArtifactsRoot $ArtifactsRoot -NuGetPackagesRoot $NuGetPackagesRoot
$windowsRoot = Split-Path -Parent $PSScriptRoot
$repositoryRoot = [System.IO.Path]::GetFullPath((Join-Path $windowsRoot '..\..'))
if (-not $ServerVersion) {
    $projectText = Get-Content -LiteralPath (Join-Path $windowsRoot 'src\Fileway.App\Fileway.App.csproj') -Raw
    $match = [regex]::Match($projectText, '<Version>([^<]+)</Version>')
    if (-not $match.Success) { throw 'Cannot resolve the bundled server version from the client project.' }
    $ServerVersion = $match.Groups[1].Value
}
$commit = (& git -C $repositoryRoot rev-parse --verify HEAD).Trim()
if ($LASTEXITCODE -ne 0 -or $commit -notmatch '^[0-9a-f]{40}$') { throw 'Cannot resolve the source commit.' }

# The bundled server must serve the product Web UI, so the same embedded asset tree CI downloads is built here.
$webSource = Join-Path $repositoryRoot 'server\frontend'
$embedded = Join-Path $repositoryRoot 'server\backend\frontend\dist'
if (-not $SkipWebBuild) {
    if (-not (Get-Command pnpm -ErrorAction SilentlyContinue)) { throw 'pnpm is required to build the bundled Web UI. Install it or pass -SkipWebBuild against a prepared dist.' }
    Push-Location -LiteralPath $webSource
    try {
        if (-not (Test-Path -LiteralPath (Join-Path $webSource 'node_modules'))) {
            & pnpm install --frozen-lockfile
            if ($LASTEXITCODE -ne 0) { throw 'Web dependency install failed.' }
        }
        & pnpm exec vite build
        if ($LASTEXITCODE -ne 0) { throw 'Web UI build failed.' }
    }
    finally { Pop-Location }
    & node (Join-Path $repositoryRoot 'server\scripts\embed-web.mjs')
    if ($LASTEXITCODE -ne 0) { throw 'Embedding the Web UI into the server source tree failed.' }
}
$index = Join-Path $embedded 'index.html'
if (-not (Test-Path -LiteralPath $index)) { throw 'The server would ship without a Web UI: server/backend/frontend/dist/index.html is missing. Run this script without -SkipWebBuild or embed a reviewed Web build first.' }
$webFiles = @(Get-ChildItem -LiteralPath $embedded -Recurse -File)
$webBytes = ($webFiles | Measure-Object -Property Length -Sum).Sum

$outputDirectory = Join-Path $env:FilewayArtifactsRoot "server\$Configuration"
New-Item -ItemType Directory -Path $outputDirectory -Force | Out-Null
$executable = Join-Path $outputDirectory 'fileway-server.exe'
$backend = [System.IO.Path]::GetFullPath((Join-Path $repositoryRoot 'server\backend'))
$versionPackage = 'github.com/Kkwans/nas-file-browser/backend/version'
$previousCgo = $env:CGO_ENABLED
try {
    $env:CGO_ENABLED = '0'
    Push-Location -LiteralPath $backend
    try {
        $ldflags = "-s -w -X $versionPackage.Version=Fileway-$ServerVersion -X $versionPackage.CommitSHA=$commit"
        & go build -trimpath -buildvcs=true -ldflags $ldflags -o $executable .
        if ($LASTEXITCODE -ne 0) { throw 'Go server build failed.' }
    }
    finally { Pop-Location }
}
finally { $env:CGO_ENABLED = $previousCgo }
if (-not (Test-Path -LiteralPath $executable)) { throw 'Go server build produced no executable.' }
$sourceStatus = & git -C $repositoryRoot status --porcelain=v1 --untracked-files=normal -- server/backend server/frontend/src server/frontend/public
if ($LASTEXITCODE -ne 0) { throw 'Cannot resolve the source working tree state.' }
$dirty = [bool]$sourceStatus
[ordered]@{
    serverVersion = $ServerVersion
    buildCommit = $commit
    workingTreeDirty = $dirty
    architecture = 'win-x64'
    goVersion = (& go version)
    webAssets = [ordered]@{ fileCount = $webFiles.Count; totalBytes = $webBytes }
    sha256 = (Get-FileHash -LiteralPath $executable -Algorithm SHA256).Hash.ToLowerInvariant()
} | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $outputDirectory 'server-build.json') -Encoding utf8
Write-Output $executable
