#pragma once

#include "competition_mahjong_round.hpp"

#include <cstdint>
#include <optional>

namespace mocs::engine {

// Baseline offline strategy. Levels 0..3 increase shape and available-tile
// awareness; none is advertised as tournament-strength Mahjong AI.
[[nodiscard]] std::optional<CompetitionMahjongRoundAction>
competition_mahjong_choose_bot_action(const CompetitionMahjongRound& round,
                                      std::uint8_t player, std::uint8_t level,
                                      std::uint64_t random_seed);

} // namespace mocs::engine
