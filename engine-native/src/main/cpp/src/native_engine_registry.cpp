#include "mocs/engine/native_engine_registry.hpp"

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

bool NativeEngineRegistry::release(const EngineHandle handle) {
    if (handle <= 0) {
        return false;
    }

    std::lock_guard lock(mutex_);
    return chinese_chess_engines_.erase(handle) == 1;
}

}  // namespace mocs::engine
