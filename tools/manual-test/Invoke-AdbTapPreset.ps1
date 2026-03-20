param(
    [string]$Serial = "30ebcf03",
    [string]$PresetId,
    [switch]$List
)

$adbPath = "D:\adb\adb.exe"
$presetPath = "D:\project\adb\artifacts\adb-tap-presets.json"

if (-not (Test-Path $adbPath)) {
    throw "adb not found: $adbPath"
}

if (-not (Test-Path $presetPath)) {
    throw "preset file not found: $presetPath"
}

$config = Get-Content -Raw $presetPath | ConvertFrom-Json
$presets = @($config.presets)

if ($List -or [string]::IsNullOrWhiteSpace($PresetId)) {
    $presets |
        Select-Object id, package, x, y, notes |
        Format-Table -AutoSize
    return
}

$preset = $presets | Where-Object { $_.id -eq $PresetId } | Select-Object -First 1
if (-not $preset) {
    throw "Unknown preset id: $PresetId"
}

Write-Host ("Tapping preset {0} at ({1}, {2}) on device {3}" -f $preset.id, $preset.x, $preset.y, $Serial)
& $adbPath -s $Serial shell input tap $preset.x $preset.y
