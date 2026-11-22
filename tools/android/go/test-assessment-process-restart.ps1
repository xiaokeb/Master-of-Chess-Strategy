param(
    [Parameter(Mandatory)][string]$Adb,
    [Parameter(Mandatory)][string]$Device,
    [Parameter(Mandatory)][string]$OutputDirectory,
    [switch]$CustomSetup,
    [switch]$Streak
)

$ErrorActionPreference = 'Stop'
$app = 'com.masterofchessstrategy'
$process = "${app}:go_formal_qa"
$activity = "$app/.ui.GoFormalProcessCheckActivity"
$selector = 'com.masterofchessstrategy.games.go.data.GoFormalProcessCheckpointInstrumentedTest#seed'

function Invoke-Device([string[]]$Arguments) {
    $value = & $Adb -s $Device @Arguments 2>&1
    if ($LASTEXITCODE) { throw ($value | Out-String) }
    ($value | Out-String).Trim()
}

function Get-OwnerPid {
    $value = & $Adb -s $Device shell pidof $process 2>$null
    if ($LASTEXITCODE -eq 1) { return $null }
    if ($LASTEXITCODE -or $value -notmatch '^\d+$') { throw 'Unexpected Go test process identity' }
    [int]$value
}

function Stop-Owner([int]$OwnerPid) {
    if ((Get-OwnerPid) -ne $OwnerPid) { throw 'Go test process identity changed' }
    $null = Invoke-Device @('shell', 'run-as', $app, 'kill', '-9', "$OwnerPid")
    $wait = [Diagnostics.Stopwatch]::StartNew()
    while (Get-OwnerPid) {
        if ($wait.Elapsed.TotalSeconds -gt 10) { throw 'Go test process did not exit' }
        Start-Sleep -Milliseconds 200
    }
}

function Wait-Proof([string]$Path, [int]$DifferentFrom, [scriptblock]$Ready) {
    $wait = [Diagnostics.Stopwatch]::StartNew()
    while ($wait.Elapsed.TotalSeconds -lt 60) {
        $owner = Get-OwnerPid
        if ($owner -and $owner -ne $DifferentFrom) {
            $raw = & $Adb -s $Device shell run-as $app cat $Path 2>$null
            if ($LASTEXITCODE -eq 0) {
                try { $proof = $raw | ConvertFrom-Json -ErrorAction Stop }
                catch { $proof = $null }
                if ($proof.pid -eq $owner -and (& $Ready $proof)) { return $proof }
            }
        }
        Start-Sleep -Milliseconds 500
    }
    throw "No matching Go placement receipt at $Path"
}

