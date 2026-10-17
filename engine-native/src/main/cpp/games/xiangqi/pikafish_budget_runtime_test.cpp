// Production Engine API smoke/safety tests, not a strength benchmark.
#include "attacks.h"
#include "engine.h"
#include "movegen.h"
#include "uci.h"

#include <algorithm>
#include <atomic>
#include <chrono>
#include <iostream>
#include <sstream>
#include <stdexcept>
#include <string>
#include <thread>
#include <vector>

namespace Stockfish::Search {
struct BudgetSafetyProbe {
    static std::vector<std::string> legal_roots(Engine& engine) {
        std::vector<std::string> roots;
        for (const auto move : MoveList<LEGAL>(engine.pos)) {
            roots.push_back(UCIEngine::move(move));
            if (roots.size() == 4) break;
        }
        return roots;
    }
    static u64 nodes(const Engine& engine) { return engine.threads.nodes_searched(); }
    static void restored(Engine& engine) {
        for (const auto& thread : engine.threads) {
            if (thread->worker->rootPos.fen() != engine.pos.fen()
                || thread->worker->rootPos.key() != engine.pos.key())
                throw std::runtime_error("worker board/key changed by interrupted search");
            if (thread->worker->rootPos.state() != &thread->worker->rootState)
                throw std::runtime_error("worker state was not unwound to the root");
        }
    }
};
} // namespace Stockfish::Search

int main(int argc, const char* argv[]) {
    if (argc != 2) return 2;
    using namespace Stockfish;
    using Clock = std::chrono::steady_clock;
    const auto millis = [](const auto duration) {
        return std::chrono::duration_cast<std::chrono::milliseconds>(duration).count();
    };
    Attacks::init();
    Position::init();
    Engine engine;
    const auto option = [&](const std::string& name, const std::string& value) {
        std::istringstream input("name " + name + " value " + value);
        engine.get_options().setoption(input);
    };
    option("EvalFile", argv[1]);
    engine.set_on_verify_network([](std::string_view) {});
    engine.verify_network();
    engine.set_on_update_no_moves([](const Engine::InfoShort&) {});
    engine.set_on_iter([](const Engine::InfoIter&) {});
    std::atomic_bool started{false};
    engine.set_on_start([&] { started = true; });
    std::string best;
    int bestCallbacks = 0;
    int fullCallbacks = 0;
    bool invalidPv = false;
    std::vector<std::string> roots;
    engine.set_on_bestmove([&](std::string_view move, std::string_view) {
        best.assign(move);
        ++bestCallbacks;
    });
    engine.set_on_update_full([&](const Engine::InfoFull& info) {
        ++fullCallbacks;
        if (info.pv.size() < 4 || std::find(roots.begin(), roots.end(), info.pv.substr(0, 4)) == roots.end())
            invalidPv = true;
    });

    int checks = 0;
    int failures = 0;
    for (const int threads : {1, 2}) {
        option("Threads", std::to_string(threads));
        for (const int multiPv : {1, 4}) {
            option("MultiPV", std::to_string(multiPv));
            const std::string initial =
                "rnbakabnr/9/1c5c1/p1p1p1p1p/9/9/P1P1P1P1P/1C5C1/9/RNBAKABNR w - - 0 1";
            if (const auto error = engine.set_position(initial, {"h2e2", "h7e7"}); error)
                throw std::runtime_error(error->what());
            roots = Search::BudgetSafetyProbe::legal_roots(engine);
            if (roots.size() != 4) throw std::runtime_error("fixture needs four legal roots");
            const auto before = engine.fen();
            // Deliberately no clear/reset between requests: the final request is
            // a new low budget after external stop on the very same instance.
            for (const std::string mode : {"node-1", "node-128", "time-10", "external-stop", "resume-64"}) {
                Search::LimitsType limits;
                limits.startTime = now();
                limits.searchmoves = roots;
                limits.depth = 64;
                if (mode == "node-1") limits.nodes = 1;
                if (mode == "node-128") limits.nodes = 128;
                if (mode == "time-10") limits.movetime = 10;
                if (mode == "resume-64") limits.nodes = 64;
                started = false;
                best.clear();
                bestCallbacks = fullCallbacks = 0;
                invalidPv = false;
                const auto begin = Clock::now();
                auto requestedStop = begin;
                engine.go(limits);
                bool startTimedOut = false;
                if (mode == "external-stop") {
                    while (!started && millis(Clock::now() - begin) < 2000)
                        std::this_thread::sleep_for(std::chrono::milliseconds(1));
                    startTimedOut = !started;
                    // Explicitly stopping this NEW bounded diagnostic is part of
                    // its specification; no original evaluation is touched.
                    std::this_thread::sleep_for(std::chrono::milliseconds(100));
                    requestedStop = Clock::now();
                    engine.stop();
                }
                engine.wait_for_search_finished();
                const auto end = Clock::now();
                const auto elapsed = millis(end - begin);
                const auto stopLatency = mode == "external-stop" ? millis(end - requestedStop) : 0;
                const auto nodes = Search::BudgetSafetyProbe::nodes(engine);
                try {
                    if (startTimedOut) throw std::runtime_error("search start deadline exceeded");
                    if (elapsed > 2000 || stopLatency > 1000)
                        throw std::runtime_error("registered response tolerance exceeded");
                    if (bestCallbacks != 1 || std::find(roots.begin(), roots.end(), best) == roots.end()
                        || invalidPv)
                        throw std::runtime_error("bestmove/PV callback escaped legal roots");
                    if (engine.fen() != before)
                        throw std::runtime_error("public board/history endpoint changed");
                    Search::BudgetSafetyProbe::restored(engine);
                    if (!nodes) throw std::runtime_error("no actual search node processed");
                    std::cout << "PASS ";
                } catch (const std::exception& error) {
                    ++failures;
                    std::cout << "FAIL " << error.what() << ' ';
                }
                ++checks;
                std::cout << "threads=" << threads << " multipv=" << multiPv << " mode=" << mode
                          << " elapsed_ms=" << elapsed << " stop_ms=" << stopLatency
                          << " nodes=" << nodes << " full_callbacks=" << fullCallbacks
                          << " best=" << best << '\n' << std::flush;
            }
        }
    }
    std::cout << "runtime checks=" << checks << " failures=" << failures << '\n';
    return failures == 0 ? 0 : 1;
}
