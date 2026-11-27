"""Prepare and validate a group-by-group worksheet for the Go score reviewer."""

import argparse
import csv
import hashlib
import json
import re
from pathlib import Path


GROUP_FIELDS = ("x", "y", "color", "stones", "liberties", "status")
FIELDS = ("slot", "raw_sha256", "board_sha256", "groups_sha256", *GROUP_FIELDS, "decision")
STATUSES = {"pass_alive", "unresolved", "inside_opponent_pass_alive"}


def read_verified(path, digest):
    data = path.read_bytes()
    if hashlib.sha256(data).hexdigest() != digest:
        raise ValueError(f"Evidence hash mismatch: {path}")
    return data


def read_capture_proof(output, capture, stone_count, liberties, log):
    lines = output.splitlines()
    if not lines or not lines[0].startswith("raw,") or not lines[-1].startswith("result,"):
        raise ValueError(f"Malformed reviewer output: {log}")
    result = lines[-1].split(",")[1:]
    if result[0] == "unknown":
        return result
    marker, move, captured, defenses = lines[1].split(",")
    cases = [case.split(",") for case in lines[3:-1]]
    expected = ("first-capture", stone_count) if liberties == 1 else ("first-liberty", 0)
    if ((marker, int(captured)) != expected or move.replace(":", ",") != capture
            or len(cases) != int(defenses) or len({case[0] for case in cases}) != len(cases)
            or any(len(case) != 3 or int(case[2]) < 1 for case in cases)
            or not cases or result != ["dead", "held-territory"]):
        raise ValueError(f"Incomplete proof: {log}")
    return result


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


def verified_board(directory, slot, groups):
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
    if any(y >= size or x >= size or board[y][x] != row["color"]
           for (x, y), row in anchors.items()):
        raise ValueError(f"Group anchor does not match board: {source}")
    return board, anchors


def render_board(directory, slot, rows, output):
    groups = [row for row in rows if int(row["slot"]) == slot]
    if not groups:
        raise ValueError(f"No inspected board for slot {slot}")
    board, anchors = verified_board(directory, slot, groups)
    size = len(board)
    labels = {"pass_alive": "A", "unresolved": "?", "inside_opponent_pass_alive": "R"}
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


GROUP_KEY = ("slot", "x", "y")


def read_worksheet(worksheet):
    reviewed = {}
    with worksheet.open(encoding="utf-8-sig", newline="") as stream:
        edited = csv.DictReader(stream, delimiter="\t")
        if tuple(edited.fieldnames or ()) != FIELDS:
            raise ValueError("Invalid worksheet header")
        for actual in edited:
            key = tuple(actual[field] for field in GROUP_KEY)
            if None in actual or key in reviewed:
                raise ValueError(f"Invalid or duplicate group: {key}")
            reviewed[key] = actual
    return reviewed


def verify_worksheet_rows(rows, reviewed):
    checked = []
    for expected in rows:
        key = tuple(expected[field] for field in GROUP_KEY)
        actual = reviewed.pop(key, None)
        if actual is None or any(expected[field] != actual[field] for field in FIELDS[:-1]):
            raise ValueError(f"Evidence changed for group {key}")
        selected = actual["decision"]
        if selected not in ("", "disputed", "alive", "dead"):
            raise ValueError(f"Invalid decision for slot {expected['slot']}")
        if selected == "dead" and expected["status"] == "pass_alive":
            raise ValueError(f"Dead decision conflicts with pass-alive evidence in slot {expected['slot']}")
        checked.append(actual)
    if reviewed:
        raise ValueError("Worksheet contains unknown groups")
    return checked


def checked_rows(rows, worksheet):
    return verify_worksheet_rows(rows, read_worksheet(worksheet))


def select_slots(rows, worksheet, slots):
    selected_slots = set(map(str, slots))
    checked = checked_rows(rows, worksheet)
    selected = [row for row in checked if row["slot"] in selected_slots]
    if {row["slot"] for row in selected} != selected_slots:
        raise ValueError("Selection contains an uninspected slot")
    return selected


def merge_reviews(rows, worksheet, subset):
    original = checked_rows(rows, worksheet)
    reviewed = read_worksheet(subset)
    if not reviewed:
        raise ValueError("Review subset has no groups")
    slots = {key[0] for key in reviewed}
    verified = verify_worksheet_rows([row for row in rows if row["slot"] in slots], reviewed)
    updates = {tuple(row[field] for field in GROUP_KEY): row["decision"] for row in verified}
    for row in original:
        selected = updates.get(tuple(row[field] for field in GROUP_KEY))
        if not selected:
            continue
        if row["decision"] in ("alive", "dead") and selected != row["decision"]:
            raise ValueError(f"Review conflicts with an existing decision in slot {row['slot']}")
        row["decision"] = selected
    return original


