param(
    [ValidateSet('Stage', 'Verify')][string]$Mode = 'Stage',
    [string]$OutputDirectory = '.build/go-katago-distribution-v1',
    [string]$SelectionFile = 'config/games/go/katago-distribution-v1.json'
)

# Dependency payload only. No Gradle, NDK build, ADB, download or production asset writes.
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
Add-Type -AssemblyName System.IO.Compression.FileSystem
$goProject = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '../../..')).Path
$goCache = Join-Path $goProject '.build/go-katago-probe'
$goUtf8 = [Text.UTF8Encoding]::new($false)
$goLockPath = Join-Path $goProject 'config/games/go/katago-probe-lock.json'
$goLock = Get-Content -LiteralPath $goLockPath -Raw | ConvertFrom-Json

function Assert-GoRelative([string]$Path) {
    if ([string]::IsNullOrWhiteSpace($Path) -or $Path -match '[\\:]' -or
        $Path.StartsWith('/') -or ($Path.Split('/') | Where-Object { $_ -in @('', '.', '..') })) {
        throw "Unsafe relative path: $Path"
    }
}

function Get-GoWorkspacePath([string]$Path) {
    $goResolved = [IO.Path]::GetFullPath((Join-Path $goProject $Path))
    if (-not $goResolved.StartsWith($goProject + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
        throw 'Path is outside this workspace'
    }
    # Refuse existing junctions/symlinks throughout the destination ancestry.
    $goAncestor = $goResolved
    while ($goAncestor -ne $goProject) {
        if ((Test-Path -LiteralPath $goAncestor) -and
            ((Get-Item -LiteralPath $goAncestor -Force).Attributes -band [IO.FileAttributes]::ReparsePoint)) {
            throw "Reparse point is not a distribution directory: $goAncestor"
        }
        $goAncestor = [IO.Path]::GetDirectoryName($goAncestor)
    }
    return $goResolved
}

function Get-GoHash([byte[]]$Bytes) {
    $goHasher = [Security.Cryptography.SHA256]::Create()
    try { return [Convert]::ToHexString($goHasher.ComputeHash($Bytes)).ToLowerInvariant() }
    finally { $goHasher.Dispose() }
}

function Read-GoLocked([string]$Name, [long]$Length, [string]$Hash) {
    $goPath = Join-Path $goCache $Name
    if ((Get-Item -LiteralPath $goPath).Length -ne $Length -or
        (Get-FileHash -LiteralPath $goPath -Algorithm SHA256).Hash -ne $Hash) {
        throw "Locked input differs: $Name"
    }
    return $goPath
}

Assert-GoRelative $OutputDirectory
if ($OutputDirectory -notmatch '^\.build/go-katago-distribution-[A-Za-z0-9_-]+$') {
    throw 'Output must be a dedicated .build/go-katago-distribution-* directory'
}
$goOutput = Get-GoWorkspacePath $OutputDirectory
$goSelectionPath = Get-GoWorkspacePath $SelectionFile
$goSpec = Get-Content -LiteralPath $goSelectionPath -Raw | ConvertFrom-Json
if ($goSpec.schemaVersion -ne 1 -or $goSpec.status -ne 'dependency-stage-only' -or
    $goSpec.probeLock -ne 'katago-probe-lock.json' -or
    $goSpec.requiredCompileDefinitions -notcontains 'EIGEN_MPL2_ONLY') {
    throw 'Unsupported distribution selection'
}
$goKataZip = Read-GoLocked 'KataGo-92ee95c0.zip' $goLock.katago.archiveBytes $goLock.katago.archiveSha256
$goEigenZip = Read-GoLocked 'eigen-3.4.0-verified.zip' $goLock.eigen.localArchiveBytes $goLock.eigen.localArchiveSha256
$goModelPath = Read-GoLocked ($goLock.network.name + '.txt.gz') $goLock.network.bytes $goLock.network.sha256
$goLicensePath = Join-Path $goCache 'network-license.html'
if ((Get-FileHash -LiteralPath $goLicensePath -Algorithm SHA256).Hash -ne $goLock.network.licensePageSha256) {
    throw 'Network license snapshot differs'
}

$goSelected = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
foreach ($goName in $goSpec.sourceFiles) {
    Assert-GoRelative $goName
    if ($goName -notmatch '^(source|eigen)/' -or -not $goSelected.Add($goName)) {
        throw "Invalid or duplicate source selection: $goName"
    }
}
$goPayload = [Collections.Generic.SortedDictionary[string,byte[]]]::new([StringComparer]::Ordinal)
foreach ($goPair in @(
    @{ Path = $goKataZip; Root = 'KataGo-' + $goLock.katago.commit + '/'; Prefix = 'source/' },
    @{ Path = $goEigenZip; Root = ''; Prefix = 'eigen/' }
)) {
    $goZip = [IO.Compression.ZipFile]::OpenRead($goPair.Path)
    $goEntryNames = [Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
    try {
        foreach ($goEntry in $goZip.Entries) {
            if (-not $goEntry.FullName.StartsWith($goPair.Root, [StringComparison]::Ordinal)) {
                throw 'Archive root differs from locked source revision'
            }
            $goRelative = $goEntry.FullName.Substring($goPair.Root.Length)
            if ($goEntry.Name.Length -eq 0) { continue }
            Assert-GoRelative $goRelative
            if (-not $goEntryNames.Add($goRelative)) { throw 'Duplicate archive member' }
            $goName = $goPair.Prefix + $goRelative
            if (-not $goSelected.Contains($goName)) { continue }
            $goStream = $goEntry.Open()
            $goMemory = [IO.MemoryStream]::new()
            try { $goStream.CopyTo($goMemory); $goPayload.Add($goName, $goMemory.ToArray()) }
            finally { $goStream.Dispose(); $goMemory.Dispose() }
        }
    } finally { $goZip.Dispose() }
}
foreach ($goName in $goSelected) {
    if (-not $goPayload.ContainsKey($goName)) { throw "Required archive member is missing: $goName" }
}
foreach ($goCheck in @(
    @{ Name = 'source/LICENSE'; Hash = $goLock.katago.licenseSha256 },
    @{ Name = 'eigen/COPYING.MPL2'; Hash = $goLock.eigen.mpl2LicenseSha256 }
)) {
    if (-not $goPayload.ContainsKey($goCheck.Name) -or (Get-GoHash $goPayload[$goCheck.Name]) -ne $goCheck.Hash) {
        throw "Required license is missing or differs: $($goCheck.Name)"
    }
}

$goLegalRoot = 'assets/games/go/legal/'
foreach ($goNotice in @(
    @{ Source = 'source/LICENSE'; Target = 'KataGo-LICENSE.txt' },
    @{ Source = 'source/CONTRIBUTORS'; Target = 'KataGo-CONTRIBUTORS.txt' },
    @{ Source = 'source/cpp/external/filesystem-1.5.8/LICENSE'; Target = 'filesystem-LICENSE.txt' },
    # Retain every embedded notice, including CC0/Hedley, Grisu2 and UTF8 contributors.
    @{ Source = 'source/cpp/external/nlohmann_json/json.hpp'; Target = 'nlohmann-json-source.hpp' },
    @{ Source = 'source/cpp/core/sha2.cpp'; Target = 'sha2-source.cpp' },
    @{ Source = 'eigen/COPYING.MPL2'; Target = 'Eigen-COPYING.MPL2' },
    @{ Source = 'eigen/COPYING.README'; Target = 'Eigen-COPYING.README' },
    @{ Source = 'eigen/COPYING.BSD'; Target = 'Eigen-COPYING.BSD' },
    @{ Source = 'eigen/COPYING.APACHE'; Target = 'Eigen-COPYING.APACHE' },
    @{ Source = 'eigen/COPYING.MINPACK'; Target = 'Eigen-COPYING.MINPACK' }
)) {
    if (-not $goPayload.ContainsKey($goNotice.Source)) { throw "Required notice missing: $($goNotice.Source)" }
    $goPayload.Add($goLegalRoot + $goNotice.Target, $goPayload[$goNotice.Source])
}
$goPayload.Add($goLegalRoot + 'KataGo-network-license.html', [IO.File]::ReadAllBytes($goLicensePath))
$goPayload.Add('assets/games/go/ai/' + $goLock.network.name + '.txt.gz', [IO.File]::ReadAllBytes($goModelPath))
$goPayload.Add('selection.json', [IO.File]::ReadAllBytes($goSelectionPath))
$goPayload.Add('probe-lock.json', [IO.File]::ReadAllBytes($goLockPath))

# Deterministic dependency source bundle; includes original per-file copyright notices.
# This is not the complete corresponding source of the future Android application.
$goSourceMemory = [IO.MemoryStream]::new()
$goSourceZip = [IO.Compression.ZipArchive]::new($goSourceMemory, [IO.Compression.ZipArchiveMode]::Create, $true)
try {
    foreach ($goName in $goPayload.Keys) {
        if ($goName -notmatch '^(source|eigen)/' -and $goName -notin @('selection.json','probe-lock.json')) { continue }
        $goEntry = $goSourceZip.CreateEntry($goName, [IO.Compression.CompressionLevel]::Optimal)
        $goEntry.LastWriteTime = [DateTimeOffset]::new(1980, 1, 1, 0, 0, 0, [TimeSpan]::Zero)
        $goStream = $goEntry.Open()
        try { $goStream.Write($goPayload[$goName], 0, $goPayload[$goName].Length) }
        finally { $goStream.Dispose() }
    }
} finally { $goSourceZip.Dispose() }
$goPayload.Add($goLegalRoot + 'dependency-source.zip', $goSourceMemory.ToArray())
$goSourceMemory.Dispose()
$goEntries = @(foreach ($goName in $goPayload.Keys) {
    [ordered]@{ path = $goName; bytes = $goPayload[$goName].Length; sha256 = Get-GoHash $goPayload[$goName] }
})
$goManifest = [ordered]@{
    schemaVersion = 1; status = 'dependency-stage-only'; sourceFiles = $goSelected.Count
    katagoCommit = $goLock.katago.commit; eigenCommit = $goLock.eigen.commit
    requiredCompileDefinitions = $goSpec.requiredCompileDefinitions
    releaseGates = $goSpec.releaseGates; files = $goEntries
}
$goPayload.Add('manifest.json', $goUtf8.GetBytes(($goManifest | ConvertTo-Json -Depth 8) + "`n"))

# Preflight the entire destination before writing. Existing different bytes are never overwritten.
$goExisting = @(if (Test-Path -LiteralPath $goOutput) { Get-ChildItem -LiteralPath $goOutput -Recurse -Force })
foreach ($goItem in $goExisting) {
    if ($goItem.Attributes -band [IO.FileAttributes]::ReparsePoint) { throw 'Reparse point in staged payload' }
    if ($goItem.PSIsContainer) { continue }
    $goRelative = [IO.Path]::GetRelativePath($goOutput, $goItem.FullName).Replace('\', '/')
    if (-not $goPayload.ContainsKey($goRelative)) { throw "Unexpected staged file: $goRelative" }
}
foreach ($goName in $goPayload.Keys) {
    $goPath = Join-Path $goOutput $goName
    if (Test-Path -LiteralPath $goPath) {
        if (-not (Test-Path -LiteralPath $goPath -PathType Leaf) -or
            (Get-Item -LiteralPath $goPath).Length -ne $goPayload[$goName].Length -or
            (Get-FileHash -LiteralPath $goPath -Algorithm SHA256).Hash -ne (Get-GoHash $goPayload[$goName])) {
            throw "Staged bytes differ: $goName"
        }
    } elseif ($Mode -eq 'Verify') { throw "Staged file missing: $goName" }
}
if ($Mode -eq 'Stage') {
    foreach ($goName in $goPayload.Keys) {
        $goPath = Join-Path $goOutput $goName
        if (Test-Path -LiteralPath $goPath) { continue }
        [void][IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($goPath))
        $goStream = [IO.File]::Open($goPath, [IO.FileMode]::CreateNew, [IO.FileAccess]::Write, [IO.FileShare]::None)
        try { $goStream.Write($goPayload[$goName], 0, $goPayload[$goName].Length); $goStream.Flush($true) }
        finally { $goStream.Dispose() }
    }
}
foreach ($goName in $goPayload.Keys) {
    if ((Get-FileHash -LiteralPath (Join-Path $goOutput $goName) -Algorithm SHA256).Hash -ne (Get-GoHash $goPayload[$goName])) {
        throw "Staged readback differs: $goName"
    }
}
Write-Output ("Go dependency payload {0}: {1} source files, {2} artifacts; manifest SHA256 {3}" -f
    $Mode, $goSelected.Count, $goPayload.Count, (Get-GoHash $goPayload['manifest.json']))
