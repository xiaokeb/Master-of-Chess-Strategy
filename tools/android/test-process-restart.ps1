param(
    [Parameter(Mandatory = $true)][string]$AdbPath,
    [string]$DeviceSerial = 'emulator-5554',
    [ValidateSet('auto', 'human', 'timed')][string[]]$Scenarios = @('auto', 'human', 'timed'),
    [switch]$AllowEmulatorDataReset
)

# Deliberately destructive only to this app's data on the selected test emulator.
# No Gradle connected-test task may run concurrently: its uninstall would erase checkpoints.
$ErrorActionPreference = 'Stop'
if (-not $AllowEmulatorDataReset) { throw 'Pass -AllowEmulatorDataReset to erase this app on the test emulator.' }
if ($DeviceSerial -notmatch '^emulator-\d+$') { throw 'Physical devices are not supported by this harness.' }
$adbExecutable = (Resolve-Path -LiteralPath $AdbPath).Path
$projectRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '../..')).Path
$packageName = 'com.masterofchessstrategy'
$testClass = 'com.masterofchessstrategy.ui.ChineseChessProcessRestartTest'
$runner = 'com.masterofchessstrategy.test/androidx.test.runner.AndroidJUnitRunner'
$runId = [guid]::NewGuid().ToString('N')
$outputDirectory = Join-Path $projectRoot ".build/process-restart/$runId"
$null = New-Item -ItemType Directory -Path $outputDirectory -Force

function Invoke-Device {
    param([string[]]$Arguments, [switch]$AllowFailure)
    $result = & $adbExecutable -s $DeviceSerial @Arguments 2>&1
    $code = $LASTEXITCODE
    $message = ($result | Out-String).Trim()
    if ($code -ne 0 -and -not $AllowFailure) { throw "ADB failed ($code): $message" }
    return [pscustomobject]@{ Code = $code; Text = $message }
}

