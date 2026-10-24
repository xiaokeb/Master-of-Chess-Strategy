"""Offline identity/exposure admission only. No search, device or slot operations."""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path, PurePosixPath
import re
import sys

ROOT = Path(__file__).resolve().parents[3]
PROTOCOL = "calibration-budget-v2-20261022-admission"
SPEC_PATH = "config/games/xiangqi/ai-evaluations/calibration-budget-v2-20261022-admission.json"
EXPOSURE_PATH = "config/games/xiangqi/ai-evaluations/calibration-budget-v2-20261022-exclusions.json"
TOOL_PATH = "tools/games/xiangqi/budget_v2_admission.py"
PREFIX = "engine-native/src/main/cpp/"
RULE_FILES = [PREFIX + p for p in (
    "src/chinese_chess.cpp", "games/xiangqi/checking_mate_proof.cpp",
    "include/mocs/engine/chinese_chess.hpp",
    "include/mocs/engine/chinese_chess_search_position.hpp",
    "include/mocs/engine/engine_types.hpp", "include/mocs/engine/rule_engine.hpp")]
ADAPTER_FILES = [PREFIX + p for p in (
    "src/pikafish_adapter.cpp", "src/pikafish_difficulty.cpp",
    "include/mocs/engine/pikafish_adapter.hpp", "include/mocs/engine/pikafish_difficulty.hpp")]
SEARCH_ANCHOR = "b760fd489469fb095d8ac395164fc1b37ec1dece93872b13c6f1ff269665cab5"
OLD_LIBRARY = "90292badec72400a767860b9eff20994f9fc185b795f9b40a4eb6ac4e8789e49"
NETWORK_SHA = "7d13d73569a9b571ba0eb20cf1596247bc2a42738967e61afef6482b231e900e"
CLANG_SHA = "46f558e3cb379b11c8df34a2d32466d755cf5e19646d5ec9c2b79d95dc82c8be"
EXPOSED = [
    ("config/games/xiangqi/ai-evaluations/calibration-exclusions-v1.fen",
     "2d17f81d99b4b2f132c2f42473cb874265576439885466f75d0cace473899216"),
    ("config/games/xiangqi/ai-evaluations/holdout-openings-v1-20261015.jsonl",
     "387be85a6ac27b36f86bb72993887422302181038ebcf4ec1deb9d1cf58a2d4f")]


def sha(data):
    return hashlib.sha256(data).hexdigest()


def encoded(value):
    return (json.dumps(value, ensure_ascii=True, sort_keys=True, indent=2,
                       allow_nan=False) + "\n").encode("ascii")


def identity(value):
    return sha(encoded(value))


def digest(value):
    if type(value) is not str or re.fullmatch(r"[0-9a-f]{64}", value) is None:
        raise ValueError("required lowercase SHA256 missing or malformed")
    return value


def _pairs(items):
    obj = {}
    for key, value in items:
        if key in obj:
            raise ValueError("duplicate JSON key")
        obj[key] = value
    return obj


def parse(data):
    if len(data) > 4 * 1024 * 1024:
        raise ValueError("JSON exceeds bound")
    return json.loads(data.decode("utf-8"), object_pairs_hook=_pairs,
                      parse_constant=lambda x: (_ for _ in ()).throw(ValueError(x)))


def exact(actual, expected, label):
    if (actual != expected if type(expected) is bytes else encoded(actual) != encoded(expected)):
        raise ValueError(label + " differs")


def repo_path(root, name):
    if type(name) is not str or "\\" in name or ":" in name:
        raise ValueError("expected repository-relative POSIX path")
    path = PurePosixPath(name)
    if path.is_absolute() or any(p in ("..", ".") for p in name.split("/")):
        raise ValueError("unsafe artifact path")
    resolved = (root / name).resolve()
    if not resolved.is_relative_to(root.resolve()) or not name:
        raise ValueError("artifact outside repository")
    return resolved


def file_sha(root, name):
    return sha(repo_path(root, name).read_bytes())


