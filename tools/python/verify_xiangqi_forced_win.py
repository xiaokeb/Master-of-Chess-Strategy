"""Independent shallow Xiangqi puzzle oracle for host-side content screening.

This covers ordinary legal movement, check, mate and stalemate for at most two
red turns. It deliberately does not model repetition or the 2020 chase rules;
the native engine remains authoritative for publication.
"""

from __future__ import annotations

import argparse
from dataclasses import dataclass
from functools import cache, lru_cache
import json
from pathlib import Path

from audit_chinese_chess_endgames import Level, parse_pack


TYPES = {"GENERAL": 1, "ADVISOR": 2, "ELEPHANT": 3, "HORSE": 4,
         "CHARIOT": 5, "CANNON": 6, "SOLDIER": 7}
RED, BLACK = 1, -1
Board = tuple[int, ...]
Move = tuple[int, int]


def _xy(square: int) -> tuple[int, int]:
    return square % 9, square // 9


def _palace(side: int, x: int, y: int) -> bool:
    return 3 <= x <= 5 and (7 <= y <= 9 if side == RED else 0 <= y <= 2)


def _blockers(board: Board, source: int, target: int) -> int | None:
    sx, sy = _xy(source)
    tx, ty = _xy(target)
    if sx != tx and sy != ty:
        return None
    step = (1 if tx > sx else -1) if sy == ty else (9 if ty > sy else -9)
    return sum(board[square] != 0 for square in range(source + step, target, step))


