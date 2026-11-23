"""Prepare and validate a group-by-group worksheet for the Go score reviewer."""

import argparse
import csv
import hashlib
import json
from pathlib import Path


GROUP_FIELDS = ("x", "y", "color", "stones", "liberties", "status")
FIELDS = ("slot", "raw_sha256", "board_sha256", "groups_sha256", *GROUP_FIELDS, "decision")
STATUSES = {"pass_alive", "unresolved", "inside_opponent_pass_alive"}


def read_verified(path, digest):
    data = path.read_bytes()
    if hashlib.sha256(data).hexdigest() != digest:
        raise ValueError(f"Evidence hash mismatch: {path}")
    return data


def evidence_rows(directory, raw_directory):
    manifest = json.loads((directory / "inspection-receipt.json").read_text(encoding="utf-8-sig"))
    seen_slots = set()
    rows = []
    for evidence in sorted(manifest["slots"], key=lambda slot: slot["slot"]):
        slot, size = evidence["slot"], evidence["size"]
        if slot in seen_slots or slot not in range(144) or size not in (9, 13, 19):
            raise ValueError(f"Unexpected review slot: {slot}")
        seen_slots.add(slot)
        name = f"match-{slot:03}"
        read_verified(raw_directory / f"{name}.mocg", evidence["raw_sha256"])
        read_verified(directory / f"{name}-board.txt", evidence["board_sha256"])
        raw = read_verified(directory / f"{name}-groups.csv", evidence["groups_sha256"])
        groups = csv.DictReader(raw.decode("utf-8-sig").splitlines())
        if tuple(groups.fieldnames or ()) != GROUP_FIELDS:
            raise ValueError(f"Invalid group header: {name}")
        start = len(rows)
        seen_points = set()
        for group in groups:
            if None in group or any(value is None for value in group.values()):
                raise ValueError(f"Invalid group fields in {name}")
            x, y = int(group["x"]), int(group["y"])
            if (x, y) in seen_points or not (0 <= x < size and 0 <= y < size) or (
                group["color"] not in ("B", "W") or group["status"] not in STATUSES
            ):
                raise ValueError(f"Invalid group in {name}: {group}")
            seen_points.add((x, y))
            rows.append({**{key: str(evidence[key]) for key in FIELDS[:4]}, **group, "decision": ""})
        if len(rows) == start:
            raise ValueError(f"No groups for {name}")
    if not rows:
        raise ValueError("No inspected boards")
    return rows


def write_table(path, fields, rows):
    with path.open("x", encoding="utf-8", newline="") as stream:
        writer = csv.DictWriter(stream, fields, delimiter="\t", lineterminator="\n")
        writer.writeheader()
        writer.writerows(rows)


def render_board(directory, slot, rows, output):
    groups = [row for row in rows if int(row["slot"]) == slot]
    if not groups:
        raise ValueError(f"No inspected board for slot {slot}")
    source = directory / f"match-{slot:03}-board.txt"
    lines = read_verified(source, groups[0]["board_sha256"]).decode("utf-8-sig").splitlines()
    size = len(lines) - 1
    records = [line.split() for line in lines[1:]]
    board = [record[1:] for record in records]
    if (
        size not in (9, 13, 19)
        or lines[0].split() != [str(i) for i in range(size)]
        or any(not record or record[0] != str(y) for y, record in enumerate(records))
        or any(len(row) != size or set(row) - {".", "B", "W"} for row in board)
    ):
        raise ValueError(f"Invalid board: {source}")
    anchors = {(int(group["x"]), int(group["y"])): group for group in groups}
    labels = {"pass_alive": "A", "unresolved": "?", "inside_opponent_pass_alive": "R"}
    if any(y >= size or x >= size or board[y][x] != row["color"]
           for (x, y), row in anchors.items()):
        raise ValueError(f"Group anchor does not match board: {source}")
    extent = 36 * (size - 1)
    margin = 40
    edge = margin + extent
    svg = [
        f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {edge + margin} {edge + margin + 22}">',
        f'<title>Go terminal board, slot {slot:03}, {size} lines</title>',
        '<rect width="100%" height="100%" fill="#ead3a4"/>',
        '<g font-family="sans-serif" text-anchor="middle">',
    ]
    for i in range(size):
        point = margin + 36 * i
        svg += [
            f'<path d="M {margin} {point} H {edge} M {point} {margin} V {edge}" stroke="#6e5530"/>',
            f'<text x="{point}" y="18" font-size="12">{i}</text>',
            f'<text x="15" y="{point + 4}" font-size="12">{i}</text>',
        ]
    for y, row in enumerate(board):
        for x, color in enumerate(row):
            if color == ".":
                continue
            cx, cy = margin + 36 * x, margin + 36 * y
            fill = "#202018" if color == "B" else "#fffaf0"
            svg.append(f'<circle cx="{cx}" cy="{cy}" r="15" fill="{fill}" stroke="#202018"/>')
            group = anchors.get((x, y))
            if group:
                label = labels[group["status"]]
                ink = "#fffaf0" if color == "B" else "#202018"
                svg.append(f'<text x="{cx}" y="{cy + 5}" fill="{ink}" font-size="15">{label}</text>')
    svg += [
        f'<text x="{(edge + margin) // 2}" y="{edge + 38}" font-size="14">'
        'A = pass alive   ? = unresolved   R = review first</text>',
        '</g></svg>',
    ]
    with output.open("x", encoding="utf-8", newline="\n") as stream:
        stream.write("\n".join(svg) + "\n")


