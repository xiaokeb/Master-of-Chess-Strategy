#include "go_katago_search.hpp"
#include "go_katago_position.hpp"
#include "search/search.h"

#include <cmath>
#include <functional>
#include <stdexcept>

namespace mocs::engine::go_ai {
namespace {
using Clock = std::chrono::steady_clock;

GoEngine restore_request(const std::vector<std::uint8_t>& snapshot) {
    for (const std::uint8_t size : {9, 13, 19}) {
        GoEngine candidate(size);
        if (candidate.restore(snapshot).restored) {
            if (candidate.phase() != GoPhase::playing) {
                throw std::invalid_argument("Go AI snapshot is not in the playing phase");
            }
            return candidate;
        }
    }
    throw std::invalid_argument("Invalid Go AI snapshot");
}

}  // namespace

void validate_go_search_budget(const GoSearchBudget budget) {
    if (budget.max_visits < 1 || budget.max_visits > 512 ||
        budget.max_time.count() < 1 || budget.max_time.count() > 20'000)
        throw std::invalid_argument("Go AI budget must be 1-512 visits and 1-20000 ms");
}

KataGoSearchSession::KataGoSearchSession(NNEvaluator& evaluator, Logger& logger)
    : evaluator_(evaluator), logger_(logger) {
    if (evaluator_.isNeuralNetLess() || evaluator_.getModelVersion() != 8 ||
        evaluator_.getInternalModelName() != "b15c192-s1672170752-d466197061" ||
        evaluator_.getNumModelParameters() != 9'930'951)
        throw std::invalid_argument("Go AI needs the locked real 15B network");
}

GoSearchSuggestion KataGoSearchSession::suggest(const std::vector<std::uint8_t>& snapshot,
    const GoSearchBudget budget, const std::atomic<bool>& cancelled) {
    validate_go_search_budget(budget);
    const auto started = Clock::now();
    const auto deadline = started + budget.max_time;
    auto result = [&](const GoSearchStatus status) {
        GoSearchSuggestion value{status, std::nullopt};
        value.elapsed_ms = std::chrono::duration<double, std::milli>(Clock::now() - started).count();
        return value;
    };
    if (cancelled.load(std::memory_order_acquire)) return result(GoSearchStatus::cancelled);
    std::unique_lock<std::mutex> lock(operation_mutex_, std::try_to_lock);
    if (!lock.owns_lock()) return result(GoSearchStatus::busy);

    GoEngine native = restore_request(snapshot);
    KataGoPosition position(native);
    bool supported = false;
    evaluator_.getSupportedRules(position.history.rules, supported);
    if (!supported) throw std::runtime_error("Go AI network does not support required rules");
    if (cancelled.load(std::memory_order_acquire)) return result(GoSearchStatus::cancelled);
    if (Clock::now() >= deadline) return result(GoSearchStatus::budget_exhausted);

    SearchParams params;
    params.numThreads = 1;
    params.maxVisits = budget.max_visits;
    params.maxPlayouts = budget.max_visits;
    params.maxTime = std::chrono::duration<double>(deadline - Clock::now()).count();
    params.nodeTableShardsPowerOfTwo = 8;
    // Keep all accepted pre-root history, no pondering/tree reuse, noise, or implicit resignations.
    params.ignorePreRootHistory = false;
    params.ignoreAllHistory = false;
    params.rootNoiseEnabled = false;
    params.rootSymmetryPruning = false;
    params.chosenMoveTemperature = 0.0;
    params.chosenMoveTemperatureEarly = 0.0;
    params.conservativePass = false;
    params.enablePassingHacks = false;
    params.enableMorePassingHacks = false;
    Search search(params, &evaluator_, &logger_, "mocs-go-search-v1");
    search.setPosition(position.player, position.board, position.history);
    // setPosition stamps the network's feature modes; check its installed root as well.
    position.board = search.rootBoard;
    position.history = search.rootHistory;
    position.player = search.rootPla;
    position.require_native_agreement(native);
    if (cancelled.load(std::memory_order_acquire)) return result(GoSearchStatus::cancelled);
    if (Clock::now() >= deadline) return result(GoSearchStatus::budget_exhausted);

    std::function<bool()> stop = [&] {
        return cancelled.load(std::memory_order_acquire) || Clock::now() >= deadline;
    };
    search.runWholeSearch(position.player, false, &stop);
    if (cancelled.load(std::memory_order_acquire)) return result(GoSearchStatus::cancelled);
    const auto visits = search.getRootVisits();
    if (visits == 0) return result(GoSearchStatus::budget_exhausted);
    if (visits < 0 || visits > budget.max_visits) throw std::runtime_error("Go AI exceeded visit budget");
    const Loc chosen = search.getChosenMoveLoc();
    if (chosen == Board::NULL_LOC) throw std::runtime_error("Go AI returned no legal candidate");
    const EngineAction action = chosen == Board::PASS_LOC ? make_go_pass() : make_go_place(
        Location::getX(chosen, native.board_size()), Location::getY(chosen, native.board_size()));
    const auto values = search.getRootValuesRequireSuccess();
    if (!std::isfinite(values.winValue) || values.winValue < 0.0 || values.winValue > 1.0 ||
        !std::isfinite(values.expectedScore)) throw std::runtime_error("Invalid Go AI search estimates");
    // Only mutate the isolated native candidate. The caller must revalidate its request revision.
    if (!native.apply(action).accepted) throw std::runtime_error("Go AI candidate failed native rule gate");
    if (cancelled.load(std::memory_order_acquire)) return result(GoSearchStatus::cancelled);
    auto suggestion = result(GoSearchStatus::move);
    suggestion.action = action;
    suggestion.visits = visits;
    suggestion.white_win_probability = values.winValue;
    suggestion.white_minus_black_points = values.expectedScore;
    return suggestion;
}

}  // namespace mocs::engine::go_ai
