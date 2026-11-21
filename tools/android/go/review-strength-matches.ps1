param(
    [Parameter(Mandatory)][string]$Decisions,
    [Parameter(Mandatory)][string]$Reviewer,
    [Parameter(Mandatory)][string]$RawDirectory,
    [Parameter(Mandatory)][string]$ReviewedDirectory,
    [Parameter(Mandatory)][string]$AppApk,
    [Parameter(Mandatory)][string]$TestApk,
    [string]$AdbPath = 'adb',
    [string]$Serial = 'emulator-5554'
)

$ErrorActionPreference = 'Stop'
if ((Get-Content -LiteralPath $Decisions -TotalCount 1) -cne "slot`tdead_groups") {
    throw 'Invalid review decision header'
}
$choices = @{}
foreach ($line in (Import-Csv -LiteralPath $Decisions -Delimiter "`t")) {
    if ($line.slot -cnotmatch '^(0|[1-9][0-9]{0,2})$' -or
        $line.dead_groups -cnotmatch '^(?:-|[0-9]{1,2},[0-9]{1,2}(?:;[0-9]{1,2},[0-9]{1,2})*)$') {
        throw 'Invalid review decision row'
    }
    $slot = [int]$line.slot
    if ($slot -gt 143 -or $choices.ContainsKey($slot)) { throw "Unexpected or repeated review slot $slot" }
    $choices[$slot] = $line.dead_groups
}
if (!$choices.Count) { throw 'No confirmed decisions' }
$slots = @($choices.Keys | Sort-Object)

function Device([string[]]$Arguments) {
    $result = & $AdbPath -s $Serial @Arguments 2>&1
    if ($LASTEXITCODE) { throw ($result | Out-String) }
    ($result | Out-String).Trim()
}

