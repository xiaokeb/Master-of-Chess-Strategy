"""Offline content-gate tests; run with the project Conda Python."""

from pathlib import Path
from dataclasses import replace
import hashlib
import unittest

from audit_chinese_chess_endgames import AuditError, audit, parse_pack
from verify_xiangqi_forced_win import prove_red_win


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

        self.assertEqual(35, report["level_count"])
        self.assertEqual(34, report["unique_symmetry_positions"])
        self.assertEqual(1, report["legacy_mirror_duplicates"])
        self.assertEqual({"BONUS": 30, "MAIN": 5}, report["by_track"])
        self.assertEqual(35, len(report["index"]))
        self.assertEqual(13, report["by_theme"]["两步强制胜"])
        self.assertEqual("xq-easy-001", report["index"][0]["id"])
        self.assertEqual(hashlib.sha256(source).hexdigest(), report["source_sha256"])
        self.assertEqual(report, audit(source.decode("utf-8")))

    def test_additive_revision_preserves_old_v5_levels_byte_for_byte(self) -> None:
        repository = Path(__file__).resolve().parents[2]
        source = (repository / "app/src/main/assets/endgames/chinese_chess/endgames-v5.txt").read_bytes()
        old_pack = source.split(b"\n# Additive revision:", 1)[0]
        self.assertEqual(
            "c651394d69d0bf55db4c2df709f67e2335a9af16dcf1e56dd10ad6eb33a2d20b",
            hashlib.sha256(old_pack).hexdigest(),
        )

        old_sixteen = source.split(b"\n# Project-generated mixed-piece revision.", 1)[0]
        self.assertEqual(
            "7c694f769a10768f406b9df3117208fb506616c9e51db15e07e2a94cfcb82166",
            hashlib.sha256(old_sixteen).hexdigest(),
        )
        previous_twenty_two = source.split(b"\n# Project-generated classic revision.", 1)[0]
        self.assertEqual(
            "968e734fdac7447cd8f177b279213928dfd3372d2bdd0ad103b2a6cf6420b3fb",
            hashlib.sha256(previous_twenty_two).hexdigest(),
        )
        previous_twenty_seven = source.split(b"\n# Project-generated rook/cannon and horse/cannon revision.", 1)[0]
        self.assertEqual(
            "d43cc9c44a6099d0a5e439fc4fb51ea34ba0055b199d1ea26607754d24dccedc",
            hashlib.sha256(previous_twenty_seven).hexdigest(),
        )
        previous_thirty_one = source.split(b"\n# Project-generated horse/cannon review batch.", 1)[0]
        self.assertEqual(
            "523522889b77a27b6d98e809b9da12da2b170cbd294e6f7952fbb63136ed0875",
            hashlib.sha256(previous_thirty_one).hexdigest(),
        )
        previous_thirty_three = source.split(b"\n# Project-generated horse/cannon trimmed review batch.", 1)[0]
        self.assertEqual(
            "4a352daed89ef6968c14bbb98aea9b71eb6c5dc8353ab94d1bd462ce48164d93",
            hashlib.sha256(previous_thirty_three).hexdigest(),
        )

    def test_mixed_piece_levels_match_their_project_generated_sources(self) -> None:
        repository = Path(__file__).resolve().parents[2]
        pack = repository / "app/src/main/assets/endgames/chinese_chess/endgames-v5.txt"
        by_id = {level.id: level for level in parse_pack(pack.read_text(encoding="utf-8"))[1]}
        sources = {
            "xq-easy-011": "xiangqi-rook-cannon-seed-20261002.txt",
            "xq-easy-012": "xiangqi-horse-cannon-seed-20261002.txt",
            "xq-easy-013": "xiangqi-double-rook-seed-20261002.txt",
            "xq-medium-003": "xiangqi-rook-cannon-two-move-seed-20261003.txt",
            "xq-medium-004": "xiangqi-horse-cannon-two-move-seed-20261003.txt",
            "xq-medium-005": "xiangqi-double-rook-two-move-seed-20261003.txt",
        }
        for level_id, filename in sources.items():
            with self.subTest(level=level_id):
                lines = (repository / "config/games/xiangqi/candidates" / filename).read_text(
                    encoding="utf-8"
                ).splitlines()
                pieces = sorted(
                    (int(x), int(y), side, kind)
                    for tag, x, y, side, kind in (line.split("|") for line in lines if line.startswith("PIECE|"))
                )
                moves = [
                    tuple(map(int, line.split("|")[1:]))
                    for line in lines if line.startswith("MOVE|")
                ]
                self.assertEqual(sorted(by_id[level_id].pieces), pieces)
                self.assertEqual(by_id[level_id].moves, moves)

    def test_classic_levels_match_their_project_generated_sources(self) -> None:
        repository = Path(__file__).resolve().parents[2]
        pack = repository / "app/src/main/assets/endgames/chinese_chess/endgames-v5.txt"
        by_id = {level.id: level for level in parse_pack(pack.read_text(encoding="utf-8"))[1]}
        sources = {
            "xq-easy-014": ("xiangqi-seed-20261001.txt", 1),
            "xq-easy-015": ("xiangqi-seed-20261001.txt", 2),
            "xq-easy-016": ("xiangqi-seed-20261001.txt", 3),
            "xq-medium-006": ("xiangqi-two-move-seed-20261001.txt", 1),
            "xq-medium-007": ("xiangqi-two-move-seed-20261001.txt", 2),
        }
        for level_id, (filename, ordinal) in sources.items():
            with self.subTest(level=level_id):
                stream = (repository / "config/games/xiangqi/candidates" / filename).read_text(
                    encoding="utf-8"
                )
                candidate = stream.split("CANDIDATE ")[ordinal].split("CANDIDATE ")[0]
                lines = candidate.splitlines()
                pieces = sorted(
                    (int(x), int(y), side, kind)
                    for tag, x, y, side, kind in (line.split("|") for line in lines if line.startswith("PIECE|"))
                )
                moves = [tuple(map(int, line.split("|")[1:])) for line in lines if line.startswith("MOVE|")]
                self.assertEqual(sorted(by_id[level_id].pieces), pieces)
                self.assertEqual(by_id[level_id].moves, moves)

    def test_latest_bonus_levels_match_generated_sources(self) -> None:
        repository = Path(__file__).resolve().parents[2]
        pack = repository / "app/src/main/assets/endgames/chinese_chess/endgames-v5.txt"
        by_id = {level.id: level for level in parse_pack(pack.read_text(encoding="utf-8"))[1]}
        sources = {
            "xq-easy-017": ("xiangqi-rook-cannon-seed-20261007.txt", 43),
            "xq-easy-018": ("xiangqi-rook-cannon-seed-20261007.txt", 41),
            "xq-medium-008": ("xiangqi-horse-cannon-two-move-seed-20261005.txt", 1),
            "xq-medium-009": ("xiangqi-horse-cannon-two-move-seed-20261005.txt", 2),
            "xq-medium-010": ("xiangqi-horse-cannon-two-move-seed-20261009.txt", 24),
            "xq-medium-011": ("xiangqi-horse-cannon-two-move-seed-20261009.txt", 73),
        }
        for level_id, (filename, ordinal) in sources.items():
            with self.subTest(level=level_id):
                stream = (repository / "config/games/xiangqi/candidates" / filename).read_text(
                    encoding="utf-8"
                )
                candidate = stream.split("CANDIDATE ")[ordinal].split("CANDIDATE ")[0]
                lines = candidate.splitlines()
                pieces = sorted(
                    (int(x), int(y), side, kind)
                    for tag, x, y, side, kind in (line.split("|") for line in lines if line.startswith("PIECE|"))
                )
                moves = [tuple(map(int, line.split("|")[1:])) for line in lines if line.startswith("MOVE|")]
                self.assertEqual(sorted(by_id[level_id].pieces), pieces)
                self.assertEqual(by_id[level_id].moves, moves)

    def test_trimmed_levels_match_their_sources_except_declared_removals(self) -> None:
        repository = Path(__file__).resolve().parents[2]
        pack = repository / "app/src/main/assets/endgames/chinese_chess/endgames-v5.txt"
        by_id = {level.id: level for level in parse_pack(pack.read_text(encoding="utf-8"))[1]}
        stream = (repository / "config/games/xiangqi/candidates/xiangqi-horse-cannon-two-move-seed-20261009.txt").read_text(encoding="utf-8")
        for level_id, ordinal, removed in (
            ("xq-medium-012", 23, (7, 4, "RED", "SOLDIER")),
            ("xq-medium-013", 74, (1, 1, "RED", "SOLDIER")),
        ):
            with self.subTest(level=level_id):
                lines = stream.split("CANDIDATE ")[ordinal].split("CANDIDATE ")[0].splitlines()
                pieces = {
                    (int(x), int(y), side, kind)
                    for tag, x, y, side, kind in (line.split("|") for line in lines if line.startswith("PIECE|"))
                }
                self.assertIn(removed, pieces)
                self.assertEqual(sorted(pieces - {removed}), sorted(by_id[level_id].pieces))
                self.assertEqual(
                    [tuple(map(int, line.split("|")[1:])) for line in lines if line.startswith("MOVE|")],
                    by_id[level_id].moves,
                )

    def test_latest_bonus_solutions_need_cannon_support(self) -> None:
        repository = Path(__file__).resolve().parents[2]
        pack = repository / "app/src/main/assets/endgames/chinese_chess/endgames-v5.txt"
        by_id = {level.id: level for level in parse_pack(pack.read_text(encoding="utf-8"))[1]}
        for level_id in ("xq-easy-017", "xq-easy-018", "xq-medium-008", "xq-medium-009", "xq-medium-010", "xq-medium-011", "xq-medium-012", "xq-medium-013"):
            with self.subTest(level=level_id):
                level = by_id[level_id]
                self.assertTrue(any(
                    piece[2] == "RED" and piece[3] == "CANNON" for piece in level.pieces
                ))
                without_cannon = replace(level, pieces=[
                    piece for piece in level.pieces
                    if not (piece[2] == "RED" and piece[3] == "CANNON")
                ])
                proof = prove_red_win(without_cannon, level.limit)
                self.assertEqual("complete", proof.status)
                self.assertNotIn(level.moves[0], proof.winning_first_moves)

    def test_duplicate_board_is_rejected_even_with_new_id(self) -> None:
        second = LEVEL.replace("xq-easy-001|0|1", "xq-easy-002|0|2")
        with self.assertRaisesRegex(AuditError, "duplicate board"):
            parse_pack(PACK + second)

    def test_new_mirrored_board_is_rejected_without_changing_legacy_levels(self) -> None:
        second = LEVEL.replace("xq-easy-001|0|1", "xq-easy-002|0|2").replace(
            "PIECE|3|1|RED|CHARIOT", "PIECE|5|1|RED|CHARIOT"
        )
        with self.assertRaisesRegex(AuditError, "mirrored duplicate board"):
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
