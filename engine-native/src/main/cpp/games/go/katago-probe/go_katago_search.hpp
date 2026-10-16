#pragma once

#include "mocs/engine/go.hpp"

#include <atomic>
#include <chrono>
#include <cstdint>
#include <mutex>
#include <optional>
#include <vector>

class NNEvaluator;
class Logger;

namespace mocs::engine::go_ai {

struct GoSearchBudget {
    std::int64_t max_visits;
    std::chrono::milliseconds max_time;
};

void validate_go_search_budget(GoSearchBudget budget);

enum class GoSearchStatus { move, cancelled, busy, budget_exhausted };

struct GoSearchSuggestion {
    GoSearchStatus status;
    std::optional<EngineAction> action;
    std::int64_t visits{};
    double elapsed_ms{};
    // Search estimates from White's perspective; never an adjudicated game result.
    double white_win_probability{};
    double white_minus_black_points{};
};

// Evaluator/logger must outlive this session and every call. A close must join callers first.
// The evaluator's server threads must already be running; loading is a separate UI state.
class KataGoSearchSession final {
public:
    KataGoSearchSession(NNEvaluator& evaluator, Logger& logger);
    KataGoSearchSession(const KataGoSearchSession&) = delete;
    KataGoSearchSession& operator=(const KataGoSearchSession&) = delete;

    // Stable immutable MOCG request bytes, not a live UI engine. Invalid state/budget throws.
    // Cancellation is per request and remains alive until this synchronous call returns.
    [[nodiscard]] GoSearchSuggestion suggest(const std::vector<std::uint8_t>& snapshot,
        GoSearchBudget budget, const std::atomic<bool>& cancelled);

private:
    NNEvaluator& evaluator_;
    Logger& logger_;
    std::mutex operation_mutex_;
};

}  // namespace mocs::engine::go_ai
