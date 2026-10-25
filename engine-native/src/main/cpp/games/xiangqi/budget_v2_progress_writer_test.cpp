// Toy sidecars only: no engine, NNUE, registration, slot or search invocation.
#include "budget_v2_progress_writer.hpp"

#include <algorithm>
#include <cstdlib>
#include <iostream>
#include <limits>
#include <locale>
#include <new>
#include <sstream>
#include <stdexcept>
#include <streambuf>
#include <type_traits>
#include <vector>

namespace {
bool fail_next_allocation = false;
unsigned checks = 0;
void require(bool value, const char* message) {
    ++checks;
    if (!value) throw std::runtime_error(message);
}
}  // namespace

// Test-only deterministic allocation failure during an event's line assembly.
void* operator new(std::size_t size) {
    if (fail_next_allocation) {
        fail_next_allocation = false;
        throw std::bad_alloc();
    }
    if (auto* memory = std::malloc(size == 0 ? 1 : size)) return memory;
    throw std::bad_alloc();
}
void* operator new[](std::size_t size) { return ::operator new(size); }
void operator delete(void* memory) noexcept { std::free(memory); }
void operator delete[](void* memory) noexcept { std::free(memory); }
void operator delete(void* memory, std::size_t) noexcept { std::free(memory); }
void operator delete[](void* memory, std::size_t) noexcept { std::free(memory); }

