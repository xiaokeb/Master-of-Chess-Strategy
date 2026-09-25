"""Offline content-gate tests; run with the project Conda Python."""

from pathlib import Path
import hashlib
import unittest

from audit_chinese_chess_endgames import AuditError, audit, parse_pack


LEVEL = """LEVEL|xq-easy-001|0|1|测试|唯一一步杀|RED|1|1|10|4|MAIN
PIECE|4|9|RED|GENERAL
PIECE|4|0|BLACK|GENERAL
PIECE|4|5|RED|SOLDIER
PIECE|3|1|RED|CHARIOT
MOVE|3|1|4|1
END
"""
PACK = """MOCS-XQ-ENDGAMES|5
LICENSE|GPL-3.0-or-later
AUTHOR|Project contributors
""" + LEVEL


class ChineseChessEndgameAuditTest(unittest.TestCase):
    def test_bundled_content_has_unique_boards_and_stable_index(self) -> None:
        repository = Path(__file__).resolve().parents[2]
        pack = repository / "app/src/main/assets/endgames/chinese_chess/endgames-v5.txt"
        source = pack.read_bytes()
        report = audit(source.decode("utf-8"))

        self.assertEqual(14, report["level_count"])
        self.assertEqual({"BONUS": 9, "MAIN": 5}, report["by_track"])
        self.assertEqual(14, len(report["index"]))
        self.assertEqual("xq-easy-001", report["index"][0]["id"])
        self.assertEqual(hashlib.sha256(source).hexdigest(), report["source_sha256"])
        self.assertEqual(report, audit(source.decode("utf-8")))

    def test_duplicate_board_is_rejected_even_with_new_id(self) -> None:
        second = LEVEL.replace("xq-easy-001|0|1", "xq-easy-002|0|2")
        with self.assertRaisesRegex(AuditError, "duplicate board"):
            parse_pack(PACK + second)

    def test_bonus_without_main_is_rejected(self) -> None:
        with self.assertRaisesRegex(AuditError, "bonus without main"):
            parse_pack(PACK.replace("|MAIN", "|BONUS"))

    def test_piece_and_move_record_order_is_enforced(self) -> None:
        malformed = PACK.replace(
            "PIECE|3|1|RED|CHARIOT\nMOVE|3|1|4|1",
            "MOVE|3|1|4|1\nPIECE|3|1|RED|CHARIOT",
        )
        with self.assertRaisesRegex(AuditError, "moves must follow all declared pieces"):
            parse_pack(malformed)
        malformed = malformed.replace("|10|4|MAIN", "|10|3|MAIN")
        with self.assertRaisesRegex(AuditError, "pieces must precede moves"):
            parse_pack(malformed)

    def test_facing_generals_are_rejected(self) -> None:
        malformed = PACK.replace("|10|4|MAIN", "|10|3|MAIN").replace(
            "PIECE|4|5|RED|SOLDIER\n", "",
        )
        with self.assertRaisesRegex(AuditError, "generals face"):
            parse_pack(malformed)

    def test_soldier_behind_its_starting_row_is_rejected(self) -> None:
        malformed = PACK.replace("PIECE|4|5|RED|SOLDIER", "PIECE|4|7|RED|SOLDIER")
        with self.assertRaisesRegex(AuditError, "soldier behind its starting row"):
            parse_pack(malformed)

    def test_uncrossed_soldier_and_palace_piece_unreachable_squares_are_rejected(self) -> None:
        replacements = (
            ("PIECE|5|6|RED|SOLDIER", "soldier cannot reach"),
            ("PIECE|5|3|BLACK|SOLDIER", "soldier cannot reach"),
            ("PIECE|4|9|RED|ADVISOR", "advisor cannot reach"),
            ("PIECE|4|2|BLACK|ADVISOR", "advisor cannot reach"),
            ("PIECE|5|5|RED|ELEPHANT", "elephant cannot reach"),
            ("PIECE|5|4|BLACK|ELEPHANT", "elephant cannot reach"),
        )
        for piece, message in replacements:
            with self.subTest(piece=piece):
                malformed = PACK.replace("PIECE|3|1|RED|CHARIOT", piece)
                if piece == "PIECE|4|9|RED|ADVISOR":
                    malformed = malformed.replace(
                        "PIECE|4|9|RED|GENERAL", "PIECE|3|9|RED|GENERAL"
                    )
                with self.assertRaisesRegex(AuditError, message):
                    parse_pack(malformed)

    def test_reachable_soldier_advisor_and_elephant_squares_remain_accepted(self) -> None:
        for piece in (
            "PIECE|2|6|RED|SOLDIER",
            "PIECE|2|4|BLACK|SOLDIER",
            "PIECE|3|9|RED|ADVISOR",
            "PIECE|3|0|BLACK|ADVISOR",
            "PIECE|2|5|RED|ELEPHANT",
            "PIECE|2|4|BLACK|ELEPHANT",
        ):
            with self.subTest(piece=piece):
                parse_pack(PACK.replace("PIECE|3|1|RED|CHARIOT", piece))

    def test_missing_license_and_wrong_version_are_rejected(self) -> None:
        for malformed in (
            PACK.replace("GPL-3.0-or-later", "UNKNOWN"),
            PACK.replace("MOCS-XQ-ENDGAMES|5", "MOCS-XQ-ENDGAMES|3"),
        ):
            with self.subTest(malformed=malformed.splitlines()[0]):
                with self.assertRaises(AuditError):
                    parse_pack(malformed)


if __name__ == "__main__":
    unittest.main()
