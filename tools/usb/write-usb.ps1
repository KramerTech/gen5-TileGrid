# Write a Tilepad USB bundle onto a FAT32/exFAT drive. Runs on your computer;
# does not connect to the car.
#   .\tools\usb\write-usb.ps1 install|update|uninstall E:\ [bundle-dir]
# bundle-dir holds Tilepad-<action>-usb.zip (default: .\dist, then the current dir).
param(
    [Parameter(Mandatory = $true)][ValidateSet('install', 'update', 'uninstall')][string]$Action,
    [Parameter(Mandatory = $true)][string]$Drive,
    [string]$BundleDir
)
$ErrorActionPreference = 'Stop'

$name = "Tilepad-$Action-usb.zip"
$candidates = @($BundleDir, (Join-Path $PSScriptRoot '..\..\dist'), 'dist', '.') | Where-Object { $_ }
$zip = $candidates | ForEach-Object { Join-Path $_ $name } | Where-Object { Test-Path -LiteralPath $_ -PathType Leaf } | Select-Object -First 1
if (-not $zip) { throw "$name not found; pass the folder you downloaded it to." }
if (-not (Test-Path -LiteralPath $Drive -PathType Container)) { throw "$Drive is not a directory (is the drive mounted?)" }

# Move (never delete) anything from a previous NEMESIS job out of the drive root.
$backup = Join-Path $Drive ("NEMESIS-backup-" + (Get-Date -Format 'yyyyMMdd-HHmmss'))
foreach ($item in 'NEMESIS.data', 'NEMESIS.sh', 'tilepad', 'packages') {
    $path = Join-Path $Drive $item
    if (Test-Path -LiteralPath $path) {
        New-Item -ItemType Directory -Force -Path $backup | Out-Null
        Move-Item -LiteralPath $path -Destination $backup
        Write-Host "moved $item -> $backup\"
    }
}

# Expand-Archive copies bytes as-is, so the scripts keep their LF line endings.
Expand-Archive -LiteralPath $zip -DestinationPath $Drive
Write-Host "Wrote $name to $Drive"
Get-ChildItem -LiteralPath $Drive -Name | Where-Object { $_ -in 'NEMESIS.data', 'tilepad', 'packages' }
Write-Host @"

Next:
  1. Eject the drive, insert it into the car's USB data port (car on, parked).
  2. Tap Yes on the NEMESIS prompt and wait for the result dialog.
  3. Reboot the head unit (pinhole button), then remove the drive.
  The log is written to tilepad-$Action.log on the drive.
"@
