"""Independent, read-only snapshot/command evidence verification; never runs native code."""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import re
import sys

import budget_v2_admission as admission


def digest_file(path):
    value = hashlib.sha256()
    with Path(path).open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            value.update(block)
    return value.hexdigest()


def read_json(path, expected=None, maximum=4 * 1024 * 1024):
    with Path(path).open("rb") as stream:
        data = stream.read(maximum + 1)
    if len(data) > maximum:
        raise ValueError("evidence JSON exceeds bound")
    if expected is not None:
        admission.digest(expected)
        admission.exact(admission.sha(data), expected, "evidence raw SHA")
    return json.loads(data.decode("utf-8"), object_pairs_hook=admission._pairs,
                      parse_constant=lambda value: (_ for _ in ()).throw(ValueError(value)))


def contained(root, name):
    # Check the original spelling before resolve() can hide a junction or symlink.
    raw = root / name
    for item in (raw, *raw.parents):
        if item == root.parent:
            break
        if item.is_symlink() or (item.exists() and getattr(item.lstat(), "st_file_attributes", 0) & 1024):
            raise ValueError("reparse point in evidence")
    return admission.repo_path(root, name)


def verify_execution_status(recorded):
    admission.exact(recorded["exit_code"], 0, "command exit code")
    admission.exact(recorded["process_started"], True, "command actually started flag")
    admission.exact(recorded["timed_out"], False, "command timeout flag")


def artifact(root, item):
    if type(item) is not dict or set(item) != {"path", "sha256"}:
        raise ValueError("artifact needs exact path and SHA")
    path = contained(root, item["path"])
    admission.exact(digest_file(path), admission.digest(item["sha256"]), "artifact bytes")
    return path


def flat_sources(spec, manifest):
    expected = {}
    for group in spec["source_groups"].values():
        for name, value in group.items():
            if name in expected and expected[name] != value:
                raise ValueError("source group conflict")
            expected[name] = value
    expected.update(manifest["entry_sources"])
    return expected


def snapshot_inputs(root, spec, manifest, directory):
    directory = Path(directory).resolve()
    if directory.parent != (root / spec["output_prefix"]).resolve():
        raise ValueError("snapshot is outside the one admitted build")
    snapshot = read_json(directory / "source-snapshot.json")
    ndk = read_json(directory / "ndk-inputs.json", maximum=64 * 1024 * 1024)
    expected = flat_sources(spec, manifest)
    admission.exact(sorted(snapshot), sorted(expected), "snapshot source set")
    dependencies = {}
    for name, sha in expected.items():
        item = snapshot[name]
        path = artifact(root, item)
        if path != directory / "source" / name:
            raise ValueError("snapshot path is not the exact mirrored source")
        admission.exact(item["sha256"], sha, "snapshot frozen SHA")
        admission.exact(digest_file(contained(root, name)), sha, "original frozen source")
        dependencies[str(path.resolve()).casefold()] = (path, sha)
    prebuilt = Path(manifest["toolchain"]["ndk_path"]) / "toolchains/llvm/prebuilt/windows-x86_64"
    resources = list((prebuilt / "lib/clang").iterdir())
    if len(resources) != 1:
        raise ValueError("ambiguous resource directory")
    seen = set()
    for tree, identity in ((prebuilt / "sysroot", manifest["toolchain"]["sysroot"]),
                           (resources[0], manifest["toolchain"]["clang_resources"])):
        tree = tree.resolve()
        group = {}
        for absolute, sha in ndk.items():
            path = Path(absolute).resolve()
            if path.is_relative_to(tree):
                group[path.relative_to(tree).as_posix()] = sha
                admission.exact(digest_file(path), admission.digest(sha), "locked SDK input")
                dependencies[str(path).casefold()] = (path, sha)
                seen.add(absolute)
        admission.exact(len(group), identity["file_count"], "SDK inventory count")
        admission.exact(admission.identity(group), identity["sha256"], "SDK inventory identity")
    admission.exact(sorted(ndk), sorted(seen), "SDK inventory domains")
    return snapshot, dependencies, resources[0]


