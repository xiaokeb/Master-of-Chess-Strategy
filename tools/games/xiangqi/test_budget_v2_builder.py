"""Builder parser/preflight tests only. No LLVM tool, ELF, ADB or search is run."""

import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

import budget_v2_admission as admission
import budget_v2_build_inputs as bridge
import budget_v2_build_evidence as evidence


ROOT = admission.ROOT
BUILDER = ROOT / "tools/games/xiangqi/build_budget_v2_entry.ps1"
PYTHON = Path("E:/Backend_Env/Python/miniconda3/envs/MasterofChessStrategy/python.exe")
PWSH = shutil.which("pwsh")


def quoted(value):
    return "'" + str(value).replace("'", "''") + "'"


class BuilderGuardTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        if not PWSH:
            raise RuntimeError("Existing PowerShell 7 is required, not a skipped test")
        cls.source = BUILDER.read_text(encoding="utf-8")
        cls.base = ROOT / ".build/xiangqi-budget-v2-builder-20261024/fixtures"
        cls.base.mkdir(parents=True, exist_ok=True)

    def setUp(self):
        # Retain these private evidence inputs and process logs, including failed runs.
        self.fixture = Path(tempfile.mkdtemp(prefix="preflight-", dir=self.base))
        self.output = ROOT / ".build/xiangqi-calibration-budget-v2-20261022" / ("builder-test-" + self.fixture.name)
        self.assertFalse(self.output.exists())
        self.args = dict(SpecPath=admission.SPEC_PATH,
                         SpecSha256=hashlib.sha256((ROOT / admission.SPEC_PATH).read_bytes()).hexdigest(),
                         ExposurePath=admission.EXPOSURE_PATH,
                         ExposureSha256=hashlib.sha256((ROOT / admission.EXPOSURE_PATH).read_bytes()).hexdigest(),
                         InputManifestPath="config/games/xiangqi/ai-evaluations/not-frozen-builder-test.json",
                         InputManifestSha256="0" * 64, NetworkPath="not-read-builder-test.nnue",
                         BuildDirectory=str(self.output), NdkDirectory="E:/Backend_Env/SDK/ndk/30.0.16138531")

    def invoke(self, **changes):
        arguments = dict(self.args, **changes)
        argv = [PWSH, "-NoProfile", "-NonInteractive", "-File", str(BUILDER)]
        for key, value in arguments.items():
            argv.extend(["-" + key, str(value)])
        result = subprocess.run(argv, cwd=ROOT, stdin=subprocess.DEVNULL,
                                capture_output=True, text=True, encoding="utf-8", timeout=60)
        (self.fixture / "process.json").write_text(json.dumps(dict(argv=argv, exit_code=result.returncode,
            stdout=result.stdout, stderr=result.stderr, native_executed=False), ensure_ascii=False, indent=2), encoding="utf-8")
        self.assertNotEqual(result.returncode, 0)
        self.assertFalse(self.output.exists(), "Preflight must not create build output")
        return result.stdout + result.stderr

    def powershell(self, command):
        argv = [PWSH, "-NoProfile", "-NonInteractive", "-Command", command]
        result = subprocess.run(argv, cwd=ROOT, stdin=subprocess.DEVNULL,
                                capture_output=True, text=True, encoding="utf-8", timeout=60)
        (self.fixture / "powershell-process.json").write_text(json.dumps(dict(argv=argv,
            exit_code=result.returncode, stdout=result.stdout, stderr=result.stderr, native_executed=False),
            ensure_ascii=False, indent=2), encoding="utf-8")
        self.assertEqual(result.returncode, 0, result.stderr)
        return result.stdout

    def parse_prelude(self):
        return ("$tokens=$null;$errors=$null;$ast=[Management.Automation.Language.Parser]::ParseFile(" +
                quoted(BUILDER) + ",[ref]$tokens,[ref]$errors);if($errors.Count){throw ($errors|Out-String)};")

    def test_powershell_ast_has_no_parse_errors(self):
        self.assertEqual(self.powershell(self.parse_prelude() + "'parsed'\n").strip(), "parsed")

    def test_old_five_source_spec_rejected_before_any_output_or_llvm(self):
        message = self.invoke()
        self.assertIn("preflight rejected before output creation", message)
        self.assertRegex(message, r"v2 frozen spec/source\s*(?:\|\s*)?closure differs")

    def test_missing_spec_rejected_without_creating_or_resigning_one(self):
        relative = (self.fixture / "missing-spec.json").relative_to(ROOT).as_posix()
        self.invoke(SpecPath=relative)
        self.assertFalse((ROOT / relative).exists())

    def test_wrong_external_spec_sha_is_rejected_before_output(self):
        self.assertIn("externally frozen", self.invoke(SpecSha256="0" * 64))

    def test_malformed_hash_rejected_by_parameter_contract_before_bridge(self):
        message = self.invoke(SpecSha256="not-a-sha")
        self.assertNotIn("offline-native-build-inputs-verified", message)

    def test_generated_flags_are_separate_arguments_including_paths_with_spaces(self):
        command = self.parse_prelude() + """
        $sysroot='fixture sysroot';$resource=@([pscustomobject]@{FullName='fixture resources'});
        $nativeInclude='fixture native include';$pikafishInclude='fixture PF include';
        $assignment=@($ast.FindAll({param($node) $node -is [Management.Automation.Language.AssignmentStatementAst] -and $node.Left.Extent.Text -eq '$generatedCompile'},$true));
        if($assignment.Count -ne 1){throw 'Unique generated option expression missing'};
        Invoke-Expression $assignment[0].Extent.Text;
        ConvertTo-Json -InputObject @($generatedCompile)
        """
        actual = json.loads(self.powershell(command))
        self.assertEqual(actual, ["--sysroot=fixture sysroot", "-resource-dir", "fixture resources", "--no-default-config", "-fPIC",
                                  "-I", "fixture native include", "-I", "fixture PF include"])

    def test_process_capture_preserves_argument_boundaries_and_both_streams(self):
        command = self.parse_prelude() + "$repoRoot=" + quoted(ROOT) + ";" + """
        $function=@($ast.FindAll({param($node) $node -is [Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq 'Capture-Process'},$true));
        if($function.Count -ne 1){throw 'Unique capture function missing'};
        Invoke-Expression $function[0].Extent.Text;
        """ + "$capture=Capture-Process " + quoted(PYTHON) + " " + """@('-B','-c','import sys;print(sys.argv[1]);print("err:"+sys.argv[1],file=sys.stderr);sys.exit(7)','a path with spaces');
        $capture|ConvertTo-Json -Depth 5
        """
        captured = json.loads(self.powershell(command))
        self.assertEqual(captured["exit_code"], 7)
        self.assertEqual(captured["stdout"].strip(), "a path with spaces")
        self.assertEqual(captured["stderr"].strip(), "err:a path with spaces")
        self.assertFalse(captured["timed_out"])
        self.assertEqual(captured["argv"][-1], "a path with spaces")

    def test_order_and_owned_tool_contract_do_not_reuse_old_library_or_execute_elf(self):
        self.assertLess(self.source.index("$preflight = Capture-Process"),
                        self.source.index("New-Item -ItemType Directory -Path $outputDirectory"))
        self.assertIn("$bridgeFrozenSha = 'b91a864164a0c909861ec4bd72f0bfbf23f3fcc6014910128e2af861f537b89a'", self.source)
        for forbidden in (".build/native-x86_64", "build_holdout_entry.ps1", "benchmark_xiangqi_ai_holdout_v1",
                          "mocs_go_katago", "--gc-sections", "adb.exe", "MinGW"):
            with self.subTest(forbidden=forbidden):
                self.assertNotIn(forbidden, self.source)
        self.assertIn("Invoke-RecordedTool 'llvm-ar.exe'", self.source)
        self.assertIn("Invoke-RecordedTool 'llvm-readelf.exe'", self.source)
        self.assertIn("$verifyArguments[2] = 'verify-after'", self.source)
        self.assertIn("[IO.FileMode]::CreateNew", self.source)
        self.assertNotIn("Capture-Process $binary", self.source)
        self.assertNotIn("& $binary", self.source)

    def test_base_options_are_consumed_from_frozen_manifest_not_silently_changed(self):
        self.assertIn("compile_options=$manifest.compile_options;link_options=$manifest.link_options", self.source)
        self.assertIn("if ($assembly)", self.source)
        self.assertIn("$_ -ne '-std=c++17'", self.source)
        self.assertIn("$manifest.core_sources", self.source)
        self.assertIn("$manifest.pikafish_sources", self.source)
        self.assertIn("$manifest.link_options", self.source)
        self.assertEqual(len(bridge.CORE_SOURCES), 4)
        self.assertEqual(len(bridge.PIKAFISH_SOURCES), 36)

    def helper_prelude(self, names):
        wanted = "@(" + ",".join(quoted(name) for name in names) + ")"
        return (self.parse_prelude() + "$repoRoot=" + quoted(ROOT) + ";$utf8=[Text.UTF8Encoding]::new($false);" +
                "$wanted=" + wanted + ";" + """
                $functions=@($ast.FindAll({param($node) $node -is [Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -in $wanted},$true));
                if($functions.Count -ne $wanted.Count){throw 'Expected helper definitions missing'};
                foreach($function in $functions){Invoke-Expression $function.Extent.Text};
                """)

    def test_read_reviewed_json_hashes_the_exact_decoded_bytes(self):
        data = ('{"text":"路径 with spaces"}\n').encode("utf-8")
        path = self.fixture / "reviewed.json"
        path.write_bytes(data)
        relative = path.relative_to(ROOT).as_posix()
        code = (self.helper_prelude(["Read-ReviewedJson"]) + "$facts=Read-ReviewedJson " + quoted(relative) + " " +
                quoted(hashlib.sha256(data).hexdigest()) + ";$facts|ConvertTo-Json -Depth 3")
        self.assertEqual(json.loads(self.powershell(code)), {"text": "路径 with spaces"})

    def test_reviewed_json_wrong_hash_and_oversize_are_cleanly_rejected(self):
        path = self.fixture / "oversize.json"
        path.write_bytes(b"x" * 4194305)
        code = (self.helper_prelude(["Read-ReviewedJson"]) + "$rejected=$false;try{Read-ReviewedJson " +
                quoted(path.relative_to(ROOT).as_posix()) + " " + quoted("0" * 64) +
                "|Out-Null}catch{$rejected=$_.Exception.Message -eq 'Reviewed JSON exceeds bound.'};" +
                "if(-not $rejected){throw 'Missing actual read bound'};'bounded'")
        self.assertEqual(self.powershell(code).strip(), "bounded")

    def test_generated_evidence_write_is_bom_free_and_exclusive(self):
        path = self.fixture / "exclusive.txt"
        code = (self.helper_prelude(["Write-NewText"]) + "Write-NewText " + quoted(path) + " '路径';" +
                "$rejected=$false;try{Write-NewText " + quoted(path) + " 'replacement'}catch{$rejected=$true};" +
                "if(-not $rejected){throw 'Existing evidence was overwritten'};'exclusive'")
        self.assertEqual(self.powershell(code).strip(), "exclusive")
        self.assertEqual(path.read_bytes(), "路径".encode("utf-8"))

    def test_process_start_failure_keeps_attempted_argv_and_failure_metadata(self):
        missing = self.fixture / "never-created-tool.exe"
        code = (self.helper_prelude(["Capture-Process"]) + "$capture=Capture-Process " + quoted(missing) +
                " @('--not-run');$capture|ConvertTo-Json -Depth 4")
        result = json.loads(self.powershell(code))
        self.assertEqual(result["exit_code"], -1)
        self.assertFalse(result["process_started"])
        self.assertEqual(result["argv"], ["--not-run"])
        self.assertIn("Owned process error:", result["stderr"])

    def test_read_lock_rejects_snapshot_mutation_until_the_owned_lock_is_released(self):
        path = self.fixture / "locked-source.cpp"
        original = b"frozen fixture; never compiled"
        path.write_bytes(original)
        code = (self.helper_prelude(["File-Sha", "Hold-Input"]) +
                "$heldFiles=[Collections.Generic.List[IO.FileStream]]::new();" +
                "Hold-Input " + quoted(path) + " " + quoted(hashlib.sha256(original).hexdigest()) + "|Out-Null;" +
                "$rejected=$false;try{[IO.File]::WriteAllText(" + quoted(path) +
                ",'changed')}catch{$rejected=$true};if(-not $rejected){throw 'Read lock allowed mutation'};" +
                "foreach($stream in $heldFiles){$stream.Dispose()};'locked'")
        self.assertEqual(self.powershell(code).strip(), "locked")
        self.assertEqual(original, path.read_bytes())


if __name__ == "__main__":
    unittest.main()