namespace {
using namespace mocs::engine::budget_v2;

ProgressContext context(std::string purpose = "diagnostic") {
    const auto games = purpose == "diagnostic" ? 1 : purpose == "training" ? 12 : 96;
    const auto pair = purpose == "diagnostic" ? "diagnostic" : purpose == "training" ? "M0-hard" : "easy-medium";
    return {"calibration-budget-v2-" + purpose + "-writer-fixture", std::string(64, 'a'),
        purpose, pair, "toy-run-1", std::string(64, 'b'), games, purpose == "diagnostic" ? 2 : 600};
}

struct CountingSink : std::streambuf {
    std::size_t bytes{0};
    std::size_t lines{0};
    std::size_t flushes{0};
    std::size_t maximum_line{0};
    bool short_write{false};
    bool fail_flush{false};
    bool throw_write{false};
    std::streamsize xsputn(const char* data, std::streamsize count) override {
        if (throw_write) throw std::runtime_error("test sink write failure");
        const auto actual = short_write ? count - 1 : count;
        bytes += static_cast<std::size_t>(actual);
        maximum_line = std::max(maximum_line, static_cast<std::size_t>(actual));
        for (std::streamsize i = 0; i < actual; ++i) if (data[i] == '\n') ++lines;
        return actual;
    }
    int sync() override {
        ++flushes;
        return fail_flush ? -1 : 0;
    }
};

void write_run(ProgressWriter& writer, const ProgressContext& c, std::int64_t calls) {
    std::int64_t clock = 0;
    require(writer.run_started(clock++), "run begins");
    for (std::int64_t game = 1; game <= c.expected_games; ++game) {
        require(writer.game_started(game, clock++), "ordered game begins");
        for (std::int64_t index = 1; index <= calls; ++index) {
            const auto ply = c.purpose == "diagnostic" ? 0 : index;
            const auto phase = c.purpose == "diagnostic" ? (index == 1 ? "cold" : "warm") : "play";
            require(writer.request_started(game, index, ply, phase, clock++), "serial request begins");
            require(writer.request_returned(game, index, ply, phase, clock++), "matching request returns");
        }
        require(writer.game_finished(game, calls, clock++), "ordered game ends");
    }
    require(writer.run_finished(c.expected_games, c.expected_games * calls, clock), "sidecar ends");
}

void complete_diagnostic_has_exact_framing_and_flushes() {
    CountingSink buffer;
    std::ostream stream(&buffer);
    auto c = context();
    ProgressWriter writer(c, stream);
    write_run(writer, c, 2);
    require(writer.ok() && writer.sidecar_ended(), "coherent diagnostic observation ended");
    require(writer.records_written() == 8 && buffer.lines == 8, "exactly eight LF records");
    require(buffer.flushes == 8, "every accepted record flushed");
    require(buffer.bytes == writer.bytes_written(), "only successful bytes counted");
    const auto bytes = buffer.bytes;
    require(!writer.run_finished(1, 2, 10), "duplicate end rejected");
    require(writer.error() == ProgressWriteError::invalid_state && buffer.bytes == bytes,
        "nothing appended after end");
}

void context_is_copied_and_ascii_fields_are_exact() {
    auto c = context();
    std::ostringstream stream;
    ProgressWriter writer(c, stream);
    c.protocol_id = "changed"; c.protocol_sha256 = "changed"; c.pair_id = "changed";
    require(writer.run_started(0), "writer retains original context");
    const auto line = stream.str();
    require(line.find("\"protocol_id\":\"calibration-budget-v2-diagnostic-writer-fixture\"") != std::string::npos,
        "protocol fixed before producer starts");
    require(line.find("changed") == std::string::npos, "external mutations not serialized");
    require(line.find("\"seq\":0,\"monotonic_ns\":0") != std::string::npos, "canonical integer fields");
    require(std::count(line.begin(), line.end(), '\n') == 1 && line.back() == '\n'
        && line.find('\r') == std::string::npos, "one native LF, no CR");
    require(line.find("search_authorized") == std::string::npos && line.find("result") == std::string::npos,
        "no extra result or authorization fields");
}

void invalid_contexts_and_capacities_emit_nothing() {
    std::vector<ProgressContext> bad;
    auto add = [&bad](auto change) { auto c = context(); change(c); bad.push_back(c); };
    add([](auto& c) { c.protocol_id = "calibration-budget-v2-20261022-admission"; });
    add([](auto& c) { c.protocol_id = "calibration-budget-v2-training-writer-fixture"; });
    add([](auto& c) { c.protocol_id = "calibration-budget-v2-diagnostic-"; });
    add([](auto& c) { c.protocol_id += std::string(65, 'a'); });
    add([](auto& c) { c.protocol_id += "_"; });
    add([](auto& c) { c.protocol_sha256[0] = 'A'; });
    add([](auto& c) { c.runtime_identity.pop_back(); });
    add([](auto& c) { c.run_id = "../old-slot"; });
    add([](auto& c) { c.run_id = "bad\"injection"; });
    add([](auto& c) { c.run_id = "bad\nrecord"; });
    add([](auto& c) { c.run_id = "\xff"; });
    add([](auto& c) { c.purpose = "old"; });
    add([](auto& c) { c.expected_games = 2; });
    add([](auto& c) { c.maximum_requests_per_game = 1; });
    add([](auto& c) { c.pair_id = "easy-medium"; });
    for (const auto& c : bad) {
        std::ostringstream stream;
        ProgressWriter writer(c, stream);
        require(writer.error() == ProgressWriteError::invalid_context, "bad external context rejected");
        require(!writer.run_started(0) && stream.str().empty(), "bad context cannot write");
    }
    for (const auto ceiling : {std::size_t{0}, progress_maximum_bytes + 1}) {
        std::ostringstream stream;
        ProgressWriter writer(context(), stream, ceiling);
        require(writer.error() == ProgressWriteError::invalid_capacity
            && !writer.run_started(0) && stream.str().empty(), "invalid byte ceiling rejected");
    }
    for (const auto pair : {"M0-hard", "M1-hard", "E0-M0", "E1-M0", "E1-M1", "hard-master"}) {
        auto c = context("training"); c.pair_id = pair;
        std::ostringstream stream;
        ProgressWriter writer(c, stream);
        require(writer.run_started(0), "all six and only planned training jobs allowed");
    }
    auto c = context("training"); c.pair_id = "easy-medium";
    std::ostringstream stream;
    ProgressWriter writer(c, stream);
    require(!writer.ok(), "holdout pair not a training identity");
}

void missing_request_return_never_finishes_or_fabricates_a_return() {
    std::ostringstream stream;
    {
        ProgressWriter writer(context(), stream);
        require(writer.run_started(0) && writer.game_started(1, 1)
            && writer.request_started(1, 1, 0, "cold", 2), "request visibly in flight");
    }
    const auto bytes = stream.str();
    require(bytes.find("request_returned") == std::string::npos
        && bytes.find("run_finished") == std::string::npos, "destructor invents no completion");
    require(std::count(bytes.begin(), bytes.end(), '\n') == 3,
        "only three explicit records expected");
}

void invalid_transitions_lock_the_first_error_without_appending() {
    for (int bad = 0; bad < 11; ++bad) {
        std::ostringstream stream;
        ProgressWriter writer(context(), stream);
        require(writer.run_started(10) && writer.game_started(1, 10), "valid equal timestamps");
        const auto before = stream.str();
        bool accepted = true;
        switch (bad) {
        case 0: accepted = writer.run_started(10); break;
        case 1: accepted = writer.game_started(1, 10); break;
        case 2: accepted = writer.request_returned(1, 1, 0, "cold", 10); break;
        case 3: accepted = writer.request_started(2, 1, 0, "cold", 10); break;
        case 4: accepted = writer.request_started(1, 2, 0, "warm", 10); break;
        case 5: accepted = writer.request_started(1, 1, 1, "cold", 10); break;
        case 6: accepted = writer.request_started(1, 1, 0, "warm", 10); break;
        case 7: accepted = writer.game_finished(1, 0, 10); break;
        case 8: accepted = writer.run_finished(1, 0, 10); break;
        case 9: accepted = writer.request_started(1, -1, 0, "cold", 10); break;
        case 10: accepted = writer.request_started(1, 3, 0, "warm", 10); break;
        }
        require(!accepted && writer.error() == ProgressWriteError::invalid_state, "bad transition rejected");
        require(!writer.request_started(1, 1, 0, "cold", 9), "failed writer stays closed");
        require(writer.error() == ProgressWriteError::invalid_state && stream.str() == before,
            "later clock error neither replaces first error nor emits");
    }
    std::ostringstream stream;
    ProgressWriter writer(context(), stream);
    require(!writer.game_started(1, 0) && stream.str().empty(), "run_started must be first");
}

void outstanding_calls_must_match_and_finish_counts_are_exact() {
    for (int bad = 0; bad < 4; ++bad) {
        std::ostringstream stream;
        ProgressWriter writer(context(), stream);
        require(writer.run_started(0) && writer.game_started(1, 1)
            && writer.request_started(1, 1, 0, "cold", 2), "request opened");
        const auto before = stream.str();
        const bool accepted = bad == 0 ? writer.request_started(1, 1, 0, "cold", 3)
            : bad == 1 ? writer.request_returned(1, 2, 0, "warm", 3)
            : bad == 2 ? writer.game_finished(1, 0, 3) : writer.run_finished(1, 0, 3);
        require(!accepted && stream.str() == before, "unpaired or overlapping transition rejects before writing");
    }
    std::ostringstream stream;
    ProgressWriter writer(context(), stream);
    require(writer.run_started(0) && writer.game_started(1, 1)
        && writer.request_started(1, 1, 0, "cold", 2) && writer.request_returned(1, 1, 0, "cold", 3)
        && writer.request_started(1, 2, 0, "warm", 4) && writer.request_returned(1, 2, 0, "warm", 5),
        "both diagnostic returns committed");
    const auto before = stream.str();
    require(!writer.game_finished(1, 1, 6) && stream.str() == before, "wrong request count rejected");
}

void clock_and_capacity_fail_before_output() {
    for (const auto clock : {std::int64_t{-1}, std::int64_t{8}}) {
        std::ostringstream stream;
        ProgressWriter writer(context(), stream);
        require(writer.run_started(9), "clock baseline committed");
        const auto before = stream.str();
        require(!writer.game_started(1, clock) && writer.error() == ProgressWriteError::invalid_clock
            && stream.str() == before, "invalid clock cannot append");
    }
    std::ostringstream stream;
    ProgressWriter writer(context(), stream, 10);
    require(!writer.run_started(0) && writer.error() == ProgressWriteError::capacity_exceeded,
        "byte limit preflight rejects whole event");
    require(stream.str().empty() && writer.records_written() == 0 && writer.bytes_written() == 0,
        "capacity rejection leaves no partial output or committed state");
    std::ostringstream max_stream;
    ProgressWriter max_writer(context(), max_stream);
    require(max_writer.run_started(std::numeric_limits<std::int64_t>::max())
        && max_writer.game_started(1, std::numeric_limits<std::int64_t>::max()), "maximum equal int64 clock valid");
}

void sink_failures_preserve_first_error_and_never_retry() {
    for (int fault = 0; fault < 4; ++fault) {
        CountingSink buffer;
        buffer.short_write = fault == 0;
        buffer.fail_flush = fault == 1;
        buffer.throw_write = fault >= 2;
        std::ostream stream(&buffer);
        if (fault == 3) stream.exceptions(std::ios::badbit | std::ios::failbit);
        ProgressWriter writer(context(), stream);
        require(!writer.run_started(0) && writer.error() == ProgressWriteError::stream_failed,
            "write/flush/exception failures visible");
        require(writer.records_written() == 0 && writer.bytes_written() == 0,
            "unconfirmed bytes never commit logical state");
        const auto bytes = buffer.bytes, flushes = buffer.flushes;
        stream.clear(); buffer.short_write = buffer.fail_flush = buffer.throw_write = false;
        require(!writer.run_started(0) && buffer.bytes == bytes && buffer.flushes == flushes,
            "even repaired stream cannot implicitly retry a failed writer");
    }
}

void allocation_failure_is_visible_and_permanent() {
    CountingSink buffer;
    std::ostream stream(&buffer);
    ProgressWriter writer(context(), stream);
    fail_next_allocation = true;
    const auto accepted = writer.run_started(0);
    fail_next_allocation = false;
    require(!accepted && writer.error() == ProgressWriteError::allocation_failed,
        "line assembly allocation failure caught by noexcept API");
    require(buffer.bytes == 0 && writer.records_written() == 0, "allocation failure writes nothing");
    require(!writer.run_started(0) && buffer.bytes == 0, "allocation failure cannot be retried silently");
}

void complete_largest_holdout_is_bounded_and_streamed() {
    const auto c = context("holdout");
    CountingSink buffer;
    std::ostream stream(&buffer);
    ProgressWriter writer(c, stream);
    write_run(writer, c, 600);
    require(writer.sidecar_ended() && writer.records_written() == 115394, "full 96x600 sidecar supported");
    require(buffer.lines == 115394 && buffer.flushes == 115394, "all largest-stream records flushed");
    require(buffer.maximum_line <= progress_maximum_line_bytes
        && buffer.bytes <= progress_maximum_bytes, "real serialized bounds respected");
}

void zero_request_terminal_is_observation_only_and_games_reset() {
    const auto c = context("training");
    CountingSink buffer;
    std::ostream stream(&buffer);
    ProgressWriter writer(c, stream);
    write_run(writer, c, 0);
    require(writer.sidecar_ended() && writer.records_written() == 26, "zero-request game observations supported");
    std::ostringstream other;
    ProgressWriter second(c, other);
    require(second.run_started(0) && second.game_started(1, 1)
        && second.request_started(1, 1, 1, "play", 2)
        && second.request_returned(1, 1, 1, "play", 3) && second.game_finished(1, 1, 4)
        && second.game_started(2, 5) && second.request_started(2, 1, 1, "play", 6),
        "request numbering resets only after real game boundary");
    const auto before = other.str();
    require(!second.request_returned(1, 1, 1, "play", 7) && other.str() == before, "prior game cannot supply return");
}

class GroupedNumbers : public std::numpunct<char> {
    char do_thousands_sep() const override { return ','; }
    std::string do_grouping() const override { return "\3"; }
};

void serialization_ignores_stream_locale_and_preserves_int64() {
    std::ostringstream stream;
    stream.imbue(std::locale(std::locale::classic(), new GroupedNumbers));
    ProgressWriter writer(context(), stream);
    require(writer.run_started(1234567890), "locale independent output");
    require(stream.str().find("\"monotonic_ns\":1234567890") != std::string::npos,
        "JSON integer receives no locale grouping");
}

}  // namespace

