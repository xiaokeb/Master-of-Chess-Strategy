"""Check calibration accounting, not chess legality or statistical sufficiency."""

from collections import Counter
import json
import math
from pathlib import Path
import re
import sys


PROFILE_FIELDS = ("depth", "nodes", "move_time_ms", "multi_pv", "deviation_percent", "score_window_cp")
PROFILES_V1 = dict(zip(("easy", "medium", "hard", "master"), (
    (2, 2000, 250, 8, 75, 250), (5, 12000, 500, 4, 20, 80),
    (10, 80000, 900, 1, 0, 0), (0, 0, 1200, 1, 0, 0),
)))


def audit_profiles(config: dict) -> None:
    """Experimental reports cannot masquerade as production or consume the holdout suite."""
    if type(config.get("profile_version")) is not int or config["profile_version"] not in (0, 1) or config.get("selection_seed") != 20260926:
        raise ValueError("unsupported Pikafish profile or seed")
    fields = ("base_profile_version", "profile_source", "weak_profile", "strong_profile")
    if not any(field in config for field in fields) and config["profile_version"] == 1:
        return  # Historical v1 reports predate self-describing parameter envelopes.
    if any(field not in config for field in fields) or type(config["base_profile_version"]) is not int or config["base_profile_version"] != 1:
        raise ValueError("incomplete profile envelope")
    expected = [PROFILES_V1[difficulty] for difficulty in config["pair"].split("-")]
    actual = []
    for key in ("weak_profile", "strong_profile"):
        profile = config[key]
        if not isinstance(profile, dict) or set(profile) != set(PROFILE_FIELDS):
            raise ValueError("invalid profile fields")
        values = tuple(profile[field] for field in PROFILE_FIELDS)
        bounds = ((0, 128), (0, 1_000_000_000), (1, 2000), (1, 8), (0, 100), (0, 1000))
        if any(type(value) is not int or not low <= value <= high for value, (low, high) in zip(values, bounds)):
            raise ValueError("profile outside calibration bounds")
        actual.append(values)
    if config["profile_version"] == 0:
        if (config["profile_source"] != "candidate" or actual == expected or config["suite"] != 1 or
                config.get("history_mode") not in ("full", "adaptive-rule60-window")):
            raise ValueError("candidate must be distinct, replay-history and tuning-only")
    elif config["profile_source"] != "production" or actual != expected:
        raise ValueError("production profile differs from its versioned table")
    if config["pair"] == "hard-master" and (type(config.get("master_move_ms")) is not int or config["master_move_ms"] != actual[1][2]):
        raise ValueError("master budget differs from profile")


