<#
.SYNOPSIS
    Takes the Play Store phone screenshots in demo mode on the Android emulator.

.DESCRIPTION
    Builds the debug APK, starts the emulator if none is running, installs the app, opens it straight
    into demo mode (made-up data only) and saves each screenshot to store/screenshots at 1080x2160
    with a clean status bar. Only ever talks to an emulator, never to a phone.

    Run from the repo root:  .\tools\screenshots.ps1
    Skip the build:          .\tools\screenshots.ps1 -NoBuild
#>
param(
    [string]$Avd = "Medium_Phone_API_37.0",
    [string]$Out = "store\screenshots",
    [switch]$NoBuild
)

$ErrorActionPreference = "Stop"
. (Join-Path $PSScriptRoot "emulator.ps1")
Start-DemoEmulator -Avd $Avd -NoBuild:$NoBuild

New-Item -ItemType Directory -Force $Out | Out-Null

function Shot([string]$name) {
    Adb shell screencap -p /sdcard/shot.png
    Pull /sdcard/shot.png (Join-Path $Out $name)
    Write-Host "  $name"
}

try {
    Write-Host "Light:"
    Open-Demo; Shot "1-home.png"
    Open-Demo; Tap-Text "Sample Electric"; Shot "2-details.png"
    Tap-Text "Review and change"; Shot "3-approve.png"
    Open-Demo; Tap-Text "Sample Bank: RM30.00"; Tap-Text "Fix"; Shot "4-not-understood.png"
    Open-Demo; Tap-Text "Sample Grocer"; Shot "6-sent.png"

    Adb shell cmd uimode night yes | Out-Null
    Write-Host "Dark:"
    Open-Demo; Shot "5-dark.png"
}
finally {
    Stop-DemoEmulator
}
Write-Host "Saved in $Out"
