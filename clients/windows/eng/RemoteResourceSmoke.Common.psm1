# Trusted repository helpers only. Importing this module never launches a process.
Set-StrictMode -Version Latest
function Get-RemoteSmokeFunctions {
    $errors = $null; $tokens = $null
    $ast = [Management.Automation.Language.Parser]::ParseFile((Join-Path $PSScriptRoot 'Test-TransferSmoke.ps1'), [ref]$tokens, [ref]$errors)
    if ($errors.Count -ne 0) { throw 'transfer_helper_parse_failed' }
    $names = @('Stop-Smoke', 'Assert-ExternalPath', 'Wait-Condition', 'Invoke-WinApp', 'Find-Elements', 'Unique-Elements', 'Elements',
        'Find-Control', 'Control', 'Act', 'Set-Value', 'Has-Text', 'Reveal-ConnectionSubmit')
    $definitions = foreach ($name in $names) {
        $found = @($ast.FindAll({ param($node) $node -is [Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -ceq $name }, $false))
        if ($found.Count -ne 1 -or $found[0].Parent -ne $ast.EndBlock) { throw 'trusted_helper_not_unique_top_level' }
        $found[0].Extent.Text
    }
    # Only the explicitly enumerated function definitions are evaluated by the
    # caller. The transfer script's parameters, try/finally and journey never run.
    return [scriptblock]::Create(($definitions -join "`n"))
}
function Get-RemoteSmokeNativeSource {
    $source = [IO.File]::ReadAllText((Join-Path $PSScriptRoot 'Test-PlayerSmoke.ps1'))
    $match = [regex]::Match($source, '(?s)\$nativeSource = @''\r?\n(.*?)\r?\n''@')
    if (-not $match.Success) { throw 'tested_native_helper_missing' }
    return $match.Groups[1].Value
}
function Test-RemoteImageRecentEntry($Entries, [string] $Name, [long] $NotBefore) {
    # ASCII unreserved identity: Go EncodeWirePath escapes each segment, leaving
    # this exact slash/name unchanged. No display-path inference is performed.
    if ($Name -cnotmatch '^fileway-image-ci-[a-f0-9]{32}\.png$') { throw 'synthetic_identity_invalid' }
    if ($Entries -isnot [array] -or $Entries.Count -gt 100) { throw 'recent_array_contract_mismatch' }
    $path = '/' + $Name
    $matches = @($Entries | Where-Object { $_ -is [Collections.IDictionary] -and $_.Contains('path') -and $_.path -ceq $path })
    if ($matches.Count -ne 1) { return $false }
    $entry = $matches[0]
    foreach ($field in @('id', 'name', 'isDir', 'accessedAt', 'wirePath', 'pathVerified')) {
        if (-not $entry.Contains($field)) { return $false }
    }
    return ($entry.id -is [string] -and $entry.id -cmatch '^[0-9]{8}T[0-9]{6}\.[0-9]{9}-[a-f0-9]{16}$' -and $entry.name -is [string] -and $entry.name -ceq $Name -and
        $entry.isDir -is [bool] -and -not $entry.isDir -and $entry.pathVerified -is [bool] -and $entry.pathVerified -and
        $entry.wirePath -is [string] -and $entry.wirePath -ceq $path -and $entry.accessedAt -is [long] -and $entry.accessedAt -ge $NotBefore -and
        $entry.accessedAt -le [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds() + 5000)
}
function Get-RemoteImagePixelSource {
    # Pure managed pixel policy, usable in offline tests with no desktop/PInvoke.
    return @'
using System;
public static class FilewayRemoteImagePixels {
    public static int OrderedBandRows(byte[] pixels, int width, int height) {
        if(pixels==null || width<1 || height<1 || width>8192 || height>8192 || (long)width*height*4!=pixels.Length) throw new ArgumentException("pixel_shape_invalid");
        int rows=0;
        for(int y=0;y<height;y++) {
            int red=0,green=0,blue=0,stage=0;
            for(int x=0;x<width;x++) {
                int i=(y*width+x)*4, r=pixels[i+2],g=pixels[i+1],b=pixels[i];
                bool rr=r>=239&&g<=16&&b<=16, gg=g>=239&&r<=16&&b<=16, bb=b>=239&&r<=16&&g<=16;
                if(stage==0 && rr) red++;
                else if(red>=48 && gg && stage<=1) { stage=1;green++; }
                else if(green>=48 && bb && stage<=2) { stage=2;blue++; }
                else if(stage==2 && !bb) break;
                else if(stage==1 && rr) { red=1;green=0;stage=0; }
                else if(stage==0 && red<48) red=0;
            }
            if(red>=48&&green>=48&&blue>=48) rows++;
        }
        return rows;
    }
}
'@
}
Export-ModuleMember -Function Get-RemoteSmokeFunctions, Get-RemoteSmokeNativeSource, Test-RemoteImageRecentEntry, Get-RemoteImagePixelSource
