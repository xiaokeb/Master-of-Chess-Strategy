param(
    [Parameter(Mandatory)][string]$Adb,
    [Parameter(Mandatory)][string]$Device,
    [Parameter(Mandatory)][string]$OutputDirectory,
    [switch]$PerMove
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
    throw "No matching Go clock receipt at $Path"
}

if (Get-OwnerPid) { throw 'A Go test process is already active' }
$out = New-Item -ItemType Directory -Path $OutputDirectory
$cases = if ($PerMove) { @('per-move-human') } else { @('timed-human', 'timed-auto') }
$limit = if ($PerMove) { 30000L } else { 300000L }
$clockMode = if ($PerMove) { 'PER_MOVE' } else { 'MAIN' }
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
    $hash = [Security.Cryptography.MD5]::HashData([Text.Encoding]::UTF8.GetBytes("${token}:$mode"))
    $hash[6] = ($hash[6] -band 15) -bor 48
    $hash[8] = ($hash[8] -band 63) -bor 128
    $match = [guid]::ParseExact([Convert]::ToHexString($hash), 'N').ToString()
    $proofPath = "files/go-formal-process-qa/go-formal-process-qa-$token.json"
    $launch = @('shell', 'am', 'start', '-W', '-n', $activity, '--es', 'owned_database', $db, '--es', 'match_id', $match)
    try {
        if ((Invoke-Device $launch) -notmatch '(?m)^Status: ok\s*$') { throw "Could not open $mode" }
        $before = Wait-Proof $proofPath 0 { param($p) $p.phase -eq 'PLAYING' }
        $before | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $out "$mode-before.json")
        if ($before.database -ne $db -or $before.match_id -ne $match -or
            $before.automatic -ne ($mode -eq 'timed-auto') -or $before.clock_active -ne 'BLACK' -or
            $before.clock_mode -ne $clockMode -or $before.clock_limit -ne $limit -or $before.black_millis -ne $limit -or
            $before.event_count -ne 0 -or $before.archive_count -ne 0) { throw "Unexpected initial clock for $mode" }
        Stop-Owner $before.pid
        $deadline = [long]$before.clock_started_at + [long]$before.black_millis
        $until = [Diagnostics.Stopwatch]::StartNew()
        do {
            if (Get-OwnerPid) { throw 'Go test restarted before the deadline' }
            $clock = [long](Invoke-Device @('shell', 'date', '+%s')) * 1000L
            if ($until.Elapsed.TotalSeconds -gt 60) { throw 'Device clock did not reach deadline' }
            if ($clock -lt $deadline) { Start-Sleep -Seconds 1 }
        } while ($clock -lt $deadline)
        if ((Invoke-Device $launch) -notmatch '(?m)^Status: ok\s*$') { throw "Could not reopen $mode" }
        $after = Wait-Proof $proofPath $before.pid { param($p) $p.phase -eq 'FINISHED' -and $p.completion_stored }
        $after | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $out "$mode-after.json")
        if ($after.database -ne $db -or $after.match_id -ne $match -or $after.automatic -ne $before.automatic -or
            $after.timed_out_by -ne 'BLACK' -or $after.result -ne 'SECOND_PLAYER_WIN' -or $after.clock_active -or
            $after.clock_mode -ne $clockMode -or $after.clock_limit -ne $limit -or
            $after.black_millis -ne 0 -or $after.white_millis -ne $limit -or
            $after.event_count -ne 1 -or $after.archive_count -ne 1) { throw "Unexpected restored result for $mode" }
        Stop-Owner $after.pid
        if ((Invoke-Device $launch) -notmatch '(?m)^Status: ok\s*$') { throw "Could not reopen result for $mode" }
        $again = Wait-Proof $proofPath $after.pid { param($p) $p.phase -eq 'FINISHED' -and $p.completion_stored }
        $again | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $out "$mode-final.json")
        foreach ($field in $after.PSObject.Properties.Name) {
            if ($field -ne 'pid' -and $after.$field -cne $again.$field) { throw "$mode changed $field after terminal restart" }
        }
        Write-Output "$mode verified: $($before.pid) -> $($after.pid) -> $($again.pid)"
    } finally {
        $owner = Get-OwnerPid
        if ($owner) { Stop-Owner $owner }
    }
}
