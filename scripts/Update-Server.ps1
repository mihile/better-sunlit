param([string]$ServerRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path)
$ErrorActionPreference = 'Stop'
try {
    $ServerRoot = (Resolve-Path -LiteralPath $ServerRoot).Path
    $modJar = Join-Path $ServerRoot 'mods\better-sunlit.jar'
    if (!(Test-Path -LiteralPath $modJar)) { throw 'Better Sunlit is not installed.' }
    $helperDirectory = Join-Path $ServerRoot '.better-sunlit-update\helpers'
    New-Item -ItemType Directory -Force -Path $helperDirectory | Out-Null
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $archive = [IO.Compression.ZipFile]::OpenRead($modJar)
    try {
        $metadata = $archive.GetEntry('assets/better_sunlit/update-build.properties')
        $reader = New-Object IO.StreamReader($metadata.Open())
        try { $text = $reader.ReadToEnd() } finally { $reader.Dispose() }
        if ($text -notmatch '(?m)^channel=server\r?$') { throw 'A server branch JAR is required.' }
        if ($text -notmatch '(?m)^helperSha256=([a-f0-9]{64})\r?$') { throw 'Missing helper identity.' }
        $hash = $Matches[1]
        $helper = Join-Path $helperDirectory ('server-' + $hash + '.jar')
        if (!(Test-Path -LiteralPath $helper)) {
            $entry = $archive.GetEntry('assets/better_sunlit/update-helper.jar')
            [IO.Compression.ZipFileExtensions]::ExtractToFile($entry,$helper,$false)
        }
    } finally { $archive.Dispose() }
    if ((Get-Item -LiteralPath $helper).LinkType -eq 'SymbolicLink') { throw 'Linked helper is not supported.' }
    if ((Get-FileHash -LiteralPath $helper -Algorithm SHA256).Hash.ToLowerInvariant() -ne $hash) { throw 'Helper checksum mismatch.' }
    & java '-Xms8m' '-Xmx96m' '-Djava.awt.headless=true' '-cp' $helper 'dev.sunlit.bettersunlit.update.ServerUpdate' $ServerRoot
    exit $LASTEXITCODE
} catch {
    Write-Error ('Better Sunlit updater: ' + $_.Exception.Message)
    exit 1
}
