"""Host-side staging checks; these do not certify Xiangqi wins."""

import unittest
from pathlib import Path

from audit_chinese_chess_endgames import AuditError
from stage_xiangqi_candidates import stage_candidates


RELEASED = """MOCS-XQ-ENDGAMES|5
LICENSE|GPL-3.0-or-later
AUTHOR|Project contributors
LEVEL|xq-easy-001|0|1|已发布|演示|RED|1|1|10|4|MAIN
PIECE|4|9|RED|GENERAL
PIECE|4|0|BLACK|GENERAL
PIECE|4|5|RED|SOLDIER
PIECE|3|1|RED|CHARIOT
MOVE|3|1|4|1
END
"""
HEADER = "CANDIDATE 1 seed=42 attempt=9 fen=4k4/1N5N1/R8/9/9/4P4/9/9/9/4K4 w - - 0 1"
PROOF = "PROOF|red_moves=1|unique_first=true|immediate_check_win=true"
PIECES = """PIECE|4|9|RED|GENERAL
PIECE|4|0|BLACK|GENERAL
PIECE|4|5|RED|SOLDIER
PIECE|1|1|RED|HORSE
PIECE|7|1|RED|HORSE
PIECE|0|2|RED|CHARIOT"""
STREAM = "\n".join((HEADER, PROOF, PIECES, "MOVE|0|2|4|2")) + "\n"


class CandidateStagingTest(unittest.TestCase):
    def test_valid_stream_is_staged_but_not_published(self) -> None:
        report = stage_candidates(STREAM, RELEASED)
        self.assertEqual(report["candidate_count"], 1)
        self.assertEqual(report["seed"], 42)
        self.assertEqual(report["status"], "staged-not-published")
        self.assertEqual(len(report["candidates"][0]["symmetry_sha256"]), 64)

    def test_missing_proof_or_changed_fen_is_rejected(self) -> None:
        with self.assertRaisesRegex(AuditError, "recognized native proof"):
            stage_candidates(STREAM.replace(PROOF, "PROOF|red_moves=2|unique_first=true"), RELEASED)
        with self.assertRaisesRegex(AuditError, "FEN differs"):
            stage_candidates(STREAM.replace("/R8/", "/8R/"), RELEASED)

    def test_seed_and_attempt_must_describe_one_reproducible_batch(self) -> None:
        second = STREAM.replace("CANDIDATE 1 seed=42 attempt=9", "CANDIDATE 2 seed=43 attempt=10")
        with self.assertRaisesRegex(AuditError, "numbering, seed or attempt"):
            stage_candidates(STREAM + second, RELEASED)

    def test_mirror_of_released_position_is_rejected(self) -> None:
        released_candidate = "\n".join((
            "CANDIDATE 1 seed=42 attempt=9 fen=4k4/5R3/9/9/9/4P4/9/9/9/4K4 w - - 0 1",
            PROOF, "PIECE|4|9|RED|GENERAL", "PIECE|4|0|BLACK|GENERAL",
            "PIECE|4|5|RED|SOLDIER", "PIECE|5|1|RED|CHARIOT",
            "MOVE|5|1|4|1",
        )) + "\n"
        with self.assertRaisesRegex(AuditError, "duplicates a released board"):
            stage_candidates(released_candidate, RELEASED)

    def test_actual_release_pack_prevents_reimporting_existing_seed(self) -> None:
        root = Path(__file__).resolve().parents[2]
        released = (root / "app/src/main/assets/endgames/chinese_chess/endgames-v5.txt").read_text(encoding="utf-8")
        with self.assertRaisesRegex(AuditError, "duplicates a released board"):
            stage_candidates(STREAM, released)

    def test_batch_mirror_is_rejected(self) -> None:
        mirror = STREAM.replace("CANDIDATE 1 seed=42 attempt=9", "CANDIDATE 2 seed=42 attempt=10")
        mirror = mirror.replace("/R8/", "/8R/").replace("PIECE|0|2|RED|CHARIOT", "PIECE|8|2|RED|CHARIOT")
        mirror = mirror.replace("MOVE|0|2|4|2", "MOVE|8|2|4|2")
        with self.assertRaisesRegex(AuditError, "mirrored duplicate board"):
            stage_candidates(STREAM + mirror, RELEASED)


if __name__ == "__main__":
    unittest.main()
