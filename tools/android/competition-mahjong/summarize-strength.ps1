param(
    [Parameter(Mandatory)][string]$Path,
    [Parameter(Mandatory)]
    [ValidateSet('diagnostic-v4', 'held-out-v5', 'held-out-medium-bucket-8',
        'held-out-wmo-calibrated-v1', 'held-out-wmo-kongs-v1', 'held-out-wmo-kongs-v2',
        'training-medium-control', 'training-medium-bucket-8', 'training-medium-bucket-4')]
    [string]$Profile
)

$ErrorActionPreference = 'Stop'
$heldOut = $Profile -like 'held-out-*'
$first, $seeds = switch -Wildcard ($Profile) {
    'held-out-v5' { 4285151001L, 24 }
    'held-out-medium-bucket-8' { 5920152401L, 24 }
    'held-out-wmo-calibrated-v1' { 9226102001L, 24 }
    'held-out-wmo-kongs-v1' { 9261122001L, 24 }
    'held-out-wmo-kongs-v2' { 9261123001L, 24 }
    'training-medium-*' { 71551001L, 8 }
    default { 71425001L, 8 }
}
$expectedHeader = "lower`tseed`trotation`tp0`tp1`tp2`tp3`tdraws`tevents`telapsed_ms"
if ((Get-Content -LiteralPath $Path -TotalCount 1) -cne $expectedHeader) { throw 'Unexpected TSV header' }
$rows = @(Import-Csv -LiteralPath $Path -Delimiter "`t")
if ($rows.Count -ne 12 * $seeds) { throw "Incomplete result set: $($rows.Count) rows" }

$series = @{}
foreach ($row in $rows) {
    foreach ($field in $row.PSObject.Properties) {
        if ($field.Value -notmatch '^-?\d+$') { throw "Invalid numeric field: $($field.Name)" }
    }
    $key = "$($row.lower)/$($row.seed)/$($row.rotation)"
    if ($series.ContainsKey($key)) { throw "Duplicate series: $key" }
    $series[$key] = $row
}

$summary = @(foreach ($lower in 0..2) {
    $wins = $ties = $losses = $draws = 0
    $seedMeans = @(for ($seed = $first; $seed -lt $first + $seeds; $seed++) {
        $points = 0.0
        foreach ($rotation in 0..3) {
            $key = "$lower/$seed/$rotation"
            $row = $series[$key]
            if (!$row) { throw "Missing series: $key" }
            $score = @([int]$row.p0, [int]$row.p1, [int]$row.p2, [int]$row.p3)
            if (($score | Measure-Object -Sum).Sum -ne 0 -or [int]$row.draws -notin (0..16) -or
                [int]$row.events -lt 16 -or [int]$row.events -gt 65536 -or [long]$row.elapsed_ms -lt 0) {
                throw "Invalid series totals: $key"
            }
            $draws += [int]$row.draws
            foreach ($offsetHigh in 0..1) {
                foreach ($offsetLow in 2..3) {
                    $high = $score[($rotation + $offsetHigh) % 4]
                    $low = $score[($rotation + $offsetLow) % 4]
                    if ($high -gt $low) { $wins++; $points += 1.0 }
                    elseif ($high -eq $low) { $ties++; $points += 0.5 }
                    else { $losses++ }
                }
            }
        }
        $points / 16.0
    })
    $mean = ($seedMeans | Measure-Object -Average).Average
    $result = [ordered]@{
        lower = $lower
        series = 4 * $seeds
        hands = 64 * $seeds
        wins = $wins
        ties = $ties
        losses = $losses
        draws = $draws
        mean = $mean
    }
    if ($heldOut) {
        # Pairings sharing a root seed are averaged before computing the 23-df interval.
        $sumOfSquares = ($seedMeans | ForEach-Object { [Math]::Pow($_ - $mean, 2) } | Measure-Object -Sum).Sum
        $margin = 2.06865761 * [Math]::Sqrt($sumOfSquares / (($seeds - 1) * $seeds))
        $result.interval_lower = $mean - $margin
        $result.interval_upper = $mean + $margin
        $result.meets_protocol = $mean -ge 0.6 -and $mean -le 0.8 -and $result.interval_lower -gt 0.5
    }
    $result
})

$summary | ConvertTo-Json -Depth 4
