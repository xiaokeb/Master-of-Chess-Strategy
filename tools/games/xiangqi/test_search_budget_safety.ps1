param(
    [Parameter(Mandatory)][string]$BuildDirectory,
    [string]$Serial = 'emulator-5554',
    [string]$SdkDirectory = 'E:\Backend_Env\SDK'
)

# Executes only the new diagnostic binaries. Never stop/restart an original
# benchmark, overwrite a result, clear app data, install an APK or change NNUE.
$ErrorActionPreference = 'Stop'
$repoDirectory = (Resolve-Path (Join-Path $PSScriptRoot '../../..')).Path
$budgetOutput = [IO.Path]::GetFullPath((Join-Path $repoDirectory $BuildDirectory))
$buildParent = [IO.Path]::GetFullPath((Join-Path $repoDirectory '.build'))
if (-not $budgetOutput.StartsWith($buildParent + [IO.Path]::DirectorySeparatorChar,
        [StringComparison]::OrdinalIgnoreCase)) { throw 'Build path must stay inside .build.' }
if ($Serial -notmatch '^emulator-\d+$') { throw 'This check is approved for an emulator only.' }
$adb = Join-Path $SdkDirectory 'platform-tools/adb.exe'
$deviceNnue = '/data/local/tmp/mocs-pikafish.nnue'
$networkSha = '7d13d73569a9b571ba0eb20cf1596247bc2a42738967e61afef6482b231e900e'
$receiptPath = Join-Path $budgetOutput 'build-receipt.json'
$buildReceipt = Get-Content -LiteralPath $receiptPath -Raw | ConvertFrom-Json
if ($buildReceipt.kind -ne 'xiangqi-search-budget-safety-build-v1') { throw 'Wrong build identity.' }
foreach ($registeredJob in @(
    '.build/xiangqi-ai-calibration-v4r1-20261015/active-job.json',
    '.build/xiangqi-ai-holdout-v1-20261015/active-job.json'
)) {
    if (Test-Path -LiteralPath (Join-Path $repoDirectory $registeredJob)) {
        throw "Registered evaluation owns the device: $registeredJob"
    }
}
Push-Location $repoDirectory
try {
    foreach ($inputSource in $buildReceipt.source_sha256.PSObject.Properties) {
        if ((Get-FileHash -LiteralPath $inputSource.Name).Hash.ToLowerInvariant() -ne $inputSource.Value) {
            throw "Build input changed: $($inputSource.Name)"
        }
    }
    $deviceModel = @(& $adb -s $Serial shell sha256sum $deviceNnue)
    if ($LASTEXITCODE -ne 0 -or $deviceModel.Count -ne 1 -or
        -not $deviceModel[0].StartsWith("$networkSha ")) { throw 'Device NNUE identity differs.' }
    $runId = [Guid]::NewGuid().ToString('N')
    $runDirectory = Join-Path $budgetOutput "device-$runId"
    $remoteDirectory = "/data/local/tmp/mocs-budget-safety-$runId"
    New-Item -ItemType Directory -Path $runDirectory | Out-Null
    & $adb -s $Serial shell mkdir $remoteDirectory
    if ($LASTEXITCODE -ne 0) { throw 'Cannot create fresh device diagnostic directory.' }
    $results = @()
    foreach ($suite in @('safety', 'runtime')) {
        $target = "mocs_pikafish_budget_${suite}_test"
        $product = Join-Path $budgetOutput "x86_64/tests/$target"
        $expectedSha = $buildReceipt.products."x86_64/$target"
        if ((Get-FileHash -LiteralPath $product).Hash.ToLowerInvariant() -ne $expectedSha) {
            throw 'Built product changed.'
        }
        $remote = "$remoteDirectory/$target"
        & $adb -s $Serial push $product $remote
        if ($LASTEXITCODE -ne 0) { throw 'Cannot transfer diagnostic executable.' }
        & $adb -s $Serial shell chmod 700 $remote
        if ($LASTEXITCODE -ne 0) { throw 'Cannot make executable.' }
        $actual = @(& $adb -s $Serial shell sha256sum $remote)
        if ($LASTEXITCODE -ne 0 -or $actual.Count -ne 1 -or -not $actual[0].StartsWith("$expectedSha ")) {
            throw 'Installed diagnostic executable differs.'
        }
        $log = Join-Path $runDirectory "$suite.log"
        & $adb -s $Serial shell -T $remote $deviceNnue 2>&1 | Tee-Object -FilePath $log
        $diagnosticExit = $LASTEXITCODE
        $requiredSummary = if ($suite -eq 'safety') { 'qsearch checks=11 failures=0' } else { 'runtime checks=20 failures=0' }
        $passed = $diagnosticExit -eq 0 -and
            (Get-Content -LiteralPath $log | Select-Object -Last 1) -ceq $requiredSummary
        $result = [ordered]@{
            suite = $suite; exit_code = $diagnosticExit; passed = $passed
            binary_sha256 = $expectedSha
            log_sha256 = (Get-FileHash -LiteralPath $log).Hash.ToLowerInvariant()
            ended_utc = [DateTime]::UtcNow.ToString('o')
        }
        $result | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $runDirectory "$suite-exit.json") -Encoding utf8
        $results += $result
        if (-not $passed) { throw "Diagnostic failed; preserve the original logs: $suite" }
    }
    [ordered]@{
        kind = 'xiangqi-search-budget-safety-device-v1'
        serial = $Serial; abi = 'x86_64'; arm64_executed = $false
        build_receipt_sha256 = (Get-FileHash -LiteralPath $receiptPath).Hash.ToLowerInvariant()
        nnue_sha256 = $networkSha; remote_directory = $remoteDirectory
        script_sha256 = (Get-FileHash -LiteralPath $PSCommandPath).Hash.ToLowerInvariant()
        app_data_cleared = $false; apk_installed = $false
        original_evaluation_touched = $false; results = $results
    } | ConvertTo-Json -Depth 6 |
        Set-Content -LiteralPath (Join-Path $runDirectory 'device-receipt.json') -Encoding utf8
    Write-Output "Device evidence: $runDirectory"
} finally {
    Pop-Location
}
