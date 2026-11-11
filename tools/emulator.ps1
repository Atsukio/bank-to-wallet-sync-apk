<#
.SYNOPSIS
    Shared emulator helpers for screenshots.ps1 and demo-gif.ps1; dot-source it, then call Start-DemoEmulator.

.DESCRIPTION
    Builds the debug APK, starts the emulator if none is running, installs the app and gives it a
    1080x2160 screen with a tidy status bar. Only ever talks to an emulator, never to a phone.
#>

$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

$sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { Join-Path $env:LOCALAPPDATA "Android\Sdk" }
$adb = Join-Path $sdk "platform-tools\adb.exe"
$emulator = Join-Path $sdk "emulator\emulator.exe"
$apk = "app\build\outputs\apk\debug\app-debug.apk"
$demo = "am broadcast -a com.android.systemui.demo -e command"

# An emulator only: its serial starts with "emulator-". A connected phone is never touched.
function Get-Emulator {
    (& $adb devices) | Where-Object { $_ -match "^(emulator-\d+)\s+device$" } | ForEach-Object { $Matches[1] } | Select-Object -First 1
}

function Adb { & $adb -s $serial @args }

function Start-DemoEmulator([string]$Avd, [switch]$NoBuild) {
    if (-not $NoBuild) {
        if (-not $env:JAVA_HOME) { $env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-17.0.20.101-hotspot" }
        & .\gradlew.bat assembleDebug --no-daemon --console=plain -q
        if ($LASTEXITCODE -ne 0) { throw "Build failed" }
    }
    $script:serial = Get-Emulator
    if (-not $script:serial) {
        Write-Host "Starting the $Avd emulator..."
        Start-Process $emulator -ArgumentList "-avd", $Avd, "-no-snapshot-save", "-no-boot-anim", "-no-audio" -WindowStyle Minimized
        for ($i = 0; $i -lt 90 -and -not $script:serial; $i++) { Start-Sleep 2; $script:serial = Get-Emulator }
        if (-not $script:serial) { throw "The emulator didn't start" }
    }
    while ((Adb shell getprop sys.boot_completed) -ne "1") { Start-Sleep 2 }

    Adb install -r $apk | Out-Null

    # Play takes at most 2:1, so 1080x2160; and a tidy status bar: noon, full battery, full Wi-Fi, no icons.
    Adb shell wm size 1080x2160
    Adb shell settings put global sysui_demo_allowed 1
    Adb shell "$demo enter" | Out-Null
    Adb shell "$demo clock -e hhmm 1200" | Out-Null
    Adb shell "$demo battery -e level 100 -e plugged false" | Out-Null
    Adb shell "$demo network -e wifi show -e level 4 -e fully true" | Out-Null
    Adb shell "$demo network -e mobile hide" | Out-Null
    Adb shell "$demo notifications -e visible false" | Out-Null
    Adb shell cmd uimode night no | Out-Null
}

# Opens the app afresh, straight into the demo (debug builds only; see MainActivity.EXTRA_DEMO).
function Open-Demo {
    Adb shell am start -S -W -n app.banksync/.MainActivity --ez demo true | Out-Null
    Start-Sleep 3
}

# Taps the first on-screen element whose text is like $text (a -like pattern; a trailing * is added),
# scrolling down to find it.
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

# Copies a file off the emulator, through cmd so adb's progress line on stderr stays quiet.
function Pull([string]$from, [string]$to) {
    cmd /c "`"$adb`" -s $serial pull $from `"$to`" >nul 2>&1"
}

# Leaves the emulator as it was.
function Stop-DemoEmulator {
    Adb shell cmd uimode night no | Out-Null
    Adb shell "$demo exit" | Out-Null
    Adb shell wm size reset
    Adb shell am force-stop app.banksync
}
