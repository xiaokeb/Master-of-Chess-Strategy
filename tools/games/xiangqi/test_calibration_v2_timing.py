"""Real G0 parsing of toy streams only: no engine, producer or clock provenance claim."""

from dataclasses import FrozenInstanceError, replace
from fractions import Fraction
import io
import json
import unittest

import calibration_v2_progress as progress
import calibration_v2_timing as timing


def context(purpose="diagnostic"):
    return progress.ProgressContext("calibration-budget-v2-" + purpose + "-timing-test", "a" * 64,
        purpose, {"diagnostic": "diagnostic", "training": "M0-hard", "holdout": "easy-medium"}[purpose],
        "toy-run-1", "b" * 64, {"diagnostic": 1, "training": 12, "holdout": 96}[purpose],
        2 if purpose == "diagnostic" else 600)


class Sidecar:
    def __init__(self, c=None):
        self.context = c or context()
        self.rows = []

    def event(self, event, clock, **extra):
        c = self.context
        self.rows.append(dict(schema=progress.SCHEMA, event=event, protocol_id=c.protocol_id,
            protocol_sha256=c.protocol_sha256, purpose=c.purpose, pair_id=c.pair_id,
            run_id=c.run_id, runtime_identity=c.runtime_identity, seq=len(self.rows),
            monotonic_ns=clock, **extra))
        return self

    def call(self, event, index, clock, *, game=1):
        diagnostic = self.context.purpose == "diagnostic"
        return self.event(event, clock, game_index=game, request_index=index,
            ply=0 if diagnostic else index,
            call_phase=("cold" if index == 1 else "warm") if diagnostic else "play")

    def observed(self, tail=b""):
        raw = b"".join((json.dumps(row, separators=(",", ":")) + "\n").encode("ascii") for row in self.rows)
        return progress.observe(io.BytesIO(raw + tail), self.context)


def diagnostic():
    return (Sidecar().event("run_started", 1).event("game_started", 2, game_index=1)
        .call("request_started", 1, 10).call("request_returned", 1, 17)
        .call("request_started", 2, 100).call("request_returned", 2, 112)
        .event("game_finished", 113, game_index=1, request_count=2)
        .event("run_finished", 114, game_count=1, request_count=2))


def sample(c, clock):
    return timing.ClockSample(c.run_id, c.runtime_identity, clock)


