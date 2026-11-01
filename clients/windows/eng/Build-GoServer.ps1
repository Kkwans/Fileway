param(
    [ValidateSet('Debug', 'Release')][string] $Configuration = 'Debug',
    [string] $ServerVersion,
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
$sourceStatus = & git -C $repositoryRoot status --porcelain=v1 --untracked-files=normal -- server
if ($LASTEXITCODE -ne 0) { throw 'Cannot resolve the source working tree state.' }
$dirty = [bool]$sourceStatus
[ordered]@{
    serverVersion = $ServerVersion
    buildCommit = $commit
    workingTreeDirty = $dirty
    architecture = 'win-x64'
    goVersion = (& go version)
    sha256 = (Get-FileHash -LiteralPath $executable -Algorithm SHA256).Hash.ToLowerInvariant()
} | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $outputDirectory 'server-build.json') -Encoding utf8
Write-Output $executable
