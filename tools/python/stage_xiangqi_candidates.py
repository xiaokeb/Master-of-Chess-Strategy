"""Validate native Xiangqi authoring output without publishing unreviewed puzzles.

The native proof and later Android rule tests remain authoritative. This tool
checks the stream envelope, reproducibility metadata, board syntax and mirrors.
"""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import re

from audit_chinese_chess_endgames import AuditError, parse_pack


HEADER = re.compile(
    r"CANDIDATE ([1-9][0-9]*) seed=([0-9]+) attempt=([0-9]+) "
    r"fen=(\S+ \S+ \S+ \S+ \S+ \S+)"
)
SYMBOLS = {
    "GENERAL": "K", "ADVISOR": "A", "ELEPHANT": "B", "HORSE": "N",
    "CHARIOT": "R", "CANNON": "C", "SOLDIER": "P",
}


def _fen_board(pieces: list[str]) -> str:
    board = [["" for _ in range(9)] for _ in range(10)]
    for line in pieces:
        parts = line.split("|")
        if len(parts) != 5 or parts[0] != "PIECE":
            raise AuditError("malformed candidate piece")
        try:
            x, y = int(parts[1]), int(parts[2])
            symbol = SYMBOLS[parts[4]]
        except (ValueError, KeyError) as error:
            raise AuditError("invalid candidate piece") from error
        if parts[3] not in ("RED", "BLACK") or not 0 <= x <= 8 or not 0 <= y <= 9 or board[y][x]:
            raise AuditError("invalid candidate piece square")
        board[y][x] = symbol if parts[3] == "RED" else symbol.lower()
    rows = []
    for row in board:
        encoded, empty = "", 0
        for symbol in row:
            if symbol:
                if empty:
                    encoded += str(empty)
                    empty = 0
                encoded += symbol
            else:
                empty += 1
        rows.append(encoded + (str(empty) if empty else ""))
    return "/".join(rows)


def stage_candidates(stream: str, released_pack: str) -> dict[str, object]:
    """Return review metadata only; never write candidate positions to the APK."""
    if len(stream.encode("utf-8")) > 2 * 1024 * 1024:
        raise AuditError("candidate stream exceeds 2 MiB")
    released = parse_pack(released_pack)[1]
    released_positions = {level.symmetry_key() for level in released}
    blocks: list[tuple[re.Match[str], str, list[str], list[str]]] = []
    current: tuple[re.Match[str], str, list[str], list[str]] | None = None
    for line in stream.splitlines():
        line = line.strip()
        if not line:
            continue
        header = HEADER.fullmatch(line)
        if header:
            current = (header, "", [], [])
            blocks.append(current)
            continue
        if current is None:
            raise AuditError("candidate stream begins without a header")
        if line.startswith("PROOF|"):
            if current[1] or current[2] or current[3]:
                raise AuditError("candidate proof is duplicate or out of order")
            current = (current[0], line, current[2], current[3])
            blocks[-1] = current
        elif line.startswith("PIECE|"):
            if not current[1] or current[3]:
                raise AuditError("candidate pieces are out of order")
            current[2].append(line)
        elif line.startswith("MOVE|"):
            if not current[2]:
                raise AuditError("candidate moves lack pieces")
            current[3].append(line)
        else:
            raise AuditError("unknown candidate stream record")
    if not blocks:
        raise AuditError("empty candidate stream")
    if len(blocks) > 100:
        raise AuditError("candidate batch exceeds generator limit")

    staged, synthetic = [], [
        "MOCS-XQ-ENDGAMES|5", "LICENSE|GPL-3.0-or-later",
        "AUTHOR|Master of Chess Strategy contributors",
    ]
    seed, previous_attempt = None, -1
    for index, (header, proof, pieces, moves) in enumerate(blocks, 1):
        ordinal, candidate_seed, attempt, fen = header.groups()
        candidate_seed, attempt = int(candidate_seed), int(attempt)
        if (int(ordinal) != index or candidate_seed > 0xffffffff or
                (seed is not None and candidate_seed != seed) or attempt <= previous_attempt):
            raise AuditError("candidate numbering, seed or attempt is inconsistent")
        seed, previous_attempt = candidate_seed, attempt
        fields = proof.split("|")
        if fields == ["PROOF", "red_moves=1", "unique_first=true", "immediate_check_win=true"]:
            red_moves = 1
        elif (len(fields) == 5 and fields[:4] == [
            "PROOF", "red_moves=2", "unique_first=true", "all_defenses=true"
        ] and re.fullmatch(r"nodes=[1-9][0-9]*", fields[4])):
            red_moves = 2
        else:
            raise AuditError("candidate has no recognized native proof marker")
        if len(moves) != 2 * red_moves - 1:
            raise AuditError("candidate principal variation length differs from proof")
        fen_fields = fen.split()
        if (fen_fields[0] != _fen_board(pieces) or fen_fields[1:] != ["w", "-", "-", "0", "1"]):
            raise AuditError("candidate FEN differs from its placement")
        synthetic.extend([
            f"LEVEL|xq-easy-{index:03d}|0|{index}|待审候选|待审|RED|{red_moves}|1|10|{len(pieces)}|"
            + ("MAIN" if index == 1 else "BONUS"),
            *pieces, *moves, "END",
        ])
        staged.append({"ordinal": index, "seed": seed, "attempt": attempt,
                       "fen": fen, "red_moves": red_moves, "proof_marker": proof,
                       "pieces": pieces,
                       "principal_variation": moves})
    levels = parse_pack("\n".join(synthetic) + "\n")[1]
    seen_candidates = set()
    for item, level in zip(staged, levels):
        key = level.symmetry_key()
        if key in seen_candidates:
            raise AuditError("candidate batch contains a mirrored duplicate")
        seen_candidates.add(key)
        if key in released_positions:
            raise AuditError("candidate duplicates a released board or its mirror")
        item["symmetry_sha256"] = hashlib.sha256(
            json.dumps(key, separators=(",", ":")).encode("utf-8")
        ).hexdigest()
    return {"candidate_count": len(staged), "seed": seed,
            "source_sha256": hashlib.sha256(stream.encode("utf-8")).hexdigest(),
            "status": "staged-not-published", "candidates": staged}


def main() -> int:
    repository = Path(__file__).resolve().parents[2]
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("stream", type=Path)
    parser.add_argument("--released-pack", type=Path, default=
                        repository / "app/src/main/assets/endgames/chinese_chess/endgames-v5.txt")
    args = parser.parse_args()
    try:
        result = stage_candidates(args.stream.read_text(encoding="utf-8"),
                                  args.released_pack.read_text(encoding="utf-8"))
    except (AuditError, OSError, UnicodeDecodeError) as error:
        parser.exit(1, f"candidate staging failed: {error}\n")
    print(json.dumps(result, ensure_ascii=True, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
