#include "budget_v2_progress_writer.hpp"

#include <array>
#include <charconv>
#include <new>
#include <stdexcept>

namespace mocs::engine::budget_v2 {
namespace {

bool lower_or_digit(char value) noexcept {
    return (value >= 'a' && value <= 'z') || (value >= '0' && value <= '9');
}

bool token(std::string_view value, bool run_token) noexcept {
    if (value.empty() || value.size() > 64 || !lower_or_digit(value.front())) return false;
    for (const auto cell : value) {
        if (!lower_or_digit(cell) && cell != '-'
            && !(run_token && (cell == '.' || cell == '_'))) return false;
    }
    return true;
}

bool digest(std::string_view value) noexcept {
    if (value.size() != 64) return false;
    for (const auto cell : value) {
        if (!(cell >= '0' && cell <= '9') && !(cell >= 'a' && cell <= 'f')) return false;
    }
    return true;
}

bool valid_context(const ProgressContext& context) noexcept {
    std::string_view prefix;
    std::int64_t expected = 0;
    if (context.purpose == "diagnostic") {
        prefix = "calibration-budget-v2-diagnostic-";
        expected = 1;
        if (context.pair_id != "diagnostic" || context.maximum_requests_per_game != 2) return false;
    } else if (context.purpose == "training") {
        prefix = "calibration-budget-v2-training-";
        expected = 12;
        constexpr std::array<std::string_view, 6> jobs{
            "M0-hard", "M1-hard", "E0-M0", "E1-M0", "E1-M1", "hard-master"};
        bool found = false;
        for (const auto job : jobs) found = found || context.pair_id == job;
        if (!found) return false;
    } else if (context.purpose == "holdout") {
        prefix = "calibration-budget-v2-holdout-";
        expected = 96;
        if (context.pair_id != "easy-medium" && context.pair_id != "medium-hard"
            && context.pair_id != "hard-master") return false;
    } else return false;
    const std::string_view protocol(context.protocol_id);
    return protocol.size() > prefix.size() && protocol.substr(0, prefix.size()) == prefix
        && token(protocol.substr(prefix.size()), false) && token(context.run_id, true)
        && digest(context.protocol_sha256) && digest(context.runtime_identity)
        && context.expected_games == expected && context.maximum_requests_per_game >= 1
        && context.maximum_requests_per_game <= 600;
}

// All strings are validated ASCII tokens or fixed literals. No caller-controlled
// quote, escape, locale, nested value, or optional field enters the JSON envelope.
void text_field(std::string& line, const char* key, std::string_view value) {
    line += ",\""; line += key; line += "\":\""; line.append(value); line += '"';
}

void integer_field(std::string& line, const char* key, std::int64_t value) {
    char buffer[32];
    const auto result = std::to_chars(buffer, buffer + sizeof(buffer), value);
    if (result.ec != std::errc{}) throw std::runtime_error("cannot encode progress integer");
    line += ",\""; line += key; line += "\":";
    line.append(buffer, result.ptr);
}

}  // namespace

ProgressWriter::ProgressWriter(const ProgressContext& context, std::ostream& sink,
    std::size_t maximum_bytes)
    : context_(context), sink_(sink), maximum_bytes_(maximum_bytes) {
    if (!valid_context(context_)) error_ = ProgressWriteError::invalid_context;
    else if (maximum_bytes_ == 0 || maximum_bytes_ > progress_maximum_bytes)
        error_ = ProgressWriteError::invalid_capacity;
}

bool ProgressWriter::fail(ProgressWriteError error) noexcept {
    if (error_ == ProgressWriteError::none) error_ = error;
    return false;
}

bool ProgressWriter::run_started(std::int64_t clock) noexcept {
    return emit(Event::run_start, 0, 0, 0, {}, clock);
}
bool ProgressWriter::game_started(std::int64_t game, std::int64_t clock) noexcept {
    return emit(Event::game_start, game, 0, 0, {}, clock);
}
bool ProgressWriter::request_started(std::int64_t game, std::int64_t index,
    std::int64_t ply, std::string_view phase, std::int64_t clock) noexcept {
    return emit(Event::request_start, game, index, ply, phase, clock);
}
bool ProgressWriter::request_returned(std::int64_t game, std::int64_t index,
    std::int64_t ply, std::string_view phase, std::int64_t clock) noexcept {
    return emit(Event::request_return, game, index, ply, phase, clock);
}
bool ProgressWriter::game_finished(std::int64_t game, std::int64_t count,
    std::int64_t clock) noexcept {
    return emit(Event::game_finish, game, count, 0, {}, clock);
}
bool ProgressWriter::run_finished(std::int64_t games, std::int64_t count,
    std::int64_t clock) noexcept {
    return emit(Event::run_finish, games, count, 0, {}, clock);
}

bool ProgressWriter::emit(Event event, std::int64_t first, std::int64_t second,
    std::int64_t ply, std::string_view phase, std::int64_t clock) noexcept {
    if (!ok()) return false;
    if (state_.ended || (!state_.started && event != Event::run_start))
        return fail(ProgressWriteError::invalid_state);
    if (clock < 0 || clock < state_.last_ns) return fail(ProgressWriteError::invalid_clock);
    State next = state_;
    const char* name = nullptr;
    switch (event) {
    case Event::run_start:
        if (state_.started) return fail(ProgressWriteError::invalid_state);
        name = "run_started"; next.started = true;
        break;
    case Event::game_start:
        if (state_.game_open || state_.pending_index != 0
            || state_.games_finished >= context_.expected_games
            || first != state_.games_finished + 1) return fail(ProgressWriteError::invalid_state);
        name = "game_started"; next.game_open = true;
        ++next.games_started; next.returned_in_game = 0;
        break;
    case Event::request_start:
    case Event::request_return: {
        if (!state_.game_open || first != state_.games_started || second < 1
            || second > context_.maximum_requests_per_game || ply < 0
            || ply > context_.maximum_requests_per_game) return fail(ProgressWriteError::invalid_state);
        const bool diagnostic = context_.purpose == "diagnostic";
        if ((diagnostic && (ply != 0 || phase != (second == 1 ? "cold" : "warm")))
            || (!diagnostic && (ply != second || phase != "play")))
            return fail(ProgressWriteError::invalid_state);
        if (event == Event::request_start) {
            if (state_.pending_index != 0 || second != state_.returned_in_game + 1)
                return fail(ProgressWriteError::invalid_state);
            name = "request_started"; next.pending_index = second;
        } else {
            if (state_.pending_index == 0 || second != state_.pending_index)
                return fail(ProgressWriteError::invalid_state);
            name = "request_returned"; next.pending_index = 0;
            ++next.returned_in_game; ++next.requests_returned;
        }
        break;
    }
    case Event::game_finish:
        if (!state_.game_open || state_.pending_index != 0 || first != state_.games_started
            || second != state_.returned_in_game
            || (context_.purpose == "diagnostic" && state_.returned_in_game != 2))
            return fail(ProgressWriteError::invalid_state);
        name = "game_finished"; next.game_open = false; ++next.games_finished;
        break;
    case Event::run_finish:
        if (state_.game_open || state_.pending_index != 0
            || state_.games_finished != context_.expected_games || first != state_.games_finished
            || second != state_.requests_returned) return fail(ProgressWriteError::invalid_state);
        name = "run_finished"; next.ended = true;
        break;
    }
    const auto limit = static_cast<std::size_t>(context_.expected_games
        * (2 * context_.maximum_requests_per_game + 2) + 2);
    if (state_.records >= limit) return fail(ProgressWriteError::capacity_exceeded);
    try {
        std::string line = "{\"schema\":\"mocs-xiangqi-progress-v2-20261025\"";
        line.reserve(1024);
        text_field(line, "event", name);
        text_field(line, "protocol_id", context_.protocol_id);
        text_field(line, "protocol_sha256", context_.protocol_sha256);
        text_field(line, "purpose", context_.purpose);
        text_field(line, "pair_id", context_.pair_id);
        text_field(line, "run_id", context_.run_id);
        text_field(line, "runtime_identity", context_.runtime_identity);
        integer_field(line, "seq", static_cast<std::int64_t>(state_.records));
        integer_field(line, "monotonic_ns", clock);
        if (event == Event::game_start || event == Event::game_finish
            || event == Event::request_start || event == Event::request_return)
            integer_field(line, "game_index", first);
        if (event == Event::request_start || event == Event::request_return) {
            integer_field(line, "request_index", second);
            integer_field(line, "ply", ply);
            text_field(line, "call_phase", phase);
        } else if (event == Event::game_finish || event == Event::run_finish) {
            if (event == Event::run_finish) integer_field(line, "game_count", first);
            integer_field(line, "request_count", second);
        }
        line += "}\n";
        if (line.size() > progress_maximum_line_bytes || line.size() > maximum_bytes_ - bytes_written_)
            return fail(ProgressWriteError::capacity_exceeded);
        sink_.write(line.data(), static_cast<std::streamsize>(line.size()));
        if (!sink_) return fail(ProgressWriteError::stream_failed);
        sink_.flush();
        if (!sink_) return fail(ProgressWriteError::stream_failed);
        ++next.records; next.last_ns = clock;
        state_ = next; bytes_written_ += line.size();
        return true;
    } catch (const std::bad_alloc&) {
        return fail(ProgressWriteError::allocation_failed);
    } catch (...) {
        return fail(ProgressWriteError::stream_failed);
    }
}

}  // namespace mocs::engine::budget_v2
