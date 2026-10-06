# Builds HopDrop.ico (16–256 px): the app logo (Source/Android/res/drawable/ic_logo.xml) on a white tile.
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'VectorImage.ps1')
$logo = Join-Path $PSScriptRoot '../../../Android/res/drawable/ic_logo.xml'
$sizes = @(16, 20, 24, 32, 40, 48, 64, 128, 256)
$pngs = @($sizes | ForEach-Object { , (ConvertTo-VectorPng -Path $logo -Size $_ -Tile '#FFFFFF') })
$target = Join-Path $PSScriptRoot 'HopDrop.ico'
$file = [System.IO.File]::Create($target)
$writer = [System.IO.BinaryWriter]::new($file)
try {
    $writer.Write([uint16]0); $writer.Write([uint16]1); $writer.Write([uint16]$sizes.Count)
    $offset = 6 + 16 * $sizes.Count
    for ($i = 0; $i -lt $sizes.Count; $i++) {
        $writer.Write([byte]($sizes[$i] % 256)); $writer.Write([byte]($sizes[$i] % 256))
        $writer.Write([byte]0); $writer.Write([byte]0)
        $writer.Write([uint16]1); $writer.Write([uint16]32)
        $writer.Write([uint32]$pngs[$i].Length); $writer.Write([uint32]$offset)
        $offset += $pngs[$i].Length
    }
    foreach ($png in $pngs) { $writer.Write([byte[]]$png) }
}
finally { $writer.Dispose() }
Write-Host "Created $target"
