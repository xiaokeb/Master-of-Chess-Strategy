#pragma once

#include "mocs/engine/engine_types.hpp"
#include "mocs/engine/lanzhou_mahjong_game.hpp"

#include <cstdint>
#include <optional>

namespace mocs::engine {

enum class LanzhouMahjongAutoStop : std::uint8_t {
    viewer_action, terminal, step_limit, rejected,
};

struct LanzhouMahjongAutoResult {
    LanzhouMahjongAutoStop stop{};
    std::uint16_t steps{};
};

// Operates on the native full state. The public JNI view must still hide other hands.
class LanzhouMahjongBot final {
public:
    [[nodiscard]] static std::optional<LanzhouMahjongAction> choose(
        const LanzhouMahjongGame& game, std::uint8_t player,
        Difficulty difficulty, std::uint64_t choice_seed
    );
    [[nodiscard]] static LanzhouMahjongAutoResult advance_other_seats(
        LanzhouMahjongGame& game, std::uint8_t viewer,
        Difficulty difficulty, std::uint64_t choice_seed,
        std::uint16_t step_limit = 512
    );
};

}  // namespace mocs::engine