def source_groups(root):
    tree = repo_path(root, "third_party/pikafish/src")
    pikafish = sorted(p.relative_to(root).as_posix() for p in tree.rglob("*") if p.is_file())
    if not pikafish:
        raise ValueError("missing complete Pikafish source tree")
    return {group: {name: file_sha(root, name) for name in names}
            for group, names in (("rules", RULE_FILES), ("adapter", ADAPTER_FILES),
                                  ("pikafish", pikafish))}


def make_spec(root):
    """Snapshot current bytes for initial review, never manufacture build hashes."""
    groups = source_groups(root)
    exact(groups["pikafish"]["third_party/pikafish/src/search.cpp"], SEARCH_ANCHOR,
          "released budget repair anchor")
    return dict(schema_version=2, protocol=PROTOCOL, purpose="admission-only-no-search",
                admission_tool_sha256=file_sha(root, TOOL_PATH),
                rule_profile=dict(format_version=4, revision=1), source_groups=groups,
                rule_identity=identity(groups["rules"]),
                search_source_identity=identity({g: groups[g] for g in ("adapter", "pikafish")}),
                network_sha256=NETWORK_SHA,
                toolchain=dict(ndk_version="30.0.16138531", target="x86_64-linux-android24",
                               clang_sha256=CLANG_SHA),
                output_prefix=".build/xiangqi-calibration-budget-v2-20261022/",
                search_authorized=False, strength_accepted=False)


def validate_spec(root, data):
    spec = parse(data)
    exact(spec, make_spec(root), "v2 frozen spec/source closure")
    return spec


def canonical_fen(fen):
    """Four-ply noncapture envelope, horizontal equivalence; no legality claim."""
    if type(fen) is not str or len(fen) > 128:
        raise ValueError("invalid bounded FEN")
    fields = fen.split(" ")
    if len(fields) != 6 or fields[1:] != ["w", "-", "-", "4", "3"]:
        raise ValueError("expected canonical four-ply red-to-move FEN")
    ranks = fields[0].split("/")
    if len(ranks) != 10:
        raise ValueError("expected ten ranks")
    expanded = []
    for rank in ranks:
        cells = []
        for cell in rank:
            if cell in "123456789":
                cells.extend("." * int(cell))
            elif cell in "rnbakcpRNBAKCP":
                cells.append(cell)
            else:
                raise ValueError("invalid FEN cell")
        if len(cells) != 9:
            raise ValueError("expected nine files")
        expanded.append(cells)

    def encode(rows):
        result = []
        for row in rows:
            text, empty = "", 0
            for cell in row + ["!"]:
                if cell == ".":
                    empty += 1
                else:
                    if empty:
                        text += str(empty)
                        empty = 0
                    if cell != "!":
                        text += cell
            result.append(text)
        return "/".join(result) + " w - - 4 3"

    flat = "".join("".join(row) for row in expanded)
    if any(flat.count(c) != count for c, count in dict(
            r=2, n=2, b=2, a=2, k=1, c=2, p=5,
            R=2, N=2, B=2, A=2, K=1, C=2, P=5).items()):
        raise ValueError("four-ply noncapture material differs")
    exact(fen, encode(expanded), "canonical FEN encoding")
    return min(fen, encode([list(reversed(row)) for row in expanded]))


