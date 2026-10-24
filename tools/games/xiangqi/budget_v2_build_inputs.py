"""Read-only, fail-closed inputs bridge. Never builds, executes, or freezes a spec."""

from __future__ import annotations

import argparse
from pathlib import Path
import re
import stat
import sys

import budget_v2_admission as admission

ENTRY = admission.PREFIX + "games/xiangqi/budget_v2_entry.cpp"
BRIDGE = "tools/games/xiangqi/budget_v2_build_inputs.py"
BUILD_TOOLS = ("tools/games/xiangqi/build_budget_v2_entry.ps1",
               "tools/games/xiangqi/budget_v2_build_evidence.py")
ADMISSION_SHA = "47946f098a1bea5de3f61542d2e1ddd97591436a3b43b03a8fe6f8fe3c54948a"
NDK_VERSION = "30.0.16138531"
NDK_PACKAGE = {"Pkg.Desc": "Android NDK", "Pkg.Revision": "30.0.16138531-beta3",
               "Pkg.BaseRevision": NDK_VERSION, "Pkg.ReleaseName": "r30-beta3"}
TARGET = "x86_64-linux-android24"
CORE_SOURCES = [admission.PREFIX + name for name in (
    "src/chinese_chess.cpp", "games/xiangqi/checking_mate_proof.cpp",
    "src/pikafish_adapter.cpp", "src/pikafish_difficulty.cpp")]
PIKAFISH_SOURCES = ["third_party/pikafish/src/" + name for name in (
    "attacks.cpp", "benchmark.cpp", "bitboard.cpp", "engine.cpp", "evaluate.cpp",
    "memory.cpp", "misc.cpp", "movegen.cpp", "movepick.cpp", "position.cpp",
    "score.cpp", "search.cpp", "thread.cpp", "timeman.cpp", "tt.cpp", "tune.cpp",
    "uci.cpp", "ucioption.cpp", "external/common/debug.cpp",
    "external/common/entropy_common.cpp", "external/common/error_private.cpp",
    "external/common/fse_decompress.cpp", "external/common/pool.cpp",
    "external/common/threading.cpp", "external/common/xxhash.cpp",
    "external/common/zstd_common.cpp", "external/decompress/huf_decompress.cpp",
    "external/decompress/zstd_ddict.cpp", "external/decompress/zstd_decompress_block.cpp",
    "external/decompress/zstd_decompress.cpp", "nnue/network.cpp",
    "nnue/nnue_accumulator.cpp", "nnue/nnue_misc.cpp", "nnue/features/full_threats.cpp",
    "nnue/features/half_ka_v2_hm.cpp", "external/decompress/huf_decompress_amd64.S")]
COMPILE_OPTIONS = ["--target=" + TARGET, "-std=c++17", "-O2", "-DNDEBUG",
                   "-DIS_64BIT", "-Wall", "-Wextra", "-Wpedantic"]
LINK_OPTIONS = ["--target=" + TARGET, "-static-libstdc++", "-Wl,--whole-archive",
                "-Wl,--no-whole-archive", "-llog", "-latomic", "-pthread"]
TOOLS = ("clang++.exe", "clang.exe", "llvm-ar.exe", "ld.lld.exe", "llvm-readelf.exe")


def no_reparse(path):
    """Reject links/junctions before resolve can hide them, including existing parents."""
    path = Path(path).absolute()
    for item in (path, *path.parents):
        if item.exists() or item.is_symlink():
            details = item.lstat()
            if item.is_symlink() or getattr(details, "st_file_attributes", 0) & stat.FILE_ATTRIBUTE_REPARSE_POINT:
                raise ValueError("link or reparse point in input/output path")
    return path.resolve()


def repo_file(root, name):
    path = admission.repo_path(root, name)
    no_reparse(Path(root) / name)
    if not path.is_file():
        raise ValueError("required repository input missing")
    return path


def hash_file(path):
    import hashlib
    digest = hashlib.sha256()
    with Path(path).open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def frozen_file(root, name, expected, maximum=4 * 1024 * 1024):
    admission.digest(expected)
    path = repo_file(root, name)
    if path.stat().st_size > maximum:
        raise ValueError("frozen JSON exceeds bound")
    with path.open("rb") as stream:
        data = stream.read(maximum + 1)
    if len(data) > maximum:
        raise ValueError("frozen JSON exceeds bound")
    admission.exact(admission.sha(data), expected, "externally frozen " + name)
    return data


