#pragma once

#include <cstddef>
#include <cstdint>
#include <ostream>
#include <string>
#include <string_view>

namespace mocs::engine::budget_v2 {

inline constexpr std::size_t progress_maximum_bytes = 128U * 1024U * 1024U;
inline constexpr std::size_t progress_maximum_line_bytes = 4096;

struct ProgressContext {
    std::string protocol_id;
    std::string protocol_sha256;
    std::string purpose;
    std::string pair_id;
    std::string run_id;
    std::string runtime_identity;
    std::int64_t expected_games;
    std::int64_t maximum_requests_per_game;
};

enum class ProgressWriteError {
    none, invalid_context, invalid_capacity, invalid_state, invalid_clock,
    capacity_exceeded, allocation_failed, stream_failed,
};

// Owns a fixed copy of external identity, not the sink or a search process.
// The caller must own an exclusive binary sink for this writer's lifetime.
// Serial use only; the writer and its stream are not thread-safe. No destructor
// record, implicit run completion, result authority, or search permission.
class ProgressWriter final {
public:
    // Copy allocation can throw before construction; no sink bytes are written.
    explicit ProgressWriter(const ProgressContext& context, std::ostream& sink,
        std::size_t maximum_bytes = progress_maximum_bytes);
    ~ProgressWriter() = default;
    ProgressWriter(const ProgressWriter&) = delete;
    ProgressWriter& operator=(const ProgressWriter&) = delete;
    ProgressWriter(ProgressWriter&&) = delete;
    ProgressWriter& operator=(ProgressWriter&&) = delete;

    [[nodiscard]] bool run_started(std::int64_t monotonic_ns) noexcept;
    [[nodiscard]] bool game_started(std::int64_t game_index, std::int64_t monotonic_ns) noexcept;
    [[nodiscard]] bool request_started(std::int64_t game_index, std::int64_t request_index,
        std::int64_t ply, std::string_view call_phase, std::int64_t monotonic_ns) noexcept;
    [[nodiscard]] bool request_returned(std::int64_t game_index, std::int64_t request_index,
        std::int64_t ply, std::string_view call_phase, std::int64_t monotonic_ns) noexcept;
    [[nodiscard]] bool game_finished(std::int64_t game_index, std::int64_t request_count,
        std::int64_t monotonic_ns) noexcept;
    [[nodiscard]] bool run_finished(std::int64_t game_count, std::int64_t request_count,
        std::int64_t monotonic_ns) noexcept;

    [[nodiscard]] bool ok() const noexcept { return error_ == ProgressWriteError::none; }
    [[nodiscard]] ProgressWriteError error() const noexcept { return error_; }
    [[nodiscard]] std::size_t records_written() const noexcept { return state_.records; }
    [[nodiscard]] std::size_t bytes_written() const noexcept { return bytes_written_; }
    [[nodiscard]] bool sidecar_ended() const noexcept { return state_.ended; }

private:
    enum class Event { run_start, game_start, request_start, request_return, game_finish, run_finish };
    struct State {
        std::size_t records{0};
        std::int64_t last_ns{-1};
        std::int64_t games_started{0};
        std::int64_t games_finished{0};
        std::int64_t returned_in_game{0};
        std::int64_t requests_returned{0};
        std::int64_t pending_index{0};
        bool started{false};
        bool ended{false};
        bool game_open{false};
    };
    [[nodiscard]] bool fail(ProgressWriteError error) noexcept;
    [[nodiscard]] bool emit(Event event, std::int64_t first, std::int64_t second,
        std::int64_t ply, std::string_view phase, std::int64_t clock) noexcept;

    const ProgressContext context_;
    std::ostream& sink_;
    const std::size_t maximum_bytes_;
    ProgressWriteError error_{ProgressWriteError::none};
    State state_;
    std::size_t bytes_written_{0};
};

}  // namespace mocs::engine::budget_v2
