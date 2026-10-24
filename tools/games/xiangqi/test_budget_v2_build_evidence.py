"""Read-only evidence boundary fixtures, not compiler or native execution evidence."""

from pathlib import Path
import tempfile
import unittest

import budget_v2_admission as admission
import budget_v2_build_evidence as evidence


class EvidenceBoundaryTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="mocs-evidence-")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name).resolve()
        self.directory = self.root / ".build/review/build-r1"
        self.directory.mkdir(parents=True)

    def write(self, path, data):
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(data)
        return path

    def artifact(self, path):
        return {"path": path.relative_to(self.root).as_posix(), "sha256": evidence.digest_file(path)}

    def test_json_rejects_duplicate_keys_nonfinite_and_oversize(self):
        path = self.root / "input.json"
        for content in (b'{"x":1,"x":2}', b'{"x":NaN}', b'{"x":Infinity}'):
            with self.subTest(content=content):
                self.write(path, content)
                with self.assertRaises(ValueError):
                    evidence.read_json(path, maximum=64 * 1024 * 1024)
        self.write(path, b' ' * 33)
        with self.assertRaisesRegex(ValueError, "bound"):
            evidence.read_json(path, maximum=32)

    def test_raw_json_hash_is_checked_before_semantic_acceptance(self):
        path = self.write(self.root / "input.json", b'{"ok":true}\n')
        with self.assertRaises(ValueError):
            evidence.read_json(path, "0" * 64)
        self.assertEqual({"ok": True}, evidence.read_json(path, evidence.digest_file(path)))

    def test_process_flags_require_exact_boolean_and_integer_shapes(self):
        valid = dict(exit_code=0, process_started=True, timed_out=False)
        evidence.verify_execution_status(valid)
        for key, value in (("exit_code", False), ("exit_code", 0.0), ("exit_code", 7),
                           ("process_started", 1), ("process_started", "true"),
                           ("timed_out", 0), ("timed_out", True)):
            with self.subTest(key=key, value=value):
                with self.assertRaises(ValueError):
                    evidence.verify_execution_status(dict(valid, **{key: value}))

    def test_dependency_parser_preserves_drive_colons_spaces_and_line_continuations(self):
        text = "C:/own/out.o: C:/own/source.cpp \\\n C:/folder\\ with\\ spaces/header.h\n"
        self.assertEqual(["C:/own/source.cpp", "C:/folder with spaces/header.h"], evidence.dependency_paths(text))
        for text in ("missing separator", "target.o: \n"):
            with self.assertRaises(ValueError):
                evidence.dependency_paths(text)

    def test_dependency_closure_rejects_unknown_files_and_omitted_primary_source(self):
        source = self.write(self.directory / "source/main.cpp", b"fixture source; never compiled")
        header = self.write(self.directory / "source/header.h", b"fixture header")
        dep = self.write(self.directory / "main.o.d", ("out.o: " + str(source) + " " + str(header) + "\n").encode())
        item = {"source": "main.cpp", "snapshot_source": source.relative_to(self.root).as_posix(),
                "dependencies": self.artifact(dep)}
        allowed = {str(p).casefold(): (p, evidence.digest_file(p)) for p in (source, header)}
        closure = evidence.collect_dependencies(self.root, [item], allowed)
        self.assertEqual(set(closure["main.cpp"]), {str(source), str(header)})
        with self.assertRaisesRegex(ValueError, "outside"):
            evidence.collect_dependencies(self.root, [item], {str(source).casefold(): allowed[str(source).casefold()]})
        self.write(dep, ("out.o: " + str(header) + "\n").encode())
        item["dependencies"] = self.artifact(dep)
        with self.assertRaisesRegex(ValueError, "omits"):
            evidence.collect_dependencies(self.root, [item], allowed)

    def test_snapshot_checks_original_copied_bytes_and_exact_source_set(self):
        source_name = "engine/source.cpp"
        original = self.write(self.root / source_name, b"original fixture; never compiled")
        copied = self.write(self.directory / "source" / source_name, original.read_bytes())
        snapshot = {source_name: self.artifact(copied)}
        spec = {"output_prefix": ".build/review/", "source_groups": {"rules": {source_name: evidence.digest_file(original)}}}
        prebuilt = self.root / "fixture-ndk/toolchains/llvm/prebuilt/windows-x86_64"
        sdk_header = self.write(prebuilt / "sysroot/include/fixture.h", b"sdk fixture")
        resource_header = self.write(prebuilt / "lib/clang/22/include/fixture.h", b"resource fixture")
        def tree(path):
            rows = {p.relative_to(path).as_posix(): evidence.digest_file(p) for p in path.rglob("*") if p.is_file()}
            return {"file_count": len(rows), "sha256": admission.identity(rows)}
        manifest = {"entry_sources": {}, "toolchain": {"ndk_path": str(self.root / "fixture-ndk"),
                    "sysroot": tree(prebuilt / "sysroot"), "clang_resources": tree(prebuilt / "lib/clang/22")}}
        self.write(self.directory / "source-snapshot.json", admission.encoded(snapshot))
        self.write(self.directory / "ndk-inputs.json", admission.encoded({str(p): evidence.digest_file(p) for p in (sdk_header, resource_header)}))
        evidence.snapshot_inputs(self.root, spec, manifest, self.directory)
        self.write(copied, b"modified snapshot")
        with self.assertRaises(ValueError):
            evidence.snapshot_inputs(self.root, spec, manifest, self.directory)
        self.write(copied, original.read_bytes())
        self.write(original, b"modified original")
        with self.assertRaises(ValueError):
            evidence.snapshot_inputs(self.root, spec, manifest, self.directory)


if __name__ == "__main__":
    unittest.main()