def tree_identity(path):
    path = no_reparse(path)
    if not path.is_dir():
        raise ValueError("required NDK tree missing")
    entries = sorted(path.rglob("*"))
    for item in entries:
        no_reparse(item)
    files = [item for item in entries if item.is_file()]
    if not files:
        raise ValueError("empty NDK tree")
    identities = {}
    for item in files:
        no_reparse(item)
        identities[item.relative_to(path).as_posix()] = hash_file(item)
    return dict(file_count=len(identities), sha256=admission.identity(identities))


def toolchain_identity(ndk_directory):
    path = Path(ndk_directory)
    if not path.is_absolute():
        raise ValueError("explicit absolute NDK path required")
    path = no_reparse(path)
    properties = no_reparse(path / "source.properties").read_bytes()
    package = {}
    for line in properties.splitlines():
        if not line.strip():
            continue
        key, separator, value = line.partition(b"=")
        key, value = key.strip().decode("ascii"), value.strip().decode("ascii")
        if not separator or not key or key in package:
            raise ValueError("malformed or duplicate NDK package metadata")
        package[key] = value
    # The installed compiler was already locked by SHA; its real package is beta3,
    # not an imagined suffix-free release. Do not accept other beta/base revisions.
    admission.exact(package, NDK_PACKAGE, "NDK30 beta3 package metadata")
    prebuilt = path / "toolchains/llvm/prebuilt/windows-x86_64"
    tools = {}
    for name in TOOLS:
        actual = no_reparse(prebuilt / "bin" / name)
        if not actual.is_file():
            raise ValueError("required explicit LLVM tool missing")
        tools[name] = dict(path=str(actual), sha256=hash_file(actual))
    admission.exact(tools["clang++.exe"]["sha256"], admission.CLANG_SHA, "actual NDK compiler")
    resources = list((prebuilt / "lib/clang").iterdir())
    if len(resources) != 1 or not resources[0].is_dir():
        raise ValueError("ambiguous Clang resource tree")
    return dict(ndk_version=NDK_VERSION, target=TARGET, ndk_path=str(path), tools=tools,
                package_revision=package["Pkg.Revision"], release_name=package["Pkg.ReleaseName"],
                source_properties_sha256=admission.sha(properties),
                sysroot=tree_identity(prebuilt / "sysroot"),
                clang_resources=tree_identity(resources[0]))


def manifest_for_review(root, spec, ndk_directory):
    """Compute a candidate value for Root review, not a freeze or build attestation."""
    admission.exact(admission.file_sha(root, admission.TOOL_PATH), ADMISSION_SHA,
                    "reviewed admission implementation")
    names = [ENTRY, BRIDGE, *BUILD_TOOLS, *CORE_SOURCES, *PIKAFISH_SOURCES]
    for group in spec["source_groups"].values():
        names.extend(group)
    for name in names:
        repo_file(root, name)
    return dict(schema_version=1, kind="xiangqi-budget-v2-native-inputs",
                protocol=admission.PROTOCOL, output_prefix=spec["output_prefix"],
                entry_sources={ENTRY: admission.file_sha(root, ENTRY)},
                build_tools={name: admission.file_sha(root, name) for name in BUILD_TOOLS},
                bridge_sha256=admission.file_sha(root, BRIDGE), admission_tool_sha256=ADMISSION_SHA,
                source_groups=spec["source_groups"], core_sources=CORE_SOURCES,
                pikafish_sources=PIKAFISH_SOURCES, toolchain=toolchain_identity(ndk_directory),
                compile_options=COMPILE_OPTIONS, link_options=LINK_OPTIONS,
                search_authorized=False, native_executed=False, strength_accepted=False)


def output_path(root, spec, directory, *, after=False):
    raw = Path(directory)
    if not raw.is_absolute():
        raise ValueError("explicit absolute output directory required")
    actual = no_reparse(raw)
    parent = no_reparse(Path(root) / spec["output_prefix"])
    if actual.parent != parent or re.fullmatch(r"[a-z0-9][a-z0-9-]{0,79}", actual.name) is None:
        raise ValueError("new output must be one named v2 child")
    if actual.exists() != after or (after and not actual.is_dir()):
        raise ValueError("output already exists" if not after else "build output missing")
    return actual