def decisions(rows, worksheet):
    pending = set()
    results = {}
    for row in checked_rows(rows, worksheet):
        slot = row["slot"]
        selected = row["decision"]
        groups = results.setdefault(slot, [])
        if selected == "dead":
            groups.append(f"{row['x']},{row['y']}")
        if selected in ("", "disputed"):
            pending.add(slot)
    return [{"slot": slot, "dead_groups": ";".join(groups) or "-"}
            for slot, groups in results.items() if slot not in pending]


def review_queue(rows, worksheet):
    return queue_from_rows(checked_rows(rows, worksheet))


def queue_from_rows(rows):
    slots = {}
    for row in rows:
        slot = row["slot"]
        work = slots.setdefault(slot, {
            "slot": slot, "unreviewed": 0, "disputed": 0, "priority_points": [],
        })
        if row["decision"] == "disputed":
            work["disputed"] += 1
        elif row["decision"] == "":
            work["unreviewed"] += 1
            if row["status"] == "inside_opponent_pass_alive":
                work["priority_points"].append(f"{row['x']},{row['y']}")
    pending = [work for work in slots.values() if work["unreviewed"] or work["disputed"]]
    return [
        {**work, "priority_points": ";".join(work["priority_points"])}
        for work in sorted(pending, key=lambda work: (
            work["disputed"] > 0, work["unreviewed"], int(work["slot"]),
        ))
    ]


def proof_queue(rows, worksheet, receipts):
    checked = checked_rows(rows, worksheet)
    groups = {(int(row["slot"]), f"{row['x']},{row['y']}"): row for row in checked}
    proved_by_slot = {}
    for path, digest in receipts:
        path = Path(path)
        receipt = json.loads(read_verified(path, digest))
        liberties = receipt.get("liberties", 1)
        if liberties not in (1, 2):
            raise ValueError(f"Invalid liberty count in {path}")
        for entry in receipt["results"]:
            key = entry["slot"], entry["anchor"]
            row = groups.get(key)
            if (row is None or entry["record_sha256"] != row["raw_sha256"]
                    or row["status"] != "inside_opponent_pass_alive"
                    or int(row["liberties"]) != liberties
                    or not re.fullmatch(r"\d{1,2},\d{1,2}", entry["capture"])):
                raise ValueError(f"Proof does not match worksheet group {key}")
            name = f'{entry["slot"]:03}-{entry["anchor"].replace(",", "-")}'
            if liberties == 2:
                name += "-" + entry["capture"].replace(",", "-")
            log = entry["log"]
            if log != name + ".log":
                raise ValueError(f"Unexpected proof filename: {log}")
            data = read_verified(path.parent / log, entry["log_sha256"])
            result = read_capture_proof(data.decode("utf-8"), entry["capture"], int(row["stones"]), liberties, log)
            if result != entry["result"]:
                raise ValueError(f"Receipt disagrees with proof output: {log}")
            if result[0] == "dead" and row["decision"] in ("", "disputed"):
                proved_by_slot.setdefault(row["slot"], set()).add(entry["anchor"])
    pending = queue_from_rows(checked)
    for entry in pending:
        proved = proved_by_slot.get(entry["slot"], set())
        entry["capture_proofs"] = len(proved)
        entry["missing_proofs"] = entry["unreviewed"] + entry["disputed"] - len(proved)
        entry["proved_anchors"] = ";".join(sorted(proved))
    return sorted(pending, key=lambda entry: (
        entry["disputed"] > 0, entry["missing_proofs"], -entry["capture_proofs"], int(entry["slot"]),
    ))


