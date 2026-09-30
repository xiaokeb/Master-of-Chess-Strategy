#pragma once

#include "mocs/engine/engine_types.hpp"
#include <cstddef>
#include <cstdint>
#include <vector>

namespace mocs::engine {

// Versioned starting profiles, not calibrated Elo or a 7:3 strength claim.
struct PikafishDifficultyProfile {
    int depth;
    std::uint64_t nodes;
    std::int64_t move_time_millis;
    std::size_t multi_pv;
    unsigned deviation_percent;
    int score_window_cp;

    [[nodiscard]] bool operator==(const PikafishDifficultyProfile& other) const noexcept {
        return depth == other.depth && nodes == other.nodes && move_time_millis == other.move_time_millis &&
            multi_pv == other.multi_pv && deviation_percent == other.deviation_percent && score_window_cp == other.score_window_cp;
    }
};

[[nodiscard]] PikafishDifficultyProfile pikafish_profile(Difficulty difficulty);
// Bounded experimental inputs; does not change the production profile table.
void validate_pikafish_profile(const PikafishDifficultyProfile& profile);

// Scores must be ordered best first and come from a completed MultiPV depth.
// Mate scores use +/-1,000,000 minus distance; winning mates are never weakened.
[[nodiscard]] std::size_t pikafish_candidate_index(
    const std::vector<int>& scores, const PikafishDifficultyProfile& profile,
    std::uint64_t random_value
);

} // namespace mocs::engine