def check_inputs(root, *, spec_path, spec_sha256, exposure_path, exposure_sha256,
                 manifest_path, manifest_sha256, network_path, build_directory,
                 ndk_directory, after=False):
    root = no_reparse(root)
    spec_data = frozen_file(root, spec_path, spec_sha256)
    spec = admission.validate_spec(root, spec_data)
    exposure = frozen_file(root, exposure_path, exposure_sha256)
    admission.verify_exposure(root, exposure)
    manifest_data = frozen_file(root, manifest_path, manifest_sha256)
    manifest = admission.parse(manifest_data)
    admission.exact(manifest, manifest_for_review(root, spec, ndk_directory), "frozen native input manifest")
    network = repo_file(root, network_path)
    admission.exact(hash_file(network), admission.NETWORK_SHA, "actual NNUE")
    output = output_path(root, spec, build_directory, after=after)
    return dict(status="offline-native-build-inputs-verified", protocol=admission.PROTOCOL,
                spec_sha256=spec_sha256, exposure_sha256=exposure_sha256,
                input_manifest_sha256=manifest_sha256, output_directory=output.relative_to(root).as_posix(),
                rule_identity=spec["rule_identity"], search_source_identity=spec["search_source_identity"],
                entry_sources=manifest["entry_sources"], network=dict(path=network_path, sha256=admission.NETWORK_SHA),
                search_authorized=False, native_executed=False, strength_accepted=False)


def verify_after(root, *, receipt_path, receipt_sha256, **inputs):
    result = check_inputs(root, after=True, **inputs)
    raw = frozen_file(root, receipt_path, receipt_sha256)
    receipt = admission.parse(raw)
    if type(receipt) is not dict or type(receipt.get("libraries")) is not dict:
        raise ValueError("receipt must contain an object and a library map")
    prefix = result["output_directory"] + "/"
    # Existing verifier accepts any v2 child; this bridge binds one build directory.
    for item in [*receipt.get("libraries", {}).values(), *(receipt.get(k, {}) for k in (
            "binary", "build_recipe", "build_log"))]:
        if not isinstance(item, dict) or not isinstance(item.get("path"), str) or not item["path"].startswith(prefix):
            raise ValueError("receipt artifact belongs to a different build")
        repo_file(root, item["path"])
    admission.exact(receipt.get("entry_sources"), result["entry_sources"], "receipt independent entry closure")
    admission.exact(receipt.get("network"), result["network"], "receipt network path and identity")
    manifest = admission.parse(frozen_file(root, inputs["manifest_path"], inputs["manifest_sha256"]))
    admission.exact(receipt.get("compile_options"), manifest["compile_options"], "receipt compile options")
    admission.exact(receipt.get("link_options"), manifest["link_options"], "receipt link options")
    checked = admission.validate_receipt(root,
        frozen_file(root, inputs["spec_path"], inputs["spec_sha256"]), raw, receipt_sha256,
        frozen_file(root, inputs["exposure_path"], inputs["exposure_sha256"]))
    return dict(checked, input_manifest_sha256=inputs["manifest_sha256"],
                output_directory=result["output_directory"])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("check", "verify-after"))
    for name in ("spec-path", "spec-sha256", "exposure-path", "exposure-sha256", "manifest-path",
                 "manifest-sha256", "network-path", "build-directory", "ndk-directory"):
        parser.add_argument("--" + name, required=True)
    parser.add_argument("--receipt-path")
    parser.add_argument("--receipt-sha256")
    args = vars(parser.parse_args())
    command = args.pop("command")
    receipt = {key: args.pop(key) for key in ("receipt_path", "receipt_sha256")}
    if command == "verify-after":
        if not all(receipt.values()):
            parser.error("verify-after requires an independently frozen receipt path and SHA")
        result = verify_after(admission.ROOT, **receipt, **args)
    else:
        if any(receipt.values()):
            parser.error("check does not accept or create a receipt")
        result = check_inputs(admission.ROOT, **args)
    sys.stdout.buffer.write(admission.encoded(result))


if __name__ == "__main__":
    try:
        main()
    except (ValueError, TypeError, KeyError, OSError, UnicodeError) as error:
        sys.exit(f"budget v2 build inputs rejected: {error}")
