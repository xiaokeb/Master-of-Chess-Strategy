"""Standard-library tests. Toy receipt fixtures do not attest any Native build."""

import copy
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import budget_v2_admission as b


def temp_base():
    # Avoid Windows AppContainer temp ACLs; every fixture owns a unique child.
    path = b.ROOT / ".build/xiangqi-budget-v2-host-tests-20261022"
    path.mkdir(parents=True, exist_ok=True)
    return path


class ExposureTests(unittest.TestCase):
    def setUp(self):
        self.manifest = b.exposure_manifest(b.ROOT)

    def test_frozen_baseline_is_exactly_114_mirror_classes(self):
        self.assertEqual(self.manifest["count"], 114)
        self.assertEqual(len(set(e["canonical_fen"] for e in self.manifest["entries"])), 114)
        self.assertEqual([s["count"] for s in self.manifest["sources"]], [66, 48])
        self.assertEqual(b.verify_exposure(b.ROOT, (b.ROOT / b.EXPOSURE_PATH).read_bytes()),
                         b.sha(b.encoded(self.manifest)))

    def test_mirror_pair_collapses_without_changing_side(self):
        fen = b.parse((b.ROOT / b.EXPOSED[1][0]).read_bytes().splitlines()[1])["fen"]
        fields = fen.split(" ")
        mirror = "/".join(rank[::-1] for rank in fields[0].split("/")) + " " + " ".join(fields[1:])
        self.assertNotEqual(fen, mirror)
        self.assertEqual(b.canonical_fen(fen), b.canonical_fen(mirror))

    def test_removed_class_rejected_even_when_count_and_sha_are_changed(self):
        self.manifest["entries"].pop()
        self.manifest["count"] = 113
        self.manifest["canonical_fen_sha256"] = b.sha(b"edited")
        with self.assertRaises(ValueError):
            b.verify_exposure(b.ROOT, b.encoded(self.manifest))

    def test_reordered_or_edited_provenance_rejected(self):
        for altered in (dict(self.manifest, entries=list(reversed(self.manifest["entries"]))),
                        dict(self.manifest, sources=[])):
            with self.subTest(altered=altered["sources"]), self.assertRaises(ValueError):
                b.verify_exposure(b.ROOT, b.encoded(altered))

    def test_noncanonical_counter_material_and_rank_rejected(self):
        fen = self.manifest["entries"][0]["canonical_fen"]
        for invalid in (fen.replace(" w ", " b "), fen.replace(" 4 3", " 0 1"),
                        fen.replace("r", "1", 1), fen.replace("/9/", "/45/", 1)):
            with self.subTest(fen=invalid), self.assertRaises(ValueError):
                b.canonical_fen(invalid)

    def test_extra_exposure_deduplicates_and_binds_input_bytes(self):
        with tempfile.TemporaryDirectory(prefix="xq-v2-exposure-", dir=temp_base()) as directory:
            root = Path(directory)
            for name, _ in b.EXPOSED:
                path = root / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_bytes((b.ROOT / name).read_bytes())
            extra = root / "extra.fen"
            fen = self.manifest["entries"][0]["canonical_fen"]
            extra.write_text(fen + "\n" + fen + "\n", encoding="ascii")
            new = b.exposure_manifest(root, ["extra.fen"])
            self.assertEqual(new["count"], 114)
            self.assertEqual(new["sources"][-1]["count"], 2)
            self.assertEqual(new["sources"][-1]["sha256"], b.sha(extra.read_bytes()))
            b.verify_exposure(root, b.encoded(new), ["extra.fen"])
            extra.write_text(fen + "\n", encoding="ascii")
            with self.assertRaises(ValueError):
                b.verify_exposure(root, b.encoded(new), ["extra.fen"])


