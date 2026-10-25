"""Toy sidecars only. These tests execute no engine, device, protocol registration or slot."""

import copy
from dataclasses import FrozenInstanceError, replace
import hashlib
import io
import json
import unittest

import calibration_v2_progress as p


def context(purpose="diagnostic", maximum=None):
    return p.ProgressContext("calibration-budget-v2-" + purpose + "-contract-test", "a" * 64,
        purpose, {"diagnostic": "diagnostic", "training": "M0-hard", "holdout": "easy-medium"}[purpose],
        "toy-run-1", "b" * 64,
        {"diagnostic": 1, "training": 12, "holdout": 96}[purpose],
        maximum if maximum is not None else (2 if purpose == "diagnostic" else 600))


def records(c, calls=2):
    seq = 0

    def event(kind, **extra):
        nonlocal seq
        result = dict(schema=p.SCHEMA, event=kind, protocol_id=c.protocol_id,
            protocol_sha256=c.protocol_sha256, purpose=c.purpose, pair_id=c.pair_id,
            run_id=c.run_id, runtime_identity=c.runtime_identity, seq=seq, monotonic_ns=seq * 10)
        result.update(extra)
        seq += 1
        return result

    yield event("run_started")
    for game in range(1, c.expected_games + 1):
        yield event("game_started", game_index=game)
        for index in range(1, calls + 1):
            location = dict(game_index=game, request_index=index,
                ply=0 if c.purpose == "diagnostic" else index,
                call_phase=("cold" if index == 1 else "warm") if c.purpose == "diagnostic" else "play")
            yield event("request_started", **location)
            yield event("request_returned", **location)
        yield event("game_finished", game_index=game, request_count=calls)
    yield event("run_finished", game_count=c.expected_games, request_count=c.expected_games * calls)


def line(record):
    return (json.dumps(record, separators=(",", ":"), allow_nan=False) + "\n").encode("ascii")


def observe(items, c=None, **kwargs):
    return p.observe(io.BytesIO(b"".join(line(item) for item in items)), c or context(), **kwargs)


class LineFeed:
    """Produces the full largest supported stream without buffering all 115394 records."""
    def __init__(self, items):
        self.items = iter(items)
        self.reads = 0
        self.maximum_requested = 0

    def readline(self, maximum):
        self.reads += 1
        self.maximum_requested = max(self.maximum_requested, maximum)
        return next(self.items, b"")