def _attacks(board: Board, source: int, target: int) -> bool:
    code = board[source]
    if not code or source == target:
        return False
    side, kind = (RED if code > 0 else BLACK), abs(code)
    sx, sy = _xy(source)
    tx, ty = _xy(target)
    dx, dy = tx - sx, ty - sy
    ax, ay = abs(dx), abs(dy)
    if kind == 1:
        if abs(board[target]) == 1 and board[target] * side < 0 and sx == tx:
            return _blockers(board, source, target) == 0
        return ax + ay == 1 and _palace(side, tx, ty)
    if kind == 2:
        return ax == ay == 1 and _palace(side, tx, ty)
    if kind == 3:
        return (ax == ay == 2 and (ty >= 5 if side == RED else ty <= 4) and
                board[(sy + dy // 2) * 9 + sx + dx // 2] == 0)
    if kind == 4:
        if (ax, ay) not in ((2, 1), (1, 2)):
            return False
        leg_x = sx + (1 if dx > 0 else -1) if ax == 2 else sx
        leg_y = sy + (1 if dy > 0 else -1) if ay == 2 else sy
        return board[leg_y * 9 + leg_x] == 0
    if kind in (5, 6):
        blockers = _blockers(board, source, target)
        return blockers is not None and (blockers == 0 if kind == 5 or board[target] == 0 else blockers == 1)
    if kind == 7:
        return (dx == 0 and dy == (-1 if side == RED else 1)) or (
            (sy <= 4 if side == RED else sy >= 5) and ax == 1 and dy == 0)
    raise ValueError("unknown Xiangqi piece")


def _after(board: Board, move: Move) -> Board:
    source, target = move
    next_board = list(board)
    next_board[target] = next_board[source]
    next_board[source] = 0
    return tuple(next_board)


@lru_cache(maxsize=50_000)
def _in_check(board: Board, side: int) -> bool:
    try:
        general = board.index(side)
    except ValueError:
        return True
    return any(code * side < 0 and _attacks(board, source, general)
               for source, code in enumerate(board))


@lru_cache(maxsize=50_000)
def legal_moves(board: Board, side: int) -> tuple[Move, ...]:
    result = []
    for source, code in enumerate(board):
        if code * side <= 0:
            continue
        for target, occupant in enumerate(board):
            if occupant * side > 0 or not _attacks(board, source, target):
                continue
            move = (source, target)
            if not _in_check(_after(board, move), side):
                result.append(move)
    return tuple(result)


def board_from_level(level: Level) -> Board:
    board = [0] * 90
    for x, y, side, kind in level.pieces:
        board[y * 9 + x] = TYPES[kind] * (RED if side == "RED" else BLACK)
    return tuple(board)


def principal_variation_wins(level: Level, require_check: bool = False) -> bool:
    """Check that the shown line is legal and ends in a red terminal win."""
    board, side = board_from_level(level), RED
    for fx, fy, tx, ty in level.moves:
        if RED not in board or BLACK not in board:
            return False
        move = (fy * 9 + fx, ty * 9 + tx)
        if move not in legal_moves(board, side):
            return False
        board = _after(board, move)
        side = -side
    return (RED in board and side == BLACK and
            (BLACK not in board or not legal_moves(board, BLACK)) and
            (not require_check or (BLACK in board and _in_check(board, BLACK))))


@dataclass(frozen=True)
class ShallowProof:
    status: str
    winning_first_moves: tuple[tuple[int, int, int, int], ...]
    visited_nodes: int


def prove_red_win(level: Level, red_moves: int, node_budget: int = 200_000) -> ShallowProof:
    """Exhaustively check all black replies; budget exhaustion is inconclusive."""
    if red_moves not in (1, 2) or node_budget < 1:
        raise ValueError("red_moves must be 1 or 2 and node_budget positive")
    initial = board_from_level(level)
    if _in_check(initial, RED) or _in_check(initial, BLACK):
        return ShallowProof("invalid_start", (), 0)
    visited = 0

    @cache
    def can_force(board: Board, side: int, remaining: int) -> bool:
        nonlocal visited
        visited += 1
        if visited > node_budget:
            raise OverflowError("puzzle proof budget exhausted")
        if RED not in board:
            return False
        if BLACK not in board:
            return True
        options = legal_moves(board, side)
        if not options:
            return side == BLACK
        if side == RED:
            if remaining == 0:
                return False
            return any(can_force(_after(board, move), BLACK, remaining - 1) for move in options)
        return all(can_force(_after(board, move), RED, remaining) for move in options)

    winners = []
    try:
        if RED not in initial or BLACK not in initial or not legal_moves(initial, RED):
            return ShallowProof("invalid_start", (), visited)
        for source, target in legal_moves(initial, RED):
            if can_force(_after(initial, (source, target)), BLACK, red_moves - 1):
                sx, sy = _xy(source)
                tx, ty = _xy(target)
                winners.append((sx, sy, tx, ty))
    except OverflowError:
        return ShallowProof("budget_exhausted", (), visited)
    return ShallowProof("complete", tuple(winners), visited)


def audit_published_pack(content: str) -> dict[str, object]:
    """Check the current one-/two-turn themes, never treating unknown as solved."""
    levels = parse_pack(content)[1]
    themes = {"多解胜局": 0, "唯一一步杀": 0, "两步强制胜": 0}
    nodes = 0
    for level in levels:
        if (level.limit not in (1, 2) or level.theme not in themes or
                not principal_variation_wins(level, require_check=level.theme == "唯一一步杀")):
            raise ValueError(f"{level.id}: unsupported or invalid shallow puzzle")
        proof = prove_red_win(level, level.limit)
        if proof.status != "complete" or level.moves[0] not in proof.winning_first_moves:
            raise ValueError(f"{level.id}: incomplete or mismatched proof")
        count = len(proof.winning_first_moves)
        if ((level.theme == "多解胜局" and (level.limit != 1 or count <= 1)) or
                (level.theme != "多解胜局" and count != 1) or
                (level.theme == "两步强制胜" and level.limit != 2) or
                (level.theme == "唯一一步杀" and level.limit != 1)):
            raise ValueError(f"{level.id}: declared theme does not match exhaustive search")
        if level.theme == "两步强制胜":
            shallow = prove_red_win(level, 1)
            if shallow.status != "complete" or shallow.winning_first_moves:
                raise ValueError(f"{level.id}: one-turn result is not proven absent")
        themes[level.theme] += 1
        nodes += proof.visited_nodes
    return {"level_count": len(levels), "themes": themes,
            "visited_nodes": nodes, "scope": "shallow-host-oracle-not-native-acceptance"}


def main() -> int:
    root = Path(__file__).resolve().parents[2]
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("pack", nargs="?", type=Path,
                        default=root / "app/src/main/assets/endgames/chinese_chess/endgames-v5.txt")
    args = parser.parse_args()
    try:
        report = audit_published_pack(args.pack.read_text(encoding="utf-8"))
    except (ValueError, OSError, UnicodeDecodeError) as error:
        parser.exit(1, f"shallow Xiangqi audit failed: {error}\n")
    print(json.dumps(report, ensure_ascii=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
