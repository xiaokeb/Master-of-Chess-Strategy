#include "mocs/engine/native_engine_registry.hpp"
#include "mocs/engine/go_search_handle.hpp"

#include <limits>
#include <stdexcept>
#include <utility>

namespace mocs::engine {

NativeEngineRegistry& NativeEngineRegistry::instance() {
    static NativeEngineRegistry registry;
    return registry;
}

EngineHandle NativeEngineRegistry::create_chinese_chess() {
    auto engine = std::make_shared<ChineseChessEngine>();
    std::lock_guard lock(mutex_);
    if (next_handle_ == std::numeric_limits<EngineHandle>::max()) {
        throw std::overflow_error("Native engine handle space is exhausted");
    }

    const auto handle = next_handle_++;
    chinese_chess_engines_.emplace(handle, std::move(engine));
    return handle;
}

std::shared_ptr<ChineseChessEngine>
NativeEngineRegistry::find_chinese_chess(const EngineHandle handle) const {
    if (handle <= 0) {
        return {};
    }

    std::lock_guard lock(mutex_);
    const auto found = chinese_chess_engines_.find(handle);
    return found == chinese_chess_engines_.end() ? nullptr : found->second;
}

EngineHandle NativeEngineRegistry::create_go(
    const std::uint8_t size, const std::int32_t black_komi_quarters,
    const std::uint8_t handicap_stones
) {
    auto engine = std::make_shared<GoEngine>(size, black_komi_quarters, handicap_stones);
    std::lock_guard lock(mutex_);
    if (next_handle_ == std::numeric_limits<EngineHandle>::max()) {
        throw std::overflow_error("Native engine handle space is exhausted");
    }
    const auto handle = next_handle_++;
    go_engines_.emplace(handle, std::move(engine));
    return handle;
}

std::shared_ptr<GoEngine> NativeEngineRegistry::find_go(const EngineHandle handle) const {
    if (handle <= 0) return {};
    std::lock_guard lock(mutex_);
    const auto found = go_engines_.find(handle);
    return found == go_engines_.end() ? nullptr : found->second;
}

bool NativeEngineRegistry::release_chinese_chess(const EngineHandle handle) {
    if (handle <= 0) {
        return false;
    }

    std::lock_guard lock(mutex_);
    return chinese_chess_engines_.erase(handle) == 1;
}

bool NativeEngineRegistry::release_go(const EngineHandle handle) {
    if (handle <= 0) return false;
    std::lock_guard lock(mutex_);
    return go_engines_.erase(handle) == 1;
}

EngineHandle NativeEngineRegistry::create_tiger_goat() {
    auto session = std::make_shared<TigerGoatHandle>();
    std::lock_guard lock(mutex_);
    if (next_handle_ == std::numeric_limits<EngineHandle>::max()) {
        throw std::overflow_error("Native engine handle space is exhausted");
    }
    const auto handle = next_handle_++;
    tiger_goat_engines_.emplace(handle, std::move(session));
    return handle;
}

std::shared_ptr<TigerGoatHandle> NativeEngineRegistry::find_tiger_goat(
    const EngineHandle handle
) const {
    if (handle <= 0) return {};
    std::lock_guard lock(mutex_);
    const auto found = tiger_goat_engines_.find(handle);
    return found == tiger_goat_engines_.end() ? nullptr : found->second;
}

bool NativeEngineRegistry::release_tiger_goat(const EngineHandle handle) {
    if (handle <= 0) return false;
    std::lock_guard lock(mutex_);
    return tiger_goat_engines_.erase(handle) == 1;
}

EngineHandle NativeEngineRegistry::register_go_search_runtime(std::shared_ptr<go_ai::KataGoRuntime> runtime) {
    if (!runtime) throw std::invalid_argument("Missing Go runtime");
    std::lock_guard lock(mutex_);
    if (next_handle_ == std::numeric_limits<EngineHandle>::max())
        throw std::overflow_error("Native handle space is exhausted");
    const auto handle = next_handle_++;
    go_search_runtimes_.emplace(handle, std::move(runtime));
    return handle;
}

EngineHandle NativeEngineRegistry::register_go_search_request(std::shared_ptr<go_ai::GoSearchRequestHandle> request) {
    if (!request) throw std::invalid_argument("Missing Go request");
    std::lock_guard lock(mutex_);
    if (next_handle_ == std::numeric_limits<EngineHandle>::max())
        throw std::overflow_error("Native handle space is exhausted");
    const auto handle = next_handle_++;
    go_search_requests_.emplace(handle, std::move(request));
    return handle;
}

std::shared_ptr<go_ai::KataGoRuntime> NativeEngineRegistry::find_go_search_runtime(EngineHandle handle) const {
    std::lock_guard lock(mutex_);
    const auto found = go_search_runtimes_.find(handle);
    return found == go_search_runtimes_.end() ? nullptr : found->second;
}

std::shared_ptr<go_ai::GoSearchRequestHandle> NativeEngineRegistry::find_go_search_request(EngineHandle handle) const {
    std::lock_guard lock(mutex_);
    const auto found = go_search_requests_.find(handle);
    return found == go_search_requests_.end() ? nullptr : found->second;
}

bool NativeEngineRegistry::release_go_search_runtime(EngineHandle handle) {
    std::shared_ptr<go_ai::KataGoRuntime> runtime;
    {
        std::lock_guard lock(mutex_);
        const auto found = go_search_runtimes_.find(handle);
        if (found == go_search_runtimes_.end()) return false;
        runtime = std::move(found->second);
        go_search_runtimes_.erase(found);
    }
    runtime->close(); // Never hold the global registry mutex while joining network threads.
    return true;
}

bool NativeEngineRegistry::release_go_search_request(EngineHandle handle) {
    std::shared_ptr<go_ai::GoSearchRequestHandle> request;
    {
        std::lock_guard lock(mutex_);
        const auto found = go_search_requests_.find(handle);
        if (found == go_search_requests_.end()) return false;
        request = std::move(found->second);
        go_search_requests_.erase(found);
    }
    request->close();
    return true;
}

bool NativeEngineRegistry::release(const EngineHandle handle) {
    if (handle <= 0) return false;
    if (release_go_search_runtime(handle) || release_go_search_request(handle)) return true;
    std::lock_guard lock(mutex_);
    return chinese_chess_engines_.erase(handle) + go_engines_.erase(handle)
        + tiger_goat_engines_.erase(handle) == 1;
}

}  // namespace mocs::engine
