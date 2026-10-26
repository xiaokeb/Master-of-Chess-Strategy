"""Bounded latency samples from the same immutable G0 bytes; no execution authority."""

from __future__ import annotations

from dataclasses import dataclass, field
from fractions import Fraction
import io
import json

from calibration_v2_progress import (
    MAX_BYTES, MAX_LINE_BYTES, PendingRequest, ProgressContext, ProgressObservation, observe,
)

# Product response target, not a cancellation deadline or a performance acceptance test.
RESPONSE_TARGET_NS = 2_000_000_000


@dataclass(frozen=True)
class LatencySample:
    game_index: int
    request_index: int
    ply: int
    call_phase: str
    started_ns: int
    returned_ns: int

    @property
    def elapsed_ns(self) -> int:
        return self.returned_ns - self.started_ns


@dataclass(frozen=True)
class LatencyStatistics:
    count: int
    total_ns: int
    mean_ns: Fraction | None
    p95_ns: int | None
    maximum_ns: int | None
    above_response_target: int


@dataclass(frozen=True)
class LatencyReport:
    run_id: str
    runtime_identity: str
    observation: ProgressObservation
    samples: tuple[LatencySample, ...]
    paired: LatencyStatistics
    cold: LatencyStatistics
    warm: LatencyStatistics
    play: LatencyStatistics
    # Completeness is only about sidecar syntax, never game/result or clock authenticity.
    sidecar_distribution_complete: bool
    pending: PendingRequest | None
    quantile_method: str = field(default="nearest-rank-ceil-0.95-n", init=False)
    response_target_ns: int = field(default=RESPONSE_TARGET_NS, init=False)
    physical_clock_origin_verified: bool = field(default=False, init=False)
    search_authorized: bool = field(default=False, init=False)
    strength_accepted: bool = field(default=False, init=False)
    next_slot_authorized: bool = field(default=False, init=False)


def _statistics(samples: tuple[LatencySample, ...]) -> LatencyStatistics:
    durations = sorted(sample.elapsed_ns for sample in samples)
    count = len(durations)
    total = sum(durations)
    # Integer arithmetic fixes the quantile convention before any real result exists.
    p95 = durations[(95 * count + 99) // 100 - 1] if count else None
    return LatencyStatistics(count, total, Fraction(total, count) if count else None,
        p95, durations[-1] if count else None,
        sum(duration > RESPONSE_TARGET_NS for duration in durations))


def analyze(raw: bytes, context: ProgressContext) -> LatencyReport:
    """Validate and project one bounded immutable snapshot, including partial observations.

    Never reopen a pathname between validation and projection. Full G0 validation
    runs before any sample is returned; unknown keys, identities and bad ordering
    are rejected by the existing observer. An uncommitted tail is not a sample.
    ``play`` means a complete choose call; G0 cannot identify NNUE initialization,
    actual go/join, engine cache warmth, requested difficulty or pure search time.
    """
    if type(raw) is not bytes:
        raise TypeError("an immutable bytes snapshot is required")
    if len(raw) > MAX_BYTES:
        raise ValueError("sidecar snapshot exceeds the G0 byte limit")
    if type(context) is not ProgressContext:
        raise TypeError("an external ProgressContext is required")
    observation = observe(io.BytesIO(raw), context)
    samples: list[LatencySample] = []
    pending: dict | None = None
    stream = io.BytesIO(raw)
    # The observer already admitted exactly this immutable snapshot. Use its
    # committed record count rather than trying to complete or parse the tail.
    for _ in range(observation.records):
        record = json.loads(stream.readline(MAX_LINE_BYTES + 1))
        if record["event"] == "request_started":
            pending = record
        elif record["event"] == "request_returned":
            assert pending is not None  # Proven by observe, never caller supplied.
            samples.append(LatencySample(record["game_index"], record["request_index"],
                record["ply"], record["call_phase"], pending["monotonic_ns"], record["monotonic_ns"]))
            pending = None
    frozen = tuple(samples)
    paired = _statistics(frozen)
    # Cross-check the projection against G0's independently accumulated totals.
    if (paired.count, paired.total_ns, paired.maximum_ns or 0) != (
            observation.requests_returned, observation.elapsed_ns_total, observation.maximum_elapsed_ns):
        raise ValueError("latency projection disagrees with validated progress")
    return LatencyReport(context.run_id, context.runtime_identity, observation, frozen, paired,
        _statistics(tuple(sample for sample in frozen if sample.call_phase == "cold")),
        _statistics(tuple(sample for sample in frozen if sample.call_phase == "warm")),
        _statistics(tuple(sample for sample in frozen if sample.call_phase == "play")),
        observation.sidecar_ended, observation.pending)
