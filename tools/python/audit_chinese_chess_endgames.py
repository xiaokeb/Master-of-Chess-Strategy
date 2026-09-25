"""Audit the published Xiangqi endgame pack before native rule verification.

This is a structural and duplicate-content gate, not a chess solver or a
copyright clearance tool. Android's parser and NDK tests remain authoritative.
"""

from __future__ import annotations

import argparse
from collections import Counter
from dataclasses import dataclass, field
import hashlib
import json
from pathlib import Path
import re


PACK_HEADER = "MOCS-XQ-ENDGAMES|4"
REQUIRED_LICENSE = "GPL-3.0-or-later"
DIFFICULTIES = ("easy", "medium", "hard", "master")
PIECE_TYPES = {"GENERAL", "ADVISOR", "ELEPHANT", "HORSE", "CHARIOT", "CANNON", "SOLDIER"}
SIDES = {"RED", "BLACK"}
TRACKS = {"MAIN", "BONUS"}
PIECE_QUOTAS = {
    "GENERAL": 1, "ADVISOR": 2, "ELEPHANT": 2, "HORSE": 2,
    "CHARIOT": 2, "CANNON": 2, "SOLDIER": 5,
}
MAX_PACK_BYTES = 2 * 1024 * 1024


class AuditError(ValueError):
    """A published pack violates its versioned structural contract."""


@dataclass
class Level:
    id: str
    difficulty: int
    order: int
    title: str
    theme: str
    limit: int
    stars: int
    score: int
    declared_pieces: int
    track: str
    pieces: list[tuple[int, int, str, str]] = field(default_factory=list)
    moves: list[tuple[int, int, int, int]] = field(default_factory=list)

    def position_key(self) -> tuple[tuple[int, int, str, str], ...]:
        return tuple(sorted(self.pieces))


def _number(value: str, minimum: int, maximum: int, location: str) -> int:
    try:
        number = int(value)
    except ValueError as error:
        raise AuditError(f"{location}: expected integer, got {value!r}") from error
    if number < minimum or number > maximum:
        raise AuditError(f"{location}: {number} outside {minimum}..{maximum}")
    return number


def _text(value: str, location: str) -> str:
    value = value.strip()
    if not value or len(value) > 80 or "|" in value:
        raise AuditError(f"{location}: text must contain 1..80 characters")
    return value


def parse_pack(content: str) -> tuple[str, list[Level]]:
    if len(content.encode("utf-8")) > MAX_PACK_BYTES:
        raise AuditError("pack exceeds 2 MiB")
    lines = [
        (number, line.strip())
        for number, line in enumerate(content.splitlines(), 1)
        if line.strip() and not line.lstrip().startswith("#")
    ]
    if len(lines) < 4 or lines[0][1] != PACK_HEADER:
        raise AuditError("expected v4 pack header")
    if lines[1][1] != f"LICENSE|{REQUIRED_LICENSE}":
        raise AuditError("pack license is missing or incompatible")
    if not lines[2][1].startswith("AUTHOR|") or len(lines[2][1].split("|")) != 2:
        raise AuditError("expected author record")
    author = _text(lines[2][1].split("|", 1)[1], "author")
    levels: list[Level] = []
    current: Level | None = None
    for number, line in lines[3:]:
        parts = line.split("|")
        kind = parts[0]
        location = f"line {number}"
        if kind == "LEVEL":
            if current is not None or len(parts) != 12:
                raise AuditError(f"{location}: malformed or nested level")
            difficulty = _number(parts[2], 0, 3, location)
            order = _number(parts[3], 1, 3000, location)
            expected_id = f"xq-{DIFFICULTIES[difficulty]}-{order:03d}"
            if not re.fullmatch(r"xq-(easy|medium|hard|master)-[0-9]{3,4}", parts[1]):
                raise AuditError(f"{location}: invalid level ID")
            if parts[1] != expected_id:
                raise AuditError(f"{location}: ID suffix and order disagree")
            if parts[6] != "RED" or parts[11] not in TRACKS:
                raise AuditError(f"{location}: invalid first side or track")
            current = Level(
                id=parts[1],
                difficulty=difficulty,
                order=order,
                title=_text(parts[4], location),
                theme=_text(parts[5], location),
                limit=_number(parts[7], 1, 100, location),
                stars=_number(parts[8], 1, 5, location),
                score=_number(parts[9], 1, 1000, location),
                declared_pieces=_number(parts[10], 2, 32, location),
                track=parts[11],
            )
        elif kind == "PIECE":
            if current is None or len(parts) != 5:
                raise AuditError(f"{location}: piece outside a level")
            if current.moves:
                raise AuditError(f"{location}: pieces must precede moves")
            if parts[3] not in SIDES or parts[4] not in PIECE_TYPES:
                raise AuditError(f"{location}: unknown piece")
            current.pieces.append((
                _number(parts[1], 0, 8, location),
                _number(parts[2], 0, 9, location),
                parts[3],
                parts[4],
            ))
        elif kind == "MOVE":
            if current is None or len(parts) != 5:
                raise AuditError(f"{location}: move outside a level")
            if len(current.pieces) != current.declared_pieces:
                raise AuditError(f"{location}: moves must follow all declared pieces")
            current.moves.append((
                _number(parts[1], 0, 8, location),
                _number(parts[2], 0, 9, location),
                _number(parts[3], 0, 8, location),
                _number(parts[4], 0, 9, location),
            ))
        elif kind == "END":
            if current is None or len(parts) != 1:
                raise AuditError(f"{location}: end outside a level")
            _validate_level(current)
            levels.append(current)
            current = None
        else:
            raise AuditError(f"{location}: unknown record {kind!r}")
    if current is not None or not levels:
        raise AuditError("unfinished or empty pack")
    _validate_catalog(levels)
    return author, levels


