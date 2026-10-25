"""Pure, bounded v2 sidecar observation. No execution, registration or result authority."""

from __future__ import annotations

from dataclasses import dataclass, field
import hashlib
import json
import re
from typing import BinaryIO

SCHEMA = "mocs-xiangqi-progress-v2-20261025"
MAX_BYTES = 128 * 1024 * 1024
MAX_LINE_BYTES = 4096
MAX_GAMES = 96
MAX_REQUESTS_PER_GAME = 600
MAX_RECORDS = MAX_GAMES * (2 * MAX_REQUESTS_PER_GAME + 2) + 2
MAX_CLOCK_NS = (1 << 63) - 1
_PAIRS = {"easy-medium", "medium-hard", "hard-master"}
_TRAINING_JOBS = {"M0-hard", "M1-hard", "E0-M0", "E1-M0", "E1-M1", "hard-master"}
_COMMON = {"schema", "event", "protocol_id", "protocol_sha256", "purpose", "pair_id",
           "run_id", "runtime_identity", "seq", "monotonic_ns"}
_REQUEST = {"game_index", "request_index", "ply", "call_phase"}
_EXTRA = {"run_started": set(), "game_started": {"game_index"},
          "request_started": _REQUEST, "request_returned": _REQUEST,
          "game_finished": {"game_index", "request_count"},
          "run_finished": {"game_count", "request_count"}}


class ProgressError(ValueError):
    """A committed record or externally supplied context violates the contract."""


def _integer(value: object, lower: int, upper: int, label: str) -> int:
    if type(value) is not int or not lower <= value <= upper:
        raise ProgressError(f"invalid {label}")
    return value


def _digest(value: object, label: str) -> str:
    if type(value) is not str or re.fullmatch(r"[0-9a-f]{64}", value) is None:
        raise ProgressError(f"invalid {label}")
    return value


@dataclass(frozen=True)
class ProgressContext:
    """Supply from a separately reviewed registration; never reconstruct from the sidecar."""

    protocol_id: str
    protocol_sha256: str
    purpose: str
    pair_id: str
    run_id: str
    runtime_identity: str
    expected_games: int
    maximum_requests_per_game: int

    def __post_init__(self) -> None:
        if type(self.purpose) is not str or self.purpose not in {"diagnostic", "training", "holdout"}:
            raise ProgressError("invalid purpose")
        namespace = rf"calibration-budget-v2-{self.purpose}-[a-z0-9][a-z0-9-]{{0,63}}"
        if type(self.protocol_id) is not str or re.fullmatch(namespace, self.protocol_id) is None:
            raise ProgressError("independent purpose-specific v2 protocol required")
        _digest(self.protocol_sha256, "external protocol SHA")
        _digest(self.runtime_identity, "external runtime identity")
        if type(self.run_id) is not str or re.fullmatch(r"[a-z0-9][a-z0-9._-]{0,63}", self.run_id) is None:
            raise ProgressError("invalid run identity")
        expected = {"diagnostic": 1, "training": 12, "holdout": 96}[self.purpose]
        _integer(self.expected_games, expected, expected, "expected game count")
        _integer(self.maximum_requests_per_game, 1, MAX_REQUESTS_PER_GAME, "request ceiling")
        if self.purpose == "diagnostic":
            if type(self.pair_id) is not str or self.pair_id != "diagnostic" or self.maximum_requests_per_game != 2:
                raise ProgressError("diagnostic process observes exactly cold/warm requests")
        elif type(self.pair_id) is not str or self.pair_id not in (
                _TRAINING_JOBS if self.purpose == "training" else _PAIRS):
            raise ProgressError("invalid calibration pair")


@dataclass(frozen=True)
class PendingRequest:
    game_index: int
    request_index: int
    ply: int
    call_phase: str
    started_ns: int


