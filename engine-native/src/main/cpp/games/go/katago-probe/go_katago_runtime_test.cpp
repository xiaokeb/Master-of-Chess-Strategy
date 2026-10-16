#include "go_katago_runtime.hpp"

#include <exception>
#include <iostream>
#include <stdexcept>
#include <thread>

namespace {
using namespace mocs::engine;
using namespace mocs::engine::go_ai;
using namespace std::chrono_literals;
using Clock = std::chrono::steady_clock;
int cases = 0;

void require(const bool condition, const char* message) {
    if (!condition) throw std::runtime_error(message);
}

template<typename Action> void check_rejected(Action action, const char* message) {
    bool rejected = false;
    try { action(); } catch (const std::exception&) { rejected = true; }
    require(rejected, message);
    ++cases;
}

void check_cancelled(const std::shared_ptr<KataGoSearchRequest>& request) {
    const auto result = request->run();
    require(result.status == GoSearchStatus::cancelled && !result.action, "Cancelled runtime request exposed a move");
}

void check_move(const GoSearchSuggestion& result, const std::vector<std::uint8_t>& original, const std::uint8_t size) {
    require(result.status == GoSearchStatus::move && result.action, "Runtime returned no NN move");
    GoEngine verifier(size);
    require(verifier.restore(original).restored && verifier.apply(*result.action).accepted,
            "Runtime move failed independent native rule gate");
}

void close_running(KataGoRuntime& runtime) {
    GoEngine game(19);
    require(game.apply(make_go_place(3, 3)).accepted, "Runtime fixture invalid");
    const auto snapshot = game.serialize();
    const auto rows = runtime.processed_rows();
    auto request = runtime.prepare(snapshot, {512, 20s});
    std::atomic<bool> done(false);
    GoSearchSuggestion result{GoSearchStatus::budget_exhausted, std::nullopt};
    std::exception_ptr failure;
    std::thread worker([&] {
        try { result = request->run(); } catch (...) { failure = std::current_exception(); }
        done.store(true, std::memory_order_release);
    });
    bool observed_real_nn = false;
    const auto until = Clock::now() + 10s;
    while (!done.load(std::memory_order_acquire) && Clock::now() < until) {
        if (runtime.processed_rows() > rows) { observed_real_nn = true; break; }
        std::this_thread::sleep_for(1ms);
    }
    const auto closing_started = Clock::now();
    // close runs concurrently with the actual NN/search, without releasing its evaluator early.
    try { runtime.close(); }
    catch (...) { request->cancel(); worker.join(); throw; }
    worker.join();
    if (failure) std::rethrow_exception(failure);
    require(observed_real_nn && runtime.is_closed() && done.load(std::memory_order_acquire),
            "Close did not join a real active NN search");
    require(result.status == GoSearchStatus::cancelled && !result.action && snapshot == game.serialize(),
            "Close exposed a move or changed input");
    ++cases;
    std::cout << "{\"type\":\"runtime-close\",\"elapsedMs\":"
        << std::chrono::duration<double, std::milli>(Clock::now() - closing_started).count()
        << ",\"observedRealNn\":true,\"joined\":true}" << std::endl;
}

void run_cases(const std::string& model_path) {
    check_rejected([] { KataGoRuntime invalid("relative-model.txt.gz"); }, "Relative model path accepted");
    KataGoRuntime runtime(model_path);
    require(!runtime.is_closed(), "Loaded runtime is already closed");
    GoEngine game(9);
    const auto snapshot = game.serialize();
    const auto initial_rows = runtime.processed_rows();

    std::vector<std::shared_ptr<KataGoSearchRequest>> pending;
    for (int i = 0; i < 16; ++i) pending.push_back(runtime.prepare(snapshot, {1, 1s}));
    check_rejected([&] { const auto excess = runtime.prepare(snapshot, {1, 1s}); (void)excess; },
                   "Unbounded pending runtime requests accepted");
    runtime.cancel_all();
    for (const auto& request : pending) check_cancelled(request);
    require(initial_rows == runtime.processed_rows(), "Prepared cancelled requests consumed NN rows");
    ++cases;
    pending.clear();

    auto cancelled = runtime.prepare(snapshot, {1, 1s});
    cancelled->cancel();
    check_cancelled(cancelled);
    require(initial_rows == runtime.processed_rows(), "Pre-cancellation reached NN");
    ++cases;
    cancelled.reset();

    auto mutable_input = snapshot;
    auto copied = runtime.prepare(mutable_input, {1, 1s});
    mutable_input[0] = 0;
    check_move(copied->run(), snapshot, 9);
    ++cases;
    const auto rows = runtime.processed_rows();
    check_rejected([&] { const auto duplicate = copied->run(); (void)duplicate; }, "Duplicate request run accepted");
    require(rows == runtime.processed_rows(), "Duplicate run consumed NN rows");
    copied.reset();

    auto malformed = runtime.prepare({0, 1, 2}, {1, 1s});
    check_rejected([&] { const auto invalid = malformed->run(); (void)invalid; }, "Malformed runtime snapshot accepted");
    require(rows == runtime.processed_rows(), "Malformed snapshot reached NN");
    malformed.reset();
    // Exceptions must release the running guard; cancel_all must permit fresh requests.
    auto after_failure = runtime.prepare(snapshot, {1, 1s});
    check_move(after_failure->run(), snapshot, 9);
    ++cases;
    after_failure.reset();

    auto unstarted = runtime.prepare(snapshot, {1, 1s});
    close_running(runtime);
    check_cancelled(unstarted);
    runtime.close();
    require(runtime.is_closed(), "Repeated close reopened runtime");
    check_rejected([&] { const auto closed = runtime.prepare(snapshot, {1, 1s}); (void)closed; }, "Closed runtime admitted request");

    std::shared_ptr<KataGoSearchRequest> outliving;
    {
        // New runtime initializes process globals only once and owns a fresh model lifetime.
        KataGoRuntime second(model_path);
        outliving = second.prepare(snapshot, {1, 1s});
    }
    check_cancelled(outliving);
    ++cases;
}
}  // namespace

int main(const int argc, const char* const* argv) {
    if (argc != 2) { std::cerr << "usage: mocs_go_katago_runtime_test <locked-15B.txt.gz>\n"; return 2; }
    try {
        run_cases(argv[1]);
        std::cout << "{\"type\":\"runtime-complete\",\"cases\":" << cases << "}" << std::endl;
        return 0;
    } catch (const std::exception& error) {
        std::cerr << "Go KataGo runtime test failed: " << error.what() << '\n';
        return 1;
    }
}
