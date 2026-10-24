param(
    [Parameter(Mandatory)][string]$SpecPath,
    [Parameter(Mandatory)][ValidatePattern('^[0-9a-f]{64}$')][string]$SpecSha256,
    [Parameter(Mandatory)][string]$ExposurePath,
    [Parameter(Mandatory)][ValidatePattern('^[0-9a-f]{64}$')][string]$ExposureSha256,
    [Parameter(Mandatory)][string]$InputManifestPath,
    [Parameter(Mandatory)][ValidatePattern('^[0-9a-f]{64}$')][string]$InputManifestSha256,
    [Parameter(Mandatory)][string]$NetworkPath,
    [Parameter(Mandatory)][string]$BuildDirectory,
    [Parameter(Mandatory)][string]$NdkDirectory
)

# Root must own the serial native-build window. This script never executes its ELF.
$ErrorActionPreference = 'Stop'
$repoRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../..'))
$python = 'E:/Backend_Env/Python/miniconda3/envs/MasterofChessStrategy/python.exe'
$bridgeRelative = 'tools/games/xiangqi/budget_v2_build_inputs.py'
$bridgeFile = Join-Path $repoRoot $bridgeRelative
$builderFile = $PSCommandPath
$bridgeFrozenSha = 'b91a864164a0c909861ec4bd72f0bfbf23f3fcc6014910128e2af861f537b89a'
$evidenceRelative = 'tools/games/xiangqi/budget_v2_build_evidence.py'
$evidenceFile = Join-Path $repoRoot $evidenceRelative
$utf8 = [Text.UTF8Encoding]::new($false)

