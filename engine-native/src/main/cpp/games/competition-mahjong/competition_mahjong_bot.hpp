#pragma once

#include "competition_mahjong_round.hpp"

#include <cstdint>
#include <optional>

namespace mocs::engine {

enum class CompetitionMahjongBotPolicy : std::uint8_t {
    baseline, diagnostic_v2, diagnostic_v3, diagnostic_v4, candidate_v5,
    medium_live_bucket_8, medium_live_bucket_4
};

// Baseline offline strategy. Levels 0..3 increase shape and available-tile
// awareness; none is advertised as tournament-strength Mahjong AI.
[[nodiscard]] std::optional<CompetitionMahjongRoundAction>
competition_mahjong_choose_bot_action(const CompetitionMahjongRound& round,
                                      std::uint8_t player, std::uint8_t level,
                                      std::uint64_t random_seed,
                                      CompetitionMahjongBotPolicy policy = CompetitionMahjongBotPolicy::baseline);

} // namespace mocs::engine
