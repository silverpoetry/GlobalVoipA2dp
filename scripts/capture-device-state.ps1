param(
    [Parameter(Mandatory = $true)]
    [string]$Serial,
    [string]$Label = 'snapshot'
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$safeLabel = $Label -replace '[^A-Za-z0-9._-]', '_'
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$output = Join-Path $projectRoot "captures\$stamp-$safeLabel"
[IO.Directory]::CreateDirectory($output) | Out-Null

if ((& adb -s $Serial get-state).Trim() -ne 'device') {
    throw "ADB device is not online: $Serial"
}

& adb -s $Serial shell dumpsys audio > (Join-Path $output 'audio.txt')
& adb -s $Serial shell dumpsys media.audio_policy > (Join-Path $output 'audio-policy.txt')
& adb -s $Serial shell dumpsys media.audio_flinger > (Join-Path $output 'audio-flinger.txt')
& adb -s $Serial shell dumpsys bluetooth_manager > (Join-Path $output 'bluetooth.txt')
& adb -s $Serial shell getprop > (Join-Path $output 'getprop.txt')

Write-Output $output
