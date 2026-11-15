param(
    [Parameter(Mandatory)][string]$RawDirectory,
    [Parameter(Mandatory)][string]$ReviewedDirectory,
    [Parameter(Mandatory)][string]$AppApk,
    [Parameter(Mandatory)][string]$TestApk
)

$ErrorActionPreference = 'Stop'
$reviewed = (Resolve-Path -LiteralPath $ReviewedDirectory).Path
$header = "slot`traw_sha256`treviewed_sha256`treview_log_sha256"
$ledger = Join-Path $reviewed 'adjudication.tsv'
if ((Get-Content -LiteralPath $ledger -TotalCount 1) -cne $header) { throw 'Invalid adjudication header' }
$reviews = @(Import-Csv -LiteralPath $ledger -Delimiter "`t")
if ($reviews.Count -ne 144) { throw "Incomplete adjudication: $($reviews.Count) of 144 slots" }

$samples = @(& "$PSScriptRoot/run-strength-matches.ps1" -Cohort holdout-1 -Slot (0..143) -VerifyExisting `
    -AppApk $AppApk -TestApk $TestApk -OutputDirectory $RawDirectory)
if ($samples.Count -ne 144) { throw 'Independent match receipts are incomplete' }

function Sha([string]$Path) {
    (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
}

function ReviewedResult([string]$Row, [string]$Label, [int]$Size) {
    if ($Row -cnotmatch "^$Label,([123]),([0-9]+),([0-9]+),15,([0-9]+)$") {
        throw "Invalid $Label result"
    }
    $result = [int]$Matches[1]
    $black = [int]$Matches[2]
    $white = [int]$Matches[3]
    $dead = [int]$Matches[4]
    $limit = $Size * $Size
    $delta = $black - $white - 30 # A quarter stone is half a full-area point.
    $expected = if ($delta -gt 0) { 1 } elseif ($delta -lt 0) { 2 } else { 3 }
    if ($result -ne $expected -or $black % 4 -ne 0 -or $white % 4 -ne 0 -or
        $black + $white -gt 4 * $limit -or $dead -gt $limit -or ($Label -eq 'raw' -and $dead -ne 0)) {
        throw "Invalid $Label area or result"
    }
    $result
}

$scores = @{}
foreach ($review in $reviews) {
    if ($review.slot -cnotmatch '^(0|[1-9][0-9]{0,2})$') { throw 'Invalid adjudicated slot' }
    $slot = [int]$review.slot
    if ($slot -gt 143 -or $scores.ContainsKey($slot)) { throw "Unexpected or repeated slot $slot" }
    $sample = $samples[$slot].Split(',')
    if ($sample[9] -cne 'double_pass' -or $sample[18] -cne $review.raw_sha256) {
        throw "Original result is incomplete or changed for slot $slot"
    }
    $name = 'match-{0:D3}' -f $slot
    $log = Join-Path $reviewed "$name.log"
    $record = Join-Path $reviewed "$name.mocg"
    if ((Sha $log) -cne $review.review_log_sha256 -or (Sha $record) -cne $review.reviewed_sha256) {
        throw "Adjudication evidence changed for slot $slot"
    }
    $lines = @(Get-Content -LiteralPath $log)
    if ($lines.Count -ne 2) { throw "Missing reviewed agreement for slot $slot" }
    $raw = ReviewedResult $lines[0] 'raw' ([int]$sample[2])
    $rawAreas = $lines[0].Split(',')
    if ($sample[7] -cne @('ONGOING', 'FIRST_PLAYER_WIN', 'SECOND_PLAYER_WIN', 'DRAW')[$raw] -or
        $rawAreas[2] -cne $sample[11] -or $rawAreas[3] -cne $sample[12]) {
        throw "Adjudication started from a different result in slot $slot"
    }
    $winner = ReviewedResult $lines[1] 'reviewed' ([int]$sample[2])
    $scoredColor = if ($sample[5] -ceq 'BLACK') { 1 } else { 2 }
    $scores[$slot] = if ($winner -eq 3) { 0.5 } elseif ($winner -eq $scoredColor) { 1.0 } else { 0.0 }
}

foreach ($level in 0..2) {
    $start = 48 * $level
    $pairs = @(foreach ($seed in 0..23) {
        $even = $start + 2 * $seed
        ($scores[$even] + $scores[$even + 1]) / 2.0
    })
    $mean = ($pairs | Measure-Object -Average).Average
    $residuals = ($pairs | ForEach-Object { [Math]::Pow($_ - $mean, 2) } | Measure-Object -Sum).Sum
    $margin = 2.06865761 * [Math]::Sqrt($residuals / (23 * 24))
    [pscustomobject]@{
        lower = @('SIMPLE', 'MEDIUM', 'HARD')[$level]
        paired_openings = 24
        matches = 48
        mean = $mean
        interval_lower = $mean - $margin
        interval_upper = $mean + $margin
        meets_protocol = $mean -ge 0.6 -and $mean -le 0.8 -and $mean - $margin -gt 0.5
    }
}
