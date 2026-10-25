#pragma once

#include "mocs/engine/chinese_chess.hpp"
#include "mocs/engine/competition_mahjong_native_session.hpp"
#include "mocs/engine/go.hpp"
#include "mocs/engine/lanzhou_fangqi.hpp"
#include "mocs/engine/tiger_goat_game.hpp"

#include <cstdint>
#include <memory>
#include <mutex>
#include <unordered_map>

namespace mocs::engine {

using EngineHandle = std::int64_t;
namespace go_ai { class KataGoRuntime; class GoSearchRequestHandle; }

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
    [[nodiscard]] EngineHandle create_go(std::uint8_t size, std::int32_t black_komi_quarters,
                                         std::uint8_t handicap_stones = 0);
    [[nodiscard]] std::shared_ptr<GoEngine> find_go(EngineHandle handle) const;
    // JNI releases are type-scoped: a wrong game's handle must remain alive.
    [[nodiscard]] bool release_chinese_chess(EngineHandle handle);
    [[nodiscard]] bool release_go(EngineHandle handle);
    [[nodiscard]] EngineHandle create_fangqi();
    [[nodiscard]] EngineHandle create_fangqi_from_position(
        const lanzhou_fangqi::Board& board, std::uint8_t to_play);
    [[nodiscard]] std::shared_ptr<lanzhou_fangqi::Game> find_fangqi(EngineHandle handle) const;
    [[nodiscard]] bool release_fangqi(EngineHandle handle);
    [[nodiscard]] EngineHandle create_tiger_goat();
    [[nodiscard]] std::shared_ptr<TigerGoatHandle> find_tiger_goat(EngineHandle handle) const;
    [[nodiscard]] bool release_tiger_goat(EngineHandle handle);
    [[nodiscard]] EngineHandle create_competition_mahjong(
        std::uint64_t seed, std::uint8_t dealer, std::uint8_t round_wind, std::uint8_t viewer);
    [[nodiscard]] std::shared_ptr<CompetitionMahjongNativeSession> find_competition_mahjong(
        EngineHandle handle) const;
    [[nodiscard]] bool release_competition_mahjong(EngineHandle handle);
    [[nodiscard]] EngineHandle register_go_search_runtime(std::shared_ptr<go_ai::KataGoRuntime> runtime);
    [[nodiscard]] EngineHandle register_go_search_request(std::shared_ptr<go_ai::GoSearchRequestHandle> request);
    [[nodiscard]] std::shared_ptr<go_ai::KataGoRuntime> find_go_search_runtime(EngineHandle handle) const;
    [[nodiscard]] std::shared_ptr<go_ai::GoSearchRequestHandle> find_go_search_request(EngineHandle handle) const;
    [[nodiscard]] bool release_go_search_runtime(EngineHandle handle);
    [[nodiscard]] bool release_go_search_request(EngineHandle handle);
    [[nodiscard]] bool release(EngineHandle handle);

private:
    NativeEngineRegistry() = default;

    mutable std::mutex mutex_;
    EngineHandle next_handle_{1};
    std::unordered_map<EngineHandle, std::shared_ptr<ChineseChessEngine>>
        chinese_chess_engines_;
    std::unordered_map<EngineHandle, std::shared_ptr<GoEngine>> go_engines_;
    std::unordered_map<EngineHandle, std::shared_ptr<lanzhou_fangqi::Game>> fangqi_engines_;
    std::unordered_map<EngineHandle, std::shared_ptr<TigerGoatHandle>> tiger_goat_engines_;
    std::unordered_map<EngineHandle, std::shared_ptr<CompetitionMahjongNativeSession>>
        competition_mahjong_engines_;
    std::unordered_map<EngineHandle, std::shared_ptr<go_ai::KataGoRuntime>> go_search_runtimes_;
    std::unordered_map<EngineHandle, std::shared_ptr<go_ai::GoSearchRequestHandle>> go_search_requests_;
};

}  // namespace mocs::engine
