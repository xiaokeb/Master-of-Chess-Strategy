"""Host-side staging checks; these do not certify Xiangqi wins."""

import unittest
from pathlib import Path

from audit_chinese_chess_endgames import AuditError
from stage_xiangqi_candidates import stage_candidate_collection, stage_candidates


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
        self.assertEqual(report["family"], "classic")
        self.assertEqual(report["status"], "staged-not-published")
        self.assertEqual(len(report["candidates"][0]["symmetry_sha256"]), 64)

    def test_explicit_attack_family_must_match_piece_inventory(self) -> None:
        tagged = STREAM.replace("attempt=9 fen=", "attempt=9 family=rook-horse fen=")
        report = stage_candidates(tagged, RELEASED)
        self.assertEqual(report["family"], "rook-horse")
        self.assertEqual(report["candidates"][0]["family"], "rook-horse")
        with self.assertRaisesRegex(AuditError, "attack family piece inventory"):
            stage_candidates(tagged.replace("family=rook-horse", "family=horse-cannon"), RELEASED)
        with self.assertRaisesRegex(AuditError, "attack family piece inventory"):
            stage_candidates(tagged.replace("family=rook-horse", "family=double-rook"), RELEASED)

    def test_attack_family_cannot_change_within_a_batch(self) -> None:
        first = STREAM.replace("attempt=9 fen=", "attempt=9 family=rook-horse fen=")
        second = STREAM.replace("CANDIDATE 1 seed=42 attempt=9 fen=",
                                "CANDIDATE 2 seed=42 attempt=10 family=classic fen=")
        with self.assertRaisesRegex(AuditError, "attack family changes"):
            stage_candidates(first + second, RELEASED)

    def test_two_move_candidate_is_independently_screened(self) -> None:
        stream = "\n".join((
            "CANDIDATE 1 seed=42 attempt=19 fen=9/4a2P1/5k3/5C3/9/9/8R/8N/4K4/9 w - - 0 1",
            "PROOF|red_moves=2|unique_first=true|all_defenses=true|nodes=2355",
            "PIECE|4|8|RED|GENERAL", "PIECE|5|2|BLACK|GENERAL",
            "PIECE|7|1|RED|SOLDIER", "PIECE|8|6|RED|CHARIOT",
            "PIECE|8|7|RED|HORSE", "PIECE|5|3|RED|CANNON",
            "PIECE|4|1|BLACK|ADVISOR",
            "MOVE|7|1|6|1", "MOVE|4|1|3|0", "MOVE|8|6|8|2",
        )) + "\n"
        report = stage_candidates(stream, RELEASED)
        self.assertEqual(report["candidates"][0]["red_moves"], 2)
        self.assertGreater(report["candidates"][0]["host_proof_nodes"], 0)
        with self.assertRaisesRegex(AuditError, "attack family piece inventory"):
            stage_candidates(stream.replace("attempt=19 fen=", "attempt=19 family=rook-cannon fen="), RELEASED)

    def test_missing_proof_or_changed_fen_is_rejected(self) -> None:
        with self.assertRaisesRegex(AuditError, "recognized native proof"):
            stage_candidates(STREAM.replace(PROOF, "PROOF|red_moves=2|unique_first=true"), RELEASED)
        with self.assertRaisesRegex(AuditError, "FEN differs"):
            stage_candidates(STREAM.replace("/R8/", "/8R/"), RELEASED)

    def test_claimed_proof_with_wrong_winning_move_is_rejected(self) -> None:
        with self.assertRaisesRegex(AuditError, "legal required win|host proof"):
            stage_candidates(STREAM.replace("MOVE|0|2|4|2", "MOVE|0|2|0|3"), RELEASED)

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

    def test_separate_batches_cannot_repeat_a_board_or_its_mirror(self) -> None:
        with self.assertRaisesRegex(AuditError, "cross-batch mirrored duplicate"):
            stage_candidate_collection([("first.txt", STREAM), ("second.txt", STREAM)], RELEASED)

    def test_legacy_ids_cannot_exempt_new_batch_mirrors(self) -> None:
        candidates = []
        for ordinal, x, row in ((1, 0, "R8"), (2, 1, "1R7"),
                                (3, 2, "2R6"), (4, 6, "6R2")):
            candidate = STREAM.replace(
                "CANDIDATE 1 seed=42 attempt=9",
                f"CANDIDATE {ordinal} seed=42 attempt={8 + ordinal}",
            ).replace("/R8/", f"/{row}/")
            candidate = candidate.replace("PIECE|0|2|RED|CHARIOT", f"PIECE|{x}|2|RED|CHARIOT")
            candidate = candidate.replace("MOVE|0|2|4|2", f"MOVE|{x}|2|4|2")
            candidates.append(candidate)
        with self.assertRaisesRegex(AuditError, "candidate batch contains a mirrored duplicate"):
            stage_candidates("".join(candidates), RELEASED)

    def test_new_project_batches_were_distinct_before_release_and_now_cannot_republish(self) -> None:
        root = Path(__file__).resolve().parents[2]
        source_dir = root / "config/games/xiangqi/candidates"
        sources = [
            ("xiangqi-rook-cannon-seed-20261007.txt",
             "72bf3897d650f7f88f03f6b207f33ac9bd59e6f5d5a7127d3d2395416034f882"),
            ("xiangqi-horse-cannon-two-move-seed-20261005.txt",
             "2d9e1a35affce1a42b18c29d7763ec44e1a9f519e3e0991108832d9b7321aa1c"),
        ]
        pack = root / "app/src/main/assets/endgames/chinese_chess/endgames-v5.txt"
        batches = [(name, (source_dir / name).read_text(encoding="utf-8")) for name, _ in sources]
        released = pack.read_text(encoding="utf-8")
        previous_pack = released.split("\n# Project-generated rook/cannon and horse/cannon revision.", 1)[0]
        report = stage_candidate_collection(batches, previous_pack)
        self.assertEqual("staged-not-published", report["status"])
        self.assertEqual(102, report["candidate_count"])
        self.assertEqual([expected for _, expected in sources],
                         [batch["source_sha256"] for batch in report["batches"]])
        with self.assertRaisesRegex(AuditError, "candidate duplicates a released board"):
            stage_candidate_collection(batches, released)


if __name__ == "__main__":
    unittest.main()
