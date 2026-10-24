"""Real source-copy boundary tests. Tool/NNUE bytes are fixtures, never executed."""

import copy
from pathlib import Path
import shutil
import tempfile
import unittest
from unittest.mock import patch

import budget_v2_admission as admission
import budget_v2_build_inputs as bridge


class InputBoundaryTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.real_spec = admission.make_spec(admission.ROOT)
        cls.real_sources = [admission.TOOL_PATH, bridge.ENTRY, bridge.BRIDGE, *bridge.BUILD_TOOLS]
        for group in cls.real_spec["source_groups"].values():
            cls.real_sources.extend(group)
        cls.real_sources.extend(name for name, _ in admission.EXPOSED)
        cls.base = admission.ROOT / ".build/xiangqi-budget-v2-entry-20261024/fixtures"
        cls.base.mkdir(parents=True, exist_ok=True)

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="boundary-", dir=self.base)
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        for name in set(self.real_sources):
            target = self.root / name
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(admission.ROOT / name, target)
        self.ndk = self.root / "fixture-ndk/30.0.16138531"
        self.package_bytes = (b"Pkg.Desc = Android NDK\r\nPkg.Revision = 30.0.16138531-beta3\r\n"
                              b"Pkg.BaseRevision = 30.0.16138531\r\nPkg.ReleaseName = r30-beta3\r\n")
        self.put(self.ndk / "source.properties", self.package_bytes)
        self.prebuilt = self.ndk / "toolchains/llvm/prebuilt/windows-x86_64"
        for name in bridge.TOOLS:
            self.put(self.prebuilt / "bin" / name, ("unexecuted fixture " + name).encode())
        self.put(self.prebuilt / "sysroot/usr/include/fixture.h", b"fixture sysroot header")
        self.put(self.prebuilt / "lib/clang/22/include/fixture.h", b"fixture compiler resource")
        self.put(self.root / "fixture.nnue", b"not a real neural network")
        self.addCleanup(patch.stopall)
        patch.object(admission, "CLANG_SHA", bridge.hash_file(self.prebuilt / "bin/clang++.exe")).start()
        patch.object(admission, "NETWORK_SHA", bridge.hash_file(self.root / "fixture.nnue")).start()
        self.spec_data = admission.encoded(admission.make_spec(self.root))
        self.exposure_data = admission.encoded(admission.exposure_manifest(self.root))
        self.put(self.root / "review/spec.json", self.spec_data)
        self.put(self.root / "review/exposure.json", self.exposure_data)
        self.manifest = bridge.manifest_for_review(self.root, admission.parse(self.spec_data), self.ndk)
        self.manifest_data = admission.encoded(self.manifest)
        self.put(self.root / "review/inputs.json", self.manifest_data)
        self.output = self.root / admission.parse(self.spec_data)["output_prefix"] / "compile-r1"
        self.args = dict(spec_path="review/spec.json", spec_sha256=admission.sha(self.spec_data),
                         exposure_path="review/exposure.json", exposure_sha256=admission.sha(self.exposure_data),
                         manifest_path="review/inputs.json", manifest_sha256=admission.sha(self.manifest_data),
                         network_path="fixture.nnue", build_directory=str(self.output), ndk_directory=str(self.ndk))

    @staticmethod
    def put(path, data):
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(data)

    def check(self, **changes):
        return bridge.check_inputs(self.root, **dict(self.args, **changes))

    def reject(self, **changes):
        with self.assertRaises((ValueError, OSError, TypeError, KeyError)):
            self.check(**changes)

    def change_manifest(self, mutate):
        changed = copy.deepcopy(self.manifest)
        mutate(changed)
        data = admission.encoded(changed)
        self.put(self.root / "review/inputs.json", data)
        self.args["manifest_sha256"] = admission.sha(data)

    def test_valid_real_source_copy_is_read_only_and_does_not_authorize_search(self):
        result = self.check()
        self.assertFalse(self.output.exists())
        self.assertFalse(result["native_executed"])
        self.assertFalse(result["search_authorized"])
        self.assertFalse(result["strength_accepted"])
        self.assertEqual(result["rule_identity"], admission.parse(self.spec_data)["rule_identity"])
        self.assertEqual(len(self.manifest["source_groups"]["rules"]), 6)
        self.assertIn("engine-native/src/main/cpp/games/xiangqi/checking_mate_proof.cpp",
                      self.manifest["core_sources"])
        self.assertEqual("30.0.16138531-beta3", self.manifest["toolchain"]["package_revision"])
        self.assertEqual("r30-beta3", self.manifest["toolchain"]["release_name"])

    def test_other_beta_suffix_free_and_mismatched_base_or_release_are_rejected(self):
        variants = [self.package_bytes.replace(b"30.0.16138531-beta3", b"30.0.16138531-beta2"),
                    self.package_bytes.replace(b"30.0.16138531-beta3", b"30.0.16138531"),
                    self.package_bytes.replace(b"Pkg.BaseRevision = 30.0.16138531", b"Pkg.BaseRevision = 30.0.1"),
                    self.package_bytes.replace(b"Pkg.ReleaseName = r30-beta3", b"Pkg.ReleaseName = r30")]
        for data in variants:
            with self.subTest(metadata=data):
                self.put(self.ndk / "source.properties", data)
                with self.assertRaisesRegex(ValueError, "NDK30 beta3 package metadata"):
                    bridge.toolchain_identity(self.ndk)
        self.assertFalse(self.output.exists())

    def test_duplicate_and_extra_package_keys_cannot_hide_a_different_revision(self):
        for extra in (b"Pkg.Revision = 30.0.16138531-beta3\n", b"Unexpected.Metadata = true\n"):
            with self.subTest(extra=extra):
                self.put(self.ndk / "source.properties", self.package_bytes + extra)
                with self.assertRaises(ValueError):
                    bridge.toolchain_identity(self.ndk)
        self.assertFalse(self.output.exists())

    def test_missing_reviewed_spec_does_not_make_or_write_one(self):
        self.reject(spec_path="review/not-created.json")
        self.assertFalse((self.root / "review/not-created.json").exists())
        self.assertFalse(self.output.exists())

    def test_old_five_source_spec_is_rejected_even_with_its_actual_sha(self):
        old = (admission.ROOT / admission.SPEC_PATH).read_bytes()
        self.put(self.root / "review/spec.json", old)
        self.reject(spec_sha256=admission.sha(old))

    def test_external_hashes_are_not_optional_or_self_reported(self):
        for field in ("spec_sha256", "exposure_sha256", "manifest_sha256"):
            with self.subTest(field=field):
                self.reject(**{field: "0" * 64})
                self.reject(**{field: "A" * 64})

    def test_missing_entry_is_rejected(self):
        (self.root / bridge.ENTRY).unlink()
        self.reject()

    def test_entry_change_rejects_previously_frozen_manifest(self):
        entry = self.root / bridge.ENTRY
        entry.write_bytes(entry.read_bytes() + b"\n// changed fixture\n")
        self.reject()

    def test_rule_proof_change_rejects_previously_frozen_spec(self):
        proof = self.root / "engine-native/src/main/cpp/games/xiangqi/checking_mate_proof.cpp"
        proof.write_bytes(proof.read_bytes().replace(b"checking_move_limit > 8", b"checking_move_limit > 7"))
        self.reject()

    def test_bridge_or_admission_change_rejects_frozen_inputs(self):
        for name in (bridge.BRIDGE, admission.TOOL_PATH):
            with self.subTest(name=name):
                path = self.root / name
                original = path.read_bytes()
                path.write_bytes(original + b"\n# changed fixture\n")
                self.reject()
                path.write_bytes(original)

    def test_builder_and_independent_evidence_tool_are_frozen_before_build(self):
        for name in bridge.BUILD_TOOLS:
            with self.subTest(name=name):
                path = self.root / name
                original = path.read_bytes()
                path.write_bytes(original + b"\n# changed tool fixture\n")
                self.reject()
                path.write_bytes(original)
        self.assertFalse(self.output.exists())

    def test_missing_each_manifest_field_and_unknown_field_are_rejected(self):
        original = self.manifest_data
        for key in [*self.manifest, "unknown"]:
            with self.subTest(key=key):
                self.put(self.root / "review/inputs.json", original)
                self.change_manifest(lambda value: value.update(unknown=True) if key == "unknown" else value.pop(key))
                self.reject()

    def test_changed_exposure_or_network_rejected(self):
        exposure = admission.parse(self.exposure_data)
        exposure["entries"].pop()
        altered = admission.encoded(exposure)
        self.put(self.root / "review/exposure.json", altered)
        self.reject(exposure_sha256=admission.sha(altered))
        self.put(self.root / "review/exposure.json", self.exposure_data)
        self.put(self.root / "fixture.nnue", b"different fixture")
        self.reject()

    def test_compiler_other_tool_sysroot_resource_and_ndk_revision_are_bound(self):
        paths = [self.prebuilt / "bin/clang++.exe", self.prebuilt / "bin/llvm-ar.exe",
                 self.prebuilt / "bin/ld.lld.exe", self.prebuilt / "sysroot/usr/include/fixture.h",
                 self.prebuilt / "lib/clang/22/include/fixture.h", self.ndk / "source.properties"]
        for path in paths:
            with self.subTest(path=path):
                original = path.read_bytes()
                path.write_bytes(original + b"changed")
                self.reject()
                path.write_bytes(original)

    def test_missing_proof_unit_old_libraries_and_unsafe_flags_are_rejected(self):
        for mutate in (lambda value: value["core_sources"].pop(1),
                       lambda value: value.update(libraries=[".build/native-x86_64/engine.a"]),
                       lambda value: value["compile_options"].append("-include=old.cpp"),
                       lambda value: value["link_options"].append("-Wl,--gc-sections")):
            self.change_manifest(mutate)
            self.reject()

    def test_duplicate_manifest_json_key_rejected_with_matching_raw_hash(self):
        duplicate = self.manifest_data.rstrip()[:-1] + b', "schema_version": 1}\n'
        self.put(self.root / "review/inputs.json", duplicate)
        self.reject(manifest_sha256=admission.sha(duplicate))

    def test_existing_output_or_escape_or_broad_directory_is_rejected(self):
        self.output.mkdir(parents=True)
        self.reject()
        for path in (self.root, self.root / ".build", self.output.parent,
                     self.root / ".build/native-x86_64/new", self.output / "nested"):
            with self.subTest(path=path):
                self.reject(build_directory=str(path))

    def test_relative_ndk_and_output_and_escaped_input_paths_are_rejected(self):
        self.reject(ndk_directory="fixture-ndk/30.0.16138531")
        self.reject(build_directory=".build/relative")
        for name in ("../spec.json", "review\\spec.json", str(self.root / "review/spec.json")):
            self.reject(spec_path=name)

    def test_output_reparse_parent_is_rejected_without_following(self):
        self.output.parent.mkdir(parents=True)
        # A platform lacking link permission still tests the deterministic lstat rejection.
        target = self.output.parent
        old = Path.lstat

        def details(path, *args, **kwargs):
            value = old(path, *args, **kwargs)
            if path == target:
                class Reparse:
                    st_file_attributes = 0x400

                    def __getattr__(self, name):
                        return getattr(value, name)
                return Reparse()
            return value

        with patch.object(Path, "lstat", details):
            self.reject()

    def receipt_fixture(self):
        self.output.mkdir(parents=True)
        names = {"engine.a": b"!<arch>\nunexecuted fixture core", "pikafish.a": b"!<arch>\nunexecuted fixture PF",
                 "recipe.json": b"fixture only; not an actual build recipe",
                 "build.log": b"fixture only; no compiler was executed"}
        elf = bytearray(64)
        elf[:7] = b"\x7fELF\x02\x01\x01"
        elf[18:20] = (62).to_bytes(2, "little")
        names["entry"] = bytes(elf)
        for name, data in names.items():
            self.put(self.output / name, data)

        def artifact(name):
            path = self.output / name
            return dict(path=path.relative_to(self.root).as_posix(), sha256=bridge.hash_file(path))

        spec = admission.parse(self.spec_data)
        return dict(schema_version=2, kind="xiangqi-budget-v2-build-receipt", protocol=admission.PROTOCOL,
                    spec_sha256=self.args["spec_sha256"], exposure_sha256=self.args["exposure_sha256"],
                    source_groups=spec["source_groups"], rule_identity=spec["rule_identity"],
                    search_source_identity=spec["search_source_identity"],
                    network=dict(path="fixture.nnue", sha256=admission.NETWORK_SHA),
                    toolchain=dict(spec["toolchain"], clang_path=str(self.prebuilt / "bin/clang++.exe")),
                    compile_options=bridge.COMPILE_OPTIONS, link_options=bridge.LINK_OPTIONS,
                    libraries=dict(engine_core=artifact("engine.a"), pikafish=artifact("pikafish.a")),
                    binary=artifact("entry"), build_recipe=artifact("recipe.json"), build_log=artifact("build.log"),
                    entry_sources=self.manifest["entry_sources"])

    def after(self, receipt):
        path = self.output / "receipt.json"
        self.put(path, admission.encoded(receipt))
        return bridge.verify_after(self.root, receipt_path=path.relative_to(self.root).as_posix(),
                                   receipt_sha256=bridge.hash_file(path), **self.args)

    def test_synthetic_receipt_binding_remains_offline_and_not_native_evidence(self):
        result = self.after(self.receipt_fixture())
        self.assertEqual(result["status"], "offline-artifact-bindings-verified")
        self.assertFalse(result["native_executed"])
        self.assertFalse(result["search_authorized"])
        self.assertFalse(result["strength_accepted"])
        self.assertEqual(result["input_manifest_sha256"], self.args["manifest_sha256"])

    def test_after_rechecks_real_source_bytes_instead_of_trusting_before_result(self):
        self.check()
        receipt = self.receipt_fixture()
        proof = self.root / bridge.CORE_SOURCES[1]
        proof.write_bytes(proof.read_bytes() + b"\n// fixture changed during build\n")
        with self.assertRaises(ValueError):
            self.after(receipt)

    def test_receipt_cannot_mix_outputs_from_a_different_or_old_directory(self):
        receipt = self.receipt_fixture()
        for name in (".build/native-x86_64/pikafish.a",
                     admission.parse(self.spec_data)["output_prefix"] + "another-build/pikafish.a"):
            changed = copy.deepcopy(receipt)
            changed["libraries"]["pikafish"]["path"] = name
            with self.subTest(name=name), self.assertRaises(ValueError):
                self.after(changed)

    def test_receipt_must_bind_exact_entry_network_and_flags(self):
        receipt = self.receipt_fixture()
        for mutate in (lambda value: value["entry_sources"].update(extra="0" * 64),
                       lambda value: value["network"].update(path="another.nnue"),
                       lambda value: value["compile_options"].append("-include=old.cpp"),
                       lambda value: value["link_options"].append("old.a")):
            changed = copy.deepcopy(receipt)
            mutate(changed)
            with self.assertRaises(ValueError):
                self.after(changed)

    def test_changed_binary_or_library_cannot_reuse_receipt(self):
        receipt = self.receipt_fixture()
        for name in ("entry", "engine.a", "pikafish.a"):
            path = self.output / name
            original = path.read_bytes()
            path.write_bytes(original + b"changed")
            with self.subTest(name=name), self.assertRaises(ValueError):
                self.after(receipt)
            path.write_bytes(original)

    def test_receipt_arrays_and_non_map_libraries_are_cleanly_rejected(self):
        receipt = self.receipt_fixture()
        for value in ([], dict(receipt, libraries=[]), dict(receipt, libraries=None)):
            with self.subTest(value=value), self.assertRaises(ValueError):
                self.after(value)

    def test_frozen_json_actual_read_is_bounded(self):
        path = self.root / "review/oversize.json"
        self.put(path, b"x" * 17)
        with self.assertRaisesRegex(ValueError, "bound"):
            bridge.frozen_file(self.root, "review/oversize.json", admission.sha(path.read_bytes()), maximum=16)


if __name__ == "__main__":
    unittest.main()