def audit(text: str) -> dict:
    records = [json.loads(line) for line in text.splitlines() if line.strip()]
    if len(records) < 4:
        raise ValueError("incomplete report")
    config, *games, summary = records
    if config.get("type") != "config" or summary.get("type") != "summary":
        raise ValueError("missing config or summary")
    if config.get("suite") not in (1, 2) or config.get("pair") not in (
        "easy-medium", "medium-hard", "hard-master"
    ):
        raise ValueError("unsupported suite or pair")
    if config.get("backend", "legacy") not in ("legacy", "pikafish"):
        raise ValueError("unsupported AI backend")
    if config.get("history_mode", "fen-only") not in ("fen-only", "full", "adaptive-rule60-window"):
        raise ValueError("unsupported search history mode")
    if config.get("backend") == "pikafish":
        audit_profiles(config)
    elif any(key in config for key in ("profile_source", "weak_profile", "strong_profile")):
        raise ValueError("explicit profiles require Pikafish backend")
    count, cap = config["openings"], config["max_search_plies"]
    maximum_count = 6 if config["suite"] == 1 else 12
    if type(count) is not int or not 1 <= count <= maximum_count or type(cap) is not int or not 16 <= cap <= 600:
        raise ValueError("invalid configuration")
    expected_pairs = {(opening, side) for opening in range(1, count + 1) for side in (0, 1)}
    if len(games) != 2 * count or {(g["opening"], g["strong_side"]) for g in games} != expected_pairs:
        raise ValueError("missing or duplicate color pair")
    outcomes = Counter()
    strong_moves = weak_moves = 0
    starts = {}
    for game in games:
        if game.get("type") != "game" or game["outcome"] not in (
            "strong_win", "weak_win", "draw", "unfinished"
        ):
            raise ValueError("invalid game outcome")
        start, end = game["initial_fen"], game["final_fen"]
        if len(start.split()) != 6 or start.split()[1] != "w" or len(end.split()) != 6:
            raise ValueError("invalid FEN envelope")
        if starts.setdefault(game["opening"], start) != start:
            raise ValueError("paired opening differs")
        moves = game["moves"]
        if not isinstance(moves, list) or not 1 <= len(moves) <= cap or any(
            not isinstance(move, str) or re.fullmatch(r"[a-i][0-9][a-i][0-9]", move) is None
            for move in moves
        ):
            raise ValueError("invalid move record")
        if game["outcome"] == "unfinished" and len(moves) != cap:
            raise ValueError("unfinished game did not reach cap")
        if end.split()[1] != ("w" if len(moves) % 2 == 0 else "b"):
            raise ValueError("final side does not match move count")
        strong_count = (len(moves) + (game["strong_side"] == 0)) // 2
        strong_moves += strong_count
        weak_moves += len(moves) - strong_count
        outcomes[game["outcome"]] += 1
    wins, losses, draws = (outcomes[key] for key in ("strong_win", "weak_win", "draw"))
    completed = wins + losses + draws
    expected = dict(strong_wins=wins, weak_wins=losses, draws=draws,
                    unfinished=outcomes["unfinished"], completed=completed)
    if any(type(summary[key]) is not int or summary[key] != value for key, value in expected.items()):
        raise ValueError("summary counts disagree")
    rates = {
        "strong_score": (wins + 0.5 * draws) / completed if completed else None,
        "decisive_strong_win_rate": wins / (wins + losses) if wins + losses else None,
    }
    for key, expected_rate in rates.items():
        actual = summary[key]
        if expected_rate is None:
            if actual is not None:
                raise ValueError("undefined rate must be null")
        elif not isinstance(actual, (int, float)) or not math.isclose(actual, expected_rate, abs_tol=1e-6):
            raise ValueError("summary rate disagrees")
    for key, move_count in (("strong_latency", strong_moves), ("weak_latency", weak_moves)):
        timing = summary[key]
        if timing["moves"] != move_count:
            raise ValueError("latency sample count disagrees")
        values = [timing[name] for name in ("mean_ms", "p95_ms", "max_ms")]
        if any(not isinstance(v, (int, float)) or not math.isfinite(v) or v < 0 for v in values):
            raise ValueError("invalid latency")
        if max(values[:2]) > values[2]:
            raise ValueError("latency exceeds maximum")
    # Capped games are right-censored, not draws. Bound the eventual score
    # without assuming their outcomes; these are not confidence intervals.
    total = completed + outcomes["unfinished"]
    settled_points = wins + 0.5 * draws
    return {
        **summary,
        "completion_fraction": completed / total,
        "score_lower_bound_all_games": settled_points / total,
        "score_upper_bound_all_games": (settled_points + outcomes["unfinished"]) / total,
    }


if __name__ == "__main__":
    try:
        if len(sys.argv) < 2:
            raise ValueError("usage: audit_xiangqi_ai.py <report.jsonl> [...]")
        for name in sys.argv[1:]:
            print(json.dumps({"file": name, **audit(Path(name).read_text(encoding="utf-8"))}))
    except (ValueError, KeyError, TypeError, OSError) as error:
        sys.exit(f"calibration audit failed: {error}")
