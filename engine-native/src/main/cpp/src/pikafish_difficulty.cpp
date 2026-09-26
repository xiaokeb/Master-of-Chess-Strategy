#include "mocs/engine/pikafish_difficulty.hpp"
#include <algorithm>
#include <stdexcept>

namespace mocs::engine {

PikafishDifficultyProfile pikafish_profile(const Difficulty difficulty) {
    switch (difficulty) {
        case Difficulty::easy: return {2, 2'000, 250, 8, 75, 250};
        case Difficulty::medium: return {5, 12'000, 500, 4, 20, 80};
        case Difficulty::hard: return {10, 80'000, 900, 1, 0, 0};
        case Difficulty::master: return {0, 0, 1'200, 1, 0, 0};
    }
    throw std::invalid_argument("Unsupported Pikafish difficulty");
}

std::size_t pikafish_candidate_index(
    const std::vector<int>& scores, const PikafishDifficultyProfile& profile,
    const std::uint64_t random_value
) {
    if (scores.empty()) throw std::invalid_argument("No Pikafish candidates");
    if (scores.front() >= 900'000 || random_value % 100 >= profile.deviation_percent) return 0;
    std::size_t count = 1;
    while (count < std::min(scores.size(), profile.multi_pv) &&
           scores[count] > -900'000 &&
           static_cast<std::int64_t>(scores.front()) - scores[count] <= profile.score_window_cp) {
        ++count;
    }
    return count == 1 ? 0 : 1 + (random_value / 100) % (count - 1);
}

} // namespace mocs::engine
