"""Collect native capture-and-hold evidence for unresolved enclosed groups in atari."""

import argparse
import hashlib
import json
import runpy
import subprocess
from collections import Counter
from itertools import groupby
from pathlib import Path
from uuid import uuid4


WORKSHEET = runpy.run_path(str(Path(__file__).with_name("review-strength-groups.py")))


def sole_liberty(board, anchor, group):
    size = len(board)
    color = group["color"]
    pending = [anchor]
    seen = {anchor}
    liberties = set()
    while pending:
        x, y = pending.pop()
        for nx, ny in ((x - 1, y), (x + 1, y), (x, y - 1), (x, y + 1)):
            if not (0 <= nx < size and 0 <= ny < size):
                continue
            if board[ny][nx] == ".":
                liberties.add((nx, ny))
            elif board[ny][nx] == color and (nx, ny) not in seen:
                seen.add((nx, ny))
                pending.append((nx, ny))
    if len(seen) != int(group["stones"]) or len(liberties) != 1:
        raise ValueError(f"Unexpected chain or liberty at {anchor}")
    x, y = liberties.pop()
    return f"{x},{y}"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("matrix", "raw", "worksheet", "reviewer", "output"):
        parser.add_argument(name, type=Path)
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--serial", default="emulator-5554")
    parser.add_argument("--avd", default="Pixel_7_Pro")
    parser.add_argument("--previous-atari", nargs=2, metavar=("RECEIPT", "SHA256"))
    args = parser.parse_args()
    prior_proofs = {}
    prior_hash = None
    if args.previous_atari:
        path, prior_hash = args.previous_atari
        prior = json.loads(WORKSHEET["read_verified"](Path(path), prior_hash))
        for row in prior["results"]:
            if row["status"] == "dead" and row["detail"] == ["held-territory"]:
                key = (row["slot"], row["anchor"])
                if key in prior_proofs or row["replies"] < 1:
                    raise ValueError(f"Invalid prior proof: {key}")
                prior_proofs[key] = row

    def adb(*command):
        return subprocess.run(
            [args.adb, "-s", args.serial, *map(str, command)],
            check=True, capture_output=True, text=True, timeout=180,
        ).stdout

    if adb("emu", "avd", "name").splitlines()[0] != args.avd:
        raise ValueError("Unexpected target emulator")
    evidence = WORKSHEET["evidence_rows"](args.matrix, args.raw)
    rows = WORKSHEET["checked_rows"](evidence, args.worksheet)
    remote = "/data/local/tmp/mocs-go-holds-" + uuid4().hex
    args.output.mkdir(parents=True)
    adb("shell", "mkdir", remote)

    def upload(source, name, digest):
        WORKSHEET["read_verified"](source, digest)
        target = f"{remote}/{name}"
        adb("push", source, target)
        if adb("shell", "sha256sum", target).split()[0] != digest:
            raise ValueError(f"Device transfer hash mismatch: {source}")
        return target

    reviewer_hash = hashlib.sha256(args.reviewer.read_bytes()).hexdigest()
    probe = upload(args.reviewer, "reviewer", reviewer_hash)
    adb("shell", "chmod", "700", probe)
    results = []
    reused = []
    for slot, slot_rows in groupby(rows, key=lambda row: int(row["slot"])):
        groups = list(slot_rows)
        targets = [row for row in groups if not row["decision"] and row["liberties"] == "1"
                   and row["status"] == "inside_opponent_pass_alive"]
        if not targets:
            continue
        board, _ = WORKSHEET["verified_board"](args.matrix, slot, groups)
        name = f"match-{slot:03}.mocg"
        record_hash = groups[0]["raw_sha256"]
        record = None
        for row in targets:
            anchor = f'{row["x"]},{row["y"]}'
            capture = sole_liberty(board, (int(row["x"]), int(row["y"])), row)
            prior = prior_proofs.get((slot, anchor))
            if prior is not None:
                if (prior["record_sha256"] != record_hash or prior["stones"] != int(row["stones"])
                        or prior["capture_move"] != capture):
                    raise ValueError(f"Prior proof does not match slot {slot} at {anchor}")
                reused.append({"slot": slot, "anchor": anchor, "record_sha256": record_hash})
                continue
            if record is None:
                record = upload(args.raw / name, name, record_hash)
            output = adb("shell", probe, len(board), record, "--capture-and-hold", capture, anchor)
            log = f'{slot:03}-{anchor.replace(",", "-")}.log'
            data = output.encode("utf-8")
            (args.output / log).write_bytes(data)
            lines = output.splitlines()
            if not lines or not lines[0].startswith("raw,") or not lines[-1].startswith("result,"):
                raise ValueError(f"Malformed reviewer output: {log}")
            result = lines[-1].split(",")[1:]
            if result[0] == "dead":
                marker, move, captured, defenses = lines[1].split(",")
                cases = [case.split(",") for case in lines[3:-1]]
                if (marker != "first-capture" or move.replace(":", ",") != capture
                        or int(captured) != int(row["stones"]) or len(cases) != int(defenses)
                        or len({case[0] for case in cases}) != len(cases)
                        or any(len(case) != 3 or int(case[2]) < 1 for case in cases)
                        or not cases or result != ["dead", "held-territory"]):
                    raise ValueError(f"Incomplete proof: {log}")
            elif result[0] != "unknown":
                raise ValueError(f"Unexpected reviewer result: {log}")
            results.append({
                "slot": slot, "anchor": anchor, "capture": capture, "record_sha256": record_hash,
                "result": result, "log": log, "log_sha256": hashlib.sha256(data).hexdigest(),
            })

    receipt = {"reviewer_sha256": reviewer_hash, "avd": args.avd,
               "previous_atari_sha256": prior_hash, "reused": reused, "results": results}
    (args.output / "summary.json").write_text(json.dumps(receipt, indent=2) + "\n", encoding="utf-8")
    print(Counter(row["result"][0] for row in results))
    print(f"{len(reused)} prior proofs reused")


if __name__ == "__main__":
    main()
