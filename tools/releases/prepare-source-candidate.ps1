[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$ManifestPath,
    [Parameter(Mandatory)][string]$OutputDirectory
)

$ErrorActionPreference = 'Stop'
$repoRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$buildRoot = Join-Path $repoRoot '.build'

# Never borrow a parent repository when invoked from a nested exported source snapshot.
$gitRoot = & git -C $repoRoot rev-parse --show-toplevel
if ($LASTEXITCODE -ne 0 -or [IO.Path]::GetFullPath($gitRoot) -ne $repoRoot) {
    throw 'Repository root mismatch; run this tool from its own Git checkout'
}

function Resolve-ContainedPath([string]$BasePath, [string]$RelativePath) {
    $parts = $RelativePath.Split([char[]]'/\')
    if ([string]::IsNullOrWhiteSpace($RelativePath) -or [IO.Path]::IsPathRooted($RelativePath) -or
        $RelativePath.Contains(':') -or @($parts | Where-Object {
            $_ -in @('', '.', '..') -or $_ -ne $_.TrimEnd(' ', '.')
        }).Count -gt 0) {
        throw "Unsafe relative path: $RelativePath"
    }
    $baseFull = [IO.Path]::GetFullPath($BasePath).TrimEnd([IO.Path]::DirectorySeparatorChar)
    $full = [IO.Path]::GetFullPath((Join-Path $baseFull $RelativePath))
    if (-not $full.StartsWith($baseFull + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
        throw "Path escapes its owned directory: $RelativePath"
    }
    return $full
}

function File-Sha([string]$Path) {
    return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
}

$manifestFull = Resolve-ContainedPath $repoRoot $ManifestPath
$outputFull = Resolve-ContainedPath $buildRoot $OutputDirectory
if (Test-Path -LiteralPath $outputFull) { throw 'Candidate output already exists; never overwrite evidence' }
$manifest = Get-Content -Raw -LiteralPath $manifestFull | ConvertFrom-Json
if ($manifest.schemaVersion -ne 1 -or $manifest.baselineCommit -notmatch '^[0-9a-f]{40}$' -or
    @($manifest.overlays).Count -eq 0) { throw 'Invalid candidate manifest' }
$manifestSha = File-Sha $manifestFull
$candidateSource = Join-Path $outputFull 'source'
$destinations = [Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
$inputs = @($manifest.overlays | ForEach-Object {
    if ($_.sha256 -notmatch '^[0-9a-f]{64}$') { throw 'An overlay needs its exact SHA-256' }
    $source = Resolve-ContainedPath $repoRoot $_.sourcePath
    $destination = Resolve-ContainedPath $candidateSource $_.path
    if ($_.path.Split([char[]]'/\')[0] -in @('.git','.codex','.agents','.aws','.build') -or
        -not $destinations.Add($destination)) { throw "Forbidden or duplicate destination: $($_.path)" }
    if (-not (Test-Path -LiteralPath $source -PathType Leaf) -or (File-Sha $source) -ne $_.sha256) {
        throw "Overlay input changed: $($_.sourcePath)"
    }
    [pscustomobject]@{ Source = $source; Destination = $destination; Entry = $_ }
})

$baseline = $manifest.baselineCommit
$resolved = & git -C $repoRoot rev-parse "$baseline^{commit}"
if ($LASTEXITCODE -ne 0 -or $resolved -ne $baseline) { throw 'Baseline is not the specified existing commit' }
$headBefore = & git -C $repoRoot rev-parse HEAD
if ($LASTEXITCODE -ne 0) { throw 'Cannot read main HEAD' }
$indexPath = Join-Path $repoRoot '.git/index'
$indexBefore = if (Test-Path -LiteralPath $indexPath) { File-Sha $indexPath } else { $null }
New-Item -ItemType Directory -Path $outputFull | Out-Null
$archive = Join-Path $outputFull 'baseline.zip'
& git -C $repoRoot -c core.autocrlf=false -c core.eol=lf archive '--format=zip' '-o' $archive $baseline
if ($LASTEXITCODE -ne 0) { throw 'Read-only baseline archive failed; partial evidence is retained' }

Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [IO.Compression.ZipFile]::OpenRead($archive)
try {
    foreach ($entry in $zip.Entries) {
        $relative = $entry.FullName.TrimEnd('/')
        if ($relative) { $null = Resolve-ContainedPath $candidateSource $relative }
        if ($relative.Split('/')[0] -eq '.git') { throw 'An archive may not contain Git metadata' }
    }
} finally { $zip.Dispose() }
[IO.Compression.ZipFile]::ExtractToDirectory($archive, $candidateSource)

foreach ($input in $inputs) {
    # Only exact destinations in this newly created snapshot may replace baseline files.
    New-Item -ItemType Directory -Path ([IO.Path]::GetDirectoryName($input.Destination)) -Force | Out-Null
    Copy-Item -LiteralPath $input.Source -Destination $input.Destination -Force
    if ((File-Sha $input.Destination) -ne $input.Entry.sha256) { throw 'Candidate copy identity mismatch' }
}
foreach ($input in $inputs) {
    if ((File-Sha $input.Source) -ne $input.Entry.sha256) { throw 'An overlay changed during assembly' }
}
if ((File-Sha $manifestFull) -ne $manifestSha) { throw 'Manifest changed during assembly' }
$headAfter = & git -C $repoRoot rev-parse HEAD
$indexAfter = if (Test-Path -LiteralPath $indexPath) { File-Sha $indexPath } else { $null }
if ($LASTEXITCODE -ne 0 -or $headAfter -ne $headBefore -or $indexAfter -ne $indexBefore) {
    throw 'Main HEAD/index changed during assembly; candidate is not accepted'
}

$identities = @(Get-ChildItem -LiteralPath $candidateSource -File -Recurse | ForEach-Object {
    [pscustomobject]@{ path = [IO.Path]::GetRelativePath($candidateSource, $_.FullName).Replace('\','/'); sha256 = File-Sha $_.FullName }
} | Sort-Object path)
@('path' + "`t" + 'sha256') + @($identities | ForEach-Object { $_.path + "`t" + $_.sha256 }) |
    Set-Content -LiteralPath (Join-Path $outputFull 'source-identity.tsv') -Encoding utf8
[pscustomobject]@{
    schemaVersion = 1; assembledUtc = [DateTime]::UtcNow.ToString('o'); baselineCommit = $baseline
    manifestSha256 = $manifestSha; baselineArchiveSha256 = File-Sha $archive
    archiveTextMode = 'core.autocrlf=false;core.eol=lf'
    sourceIdentitySha256 = File-Sha (Join-Path $outputFull 'source-identity.tsv')
    fileCount = $identities.Count; overlayCount = $inputs.Count
    mainHeadUnchanged = $true; mainIndexUnchanged = $true; worktreeCreated = $false
    compiled = $false; deviceTested = $false; releaseReady = $false
} | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $outputFull 'assembly.json') -Encoding utf8
"Candidate assembled: $candidateSource ($($identities.Count) files); verification and release gates remain open."