@dataclass(frozen=True)
class ProgressObservation:
    status: str
    records: int
    bytes_observed: int
    raw_sha256: str
    games_started: int
    games_finished: int
    requests_started: int
    requests_returned: int
    elapsed_ns_total: int
    maximum_elapsed_ns: int
    pending: PendingRequest | None
    sidecar_ended: bool
    trailing_bytes: int
    trailing_sha256: str | None
    search_authorized: bool = field(default=False, init=False)
    strength_accepted: bool = field(default=False, init=False)
    next_slot_authorized: bool = field(default=False, init=False)


def _pairs(pairs: list[tuple[str, object]]) -> dict[str, object]:
    result: dict[str, object] = {}
    for key, value in pairs:
        if key in result:
            raise ProgressError("duplicate JSON key")
        result[key] = value
    return result


def _nonfinite(value: str) -> None:
    raise ProgressError("non-finite JSON value: " + value)


def _record(raw: bytes) -> dict[str, object]:
    try:
        value = json.loads(raw.decode("utf-8"), object_pairs_hook=_pairs, parse_constant=_nonfinite)
    except (UnicodeError, ValueError, RecursionError) as error:
        raise ProgressError("invalid committed JSON record") from error
    if type(value) is not dict:
        raise ProgressError("record must be a JSON object")
    event = value.get("event")
    if type(event) is not str or event not in _EXTRA or set(value) != _COMMON | _EXTRA[event]:
        raise ProgressError("unknown event or nonexact record fields")
    return value


class _State:
    def __init__(self, context: ProgressContext):
        self.context = context
        self.records = 0
        self.last_ns = -1
        self.started = False
        self.ended = False
        self.games_started = 0
        self.games_finished = 0
        self.game_open = False
        self.returned_in_game = 0
        self.requests_started = 0
        self.requests_returned = 0
        self.elapsed_ns_total = 0
        self.maximum_elapsed_ns = 0
        self.pending: PendingRequest | None = None

    def consume(self, record: dict[str, object]) -> None:
        c = self.context
        if self.ended:
            raise ProgressError("data after run_finished")
        expected = {"schema": SCHEMA, "protocol_id": c.protocol_id,
                    "protocol_sha256": c.protocol_sha256, "purpose": c.purpose,
                    "pair_id": c.pair_id, "run_id": c.run_id, "runtime_identity": c.runtime_identity}
        if any(type(record[k]) is not str or record[k] != value for k, value in expected.items()):
            raise ProgressError("sidecar differs from external context")
        _integer(record["seq"], self.records, self.records, "contiguous sequence")
        clock = _integer(record["monotonic_ns"], 0, MAX_CLOCK_NS, "monotonic timestamp")
        if clock < self.last_ns:
            raise ProgressError("monotonic clock moved backwards")
        event = record["event"]
        if not self.started and event != "run_started":
            raise ProgressError("run_started must be first")
        if event == "run_started":
            if self.started:
                raise ProgressError("duplicate run_started")
            self.started = True
        elif event == "game_started":
            if self.game_open or self.pending is not None:
                raise ProgressError("game started before previous game finished")
            _integer(record["game_index"], self.games_finished + 1,
                     min(self.games_finished + 1, c.expected_games), "next game index")
            self.game_open = True
            self.games_started += 1
            self.returned_in_game = 0
        elif event in {"request_started", "request_returned"}:
            if not self.game_open:
                raise ProgressError("request outside a game")
            game = _integer(record["game_index"], self.games_started, self.games_started, "request game")
            index = _integer(record["request_index"], 1, c.maximum_requests_per_game, "request index")
            ply = _integer(record["ply"], 0, c.maximum_requests_per_game, "ply")
            phase = record["call_phase"]
            if c.purpose == "diagnostic":
                expected_phase = "cold" if index == 1 else "warm"
                if ply != 0 or phase != expected_phase:
                    raise ProgressError("diagnostic calls keep ply zero and cold/warm order")
            elif ply != index or phase != "play":
                raise ProgressError("game requests must follow one-based search plies")
            if event == "request_started":
                if self.pending is not None or index != self.returned_in_game + 1:
                    raise ProgressError("overlapping, repeated or skipped request")
                self.pending = PendingRequest(game, index, ply, str(phase), clock)
                self.requests_started += 1
            else:
                pending = self.pending
                if pending is None or (game, index, ply, phase) != (
                        pending.game_index, pending.request_index, pending.ply, pending.call_phase):
                    raise ProgressError("return has no identical outstanding request")
                elapsed = clock - pending.started_ns
                self.elapsed_ns_total += elapsed
                self.maximum_elapsed_ns = max(self.maximum_elapsed_ns, elapsed)
                self.pending = None
                self.returned_in_game += 1
                self.requests_returned += 1
        elif event == "game_finished":
            if not self.game_open or self.pending is not None:
                raise ProgressError("game finished with no game or an outstanding request")
            _integer(record["game_index"], self.games_started, self.games_started, "finished game")
            _integer(record["request_count"], self.returned_in_game, self.returned_in_game, "game request count")
            if c.purpose == "diagnostic" and self.returned_in_game != 2:
                raise ProgressError("diagnostic cold/warm observation is incomplete")
            self.game_open = False
            self.games_finished += 1
        elif event == "run_finished":
            if self.game_open or self.pending is not None or self.games_finished != c.expected_games:
                raise ProgressError("run finished before all declared game observations")
            _integer(record["game_count"], self.games_finished, self.games_finished, "run game count")
            _integer(record["request_count"], self.requests_returned, self.requests_returned, "run request count")
            self.ended = True
        self.records += 1
        self.last_ns = clock


