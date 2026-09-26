param(
    [Parameter(Mandatory = $true)][string]$AdbPath,
    [string]$DeviceSerial = 'emulator-5554',
    [switch]$AllowEmulatorDataReset
)

# Explicit opt-in: only this app's disposable emulator data is cleared, never a physical device.
$ErrorActionPreference = 'Stop'
if (-not $AllowEmulatorDataReset) { throw 'Pass -AllowEmulatorDataReset to erase test app data.' }
if ($DeviceSerial -notmatch '^emulator-\d+$') { throw 'Only emulators are supported.' }
$adbExecutable = (Resolve-Path -LiteralPath $AdbPath).Path
$projectRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '../..')).Path
$packageName = 'com.masterofchessstrategy'
$runId = [guid]::NewGuid().ToString('N')
$outputDirectory = Join-Path $projectRoot ".build/continuous-play/$runId"
$null = New-Item -ItemType Directory -Path $outputDirectory -Force

function Invoke-Device {
    param([string[]]$Arguments, [switch]$AllowFailure)
    $result = & $adbExecutable -s $DeviceSerial @Arguments 2>&1
    $code = $LASTEXITCODE
    $message = ($result | Out-String).Trim()
    if ($code -ne 0 -and -not $AllowFailure) { throw "ADB failed ($code): $message" }
    return [pscustomobject]@{ Code = $code; Text = $message }
}

if ((Invoke-Device -Arguments @('shell', 'getprop', 'ro.kernel.qemu')).Text -ne '1') { throw 'Target is not an emulator.' }
foreach ($apk in @('app/build/outputs/apk/debug/app-debug.apk', 'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk')) {
    $apkPath = (Resolve-Path -LiteralPath (Join-Path $projectRoot $apk)).Path
    $null = Invoke-Device -Arguments @('install', '-r', '-t', $apkPath)
}
$handle = $null
try {
    $null = Invoke-Device -Arguments @('shell', 'am', 'force-stop', $packageName)
    if ((Invoke-Device -Arguments @('shell', 'pm', 'clear', $packageName)).Text -ne 'Success') { throw 'App reset failed.' }
    $null = Invoke-Device -Arguments @('shell', 'input', 'keyevent', '224')
    $null = Invoke-Device -Arguments @('shell', 'wm', 'dismiss-keyguard')
    $logPath = Join-Path $outputDirectory 'instrumentation.log'
    $errorPath = Join-Path $outputDirectory 'instrumentation.stderr.log'
    $instrumentArguments = @('-s', $DeviceSerial, 'shell', 'am', 'instrument', '-w', '-r',
        '-e', 'class', 'com.masterofchessstrategy.ui.ChineseChessContinuousPlayTest',
        '-e', 'continuousHarness', 'true', '-e', 'continuousRunId', $runId,
        'com.masterofchessstrategy.test/androidx.test.runner.AndroidJUnitRunner')
    $handle = Start-Process -FilePath $adbExecutable -ArgumentList $instrumentArguments -WindowStyle Hidden `
        -RedirectStandardOutput $logPath -RedirectStandardError $errorPath -PassThru
    Write-Output "Continuous play started: $runId; logs: $outputDirectory"
    $deadline = [DateTime]::UtcNow.AddMinutes(25)
    while (-not $handle.WaitForExit(10000)) {
        if ([DateTime]::UtcNow -gt $deadline) { throw 'Safety timeout; the run is failed, not a completed game.' }
    }
    $read = Invoke-Device -Arguments @('shell', 'run-as', $packageName, 'cat', 'files/continuous-play.json')
    $read.Text | Set-Content -LiteralPath (Join-Path $outputDirectory 'evidence.json') -Encoding utf8
    $evidence = $read.Text | ConvertFrom-Json
    $log = Get-Content -LiteralPath $logPath -Raw
    if ($handle.ExitCode -ne 0 -or $log -notmatch 'OK \(1 test\)' -or
        $log -match 'FAILURES|INSTRUMENTATION_FAILED' -or $evidence.runId -ne $runId -or $evidence.status -ne 'passed') {
        throw "Continuous play failed. $log"
    }
    Write-Output "Passed: $($evidence.records.Count) games; $($evidence.backgroundMoves) screen-off moves; $($evidence.elapsedMillis) ms."
} finally {
    # Preserve failed state/logs for diagnosis. No automatic retry or new game on a transport failure.
    $null = Invoke-Device -Arguments @('shell', 'input', 'keyevent', '224') -AllowFailure
    $null = Invoke-Device -Arguments @('shell', 'wm', 'dismiss-keyguard') -AllowFailure
    $null = Invoke-Device -Arguments @('shell', 'am', 'force-stop', $packageName) -AllowFailure
    if ($null -ne $handle -and -not $handle.HasExited) { $null = $handle.WaitForExit(10000) }
}
Write-Output "Evidence: $outputDirectory"
