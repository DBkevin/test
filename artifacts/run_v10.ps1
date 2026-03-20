$ErrorActionPreference = "Stop"
$device = "30ebcf03"
$artifactDir = "D:\project\adb\artifacts"
New-Item -ItemType Directory -Force -Path $artifactDir | Out-Null

function Get-StartButtonCenter {
    for ($i = 0; $i -lt 6; $i++) {
        adb -s $device shell uiautomator dump /sdcard/codex_trigger.xml | Out-Null
        $xml = adb -s $device shell cat /sdcard/codex_trigger.xml
        if ($xml -match 'resource-id="com\.example\.a11yframework:id/startDouyinCaptureButton"') {
            if ($xml -match 'resource-id="com\.example\.a11yframework:id/startDouyinCaptureButton"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"') {
                $x1 = [int]$matches[1]
                $y1 = [int]$matches[2]
                $x2 = [int]$matches[3]
                $y2 = [int]$matches[4]
                return @{ x = [int](($x1 + $x2) / 2); y = [int](($y1 + $y2) / 2) }
            }
        }
        Start-Sleep -Milliseconds 600
    }
    return $null
}

function Invoke-Round {
    param([string]$RoundName)

    $logPath = Join-Path $artifactDir ("{0}.log" -f $RoundName)
    $finishPath = Join-Path $artifactDir ("{0}_finished.txt" -f $RoundName)
    $beforeXml = Join-Path $artifactDir ("{0}_before.xml" -f $RoundName)
    $afterXml = Join-Path $artifactDir ("{0}_after.xml" -f $RoundName)

    if (Test-Path $logPath) { Remove-Item $logPath -Force }
    if (Test-Path $finishPath) { Remove-Item $finishPath -Force }

    adb -s $device logcat -c
    $proc = Start-Process adb -ArgumentList "-s $device logcat -v time" -RedirectStandardOutput $logPath -PassThru -WindowStyle Hidden

    try {
        adb -s $device shell am start -n com.example.a11yframework/.MainActivity | Out-Null
        Start-Sleep -Seconds 2

        adb -s $device shell uiautomator dump /sdcard/${RoundName}_before.xml | Out-Null
        adb -s $device pull /sdcard/${RoundName}_before.xml $beforeXml | Out-Null

        $btn = Get-StartButtonCenter
        if (-not $btn) {
            throw "start button not found in $RoundName"
        }

        adb -s $device shell input tap $($btn.x) $($btn.y) | Out-Null

        $saved = $false
        $savedDeadline = (Get-Date).AddSeconds(25)
        while ((Get-Date) -lt $savedDeadline) {
            if ((Test-Path $logPath) -and (Select-String -Path $logPath -Pattern "Pending local capture saved" -Quiet)) {
                $saved = $true
                break
            }
            Start-Sleep -Milliseconds 700
        }

        adb -s $device shell am force-stop com.ss.android.ugc.aweme | Out-Null
        Start-Sleep -Milliseconds 900
        adb -s $device shell monkey -p com.ss.android.ugc.aweme -c android.intent.category.LAUNCHER 1 | Out-Null

        $finished = $false
        $finishDeadline = (Get-Date).AddMinutes(4)
        while ((Get-Date) -lt $finishDeadline) {
            if ((Test-Path $logPath) -and (Select-String -Path $logPath -Pattern "Pending local capture cleared" -Quiet)) {
                $finished = $true
                break
            }
            Start-Sleep -Seconds 2
        }

        adb -s $device shell uiautomator dump /sdcard/${RoundName}_after.xml | Out-Null
        adb -s $device pull /sdcard/${RoundName}_after.xml $afterXml | Out-Null

        "saved=$saved finished=$finished" | Set-Content -Encoding UTF8 $finishPath
        return @{ saved = $saved; finished = $finished; log = $logPath }
    }
    finally {
        if ($proc -and -not $proc.HasExited) {
            $proc.Kill() | Out-Null
        }
        Start-Sleep -Milliseconds 400
    }
}

$r1 = Invoke-Round -RoundName "v10_round1"
Start-Sleep -Seconds 3
$r2 = Invoke-Round -RoundName "v10_round2"

Write-Output ("ROUND1 saved={0} finished={1} log={2}" -f $r1.saved, $r1.finished, $r1.log)
Write-Output ("ROUND2 saved={0} finished={1} log={2}" -f $r2.saved, $r2.finished, $r2.log)


