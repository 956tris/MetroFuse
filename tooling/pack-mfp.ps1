param(
    [Parameter(Mandatory=$true)][string]$Source,
    [Parameter(Mandatory=$true)][string]$Output
)
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem
$pluginRoot = (Resolve-Path -LiteralPath $Source).Path
$pluginManifestPath = Join-Path $pluginRoot 'manifest.json'
$pluginManifest = Get-Content -LiteralPath $pluginManifestPath -Raw | ConvertFrom-Json
if ($pluginManifest.runtime -ne 'javascript' -or $pluginManifest.api -ne '1.1.0') { throw 'This packer accepts JavaScript API 1.1.0 plugins only' }
$pluginFiles = @(Get-ChildItem -LiteralPath $pluginRoot -File -Recurse | Where-Object { $_.FullName -ne $pluginManifestPath } | Sort-Object FullName)
$pluginHashes = [ordered]@{}
foreach ($pluginFile in $pluginFiles) {
    $pluginRelative = $pluginFile.FullName.Substring($pluginRoot.Length + 1).Replace('\','/')
    $pluginHashes[$pluginRelative] = (Get-FileHash -LiteralPath $pluginFile.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
}
$pluginManifest | Add-Member -NotePropertyName integrity -NotePropertyValue $pluginHashes -Force
$pluginOutput = [System.IO.Path]::GetFullPath($Output)
if ([System.IO.Path]::GetExtension($pluginOutput) -ne '.mfp') { throw 'Output must use .mfp extension' }
[System.IO.Directory]::CreateDirectory([System.IO.Path]::GetDirectoryName($pluginOutput)) | Out-Null
$pluginStream = [System.IO.File]::Open($pluginOutput, [System.IO.FileMode]::Create)
$pluginArchive = [System.IO.Compression.ZipArchive]::new($pluginStream,[System.IO.Compression.ZipArchiveMode]::Create)
try {
    $pluginEntries = [ordered]@{'manifest.json'=[System.Text.Encoding]::UTF8.GetBytes(($pluginManifest | ConvertTo-Json -Depth 30 -Compress))}
    foreach ($pluginFile in $pluginFiles) { $pluginEntries[$pluginFile.FullName.Substring($pluginRoot.Length+1).Replace('\','/')] = [System.IO.File]::ReadAllBytes($pluginFile.FullName) }
    foreach ($pluginName in $pluginEntries.Keys) {
        $pluginEntry = $pluginArchive.CreateEntry($pluginName,[System.IO.Compression.CompressionLevel]::Optimal)
        $pluginEntry.LastWriteTime = [DateTimeOffset]::new(1980,1,1,0,0,0,[TimeSpan]::Zero)
        $pluginEntryStream = $pluginEntry.Open()
        try { $pluginBytes=$pluginEntries[$pluginName]; $pluginEntryStream.Write($pluginBytes,0,$pluginBytes.Length) } finally { $pluginEntryStream.Dispose() }
    }
} finally { $pluginArchive.Dispose(); $pluginStream.Dispose() }
Write-Output $pluginOutput
