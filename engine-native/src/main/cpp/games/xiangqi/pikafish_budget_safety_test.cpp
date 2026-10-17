// Real NDK/NNUE regression, independent of all frozen strength-evaluation jobs.
#include "attacks.h"
#include "engine.h"
#include "movegen.h"

#include <chrono>
#include <iostream>
#include <sstream>
#include <stdexcept>
#include <string>

namespace Stockfish::Search {

struct BudgetSafetyProbe {
    enum class Stop { already_requested, expired_time, one_node };

    template<NodeType nodeType>
    static void check(Engine& engine, const Stop stop) {
        engine.wait_for_search_finished();
        engine.search_clear();
        auto& worker = *engine.threads.main_thread()->worker;
        worker.rootPos.set(engine.pos, &worker.rootState);
        worker.rootState = engine.states->back();
        worker.nodes = 0;
        worker.selDepth = worker.nmpMinPly = 0;
        worker.optimism[WHITE] = worker.optimism[BLACK] = 0;
        worker.rootDepth = worker.rootDelta = 1;
        worker.pvIdx = 0;
        worker.accumulatorStack.reset();
        worker.limits = LimitsType{};
        worker.limits.startTime = now() - (stop == Stop::expired_time ? 100 : 0);
        worker.limits.movetime = stop == Stop::expired_time ? 1 : 0;
        worker.limits.nodes = stop == Stop::one_node ? 1 : 0;
        engine.threads.stop = stop == Stop::already_requested;
        auto* manager = worker.main_manager();
        manager->ponder = false;
        manager->stopOnPonderhit = false;
        manager->callsCnt = 0;
        manager->tm.init(worker.limits, worker.rootPos.side_to_move(),
                         worker.rootPos.game_ply(), worker.options, manager->originalTimeAdjust);

        Stack stack[MAX_PLY + 10]{};
        auto* ss = stack + 7;
        for (int i = 7; i > 0; --i) {
            (ss - i)->continuationHistory = &worker.continuationHistory[0][0][NO_PIECE][0];
            (ss - i)->continuationCorrectionHistory =
                &worker.continuationCorrectionHistory[NO_PIECE][0];
            (ss - i)->staticEval = VALUE_NONE;
        }
        for (int i = 0; i <= MAX_PLY + 2; ++i) (ss + i)->ply = i;
        PVMoves pv;
        ss->pv = &pv;
        const auto fen = worker.rootPos.fen();
        const auto key = worker.rootPos.key();
        const auto* state = worker.rootPos.state();
        const auto* accumulator = &worker.accumulatorStack.latest();
        if (std::get<0>(engine.tt.probe(key))) throw std::runtime_error("fixture TT not empty");

        const Value alpha = nodeType == PV ? -VALUE_INFINITE : -20000;
        const Value beta = nodeType == PV ? VALUE_INFINITE : alpha + 1;
        const auto value = worker.qsearch<nodeType>(worker.rootPos, ss, alpha, beta);
        if (!engine.threads.stop) throw std::runtime_error("qsearch did not observe budget/stop");
        if (worker.rootPos.fen() != fen || worker.rootPos.key() != key
            || worker.rootPos.state() != state)
            throw std::runtime_error("aborted qsearch did not undo position/state");
        if (std::get<0>(engine.tt.probe(key)))
            throw std::runtime_error("aborted qsearch wrote a root TT bound");
        if (&worker.accumulatorStack.latest() != accumulator)
            throw std::runtime_error("aborted qsearch did not unwind its NNUE stack");
        if (pv.size() != 0 || value != VALUE_ZERO)
            throw std::runtime_error("aborted qsearch published a PV or non-placeholder score");
        if (stop == Stop::one_node && worker.nodes != 1)
            throw std::runtime_error("one-node qsearch did not stop at its first child");
        if (stop != Stop::one_node && worker.nodes != 0)
            throw std::runtime_error("already-stopped qsearch explored a child");
    }
};

} // namespace Stockfish::Search

int main(int argc, const char* argv[]) {
    if (argc != 2) {
        std::cerr << "usage: mocs_pikafish_budget_safety_test <verified NNUE path>\n";
        return 2;
    }
    using namespace Stockfish;
    Attacks::init();
    Position::init();
    Engine engine;
    engine.set_on_update_no_moves([](const Engine::InfoShort&) {});
    engine.set_on_update_full([](const Engine::InfoFull&) {});
    engine.set_on_iter([](const Engine::InfoIter&) {});
    engine.set_on_bestmove([](std::string_view, std::string_view) {});
    engine.set_on_start([] {});
    engine.set_on_verify_network([](std::string_view) {});
    std::istringstream option(std::string("name EvalFile value ") + argv[1]);
    engine.get_options().setoption(option);
    engine.verify_network();

    // Initial captures and a checked king with legal quiet evasions. Diagnostic
    // fixtures only: never results or strength holdout samples.
    const std::string positions[] = {
        "rnbakabnr/9/1c5c1/p1p1p1p1p/9/9/P1P1P1P1P/1C5C1/9/RNBAKABNR w - - 0 1",
        "4k4/9/9/9/9/9/9/4r4/3R5/3AKA3 w - - 0 1"
    };
    int failures = 0;
    int checks = 0;
    for (int position = 0; position != 2; ++position) {
        if (const auto error = engine.set_position(positions[position], {}); error)
            throw std::runtime_error(error->what());
        for (const auto stop : {Search::BudgetSafetyProbe::Stop::already_requested,
                               Search::BudgetSafetyProbe::Stop::expired_time,
                               Search::BudgetSafetyProbe::Stop::one_node}) {
            // A non-PV stand-pat cutoff needs no recursive child, so the one-node
            // case uses only the checked fixture for that window.
            for (const bool pv : {true, false}) {
                if (!pv && position == 0 && stop == Search::BudgetSafetyProbe::Stop::one_node)
                    continue;
                const auto started = std::chrono::steady_clock::now();
                try {
                    if (pv) Search::BudgetSafetyProbe::check<PV>(engine, stop);
                    else Search::BudgetSafetyProbe::check<NonPV>(engine, stop);
                    std::cout << "PASS ";
                } catch (const std::exception& error) {
                    ++failures;
                    std::cout << "FAIL " << error.what() << ' ';
                }
                ++checks;
                std::cout << "position=" << position << " pv=" << pv
                          << " stop=" << int(stop) << " elapsed_ms="
                          << std::chrono::duration_cast<std::chrono::milliseconds>(
                                 std::chrono::steady_clock::now() - started).count() << '\n'
                          << std::flush;
            }
        }
    }
    std::cout << "qsearch checks=" << checks << " failures=" << failures << '\n';
    return failures == 0 ? 0 : 1;
}
