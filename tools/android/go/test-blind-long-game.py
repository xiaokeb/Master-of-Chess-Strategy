"""Run the 19x19 blind KataGo QA game on Pixel_7_Pro using a frozen build receipt."""

import argparse
import hashlib
import json
import re
import sqlite3
import subprocess
import time
import uuid
import xml.etree.ElementTree as ET
from pathlib import Path


APP = "com.masterofchessstrategy"
OWNER = APP + ":go_formal_qa"
MODE = "blind-long19"
SELECTOR = APP + ".games.go.data.GoFormalProcessCheckpointInstrumentedTest#seed"
POINT = re.compile(r"^第\d+行第\d+列，([^，]+)")


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def run_game(adb, device, candidate, output, resume_before_midgame):
    command = [str(adb), "-s", device]
    token = str(resume_before_midgame or uuid.uuid4())
    database = f"go-formal-process-qa-{token}.db"
    match_id = str(uuid.UUID(bytes=hashlib.md5(f"{token}:{MODE}".encode()).digest(), version=3))
    receipt = "files/go-formal-process-qa/" + database.removesuffix(".db") + ".json"
    remote_ui = f"/data/local/tmp/mocs-go-blind-{token}.xml"

    def call(*args, timeout=60):
        return subprocess.check_output(command + list(args), timeout=timeout)

    def owner_pid():
        result = subprocess.run(command + ["shell", "pidof", OWNER], capture_output=True, timeout=15)
        if result.returncode == 1 and not result.stdout and not result.stderr:
            return None
        result.check_returncode()
        return int(result.stdout)

    def stored_state():
        result = subprocess.run(command + ["shell", "run-as", APP, "cat", receipt],
                                capture_output=True, timeout=15)
        if b"No such file" in result.stderr + result.stdout:
            return None
        result.check_returncode()
        if not result.stdout.strip():
            return None
        value = json.loads(result.stdout)
        require(value["match_id"] == match_id and value["database"] == database,
                "Unexpected fixture identity")
        require(value["blind"] and value["automatic"] and value["board_size"] == 19,
                "The loaded game lost its blind automatic configuration")
        return value

    def state():
        value = stored_state()
        return value if value is not None and value["pid"] == owner_pid() else None

    def await_state(predicate, timeout=90):
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            try:
                value = state()
            except subprocess.TimeoutExpired:
                # A slow ADB read does not establish that the QA owner exited.
                value = None
            if value is not None and predicate(value):
                return value
            time.sleep(.5)
        raise RuntimeError("No matching committed game receipt")

    def save(name, value):
        (output / (name + ".json")).write_text(json.dumps(value, indent=2) + "\n", encoding="utf-8")

    def launch():
        result = call("shell", "am", "start", "-W", "-n", APP + "/.ui.GoFormalProcessCheckActivity",
                      "--es", "owned_database", database, "--es", "match_id", match_id)
        require(b"Status: ok" in result, result)

    def stop(value):
        require(owner_pid() == value["pid"], "Dedicated owner identity changed")
        call("shell", "run-as", APP, "kill", "-9", str(value["pid"]))
        for _ in range(40):
            if owner_pid() is None:
                return
            time.sleep(.25)
        raise RuntimeError("Dedicated owner remained active")

    def ui(name):
        call("shell", "uiautomator", "dump", remote_ui)
        raw = call("exec-out", "cat", remote_ui)
        (output / (name + ".xml")).write_bytes(raw)
        return ET.fromstring(raw)

    def tap(tree, label):
        for node in tree.iter("node"):
            if node.get("text") == label and node.get("enabled") == "true":
                x1, y1, x2, y2 = map(int, re.findall(r"\d+", node.get("bounds", "")))
                if x2 > x1 and y2 > y1:
                    call("shell", "input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))
                    return
        raise RuntimeError(f"Visible control missing: {label}")

    def capture(name, hidden):
        tree = ui(name)
        points = {}
        for node in tree.iter("node"):
            description = node.get("content-desc", "")
            cell = POINT.match(description)
            if cell:
                points[description.split("，", 1)[0]] = cell[1]
        require(len(points) == 361, f"{name}: expected all 361 intersections")
        occupied = set(points.values()) - {"空点"}
        require(occupied == ({"棋子背面"} if hidden else {"黑子", "白子"}),
                f"{name}: unexpected occupied-point descriptions: {occupied}")
        (output / (name + ".png")).write_bytes(call("exec-out", "screencap", "-p"))
        return tree, points

    def verify_archive():
        require(owner_pid() is None, "Archive can only be inspected after stopping its owner")
        directory = output / "storage"
        directory.mkdir()
        available = call("shell", "run-as", APP, "ls", "databases").decode().splitlines()
        for name in (database, database + "-wal"):
            if name in available:
                (directory / name).write_bytes(call("exec-out", "run-as", APP, "cat", "databases/" + name))
        db_uri = (directory / database).resolve().as_uri() + "?mode=ro"
        with sqlite3.connect(db_uri, uri=True) as db:
            result = {
                "archives": db.execute("select recordId from go_ai_archives").fetchall(),
                "records": db.execute("select recordId from game_records").fetchall(),
                "projections": db.execute("select recordId from go_ai_projections").fetchall(),
                "outcomes": db.execute("select matchId from match_outcomes").fetchall(),
            }
        record = hashlib.sha256(("MasterofChessStrategy:go-ai-record:v1\0" + match_id).encode()).hexdigest()
        require(result["archives"] == result["records"] == result["projections"] == [(record,)]
                and not result["outcomes"], "Duplicate or ranked settlement from an automatic game")
        save("storage", result)

    require(b"Pixel_7_Pro" in call("emu", "avd", "name").splitlines(), "Unexpected AVD")
    require(resume_before_midgame or owner_pid() is None, "A Go QA owner is already active")
    require(not re.search(rb"ActiveInstrumentation\{", call("shell", "dumpsys", "activity", "instrumentation")),
            "Active emulator instrumentation")
    build = json.loads((candidate / "build-receipt.json").read_text(encoding="utf-8-sig"))
    require(len(build["products"]) == 2, "Expected application and instrumentation APKs")
    for path, digest in build["products"].items():
        apk = candidate / "source" / path
        with apk.open("rb") as stream:
            require(hashlib.file_digest(stream, "sha256").hexdigest() == digest, f"APK checksum drift: {apk}")
        if resume_before_midgame:
            package = APP + (".test" if "androidTest" in path else "")
            installed, = [line.removeprefix("package:") for line in
                          call("shell", "pm", "path", package).decode().splitlines() if line.endswith("/base.apk")]
            require(call("shell", "sha256sum", installed).decode().split()[0] == digest,
                    f"Installed APK checksum drift: {package}")
        else:
            require(b"Success" in call("install", "-r", "-t", str(apk)), f"Could not install {apk}")
    if resume_before_midgame:
        seed = (output / "seed.log").read_bytes()
        existing = stored_state()
        require(existing is not None and existing["phase"] == "PLAYING" and existing["event_count"] < 80,
                "Resume is limited to the same fixture before the planned midgame checkpoint")
        current = owner_pid()
        require(current is None or current == existing["pid"], "Another QA owner is running")
    else:
        output.mkdir(parents=False)
        seed = call("shell", "am", "instrument", "-w", "-r", "-e", "class", SELECTOR,
                    "-e", "go_process_token", token, "-e", "go_process_case", MODE,
                    APP + ".test/androidx.test.runner.AndroidJUnitRunner")
        (output / "seed.log").write_bytes(seed)
    require(b"OK (1 test)" in seed and b"INSTRUMENTATION_CODE: -1" in seed, "Could not seed blind game")
    started = time.monotonic()
    try:
        if resume_before_midgame:
            if current is None:
                launch()
            restored = await_state(lambda value: value["event_count"] >= existing["event_count"])
            if current is None:
                require(all(restored[key] == existing[key] for key in existing.keys() - {"pid"}),
                        "Early checkpoint changed on reopen")
            save("resumed-early", restored)
            initial_ui = ui("resumed-early")
            if any(node.get("text") == "继续思考" for node in initial_ui.iter("node")):
                tap(initial_ui, "继续思考")
        else:
            launch()
            initial = await_state(lambda value: value["event_count"] == 0)
            require(initial["series_game"] == initial["series_limit"] == 2, "Unexpected series state")
            save("initial", initial)
            tap(ui("initial"), "继续思考")
        await_state(lambda value: value["event_count"] >= 80, 300)
        tap(ui("midgame-running"), "暂停 AI")
        tree, before_points = capture("midgame-before", hidden=True)
        require(any(node.get("text") == "继续思考" for node in tree.iter("node")), "Midgame was not paused")
        before = await_state(lambda value: value["event_count"] >= 80)
        save("midgame-before", before)
        stop(before)
        launch()
        after = await_state(lambda value: value["pid"] != before["pid"])
        require(all(after[key] == before[key] for key in before.keys() - {"pid"}), "Checkpoint changed on reopen")
        tree, after_points = capture("midgame-after", hidden=True)
        require(before_points == after_points, "Blind board changed on reopen")
        save("midgame-after", after)
        tap(tree, "继续思考")
        with (output / "progress.jsonl").open("w", encoding="utf-8") as progress:
            last = after
            deadline = time.monotonic() + 900
            while last["phase"] == "PLAYING" and time.monotonic() < deadline:
                value = await_state(lambda value: value["event_count"] > last["event_count"], 60)
                require(value["pid"] == after["pid"], "Owner changed during play")
                item = {"elapsed_seconds": round(time.monotonic() - started, 2), **value}
                progress.write(json.dumps(item) + "\n")
                progress.flush()
                if value["event_count"] // 20 > last["event_count"] // 20:
                    print(f"{value['event_count']} events", flush=True)
                last = value
        require(last["phase"] == "SCORING" and last["event_count"] >= 150, "No representative game reached scoring")
        save("scoring", last)
        tree, _ = capture("scoring", hidden=False)
        tap(tree, "黑方确认")
        tap(ui("black-confirmed"), "白方确认")
        final = await_state(lambda value: value["phase"] == "FINISHED" and value["completion_stored"])
        require(final["event_count"] == last["event_count"] + 2 and final["black_confirmed"] and final["white_confirmed"]
                and final["archive_count"] == final["record_count"] == 1, "Unexpected terminal settlement")
        save("terminal", final)
        capture("terminal", hidden=False)
        stop(final)
        launch()
        restored = await_state(lambda value: value["pid"] != final["pid"] and value["completion_stored"])
        require(all(restored[key] == final[key] for key in final.keys() - {"pid"}), "Terminal settlement changed on reopen")
        save("terminal-after", restored)
        stop(restored)
        verify_archive()
        print(f"Verified {final['event_count']} events and one archive in {output}", flush=True)
    finally:
        subprocess.run(command + ["shell", "rm", "-f", remote_ui], check=False, timeout=20)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", type=Path, required=True)
    parser.add_argument("--device", required=True)
    parser.add_argument("--candidate", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--resume-before-midgame", type=uuid.UUID)
    args = parser.parse_args()
    run_game(args.adb, args.device, args.candidate, args.output, args.resume_before_midgame)
