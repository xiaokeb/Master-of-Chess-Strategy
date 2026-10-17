param(
    [string]$BuildDirectory = '.build/xiangqi-search-budget-safety',
    [string]$SdkDirectory = 'E:\Backend_Env\SDK',
    [string]$NdkVersion = '30.0.16138531'
)

# Root owns the serial native/AVD window. Never overwrite an earlier build or
# evaluation slot; this script does not start matches, clear data or run ADB.
$ErrorActionPreference = 'Stop'
$repoDirectory = (Resolve-Path (Join-Path $PSScriptRoot '../../..')).Path
$buildParent = [IO.Path]::GetFullPath((Join-Path $repoDirectory '.build'))
$budgetOutput = [IO.Path]::GetFullPath((Join-Path $repoDirectory $BuildDirectory))
if (-not $budgetOutput.StartsWith($buildParent + [IO.Path]::DirectorySeparatorChar,
        [StringComparison]::OrdinalIgnoreCase)) {
    throw 'Output must be a new child of the repository .build directory.'
}
if (Test-Path -LiteralPath $budgetOutput) { throw 'Refusing to overwrite an existing build.' }
foreach ($registeredJob in @(
    '.build/xiangqi-ai-calibration-v4r1-20261015/active-job.json',
    '.build/xiangqi-ai-holdout-v1-20261015/active-job.json'
)) {
    if (Test-Path -LiteralPath (Join-Path $repoDirectory $registeredJob)) {
        throw "Registered evaluation owns the native window: $registeredJob"
    }
}
$ndkDirectory = Join-Path $SdkDirectory "ndk/$NdkVersion"
$compiler = Join-Path $ndkDirectory 'toolchains/llvm/prebuilt/windows-x86_64/bin/clang++.exe'
$compilerSha = (Get-FileHash -LiteralPath $compiler).Hash.ToLowerInvariant()
if ($NdkVersion -ne '30.0.16138531' -or
    $compilerSha -ne '46f558e3cb379b11c8df34a2d32466d755cf5e19646d5ec9c2b79d95dc82c8be') {
    throw 'Expected NDK30 LLVM toolchain not found; no MinGW fallback.'
}
$cmake = Join-Path $SdkDirectory 'cmake/4.1.2/bin/cmake.exe'
$ninja = Join-Path $SdkDirectory 'cmake/4.1.2/bin/ninja.exe'
$targets = @('mocs_pikafish_budget_safety_test', 'mocs_pikafish_budget_runtime_test')
Push-Location $repoDirectory
try {
    # Include the complete vendored source/header closure and shared CMake inputs.
    $paths = @(& rg --files --hidden third_party/pikafish engine-native/src/main/cpp)
    if ($LASTEXITCODE -ne 0) { throw 'Cannot inventory native inputs.' }
    $paths += $MyInvocation.MyCommand.Path
    $sourceHashes = [ordered]@{}
    foreach ($path in ($paths | Sort-Object -Unique)) {
        # .NET's process working directory need not follow PowerShell Push-Location.
        $absoluteInput = [IO.Path]::GetFullPath((Join-Path $repoDirectory $path))
        $relative = [IO.Path]::GetRelativePath($repoDirectory, $absoluteInput).Replace('\', '/')
        $sourceHashes[$relative] = (Get-FileHash -LiteralPath $path).Hash.ToLowerInvariant()
    }
    New-Item -ItemType Directory -Path $budgetOutput | Out-Null
    $sourceHashes | ConvertTo-Json -Depth 4 |
        Set-Content -LiteralPath (Join-Path $budgetOutput 'source-before.json') -Encoding utf8
    foreach ($abi in @('x86_64', 'arm64-v8a')) {
        $abiOutput = Join-Path $budgetOutput $abi
        & $cmake -S engine-native/src/main/cpp -B $abiOutput -G Ninja `
            "-DCMAKE_MAKE_PROGRAM=$ninja" "-DCMAKE_TOOLCHAIN_FILE=$ndkDirectory/build/cmake/android.toolchain.cmake" `
            "-DANDROID_ABI=$abi" '-DANDROID_PLATFORM=android-24' '-DCMAKE_BUILD_TYPE=RelWithDebInfo' `
            '-DMOCS_BUILD_TESTS=ON' 2>&1 |
            Tee-Object -FilePath (Join-Path $budgetOutput "configure-$abi.log")
        if ($LASTEXITCODE -ne 0) { throw "CMake configuration failed: $abi" }
        & $cmake --build $abiOutput --target @targets -j 4 2>&1 |
            Tee-Object -FilePath (Join-Path $budgetOutput "build-$abi.log")
        if ($LASTEXITCODE -ne 0) { throw "NDK build failed: $abi" }
    }
    foreach ($path in $sourceHashes.Keys) {
        if ((Get-FileHash -LiteralPath $path).Hash.ToLowerInvariant() -ne $sourceHashes[$path]) {
            throw "Native source changed during the build: $path"
        }
    }
    $products = [ordered]@{}
    foreach ($abi in @('x86_64', 'arm64-v8a')) {
        foreach ($target in $targets) {
            $product = Join-Path $budgetOutput "$abi/tests/$target"
            $products["$abi/$target"] = (Get-FileHash -LiteralPath $product).Hash.ToLowerInvariant()
        }
    }
    [ordered]@{
        kind = 'xiangqi-search-budget-safety-build-v1'
        ended_utc = [DateTime]::UtcNow.ToString('o')
        compiler_sha256 = $compilerSha
        ndk_version = $NdkVersion
        android_api = 24
        source_sha256 = $sourceHashes
        products = $products
        executed = $false
    } | ConvertTo-Json -Depth 7 |
        Set-Content -LiteralPath (Join-Path $budgetOutput 'build-receipt.json') -Encoding utf8
    $products | ConvertTo-Json
} finally {
    Pop-Location
}
