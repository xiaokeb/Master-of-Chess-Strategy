"""Synthetic JSONL latency evidence only; no engine calls or process creation."""

from dataclasses import FrozenInstanceError, replace
from fractions import Fraction
import hashlib
import json
import unittest

import calibration_v2_latency as latency
import calibration_v2_progress as progress


class Stream:
    def __init__(self, purpose="diagnostic"):
        self.context = progress.ProgressContext("calibration-budget-v2-" + purpose + "-latency-test",
            "a" * 64, purpose, "diagnostic" if purpose == "diagnostic" else "M0-hard",
            "synthetic-latency", "b" * 64, 1 if purpose == "diagnostic" else 12,
            2 if purpose == "diagnostic" else 600)
        self.rows = []
        self.clock = 0
        self.event("run_started")

    def event(self, name, **extra):
        context = self.context
        self.rows.append(dict(schema=progress.SCHEMA, event=name, protocol_id=context.protocol_id,
            protocol_sha256=context.protocol_sha256, purpose=context.purpose, pair_id=context.pair_id,
            run_id=context.run_id, runtime_identity=context.runtime_identity, seq=len(self.rows),
            monotonic_ns=self.clock, **extra))

    def request(self, index, *, game=1, duration=None):
        diagnostic = self.context.purpose == "diagnostic"
        args = dict(game_index=game, request_index=index, ply=0 if diagnostic else index,
            call_phase=("cold" if index == 1 else "warm") if diagnostic else "play")
        self.event("request_started", **args)
        if duration is not None:
            self.clock += duration
            self.event("request_returned", **args)

    def raw(self, tail=b""):
        return b"".join((json.dumps(row, separators=(",", ":")) + "\n").encode()
            for row in self.rows) + tail

    def report(self, tail=b""):
        return latency.analyze(self.raw(tail), self.context)


def diagnostic(first=7, second=12):
    stream = Stream()
    stream.event("game_started", game_index=1)
    stream.request(1, duration=first)
    stream.request(2, duration=second)
    stream.event("game_finished", game_index=1, request_count=2)
    stream.event("run_finished", game_count=1, request_count=2)
    return stream


