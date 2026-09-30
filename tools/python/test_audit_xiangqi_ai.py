"""Regression guards for the saved calibration evidence and its denominators."""

import json
from pathlib import Path
import unittest

from audit_xiangqi_ai import audit, PROFILE_FIELDS, PROFILES_V1


RESULTS = Path(__file__).resolve().parents[2] / "docs/testing/results"


class XiangqiAiAuditTest(unittest.TestCase):
    def setUp(self) -> None:
        self.records = [json.loads(line) for line in (
            RESULTS / "2026-09-26-ai-medium-hard-v1.jsonl"
        ).read_text(encoding="utf-8").splitlines()]

    def report(self) -> str:
        return "\n".join(json.dumps(record) for record in self.records)

    def describe_profiles(self, candidate: bool = False) -> None:
        config = self.records[0]
        weak, strong = config["pair"].split("-")
        config.update(backend="pikafish", history_mode="full", selection_seed=20260926,
                      profile_version=0 if candidate else 1, base_profile_version=1,
                      profile_source="candidate" if candidate else "production",
                      weak_profile=dict(zip(PROFILE_FIELDS, PROFILES_V1[weak])),
                      strong_profile=dict(zip(PROFILE_FIELDS, PROFILES_V1[strong])))
        if candidate:
            config["weak_profile"]["depth"] = 3

    def test_self_describing_production_profile_passes(self) -> None:
        self.describe_profiles()
        audit(self.report())

    def test_explicit_candidate_on_tuning_suite_passes(self) -> None:
        self.describe_profiles(candidate=True)
        audit(self.report())

    def test_candidate_cannot_use_frozen_holdout(self) -> None:
        self.describe_profiles(candidate=True)
        self.records[0]["suite"] = 2
        with self.assertRaisesRegex(ValueError, "tuning-only"):
            audit(self.report())

    def test_candidate_cannot_be_labelled_production(self) -> None:
        self.describe_profiles(candidate=True)
        self.records[0].update(profile_version=1, profile_source="production")
        with self.assertRaisesRegex(ValueError, "production profile"):
            audit(self.report())

    def test_candidate_must_include_full_profile_and_history(self) -> None:
        self.describe_profiles(candidate=True)
        del self.records[0]["strong_profile"]
        with self.assertRaisesRegex(ValueError, "envelope"):
            audit(self.report())
        self.describe_profiles(candidate=True)
        self.records[0]["history_mode"] = "fen-only"
        with self.assertRaisesRegex(ValueError, "full-history"):
            audit(self.report())

    def test_identical_defaults_cannot_be_labelled_candidate(self) -> None:
        self.describe_profiles()
        self.records[0].update(profile_version=0, profile_source="candidate")
        with self.assertRaisesRegex(ValueError, "distinct"):
            audit(self.report())

    def test_explicit_profiles_reject_legacy_backend(self) -> None:
        self.describe_profiles(candidate=True)
        self.records[0]["backend"] = "legacy"
        with self.assertRaisesRegex(ValueError, "require Pikafish"):
            audit(self.report())

    def test_invalid_profile_values_are_rejected(self) -> None:
        for key, value in (("depth", True), ("depth", 129), ("nodes", -1), ("move_time_ms", 0),
                           ("move_time_ms", 2001), ("multi_pv", 0), ("multi_pv", 9),
                           ("deviation_percent", 101), ("score_window_cp", -1), ("score_window_cp", 1001)):
            with self.subTest(key=key, value=value):
                self.describe_profiles(candidate=True)
                self.records[0]["weak_profile"][key] = value
                with self.assertRaisesRegex(ValueError, "bounds"):
                    audit(self.report())

    def test_saved_baselines_pass(self) -> None:
        for pair in ("easy-medium", "medium-hard", "hard-master",
                     "easy-medium-horizon", "medium-hard-horizon",
                     "pikafish-easy-medium", "pikafish-medium-hard", "pikafish-hard-master",
                     "pikafish-history-smoke"):
            with self.subTest(pair=pair):
                audit((RESULTS / f"2026-09-26-ai-{pair}-v1.jsonl").read_text(encoding="utf-8"))

    def test_full_history_hard_master_baseline_keeps_capped_games_unfinished(self) -> None:
        report = RESULTS / "2026-09-26-ai-pikafish-hard-master-v1.jsonl"
        summary = audit(report.read_text(encoding="utf-8"))
        self.assertEqual((summary["strong_wins"], summary["draws"], summary["unfinished"]), (5, 2, 5))
        self.assertEqual(summary["completed"], 7)

    def test_missing_summary_is_rejected(self) -> None:
        self.records.pop()
        with self.assertRaisesRegex(ValueError, "summary"):
            audit(self.report())

    def test_unknown_backend_is_rejected(self) -> None:
        self.records[0]["backend"] = "unknown"
        with self.assertRaisesRegex(ValueError, "backend"):
            audit(self.report())

    def test_unknown_history_mode_is_rejected(self) -> None:
        self.records[0]["history_mode"] = "partial"
        with self.assertRaisesRegex(ValueError, "history mode"):
            audit(self.report())

    def test_pikafish_requires_versioned_profile_and_seed(self) -> None:
        self.records[0]["backend"] = "pikafish"
        with self.assertRaisesRegex(ValueError, "profile or seed"):
            audit(self.report())

    def test_holdout_configuration_accepts_paired_games(self) -> None:
        self.records[0]["suite"] = 2
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
