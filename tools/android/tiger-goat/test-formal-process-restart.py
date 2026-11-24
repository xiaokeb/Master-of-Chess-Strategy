"""Restart only the isolated Tiger/Goat Debug QA process on a Pixel 7 Pro AVD."""

import argparse
import hashlib
import json
import re
import subprocess
import time
import uuid
import xml.etree.ElementTree as ET
from pathlib import Path


APP = "com.masterofchessstrategy"
PROCESS = f"{APP}:tiger_goat_formal_qa"
ACTIVITY = f"{APP}/.ui.TigerGoatFormalProcessCheckActivity"


def run(adb, output):
    token = str(uuid.uuid4())
    database = f"tiger-goat-formal-qa-{token}.db"
    remote_proof = f"files/tiger-goat-formal-qa/{token}.json"
    remote_ui = f"/data/local/tmp/tiger-goat-{token}.xml"
    owned_pid = None

    def command(*arguments):
        return subprocess.check_output([*adb, *arguments], timeout=40)

    def pid():
        listing = command("shell", "ps", "-A", "-o", "PID,NAME").decode()
        matches = [int(fields[0]) for line in listing.splitlines()
                   if len(fields := line.split()) == 2 and fields[1] == PROCESS]
        if len(matches) > 1:
            raise RuntimeError("Multiple QA processes match the expected name")
        return next(iter(matches), None)

    def launch():
        nonlocal owned_pid
        raw = command("shell", "am", "start", "-W", "-f", "0x10008000", "-n", ACTIVITY, "--es", "qa_token", token)
        if b"Status: ok" not in raw:
            raise RuntimeError(raw.decode(errors="replace"))
        owned_pid = pid()
        if owned_pid is None:
            raise RuntimeError("QA process failed to start")

    def stop(current):
        if pid() != current:
            raise RuntimeError("QA process changed before the planned termination")
        command("shell", "run-as", APP, "kill", "-9", str(current))
        for _ in range(25):
            if pid() is None:
                return
            time.sleep(0.2)
        raise RuntimeError("QA process did not terminate")

    def nodes(label=None):
        command("shell", "uiautomator", "dump", remote_ui)
        raw = command("exec-out", "cat", remote_ui)
        if label is not None:
            (output / f"{label}.xml").write_bytes(raw)
        return ET.fromstring(raw).iter("node")

    def inspect_points(label, playable):
        points = {}
        for node in nodes(label):
            match = re.fullmatch(r"(?:空点|老虎|羊)，落点 (\d+)", node.get("content-desc", ""))
            if match:
                index = int(match[1])
                if index in points:
                    raise RuntimeError("Duplicate accessible point")
                points[index] = node
        if set(points) != set(range(37)):
            raise RuntimeError("The accessibility tree does not contain all 37 board points")
        enabled = "true" if playable else "false"
        if any(node.get("enabled") != enabled or node.get("clickable") != enabled for node in points.values()):
            raise RuntimeError("The board's accessibility actions do not match its play state")

    def tap(label, last=False):
        for _ in range(12):
            candidates = [node for node in nodes() if node.get("text") == label and node.get("enabled") == "true"]
            if candidates:
                target = candidates[-1 if last else 0]
                x1, y1, x2, y2 = map(int, re.findall(r"\d+", target.attrib["bounds"]))
                if x2 > x1 and y2 > y1:
                    command("shell", "input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))
                    return
        raise RuntimeError(f"Visible enabled control missing: {label}")

    def checkpoint(label, expected_actions, winner):
        for _ in range(80):
            raw = subprocess.run([*adb, "shell", "run-as", APP, "cat", remote_proof],
                                 capture_output=True, timeout=15)
            if raw.returncode == 0:
                value = json.loads(raw.stdout)
                if value["pid"] == owned_pid == pid() and value["database"] == database and (
                    value["action_count"], value["winner"]
                ) == (expected_actions, winner):
                    (output / f"{label}.json").write_text(json.dumps(value, indent=2) + "\n", encoding="utf-8")
                    (output / f"{label}.png").write_bytes(command("exec-out", "screencap", "-p"))
                    return value
            elif b"No such file" not in raw.stderr + raw.stdout:
                raw.check_returncode()
            time.sleep(0.5)
        raise RuntimeError(f"Stable persisted checkpoint not seen: {label}")

    def reopened(previous, label):
        stop(previous["pid"])
        launch()
        tap("继续正式人机")
        current = checkpoint(label, previous["action_count"], previous["winner"])
        if current["pid"] == previous["pid"] or current != dict(previous, pid=current["pid"]):
            raise RuntimeError("The reopened Native and Room checkpoint differs from the original")
        print(f"{label}: {previous['pid']} -> {current['pid']}", flush=True)
        return current

    if b"Pixel_7_Pro" not in command("emu", "avd", "name").splitlines() or pid() is not None:
        raise RuntimeError("Expected an idle Pixel 7 Pro QA process window")
    font_scale = command("shell", "settings", "get", "system", "font_scale").decode().strip()
    output.mkdir(parents=True)
    print(output, flush=True)
    launch()
    try:
        tap("正式人机 · 执羊")
        first = checkpoint("active-before", 1, "NONE")
        if (first["phase"], first["human_side"], first["to_move"]) != ("PLAY", 2, 2):
            raise RuntimeError("Expected AI opening removal and the human goat turn")
        active = reopened(first, "active-after")
        inspect_points("active-accessibility", playable=True)
        tap("认输")
        tap("认输", last=True)
        finished = checkpoint("finished-before", 2, "TIGER")
        if finished["session_id"] != active["session_id"] or finished["phase"] != "FINISHED":
            raise RuntimeError("Resignation did not complete the resumed session")
        terminal = reopened(finished, "finished-after")
        inspect_points("finished-accessibility", playable=False)
    finally:
        if owned_pid is not None and pid() == owned_pid:
            stop(owned_pid)
    evidence = {path.name: hashlib.sha256(path.read_bytes()).hexdigest() for path in sorted(output.iterdir())}
    receipt = {"avd": "Pixel_7_Pro", "abi": "x86_64", "font_scale": font_scale,
               "active_pids": [first["pid"], active["pid"]], "finished_pids": [finished["pid"], terminal["pid"]],
               "kills_verified": 2, "evidence": evidence}
    (output / "device-receipt.json").write_text(json.dumps(receipt, indent=2) + "\n", encoding="utf-8")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", type=Path, required=True)
    parser.add_argument("--serial", default="emulator-5554")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    run([str(args.adb), "-s", args.serial], args.output)