class ReceiptFixtureTests(unittest.TestCase):
    """Synthetic contents and overridden anchors isolate the verifier contract."""

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(prefix="xq-v2-toy-receipt-", dir=temp_base())
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        for name in b.RULE_FILES + b.ADAPTER_FILES + ["third_party/pikafish/src/search.cpp"]:
            self.put(name, ("toy source " + name).encode())
        self.put(b.TOOL_PATH, b"toy admission tool bytes, never executed")
        for name, _ in b.EXPOSED:
            self.put(name, (b.ROOT / name).read_bytes())
        self.compiler = self.root / "ndk" / "clang++.exe"
        self.put("ndk/clang++.exe", b"toy compiler, never executed")
        self.put("net.nnue", b"toy NNUE, never searched")
        self.addCleanup(patch.stopall)
        patch.object(b, "SEARCH_ANCHOR", b.file_sha(self.root, "third_party/pikafish/src/search.cpp")).start()
        patch.object(b, "NETWORK_SHA", b.file_sha(self.root, "net.nnue")).start()
        patch.object(b, "CLANG_SHA", b.sha(self.compiler.read_bytes())).start()
        self.spec = b.make_spec(self.root)
        self.spec_data = b.encoded(self.spec)
        self.exposure = b.encoded(b.exposure_manifest(self.root))
        self.output = self.spec["output_prefix"]
        self.put(self.output + "engine.a", b"!<arch>\ntoy engine")
        self.put(self.output + "pikafish.a", b"!<arch>\ntoy pikafish")
        elf = bytearray(64)
        elf[:7] = b"\x7fELF\x02\x01\x01"
        elf[18:20] = (62).to_bytes(2, "little")
        self.put(self.output + "entry", bytes(elf))
        self.put(self.output + "recipe.json", b"toy commands, never executed")
        self.put(self.output + "build.log", b"toy fixture log, not Native evidence")
        entry = b.PREFIX + "games/xiangqi/budget_v2_toy_entry.cpp"
        self.put(entry, b"toy source; no native compilation")
        self.receipt = dict(
            schema_version=2, kind="xiangqi-budget-v2-build-receipt", protocol=b.PROTOCOL,
            spec_sha256=b.sha(self.spec_data), exposure_sha256=b.sha(self.exposure),
            source_groups=self.spec["source_groups"], rule_identity=self.spec["rule_identity"],
            search_source_identity=self.spec["search_source_identity"],
            network=dict(path="net.nnue", sha256=b.NETWORK_SHA),
            toolchain=dict(self.spec["toolchain"], clang_path=str(self.compiler)),
            compile_options=["-std=c++17", "-O2"], link_options=["-static-libstdc++"],
            libraries=dict(engine_core=self.ref("engine.a"), pikafish=self.ref("pikafish.a")),
            binary=self.ref("entry"), build_recipe=self.ref("recipe.json"), build_log=self.ref("build.log"),
            entry_sources={entry: b.file_sha(self.root, entry)})

    def put(self, name, data):
        path = self.root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(data)

    def ref(self, name):
        path = self.output + name
        return dict(path=path, sha256=b.file_sha(self.root, path))

    def check(self, receipt=None, expected=None, spec=None):
        data = b.encoded(self.receipt if receipt is None else receipt)
        return b.validate_receipt(self.root, self.spec_data if spec is None else spec,
                                  data, b.sha(data) if expected is None else expected, self.exposure)

    def test_toy_binding_pass_does_not_authorize_search_or_claim_native(self):
        result = self.check()
        self.assertFalse(result["search_authorized"])
        self.assertFalse(result["native_executed"])
        self.assertFalse(result["strength_accepted"])

    def test_missing_every_required_receipt_field_rejected(self):
        for key in self.receipt:
            altered = copy.deepcopy(self.receipt)
            del altered[key]
            with self.subTest(key=key), self.assertRaises((ValueError, TypeError)):
                self.check(altered)

    def test_old_protocol_rule_and_receipt_kind_rejected(self):
        for key, value in (("protocol", "calibration-v4r1-20261015"),
                           ("kind", "xiangqi-holdout-entry-build-v1"),
                           ("schema_version", True), ("rule_identity", b.sha(b"other rules")),
                           ("search_source_identity", b.sha(b"old search"))):
            altered = dict(self.receipt, **{key: value})
            with self.subTest(key=key), self.assertRaises(ValueError):
                self.check(altered)

    def test_old_spec_rejected(self):
        old = b.encoded(dict(schema_version=1, protocol="calibration-v4r1-20261015"))
        with self.assertRaises(ValueError):
            self.check(spec=old)

    def test_missing_or_invalid_external_sha_rejected(self):
        for expected in ("", "A" * 64, "abc", b.sha(b"wrong receipt")):
            with self.subTest(expected=expected), self.assertRaises(ValueError):
                self.check(expected=expected)

    def test_edited_receipt_cannot_reuse_external_frozen_sha(self):
        frozen = b.sha(b.encoded(self.receipt))
        self.receipt["compile_options"].append("-DCHANGED=1")
        with self.assertRaises(ValueError):
            self.check(expected=frozen)

    def test_source_modification_addition_deletion_rejected(self):
        cases = ((b.RULE_FILES[0], b"altered rule"),
                 (b.ADAPTER_FILES[0], b"altered adapter"),
                 (b.TOOL_PATH, b"altered admission tool"),
                 ("third_party/pikafish/src/added.h", b"new header"))
        for name, data in cases:
            path = self.root / name
            old = path.read_bytes() if path.exists() else None
            self.put(name, data)
            with self.subTest(name=name), self.assertRaises(ValueError):
                self.check()
            if old is None:
                path.unlink()
            else:
                path.write_bytes(old)
        (self.root / b.RULE_FILES[0]).unlink()
        with self.assertRaises(OSError):
            self.check()

    def test_budget_anchor_cannot_be_resnapshotted_after_change(self):
        self.put("third_party/pikafish/src/search.cpp", b"old unsafe search")
        with self.assertRaises(ValueError):
            b.make_spec(self.root)

    def test_missing_library_sha_and_old_output_rejected(self):
        for item in ({"path": self.output + "pikafish.a"},
                     dict(path=".build/native-x86_64/libmocs_pikafish.a", sha256=b.OLD_LIBRARY),
                     dict(path=self.output + "pikafish.a", sha256="")):
            altered = copy.deepcopy(self.receipt)
            altered["libraries"]["pikafish"] = item
            with self.subTest(item=item), self.assertRaises(ValueError):
                self.check(altered)

    def test_blacklisted_library_rejected_even_in_new_directory(self):
        with patch.object(b, "OLD_LIBRARY", self.receipt["libraries"]["pikafish"]["sha256"]):
            with self.assertRaises(ValueError):
                self.check()

    def test_library_or_elf_tampering_rejected(self):
        for name in ("engine.a", "pikafish.a", "entry"):
            path = self.root / (self.output + name)
            old = path.read_bytes()
            path.write_bytes(old + b"changed")
            with self.subTest(name=name), self.assertRaises(ValueError):
                self.check()
            path.write_bytes(old)

    def test_non_elf_wrong_arch_and_non_archive_rejected_with_updated_digest(self):
        for name, data, field in (("entry", b"plain not ELF", "binary"),
                                  ("entry", b"\x7fELF" + bytes(60), "binary"),
                                  ("pikafish.a", b"not archive", "libraries")):
            path = self.root / (self.output + name)
            old = path.read_bytes()
            self.put(self.output + name, data)
            altered = copy.deepcopy(self.receipt)
            if field == "binary":
                altered[field] = self.ref(name)
            else:
                altered[field]["pikafish"] = self.ref(name)
            with self.subTest(name=name, data=data), self.assertRaises(ValueError):
                self.check(altered)
            path.write_bytes(old)

    def test_compiler_network_and_native_entry_tampering_rejected(self):
        for path in (self.compiler, self.root / "net.nnue",
                     self.root / next(iter(self.receipt["entry_sources"]))):
            old = path.read_bytes()
            path.write_bytes(old + b"altered")
            with self.subTest(path=path), self.assertRaises(ValueError):
                self.check()
            path.write_bytes(old)

    def test_v1_entry_and_empty_commands_or_build_log_rejected(self):
        for key, value in (("entry_sources", {b.PREFIX + "games/xiangqi/benchmark_xiangqi_ai_holdout_v1.cpp": b.sha(b"old")}),
                           ("compile_options", []), ("link_options", [])):
            altered = dict(self.receipt, **{key: value})
            with self.subTest(key=key), self.assertRaises(ValueError):
                self.check(altered)
        self.put(self.output + "build.log", b"")
        self.receipt["build_log"] = self.ref("build.log")
        with self.assertRaises(ValueError):
            self.check()

    def test_path_escape_rejected(self):
        for path in ("../escape", "C:/escape", "/escape", "a\\b", "a/../b", ""):
            with self.subTest(path=path), self.assertRaises(ValueError):
                b.repo_path(self.root, path)


