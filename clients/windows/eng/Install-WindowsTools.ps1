param(
    [string] $ToolsRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..\..\..\Fileway-windows-tools'))
)
$ErrorActionPreference = 'Stop'
$toolsDirectory = [System.IO.Path]::GetFullPath($ToolsRoot)
$downloadDirectory = Join-Path $toolsDirectory 'downloads'
New-Item -ItemType Directory -Path $downloadDirectory -Force | Out-Null
$archives = @(
    @{
        Name = 'dotnet-sdk-10.0.401-win-x64.zip'
        Url = 'https://builds.dotnet.microsoft.com/dotnet/Sdk/10.0.401/dotnet-sdk-10.0.401-win-x64.zip'
        Algorithm = 'SHA512'
        Hash = '24b670ad3d923bfcf47df6c3b034152398b42f6dbc388e10d783aee1cfb5e5817d399fc0ae2a12cfa822a55e61d34830ccb15c50ef6efee437ab874bb7c79430'
        Directory = 'dotnet-10.0.401'
    },
    @{
        Name = 'go1.26.6.windows-amd64.zip'
        Url = 'https://go.dev/dl/go1.26.6.windows-amd64.zip'
        Algorithm = 'SHA256'
        Hash = '5b6c5b556525810463b5c897b50dc7a82d6a3dc0bfaf55d990a7e9f31d6b2318'
        Directory = 'go-1.26.6'
    },
    @{
        Name = 'winappcli-0.7.1-x64.zip'
        Url = 'https://github.com/microsoft/winappCli/releases/download/v0.7.1/winappcli-x64.zip'
        Algorithm = 'SHA256'
        Hash = 'd925d1e32cdc320b6d271f653fd2cd3c05b5d7558deb20d1b548bead77900fcf'
        Directory = 'winapp-0.7.1'
    }
)
foreach ($archive in $archives) {
    $archivePath = Join-Path $downloadDirectory $archive.Name
    $archiveValid = (Test-Path -LiteralPath $archivePath) -and ((Get-FileHash -LiteralPath $archivePath -Algorithm $archive.Algorithm).Hash -eq $archive.Hash)
    if (-not $archiveValid) {
        $partialPath = "$archivePath.part"
        if (Test-Path -LiteralPath $archivePath) {
            if (Test-Path -LiteralPath $partialPath) { throw 'An incomplete archive and partial file both exist. Inspect them before retrying.' }
            Move-Item -LiteralPath $archivePath -Destination $partialPath
        }
        Write-Output "Downloading $($archive.Name)"
        & curl.exe --fail --location --http1.1 --silent --show-error --retry 3 --retry-all-errors --retry-delay 2 --connect-timeout 15 --speed-time 60 --speed-limit 1024 --continue-at - --output $partialPath $archive.Url
        if ($LASTEXITCODE -ne 0) { throw "Download failed: $($archive.Name). Partial data retained." }
        if ((Get-FileHash -LiteralPath $partialPath -Algorithm $archive.Algorithm).Hash -ne $archive.Hash) { throw "Hash mismatch: $($archive.Name). File retained for inspection." }
        Move-Item -LiteralPath $partialPath -Destination $archivePath
    }
    $destination = Join-Path $toolsDirectory $archive.Directory
    if (-not (Test-Path -LiteralPath $destination)) {
        $stagingDirectory = "$destination.extracting-$([Guid]::NewGuid().ToString('N'))"
        $rootPrefix = $toolsDirectory.TrimEnd('\') + '\'
        foreach ($target in @($stagingDirectory, $destination)) {
            if (-not ([System.IO.Path]::GetFullPath($target).StartsWith($rootPrefix, [StringComparison]::OrdinalIgnoreCase))) { throw 'Tool extraction target escaped its root.' }
        }
        Expand-Archive -LiteralPath $archivePath -DestinationPath $stagingDirectory
        Move-Item -LiteralPath $stagingDirectory -Destination $destination
    }
    Write-Output "Verified $($archive.Name)"
}
$env:DOTNET_CLI_TELEMETRY_OPTOUT = '1'
$env:WINAPP_CLI_TELEMETRY_OPTOUT = '1'
$env:DOTNET_CLI_HOME = Join-Path $toolsDirectory 'state\dotnet-home'
& (Join-Path $toolsDirectory 'dotnet-10.0.401\dotnet.exe') --list-sdks
if ($LASTEXITCODE -ne 0) { throw '.NET SDK verification failed.' }
& (Join-Path $toolsDirectory 'go-1.26.6\go\bin\go.exe') version
if ($LASTEXITCODE -ne 0) { throw 'Go verification failed.' }
& (Join-Path $toolsDirectory 'winapp-0.7.1\winapp.exe') --version
if ($LASTEXITCODE -ne 0) { throw 'winapp verification failed.' }
