param(
    [ValidateSet('Import', 'Verify', 'TestAssets', 'VerifyTestAssets', 'AppAssets', 'VerifyAppAssets')]
    [string]$Mode = 'Verify'
)

# Root-owned import of reviewed original bytes; never overwrites differing upstream files.
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$goRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '../..')).Path
$goStage = Join-Path $goRoot '.build/go-katago-distribution-v1'
$goTarget = Join-Path $goRoot 'third_party/go-katago-upstream'
& (Join-Path $goRoot 'tools/games/go/prepare-katago-distribution.ps1') -Mode Verify
if ((Get-FileHash -LiteralPath (Join-Path $goStage 'manifest.json')).Hash -ne
    '081f5e48bdd5979cfe9b342470b1c4ccf11570646d9a0949ab5933239958202a') {
    throw 'Reviewed distribution manifest changed; reassess the closure before importing'
}
$goManifest = Get-Content -LiteralPath (Join-Path $goStage 'manifest.json') -Raw | ConvertFrom-Json
$goAssetImport = $Mode -in @('TestAssets', 'VerifyTestAssets', 'AppAssets', 'VerifyAppAssets')
$goAppAssets = $Mode -in @('AppAssets', 'VerifyAppAssets')
if ($goAssetImport) {
    $goTarget = if ($goAppAssets) {
        Join-Path $goRoot 'app/src/main/assets'
    } else {
        Join-Path $goRoot 'engine-native/src/androidTest/assets'
    }
    $goFiles = @($goManifest.files | Where-Object { $_.path -match '^assets/games/go/' } | ForEach-Object {
        # Android's asset merger expands .gz. A neutral suffix must retain the locked compressed bytes.
        $goAssetName = $_.path.Substring(7)
        if ($goAssetName.EndsWith('.txt.gz')) { $goAssetName += '.bin' }
        [pscustomobject]@{path=$goAssetName; source=$_.path; sha256=$_.sha256}
    })
    if ($goFiles.Count -ne 13) { throw 'Unexpected model/legal artifact count' }
    if ($goAppAssets) {
        $goReleaseManifest = Join-Path $goRoot 'config/games/go/katago-app-assets-v1.tsv'
        $goExpected = @{}
        foreach ($goLine in Get-Content -LiteralPath $goReleaseManifest) {
            if ([string]::IsNullOrWhiteSpace($goLine) -or $goLine.StartsWith('#')) { continue }
            $goFields = $goLine.Split('|')
            if ($goFields.Count -ne 4 -or $goExpected.ContainsKey($goFields[0])) {
                throw 'Invalid or duplicate production Go asset manifest row'
            }
            $goExpected.Add($goFields[0], [pscustomobject]@{
                source = $goFields[1]; bytes = [long]$goFields[2]; sha256 = $goFields[3]
            })
        }
        if ($goExpected.Count -ne 13) { throw 'Unexpected production Go asset manifest count' }
        foreach ($goFile in $goFiles) {
            if (-not $goExpected.ContainsKey($goFile.path)) { throw "Unreviewed production Go asset: $($goFile.path)" }
            $goRow = $goExpected[$goFile.path]
            $goStageEntry = @($goManifest.files | Where-Object { $_.path -eq $goRow.source })
            if ($goStageEntry.Count -ne 1 -or
                [long]$goStageEntry[0].bytes -ne $goRow.bytes -or
                $goStageEntry[0].sha256 -ne $goRow.sha256 -or
                $goFile.source -ne $goRow.source -or $goFile.sha256 -ne $goRow.sha256) {
                throw "Production Go asset review differs: $($goFile.path)"
            }
            $goFile | Add-Member -NotePropertyName bytes -NotePropertyValue $goRow.bytes
        }
    }
} else {
    $goFiles = @($goManifest.files | Where-Object { $_.path -match '^(source|eigen)/' })
    if ($goFiles.Count -ne 392) { throw 'Unexpected native dependency file count' }
    $goFiles += @([pscustomobject]@{path='selection.json'; sha256=(Get-FileHash (Join-Path $goStage 'selection.json')).Hash},
        [pscustomobject]@{path='probe-lock.json'; sha256=(Get-FileHash (Join-Path $goStage 'probe-lock.json')).Hash},
        [pscustomobject]@{path='manifest.json'; sha256=(Get-FileHash (Join-Path $goStage 'manifest.json')).Hash})
}