class ProgressTests(unittest.TestCase):
    def setUp(self):
        self.c = context()
        self.events = list(records(self.c))

    def rejected(self, events, c=None):
        with self.assertRaises(p.ProgressError):
            observe(events, c or self.c)

    def test_cold_warm_same_position_observation_never_authorizes_results(self):
        raw = b"".join(map(line, self.events))
        got = p.observe(io.BytesIO(raw), self.c)
        self.assertEqual(got.status, "sidecar-ended")
        self.assertTrue(got.sidecar_ended)
        self.assertEqual((got.games_started, got.games_finished), (1, 1))
        self.assertEqual((got.requests_started, got.requests_returned), (2, 2))
        self.assertEqual((got.elapsed_ns_total, got.maximum_elapsed_ns), (20, 10))
        self.assertEqual(got.raw_sha256, hashlib.sha256(raw).hexdigest())
        self.assertEqual(got.bytes_observed, len(raw))
        self.assertFalse(got.search_authorized or got.strength_accepted or got.next_slot_authorized)
        with self.assertRaises(FrozenInstanceError):
            got.sidecar_ended = False

    def test_training_resets_request_and_ply_only_at_declared_game_boundaries(self):
        c = context("training", 3)
        got = observe(records(c, 3), c)
        self.assertEqual((got.games_finished, got.requests_returned), (12, 36))
        self.assertEqual(got.maximum_elapsed_ns, 10)
        for job in ("M0-hard", "M1-hard", "E0-M0", "E1-M0", "E1-M1", "hard-master"):
            self.assertTrue(observe(records(replace(c, pair_id=job), 3), replace(c, pair_id=job)).sidecar_ended)
        with self.assertRaises(p.ProgressError):
            replace(c, pair_id="easy-medium")

    def test_largest_holdout_stream_fits_real_bounds_and_reads_one_line_at_a_time(self):
        c = context("holdout")
        stream = LineFeed(line(item) for item in records(c, 600))
        got = p.observe(stream, c)
        self.assertEqual(got.records, 115394)
        self.assertEqual(got.records, p.MAX_RECORDS)
        self.assertEqual(got.requests_returned, 57600)
        self.assertEqual(got.games_finished, 96)
        self.assertEqual(stream.maximum_requested, p.MAX_LINE_BYTES + 1)
        self.assertEqual(stream.reads, got.records + 1)
        self.assertLess(got.bytes_observed, p.MAX_BYTES)
        self.assertGreater(got.bytes_observed, 8 * 1024 * 1024)

    def test_terminal_without_search_is_observable_but_not_a_legal_game_proof(self):
        c = context("training", 2)
        got = observe(records(c, 0), c)
        self.assertTrue(got.sidecar_ended)
        self.assertEqual(got.requests_returned, 0)
        self.assertFalse(got.next_slot_authorized)

    def test_context_is_external_and_each_identity_field_is_bound(self):
        for key in ("protocol_id", "protocol_sha256", "purpose", "pair_id", "run_id", "runtime_identity", "schema"):
            events = copy.deepcopy(self.events)
            events[2][key] = "spoofed"
            with self.subTest(key=key):
                self.rejected(events)
        with self.assertRaises(p.ProgressError):
            p.observe(io.BytesIO(line(self.events[0])), dict(self.c.__dict__))

    def test_context_rejects_legacy_admission_and_invalid_or_mutable_values(self):
        changes = ({"protocol_id": "calibration-budget-v2-20261022-admission"},
            {"protocol_id": "calibration-budget-v2-training-contract-test"},
            {"protocol_sha256": "A" * 64}, {"runtime_identity": "b" * 63},
            {"run_id": "../old-slot"}, {"purpose": "old"}, {"expected_games": True},
            {"maximum_requests_per_game": False}, {"maximum_requests_per_game": 601},
            {"expected_games": 2}, {"pair_id": []})
        for change in changes:
            with self.subTest(change=change), self.assertRaises(p.ProgressError):
                replace(self.c, **change)
        for change in ({"pair_id": []}, {"pair_id": "diagnostic"}, {"expected_games": 96}):
            with self.subTest(change=change), self.assertRaises(p.ProgressError):
                replace(context("training"), **change)

    def test_complete_records_reject_duplicate_keys_nonfinite_and_nonobject_json(self):
        raws = (b'{"event":"run_started","event":"run_started"}\n',
            b'{"x":NaN}\n', b'{"x":Infinity}\n', b'[]\n', b'null\n', b'{broken}\n', b'\xff\n')
        for raw in raws:
            with self.subTest(raw=raw), self.assertRaises(p.ProgressError):
                p.observe(io.BytesIO(raw), self.c)

    def test_unknown_event_fields_nested_payloads_and_blank_lines_rejected(self):
        for extra in ({"search_authorized": False}, {"result": "win"}, {"elapsed_ns": 10}, {"metadata": {}}):
            changed = copy.deepcopy(self.events)
            changed[2].update(extra)
            self.rejected(changed)
        changed = copy.deepcopy(self.events)
        changed[0]["event"] = "go_started"
        self.rejected(changed)
        with self.assertRaises(p.ProgressError):
            p.observe(io.BytesIO(b"\n"), self.c)

    def test_booleans_floats_negative_and_oversized_integers_are_not_counters(self):
        for key, position in (("seq", 2), ("monotonic_ns", 2), ("game_index", 2),
                              ("request_index", 2), ("ply", 2), ("request_count", 6),
                              ("game_count", 7)):
            for value in (True, 1.0, -1, 1 << 80):
                changed = copy.deepcopy(self.events)
                changed[position][key] = value
                with self.subTest(key=key, value=value):
                    self.rejected(changed)

    def test_clock_may_be_equal_but_not_backwards(self):
        for event in self.events:
            event["monotonic_ns"] = 0
        self.assertEqual(observe(self.events).elapsed_ns_total, 0)
        self.events[3]["monotonic_ns"] = 9
        self.events[4]["monotonic_ns"] = 8
        self.rejected(self.events)

    def test_sequence_duplicate_gap_and_reorder_are_rejected(self):
        for value in (1, 3):
            changed = copy.deepcopy(self.events)
            changed[2]["seq"] = value
            self.rejected(changed)
        self.rejected(self.events[1:])
        changed = copy.deepcopy(self.events)
        changed[1], changed[2] = changed[2], changed[1]
        self.rejected(changed)

    def test_missing_return_is_pending_but_not_fabricated_duration_or_completion(self):
        got = observe(self.events[:3])
        self.assertEqual(got.status, "pending-request")
        self.assertEqual((got.requests_started, got.requests_returned), (1, 0))
        self.assertEqual(got.elapsed_ns_total, 0)
        self.assertEqual((got.pending.game_index, got.pending.request_index, got.pending.ply), (1, 1, 0))
        self.assertFalse(got.sidecar_ended or got.next_slot_authorized)

    def test_eof_at_each_record_boundary_retains_only_committed_observations(self):
        for count in range(len(self.events)):
            got = observe(self.events[:count])
            self.assertEqual(got.records, count)
            self.assertFalse(got.sidecar_ended)
        self.assertEqual(observe([]).status, "not-started")
        self.assertEqual(observe(self.events[:4]).status, "partial-observation")

    def test_unterminated_even_parseable_end_record_is_not_consumed(self):
        committed = b"".join(line(item) for item in self.events[:-1])
        tail = line(self.events[-1])[:-1]
        got = p.observe(io.BytesIO(committed + tail), self.c)
        self.assertEqual(got.status, "partial-tail")
        self.assertEqual(got.records, len(self.events) - 1)
        self.assertFalse(got.sidecar_ended)
        self.assertEqual(got.trailing_bytes, len(tail))
        self.assertEqual(got.trailing_sha256, hashlib.sha256(tail).hexdigest())
        self.assertEqual(got.raw_sha256, hashlib.sha256(committed + tail).hexdigest())

    def test_unparseable_tail_is_preserved_without_repairing_or_inventing_records(self):
        prefix = line(self.events[0])
        for tail in (b'{"event":', b'\xff', b'{"x":NaN}'):
            got = p.observe(io.BytesIO(prefix + tail), self.c)
            self.assertEqual(got.records, 1)
            self.assertEqual(got.status, "partial-tail")
            self.assertEqual(got.trailing_bytes, len(tail))

    def test_return_without_start_overlap_wrong_phase_and_request_skip_rejected(self):
        changes = ((2, {"event": "request_returned"}), (3, {"event": "request_started"}),
            (4, {"request_index": 1, "call_phase": "cold"}), (2, {"request_index": 2, "call_phase": "warm"}),
            (3, {"request_index": 2, "call_phase": "warm"}), (2, {"ply": 1}),
            (4, {"call_phase": "cold"}), (2, {"call_phase": "go"}))
        for position, update in changes:
            events = copy.deepcopy(self.events)
            events[position].update(update)
            with self.subTest(position=position, update=update):
                self.rejected(events)

    def test_game_mismatch_early_finish_wrong_counts_and_repeated_end_rejected(self):
        for position, update in ((1, {"game_index": 2}), (3, {"game_index": 2}),
            (3, {"event": "game_finished", "request_count": 0}),
            (6, {"request_count": 1}), (7, {"game_count": 0}), (7, {"request_count": 1})):
            changed = copy.deepcopy(self.events)
            changed[position].update(update)
            self.rejected(changed)
        self.rejected(self.events + [self.events[-1]])
        self.rejected([self.events[0], self.events[-1]])

    def test_cross_game_jump_reuse_and_unpaired_return_rejected(self):
        c = context("training", 1)
        original = list(records(c, 1))
        for position, update in ((5, {"game_index": 3}), (5, {"game_index": 1}),
                                  (6, {"ply": 2}), (6, {"request_index": 2}),
                                  (7, {"game_index": 1})):
            changed = copy.deepcopy(original)
            changed[position].update(update)
            self.rejected(changed, c)

    def test_semantically_invalid_transitions_with_valid_shape_sequence_and_clock(self):
        def resequence(items):
            changed = copy.deepcopy(items)
            for index, event in enumerate(changed):
                event.update(seq=index, monotonic_ns=index * 10)
            return changed

        # Remove the return or inject boundaries without relying on a sequence/shape error.
        for items in (self.events[:3] + self.events[4:],
                      self.events[:3] + [self.events[6]],
                      self.events[:3] + [self.events[1]],
                      self.events[:4] + [self.events[6]],
                      self.events[:2] + [self.events[0]],
                      [self.events[0], self.events[2]],
                      self.events[:7] + [self.events[1]]):
            self.rejected(resequence(items))
        c = context("training", 1)
        training = list(records(c, 1))
        # Game 1 cannot end while its request is in flight or skip to game 2.
        self.rejected(resequence(training[:3] + training[4:]), c)
        self.rejected(resequence(training[:3] + training[5:]), c)

    def test_ceilings_and_native_lf_framing_fail_closed(self):
        for raw in (b"x" * (p.MAX_LINE_BYTES + 1), line(self.events[0]).replace(b"\n", b"\r\n")):
            with self.assertRaises(p.ProgressError):
                p.observe(io.BytesIO(raw), self.c)
        with self.assertRaises(p.ProgressError):
            observe(self.events, maximum_bytes=10)
        for ceiling in (True, 0, p.MAX_BYTES + 1):
            with self.assertRaises(p.ProgressError):
                observe(self.events, maximum_bytes=ceiling)
        with self.assertRaises(p.ProgressError):
            p.observe(io.StringIO("not binary"), self.c)

    def test_extra_bytes_after_finished_and_false_readline_boundaries_rejected(self):
        raw = b"".join(map(line, self.events))
        for extra in (b"\n", b"garbage", line(self.events[0])):
            with self.assertRaises(p.ProgressError):
                p.observe(io.BytesIO(raw + extra), self.c)
        with self.assertRaises(p.ProgressError):
            p.observe(LineFeed([b"{partial", b"more"]), self.c)


if __name__ == "__main__":
    unittest.main()
