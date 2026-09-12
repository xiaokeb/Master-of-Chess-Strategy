#pragma once

#include "mocs/engine/chinese_chess.hpp"

#include <cstdint>
#include <memory>
#include <mutex>
#include <unordered_map>

namespace mocs::engine {

using EngineHandle = std::int64_t;

/** Process-local handle table that never exposes native addresses to JNI. */
class NativeEngineRegistry final {
public:
    [[nodiscard]] static NativeEngineRegistry& instance();

    [[nodiscard]] EngineHandle create_chinese_chess();
    [[nodiscard]] std::shared_ptr<ChineseChessEngine> find_chinese_chess(
        EngineHandle handle
    ) const;
    [[nodiscard]] bool release(EngineHandle handle);

private:
    NativeEngineRegistry() = default;

    mutable std::mutex mutex_;
    EngineHandle next_handle_{1};
    std::unordered_map<EngineHandle, std::shared_ptr<ChineseChessEngine>>
        chinese_chess_engines_;
};

}  // namespace mocs::engine