def exposure_manifest(root, extra=()):
    """Reconstruct the 114 baseline classes and optional newly exposed classes."""
    entries, sources, occupied = [], [], set()
    for source_index, (name, expected_sha) in enumerate(EXPOSED):
        data = repo_path(root, name).read_bytes()
        exact(sha(data), expected_sha, "frozen exposure input SHA")
        if source_index == 0:
            lines = data.decode("ascii").splitlines()
            exact(lines[0], "MOCS_XQ_HOLDOUT_EXCLUSIONS_V1", "exposure header")
            fens = lines[1:]
            exact(len(fens), 66, "old exposure count")
        else:
            rows = [parse(line) for line in data.splitlines()]
            exact(len(rows), 49, "v1 stream count")
            fens = [row["fen"] for row in rows[1:]]
        sources.append(dict(path=name, sha256=expected_sha, count=len(fens)))
        for row, fen in enumerate(fens, 1):
            key = canonical_fen(fen)
            if key in occupied:
                raise ValueError("baseline exposure unexpectedly duplicates a mirror")
            occupied.add(key)
            entries.append(dict(canonical_fen=key, source_index=source_index, source_row=row))
    if len(entries) != 114:
        raise ValueError("must preserve all 114 exposed equivalence classes")
    for name in extra:
        if name in [s["path"] for s in sources]:
            raise ValueError("duplicate extra exposure source")
        data = repo_path(root, name).read_bytes()
        if len(data) > 1024 * 1024:
            raise ValueError("extra exposure exceeds bound")
        fens = data.decode("ascii").splitlines()
        if not fens:
            raise ValueError("empty extra exposure source")
        source_index = len(sources)
        sources.append(dict(path=name, sha256=sha(data), count=len(fens)))
        for row, fen in enumerate(fens, 1):
            key = canonical_fen(fen)
            if key not in occupied:
                occupied.add(key)
                entries.append(dict(canonical_fen=key, source_index=source_index, source_row=row))
    return dict(schema_version=2, protocol=PROTOCOL, purpose="all-exposed-mirror-classes",
                sources=sources, baseline_count=114, count=len(entries), entries=entries,
                canonical_fen_sha256=sha("\n".join(e["canonical_fen"] for e in entries).encode("ascii")),
                search_performed=False, legality_replayed=False, strength_accepted=False)


def verify_exposure(root, data, extra=()):
    exact(data, encoded(exposure_manifest(root, extra)), "reconstructed exposure bytes")
    return sha(data)


def artifact(root, spec, value, magic):
    if type(value) is not dict or set(value) != {"path", "sha256"}:
        raise ValueError("artifact must carry path and SHA")
    digest(value["sha256"])
    if type(value["path"]) is not str or not value["path"].startswith(spec["output_prefix"]):
        raise ValueError("old or non-v2 build output path")
    data = repo_path(root, value["path"]).read_bytes()
    exact(sha(data), value["sha256"], "artifact SHA")
    if value["sha256"] == OLD_LIBRARY or not data.startswith(magic):
        raise ValueError("old library or wrong artifact format")
    return data