def decisions(rows, worksheet):
    pending = set()
    results = {}
    reviewed = {}
    keys = ("slot", "x", "y")
    with worksheet.open(encoding="utf-8-sig", newline="") as stream:
        edited = csv.DictReader(stream, delimiter="\t")
        if tuple(edited.fieldnames or ()) != FIELDS:
            raise ValueError("Invalid worksheet header")
        for actual in edited:
            key = tuple(actual[field] for field in keys)
            if None in actual or key in reviewed:
                raise ValueError(f"Invalid or duplicate group: {key}")
            reviewed[key] = actual
    for expected in rows:
        key = tuple(expected[field] for field in keys)
        actual = reviewed.pop(key, None)
        if actual is None or any(expected[field] != actual[field] for field in FIELDS[:-1]):
            raise ValueError(f"Evidence changed for group {key}")
        slot = expected["slot"]
        selected = actual["decision"]
        if selected not in ("", "disputed", "alive", "dead"):
            raise ValueError(f"Invalid decision for slot {slot}")
        if selected == "dead" and expected["status"] == "pass_alive":
            raise ValueError(f"Dead decision conflicts with pass-alive evidence in slot {slot}")
        groups = results.setdefault(slot, [])
        if selected == "dead":
            groups.append(f"{expected['x']},{expected['y']}")
        if selected in ("", "disputed"):
            pending.add(slot)
    if reviewed:
        raise ValueError("Worksheet contains unknown groups")
    return [{"slot": slot, "dead_groups": ";".join(groups) or "-"}
            for slot, groups in results.items() if slot not in pending]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("inspection", type=Path)
    parser.add_argument("raw_directory", type=Path)
    actions = parser.add_subparsers(dest="action", required=True)
    prepare = actions.add_parser("prepare")
    prepare.add_argument("output", type=Path)
    export = actions.add_parser("export")
    export.add_argument("worksheet", type=Path)
    export.add_argument("output", type=Path)
    render = actions.add_parser("render", help="draw a verified terminal board and group anchors")
    render.add_argument("slot", type=int)
    render.add_argument("output", type=Path)
    args = parser.parse_args()
    rows = evidence_rows(args.inspection, args.raw_directory)
    if args.action == "prepare":
        write_table(args.output, FIELDS, rows)
        print(f"Prepared {len(rows)} groups for {len({row['slot'] for row in rows})} slots")
    elif args.action == "export":
        accepted = decisions(rows, args.worksheet)
        if not accepted:
            raise ValueError("No fully reviewed slots")
        write_table(args.output, ("slot", "dead_groups"), accepted)
        print(f"Exported {len(accepted)} fully reviewed slots")
    else:
        render_board(args.inspection, args.slot, rows, args.output)
        print(f"Rendered terminal board for slot {args.slot}")


if __name__ == "__main__":
    main()
