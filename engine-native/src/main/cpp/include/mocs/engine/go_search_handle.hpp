#pragma once

#include "../../../games/go/katago-probe/go_katago_runtime.hpp"
#include <condition_variable>

namespace mocs::engine::go_ai {

// JNI request owner: raw close must join too, not just the Kotlin wrapper.
class GoSearchRequestHandle final {
public:
    explicit GoSearchRequestHandle(std::shared_ptr<KataGoSearchRequest> request);
    ~GoSearchRequestHandle();
    [[nodiscard]] GoSearchSuggestion run();
    void cancel();
    void close();
private:
    std::mutex mutex_;
    std::condition_variable idle_;
    std::shared_ptr<KataGoSearchRequest> request_;
    bool running_ = false;
    bool closed_ = false;
};

} // namespace mocs::engine::go_ai
