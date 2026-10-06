# Renders the Store package logos (PNG, every size Windows asks for) from the app logo in
# Source/Android/res/drawable/ic_logo.xml, so no image files need to live in git.
param([Parameter(Mandatory)][string]$Out)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot '../HopDrop.Desktop/Assets/VectorImage.ps1')
$logo = Join-Path $PSScriptRoot '../../Android/res/drawable/ic_logo.xml'
New-Item -ItemType Directory -Force $Out | Out-Null
function Save-Logo([int]$size, [string]$name) { [System.IO.File]::WriteAllBytes((Join-Path $Out $name), (ConvertTo-VectorPng -Path $logo -Size $size -Tile '#FFFFFF')) }

# Scale variants (100% … 400% display scaling) for the Store logo, Start tile and app list icon.
foreach ($scale in 100, 125, 150, 200, 400) {
    Save-Logo ([math]::Round(50 * $scale / 100)) "StoreLogo.scale-$scale.png"
    Save-Logo ([math]::Round(150 * $scale / 100)) "Square150x150Logo.scale-$scale.png"
    Save-Logo ([math]::Round(44 * $scale / 100)) "Square44x44Logo.scale-$scale.png"
}
# Exact sizes for the taskbar, Start and File Explorer; "unplated" = drawn without a coloured plate behind it.
foreach ($size in 16, 20, 24, 30, 32, 36, 40, 48, 60, 64, 72, 80, 96, 256) {
    Save-Logo $size "Square44x44Logo.targetsize-$size.png"
    Save-Logo $size "Square44x44Logo.targetsize-${size}_altform-unplated.png"
    Save-Logo $size "Square44x44Logo.targetsize-${size}_altform-lightunplated.png"
}
Write-Host "Logos in $Out"
