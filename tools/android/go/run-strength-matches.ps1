param(
    [Parameter(Mandatory)][ValidateSet('pilot','holdout-1')][string]$Cohort,
    [Parameter(Mandatory)][int[]]$Slot,
    [Parameter(Mandatory)][string]$AppApk,
    [Parameter(Mandatory)][string]$TestApk,
    [Parameter(Mandatory)][string]$OutputDirectory,
    [string]$AdbPath = 'adb',
    [string]$Serial = 'emulator-5554'
)

$ErrorActionPreference = 'Stop'
$limit = if ($Cohort -eq 'pilot') { 6 } else { 144 }
if ($Slot.Count -eq 0 -or @($Slot | Where-Object { $_ -lt 0 -or $_ -ge $limit }).Count -ne 0 -or
    @($Slot | Sort-Object -Unique).Count -ne $Slot.Count) { throw 'Invalid or repeated slot' }

function Device([string[]]$Arguments) {
    $result = & $AdbPath -s $Serial @Arguments 2>&1
    if ($LASTEXITCODE -ne 0) { throw ($result | Out-String) }
    ($result | Out-String).Trim()
}

function Sha([string]$Path) {
    (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
}

function ReadField([string]$Log, [string]$Key) {
    $matches = [regex]::Matches($Log, "(?m)^INSTRUMENTATION_STATUS: $Key=(\S+)\s*$")
    if ($matches.Count -ne 1) { throw "Expected one $Key entry" }
    $matches[0].Groups[1].Value
}

function KeepReplay([string]$Path, [byte[]]$Bytes, [string]$Hash) {
    if (!(Test-Path -LiteralPath $Path)) { [IO.File]::WriteAllBytes($Path, $Bytes) }
    if ((Sha $Path) -ne $Hash) { throw "Replay changed: $Path" }
}

function ExpectedOpening([int]$Index) {
    $level = if ($Cohort -eq 'pilot') { [int][Math]::Floor($Index / 2) } else { [int][Math]::Floor($Index / 48) }
    $difficulty = @('SIMPLE', 'MEDIUM', 'HARD')[$level]
    $color = if (($Index % 2) -eq 0) { 'BLACK' } else { 'WHITE' }
    $board = if ($Cohort -eq 'pilot') { 9 } else { @(9, 13, 19)[([int][Math]::Floor($Index / 16) % 3)] }
    $seed = if ($Cohort -eq 'pilot') { 39101 + $level } else { 7531701001L + ([int][Math]::Floor($Index / 2) % 24) }
    "$Cohort,$Index,$board,$seed,$difficulty,$color"
}

function CheckResult([string]$LogPath, [int]$Index) {
    $log = Get-Content -LiteralPath $LogPath -Raw
    if ($log -notmatch '(?m)^OK \(1 test\)\s*$' -or $log -notmatch '(?m)^INSTRUMENTATION_CODE: -1\s*$' -or
        $log -match 'FAILURES!!!|shortMsg=|INSTRUMENTATION_FAILED|INSTRUMENTATION_ABORTED') {
        throw "Instrumentation did not complete for slot $Index. Evidence: $LogPath"
    }
    $opening = ReadField $log 'go_strength_start'
    $fields = (ReadField $log 'go_strength_result').Split(',')
    $expected = ExpectedOpening $Index
    if ($opening -notmatch "^$expected,[a-f0-9]{64}$" -or $fields.Count -ne 19 -or
        ($fields[0..6] -join ',') -cne $opening -or $fields[18] -notmatch '^[a-f0-9]{64}$') {
        throw "Slot $Index does not match its schedule"
    }
    $expectedScore = switch ($fields[7]) {
        'FIRST_PLAYER_WIN' { if ($fields[5] -eq 'BLACK') { '1' } else { '0' } }
        'SECOND_PLAYER_WIN' { if ($fields[5] -eq 'WHITE') { '1' } else { '0' } }
        'DRAW' { '0.5' }
        'ONGOING' { '-' }
        default { throw "Slot $Index has an unknown result" }
    }
    if ($fields[8] -ne $expectedScore -or ($fields[9] -eq 'double_pass') -ne ($expectedScore -ne '-') -or
        $fields[9] -notin @('double_pass', 'ply_limit', 'exhausted', 'cancelled', 'busy', 'invalid_move') -or
        [int]$fields[10] -notin 0..(3 * [int]$fields[2] * [int]$fields[2])) { throw "Slot $Index has an invalid result" }
    $record = [Convert]::FromBase64String((ReadField $log 'go_strength_record'))
    $sgf = [Convert]::FromBase64String((ReadField $log 'go_strength_sgf'))
    $hash = [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($record)).ToLowerInvariant()
    if ($hash -ne $fields[18] -or ![Text.Encoding]::UTF8.GetString($sgf).StartsWith('(;')) {
        throw "Slot $Index has invalid replay evidence"
    }
    $sgfHash = [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($sgf)).ToLowerInvariant()
    KeepReplay ([IO.Path]::ChangeExtension($LogPath, '.mocg')) $record $hash
    KeepReplay ([IO.Path]::ChangeExtension($LogPath, '.sgf')) $sgf $sgfHash
    @{
        slot = $Index; row = ($fields -join ','); opening_sha256 = $fields[6]
        log_sha256 = (Sha $LogPath); record_sha256 = $hash
        sgf_sha256 = $sgfHash
    }
}

$output = (New-Item -ItemType Directory -Path $OutputDirectory -Force).FullName
$app = (Resolve-Path -LiteralPath $AppApk).Path
$test = (Resolve-Path -LiteralPath $TestApk).Path
if ((Device @('get-state')) -ne 'device') { throw 'Android device unavailable' }
$abi = Device @('shell', 'getprop', 'ro.product.cpu.abi')
$model = Device @('shell', 'getprop', 'ro.product.model')
$fingerprint = Device @('shell', 'getprop', 'ro.build.fingerprint')
$identity = [ordered]@{ cohort = $Cohort; profile = 'android-pilot-v1'; serial = $Serial; abi = $abi; model = $model
    fingerprint = $fingerprint; app_sha256 = (Sha $app); test_sha256 = (Sha $test) }
$identityPath = Join-Path $output 'run.json'
$json = $identity | ConvertTo-Json
if (Test-Path -LiteralPath $identityPath) {
    if ((Get-Content -LiteralPath $identityPath -Raw).Trim() -cne $json.Trim()) { throw 'Run inputs have changed' }
} else {
    $json | Set-Content -LiteralPath $identityPath -Encoding utf8NoBOM
}

$needsInstall = $true
foreach ($index in $Slot) {
    $name = 'match-{0:D3}' -f $index
    $logPath = Join-Path $output "$name.log"
    $receiptPath = Join-Path $output "$name.json"
    if (Test-Path -LiteralPath $receiptPath) {
        $previous = Get-Content -LiteralPath $receiptPath -Raw | ConvertFrom-Json
        $verified = CheckResult $logPath $index
        if ($verified.log_sha256 -ne $previous.log_sha256 -or $verified.row -cne $previous.row) {
            throw "Recorded slot $index changed"
        }
        $previous.row
        continue
    }
    if (!(Test-Path -LiteralPath $logPath)) {
        if ($needsInstall) {
            if ((Device @('shell', 'dumpsys', 'activity', 'instrumentation')) -match '(?im)^\s*Active instrumentation\b' -or
                (Device @('shell', 'ps', '-A', '-o', 'PID,NAME')) -match '(?m)com\.masterofchessstrategy:.+_qa\s*$') {
                throw 'Device already has an active test'
            }
            foreach ($apk in @($app, $test)) {
                if ((Device @('install', '-r', '-t', $apk)) -notmatch '(?m)^Success\s*$') { throw 'APK install failed' }
            }
            $needsInstall = $false
        }
        Write-Host "Starting $Cohort slot $index. Evidence: $logPath"
        $runner = 'com.masterofchessstrategy.test/androidx.test.runner.AndroidJUnitRunner'
        $testClass = 'com.masterofchessstrategy.games.go.GoSearchStrengthInstrumentedTest#runOneFrozenColorSlot'
        $command = @('shell', 'am', 'instrument', '-w', '-r', '-e', 'class', $testClass,
            '-e', 'go_strength_cohort', $Cohort, '-e', 'go_strength_slot', "$index", $runner)
        & $AdbPath -s $Serial @command *> $logPath
        if ($LASTEXITCODE -ne 0) { throw "Transport exited for slot $index. Evidence: $logPath" }
    }
    $receipt = CheckResult $logPath $index
    $otherName = 'match-{0:D3}.json' -f ($index -bxor 1)
    $otherPath = Join-Path $output $otherName
    if (Test-Path -LiteralPath $otherPath) {
        $other = Get-Content -LiteralPath $otherPath -Raw | ConvertFrom-Json
        if ($receipt.opening_sha256 -ne $other.opening_sha256) { throw "Opening mismatch for slot $index" }
    }
    $receipt | ConvertTo-Json | Set-Content -LiteralPath $receiptPath -Encoding utf8NoBOM
    $receipt.row
}
