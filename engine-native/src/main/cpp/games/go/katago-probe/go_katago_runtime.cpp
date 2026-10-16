#include "go_katago_runtime.hpp"
#include "core/config_parser.h"
#include "core/logger.h"
#include "game/board.h"
#include "neuralnet/nneval.h"
#include "neuralnet/nninputs.h"
#include "neuralnet/nninterface.h"

#include <algorithm>
#include <condition_variable>
#include <stdexcept>

namespace mocs::engine::go_ai {
namespace {
constexpr const char* model_sha256 = "09456899f1b9155217f4ca059cf8a68f79b7eba22cf6b7c00ffb7f17ce067967";
constexpr std::size_t max_live_requests = 16;
// Existing MOCG event limit is smaller; reject oversized JNI input before allocating a copy.
constexpr std::size_t max_snapshot_bytes = 1'000'000;
std::once_flag initialize_once;

void initialize_globals() {
    std::call_once(initialize_once, [] {
        Board::initHash();
        ScoreValue::initTables();
        NeuralNet::globalInitialize();
    });
}
}  // namespace

struct KataGoRuntimeState {
    ConfigParser config{std::map<std::string, std::string>{}};
    Logger logger{&config, false, true, false, false};
    std::unique_ptr<NNEvaluator> evaluator;
    std::unique_ptr<KataGoSearchSession> search;
    std::mutex mutex;
    std::condition_variable idle;
    bool closed = false;
    std::size_t running = 0;
    std::uint64_t final_rows = 0;
    std::vector<std::weak_ptr<KataGoSearchRequest>> requests;

    explicit KataGoRuntimeState(const std::string& path) {
        initialize_globals();
        evaluator = std::make_unique<NNEvaluator>("mocs-go-15B", path, model_sha256, &logger,
            1, 19, 19, false, true, 12, 6, false, "", enabled_t::False,
            1, std::vector<int>{-1}, "mocs-go-katago-runtime-v1", false, 0, config);
        // Session verifies real model version/name/parameter count before exposing any request.
        search = std::make_unique<KataGoSearchSession>(*evaluator, logger);
        evaluator->spawnServerThreads();
    }
};

namespace {
class RunningGuard final {
public:
    explicit RunningGuard(std::shared_ptr<KataGoRuntimeState> state) : state_(std::move(state)) {}
    ~RunningGuard() {
        std::lock_guard<std::mutex> lock(state_->mutex);
        --state_->running;
        state_->idle.notify_all();
    }
private:
    std::shared_ptr<KataGoRuntimeState> state_;
};

void signal_requests(KataGoRuntimeState& state) {
    for (const auto& weak : state.requests) if (const auto request = weak.lock()) request->cancel();
}
}  // namespace

KataGoSearchRequest::KataGoSearchRequest(std::shared_ptr<KataGoRuntimeState> state,
    std::vector<std::uint8_t> snapshot, const GoSearchBudget budget)
    : state_(std::move(state)), snapshot_(std::move(snapshot)), budget_(budget) {}

void KataGoSearchRequest::cancel() noexcept {
    cancelled_.store(true, std::memory_order_release);
}

GoSearchSuggestion KataGoSearchRequest::run() {
    if (consumed_.exchange(true, std::memory_order_acq_rel))
        throw std::logic_error("Go AI request can only run once");
    {
        std::lock_guard<std::mutex> lock(state_->mutex);
        if (state_->closed || cancelled_.load(std::memory_order_acquire))
            return GoSearchSuggestion{GoSearchStatus::cancelled, std::nullopt};
        ++state_->running;
    }
    const RunningGuard guard(state_);
    auto result = state_->search->suggest(snapshot_, budget_, cancelled_);
    if (cancelled_.load(std::memory_order_acquire)) {
        result.status = GoSearchStatus::cancelled;
        result.action.reset();
    }
    return result;
}

KataGoRuntime::KataGoRuntime(const std::string& absolute_model_path) {
    if (absolute_model_path.empty() || absolute_model_path.front() != '/' ||
        absolute_model_path.find('\0') != std::string::npos)
        throw std::invalid_argument("Go AI model requires an absolute Android path without NUL");
    state_ = std::make_shared<KataGoRuntimeState>(absolute_model_path);
}

KataGoRuntime::~KataGoRuntime() { close(); }

std::shared_ptr<KataGoSearchRequest> KataGoRuntime::prepare(
    const std::vector<std::uint8_t>& snapshot, const GoSearchBudget budget) {
    validate_go_search_budget(budget);
    if (snapshot.empty() || snapshot.size() > max_snapshot_bytes)
        throw std::invalid_argument("Go AI snapshot byte limit exceeded");
    std::lock_guard<std::mutex> lock(state_->mutex);
    if (state_->closed) throw std::logic_error("Go AI runtime is closed");
    auto& requests = state_->requests;
    requests.erase(std::remove_if(requests.begin(), requests.end(),
        [](const auto& weak) { return weak.expired(); }), requests.end());
    if (requests.size() >= max_live_requests) throw std::runtime_error("Too many live Go AI requests");
    auto request = std::shared_ptr<KataGoSearchRequest>(new KataGoSearchRequest(state_, snapshot, budget));
    requests.emplace_back(request);
    return request;
}

void KataGoRuntime::cancel_all() {
    std::lock_guard<std::mutex> lock(state_->mutex);
    signal_requests(*state_);
}

void KataGoRuntime::close() {
    std::unique_lock<std::mutex> lock(state_->mutex);
    state_->closed = true;
    signal_requests(*state_);
    state_->idle.wait(lock, [&] { return state_->running == 0; });
    if (state_->evaluator) state_->final_rows = state_->evaluator->numRowsProcessed();
    state_->search.reset();
    state_->evaluator.reset();
}

bool KataGoRuntime::is_closed() const {
    std::lock_guard<std::mutex> lock(state_->mutex);
    return state_->closed;
}

std::uint64_t KataGoRuntime::processed_rows() const {
    std::lock_guard<std::mutex> lock(state_->mutex);
    return state_->evaluator ? state_->evaluator->numRowsProcessed() : state_->final_rows;
}

}  // namespace mocs::engine::go_ai
