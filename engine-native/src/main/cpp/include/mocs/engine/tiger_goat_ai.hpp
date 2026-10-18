#pragma once

#include "mocs/engine/tiger_goat_game.hpp"

#include <chrono>
#include <cstdint>
#include <optional>

namespace mocs::engine::tiger_goat {

inline constexpr unsigned maximum_search_turn_depth = 16;
inline constexpr std::chrono::milliseconds maximum_search_time{60000};

struct SearchLimits {
    // Depth counts changes of side, not individual jumps in one tiger turn.
    unsigned turn_depth = 3;
    std::uint64_t node_limit = 10000;
    std::chrono::milliseconds time_limit{1500};
};

struct SearchCancellation {
    // Called synchronously, often. The callback must be fast, noexcept and must
    // not access/mutate this search's game. Context ownership stays with caller.
    bool (*requested)(void* context) noexcept = nullptr;
    void* context = nullptr;
};

enum class SearchStop : std::uint8_t {
    depth_completed,
    no_legal_action,
    node_limit,
    time_limit,
    cancelled,
    invalid_limits,
    rule_rejected,
};

struct SearchResult {
    // An interrupted search may retain a verified legal fallback. A cancelled
    // result is not permission for the caller to apply that action.
    std::optional<Action> action;
    std::optional<int> score;
    SearchStop stop = SearchStop::no_legal_action;
    unsigned completed_turn_depth = 0;
    std::uint64_t visited_nodes = 0;
};

// Deterministic ordering; no branch truncation or difficulty/strength claim.
// Uses a private copy, leaving the caller's state and undo history untouched.
// Allocation failures propagate without mutating the caller. Budgets are
// cooperative: one fixed-size rule operation/allocation may finish after stop.
[[nodiscard]] SearchResult search_action(
    const TigerGoatGame& game,
    SearchLimits limits = {},
    SearchCancellation cancellation = {});

}  // namespace mocs::engine::tiger_goat