class JsonTests(unittest.TestCase):
    def test_duplicate_keys_and_nonfinite_values_rejected(self):
        for data in (b'{"a":1,"a":2}', b'{"x":NaN}', b'{"x":Infinity}'):
            with self.subTest(data=data), self.assertRaises(ValueError):
                b.parse(data)

    def test_real_spec_binds_rules_and_full_pikafish_tree_separately(self):
        spec = b.make_spec(b.ROOT)
        b.validate_spec(b.ROOT, b.encoded(spec))
        self.assertEqual(len(spec["source_groups"]["rules"]), 6)
        self.assertGreater(len(spec["source_groups"]["pikafish"]), 100)
        self.assertNotEqual(spec["rule_identity"], spec["search_source_identity"])

    def test_historical_five_rule_spec_requires_new_admission(self):
        historical = (b.ROOT / b.SPEC_PATH).read_bytes()
        self.assertEqual(len(b.parse(historical)["source_groups"]["rules"]), 5)
        with self.assertRaisesRegex(ValueError, "v2 frozen spec/source closure"):
            b.validate_spec(b.ROOT, historical)

    def test_only_real_checking_mate_implementation_change_invalidates_plan(self):
        name = b.PREFIX + "games/xiangqi/checking_mate_proof.cpp"
        current = b.make_spec(b.ROOT)
        with tempfile.TemporaryDirectory(prefix="xq-v2-rule-closure-", dir=temp_base()) as directory:
            root = Path(directory)
            paths = {p for group in current["source_groups"].values() for p in group}
            for path in paths | {b.TOOL_PATH, name}:
                target = root / path
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes((b.ROOT / path).read_bytes())
            frozen = b.encoded(b.make_spec(root))
            b.validate_spec(root, frozen)
            proof = root / name
            original = proof.read_bytes()
            changed = original.replace(b"checking_move_limit > 8", b"checking_move_limit > 7", 1)
            self.assertNotEqual(original, changed)
            proof.write_bytes(changed)
            after = b.make_spec(root)
            self.assertNotEqual(current["rule_identity"], after["rule_identity"])
            self.assertEqual(current["search_source_identity"], after["search_source_identity"])
            # main() calls this same gate before plan or any receipt operation.
            with self.assertRaisesRegex(ValueError, "v2 frozen spec/source closure"):
                b.validate_spec(root, frozen)


if __name__ == "__main__":
    unittest.main()