def dependency_paths(text):
    """Clang makefile output: preserve drive colons and escaped spaces/backslashes."""
    flattened = text.replace("\\\r\n", "").replace("\\\n", "")
    separator = re.search(r":\s+", flattened)
    if separator is None:
        raise ValueError("dependency target separator missing")
    body = flattened[separator.end():]
    result, word = [], []
    index = 0
    while index < len(body):
        char = body[index]
        if char == "\\" and index + 1 < len(body) and body[index + 1] in " \\#:\t":
            index += 1
            word.append(body[index])
        elif char.isspace():
            if word:
                result.append("".join(word)); word = []
        else:
            word.append(char)
        index += 1
    if word:
        result.append("".join(word))
    if not result:
        raise ValueError("dependency list is empty")
    return result


def collect_dependencies(root, objects, permitted):
    closure = {}
    for item in objects:
        depfile = artifact(root, item["dependencies"])
        entries = {}
        for name in dependency_paths(depfile.read_text(encoding="utf-8")):
            path = Path(name)
            path = (path if path.is_absolute() else root / path).resolve()
            allowed = permitted.get(str(path).casefold())
            if allowed is None:
                raise ValueError("compiler used a dependency outside the frozen snapshot/SDK")
            admission.exact(digest_file(path), allowed[1], "actual compiler dependency")
            entries[str(path)] = allowed[1]
        source = item["snapshot_source"]
        if str(contained(root, source).resolve()) not in entries:
            raise ValueError("compile dependency omits its actual snapshot source")
        closure[item["source"]] = entries
    return closure


def expected_commands(root, spec, manifest, directory, resource):
    tc = manifest["toolchain"]
    prebuilt = Path(tc["ndk_path"]) / "toolchains/llvm/prebuilt/windows-x86_64"
    generated = ["--sysroot=" + str(prebuilt / "sysroot"), "-resource-dir", str(resource),
                 "--no-default-config", "-fPIC", "-I", str(directory / "source/engine-native/src/main/cpp/include"),
                 "-I", str(directory / "source/third_party/pikafish/src")]
    commands, objects, groups = [], [], {}
    for group in ("core", "pikafish"):
        group_objects = []
        for index, name in enumerate(manifest[group + "_sources"]):
            source = directory / "source" / name
            target = directory / f"objects/{group}/{index:03}.o"
            dep = Path(str(target) + ".d")
            assembly = name.endswith(".S")
            options = [flag for flag in manifest["compile_options"] if not assembly or flag != "-std=c++17"]
            tool = "clang.exe" if assembly else "clang++.exe"
            commands.append((tool, options + generated + ["-MMD", "-MF", str(dep), "-c", str(source), "-o", str(target)],
                             [source], [target, dep]))
            objects.append((name, source, target, dep, assembly))
            group_objects.append(target)
        archive = directory / f"libmocs_budget_v2_{group}.a"
        commands.append(("llvm-ar.exe", ["rcs", str(archive)] + list(map(str, group_objects)), group_objects, [archive]))
        groups[group] = archive
    entry_name = next(iter(manifest["entry_sources"]))
    entry = directory / "source" / entry_name
    entry_obj = directory / "objects/budget_v2_entry.o"
    entry_dep = Path(str(entry_obj) + ".d")
    bindings = {"MOCS_BUDGET_V2_PROTOCOL": spec["protocol"], "MOCS_BUDGET_V2_RULE_IDENTITY": spec["rule_identity"],
                "MOCS_BUDGET_V2_SEARCH_IDENTITY": spec["search_source_identity"],
                "MOCS_BUDGET_V2_SPEC_SHA256": None, "MOCS_BUDGET_V2_EXPOSURE_SHA256": None}
    # Raw hashes are supplied by the separately SHA-bound recipe, checked by verify().
    objects.append((entry_name, entry, entry_obj, entry_dep, False))
    return commands, objects, groups, generated, bindings


