#include "mocs/engine/go_search_handle.hpp"
#include <stdexcept>

namespace mocs::engine::go_ai {

GoSearchRequestHandle::GoSearchRequestHandle(std::shared_ptr<KataGoSearchRequest> request)
    : request_(std::move(request)) {
    if (!request_) throw std::invalid_argument("Missing Go search request");
}
GoSearchRequestHandle::~GoSearchRequestHandle() { close(); }

GoSearchSuggestion GoSearchRequestHandle::run() {
    std::shared_ptr<KataGoSearchRequest> request;
    {
        std::lock_guard lock(mutex_);
        if (closed_ || running_) throw std::logic_error("Closed or running Go request");
        running_ = true;
        request = request_;
    }
    // Both success and exceptions unblock close; no search runs under the lifecycle mutex.
    try {
        auto result = request->run();
        std::lock_guard lock(mutex_);
        running_ = false;
        idle_.notify_all();
        return result;
    } catch (...) {
        std::lock_guard lock(mutex_);
        running_ = false;
        idle_.notify_all();
        throw;
    }
}

void GoSearchRequestHandle::cancel() {
    std::lock_guard lock(mutex_);
    if (request_) request_->cancel(); // Atomic signal, never waits for inference.
}

void GoSearchRequestHandle::close() {
    std::unique_lock lock(mutex_);
    closed_ = true;
    if (request_) request_->cancel();
    idle_.wait(lock, [&] { return !running_; });
    request_.reset();
}

} // namespace mocs::engine::go_ai