# Check every destination and ancestor before any import. Fixed target stays inside the workspace.
foreach ($goFile in $goFiles) {
    if ($goFile.path -match '[\\:]' -or $goFile.path.StartsWith('/') -or
        ($goFile.path.Split('/') | Where-Object { $_ -in @('', '.', '..') })) { throw 'Unsafe source path' }
    $goPath = [IO.Path]::GetFullPath((Join-Path $goTarget $goFile.path))
    if (-not $goPath.StartsWith($goTarget + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
        throw 'Dependency path escaped the fixed target'
    }
    $goAncestor = $goPath
    while ($goAncestor -ne $goRoot) {
        if ((Test-Path -LiteralPath $goAncestor) -and
            ((Get-Item -LiteralPath $goAncestor -Force).Attributes -band [IO.FileAttributes]::ReparsePoint)) {
            throw 'Dependency destination contains a reparse point'
        }
        $goAncestor = [IO.Path]::GetDirectoryName($goAncestor)
    }
    if (Test-Path -LiteralPath $goPath) {
        if (-not (Test-Path -LiteralPath $goPath -PathType Leaf) -or
            (Get-FileHash -LiteralPath $goPath).Hash -ne $goFile.sha256) { throw "Original bytes differ: $($goFile.path)" }
    } elseif ($Mode -in @('Verify', 'VerifyTestAssets', 'VerifyAppAssets')) {
        throw "Native dependency missing: $($goFile.path)"
    }
}
if ($goAssetImport) {
    $goOldName = Join-Path $goTarget ('games/go/ai/' +
        (Get-Content (Join-Path $goStage 'probe-lock.json') -Raw | ConvertFrom-Json).network.name + '.txt.gz')
    if (Test-Path -LiteralPath $goOldName) {
        throw 'Old .gz test asset must be relocated after verifying its hash; it is automatically expanded by Android'
    }
}
if ($goAppAssets) {
    $goActual = @(foreach ($goDirectory in @('games/go/ai', 'games/go/legal')) {
        $goAssetDirectory = Join-Path $goTarget $goDirectory
        if (Test-Path -LiteralPath $goAssetDirectory) {
            Get-ChildItem -LiteralPath $goAssetDirectory -Recurse -File | ForEach-Object {
                [IO.Path]::GetRelativePath($goTarget, $_.FullName).Replace('\', '/')
            }
        }
    })
    $goReviewed = @($goFiles.path)
    if (@($goActual | Where-Object { $_ -notin $goReviewed }).Count -ne 0) {
        throw 'Production Go asset directories contain an unreviewed file'
    }
}
if ($Mode -in @('Import', 'TestAssets', 'AppAssets')) {
    foreach ($goFile in $goFiles) {
        $goPath = Join-Path $goTarget $goFile.path
        if (Test-Path -LiteralPath $goPath) { continue }
        New-Item -ItemType Directory -Path ([IO.Path]::GetDirectoryName($goPath)) -Force | Out-Null
        $goSource = if ($goAssetImport) { $goFile.source } else { $goFile.path }
        Copy-Item -LiteralPath (Join-Path $goStage $goSource) -Destination $goPath
    }
}
foreach ($goFile in $goFiles) {
    if ((Get-FileHash -LiteralPath (Join-Path $goTarget $goFile.path)).Hash -ne $goFile.sha256) {
        throw "Native dependency readback failed: $($goFile.path)"
    }
}
$goBoundary = if ($goAppAssets) { 'Production App assets match the reviewed manifest.' }
    else { 'Production App assets are untouched.' }
Write-Output "Native Go dependency $Mode verified: $($goFiles.Count) artifacts. $goBoundary"