class LatencyTests(unittest.TestCase):
    def test_real_raw_validation_and_exact_diagnostic_statistics(self):
        stream = diagnostic()
        report = stream.report()
        self.assertEqual(report.paired, latency.LatencyStatistics(2, 19, Fraction(19, 2), 12, 12, 0))
        self.assertEqual((report.cold.mean_ns, report.warm.mean_ns), (7, 12))
        self.assertEqual(report.play.count, 0)
        self.assertEqual(report.observation.raw_sha256, hashlib.sha256(stream.raw()).hexdigest())
        self.assertTrue(report.sidecar_distribution_complete)
        self.assertEqual(report.samples[0].elapsed_ns, 7)

    def test_nearest_rank_uses_19th_of_20_not_interpolation(self):
        stream = Stream("training")
        for game in range(1, 13):
            stream.event("game_started", game_index=game)
            for index, duration in enumerate(range(1, 21) if game == 1 else (), 1):
                stream.request(index, game=game, duration=duration)
            stream.event("game_finished", game_index=game, request_count=20 if game == 1 else 0)
        stream.event("run_finished", game_count=12, request_count=20)
        report = stream.report()
        self.assertEqual(report.play.p95_ns, 19)
        self.assertEqual(report.play.total_ns, 210)
        self.assertEqual(report.play.mean_ns, Fraction(21, 2))
        self.assertEqual(report.cold.count + report.warm.count, 0)
        self.assertEqual(len(report.samples), 20)

    def test_strictly_above_target_keeps_exact_boundary_and_no_timeout(self):
        report = diagnostic(latency.RESPONSE_TARGET_NS, latency.RESPONSE_TARGET_NS + 1).report()
        self.assertEqual(report.paired.above_response_target, 1)
        self.assertEqual(report.paired.maximum_ns, latency.RESPONSE_TARGET_NS + 1)
        self.assertTrue(report.sidecar_distribution_complete)
        self.assertFalse(report.search_authorized or report.strength_accepted or report.next_slot_authorized)

    def test_pending_is_excluded_and_has_no_fabricated_duration(self):
        stream = Stream()
        stream.event("game_started", game_index=1)
        stream.request(1, duration=5)
        stream.request(2)
        report = stream.report()
        self.assertEqual((report.paired.count, report.paired.total_ns, report.paired.p95_ns), (1, 5, 5))
        self.assertEqual(report.pending.request_index, 2)
        self.assertEqual(report.warm.count, 0)
        self.assertFalse(report.sidecar_distribution_complete)

    def test_parseable_uncommitted_return_remains_pending(self):
        stream = Stream()
        stream.event("game_started", game_index=1)
        stream.request(1)
        stream.clock = 100
        stream.event("request_returned", game_index=1, request_index=1, ply=0, call_phase="cold")
        raw = stream.raw()[:-1]
        report = latency.analyze(raw, stream.context)
        self.assertEqual(report.paired.count, 0)
        self.assertIsNone(report.paired.p95_ns)
        self.assertEqual(report.pending.request_index, 1)
        self.assertEqual(report.observation.status, "partial-tail")

    def test_missing_samples_distinguished_from_genuine_zero(self):
        empty = Stream().report().paired
        self.assertEqual((empty.count, empty.total_ns), (0, 0))
        self.assertIsNone(empty.mean_ns)
        self.assertIsNone(empty.p95_ns)
        self.assertIsNone(empty.maximum_ns)
        zero = diagnostic(0, 0).report().paired
        self.assertEqual((zero.count, zero.mean_ns, zero.p95_ns, zero.maximum_ns), (2, 0, 0, 0))

    def test_empty_and_bad_tail_have_no_completed_distribution(self):
        stream = Stream()
        empty = latency.analyze(b"", stream.context)
        self.assertEqual(empty.observation.status, "not-started")
        report = stream.report(b'{"unfinished":')
        self.assertEqual(report.observation.status, "partial-tail")
        self.assertFalse(report.sidecar_distribution_complete)
        self.assertEqual(report.paired.count, 0)

    def test_external_identity_mismatch_is_not_reconstructed_from_stream(self):
        stream = diagnostic()
        for context in (replace(stream.context, run_id="other"),
                        replace(stream.context, runtime_identity="c" * 64)):
            with self.assertRaises(progress.ProgressError):
                latency.analyze(stream.raw(), context)

    def test_malformed_committed_records_never_return_partial_metrics(self):
        stream = diagnostic()
        raw = stream.raw()
        for broken in (raw.replace(b'"seq":2', b'"seq":true'),
                       raw.replace(b'"seq":2', b'"seq":2,"seq":2'),
                       raw.replace(b'"monotonic_ns":7', b'"monotonic_ns":NaN'),
                       raw.replace(b'"event":"request_returned"', b'"event":"unknown"', 1),
                       raw.replace(b'\n', b'\r\n')):
            with self.subTest(raw=broken[:70]), self.assertRaises(progress.ProgressError):
                latency.analyze(broken, stream.context)

    def test_invalid_pair_and_backward_clock_are_rejected_before_projection(self):
        stream = diagnostic()
        stream.rows[3]["request_index"] = 2
        with self.assertRaises(progress.ProgressError):
            stream.report()
        stream = diagnostic()
        stream.rows[5]["monotonic_ns"] = 6
        with self.assertRaises(progress.ProgressError):
            stream.report()

    def test_mutable_or_stream_input_is_refused_without_reading(self):
        for raw in (bytearray(), memoryview(b""), None, ""):
            with self.assertRaises(TypeError):
                latency.analyze(raw, Stream().context)
        with self.assertRaises(TypeError):
            latency.analyze(b"", object())

    def test_untrusted_complete_declaration_with_data_after_end_rejected(self):
        stream = diagnostic()
        with self.assertRaises(progress.ProgressError):
            stream.report(b"x")

    def test_extreme_integer_duration_stays_exact(self):
        report = diagnostic(progress.MAX_CLOCK_NS, 0).report()
        self.assertEqual(report.paired.total_ns, progress.MAX_CLOCK_NS)
        self.assertEqual(report.paired.mean_ns, Fraction(progress.MAX_CLOCK_NS, 2))
        self.assertEqual(report.paired.p95_ns, progress.MAX_CLOCK_NS)

    def test_reports_samples_and_metrics_are_immutable(self):
        report = diagnostic().report()
        for target, name, value in ((report, "sidecar_distribution_complete", False),
                                     (report.samples[0], "returned_ns", -1),
                                     (report.paired, "p95_ns", 0)):
            with self.assertRaises(FrozenInstanceError):
                setattr(target, name, value)


if __name__ == "__main__":
    unittest.main()