function Start-Phase {
    param([string]$Scenario, [string]$Phase)
    $logPath = Join-Path $outputDirectory "$Scenario-$Phase.log"
    $errorPath = Join-Path $outputDirectory "$Scenario-$Phase.stderr.log"
    $instrumentArguments = @('-s', $DeviceSerial, 'shell', 'am', 'instrument', '-w', '-r',
        '-e', 'class', $testClass, '-e', 'restartHarness', 'true',
        '-e', 'restartRunId', $runId, '-e', 'restartScenario', $Scenario,
        '-e', 'restartPhase', $Phase, $runner)
    $process = Start-Process -FilePath $adbExecutable -ArgumentList $instrumentArguments -WindowStyle Hidden `
        -RedirectStandardOutput $logPath -RedirectStandardError $errorPath -PassThru
    return [pscustomobject]@{ Process = $process; Log = $logPath; ErrorLog = $errorPath }
}

function Wait-Receipt {
    param($Handle, [string]$Scenario, [string]$Phase)
    $deadline = [DateTime]::UtcNow.AddSeconds(75)
    do {
        # shell-v2 propagates cat's exit status; exec-out can report success for a missing file.
        $read = Invoke-Device -Arguments @('shell', 'run-as', $packageName, 'cat', "files/restart-$Phase.json") -AllowFailure
        if ($read.Code -eq 0) {
            $receipt = $read.Text | ConvertFrom-Json
            if ($receipt.runId -eq $runId -and $receipt.scenario -eq $Scenario -and $receipt.phase -eq $Phase) {
                return $receipt
            }
        }
        if ($Handle.Process.HasExited) {
            throw "Instrumentation ended before $Scenario/$Phase receipt. $(Get-Content -LiteralPath $Handle.Log -Raw) $(Get-Content -LiteralPath $Handle.ErrorLog -Raw)"
        }
        Start-Sleep -Milliseconds 250
    } while ([DateTime]::UtcNow -lt $deadline)
    throw "No checkpoint for $Scenario/$Phase; inspect $($Handle.Log)"
}

function Stop-PreparedProcess {
    param($Handle, $Receipt, [ValidateSet('sigkill', 'force-stop')][string]$Method, [int]$AbsentSeconds)
    $livePid = (Invoke-Device -Arguments @('shell', 'pidof', $packageName)).Text
    if ($livePid -ne [string]$Receipt.pid -or $livePid -notmatch '^\d+$') { throw "Unexpected target PID: $livePid" }
    # Move away first so the system is not asked to redisplay a foreground Activity after SIGKILL.
    $null = Invoke-Device -Arguments @('shell', 'input', 'keyevent', '3')
    if ($Method -eq 'sigkill') {
        $null = Invoke-Device -Arguments @('shell', 'run-as', $packageName, 'kill', '-9', $livePid)
    } else {
        $null = Invoke-Device -Arguments @('shell', 'am', 'force-stop', $packageName)
    }
    if (-not $Handle.Process.WaitForExit(10000)) { throw 'Killed instrumentation did not terminate.' }
    $deadline = [DateTime]::UtcNow.AddSeconds($AbsentSeconds)
    do {
        $check = Invoke-Device -Arguments @('shell', 'pidof', $packageName) -AllowFailure
        if ($check.Code -ne 1 -or $check.Text) { throw "App process unexpectedly survived/restarted: $($check.Text)" }
        Start-Sleep -Milliseconds 250
    } while ([DateTime]::UtcNow -lt $deadline)
}

if ((Invoke-Device -Arguments @('shell', 'getprop', 'ro.kernel.qemu')).Text -ne '1') { throw 'Target is not an emulator.' }
foreach ($apk in @('app/build/outputs/apk/debug/app-debug.apk', 'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk')) {
    $apkPath = (Resolve-Path -LiteralPath (Join-Path $projectRoot $apk)).Path
    $null = Invoke-Device -Arguments @('install', '-r', '-t', $apkPath)
}
$evidence = @()
$activeHandle = $null
try {
    foreach ($scenario in $Scenarios) {
        Write-Output "Preparing $scenario (emulator app data will be reset)."
        $null = Invoke-Device -Arguments @('shell', 'am', 'force-stop', $packageName)
        $cleared = Invoke-Device -Arguments @('shell', 'pm', 'clear', $packageName)
        if ($cleared.Text -ne 'Success') { throw "App reset failed: $($cleared.Text)" }
        $null = Invoke-Device -Arguments @('shell', 'input', 'keyevent', '224')
        $null = Invoke-Device -Arguments @('shell', 'wm', 'dismiss-keyguard')
        $activeHandle = Start-Phase $scenario 'prepare'
        $prepared = Wait-Receipt $activeHandle $scenario 'prepare'
        $method = if ($scenario -eq 'timed') { 'force-stop' } else { 'sigkill' }
        $absence = if ($scenario -eq 'timed') { 12 } else { 3 }
        Write-Output "$scenario prepared, PID=$($prepared.pid); terminating via $method."
        Stop-PreparedProcess $activeHandle $prepared $method $absence
        $activeHandle = Start-Phase $scenario 'resume'
        $resumed = Wait-Receipt $activeHandle $scenario 'resume'
        Write-Output "$scenario resumed in PID=$($resumed.pid); checking a second cold start."
        Stop-PreparedProcess $activeHandle $resumed 'force-stop' 3
        $activeHandle = Start-Phase $scenario 'verify'
        $verified = Wait-Receipt $activeHandle $scenario 'verify'
        if (-not $activeHandle.Process.WaitForExit(30000)) { throw "Final $scenario verification did not finish." }
        $finalLog = Get-Content -LiteralPath $activeHandle.Log -Raw
        if ($activeHandle.Process.ExitCode -ne 0 -or $finalLog -notmatch 'OK \(1 test\)' -or
            $finalLog -match 'FAILURES|INSTRUMENTATION_FAILED') { throw "Final verification failed: $finalLog" }
        $evidence += [pscustomobject]@{
            scenario = $scenario; firstTermination = $method; absenceSeconds = $absence
            prepare = $prepared; resume = $resumed; verify = $verified; passed = $true
        }
        $evidence | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $outputDirectory 'evidence.json') -Encoding utf8
        Write-Output "$scenario passed with three distinct application processes."
    }
} finally {
    # Only the target test app is stopped; failed logs and saved data remain available for diagnosis.
    $null = Invoke-Device -Arguments @('shell', 'am', 'force-stop', $packageName) -AllowFailure
    if ($null -ne $activeHandle -and -not $activeHandle.Process.HasExited) {
        $null = $activeHandle.Process.WaitForExit(10000)
    }
}
Write-Output "Evidence: $outputDirectory"