class TimingTests(unittest.TestCase):
    def test_completed_mean_is_exact_and_does_not_invent_p95_or_authority(self):
        s = diagnostic()
        got = timing.summarize(s.observed(), s.context)
        self.assertEqual((got.completed_requests, got.completed_elapsed_ns_total), (2, 19))
        self.assertEqual(got.completed_elapsed_ns_mean, Fraction(19, 2))
        self.assertEqual(got.completed_elapsed_ns_maximum, 12)
        self.assertIsNone(got.pending_wait_lower_bound_ns)
        self.assertIsNone(got.completed_elapsed_ns_p95)
        self.assertFalse(got.full_latency_distribution_available or got.physical_clock_origin_verified)
        self.assertFalse(got.search_authorized or got.strength_accepted or got.next_slot_authorized)
        self.assertTrue(got.sidecar_ended)
        self.assertEqual(got.timing_status, "paired-only")

    def test_inflight_wait_is_only_a_lower_bound_not_a_completed_latency(self):
        s = Sidecar().event("run_started", 0).event("game_started", 1, game_index=1)
        s.call("request_started", 1, 20)
        got = timing.summarize(s.observed(), s.context, sample(s.context, 120))
        self.assertEqual(got.pending_wait_lower_bound_ns, 100)
        self.assertEqual((got.completed_requests, got.completed_elapsed_ns_total), (0, 0))
        self.assertIsNone(got.completed_elapsed_ns_mean)
        self.assertIsNone(got.completed_elapsed_ns_maximum)
        self.assertFalse(got.sidecar_ended)
        self.assertEqual(got.timing_status, "pending-lower-bound")

    def test_missing_clock_leaves_pending_unmeasured_not_zero(self):
        s = Sidecar().event("run_started", 0).event("game_started", 1, game_index=1)
        s.call("request_started", 1, 20)
        got = timing.summarize(s.observed(), s.context)
        self.assertEqual(got.timing_status, "pending-unmeasured")
        self.assertIsNone(got.pending_wait_lower_bound_ns)
        self.assertEqual(got.pending.started_ns, 20)

    def test_equal_sample_time_gives_real_zero_lower_bound(self):
        s = Sidecar().event("run_started", 0).event("game_started", 1, game_index=1)
        s.call("request_started", 1, 20)
        got = timing.summarize(s.observed(), s.context, sample(s.context, 20))
        self.assertEqual(got.pending_wait_lower_bound_ns, 0)

    def test_pending_sample_clock_cannot_move_backwards(self):
        s = Sidecar().event("run_started", 0).event("game_started", 1, game_index=1)
        s.call("request_started", 1, 20)
        with self.assertRaises(timing.TimingError):
            timing.summarize(s.observed(), s.context, sample(s.context, 19))

    def test_declared_cross_process_and_runtime_samples_are_rejected(self):
        s = diagnostic()
        for bad in (timing.ClockSample("other-run", s.context.runtime_identity, 1000),
                    timing.ClockSample(s.context.run_id, "c" * 64, 1000)):
            with self.subTest(sample=bad), self.assertRaises(timing.TimingError):
                timing.summarize(s.observed(), s.context, bad)
        with self.assertRaises(timing.TimingError):
            timing.summarize(s.observed(), s.context, {"monotonic_ns": 1000})

    def test_clock_rejects_bool_float_negative_overflow_and_malformed_identity(self):
        c = context()
        for clock in (True, 1.0, -1, 1 << 63):
            with self.subTest(clock=clock), self.assertRaises(timing.TimingError):
                timing.ClockSample(c.run_id, c.runtime_identity, clock)
        for run_id, runtime in (("../run", c.runtime_identity), (c.run_id, "B" * 64), (False, c.runtime_identity)):
            with self.subTest(run=run_id, runtime=runtime), self.assertRaises(timing.TimingError):
                timing.ClockSample(run_id, runtime, 0)

    def test_completed_prefix_and_pending_call_remain_separate(self):
        s = diagnostic()
        s.rows = s.rows[:5]
        got = timing.summarize(s.observed(), s.context, sample(s.context, 150))
        self.assertEqual((got.completed_requests, got.completed_elapsed_ns_total), (1, 7))
        self.assertEqual((got.completed_elapsed_ns_mean, got.completed_elapsed_ns_maximum), (7, 7))
        self.assertEqual((got.pending.request_index, got.pending_wait_lower_bound_ns), (2, 50))

    def test_uncommitted_return_tail_never_becomes_a_completed_latency(self):
        s = diagnostic()
        tail = json.dumps(s.rows[5]).encode("ascii")
        s.rows = s.rows[:5]
        observed = s.observed(tail)
        got = timing.summarize(observed, s.context, sample(s.context, 160))
        self.assertEqual(got.source_status, "partial-tail")
        self.assertEqual(got.completed_requests, 1)
        self.assertEqual(got.pending_wait_lower_bound_ns, 60)
        self.assertEqual(got.raw_sidecar_sha256, observed.raw_sha256)

    def test_not_started_and_completed_zero_duration_calls_are_distinct(self):
        c = context()
        empty = progress.observe(io.BytesIO(), c)
        missing = timing.summarize(empty, c)
        self.assertEqual(missing.completed_requests, 0)
        self.assertIsNone(missing.completed_elapsed_ns_mean)
        self.assertIsNone(missing.completed_elapsed_ns_maximum)
        s = diagnostic()
        for row in s.rows:
            row["monotonic_ns"] = 0
        zero = timing.summarize(s.observed(), s.context)
        self.assertEqual(zero.completed_requests, 2)
        self.assertEqual(zero.completed_elapsed_ns_mean, 0)
        self.assertEqual(zero.completed_elapsed_ns_maximum, 0)

    def test_zero_request_games_do_not_supply_latency_samples_or_results(self):
        c = context("training")
        s = Sidecar(c).event("run_started", 0)
        for game in range(1, 13):
            s.event("game_started", game * 2, game_index=game)
            s.event("game_finished", game * 2 + 1, game_index=game, request_count=0)
        s.event("run_finished", 26, game_count=12, request_count=0)
        got = timing.summarize(s.observed(), c)
        self.assertTrue(got.sidecar_ended)
        self.assertEqual(got.completed_requests, 0)
        self.assertIsNone(got.completed_elapsed_ns_mean)
        self.assertFalse(got.strength_accepted or got.next_slot_authorized)

    def test_cross_game_pending_identity_is_preserved_without_combining_clocks(self):
        c = context("training")
        s = Sidecar(c).event("run_started", 0).event("game_started", 1, game_index=1)
        s.call("request_started", 1, 10).call("request_returned", 1, 25)
        s.event("game_finished", 26, game_index=1, request_count=1)
        s.event("game_started", 30, game_index=2).call("request_started", 1, 40, game=2)
        got = timing.summarize(s.observed(), c, sample(c, 60))
        self.assertEqual((got.completed_requests, got.completed_elapsed_ns_total), (1, 15))
        self.assertEqual((got.pending.game_index, got.pending.request_index, got.pending_wait_lower_bound_ns), (2, 1, 20))

    def test_inconsistent_unpaired_counts_and_unknown_pending_identity_rejected(self):
        s = diagnostic()
        good = s.observed()
        for changed in (replace(good, requests_started=3), replace(good, elapsed_ns_total=1),
                        replace(good, maximum_elapsed_ns=20), replace(good, requests_returned=True),
                        replace(good, games_finished=0), replace(good, status="unknown")):
            with self.subTest(changed=changed), self.assertRaises(timing.TimingError):
                timing.summarize(changed, s.context)
        s.rows = s.rows[:5]
        pending = s.observed()
        for changed in (replace(pending, pending=None),
                        replace(pending, pending=replace(pending.pending, game_index=2)),
                        replace(pending, pending=replace(pending.pending, call_phase="play")),
                        replace(pending, pending=replace(pending.pending, started_ns=True))):
            with self.subTest(changed=changed), self.assertRaises(timing.TimingError):
                timing.summarize(changed, s.context)

    def test_inputs_are_exact_existing_types_and_output_is_immutable(self):
        s = diagnostic()
        for observed, c in (({}, s.context), (s.observed(), {})):
            with self.assertRaises(timing.TimingError):
                timing.summarize(observed, c)
        got = timing.summarize(s.observed(), s.context)
        with self.assertRaises(FrozenInstanceError):
            got.pending_wait_lower_bound_ns = 10

    def test_maximum_clock_wait_is_exact_without_float_overflow(self):
        s = Sidecar().event("run_started", 0).event("game_started", 0, game_index=1)
        s.call("request_started", 1, 0)
        got = timing.summarize(s.observed(), s.context, sample(s.context, progress.MAX_CLOCK_NS))
        self.assertEqual(got.pending_wait_lower_bound_ns, progress.MAX_CLOCK_NS)
        self.assertEqual(got.completed_requests, 0)
        self.assertIsNone(got.completed_elapsed_ns_mean)


if __name__ == "__main__":
    unittest.main()