int main(int argc, char* argv[]) {
    static_assert(!std::is_copy_constructible_v<ProgressWriter> && !std::is_move_constructible_v<ProgressWriter>);
    try {
        if (argc == 2) {
            const std::string mode(argv[1]);
            const auto purpose = mode == "--fixture-diagnostic" ? "diagnostic"
                : mode == "--fixture-training" ? "training"
                : mode == "--fixture-holdout" ? "holdout" : "";
            if (*purpose == '\0') throw std::invalid_argument("unknown fixture option");
            const auto c = context(purpose);
            ProgressWriter writer(c, std::cout);
            write_run(writer, c, c.purpose == "diagnostic" ? 2 : c.purpose == "training" ? 3 : 600);
            return 0;
        }
        if (argc != 1) throw std::invalid_argument("expected no arguments or one fixture option");
        complete_diagnostic_has_exact_framing_and_flushes();
        context_is_copied_and_ascii_fields_are_exact();
        invalid_contexts_and_capacities_emit_nothing();
        missing_request_return_never_finishes_or_fabricates_a_return();
        invalid_transitions_lock_the_first_error_without_appending();
        outstanding_calls_must_match_and_finish_counts_are_exact();
        clock_and_capacity_fail_before_output();
        sink_failures_preserve_first_error_and_never_retry();
        allocation_failure_is_visible_and_permanent();
        complete_largest_holdout_is_bounded_and_streamed();
        zero_request_terminal_is_observation_only_and_games_reset();
        serialization_ignores_stream_locale_and_preserves_int64();
        std::cout << "Progress writer: 12 test groups, " << checks << " checks passed; toy sidecars only.\n";
        return 0;
    } catch (const std::exception& error) {
        fail_next_allocation = false;
        std::cerr << "FAIL progress writer: " << error.what() << '\n';
        return 1;
    }
}