def validate_receipt(root, spec_data, receipt_data, expected_sha, exposure_data, extra=()):
    """Check a Root-produced receipt; never create or attest a build receipt."""
    digest(expected_sha)
    exact(sha(receipt_data), expected_sha, "externally frozen receipt SHA")
    spec = validate_spec(root, spec_data)
    exposure_sha = verify_exposure(root, exposure_data, extra)
    receipt = parse(receipt_data)
    required = {"schema_version", "kind", "protocol", "spec_sha256", "exposure_sha256",
                "source_groups", "rule_identity", "search_source_identity", "network",
                "toolchain", "compile_options", "link_options", "libraries", "binary",
                "build_recipe", "build_log", "entry_sources"}
    if type(receipt) is not dict or set(receipt) != required:
        raise ValueError("incomplete v2 build receipt")
    for key, expected in dict(schema_version=2, kind="xiangqi-budget-v2-build-receipt",
                              protocol=PROTOCOL, spec_sha256=sha(spec_data),
                              exposure_sha256=exposure_sha, source_groups=spec["source_groups"],
                              rule_identity=spec["rule_identity"],
                              search_source_identity=spec["search_source_identity"]).items():
        exact(receipt[key], expected, "receipt " + key)
    tc = receipt["toolchain"]
    if type(tc) is not dict or set(tc) != set(spec["toolchain"]) | {"clang_path"}:
        raise ValueError("missing toolchain identity")
    for key, expected in spec["toolchain"].items():
        exact(tc[key], expected, "toolchain " + key)
    # Compiler resides outside repository; only read explicit absolute compiler path.
    compiler = Path(tc["clang_path"])
    if not compiler.is_absolute() or compiler.name != "clang++.exe":
        raise ValueError("expected actual NDK Clang compiler")
    exact(sha(compiler.read_bytes()), CLANG_SHA, "actual compiler SHA")
    network = receipt["network"]
    if type(network) is not dict or set(network) != {"path", "sha256"}:
        raise ValueError("missing NNUE identity")
    exact(network["sha256"], NETWORK_SHA, "NNUE SHA")
    exact(file_sha(root, network["path"]), NETWORK_SHA, "actual NNUE SHA")
    for name in ("compile_options", "link_options"):
        flags = receipt[name]
        if type(flags) is not list or not flags or any(type(f) is not str or not f for f in flags):
            raise ValueError("nonempty exact compiler/linker argument lists required")
    libs = receipt["libraries"]
    if type(libs) is not dict or not {"engine_core", "pikafish"} <= set(libs):
        raise ValueError("actual linked engine and Pikafish libraries required")
    for item in libs.values():
        artifact(root, spec, item, b"!<arch>\n")
    binary = artifact(root, spec, receipt["binary"], b"\x7fELF")
    if len(binary) < 64 or binary[4:7] != b"\x02\x01\x01" or int.from_bytes(binary[18:20], "little") != 62:
        raise ValueError("expected actual x86_64 ELF64 little-endian output")
    for name in ("build_recipe", "build_log"):
        data = artifact(root, spec, receipt[name], b"")
        if not data:
            raise ValueError("empty build evidence")
    entries = receipt["entry_sources"]
    if type(entries) is not dict or not entries:
        raise ValueError("missing independent native v2 entry closure")
    for path, expected in entries.items():
        digest(expected)
        if "budget_v2" not in path or not path.startswith(PREFIX + "games/xiangqi/"):
            raise ValueError("native entry must have independent budget_v2 identity")
        exact(file_sha(root, path), expected, "native v2 entry source")
    return dict(status="offline-artifact-bindings-verified", protocol=PROTOCOL,
                receipt_sha256=expected_sha, spec_sha256=sha(spec_data), exposure_sha256=exposure_sha,
                rule_identity=spec["rule_identity"], search_source_identity=spec["search_source_identity"],
                runtime_identity=identity(receipt), search_authorized=False,
                native_executed=False, strength_accepted=False)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("plan", "exclusions", "verify-exclusions", "verify-receipt"))
    parser.add_argument("--extra-exposure", action="append", default=[])
    parser.add_argument("--receipt", type=Path)
    parser.add_argument("--receipt-sha256")
    parser.add_argument("--exposure-manifest", type=Path,
                        help="Frozen independently saved manifest, including any extra exposure inputs")
    args = parser.parse_args()
    spec_data = repo_path(ROOT, SPEC_PATH).read_bytes()
    spec = validate_spec(ROOT, spec_data)
    if args.command == "exclusions":
        sys.stdout.buffer.write(encoded(exposure_manifest(ROOT, args.extra_exposure)))
        return
    exposure_data = (args.exposure_manifest or repo_path(ROOT, EXPOSURE_PATH)).read_bytes()
    exposure_sha = verify_exposure(ROOT, exposure_data, args.extra_exposure)
    if args.command == "verify-receipt":
        if args.receipt is None or args.receipt_sha256 is None:
            parser.error("verify-receipt requires --receipt and externally frozen --receipt-sha256")
        result = validate_receipt(ROOT, spec_data, args.receipt.read_bytes(),
                                  args.receipt_sha256, exposure_data, args.extra_exposure)
    else:
        result = dict(status="offline-inputs-verified", protocol=PROTOCOL, spec_sha256=sha(spec_data),
                      rule_identity=spec["rule_identity"], search_source_identity=spec["search_source_identity"],
                      exposure_sha256=exposure_sha, exposed=exposure_manifest(ROOT, args.extra_exposure)["count"],
                      search_authorized=False, native_executed=False, strength_accepted=False,
                      next_required="Root real NDK30 v2 build receipt and complete preregistered match protocol")
    sys.stdout.buffer.write(encoded(result))


if __name__ == "__main__":
    try:
        main()
    except (ValueError, TypeError, KeyError, OSError, UnicodeError) as error:
        sys.exit(f"budget v2 admission rejected: {error}")
