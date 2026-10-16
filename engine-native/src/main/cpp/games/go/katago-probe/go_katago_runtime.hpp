#pragma once

#include "go_katago_search.hpp"

#include <memory>
#include <string>

namespace mocs::engine::go_ai {

struct KataGoRuntimeState;

class KataGoSearchRequest final {
public:
    // Exactly one synchronous run. Request lifetime must exceed run/cancel calls.
    [[nodiscard]] GoSearchSuggestion run();
    void cancel() noexcept;

private:
    friend class KataGoRuntime;
    KataGoSearchRequest(std::shared_ptr<KataGoRuntimeState> state,
        std::vector<std::uint8_t> snapshot, GoSearchBudget budget);
    std::shared_ptr<KataGoRuntimeState> state_;
    const std::vector<std::uint8_t> snapshot_;
    const GoSearchBudget budget_;
    std::atomic<bool> cancelled_{false};
    std::atomic<bool> consumed_{false};
};

// Owns a locked network and its search session. Construct/close on a worker, never the UI thread.
// One runtime is reusable across requests. Globals initialize once for the native process.
class KataGoRuntime final {
public:
    explicit KataGoRuntime(const std::string& absolute_model_path);
    ~KataGoRuntime();
    KataGoRuntime(const KataGoRuntime&) = delete;
    KataGoRuntime& operator=(const KataGoRuntime&) = delete;

    // Copies request bytes. At most 16 live prepared requests; search itself permits only one.
    [[nodiscard]] std::shared_ptr<KataGoSearchRequest> prepare(
        const std::vector<std::uint8_t>& snapshot, GoSearchBudget budget);
    void cancel_all();
    // Closes admission, signals every request, joins running searches, then destroys network threads.
    void close();
    [[nodiscard]] bool is_closed() const;
    [[nodiscard]] std::uint64_t processed_rows() const;

private:
    std::shared_ptr<KataGoRuntimeState> state_;
};

}  // namespace mocs::engine::go_ai
