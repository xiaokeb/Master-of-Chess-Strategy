"""Regression guards for the saved calibration evidence and its denominators."""

import json
from pathlib import Path
import unittest

from audit_xiangqi_ai import audit


RESULTS = Path(__file__).resolve().parents[2] / "docs/testing/results"


class XiangqiAiAuditTest(unittest.TestCase):
    def setUp(self) -> None:
        self.records = [json.loads(line) for line in (
            RESULTS / "2026-09-26-ai-medium-hard-v1.jsonl"
        ).read_text(encoding="utf-8").splitlines()]

    def report(self) -> str:
        return "\n".join(json.dumps(record) for record in self.records)

    def test_saved_baselines_pass(self) -> None:
        for pair in ("easy-medium", "medium-hard"):
            with self.subTest(pair=pair):
                audit((RESULTS / f"2026-09-26-ai-{pair}-v1.jsonl").read_text(encoding="utf-8"))

    def test_missing_summary_is_rejected(self) -> None:
        self.records.pop()
        with self.assertRaisesRegex(ValueError, "summary"):
            audit(self.report())

    def test_duplicate_color_is_rejected(self) -> None:
        self.records[2]["strong_side"] = self.records[1]["strong_side"]
        with self.assertRaisesRegex(ValueError, "color pair"):
            audit(self.report())

    def test_unfinished_must_not_count_as_draw(self) -> None:
        self.records[-1]["draws"] += 1
        self.records[-1]["unfinished"] -= 1
        with self.assertRaisesRegex(ValueError, "counts"):
            audit(self.report())

    def test_wrong_score_denominator_is_rejected(self) -> None:
        self.records[-1]["strong_score"] = 9.5 / 12
        with self.assertRaisesRegex(ValueError, "rate"):
            audit(self.report())

    def test_no_completed_games_has_null_rates(self) -> None:
        for game in self.records[1:-1]:
            game["moves"] = ["a0a1", "a1a0"] * 120
            game["final_fen"] = game["initial_fen"]
            game["outcome"] = "unfinished"
        summary = self.records[-1]
        summary.update(strong_wins=0, weak_wins=0, draws=0, unfinished=12,
                       completed=0, strong_score=None, decisive_strong_win_rate=None)
        summary["strong_latency"]["moves"] = 1440
        summary["weak_latency"]["moves"] = 1440
        # These synthetic moves test accounting only; the auditor is not a rules engine.
        audit(self.report())
        summary["strong_score"] = 0
        with self.assertRaisesRegex(ValueError, "null"):
            audit(self.report())

    def test_partial_game_cannot_be_labelled_capped(self) -> None:
        game = next(g for g in self.records[1:-1] if g["outcome"] == "unfinished")
        game["moves"].pop()
        with self.assertRaisesRegex(ValueError, "reach cap"):
            audit(self.report())

    def test_invalid_move_encoding_is_rejected(self) -> None:
        self.records[1]["moves"][0] = "j0j1"
        with self.assertRaisesRegex(ValueError, "move record"):
            audit(self.report())

    def test_different_paired_opening_is_rejected(self) -> None:
        self.records[2]["initial_fen"] += " "
        with self.assertRaisesRegex(ValueError, "opening differs"):
            audit(self.report())

    def test_wrong_latency_samples_are_rejected(self) -> None:
        self.records[-1]["strong_latency"]["moves"] += 1
        with self.assertRaisesRegex(ValueError, "sample count"):
            audit(self.report())

    def test_nonfinite_latency_is_rejected(self) -> None:
        self.records[-1]["strong_latency"]["mean_ms"] = float("nan")
        with self.assertRaisesRegex(ValueError, "invalid latency"):
            audit(self.report())


if __name__ == "__main__":
    unittest.main()
