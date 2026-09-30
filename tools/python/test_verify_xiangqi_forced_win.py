"""Independent host oracle tests against already published Xiangqi puzzles."""

from pathlib import Path
import unittest

from audit_chinese_chess_endgames import parse_pack
from verify_xiangqi_forced_win import (
    _attacks, _in_check, audit_published_pack, principal_variation_wins,
    prove_red_win, RED, BLACK,
)


PACK = Path(__file__).resolve().parents[2] / "app/src/main/assets/endgames/chinese_chess/endgames-v5.txt"


class XiangqiShallowOracleTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.levels = parse_pack(PACK.read_text(encoding="utf-8"))[1]

    def test_all_published_shallow_puzzles_match_their_declared_solution_type(self) -> None:
        counts = {"多解胜局": 0, "唯一一步杀": 0, "两步强制胜": 0}
        for level in self.levels:
            with self.subTest(level=level.id):
                self.assertTrue(principal_variation_wins(level))
                proof = prove_red_win(level, level.limit)
                self.assertEqual(proof.status, "complete")
                self.assertIn(level.moves[0], proof.winning_first_moves)
                if level.theme == "多解胜局":
                    self.assertGreater(len(proof.winning_first_moves), 1)
                else:
                    self.assertEqual(proof.winning_first_moves, (level.moves[0],))
                if level.theme == "两步强制胜":
                    shallow = prove_red_win(level, 1)
                    self.assertEqual(shallow.status, "complete")
                    self.assertEqual(shallow.winning_first_moves, ())
                counts[level.theme] += 1
        self.assertEqual(counts, {"多解胜局": 5, "唯一一步杀": 9, "两步强制胜": 2})

    def test_horse_leg_cannon_screen_and_facing_generals(self) -> None:
        board = [0] * 90
        board[4 + 4 * 9] = 4  # Red horse.
        self.assertTrue(_attacks(tuple(board), 4 + 4 * 9, 6 + 5 * 9))
        board[5 + 4 * 9] = 7
        self.assertFalse(_attacks(tuple(board), 4 + 4 * 9, 6 + 5 * 9))

        board = [0] * 90
        board[0] = 6  # Red cannon.
        board[3 * 9] = -7
        self.assertFalse(_attacks(tuple(board), 0, 3 * 9))
        board[9] = 7
        self.assertTrue(_attacks(tuple(board), 0, 3 * 9))

        board = [0] * 90
        board[4] = -1
        board[4 + 9 * 9] = 1
        self.assertTrue(_in_check(tuple(board), RED))
        self.assertTrue(_in_check(tuple(board), BLACK))
        board[4 + 5 * 9] = 7
        self.assertFalse(_in_check(tuple(board), RED))
        self.assertFalse(_in_check(tuple(board), BLACK))

    def test_budget_exhaustion_never_claims_a_solution(self) -> None:
        level = next(level for level in self.levels if level.id == "xq-easy-009")
        result = prove_red_win(level, 2, node_budget=1)
        self.assertEqual(result.status, "budget_exhausted")
        self.assertEqual(result.winning_first_moves, ())

    def test_release_pack_audit_reports_current_scope(self) -> None:
        report = audit_published_pack(PACK.read_text(encoding="utf-8"))
        self.assertEqual(report["level_count"], 16)
        self.assertEqual(report["themes"], {"多解胜局": 5, "唯一一步杀": 9, "两步强制胜": 2})
        self.assertGreater(report["visited_nodes"], 0)


if __name__ == "__main__":
    unittest.main()