def describe_groups(directory, rows, worksheet, slots):
    descriptions = []
    selected = select_slots(rows, worksheet, slots)

    def coordinates(points):
        return ";".join(f"{x},{y}" for x, y in sorted(points, key=lambda point: (point[1], point[0])))

    for slot in sorted(set(slots)):
        groups = [row for row in selected if int(row["slot"]) == slot]
        board, anchors = verified_board(directory, slot, groups)
        size = len(board)
        seen = set()
        liberties_by_group = {}
        stones_by_group = {}

        def neighbours(x, y):
            return ((nx, ny) for nx, ny in ((x - 1, y), (x + 1, y), (x, y - 1), (x, y + 1))
                    if 0 <= nx < size and 0 <= ny < size)

        for anchor, row in anchors.items():
            group = {anchor}
            liberties = set()
            pending = [anchor]
            while pending:
                for x, y in neighbours(*pending.pop()):
                    cell = board[y][x]
                    point = (x, y)
                    if cell == ".":
                        liberties.add(point)
                    elif cell == row["color"] and point not in group:
                        group.add(point)
                        pending.append(point)
            if seen & group or len(group) != int(row["stones"]) or len(liberties) != int(row["liberties"]):
                raise ValueError(f"Group geometry disagrees with the native inventory in slot {slot}: {anchor}")
            seen.update(group)
            stones_by_group[anchor] = group
            liberties_by_group[anchor] = liberties
        if len(seen) != sum(cell != "." for line in board for cell in line):
            raise ValueError(f"Incomplete group inventory for slot {slot}")

        for anchor, row in anchors.items():
            if row["decision"] in ("alive", "dead"):
                continue
            liberties = liberties_by_group[anchor]
            nearby = [
                f"{ally[0]},{ally[1]}:{coordinates(liberties & liberties_by_group[ally])}"
                for ally, other in anchors.items()
                if ally != anchor and other["color"] == row["color"] and other["status"] == "pass_alive"
                and liberties & liberties_by_group[ally]
            ]
            descriptions.append({
                "slot": slot, "anchor": coordinates([anchor]), "color": row["color"],
                "decision": row["decision"], "stones": coordinates(stones_by_group[anchor]),
                "liberties": coordinates(liberties), "shared_liberties_with_pass_alive": " | ".join(nearby),
            })
    return descriptions


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
    queue = actions.add_parser("queue", help="list pending slots and priority anchor points from a verified worksheet")
    queue.add_argument("worksheet", type=Path)
    queue.add_argument("output", type=Path)
    proofs = actions.add_parser("proof-queue", help="prioritize slots using verified capture receipts")
    proofs.add_argument("worksheet", type=Path)
    proofs.add_argument("output", type=Path)
    proofs.add_argument("--receipt", action="append", nargs=2, metavar=("PATH", "SHA256"), required=True)
    select = actions.add_parser("select", help="extract full groups of selected slots from a verified worksheet")
    select.add_argument("worksheet", type=Path)
    select.add_argument("output", type=Path)
    select.add_argument("slots", type=int, nargs="+")
    merge = actions.add_parser("merge", help="merge a complete, verified slot subset into a new worksheet")
    merge.add_argument("worksheet", type=Path)
    merge.add_argument("subset", type=Path)
    merge.add_argument("output", type=Path)
    describe = actions.add_parser("describe", help="recompute board geometry for pending groups in selected slots")
    describe.add_argument("worksheet", type=Path)
    describe.add_argument("output", type=Path)
    describe.add_argument("slots", type=int, nargs="+")
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
    elif args.action == "queue":
        pending = review_queue(rows, args.worksheet)
        write_table(args.output, ("slot", "unreviewed", "disputed", "priority_points"), pending)
        print(f"Listed {len(pending)} pending slots")
    elif args.action == "proof-queue":
        pending = proof_queue(rows, args.worksheet, args.receipt)
        write_table(args.output, ("slot", "unreviewed", "disputed", "priority_points",
                                  "capture_proofs", "missing_proofs", "proved_anchors"), pending)
        print(f"Listed {len(pending)} pending slots with {sum(row['capture_proofs'] for row in pending)} capture proofs")
    elif args.action == "select":
        selected = select_slots(rows, args.worksheet, args.slots)
        write_table(args.output, FIELDS, selected)
        print(f"Selected {len(selected)} groups for {len(set(args.slots))} slots")
    elif args.action == "merge":
        merged = merge_reviews(rows, args.worksheet, args.subset)
        write_table(args.output, FIELDS, merged)
        print(f"Wrote {len(merged)} groups to the merged worksheet")
    elif args.action == "describe":
        descriptions = describe_groups(args.inspection, rows, args.worksheet, args.slots)
        write_table(args.output, ("slot", "anchor", "color", "decision", "stones", "liberties",
                                  "shared_liberties_with_pass_alive"), descriptions)
        print(f"Described {len(descriptions)} pending groups")
    else:
        render_board(args.inspection, args.slot, rows, args.output)
        print(f"Rendered terminal board for slot {args.slot}")


if __name__ == "__main__":
    main()
