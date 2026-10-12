param(
    [Parameter(Mandatory = $true)][string]$AdbPath,
    [ValidatePattern('^emulator-\d+$')][string]$DeviceSerial = 'emulator-5554',
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^com\.masterofchessstrategy\.[A-Za-z_][A-Za-z0-9_]*(\.[A-Za-z_][A-Za-z0-9_]*)+$')]
    [string]$TestClass,
    [ValidatePattern('^[A-Za-z_][A-Za-z0-9_]*$')][string]$TestMethod,
    [Parameter(Mandatory = $true)][ValidateRange(1, 10000)][int]$ExpectedTestCount
)

# Serial, single-class App tests. Never clears data or uninstalls either APK.
# The selected test itself must be reviewed for its own persistence effects.
$ErrorActionPreference = 'Stop'
if ($TestMethod -and $ExpectedTestCount -ne 1) {
    throw 'A single-method selection must expect exactly one test.'
}
$testSelector = if ($TestMethod) { "$TestClass#$TestMethod" } else { $TestClass }
$adbExecutable = (Resolve-Path -LiteralPath $AdbPath).Path
$projectRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '../..')).Path
$runner = 'com.masterofchessstrategy.test/androidx.test.runner.AndroidJUnitRunner'
$runId = [guid]::NewGuid().ToString('N')
$outputDirectory = Join-Path $projectRoot ".build/instrumented-class/$runId"
$apkPaths = @(
    'app/build/outputs/apk/debug/app-debug.apk',
    'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk'
) | ForEach-Object { (Resolve-Path -LiteralPath (Join-Path $projectRoot $_)).Path }

function Invoke-Device {
    param([string[]]$Arguments)
    $result = & $adbExecutable -s $DeviceSerial @Arguments 2>&1
    $code = $LASTEXITCODE
    $message = ($result | Out-String).Trim()
    if ($code -ne 0) { throw "ADB failed ($code): $message" }
    return $message
}

if ((Invoke-Device -Arguments @('shell', 'getprop', 'ro.kernel.qemu')) -ne '1') {
    throw 'Only an existing emulator is supported.'
}
foreach ($apkPath in $apkPaths) {
    $installed = Invoke-Device -Arguments @('install', '-r', '-t', $apkPath)
    if ($installed -notmatch '(?m)^Success\s*$') { throw "APK install did not succeed: $installed" }
}
$null = New-Item -ItemType Directory -Path $outputDirectory
$logPath = Join-Path $outputDirectory 'instrumentation.log'
$errorPath = Join-Path $outputDirectory 'instrumentation.stderr.log'
$instrumentArguments = @('-s', $DeviceSerial, 'shell', 'am', 'instrument', '-w', '-r',
    '-e', 'class', $testSelector, $runner)
Write-Output "Running $testSelector; evidence: $outputDirectory"
$process = Start-Process -FilePath $adbExecutable -ArgumentList $instrumentArguments -WindowStyle Hidden `
    -RedirectStandardOutput $logPath -RedirectStandardError $errorPath -PassThru
$process.WaitForExit()
if ($process.ExitCode -ne 0) { throw "Instrumentation transport failed; inspect $outputDirectory" }
$log = Get-Content -LiteralPath $logPath -Raw
Write-Output $log
# am instrument can return exit code 0 for JUnit failures; require its receipt.
if ($log -match 'FAILURES!!!|INSTRUMENTATION_FAILED|INSTRUMENTATION_ABORTED|shortMsg=|INSTRUMENTATION_STATUS_CODE: -[1-9]\d*') {
    throw "Instrumentation did not pass; inspect $outputDirectory"
}
$receipt = [regex]::Match($log, '(?m)^OK \((\d+) tests?\)\s*$')
if (-not $receipt.Success -or [int]$receipt.Groups[1].Value -ne $ExpectedTestCount) {
    throw "Missing or unexpected test count; expected $ExpectedTestCount. Inspect $outputDirectory"
}
Write-Output "Verified $ExpectedTestCount/$ExpectedTestCount; APKs remain installed."
