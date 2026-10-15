[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$root = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$run = 'source-candidate-tests-' + [Guid]::NewGuid().ToString('N')
$evidence = Join-Path $root ('.build/' + $run)
New-Item -ItemType Directory -Path $evidence | Out-Null
$baseline = & git -C $root rev-parse HEAD
if ($LASTEXITCODE -ne 0) { throw 'Cannot identify baseline' }
$source = 'AGENTS.md'
$sha = (Get-FileHash -LiteralPath (Join-Path $root $source)).Hash.ToLowerInvariant()
$prepare = Join-Path $PSScriptRoot 'prepare-source-candidate.ps1'
$results = [Collections.Generic.List[object]]::new()

function New-Manifest {
    return @{ schemaVersion = 1; baselineCommit = $baseline; overlays = @(
        @{ sourcePath = $source; path = 'docs/candidate-tool-fixture.md'; sha256 = $sha }
    ) }
}
function Invoke-Rejected([string]$Name, [hashtable]$Manifest, [string]$Expected, [string]$Output = "$run/$Name") {
    $manifestRelative = '.build/' + $run + '/' + $Name + '.json'
    $Manifest | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $root $manifestRelative) -Encoding utf8
    $failure = $null
    try { & $prepare -ManifestPath $manifestRelative -OutputDirectory $Output | Out-Null }
    catch { $failure = $_.Exception.Message }
    if (-not $failure) { throw "Invalid case was accepted: $Name" }
    if (-not $failure.Contains($Expected)) { throw "Wrong rejection for ${Name}: $failure" }
    if (Test-Path -LiteralPath (Join-Path $evidence $Name)) { throw "Invalid case created output: $Name" }
    $results.Add([pscustomobject]@{ test = $Name; passed = $true; rejection = $failure })
    $results | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $evidence 'tests-in-progress.json') -Encoding utf8
}

$manifest = New-Manifest; $manifest.baselineCommit = 'not-a-commit'
Invoke-Rejected 'bad-commit' $manifest 'Invalid candidate manifest'
$manifest = New-Manifest; $manifest.overlays[0].sha256 = '0' * 64
Invoke-Rejected 'changed-input' $manifest 'Overlay input changed'
$manifest = New-Manifest; $manifest.overlays[0].sourcePath = '../AGENTS.md'
Invoke-Rejected 'source-traversal' $manifest 'Unsafe relative path'
$manifest = New-Manifest; $manifest.overlays[0].path = '../escape.txt'
Invoke-Rejected 'destination-traversal' $manifest 'Unsafe relative path'
$manifest = New-Manifest; $manifest.overlays[0].path = '.git/config'
Invoke-Rejected 'git-metadata' $manifest 'Forbidden or duplicate destination'
$manifest = New-Manifest; $manifest.overlays[0].path = './.git/config'
Invoke-Rejected 'git-dot-alias' $manifest 'Unsafe relative path'
$manifest = New-Manifest; $manifest.overlays[0].path = '.git./config'
Invoke-Rejected 'git-trailing-dot-alias' $manifest 'Unsafe relative path'
$manifest = New-Manifest; $manifest.overlays[0].path = 'README.md:stream'
Invoke-Rejected 'alternate-stream' $manifest 'Unsafe relative path'
$manifest = New-Manifest; $manifest.overlays += @{ sourcePath = $source; path = 'DOCS/CANDIDATE-TOOL-FIXTURE.MD'; sha256 = $sha }
Invoke-Rejected 'case-insensitive-duplicate' $manifest 'Forbidden or duplicate destination'
Invoke-Rejected 'output-traversal' (New-Manifest) 'Unsafe relative path' '../candidate-escape'

$manifestRelative = '.build/' + $run + '/valid.json'
(New-Manifest) | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $root $manifestRelative) -Encoding utf8
& $prepare -ManifestPath $manifestRelative -OutputDirectory "$run/valid" | Out-Null
$receipt = Get-Content -Raw -LiteralPath (Join-Path $evidence 'valid/assembly.json') | ConvertFrom-Json
$candidate = Join-Path $evidence 'valid/source'
if (-not $receipt.mainHeadUnchanged -or -not $receipt.mainIndexUnchanged -or $receipt.worktreeCreated -or
    $receipt.releaseReady -or $receipt.compiled -or $receipt.deviceTested -or
    (Test-Path -LiteralPath (Join-Path $candidate '.git')) -or
    (Get-FileHash -LiteralPath (Join-Path $candidate 'docs/candidate-tool-fixture.md')).Hash.ToLowerInvariant() -ne $sha) {
    throw 'Valid assembly receipt or copied bytes are wrong'
}
$results.Add([pscustomobject]@{ test = 'valid-archive-and-overlay'; passed = $true })
$baselineIgnore = & git -C $root rev-parse "${baseline}:.gitignore"
if ($LASTEXITCODE -ne 0) { throw 'Cannot identify baseline ignore blob' }
$exportIgnore = & git -C $root hash-object --no-filters -- (Join-Path $candidate '.gitignore')
if ($LASTEXITCODE -ne 0 -or $exportIgnore -ne $baselineIgnore -or
    $receipt.archiveTextMode -ne 'core.autocrlf=false;core.eol=lf') {
    throw 'Baseline export depends on the host autocrlf configuration'
}
$results.Add([pscustomobject]@{ test = 'baseline-export-independent-of-autocrlf'; passed = $true })
$receiptBefore = (Get-FileHash -LiteralPath (Join-Path $evidence 'valid/assembly.json')).Hash
$failure = $null
try { & $prepare -ManifestPath $manifestRelative -OutputDirectory "$run/valid" | Out-Null }
catch { $failure = $_.Exception.Message }
if (-not $failure -or (Get-FileHash -LiteralPath (Join-Path $evidence 'valid/assembly.json')).Hash -ne $receiptBefore) {
    throw 'Existing evidence was not preserved'
}
$results.Add([pscustomobject]@{ test = 'existing-evidence-rejected'; passed = $true; rejection = $failure })
$nested = Join-Path $evidence 'nested/tools/releases'
New-Item -ItemType Directory -Path $nested -Force | Out-Null
$nestedPrepare = Join-Path $nested 'prepare-source-candidate.ps1'
Copy-Item -LiteralPath $prepare -Destination $nestedPrepare
$failure = $null
try { & $nestedPrepare -ManifestPath 'unused.json' -OutputDirectory 'unused' | Out-Null }
catch { $failure = $_.Exception.Message }
if (-not $failure -or -not $failure.Contains('Repository root mismatch')) {
    throw 'An exported nested snapshot borrowed the parent Git repository'
}
$results.Add([pscustomobject]@{ test = 'nested-export-cannot-borrow-parent-git'; passed = $true; rejection = $failure })
$results | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $evidence 'tests.json') -Encoding utf8
"Verified $($results.Count)/$($results.Count) candidate tool cases; evidence: $evidence"
