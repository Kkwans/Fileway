param(
    [ValidateSet('Debug', 'Release')][string] $Configuration = 'Debug',
    [ValidatePattern('^[0-9]+\.[0-9]+\.[0-9]+(?:-[0-9A-Za-z][0-9A-Za-z.-]*)?$')][string] $HostVersion = '0.1.0-dev',
    [string] $ToolsRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..\..\..\Fileway-windows-tools')),
    [string] $ArtifactsRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..\..\..\Fileway-windows-artifacts\local')),
    [string] $NuGetPackagesRoot
)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'Use-WindowsEnvironment.ps1') -ToolsRoot $ToolsRoot -ArtifactsRoot $ArtifactsRoot -NuGetPackagesRoot $NuGetPackagesRoot
$windowsRoot = Split-Path -Parent $PSScriptRoot
$repositoryRoot = [System.IO.Path]::GetFullPath((Join-Path $windowsRoot '..\..'))
$commit = (& git -C $repositoryRoot rev-parse --verify HEAD).Trim()
if ($LASTEXITCODE -ne 0 -or $commit -notmatch '^[0-9a-f]{40}$') { throw 'Cannot resolve the source commit.' }
$outputDirectory = Join-Path $env:FilewayArtifactsRoot "host\$Configuration"
New-Item -ItemType Directory -Path $outputDirectory -Force | Out-Null
$executable = Join-Path $outputDirectory 'fileway-host.exe'
$previousCgo = $env:CGO_ENABLED
try {
    $env:CGO_ENABLED = '0'
    Push-Location -LiteralPath (Join-Path $windowsRoot 'host')
    try {
        # Omit tsnet's disk buffer and uploader so authorization links stay in the in-memory UI flow.
        & go build -mod=readonly -trimpath -buildvcs=true -tags=ts_omit_logtail -ldflags "-X main.hostVersion=$HostVersion -X main.buildCommit=$commit" -o $executable ./cmd/fileway-host
        if ($LASTEXITCODE -ne 0) { throw 'Go Host build failed.' }
    }
    finally { Pop-Location }
}
finally { $env:CGO_ENABLED = $previousCgo }
$sourceStatus = & git -C $repositoryRoot status --porcelain=v1 --untracked-files=normal
if ($LASTEXITCODE -ne 0) { throw 'Cannot resolve the source working tree state.' }
$dirty = [bool]$sourceStatus
[ordered]@{
    hostVersion = $HostVersion
    buildCommit = $commit
    workingTreeDirty = $dirty
    architecture = 'win-x64'
    goVersion = (& go version)
    sha256 = (Get-FileHash -LiteralPath $executable -Algorithm SHA256).Hash.ToLowerInvariant()
} | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $outputDirectory 'host-build.json') -Encoding utf8
Write-Output $executable
