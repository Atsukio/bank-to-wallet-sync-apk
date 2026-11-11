<#
.SYNOPSIS
    Records the README's demo GIF in demo mode on the Android emulator.

.DESCRIPTION
    Starts the emulator as screenshots.ps1 does, opens the app in demo mode (made-up data only),
    records a short tour (approve a record, then send the rest) with adb screenrecord and
    turns it into docs/demo.gif with ffmpeg. Only ever talks to an emulator, never to a phone.

    Run from the repo root:  .\tools\demo-gif.ps1
    Skip the build:          .\tools\demo-gif.ps1 -NoBuild
#>
param(
    [string]$Avd = "Medium_Phone_API_37.0",
    [string]$Out = "docs\demo.gif",
    [int]$Width = 320,
    [int]$Fps = 12,
    [switch]$NoBuild
)

$ErrorActionPreference = "Stop"
. (Join-Path $PSScriptRoot "emulator.ps1")
if (-not (Get-Command ffmpeg -ErrorAction SilentlyContinue)) { throw "ffmpeg isn't on the PATH" }
Start-DemoEmulator -Avd $Avd -NoBuild:$NoBuild

$video = Join-Path $env:TEMP "banksync-demo.mp4"
New-Item -ItemType Directory -Force (Split-Path $Out) | Out-Null

try {
    Open-Demo
    $recorder = Start-Process $adb -ArgumentList "-s", $serial, "shell", "screenrecord", "--bit-rate", "8000000", "/sdcard/demo.mp4" -PassThru -WindowStyle Hidden
    # Tap-Text pauses about a second and a half after each tap, which is enough to see each step.
    Start-Sleep 1                       # Home: the demo's records, some waiting, some needing you
    Tap-Text "Sample Electric"          # its sheet, waiting for approval
    Tap-Text "Send now"
    Tap-Text "Send * waiting"           # the rest go too
    Start-Sleep 2
    # No dark theme here: switching it reopens the screen, which starts the demo over.
    # screenrecord only finishes the file cleanly on an interrupt.
    Adb shell pkill -INT screenrecord | Out-Null
    $recorder.WaitForExit(10000) | Out-Null
    Start-Sleep 1
    Pull /sdcard/demo.mp4 $video
}
finally {
    Stop-DemoEmulator
}

# Two passes through one palette keeps the GIF small and its colours true.
$filter = "fps=$Fps,scale=${Width}:-1:flags=lanczos,split[a][b];[a]palettegen=max_colors=128:stats_mode=diff[p];[b][p]paletteuse=dither=bayer:bayer_scale=5:diff_mode=rectangle"
ffmpeg -y -loglevel error -i $video -vf $filter -loop 0 $Out
if ($LASTEXITCODE -ne 0) { throw "ffmpeg failed" }
Remove-Item $video
Write-Host ("Saved $Out ({0:N0} KB)" -f ((Get-Item $Out).Length / 1KB))
