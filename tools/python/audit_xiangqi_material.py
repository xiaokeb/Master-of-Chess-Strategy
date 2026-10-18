"""Screen whether nonmoving red pieces are necessary to a Xiangqi puzzle's win.

This is an authoring aid, not a professional difficulty or publication verdict.
Removing a piece can make a position illegal; such cases remain unknown.
"""

from __future__ import annotations

import argparse
from dataclasses import replace
import json
from pathlib import Path

from audit_chinese_chess_endgames import AuditError, Level
from stage_xiangqi_candidates import stage_candidates
from verify_xiangqi_forced_win import prove_red_win


SUPPORT_PIECES = frozenset({"ADVISOR", "ELEPHANT", "HORSE", "CHARIOT", "CANNON", "SOLDIER"})


def audit_material(stream: str, released_pack: str, required_support: str | None = None) -> dict:
    """First validate every raw candidate, then ablate each nonmoving red piece."""
    if required_support is not None and required_support not in SUPPORT_PIECES:
        raise ValueError("required support piece is invalid")
    staged = stage_candidates(stream, released_pack)
    examined = []
    for candidate in staged["candidates"]:
        pieces = [
            (int(fields[1]), int(fields[2]), fields[3], fields[4])
            for line in candidate["pieces"] for fields in [line.split("|")]
        ]
        moves = [tuple(map(int, line.split("|")[1:])) for line in candidate["principal_variation"]]
        limit = candidate["red_moves"]
        level = Level(
            id=f"xq-easy-{candidate['ordinal']:03d}", difficulty=0,
            order=candidate["ordinal"], title="待审", theme="待审",
            limit=limit, stars=1, score=10, declared_pieces=len(pieces),
            track="BONUS", pieces=pieces, moves=moves,
        )
        effects = []
        for index, piece in enumerate(pieces):
            x, y, side, kind = piece
            if side != "RED" or kind == "GENERAL" or (x, y) == moves[0][:2]:
                continue
            weakened = replace(level, pieces=pieces[:index] + pieces[index + 1:])
            proof = prove_red_win(weakened, limit)
            if proof.status != "complete":
                effect = "unknown"
            elif moves[0] in proof.winning_first_moves:
                effect = "dispensable"
            else:
                effect = "necessary"
            effects.append({"square": [x, y], "piece": kind, "effect": effect})
        examined.append({
            "ordinal": candidate["ordinal"], "attempt": candidate["attempt"],
            "fen": candidate["fen"], "first_move": moves[0], "support": effects,
        })
    selected = examined if required_support is None else [
        row for row in examined if any(
            item["piece"] == required_support and item["effect"] == "necessary"
            for item in row["support"]
        )
    ]
    return {
        "status": "material-screened-not-published",
        "source_sha256": staged["source_sha256"], "candidate_count": len(examined),
        "shown_count": len(selected), "required_support": required_support,
        "candidates": selected,
    }


def main() -> int:
    repository = Path(__file__).resolve().parents[2]
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("stream", type=Path)
    parser.add_argument("--released-pack", type=Path, default=
                        repository / "app/src/main/assets/endgames/chinese_chess/endgames-v5.txt")
    parser.add_argument("--require-support", choices=sorted(SUPPORT_PIECES))
    args = parser.parse_args()
    try:
        report = audit_material(
            args.stream.read_text(encoding="utf-8"),
            args.released_pack.read_text(encoding="utf-8"), args.require_support,
        )
    except (AuditError, ValueError, OSError, UnicodeDecodeError) as error:
        parser.exit(1, f"Xiangqi material audit failed: {error}\n")
    print(json.dumps(report, ensure_ascii=True, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
