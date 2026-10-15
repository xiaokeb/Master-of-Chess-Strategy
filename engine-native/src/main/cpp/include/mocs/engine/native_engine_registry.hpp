#pragma once

#include "mocs/engine/chinese_chess.hpp"
#include "mocs/engine/tiger_goat_game.hpp"

#include <cstdint>
#include <memory>
#include <mutex>
#include <unordered_map>

namespace mocs::engine {

using EngineHandle = std::int64_t;

/** A lookup retains the session; its mutex makes each native operation atomic. */
struct TigerGoatHandle {
    tiger_goat::TigerGoatGame game;
    mutable std::mutex mutex;
};

/** Process-local handle table that never exposes native addresses to JNI. */
class NativeEngineRegistry final {
public:
    [[nodiscard]] static NativeEngineRegistry& instance();

    [[nodiscard]] EngineHandle create_chinese_chess();
    [[nodiscard]] std::shared_ptr<ChineseChessEngine> find_chinese_chess(
        EngineHandle handle
    ) const;
    // JNI releases are type-scoped: a wrong game's handle must remain alive.
    [[nodiscard]] bool release_chinese_chess(EngineHandle handle);
    [[nodiscard]] EngineHandle create_tiger_goat();
    [[nodiscard]] std::shared_ptr<TigerGoatHandle> find_tiger_goat(EngineHandle handle) const;
    [[nodiscard]] bool release_tiger_goat(EngineHandle handle);
    [[nodiscard]] bool release(EngineHandle handle);

private:
    NativeEngineRegistry() = default;

    mutable std::mutex mutex_;
    EngineHandle next_handle_{1};
    std::unordered_map<EngineHandle, std::shared_ptr<ChineseChessEngine>>
        chinese_chess_engines_;
    std::unordered_map<EngineHandle, std::shared_ptr<TigerGoatHandle>> tiger_goat_engines_;
};

}  // namespace mocs::engine
