"""Check calibration accounting, not chess legality or statistical sufficiency."""

from collections import Counter
import json
import math
from pathlib import Path
import re
import sys


def audit(text: str) -> dict:
    records = [json.loads(line) for line in text.splitlines() if line.strip()]
    if len(records) < 4:
        raise ValueError("incomplete report")
    config, *games, summary = records
    if config.get("type") != "config" or summary.get("type") != "summary":
        raise ValueError("missing config or summary")
    if config.get("suite") != 1 or config.get("pair") not in (
        "easy-medium", "medium-hard", "hard-master"
    ):
        raise ValueError("unsupported suite or pair")
    count, cap = config["openings"], config["max_search_plies"]
    if type(count) is not int or not 1 <= count <= 6 or type(cap) is not int or not 16 <= cap <= 600:
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
    return summary


if __name__ == "__main__":
    try:
        if len(sys.argv) < 2:
            raise ValueError("usage: audit_xiangqi_ai.py <report.jsonl> [...]")
        for name in sys.argv[1:]:
            print(json.dumps({"file": name, **audit(Path(name).read_text(encoding="utf-8"))}))
    except (ValueError, KeyError, TypeError, OSError) as error:
        sys.exit(f"calibration audit failed: {error}")
