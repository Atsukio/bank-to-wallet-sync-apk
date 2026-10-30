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
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

$sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { Join-Path $env:LOCALAPPDATA "Android\Sdk" }
$adb = Join-Path $sdk "platform-tools\adb.exe"
$emulator = Join-Path $sdk "emulator\emulator.exe"
$apk = "app\build\outputs\apk\debug\app-debug.apk"

if (-not $NoBuild) {
    if (-not $env:JAVA_HOME) { $env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-17.0.20.101-hotspot" }
    & .\gradlew.bat assembleDebug --no-daemon --console=plain -q
    if ($LASTEXITCODE -ne 0) { throw "Build failed" }
}

# An emulator only: its serial starts with "emulator-". A connected phone is never touched.
function Get-Emulator {
    (& $adb devices) | Where-Object { $_ -match "^(emulator-\d+)\s+device$" } | ForEach-Object { $Matches[1] } | Select-Object -First 1
}
$serial = Get-Emulator
if (-not $serial) {
    Write-Host "Starting the $Avd emulator..."
    Start-Process $emulator -ArgumentList "-avd", $Avd, "-no-snapshot-save", "-no-boot-anim", "-no-audio" -WindowStyle Minimized
    for ($i = 0; $i -lt 90 -and -not $serial; $i++) { Start-Sleep 2; $serial = Get-Emulator }
    if (-not $serial) { throw "The emulator didn't start" }
}
function Adb { & $adb -s $serial @args }
while ((Adb shell getprop sys.boot_completed) -ne "1") { Start-Sleep 2 }

Adb install -r $apk | Out-Null

# Play takes at most 2:1, so 1080x2160; and a tidy status bar: noon, full battery, full Wi-Fi, no icons.
Adb shell wm size 1080x2160
Adb shell settings put global sysui_demo_allowed 1
$demo = "am broadcast -a com.android.systemui.demo -e command"
Adb shell "$demo enter" | Out-Null
Adb shell "$demo clock -e hhmm 1200" | Out-Null
Adb shell "$demo battery -e level 100 -e plugged false" | Out-Null
Adb shell "$demo network -e wifi show -e level 4 -e fully true" | Out-Null
Adb shell "$demo network -e mobile hide" | Out-Null
Adb shell "$demo notifications -e visible false" | Out-Null

New-Item -ItemType Directory -Force $Out | Out-Null

# Opens the app afresh, straight into the demo (debug builds only; see MainActivity.EXTRA_DEMO).
function Open-Demo {
    Adb shell am start -S -W -n app.banksync/.MainActivity --ez demo true | Out-Null
    Start-Sleep 3
}

# Taps the first on-screen element whose text starts with $text, scrolling down to find it.
function Tap-Text([string]$text) {
    for ($i = 0; $i -lt 6; $i++) {
        Adb shell uiautomator dump /sdcard/ui.xml | Out-Null
        $xml = [xml]((Adb exec-out cat /sdcard/ui.xml) -join "")
        $node = $xml.SelectNodes("//node") | Where-Object { $_.text -like "$text*" } | Select-Object -First 1
        if ($node -and $node.bounds -match "\[(\d+),(\d+)\]\[(\d+),(\d+)\]") {
            $x = ([int]$Matches[1] + [int]$Matches[3]) / 2
            $y = ([int]$Matches[2] + [int]$Matches[4]) / 2
            # Not too close to the bottom edge, where the tap would land on the navigation bar.
            if ($y -lt 1950) {
                Adb shell input tap $x $y
                Start-Sleep -Milliseconds 1500
                return
            }
        }
        Adb shell input swipe 540 1500 540 900 300
        Start-Sleep -Milliseconds 700
    }
    throw "Couldn't find '$text' on screen"
}

function Shot([string]$name) {
    Adb shell screencap -p /sdcard/shot.png
    # Through cmd, so adb's progress line on stderr stays quiet.
    cmd /c "`"$adb`" -s $serial pull /sdcard/shot.png `"$(Join-Path $Out $name)`" >nul 2>&1"
    Write-Host "  $name"
}

try {
    Adb shell cmd uimode night no | Out-Null
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
    # Leave the emulator as it was.
    Adb shell cmd uimode night no | Out-Null
    Adb shell "$demo exit" | Out-Null
    Adb shell wm size reset
    Adb shell am force-stop app.banksync
}
Write-Host "Saved in $Out"