def verify(root, spec, manifest, directory, receipt_path, receipt_sha, spec_sha, exposure_sha):
    receipt = read_json(receipt_path, receipt_sha)
    recipe_path = artifact(root, receipt["build_recipe"])
    log_path = artifact(root, receipt["build_log"])
    recipe, log = read_json(recipe_path), read_json(log_path)
    snapshot, allowed, resource = snapshot_inputs(root, spec, manifest, directory)
    admission.exact(recipe["source_snapshot_inventory"],
                    {"path": (directory / "source-snapshot.json").relative_to(root).as_posix(),
                     "sha256": digest_file(directory / "source-snapshot.json")}, "recipe snapshot inventory")
    artifact(root, recipe["ndk_input_inventory"])
    if artifact(root, recipe["ndk_input_inventory"]) != directory / "ndk-inputs.json":
        raise ValueError("wrong SDK inventory")
    for name, sha in manifest["build_tools"].items():
        admission.exact(digest_file(contained(root, name)), sha, "frozen build tool")
    admission.exact(recipe["source_groups"], spec["source_groups"], "recipe source groups")
    admission.exact(recipe["toolchain"], manifest["toolchain"], "recipe toolchain")
    admission.exact(recipe["spec"]["sha256"], spec_sha, "recipe spec SHA")
    admission.exact(recipe["exposure"]["sha256"], exposure_sha, "recipe exposure SHA")
    admission.exact(receipt["spec_sha256"], spec_sha, "receipt spec SHA")
    admission.exact(receipt["exposure_sha256"], exposure_sha, "receipt exposure SHA")
    for flag in ("native_executed", "search_performed", "strength_accepted"):
        admission.exact(recipe[flag], False, flag)
    plans, expected_objects, archives, generated, bindings = expected_commands(root, spec, manifest, directory, resource)
    admission.exact(recipe["generated_compile_options"], generated, "generated compile inputs")
    admission.exact(recipe["compile_options"], manifest["compile_options"], "base compile inputs")
    admission.exact(recipe["link_options"], manifest["link_options"], "base link inputs")
    admission.exact(recipe["entry_sources"], manifest["entry_sources"], "entry source binding")
    bindings["MOCS_BUDGET_V2_SPEC_SHA256"] = spec_sha
    bindings["MOCS_BUDGET_V2_EXPOSURE_SHA256"] = exposure_sha
    defines = ['-D' + key + '="' + value + '"' for key, value in bindings.items()]
    admission.exact(recipe["entry_defines"], defines, "compiled identity macros")
    entry_name, entry, entry_obj, entry_dep, _ = expected_objects[-1]
    plans.append(("clang++.exe", manifest["compile_options"] + generated + defines +
                  ["-MMD", "-MF", str(entry_dep), "-c", str(entry), "-o", str(entry_obj)], [entry], [entry_obj, entry_dep]))
    binary, link_map = directory / "mocs_xiangqi_budget_v2_entry", directory / "entry.link.map"
    link = []
    for flag in manifest["link_options"]:
        link.append(flag)
        if flag == "-static-libstdc++": link.append(str(entry_obj))
        if flag == "-Wl,--whole-archive": link.extend(str(archives[g]) for g in ("core", "pikafish"))
    prebuilt = Path(manifest["toolchain"]["ndk_path"]) / "toolchains/llvm/prebuilt/windows-x86_64"
    link += ["--sysroot=" + str(prebuilt / "sysroot"), "--no-default-config",
             "--ld-path=" + manifest["toolchain"]["tools"]["ld.lld.exe"]["path"], "-Wl,-Map=" + str(link_map),
             "-Wl,--no-undefined", "-o", str(binary)]
    plans.append(("clang++.exe", link, [entry_obj, archives["core"], archives["pikafish"]], [binary, link_map]))
    plans.append(("llvm-readelf.exe", ["--file-header", "--dynamic", str(binary)], [binary], []))
    commands = recipe["commands"]
    admission.exact(log["commands"], commands, "recipe/log command correspondence")
    admission.exact(log["all_commands_succeeded"], True, "complete command log")
    for flag in ("native_executed", "search_performed"):
        admission.exact(log[flag], False, "log " + flag)
    if len(commands) != 45 or len(plans) != 45 or len(recipe["objects"]) != 41:
        raise ValueError("complete 41-object/45-command build required")
    for index, (recorded, (tool, argv, inputs, outputs)) in enumerate(zip(commands, plans)):
        label = (f"core-{index:03}" if index < 4 else "archive-core" if index == 4 else
                 f"pikafish-{index-5:03}" if index < 41 else "archive-pikafish" if index == 41 else
                 "entry" if index == 42 else "link" if index == 43 else "elf-inspect")
        admission.exact(read_json(directory / f"logs/{index:03}-{label}.command.json"), recorded,
                        "individual actual command record")
        expected_tool = manifest["toolchain"]["tools"][tool]
        admission.exact(recorded["tool"], tool, "command tool")
        admission.exact(recorded["executable"], expected_tool["path"], "command executable")
        admission.exact(recorded["tool_sha256"], expected_tool["sha256"], "command tool SHA")
        admission.exact(digest_file(Path(recorded["executable"])), recorded["tool_sha256"], "current tool")
        admission.exact(recorded["argv"], argv, "actual compile/link argv")
        admission.exact(Path(recorded["cwd"]).resolve() == root, True, "command cwd")
        verify_execution_status(recorded)
        for key, paths in (("input_sha256", inputs), ("output_sha256", outputs)):
            expected = {path.relative_to(root).as_posix(): digest_file(path) for path in paths}
            admission.exact(recorded[key], expected, "actual command " + key)
        artifact(root, recorded["stdout"]); artifact(root, recorded["stderr"])
    for item, (name, source, target, dep, assembly) in zip(recipe["objects"], expected_objects):
        admission.exact(item["source"], name, "object source order")
        admission.exact(item["snapshot_source"], source.relative_to(root).as_posix(), "actual snapshot compile source")
        if artifact(root, item["object"]) != target or artifact(root, item["dependencies"]) != dep or item["assembly"] != assembly:
            raise ValueError("object/dependency artifact mismatch")
    dependencies = read_json(artifact(root, recipe["dependency_inventory"]), maximum=64 * 1024 * 1024)
    admission.exact(dependencies, collect_dependencies(root, recipe["objects"], allowed), "actual dependency SHA closure")
    for key, path in (("engine_core", archives["core"]), ("pikafish", archives["pikafish"])):
        if artifact(root, receipt["libraries"][key]) != path:
            raise ValueError("receipt library differs from actual linked archive")
    admission.exact(sorted(receipt["libraries"]), ["engine_core", "pikafish"], "exact linked library set")
    if artifact(root, receipt["binary"]) != binary or artifact(root, recipe["link_map"]) != link_map:
        raise ValueError("receipt ELF/map differs from actual link")
    return dict(status="actual-frozen-build-evidence-verified", commands=45, objects=41,
                dependency_sources=len(dependencies), native_executed=False, search_performed=False)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("check-snapshot", "dependencies", "verify"))
    for flag in ("spec-path", "spec-sha256", "manifest-path", "manifest-sha256", "build-directory"):
        parser.add_argument("--" + flag, required=True)
    parser.add_argument("--receipt-path"); parser.add_argument("--receipt-sha256")
    args = parser.parse_args()
    root = admission.ROOT.resolve()
    spec = read_json(contained(root, args.spec_path), args.spec_sha256)
    manifest = read_json(contained(root, args.manifest_path), args.manifest_sha256)
    directory = Path(args.build_directory).resolve()
    snapshot, allowed, _ = snapshot_inputs(root, spec, manifest, directory)
    if args.command == "verify":
        if not args.receipt_path or not args.receipt_sha256:
            parser.error("verify requires receipt path and external SHA")
        receipt = read_json(contained(root, args.receipt_path), args.receipt_sha256)
        result = verify(root, spec, manifest, directory, contained(root, args.receipt_path), args.receipt_sha256,
                        args.spec_sha256, receipt["exposure_sha256"])
    elif args.command == "dependencies":
        result = collect_dependencies(root, read_json(directory / "objects.json"), allowed)
    else:
        result = dict(status="frozen-snapshot-inputs-verified", source_files=len(snapshot), native_executed=False)
    sys.stdout.buffer.write(admission.encoded(result))


if __name__ == "__main__":
    try:
        main()
    except (ValueError, TypeError, KeyError, OSError, UnicodeError) as error:
        sys.exit("build evidence rejected: " + str(error))