def observe(stream: BinaryIO, context: ProgressContext, *, maximum_bytes: int = MAX_BYTES) -> ProgressObservation:
    """Read bounded LF-committed records; an unterminated tail is retained, never repaired.

    Passing an opened stream does not attest its producer, clock, or runtime. A coherent
    sidecar can never authorize search, claim a game result, or release a one-shot slot.
    """
    if type(context) is not ProgressContext:
        raise ProgressError("an explicit external ProgressContext is required")
    _integer(maximum_bytes, 1, MAX_BYTES, "stream byte ceiling")
    state = _State(context)
    raw_hash = hashlib.sha256()
    byte_count = 0
    tail = b""
    record_limit = context.expected_games * (2 * context.maximum_requests_per_game + 2) + 2
    while True:
        raw = stream.readline(MAX_LINE_BYTES + 1)
        if type(raw) is not bytes:
            raise ProgressError("binary stream required")
        if not raw:
            break
        byte_count += len(raw)
        if byte_count > maximum_bytes or len(raw) > MAX_LINE_BYTES:
            raise ProgressError("sidecar byte or line ceiling exceeded")
        if state.ended:
            raise ProgressError("data after run_finished")
        raw_hash.update(raw)
        if not raw.endswith(b"\n"):
            tail = raw
            if stream.readline(1):
                raise ProgressError("unterminated fragment is not the last input")
            break
        if b"\r" in raw:
            raise ProgressError("only native LF framing is accepted")
        if state.records >= record_limit:
            raise ProgressError("sidecar record ceiling exceeded")
        state.consume(_record(raw[:-1]))
    if tail:
        status = "partial-tail"
    elif state.ended:
        status = "sidecar-ended"
    elif state.pending is not None:
        status = "pending-request"
    elif not state.started:
        status = "not-started"
    else:
        status = "partial-observation"
    return ProgressObservation(status, state.records, byte_count, raw_hash.hexdigest(),
        state.games_started, state.games_finished, state.requests_started, state.requests_returned,
        state.elapsed_ns_total, state.maximum_elapsed_ns, state.pending, state.ended,
        len(tail), hashlib.sha256(tail).hexdigest() if tail else None)
