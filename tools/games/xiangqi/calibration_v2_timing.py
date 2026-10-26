"""Pure timing projection of validated v2 observations; no execution or result authority."""

from __future__ import annotations

from dataclasses import dataclass, field
from fractions import Fraction
import re

from calibration_v2_progress import (
    MAX_CLOCK_NS, PendingRequest, ProgressContext, ProgressObservation,
)


class TimingError(ValueError):
    """A declared timing input is inconsistent; never repair or infer missing calls."""


def _integer(value: object, lower: int, upper: int, label: str) -> int:
    if type(value) is not int or not lower <= value <= upper:
        raise TimingError(f"invalid {label}")
    return value


@dataclass(frozen=True)
class ClockSample:
    """External sample from this run's producer clock, never the host wall clock.

    Identity fields are declarations to compare, not proof of physical clock origin.
    The future executor must establish the actual process and clock provenance.
    """

    run_id: str
    runtime_identity: str
    monotonic_ns: int

    def __post_init__(self) -> None:
        if type(self.run_id) is not str or re.fullmatch(r"[a-z0-9][a-z0-9._-]{0,63}", self.run_id) is None:
            raise TimingError("invalid sample run identity")
        if type(self.runtime_identity) is not str or re.fullmatch(r"[0-9a-f]{64}", self.runtime_identity) is None:
            raise TimingError("invalid sample runtime identity")
        _integer(self.monotonic_ns, 0, MAX_CLOCK_NS, "sample timestamp")


@dataclass(frozen=True)
class TimingObservation:
    run_id: str
    runtime_identity: str
    raw_sidecar_sha256: str
    source_status: str
    sidecar_ended: bool
    completed_requests: int
    completed_elapsed_ns_total: int
    completed_elapsed_ns_mean: Fraction | None
    completed_elapsed_ns_maximum: int | None
    pending: PendingRequest | None
    pending_wait_lower_bound_ns: int | None
    timing_status: str
    # G0 retains sum/max, not the per-request distribution or an authoritative result.
    completed_elapsed_ns_p95: None = field(default=None, init=False)
    full_latency_distribution_available: bool = field(default=False, init=False)
    physical_clock_origin_verified: bool = field(default=False, init=False)
    search_authorized: bool = field(default=False, init=False)
    strength_accepted: bool = field(default=False, init=False)
    next_slot_authorized: bool = field(default=False, init=False)


def _check_metrics(observation: ProgressObservation, context: ProgressContext) -> None:
    """Reject inconsistent projection inputs; do not reparse or certify raw sidecar bytes."""
    if type(observation) is not ProgressObservation or type(context) is not ProgressContext:
        raise TimingError("validated ProgressObservation and its external ProgressContext required")
    maximum_calls = context.expected_games * context.maximum_requests_per_game
    started = _integer(observation.requests_started, 0, maximum_calls, "started requests")
    returned = _integer(observation.requests_returned, 0, maximum_calls, "returned requests")
    total = _integer(observation.elapsed_ns_total, 0, MAX_CLOCK_NS, "completed elapsed total")
    maximum = _integer(observation.maximum_elapsed_ns, 0, MAX_CLOCK_NS, "completed elapsed maximum")
    games_started = _integer(observation.games_started, 0, context.expected_games, "started games")
    games_finished = _integer(observation.games_finished, 0, games_started, "finished games")
    if type(observation.sidecar_ended) is not bool:
        raise TimingError("invalid sidecar ended flag")
    if any(type(value) is not bool or value for value in (
            observation.search_authorized, observation.strength_accepted, observation.next_slot_authorized)):
        raise TimingError("a progress observation cannot grant result or execution authority")
    if type(observation.raw_sha256) is not str or re.fullmatch(r"[0-9a-f]{64}", observation.raw_sha256) is None:
        raise TimingError("invalid raw sidecar identity")
    pending = observation.pending
    if pending is None:
        if started != returned:
            raise TimingError("unpaired call has no explicit pending identity")
    else:
        if type(pending) is not PendingRequest or started != returned + 1 or games_started <= games_finished:
            raise TimingError("invalid outstanding call")
        _integer(pending.started_ns, 0, MAX_CLOCK_NS, "pending start timestamp")
        _integer(pending.game_index, games_started, games_started, "pending game")
        index = _integer(pending.request_index, 1, context.maximum_requests_per_game, "pending request")
        ply = _integer(pending.ply, 0, context.maximum_requests_per_game, "pending ply")
        if context.purpose == "diagnostic":
            if ply != 0 or pending.call_phase != ("cold" if index == 1 else "warm"):
                raise TimingError("pending diagnostic phase differs")
        elif ply != index or pending.call_phase != "play":
            raise TimingError("pending play call differs")
    if (returned == 0 and (total != 0 or maximum != 0)) or maximum > total or total > returned * maximum:
        raise TimingError("completed durations are inconsistent with paired request count")
    status = observation.status
    if status not in {"not-started", "partial-observation", "pending-request", "partial-tail", "sidecar-ended"}:
        raise TimingError("unknown observation status")
    if observation.sidecar_ended != (status == "sidecar-ended"):
        raise TimingError("sidecar end declaration differs from observation status")
    if observation.sidecar_ended and (pending is not None or games_finished != context.expected_games):
        raise TimingError("ended sidecar still has missing calls or games")
    if status == "pending-request" and pending is None:
        raise TimingError("pending status lacks a known request")
    if pending is not None and status not in {"pending-request", "partial-tail"}:
        raise TimingError("pending request hidden by a nonpending status")
    if status == "not-started" and any((started, returned, games_started, games_finished, observation.records)):
        raise TimingError("not-started observation already contains events")


def summarize(observation: ProgressObservation, validated_context: ProgressContext,
              sample: ClockSample | None = None) -> TimingObservation:
    """Summarize paired calls and, optionally, one explicitly identified pending wait.

    The caller must pass the same context used by G0 observe and a sample from
    that producer's same monotonic clock. G0's result carries no run identity,
    so these declarations cannot authenticate an arbitrary caller or clock.
    A missing sample means an in-flight call remains unmeasured, never zero.
    """
    _check_metrics(observation, validated_context)
    if sample is not None:
        if type(sample) is not ClockSample:
            raise TimingError("explicit same-process ClockSample required")
        if (sample.run_id, sample.runtime_identity) != (
                validated_context.run_id, validated_context.runtime_identity):
            raise TimingError("cross-run or cross-runtime clock sample")
    pending = observation.pending
    wait = None
    if pending is not None and sample is not None:
        if sample.monotonic_ns < pending.started_ns:
            raise TimingError("sample clock moved before the pending request")
        wait = sample.monotonic_ns - pending.started_ns
    returned = observation.requests_returned
    return TimingObservation(
        validated_context.run_id, validated_context.runtime_identity, observation.raw_sha256,
        observation.status, observation.sidecar_ended, returned, observation.elapsed_ns_total,
        Fraction(observation.elapsed_ns_total, returned) if returned else None,
        observation.maximum_elapsed_ns if returned else None, pending, wait,
        "pending-lower-bound" if wait is not None else "pending-unmeasured" if pending is not None else "paired-only",
    )
