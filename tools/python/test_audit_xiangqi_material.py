"""Regression checks for Xiangqi candidate material screening."""

from pathlib import Path
from dataclasses import replace
from hashlib import sha256
import json
import unittest

from audit_chinese_chess_endgames import AuditError, Level
from audit_xiangqi_material import audit_material
from verify_xiangqi_forced_win import prove_red_win


ROOT = Path(__file__).resolve().parents[2]
STREAM = (ROOT / "config/games/xiangqi/candidates/xiangqi-rook-cannon-seed-20261007.txt")
PACK = ROOT / "app/src/main/assets/endgames/chinese_chess/endgames-v5.txt"


class XiangqiMaterialAuditTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.stream = STREAM.read_text(encoding="utf-8")
        # Freeze a valid baseline before any of these candidates is published.
        cls.pack = PACK.read_text(encoding="utf-8").split("\nEND", 1)[0] + "\nEND\n"

    def test_corrected_batch_has_only_three_cannon_dependent_positions(self) -> None:
        report = audit_material(self.stream, self.pack, "CANNON")
        self.assertEqual(report["candidate_count"], 100)
        self.assertEqual(report["shown_count"], 3)
        self.assertEqual([row["ordinal"] for row in report["candidates"]], [41, 43, 97])
        self.assertEqual(
            {item["piece"]: item["effect"] for item in report["candidates"][1]["support"]},
            {"SOLDIER": "necessary", "CANNON": "necessary"},
        )
        self.assertEqual(report["status"], "material-screened-not-published")

    def test_unknown_piece_and_invalid_proof_are_rejected(self) -> None:
        with self.assertRaisesRegex(ValueError, "support piece"):
            audit_material(self.stream, self.pack, "DRAGON")
        corrupt = self.stream.replace("unique_first=true", "unique_first=false", 1)
        with self.assertRaises(AuditError):
            audit_material(corrupt, self.pack, "CANNON")

    def test_selected_two_move_horse_cannon_review_is_still_unpublished(self) -> None:
        review = json.loads((ROOT / "config/games/xiangqi/reviews/"
                             "2026-10-03-horse-cannon-two-move.json").read_text(encoding="utf-8"))
        self.assertEqual(review["status"], "selected-not-published")
        source = (ROOT / review["source_path"]).read_bytes()
        self.assertEqual(sha256(source).hexdigest(), review["source_sha256"])
        blocks = [block for block in source.decode("utf-8").split("CANDIDATE ") if block]
        self.assertEqual(review["source_count"], 100)
        self.assertEqual(len(blocks), review["source_count"])
        self.assertEqual([row["ordinal"] for row in review["candidates"]], [24, 73])
        for row in review["candidates"]:
            with self.subTest(ordinal=row["ordinal"]):
                source_lines = blocks[row["ordinal"] - 1].splitlines()
                self.assertEqual(
                    source_lines[0],
                    f"{row['ordinal']} seed=20261009 attempt={row['attempt']} "
                    f"family=horse-cannon fen={row['fen']}",
                )
                pieces = [tuple(piece) for piece in row["pieces"]]
                moves = [tuple(move) for move in row["moves"]]
                self.assertEqual(
                    [f"PIECE|{'|'.join(map(str, piece))}" for piece in pieces],
                    [line for line in source_lines if line.startswith("PIECE|")],
                )
                self.assertEqual(
                    [f"MOVE|{'|'.join(map(str, move))}" for move in moves],
                    [line for line in source_lines if line.startswith("MOVE|")],
                )
                level = Level(
                    id=f"candidate-{row['ordinal']}", difficulty=1, order=row["ordinal"],
                    title="待审", theme="待审", limit=2, stars=1, score=20,
                    declared_pieces=len(pieces), track="BONUS", pieces=pieces, moves=moves,
                )
                proof = prove_red_win(level, 2)
                self.assertEqual(proof.status, "complete")
                self.assertEqual(proof.winning_first_moves, (moves[0],))
                self.assertFalse(prove_red_win(level, 1).winning_first_moves)
                for support in row["required_support"]:
                    with self.subTest(piece=support):
                        self.assertIn(tuple(support), pieces)
                        weakened = replace(level, pieces=[
                            piece for piece in pieces if piece != tuple(support)
                        ])
                        ablated = prove_red_win(weakened, 2)
                        self.assertEqual(ablated.status, "complete")
                        self.assertNotIn(moves[0], ablated.winning_first_moves)


if __name__ == "__main__":
    unittest.main()
