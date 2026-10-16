#include "go_katago_search.hpp"
#include "core/config_parser.h"
#include "core/logger.h"
#include "game/board.h"
#include "neuralnet/nneval.h"
#include "neuralnet/nninputs.h"
#include "neuralnet/nninterface.h"

#include <array>
#include <cmath>
#include <exception>
#include <iostream>
#include <stdexcept>
#include <thread>

namespace {
using namespace mocs::engine;
using namespace mocs::engine::go_ai;
using Clock = std::chrono::steady_clock;
using namespace std::chrono_literals;
constexpr const char* model_sha256 = "09456899f1b9155217f4ca059cf8a68f79b7eba22cf6b7c00ffb7f17ce067967";
int cases = 0;

void require(const bool condition, const char* message) {
    if (!condition) throw std::runtime_error(message);
}

void apply(GoEngine& game, const EngineAction& action) {
    require(game.apply(action).accepted, "Native search fixture rejected");
}

void check_move(KataGoSearchSession& session, const GoEngine& game, const GoSearchBudget budget) {
    const auto original = game.serialize();
    const std::atomic<bool> cancelled(false);
    const auto result = session.suggest(original, budget, cancelled);
    require(result.status == GoSearchStatus::move && result.action.has_value(), "Real NN search returned no move");
    require(result.visits >= 1 && result.visits <= budget.max_visits, "Search visits outside requested budget");
    require(std::isfinite(result.elapsed_ms) && result.elapsed_ms >= 0.0 &&
        std::isfinite(result.white_win_probability) && result.white_win_probability >= 0.0 &&
        result.white_win_probability <= 1.0 && std::isfinite(result.white_minus_black_points),
        "Search diagnostics are invalid");
    GoEngine replay(game.board_size());
    require(replay.restore(original).restored, "Native search result fixture restore failed");
    require(replay.apply(*result.action).accepted, "Search recommendation is not native-legal");
    require(original == game.serialize(), "Search changed source bytes");
    ++cases;
    std::cout << "{\"type\":\"search\",\"size\":" << static_cast<int>(game.board_size())
        << ",\"handicap\":" << static_cast<int>(game.handicap_stones())
        << ",\"maxVisits\":" << budget.max_visits << ",\"visits\":" << result.visits
        << ",\"elapsedMs\":" << result.elapsed_ms << ",\"kind\":" << result.action->kind
        << ",\"x\":" << result.action->arguments[0] << ",\"y\":" << result.action->arguments[1]
        << ",\"nativeAccepted\":true}" << std::endl;
}

void check_rejected(KataGoSearchSession& session, NNEvaluator& evaluator,
                    const std::vector<std::uint8_t>& snapshot, const GoSearchBudget budget) {
    const auto original = snapshot;
    const auto rows = evaluator.numRowsProcessed();
    const std::atomic<bool> cancelled(false);
    bool rejected = false;
    try { const auto result = session.suggest(snapshot, budget, cancelled); (void)result; }
    catch (const std::exception&) { rejected = true; }
    require(rejected && original == snapshot && rows == evaluator.numRowsProcessed(),
            "Invalid request reached NN or changed bytes");
    ++cases;
}

GoEngine ko_fixture() {
    GoEngine game(9);
    for (const GoPoint point : {GoPoint{1,0}, {1,1}, {0,1}, {2,0}, {1,2},
                               {3,1}, {8,8}, {2,2}, {2,1}})
        apply(game, make_go_place(point.x, point.y));
    require(!game.apply(make_go_place(1, 1)).accepted, "Ko fixture is not ko");
    return game;
}

void cancellation_and_busy(KataGoSearchSession& session, NNEvaluator& evaluator) {
    GoEngine game(19);
    apply(game, make_go_place(2, 2));
    apply(game, make_go_place(16, 16));
    const auto snapshot = game.serialize();
    const auto rows = evaluator.numRowsProcessed();
    std::atomic<bool> cancelled(true);
    const auto before = session.suggest(snapshot, {512, 20s}, cancelled);
    require(before.status == GoSearchStatus::cancelled && !before.action && evaluator.numRowsProcessed() == rows,
            "Pre-cancelled search performed NN or returned a move");
    ++cases;

    cancelled.store(false, std::memory_order_release);
    std::atomic<bool> done(false);
    GoSearchSuggestion result{GoSearchStatus::budget_exhausted, std::nullopt};
    std::exception_ptr failure;
    std::thread worker([&] {
        try { result = session.suggest(snapshot, {512, 20s}, cancelled); }
        catch (...) { failure = std::current_exception(); }
        done.store(true, std::memory_order_release);
    });
    bool observed_nn = false;
    bool busy = false;
    double cancellation_ms = 0.0;
    try {
        const auto until = Clock::now() + 10s;
        while (!done.load(std::memory_order_acquire) && Clock::now() < until) {
            if (evaluator.numRowsProcessed() > rows) { observed_nn = true; break; }
            std::this_thread::sleep_for(1ms);
        }
        const std::atomic<bool> second_cancelled(false);
        const auto second = session.suggest(snapshot, {1, 1s}, second_cancelled);
        busy = second.status == GoSearchStatus::busy && !second.action;
        const auto cancel_started = Clock::now();
        cancelled.store(true, std::memory_order_release);
        worker.join();
        cancellation_ms = std::chrono::duration<double, std::milli>(Clock::now() - cancel_started).count();
    } catch (...) {
        cancelled.store(true, std::memory_order_release);
        if (worker.joinable()) worker.join();
        throw;
    }
    if (failure) std::rethrow_exception(failure);
    require(observed_nn && busy, "Did not observe a real in-flight NN and immediate busy result");
    require(result.status == GoSearchStatus::cancelled && !result.action && snapshot == game.serialize(),
            "Cancelled search returned a move or changed source");
    cases += 2;
    std::cout << "{\"type\":\"cancel\",\"elapsedAfterCancelMs\":" << cancellation_ms
        << ",\"observedRealNn\":true,\"busy\":true}" << std::endl;
    // New request has a distinct cancellation lifetime and must still work.
    check_move(session, game, {1, 1s});
}

void run_cases(NNEvaluator& evaluator, Logger& logger) {
    KataGoSearchSession session(evaluator, logger);
    constexpr std::array<std::int64_t, 4> visits{1, 8, 32, 128};
    constexpr std::array<std::chrono::milliseconds, 4> times{1s, 3s, 8s, 20s};
    for (const std::uint8_t size : {9, 13, 19}) for (const std::uint8_t handicap : {0, 5}) {
        GoEngine game(size, handicap == 0 ? 15 : 0, handicap);
        apply(game, make_go_place(1, 1));
        apply(game, make_go_place(size - 1, size - 1));
        for (std::size_t level = 0; level < visits.size(); ++level)
            check_move(session, game, {visits[level], times[level]});
    }
    GoEngine ko = ko_fixture();
    check_move(session, ko, {8, 3s});
    apply(ko, make_go_pass());
    apply(ko, make_go_pass());
    check_rejected(session, evaluator, ko.serialize(), {8, 3s});
    apply(ko, make_go_toggle_dead(2, 0, 0));
    apply(ko, make_go_resume_play(1));
    check_move(session, ko, {8, 3s});
    check_rejected(session, evaluator, {0, 1, 2}, {1, 1s});
    for (const GoSearchBudget budget : {GoSearchBudget{0, 1s}, {513, 1s}, {1, 0ms}, {1, 20001ms}})
        check_rejected(session, evaluator, ko.serialize(), budget);
    GoEngine finished(9);
    apply(finished, make_go_pass());
    apply(finished, make_go_pass());
    apply(finished, make_go_confirm_score(0, finished.scoring_state().proposal_revision));
    apply(finished, make_go_confirm_score(1, finished.scoring_state().proposal_revision));
    check_rejected(session, evaluator, finished.serialize(), {1, 1s});
    cancellation_and_busy(session, evaluator);

    GoEngine timed(13, 0);
    apply(timed, make_go_place(6, 6));
    const auto snapshot = timed.serialize();
    const std::atomic<bool> cancelled(false);
    const auto deadline = session.suggest(snapshot, {512, 1ms}, cancelled);
    require(deadline.status == GoSearchStatus::budget_exhausted || deadline.status == GoSearchStatus::move,
            "Time-limited search has unexpected status");
    require((deadline.status == GoSearchStatus::move) == deadline.action.has_value(), "Deadline result leaked action");
    if (deadline.action) {
        require(deadline.visits >= 1 && deadline.visits <= 2 && timed.apply(*deadline.action).accepted,
                "Deadline recommendation failed native rule gate");
    } else require(snapshot == timed.serialize(), "No-move deadline changed input");
    ++cases;
    std::cout << "{\"type\":\"deadline\",\"elapsedMs\":" << deadline.elapsed_ms
        << ",\"visits\":" << deadline.visits << ",\"hasMove\":" << (deadline.action ? "true" : "false")
        << "}" << std::endl;
}
}  // namespace

int main(const int argc, const char* const* argv) {
    if (argc != 2) { std::cerr << "usage: mocs_go_katago_search_test <locked-15B.txt.gz>\n"; return 2; }
    try {
        Board::initHash();
        ScoreValue::initTables();
        NeuralNet::globalInitialize();
        ConfigParser config(std::map<std::string, std::string>{});
        Logger logger(&config, false, true, false, false);
        {
            NNEvaluator evaluator("mocs-go-15B-search-test", argv[1], model_sha256, &logger,
                1, 19, 19, false, true, 12, 6, false, "", enabled_t::False,
                1, std::vector<int>{-1}, "mocs-go-search-test-v1", false, 0, config);
            require(!evaluator.isNeuralNetLess() && evaluator.getModelVersion() == 8 &&
                evaluator.getInternalModelName() == "b15c192-s1672170752-d466197061", "Unexpected search network");
            evaluator.spawnServerThreads();
            run_cases(evaluator, logger);
        }
        NeuralNet::globalCleanup();
        std::cout << "{\"type\":\"search-complete\",\"cases\":" << cases << "}" << std::endl;
        return 0;
    } catch (const std::exception& error) {
        std::cerr << "Go KataGo search test failed: " << error.what() << '\n';
        return 1;
    }
}
