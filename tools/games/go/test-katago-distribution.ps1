# Run after preparing the private dependency payload. No shared build or device operations.
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$goProject = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '../../..')).Path
$goPrepare = Join-Path $PSScriptRoot 'prepare-katago-distribution.ps1'
$goOutput = Join-Path $goProject '.build/go-katago-distribution-v1'
$goEvidence = Join-Path $goProject '.build/go-katago-distribution-check'
[void][IO.Directory]::CreateDirectory($goEvidence)
$goResults = [Collections.Generic.List[object]]::new()

function Invoke-GoCase([string]$Name, [scriptblock]$Action) {
    & $Action
    $goResults.Add([ordered]@{ name = $Name; passed = $true })
}

function Assert-GoRejected([scriptblock]$Action, [string]$Expected) {
    $goMessage = $null
    try { & $Action | Out-Null } catch { $goMessage = $_.Exception.Message }
    if ($null -eq $goMessage -or $goMessage -notlike "*$Expected*") {
        throw "Expected rejection '$Expected', got '$goMessage'"
    }
}

function Invoke-GoChangedLeaf([string]$Relative, [scriptblock]$Change, [scriptblock]$Check) {
    $goPath = [IO.Path]::GetFullPath((Join-Path $goOutput $Relative))
    if (-not $goPath.StartsWith($goOutput + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
        throw 'Test path outside private payload'
    }
    $goOriginal = [IO.File]::ReadAllBytes($goPath)
    try { & $Change $goPath $goOriginal; & $Check $goPath }
    finally { [IO.File]::WriteAllBytes($goPath, $goOriginal) }
}

Invoke-GoCase 'complete payload verifies' { & $goPrepare -Mode Verify }
Invoke-GoCase 'repeat staging preserves deterministic manifest' {
    $goBefore = (Get-FileHash -LiteralPath (Join-Path $goOutput 'manifest.json')).Hash
    & $goPrepare -Mode Stage
    if ((Get-FileHash -LiteralPath (Join-Path $goOutput 'manifest.json')).Hash -ne $goBefore) {
        throw 'Non-deterministic manifest'
    }
}
Invoke-GoCase 'missing notice fails verification' {
    Invoke-GoChangedLeaf 'assets/games/go/legal/Eigen-COPYING.MPL2' {
        param($goPath, $goBytes) [IO.File]::Delete($goPath)
    } {
        Assert-GoRejected { & $goPrepare -Mode Verify } 'Staged file missing'
    }
}
Invoke-GoCase 'changed model rejected without overwriting it' {
    $goLock = Get-Content -LiteralPath (Join-Path $goProject 'config/games/go/katago-probe-lock.json') -Raw | ConvertFrom-Json
    Invoke-GoChangedLeaf ('assets/games/go/ai/' + $goLock.network.name + '.txt.gz') {
        param($goPath, $goBytes)
        $goChanged = [byte[]]$goBytes.Clone()
        $goChanged[0] = $goChanged[0] -bxor 1
        [IO.File]::WriteAllBytes($goPath, $goChanged)
    } {
        param($goPath)
        $goBefore = (Get-FileHash -LiteralPath $goPath).Hash
        Assert-GoRejected { & $goPrepare -Mode Stage } 'Staged bytes differ'
        if ((Get-FileHash -LiteralPath $goPath).Hash -ne $goBefore) { throw 'Changed file was overwritten' }
    }
}
Invoke-GoCase 'changed source rejected' {
    Invoke-GoChangedLeaf 'source/cpp/game/board.cpp' {
        param($goPath, $goBytes) [IO.File]::WriteAllBytes($goPath, [byte[]]@(0,1,2))
    } { Assert-GoRejected { & $goPrepare -Mode Stage } 'Staged bytes differ' }
}
Invoke-GoCase 'unexpected asset rejected' {
    $goExtra = Join-Path $goOutput 'unexpected-test-asset.bin'
    $goStream = [IO.File]::Open($goExtra, [IO.FileMode]::CreateNew)
    $goStream.Dispose()
    try { Assert-GoRejected { & $goPrepare -Mode Verify } 'Unexpected staged file' }
    finally { [IO.File]::Delete($goExtra) }
}
Invoke-GoCase 'production output path rejected' {
    Assert-GoRejected { & $goPrepare -OutputDirectory 'app/src/main/assets/games/go' } 'Output must be a dedicated'
}
$goSpec = Get-Content -LiteralPath (Join-Path $goProject 'config/games/go/katago-distribution-v1.json') -Raw | ConvertFrom-Json
$goFixture = Join-Path $goEvidence 'negative-selection.json'
Invoke-GoCase 'missing selected archive member rejected before staging' {
    $goSpec.sourceFiles += 'source/cpp/core/missing-distribution-test.hpp'
    [IO.File]::WriteAllText($goFixture, ($goSpec | ConvertTo-Json -Depth 8), [Text.UTF8Encoding]::new($false))
    Assert-GoRejected { & $goPrepare -Mode Verify -SelectionFile '.build/go-katago-distribution-check/negative-selection.json' } 'Required archive member is missing'
}
Invoke-GoCase 'source traversal rejected' {
    $goSpec.sourceFiles += 'source/../LICENSE'
    [IO.File]::WriteAllText($goFixture, ($goSpec | ConvertTo-Json -Depth 8), [Text.UTF8Encoding]::new($false))
    Assert-GoRejected { & $goPrepare -Mode Verify -SelectionFile '.build/go-katago-distribution-check/negative-selection.json' } 'Unsafe relative path'
}
& $goPrepare -Mode Verify
[IO.File]::WriteAllText((Join-Path $goEvidence 'results.json'), ($goResults | ConvertTo-Json -Depth 5), [Text.UTF8Encoding]::new($false))
Write-Output "Go dependency distribution: $($goResults.Count)/$($goResults.Count) checks passed; payload restored and verified"