def _validate_level(level: Level) -> None:
    if len(level.pieces) != level.declared_pieces:
        raise AuditError(f"{level.id}: declared piece count differs")
    if not level.moves or len(level.moves) > level.limit * 2 - 1:
        raise AuditError(f"{level.id}: invalid principal variation length")
    squares = [(x, y) for x, y, _, _ in level.pieces]
    if len(squares) != len(set(squares)):
        raise AuditError(f"{level.id}: two pieces share a square")
    for side in SIDES:
        if sum(piece_side == side and kind == "GENERAL" for _, _, piece_side, kind in level.pieces) != 1:
            raise AuditError(f"{level.id}: expected one {side} general")
        counts = Counter(kind for _, _, piece_side, kind in level.pieces if piece_side == side)
        for kind, count in counts.items():
            if count > PIECE_QUOTAS[kind]:
                raise AuditError(f"{level.id}: too many {side} {kind} pieces")
    for x, y, side, kind in level.pieces:
        own_half = y >= 5 if side == "RED" else y <= 4
        if kind == "ELEPHANT" and not own_half:
            raise AuditError(f"{level.id}: {side} elephant crossed the river")
        if kind == "ADVISOR" and (x not in range(3, 6) or
                                  y not in (range(7, 10) if side == "RED" else range(0, 3))):
            raise AuditError(f"{level.id}: {side} advisor outside palace")
        if kind == "SOLDIER" and (y > 6 if side == "RED" else y < 3):
            raise AuditError(f"{level.id}: {side} soldier behind its starting row")
    generals = {
        side: next((x, y) for x, y, piece_side, kind in level.pieces
                   if piece_side == side and kind == "GENERAL")
        for side in SIDES
    }
    for side, (x, y) in generals.items():
        palace_rows = range(7, 10) if side == "RED" else range(0, 3)
        if x not in range(3, 6) or y not in palace_rows:
            raise AuditError(f"{level.id}: {side} general outside palace")
    if generals["RED"][0] == generals["BLACK"][0]:
        column = generals["RED"][0]
        if not any(x == column and generals["BLACK"][1] < y < generals["RED"][1]
                   for x, y, _, _ in level.pieces):
            raise AuditError(f"{level.id}: generals face each other")


def _validate_catalog(levels: list[Level]) -> None:
    ids = [level.id for level in levels]
    if len(ids) != len(set(ids)):
        raise AuditError("duplicate level ID")
    for difficulty in range(4):
        chapter = sorted(
            (level for level in levels if level.difficulty == difficulty),
            key=lambda level: level.order,
        )
        if [level.order for level in chapter] != list(range(1, len(chapter) + 1)):
            raise AuditError(f"{DIFFICULTIES[difficulty]}: noncontiguous order")
        seen_bonus = False
        for level in chapter:
            if level.track == "BONUS":
                seen_bonus = True
            elif seen_bonus:
                raise AuditError(f"{DIFFICULTIES[difficulty]}: main after bonus")
        if chapter and chapter[0].track != "MAIN":
            raise AuditError(f"{DIFFICULTIES[difficulty]}: bonus without main")
    by_position: dict[tuple[tuple[int, int, str, str], ...], str] = {}
    for level in levels:
        previous = by_position.setdefault(level.position_key(), level.id)
        if previous != level.id:
            raise AuditError(f"duplicate board: {previous} and {level.id}")


def audit(content: str) -> dict[str, object]:
    author, levels = parse_pack(content)
    return {
        "format": PACK_HEADER,
        "license": REQUIRED_LICENSE,
        "author": author,
        "source_sha256": hashlib.sha256(content.encode("utf-8")).hexdigest(),
        "level_count": len(levels),
        "by_difficulty": dict(sorted(Counter(DIFFICULTIES[level.difficulty] for level in levels).items())),
        "by_theme": dict(sorted(Counter(level.theme for level in levels).items())),
        "by_track": dict(sorted(Counter(level.track for level in levels).items())),
        "index": [
            {
                "id": level.id,
                "difficulty": DIFFICULTIES[level.difficulty],
                "order": level.order,
                "theme": level.theme,
                "track": level.track,
                "position_sha256": hashlib.sha256(
                    json.dumps(level.position_key(), ensure_ascii=False, separators=(",", ":")).encode("utf-8")
                ).hexdigest(),
            }
            for level in sorted(levels, key=lambda level: (level.difficulty, level.order))
        ],
    }


def main() -> int:
    repository = Path(__file__).resolve().parents[2]
    default_pack = repository / "app/src/main/assets/endgames/chinese_chess/endgames-v4.txt"
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--pack", type=Path, default=default_pack)
    args = parser.parse_args()
    try:
        report = audit(args.pack.read_bytes().decode("utf-8"))
    except (AuditError, OSError, UnicodeDecodeError) as error:
        parser.exit(1, f"endgame audit failed: {error}\n")
    # ASCII JSON survives Windows PowerShell's redirected output encoding.
    print(json.dumps(report, ensure_ascii=True, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