function File-Sha([string]$Path) {
    return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
}
function Read-ReviewedJson([string]$Relative, [string]$ExpectedSha) {
    $stream = [IO.File]::OpenRead((Join-Path $repoRoot $Relative))
    $memory = [IO.MemoryStream]::new()
    try {
        $buffer = [byte[]]::new(65536)
        while ($memory.Length -le 4194304) {
            $count = $stream.Read($buffer, 0, [Math]::Min($buffer.Length, 4194305 - $memory.Length))
            if ($count -eq 0) { break }
            $memory.Write($buffer, 0, $count)
        }
        if ($memory.Length -gt 4194304) { throw 'Reviewed JSON exceeds bound.' }
        $bytes = $memory.ToArray()
    } finally { $stream.Dispose(); $memory.Dispose() }
    $sha = [Security.Cryptography.SHA256]::HashData($bytes)
    if ([Convert]::ToHexString($sha).ToLowerInvariant() -ne $ExpectedSha) { throw 'Reviewed JSON bytes changed.' }
    return ($utf8.GetString($bytes) | ConvertFrom-Json -AsHashtable)
}
function Relative-Path([string]$Path) {
    $relative = [IO.Path]::GetRelativePath($repoRoot, [IO.Path]::GetFullPath($Path)).Replace('\', '/')
    if ($relative.StartsWith('../') -or [IO.Path]::IsPathRooted($relative)) { throw 'Artifact outside repository.' }
    return $relative
}
function Write-NewText([string]$Path, [string]$Text) {
    $stream = [IO.File]::Open($Path, [IO.FileMode]::CreateNew, [IO.FileAccess]::Write, [IO.FileShare]::None)
    try {
        $bytes = $utf8.GetBytes($Text)
        $stream.Write($bytes, 0, $bytes.Length)
    } finally { $stream.Dispose() }
}
function Write-NewJson([string]$Path, $Value) {
    Write-NewText $Path (($Value | ConvertTo-Json -Depth 100) + "`n")
}
function Artifact([string]$Path) {
    return @{path=(Relative-Path $Path);sha256=(File-Sha $Path)}
}
function Capture-Process([string]$Executable, [string[]]$Arguments) {
    $start = [Diagnostics.ProcessStartInfo]::new()
    $start.FileName = $Executable
    $start.WorkingDirectory = $repoRoot
    $start.UseShellExecute = $false
    $start.CreateNoWindow = $true
    $start.RedirectStandardInput = $true
    $start.RedirectStandardOutput = $true
    $start.RedirectStandardError = $true
    # Do not inherit another compiler installation's implicit include/library inputs.
    foreach ($name in @('CPATH','C_INCLUDE_PATH','CPLUS_INCLUDE_PATH','OBJC_INCLUDE_PATH',
        'LIBRARY_PATH','COMPILER_PATH','GCC_EXEC_PREFIX','CLANG_CONFIG_FILE_SYSTEM_DIR','CLANG_CONFIG_FILE_USER_DIR')) {
        [void]$start.Environment.Remove($name)
    }
    foreach ($argument in $Arguments) { $start.ArgumentList.Add($argument) }
    $process = [Diagnostics.Process]::new()
    $process.StartInfo = $start
    $began = [DateTime]::UtcNow.ToString('o')
    $started = $false
    try {
        $started = $process.Start()
        if (-not $started) { throw 'Cannot start owned build/check process.' }
        $process.StandardInput.Close() # Every child is a noninteractive compiler/checker.
        $stdout = $process.StandardOutput.ReadToEndAsync()
        $stderr = $process.StandardError.ReadToEndAsync()
        $timedOut = -not $process.WaitForExit(900000)
        if ($timedOut) { $process.Kill($true); $process.WaitForExit() }
        return @{executable=$Executable;argv=@($Arguments);cwd=$repoRoot;started_utc=$began;
            ended_utc=[DateTime]::UtcNow.ToString('o');exit_code=$process.ExitCode;
            process_started=$true;timed_out=$timedOut;
            stdout=$stdout.GetAwaiter().GetResult();stderr=$stderr.GetAwaiter().GetResult()}
    } catch {
        $failure = $_.Exception.Message
        if ($started) {
            try { if (-not $process.HasExited) { $process.Kill($true); $process.WaitForExit() } } catch { }
        }
        return @{executable=$Executable;argv=@($Arguments);cwd=$repoRoot;started_utc=$began;
            ended_utc=[DateTime]::UtcNow.ToString('o');exit_code=-1;process_started=$started;
            timed_out=$false;stdout='';stderr=('Owned process error: ' + $failure)}
    } finally { $process.Dispose() }
}

if (-not (Test-Path -LiteralPath $python -PathType Leaf)) { throw 'The project Conda interpreter is required.' }
if ((File-Sha $bridgeFile) -ne $bridgeFrozenSha) { throw 'Frozen bridge changed; do not silently re-sign it.' }
$builderSha = File-Sha $builderFile
$pythonSha = File-Sha $python
$bridgeArgs = @('-B', $bridgeFile, 'check', '--spec-path', $SpecPath, '--spec-sha256', $SpecSha256,
    '--exposure-path', $ExposurePath, '--exposure-sha256', $ExposureSha256,
    '--manifest-path', $InputManifestPath, '--manifest-sha256', $InputManifestSha256,
    '--network-path', $NetworkPath, '--build-directory', $BuildDirectory, '--ndk-directory', $NdkDirectory)
$preflight = Capture-Process $python $bridgeArgs
if ($preflight.exit_code -ne 0 -or $preflight.timed_out) {
    throw "Native input preflight rejected before output creation: $($preflight.stderr.Trim())"
}
$checked = $preflight.stdout | ConvertFrom-Json -AsHashtable
if ($checked.status -ne 'offline-native-build-inputs-verified' -or $checked.native_executed -or
    $checked.search_authorized -or $checked.strength_accepted) { throw 'Unexpected input admission result.' }

# No output, compiler, archiver or linker is touched before the real bridge succeeds.
$spec = Read-ReviewedJson $SpecPath $SpecSha256
$manifest = Read-ReviewedJson $InputManifestPath $InputManifestSha256
$outputDirectory = [IO.Path]::GetFullPath((Join-Path $repoRoot $checked.output_directory))
if ((File-Sha (Join-Path $repoRoot $SpecPath)) -ne $SpecSha256 -or
    (File-Sha (Join-Path $repoRoot $InputManifestPath)) -ne $InputManifestSha256) { throw 'Frozen input changed after preflight.' }
if (Test-Path -LiteralPath $outputDirectory) { throw 'Never reuse or overwrite build output.' }
New-Item -ItemType Directory -Path $outputDirectory -ErrorAction Stop | Out-Null
$owner = $null
$commands = [Collections.Generic.List[object]]::new()
$objects = [Collections.Generic.List[object]]::new()
$heldFiles = [Collections.Generic.List[IO.FileStream]]::new()
function Hold-Input([string]$Path, [string]$ExpectedSha = '') {
    $stream = [IO.File]::Open($Path, [IO.FileMode]::Open, [IO.FileAccess]::Read, [IO.FileShare]::Read)
    $heldFiles.Add($stream)
    $actual = File-Sha $Path
    if ($ExpectedSha -and $actual -ne $ExpectedSha) { throw 'Frozen input changed before its read lock.' }
    return $actual
}
try {
    $owner = [IO.File]::Open((Join-Path $outputDirectory 'owner.lock'), [IO.FileMode]::CreateNew,
        [IO.FileAccess]::Write, [IO.FileShare]::None)
    foreach ($child in @('objects', 'objects/core', 'objects/pikafish', 'logs', 'source')) {
        New-Item -ItemType Directory -Path (Join-Path $outputDirectory $child) -ErrorAction Stop | Out-Null
    }
    Write-NewJson (Join-Path $outputDirectory 'preflight.json') $checked
    Write-NewJson (Join-Path $outputDirectory 'preflight-process.json') $preflight
    $toolchain = $manifest.toolchain
    foreach ($binding in $toolchain.tools.Values) { Hold-Input $binding.path $binding.sha256 | Out-Null }
    Hold-Input (Join-Path $toolchain.ndk_path 'source.properties') $toolchain.source_properties_sha256 | Out-Null
    foreach ($name in $manifest.build_tools.Keys) { Hold-Input (Join-Path $repoRoot $name) $manifest.build_tools[$name] | Out-Null }
    Hold-Input $bridgeFile $bridgeFrozenSha | Out-Null
    Hold-Input (Join-Path $repoRoot 'tools/games/xiangqi/budget_v2_admission.py') $manifest.admission_tool_sha256 | Out-Null
    Hold-Input (Join-Path $repoRoot $SpecPath) $SpecSha256 | Out-Null
    Hold-Input (Join-Path $repoRoot $InputManifestPath) $InputManifestSha256 | Out-Null
    Hold-Input (Join-Path $repoRoot $ExposurePath) $ExposureSha256 | Out-Null
    Hold-Input $python $pythonSha | Out-Null
    $sourceRoot = Join-Path $outputDirectory 'source'
    $frozenSources = [ordered]@{}
    foreach ($group in $spec.source_groups.Values) {
        foreach ($name in $group.Keys) { $frozenSources[$name] = $group[$name] }
    }
    foreach ($name in $manifest.entry_sources.Keys) { $frozenSources[$name] = $manifest.entry_sources[$name] }
    $snapshot = [ordered]@{}
    foreach ($name in @($frozenSources.Keys | Sort-Object)) {
        $original = Join-Path $repoRoot $name
        Hold-Input $original $frozenSources[$name] | Out-Null
        $copy = Join-Path $sourceRoot $name
        New-Item -ItemType Directory -Path ([IO.Path]::GetDirectoryName($copy)) -Force | Out-Null
        $read = [IO.File]::OpenRead($original)
        $write = [IO.File]::Open($copy, [IO.FileMode]::CreateNew, [IO.FileAccess]::Write, [IO.FileShare]::None)
        try { $read.CopyTo($write) } finally { $write.Dispose(); $read.Dispose() }
        Hold-Input $copy $frozenSources[$name] | Out-Null
        $snapshot[$name] = Artifact $copy
    }
    $snapshotPath = Join-Path $outputDirectory 'source-snapshot.json'
    Write-NewJson $snapshotPath $snapshot
    Hold-Input $snapshotPath | Out-Null
    $prebuilt = Join-Path $toolchain.ndk_path 'toolchains/llvm/prebuilt/windows-x86_64'
    $sysroot = Join-Path $prebuilt 'sysroot'
    $resource = @(Get-ChildItem -LiteralPath (Join-Path $prebuilt 'lib/clang') -Directory)
    if ($resource.Count -ne 1) { throw 'Clang resource directory changed.' }
    $nativeInclude = Join-Path $sourceRoot 'engine-native/src/main/cpp/include'
    $pikafishInclude = Join-Path $sourceRoot 'third_party/pikafish/src'
    $generatedCompile = @(('--sysroot=' + $sysroot), '-resource-dir', $resource[0].FullName,
        '--no-default-config', '-fPIC', '-I', $nativeInclude, '-I', $pikafishInclude)

    # Expanded inventories supplement the frozen tree identities; they are not a new freeze.
    $ndkFiles = [ordered]@{}
    foreach ($tree in @($sysroot, $resource[0].FullName)) {
        foreach ($file in (Get-ChildItem -LiteralPath $tree -Recurse -File | Sort-Object FullName)) {
            $ndkFiles[$file.FullName] = Hold-Input $file.FullName
        }
    }
    Write-NewJson (Join-Path $outputDirectory 'ndk-inputs.json') $ndkFiles
    Hold-Input (Join-Path $outputDirectory 'ndk-inputs.json') | Out-Null
    $evidenceArgs = @('-B', $evidenceFile, 'check-snapshot', '--spec-path', $SpecPath,
        '--spec-sha256', $SpecSha256, '--manifest-path', $InputManifestPath,
        '--manifest-sha256', $InputManifestSha256, '--build-directory', $outputDirectory)
    $snapshotCheck = Capture-Process $python $evidenceArgs
    Write-NewJson (Join-Path $outputDirectory 'snapshot-verification-process.json') $snapshotCheck
    if ($snapshotCheck.exit_code -ne 0 -or $snapshotCheck.timed_out) { throw 'Frozen snapshot/SDK inventory rejected before LLVM.' }

    function Invoke-RecordedTool([string]$Tool, [string[]]$ToolArguments, [string[]]$Inputs,
                                 [string[]]$Outputs, [string]$Label) {
        $binding = $toolchain.tools[$Tool]
        if ($null -eq $binding -or (File-Sha $binding.path) -ne $binding.sha256) { throw 'Explicit LLVM tool changed.' }
        $inputHashes = [ordered]@{}
        foreach ($inputPath in $Inputs) { $inputHashes[(Relative-Path $inputPath)] = File-Sha $inputPath }
        foreach ($target in $Outputs) { if (Test-Path -LiteralPath $target) { throw 'Owned output already exists.' } }
        $result = Capture-Process $binding.path $ToolArguments
        $number = $commands.Count.ToString('D3')
        $outLog = Join-Path $outputDirectory "logs/$number-$Label.stdout.log"
        $errLog = Join-Path $outputDirectory "logs/$number-$Label.stderr.log"
        Write-NewText $outLog $result.stdout
        Write-NewText $errLog $result.stderr
        $outputHashes = [ordered]@{}
        foreach ($target in $Outputs) {
            $outputHashes[(Relative-Path $target)] = if (Test-Path -LiteralPath $target -PathType Leaf) { File-Sha $target } else { $null }
            if ($null -ne $outputHashes[(Relative-Path $target)]) { Hold-Input $target $outputHashes[(Relative-Path $target)] | Out-Null }
        }
        $record = @{tool=$Tool;tool_sha256=$binding.sha256;executable=$result.executable;
            argv=$result.argv;cwd=$result.cwd;started_utc=$result.started_utc;ended_utc=$result.ended_utc;
            exit_code=$result.exit_code;process_started=$result.process_started;
            timed_out=$result.timed_out;input_sha256=$inputHashes;
            frozen_input_manifest_sha256=$InputManifestSha256;output_sha256=$outputHashes;
            stdout=(Artifact $outLog);stderr=(Artifact $errLog)}
        $commands.Add($record)
        Write-NewJson (Join-Path $outputDirectory "logs/$number-$Label.command.json") $record
        if ($result.exit_code -ne 0 -or $result.timed_out -or @($outputHashes.Values | Where-Object { $null -eq $_ }).Count) {
            throw "Owned LLVM command failed: $Label"
        }
    }

    $archives = [ordered]@{}
    foreach ($group in @('core', 'pikafish')) {
        $sourceNames = if ($group -eq 'core') { $manifest.core_sources } else { $manifest.pikafish_sources }
        $groupObjects = [Collections.Generic.List[string]]::new()
        $index = 0
        foreach ($sourceName in $sourceNames) {
            $source = Join-Path $sourceRoot $sourceName
            $object = Join-Path $outputDirectory ('objects/' + $group + '/' + $index.ToString('D3') + '.o')
            $dependencies = $object + '.d'
            $assembly = $sourceName.EndsWith('.S', [StringComparison]::Ordinal)
            # C++-only switches are deliberately not passed to the assembler driver.
            $options = if ($assembly) { @($manifest.compile_options | Where-Object { $_ -ne '-std=c++17' }) }
                       else { @($manifest.compile_options) }
            $argv = @($options) + $generatedCompile + @('-MMD', '-MF', $dependencies, '-c', $source, '-o', $object)
            $tool = if ($assembly) { 'clang.exe' } else { 'clang++.exe' }
            Invoke-RecordedTool $tool $argv @($source) @($object, $dependencies) "$group-$($index.ToString('D3'))"
            $groupObjects.Add($object)
            $objects.Add(@{source=$sourceName;snapshot_source=(Relative-Path $source);object=(Artifact $object);dependencies=(Artifact $dependencies);assembly=$assembly})
            $index++
        }
        $archive = Join-Path $outputDirectory "libmocs_budget_v2_$group.a"
        Invoke-RecordedTool 'llvm-ar.exe' (@('rcs', $archive) + $groupObjects.ToArray()) $groupObjects.ToArray() @($archive) "archive-$group"
        $archives[$group] = $archive
    }

    $entryName = 'engine-native/src/main/cpp/games/xiangqi/budget_v2_entry.cpp'
    $entry = Join-Path $sourceRoot $entryName
    $entryObject = Join-Path $outputDirectory 'objects/budget_v2_entry.o'
    $entryDependencies = $entryObject + '.d'
    $bindings = [ordered]@{
        MOCS_BUDGET_V2_PROTOCOL=$spec.protocol;MOCS_BUDGET_V2_RULE_IDENTITY=$spec.rule_identity;
        MOCS_BUDGET_V2_SEARCH_IDENTITY=$spec.search_source_identity;MOCS_BUDGET_V2_SPEC_SHA256=$SpecSha256;
        MOCS_BUDGET_V2_EXPOSURE_SHA256=$ExposureSha256
    }
    $defines = @($bindings.Keys | ForEach-Object { '-D' + $_ + '="' + $bindings[$_] + '"' })
    $entryArguments = @($manifest.compile_options) + $generatedCompile + $defines +
        @('-MMD', '-MF', $entryDependencies, '-c', $entry, '-o', $entryObject)
    Invoke-RecordedTool 'clang++.exe' $entryArguments @($entry) @($entryObject, $entryDependencies) 'entry'
    $objects.Add(@{source=$entryName;snapshot_source=(Relative-Path $entry);object=(Artifact $entryObject);dependencies=(Artifact $entryDependencies);assembly=$false})
    $binary = Join-Path $outputDirectory 'mocs_xiangqi_budget_v2_entry'
    $linkMap = Join-Path $outputDirectory 'entry.link.map'
    $linkArguments = [Collections.Generic.List[string]]::new()
    foreach ($flag in $manifest.link_options) {
        $linkArguments.Add($flag)
        if ($flag -eq '-static-libstdc++') { $linkArguments.Add($entryObject) }
        if ($flag -eq '-Wl,--whole-archive') {
            $linkArguments.Add($archives.core)
            $linkArguments.Add($archives.pikafish)
        }
    }
    $linkArguments.Add('--sysroot=' + $sysroot)
    $linkArguments.Add('--no-default-config')
    $linkArguments.Add('--ld-path=' + $toolchain.tools['ld.lld.exe'].path)
    $linkArguments.Add('-Wl,-Map=' + $linkMap)
    $linkArguments.Add('-Wl,--no-undefined')
    $linkArguments.Add('-o'); $linkArguments.Add($binary)
    Invoke-RecordedTool 'clang++.exe' $linkArguments.ToArray() @($entryObject, $archives.core, $archives.pikafish) @($binary, $linkMap) 'link'
    Invoke-RecordedTool 'llvm-readelf.exe' @('--file-header', '--dynamic', $binary) @($binary) @() 'elf-inspect'
    $header = [byte[]]::new(64)
    $elf = [IO.File]::OpenRead($binary)
    try { if ($elf.Read($header, 0, 64) -ne 64) { throw 'Truncated ELF.' } } finally { $elf.Dispose() }
    if ($header[0] -ne 127 -or $header[1] -ne 69 -or $header[2] -ne 76 -or $header[3] -ne 70 -or
        $header[4] -ne 2 -or $header[5] -ne 1 -or $header[6] -ne 1 -or $header[18] -ne 62 -or $header[19] -ne 0) {
        throw 'Expected x86_64 little-endian ELF64.'
    }
    if ((File-Sha $builderFile) -ne $builderSha -or (File-Sha $bridgeFile) -ne $bridgeFrozenSha -or
        (File-Sha $python) -ne $pythonSha) { throw 'Builder, bridge or Conda interpreter changed.' }
    Write-NewJson (Join-Path $outputDirectory 'objects.json') $objects.ToArray()
    $dependencyArguments = @($evidenceArgs); $dependencyArguments[2] = 'dependencies'
    $dependencyCheck = Capture-Process $python $dependencyArguments
    Write-NewJson (Join-Path $outputDirectory 'dependency-verification-process.json') $dependencyCheck
    if ($dependencyCheck.exit_code -ne 0 -or $dependencyCheck.timed_out) { throw 'Compiler dependency closure rejected.' }
    $dependencyPath = Join-Path $outputDirectory 'dependency-inventory.json'
    Write-NewText $dependencyPath $dependencyCheck.stdout

    $recipe = @{schema_version=1;kind='xiangqi-budget-v2-actual-build-recipe';protocol=$spec.protocol;
        builder=@{path=(Relative-Path $builderFile);sha256=$builderSha};bridge=@{path=$bridgeRelative;sha256=$bridgeFrozenSha};
        python=@{path=$python;sha256=$pythonSha};spec=@{path=$SpecPath;sha256=$SpecSha256};
        exposure=@{path=$ExposurePath;sha256=$ExposureSha256};input_manifest=@{path=$InputManifestPath;sha256=$InputManifestSha256};
        network=$checked.network;source_groups=$spec.source_groups;entry_sources=$manifest.entry_sources;
        source_snapshot_inventory=(Artifact $snapshotPath);dependency_inventory=(Artifact $dependencyPath);
        toolchain=$toolchain;ndk_input_inventory=(Artifact (Join-Path $outputDirectory 'ndk-inputs.json'));
        compile_options=$manifest.compile_options;link_options=$manifest.link_options;generated_compile_options=$generatedCompile;
        entry_defines=$defines;objects=$objects.ToArray();commands=$commands.ToArray();link_map=(Artifact $linkMap);
        native_executed=$false;search_performed=$false;strength_accepted=$false}
    $recipePath = Join-Path $outputDirectory 'build-recipe.json'
    $logPath = Join-Path $outputDirectory 'build-log.json'
    Write-NewJson $recipePath $recipe
    Write-NewJson $logPath @{commands=$commands.ToArray();all_commands_succeeded=$true;native_executed=$false;search_performed=$false}
    $receiptPath = Join-Path $outputDirectory 'native-build-receipt.json'
    $receipt = @{schema_version=2;kind='xiangqi-budget-v2-build-receipt';protocol=$spec.protocol;
        spec_sha256=$SpecSha256;exposure_sha256=$ExposureSha256;source_groups=$spec.source_groups;
        rule_identity=$spec.rule_identity;search_source_identity=$spec.search_source_identity;network=$checked.network;
        toolchain=@{ndk_version=$spec.toolchain.ndk_version;target=$spec.toolchain.target;
            clang_sha256=$spec.toolchain.clang_sha256;clang_path=$toolchain.tools['clang++.exe'].path};
        compile_options=$manifest.compile_options;link_options=$manifest.link_options;
        libraries=@{engine_core=(Artifact $archives.core);pikafish=(Artifact $archives.pikafish)};
        binary=(Artifact $binary);build_recipe=(Artifact $recipePath);build_log=(Artifact $logPath);entry_sources=$manifest.entry_sources}
    Write-NewJson $receiptPath $receipt
    $receiptSha = File-Sha $receiptPath
    $actualEvidenceArguments = @($evidenceArgs); $actualEvidenceArguments[2] = 'verify'
    $actualEvidenceArguments += @('--receipt-path', (Relative-Path $receiptPath), '--receipt-sha256', $receiptSha)
    $actualEvidence = Capture-Process $python $actualEvidenceArguments
    Write-NewJson (Join-Path $outputDirectory 'actual-build-verification-process.json') $actualEvidence
    if ($actualEvidence.exit_code -ne 0 -or $actualEvidence.timed_out) { throw 'Independent actual command/evidence verification failed.' }
    $actualResult = $actualEvidence.stdout | ConvertFrom-Json -AsHashtable
    if ($actualResult.status -ne 'actual-frozen-build-evidence-verified') { throw 'Unexpected independent build result.' }
    $verifyArguments = @($bridgeArgs)
    $verifyArguments[2] = 'verify-after'
    $verifyArguments += @('--receipt-path', (Relative-Path $receiptPath), '--receipt-sha256', $receiptSha)
    $verified = Capture-Process $python $verifyArguments
    Write-NewJson (Join-Path $outputDirectory 'verification-process.json') $verified
    if ($verified.exit_code -ne 0 -or $verified.timed_out) { throw 'Post-build full input/receipt verification failed; preserve evidence.' }
    $verification = $verified.stdout | ConvertFrom-Json -AsHashtable
    if ($verification.status -ne 'offline-artifact-bindings-verified' -or $verification.native_executed -or
        $verification.search_authorized -or $verification.strength_accepted) { throw 'Unexpected post-build admission result.' }
    if ((File-Sha $builderFile) -ne $builderSha -or (File-Sha $python) -ne $pythonSha) { throw 'Build driver changed during final verification.' }
    Write-NewJson (Join-Path $outputDirectory 'verification.json') $verification
    $completion = @{status='x86_64-linked-not-executed';receipt=(Artifact $receiptPath);
        verification=(Artifact (Join-Path $outputDirectory 'verification.json'));
        native_executed=$false;search_performed=$false;strength_accepted=$false;ended_utc=[DateTime]::UtcNow.ToString('o')}
    Write-NewJson (Join-Path $outputDirectory 'complete.json') $completion
    $completion | ConvertTo-Json -Depth 5
} catch {
    $failureFile = Join-Path $outputDirectory 'failure.json'
    if (-not (Test-Path -LiteralPath $failureFile)) {
        Write-NewJson $failureFile @{status='build-or-verification-failed';error=$_.Exception.Message;
            commands=$commands.ToArray();native_executed=$false;search_performed=$false;
            ended_utc=[DateTime]::UtcNow.ToString('o')}
    }
    throw
} finally {
    foreach ($stream in $heldFiles) { $stream.Dispose() }
    if ($null -ne $owner) { $owner.Dispose() }
}
