param(
    [Alias('PublishDirectory')][string] $PublisherInput,
    [string] $Version = '0.2.1',
    [string] $ArtifactsRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..\..\..\Fileway-windows-artifacts\local')),
    [string] $ToolsRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..\..\..\Fileway-windows-tools')),
    [string] $OutputDirectory
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
if ($Version -notmatch '^(\d+)\.(\d+)\.(\d+)(?:-preview\.(\d+))?$') { throw 'Use a numeric x.y.z version.' }
$revision = if ($Matches.ContainsKey(4)) { $Matches[4] } else { '0' }
$fileVersion = "$($Matches[1]).$($Matches[2]).$($Matches[3]).$revision"
foreach ($part in $fileVersion.Split('.')) { if ([long]$part -gt 65535) { throw 'Version components must fit a Windows file version.' } }
$ArtifactsRoot = [System.IO.Path]::GetFullPath($ArtifactsRoot)
$ToolsRoot = [System.IO.Path]::GetFullPath($ToolsRoot)
if ([string]::IsNullOrWhiteSpace($PublisherInput)) { $PublisherInput = Join-Path $ArtifactsRoot 'publish\Release\win-x64' }
if ([string]::IsNullOrWhiteSpace($OutputDirectory)) { $OutputDirectory = Join-Path $ArtifactsRoot 'dist' }
$publish = (Resolve-Path -LiteralPath $PublisherInput).Path
$OutputDirectory = [System.IO.Path]::GetFullPath($OutputDirectory)
$repository = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..\..')).TrimEnd('\')
foreach ($external in @($ArtifactsRoot, $ToolsRoot, $OutputDirectory)) {
    if ($external.Equals($repository, [StringComparison]::OrdinalIgnoreCase) -or $external.StartsWith($repository + '\', [StringComparison]::OrdinalIgnoreCase)) { throw 'Toolchain, staging and distribution output must stay outside Git.' }
}
foreach ($writeRoot in @($OutputDirectory, (Join-Path $ArtifactsRoot 'package-staging'))) {
    if ($writeRoot.Equals($publish, [StringComparison]::OrdinalIgnoreCase) -or $writeRoot.StartsWith($publish.TrimEnd('\') + '\', [StringComparison]::OrdinalIgnoreCase)) { throw 'Packaging must not write inside its publish input.' }
}
foreach ($required in @('Fileway.App.exe', 'coreclr.dll', 'Microsoft.UI.Xaml.dll', 'App.xbf', 'MainPage.xbf', 'MainWindow.xbf', 'Fileway.App.pri', 'Assets\AppIcon.ico', 'fileway-host.exe', 'host-build.json', 'media\libmpv-2.dll', 'media\runtime-lock.json')) {
    if (-not (Test-Path -LiteralPath (Join-Path $publish $required) -PathType Leaf)) { throw "Publish input is missing $required. Run the existing publish workflow first; packaging never rebuilds." }
}
$licenseRoot = Join-Path $publish 'media\licenses'
if (-not (Test-Path -LiteralPath $licenseRoot -PathType Container) -or @(Get-ChildItem -LiteralPath $licenseRoot -Recurse -File).Count -eq 0) { throw 'Media license evidence is missing. G5 redistribution review remains required.' }
$entries = @(Get-ChildItem -LiteralPath $publish -Recurse -Force)
if (@($entries | Where-Object { $_.Attributes -band [IO.FileAttributes]::ReparsePoint }).Count -ne 0) { throw 'Publish input must not contain reparse points.' }
$payloadFiles = @($entries | Where-Object { -not $_.PSIsContainer } | Sort-Object FullName)
foreach ($entry in $payloadFiles) {
    if ($entry.Extension -in @('.db', '.sqlite', '.sqlite3', '.pfx', '.key', '.pem') -or $entry.Name -in @('.env', 'credentials.json')) { throw 'Publish input contains data or signing material; remove it through the publishing workflow.' }
    if ($entry.Name -in @('PREVIEW-PACKAGE.json', 'PREVIEW-README.txt', 'uninstall.exe', 'product-language.txt')) { throw 'Publish input contains reserved packaging or installation files; use the original publish directory.' }
}
New-Item -ItemType Directory -Path $OutputDirectory -Force | Out-Null
$baseName = if ($Version.Contains('-preview.')) { "Fileway-$Version-win-x64-unsigned-preview" } else { "Fileway-$Version-win-x64-unsigned" }
$zipPath = Join-Path $OutputDirectory ($baseName + '.zip')
$installerPath = Join-Path $OutputDirectory ($baseName + '-setup.exe')
$receiptPath = Join-Path $OutputDirectory ($baseName + '.json')
foreach ($output in @($zipPath, $installerPath, $receiptPath)) { if (Test-Path -LiteralPath $output) { throw 'Output already exists. Use a new output directory or version; packaging never replaces an existing delivery.' } }

$nsisVersion = '3.12'
$nsisArchiveSha256 = '56581f90db321581c5381193d796fffcf2d24b2f8fed2160a6c6a3baa67f2c4f'
$nsisSource = 'https://master.dl.sourceforge.net/project/nsis/NSIS%203/3.12/nsis-3.12.zip?viasf=1'
$compiler = Join-Path $ToolsRoot "nsis-$nsisVersion\makensis.exe"
if (-not (Test-Path -LiteralPath $compiler)) {
    $downloadRoot = Join-Path $ToolsRoot 'downloads'
    New-Item -ItemType Directory -Path $downloadRoot -Force | Out-Null
    $archive = Join-Path $downloadRoot "nsis-$nsisVersion.zip"
    if (-not (Test-Path -LiteralPath $archive) -or (Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash.ToLowerInvariant() -ne $nsisArchiveSha256) {
        & curl.exe --fail --location --max-time 120 --output $archive $nsisSource
        if ($LASTEXITCODE -ne 0) { throw 'Official NSIS portable download failed.' }
    }
    if ((Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash.ToLowerInvariant() -ne $nsisArchiveSha256) { throw 'The fixed NSIS archive hash does not match the reviewed download.' }
    Expand-Archive -LiteralPath $archive -DestinationPath $ToolsRoot
}
$actualNsisVersion = (& $compiler /VERSION).Trim()
if ($LASTEXITCODE -ne 0 -or $actualNsisVersion -ne "v$nsisVersion") { throw 'Unexpected NSIS compiler version.' }

$stage = Join-Path $ArtifactsRoot ('package-staging\' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $stage -Force | Out-Null
$manifest = @()
foreach ($file in $payloadFiles) {
    $relative = [System.IO.Path]::GetRelativePath($publish, $file.FullName)
    if ($relative.Contains('$') -or $relative.Contains('"') -or $relative.Contains("`r") -or $relative.Contains("`n")) { throw 'A payload filename cannot be safely represented in NSIS.' }
    $destination = Join-Path $stage $relative
    New-Item -ItemType Directory -Path (Split-Path -Parent $destination) -Force | Out-Null
    $before = (Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
    Copy-Item -LiteralPath $file.FullName -Destination $destination
    if ((Get-FileHash -LiteralPath $destination -Algorithm SHA256).Hash.ToLowerInvariant() -ne $before) { throw 'Publish input changed during staging. Retry against a stable publish directory.' }
    $manifest += [ordered]@{ path = $relative; length = (Get-Item -LiteralPath $destination).Length; sha256 = $before }
}
$metadata = [ordered]@{
    product = 'Fileway'; version = $Version; architecture = 'win-x64'; distribution = 'unsigned local development preview'
    signing = 'unsigned'; redistributionGate = 'G5 license completeness and public distribution review not completed'
    payload = $manifest
}
$metadata | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $stage 'PREVIEW-PACKAGE.json') -Encoding utf8
@"
Fileway $Version — unsigned local development preview

Extract the ZIP into a new folder and run Fileway.App.exe, or use the per-user installer.
The installer lets you choose a folder; the default is LOCALAPPDATA\Programs\Fileway Preview.
It remembers the last successful folder and installs into its versions\$Version subfolder.
The desktop shortcut is selected by default and can be disabled. Start menu shortcuts are version-specific.
Installer language defaults to Chinese for Chinese UI or China region, and English otherwise; you can change it.
The selected language names the app 栖卷 or Fileway and is saved in product-language.txt beside the installed EXE.
The ZIP has no installation language selection and follows the local UI language or region.
Close Fileway normally before installing or uninstalling. No process is forcefully stopped.
No file associations, startup entries, system services, or global configuration are created.
Uninstall removes this version's program files and unchanged shortcuts it created; other versions, extra files and downloads are kept.

This package is unsigned and intended only for local development acceptance.
Presence of media/licenses is not a completed redistribution license audit.
G5 license completeness and public distribution approval remain required.
"@ | Set-Content -LiteralPath (Join-Path $stage 'PREVIEW-README.txt') -Encoding utf8

$stageFiles = @(Get-ChildItem -LiteralPath $stage -Recurse -File | Sort-Object FullName)
$uninstallFile = Join-Path (Split-Path -Parent $stage) ((Split-Path -Leaf $stage) + '-uninstall.nsh')
$deletes = @($stageFiles | ForEach-Object { 'Delete "$INSTDIR\' + [System.IO.Path]::GetRelativePath($stage, $_.FullName) + '"' })
$directories = @(Get-ChildItem -LiteralPath $stage -Recurse -Directory | Sort-Object { $_.FullName.Length } -Descending | ForEach-Object { 'RMDir "$INSTDIR\' + [System.IO.Path]::GetRelativePath($stage, $_.FullName) + '"' })
@($deletes + $directories) | Set-Content -LiteralPath $uninstallFile -Encoding utf8
Add-Type -AssemblyName System.IO.Compression.FileSystem
[System.IO.Compression.ZipFile]::CreateFromDirectory($stage, $zipPath, [System.IO.Compression.CompressionLevel]::Optimal, $false)
$script = Join-Path $PSScriptRoot '..\installer\Fileway-Preview.nsi'
& $compiler /NOCONFIG /INPUTCHARSET UTF8 /V2 "/DPAYLOAD_DIR=$stage" "/DPACKAGE_VERSION=$Version" "/DFILE_VERSION=$fileVersion" "/DOUTPUT_INSTALLER=$installerPath" "/DUNINSTALL_FILES=$uninstallFile" $script
if ($LASTEXITCODE -ne 0 -or -not (Test-Path -LiteralPath $installerPath)) { throw 'NSIS preview compilation failed.' }
[ordered]@{
    version = $Version; publishInput = $publish; stagedInput = $stage; unsigned = $true; installed = $false
    nsis = [ordered]@{ version = $actualNsisVersion; source = $nsisSource; reviewedArchiveSha256 = $nsisArchiveSha256 }
    licenseGate = 'G5 redistribution license completeness remains unverified'
    outputs = @($zipPath, $installerPath | ForEach-Object { [ordered]@{ file = [IO.Path]::GetFileName($_); length = (Get-Item -LiteralPath $_).Length; sha256 = (Get-FileHash -LiteralPath $_ -Algorithm SHA256).Hash.ToLowerInvariant() } })
} | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $receiptPath -Encoding utf8
Write-Output $zipPath
Write-Output $installerPath
Write-Output $receiptPath