function Sha([string]$Path) {
    (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
}

function DeviceSha([string]$Path) { (Device @('shell', 'sha256sum', $Path)).Split(' ')[0] }

function PublishEvidence([string]$StagedFile, [string]$Target) {
    if (Test-Path -LiteralPath $Target) {
        if ((Sha $Target) -cne (Sha $StagedFile)) { throw "Previously saved evidence differs: $Target" }
        return
    }
    Move-Item -LiteralPath $StagedFile -Destination $Target
}

$raw = (Resolve-Path -LiteralPath $RawDirectory).Path
$directory = (New-Item -ItemType Directory -Path $ReviewedDirectory -Force).FullName
if ($raw -eq $directory) { throw 'Use a separate review directory' }
$binary = (Resolve-Path -LiteralPath $Reviewer).Path
$binaryHash = Sha $binary

$verified = @(& "$PSScriptRoot/run-strength-matches.ps1" -Cohort holdout-1 -Slot $slots -VerifyExisting `
    -AppApk $AppApk -TestApk $TestApk -OutputDirectory $raw)
if ($verified.Count -ne $slots.Count) { throw 'Missing verified raw records' }

# Validate the schedule and coordinates before opening a device window.
$samples = @{}
foreach ($row in $verified) {
    $fields = $row.Split(',')
    $slot = [int]$fields[1]
    if ($fields[9] -cne 'double_pass') { throw "Slot $slot has no double-pass agreement" }
    $size = [int]$fields[2]
    $groups = @()
    if ($choices[$slot] -cne '-') {
        $groups = @($choices[$slot].Split(';'))
        foreach ($group in $groups) {
            $x, $y = $group.Split(',')
            if ([int]$x -ge $size -or [int]$y -ge $size) { throw "Group outside slot $slot board" }
        }
    }
    $samples[$slot] = @{ size = $size; groups = $groups; hash = $fields[18] }
}

$pending = @()
foreach ($slot in $slots) {
    $name = 'match-{0:D3}' -f $slot
    $receipt = Join-Path $directory "$name.json"
    if (!(Test-Path -LiteralPath $receipt)) {
        $pending += $slot
        continue
    }
    $prior = Get-Content -LiteralPath $receipt -Raw | ConvertFrom-Json
    if ($prior.slot -ne $slot -or $prior.dead_groups -cne $choices[$slot] -or
        $prior.raw_sha256 -cne $samples[$slot].hash -or $prior.reviewer_sha256 -cne $binaryHash -or
        $prior.reviewed_sha256 -cne (Sha (Join-Path $directory "$name.mocg")) -or
        $prior.review_log_sha256 -cne (Sha (Join-Path $directory "$name.log"))) {
        throw "Previously reviewed slot $slot differs from this request"
    }
}

if ($pending.Count) {
    if ((Device @('get-state')) -ne 'device' -or
        (Device @('shell', 'dumpsys', 'activity', 'processes')) -match '(?im)^\s*Active instrumentation:' -or
        (Device @('shell', 'ps', '-A', '-o', 'PID,NAME')) -match '(?m)com\.masterofchessstrategy:.+_qa\s*$') {
        throw 'Android device is unavailable or busy'
    }
    $run = 'go-strength-review-' + [guid]::NewGuid().ToString('N')
    $remote = "/data/local/tmp/$run"
    $stage = (New-Item -ItemType Directory -Path (Join-Path $directory $run)).FullName
    $driver = "$remote/review"
    $null = Device @('shell', 'mkdir', $remote)
    $null = Device @('push', $binary, $driver)
    $null = Device @('shell', 'chmod', '700', $driver)
    if ((DeviceSha $driver) -cne $binaryHash) { throw 'Transferred reviewer differs' }

    foreach ($slot in $pending) {
        $name = 'match-{0:D3}' -f $slot
        $inputFile = Join-Path $raw "$name.mocg"
        $inputRemote = "$remote/$name-original.mocg"
        $outputRemote = "$remote/$name-reviewed.mocg"
        $sample = $samples[$slot]
        $null = Device @('push', $inputFile, $inputRemote)
        if ((DeviceSha $inputRemote) -cne $sample.hash) { throw "Transferred raw slot $slot differs" }
        $verdict = Device (@('shell', $driver, "$($sample.size)", $inputRemote, $outputRemote) + $sample.groups)
        if ($verdict -cnotmatch '\Araw,[123],\d+,\d+,15,0\r?\nreviewed,[123],\d+,\d+,15,\d+\z') {
            throw "Invalid reviewed agreement for slot $slot"
        }
        $log = Join-Path $stage "$name.log"
        $record = Join-Path $stage "$name.mocg"
        $receipt = Join-Path $stage "$name.json"
        $null = Device @('pull', $outputRemote, $record)
        if ((Sha $record) -cne (DeviceSha $outputRemote)) { throw "Transferred review for slot $slot differs" }
        $verdict | Set-Content -LiteralPath $log -Encoding utf8NoBOM
        @{
            slot = $slot; raw_sha256 = $sample.hash; dead_groups = $choices[$slot]; reviewer_sha256 = $binaryHash
            reviewed_sha256 = (Sha $record); review_log_sha256 = (Sha $log)
        } | ConvertTo-Json | Set-Content -LiteralPath $receipt -Encoding utf8NoBOM
        PublishEvidence $record (Join-Path $directory "$name.mocg")
        PublishEvidence $log (Join-Path $directory "$name.log")
        PublishEvidence $receipt (Join-Path $directory "$name.json")
    }
}

$ledger = @("slot`traw_sha256`treviewed_sha256`treview_log_sha256")
foreach ($file in (Get-ChildItem -LiteralPath $directory -File -Filter 'match-*.json' | Sort-Object Name)) {
    $record = Get-Content -LiteralPath $file.FullName -Raw | ConvertFrom-Json
    if ($record.slot -notin 0..143 -or $file.BaseName -cne ('match-{0:D3}' -f $record.slot) -or
        $record.reviewer_sha256 -cne $binaryHash -or
        $record.reviewed_sha256 -cne (Sha (Join-Path $directory "$($file.BaseName).mocg")) -or
        $record.review_log_sha256 -cne (Sha (Join-Path $directory "$($file.BaseName).log"))) {
        throw "Invalid previously reviewed evidence: $($file.BaseName)"
    }
    $ledger += "$($record.slot)`t$($record.raw_sha256)`t$($record.reviewed_sha256)`t$($record.review_log_sha256)"
}
$stagedLedger = Join-Path $directory ('adjudication-' + [guid]::NewGuid().ToString('N') + '.tmp')
$ledger | Set-Content -LiteralPath $stagedLedger -Encoding utf8NoBOM
[IO.File]::Move($stagedLedger, (Join-Path $directory 'adjudication.tsv'), $true)