if (Get-OwnerPid) { throw 'A Go test process is already active' }
$out = New-Item -ItemType Directory -Path $OutputDirectory
if ($CustomSetup -and $Streak) { throw 'Choose one scenario set' }
$cases = if ($CustomSetup) { @('custom-playing', 'custom-final') }
elseif ($Streak) { @('streak-playing', 'streak-final') }
else { @('assessment-playing', 'assessment-final') }
foreach ($mode in $cases) {
    $token = [guid]::NewGuid().ToString()
    $log = Join-Path $out "$mode-seed.log"
    & $Adb -s $Device shell am instrument -w -r -e class $selector -e go_process_token $token -e go_process_case $mode "$app.test/androidx.test.runner.AndroidJUnitRunner" *> $log
    $code = $LASTEXITCODE
    $fixture = Get-Content -LiteralPath $log -Raw
    $db = "go-formal-process-qa-$token.db"
    if ($code -or $fixture -notmatch '(?m)^OK \(1 test\)\s*$' -or
        $fixture -notmatch 'INSTRUMENTATION_CODE: -1' -or $fixture -match 'FAILURES!!!|shortMsg=') {
        throw "Could not seed $mode"
    }
    # Match UUID.nameUUIDFromBytes in the seeded fixture without relying on runner stdout.
    $hash = [Security.Cryptography.MD5]::HashData([Text.Encoding]::UTF8.GetBytes("${token}:$mode"))
    $hash[6] = ($hash[6] -band 15) -bor 48
    $hash[8] = ($hash[8] -band 63) -bor 128
    $match = [guid]::ParseExact([Convert]::ToHexString($hash), 'N').ToString()
    $proofPath = "files/go-formal-process-qa/go-formal-process-qa-$token.json"
    $launch = @('shell', 'am', 'start', '-W', '-n', $activity, '--es', 'owned_database', $db, '--es', 'match_id', $match)
    $finished = $mode.EndsWith('-final')
    $phase = if ($finished) { 'FINISHED' } else { 'PLAYING' }
    $rounds = if ($finished) { 5 } else { 2 }
    $rating = if ($finished) { 400 } else { 500 }
    $events = if ($finished) { 1 } else { 0 }
    try {
        if ((Invoke-Device $launch) -notmatch '(?m)^Status: ok\s*$') { throw "Could not open $mode" }
        $ready = { param($p) $p.phase -eq $phase -and (!$finished -or $p.completion_stored) }
        $before = Wait-Proof $proofPath 0 $ready
        if ($before.database -cne $db -or $before.match_id -cne $match -or
            $before.checkpoint_sha256 -cnotmatch '^[0-9a-f]{64}$') { throw "Unexpected checkpoint for $mode" }
        if ($CustomSetup) {
            $board = [byte[]]::new(13 * 13)
            $board[0] = 1
            $board[14] = 2
            $board[28] = 1
            $board[168] = 2
            $boardHash = [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($board)).ToLowerInvariant()
            $expectedEvents = if ($finished) { 3 } else { 2 }
            $expectedRecords = if ($finished) { 1 } else { 0 }
            $result = if ($finished) { 'FIRST_PLAYER_WIN' } else { 'ONGOING' }
            if ($before.board_size -ne 13 -or $before.board_sha256 -cne $boardHash -or
                $before.human_player -cne 'WHITE' -or $before.current_player -cne 'WHITE' -or
                $before.opening_sha256 -cnotmatch '^[0-9a-f]{64}$' -or $before.opening_event_count -ne 0 -or
                $before.event_count -ne $expectedEvents -or $before.result -cne $result -or
                $before.archive_count -ne $expectedRecords -or $before.record_count -ne $expectedRecords) {
                throw "Unexpected custom setup state for $mode"
            }
        } elseif ($Streak) {
            $expectedEvents = if ($finished) { 5 } else { 0 }
            $expectedDifficulty = if ($finished) { 'SIMPLE' } else { 'MEDIUM' }
            $expectedNext = if ($finished) { 'MEDIUM' } else { $null }
            $expectedResult = if ($finished) { 'FIRST_PLAYER_WIN' } else { 'ONGOING' }
            if ($before.streak_current -ne 3 -or $before.streak_best -ne 3 -or
                $before.difficulty -cne $expectedDifficulty -or $before.streak_next_difficulty -cne $expectedNext -or
                $before.result -cne $expectedResult -or $before.event_count -ne $expectedEvents -or
                $before.archive_count -ne 3 -or $before.record_count -ne 3) {
                throw "Unexpected streak state for $mode"
            }
        } elseif ($before.assessment_rounds -ne $rounds -or $before.assessment_rating -ne $rating -or
            $before.assessment_finished -ne $finished -or $before.event_count -ne $events -or
            $before.archive_count -ne $rounds) {
            throw "Unexpected placement result for $mode"
        }
        $before | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $out "$mode-before.json")
        Stop-Owner $before.pid
        if ((Invoke-Device $launch) -notmatch '(?m)^Status: ok\s*$') { throw "Could not reopen $mode" }
        $after = Wait-Proof $proofPath $before.pid $ready
        $after | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $out "$mode-after.json")
        foreach ($field in $before.PSObject.Properties.Name) {
            if ($field -ne 'pid' -and $before.$field -cne $after.$field) {
                throw "$mode changed $field after process restart"
            }
        }
        Write-Output "$mode verified: $($before.pid) -> $($after.pid)"
    } finally {
        $owner = Get-OwnerPid
        if ($owner) { Stop-Owner $owner }
    }
}
