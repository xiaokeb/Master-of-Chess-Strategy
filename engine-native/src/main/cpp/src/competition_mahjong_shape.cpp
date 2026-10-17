#include "mocs/engine/competition_mahjong_shape.hpp"

#include <array>
#include <cstdint>

namespace mocs::engine {
namespace {

using Counts = std::array<std::uint8_t, 34>;

constexpr std::array<std::array<std::uint8_t, 3>, 6> knitted_assignments{{
    {0, 1, 2}, {0, 2, 1}, {1, 0, 2},
    {1, 2, 0}, {2, 0, 1}, {2, 1, 0}
}};

[[nodiscard]] bool valid_counts(
    const Counts& counts,
    const std::uint8_t meld_count,
    const int complete_tile_count
) noexcept {
    if (meld_count > 4) return false;
    int total = 0;
    for (const auto count : counts) {
        if (count > 4) return false;
        total += count;
    }
    return total == complete_tile_count - 3 * meld_count;
}

[[nodiscard]] bool complete_standard(
    Counts& counts,
    const std::uint8_t sets_needed,
    const bool pair_used
) noexcept {
    std::uint8_t first = 34;
    for (std::uint8_t tile = 0; tile < 34; ++tile) {
        if (counts[tile] != 0) { first = tile; break; }
    }
    if (first == 34) return sets_needed == 0 && pair_used;

    if (!pair_used && counts[first] >= 2) {
        counts[first] -= 2;
        if (complete_standard(counts, sets_needed, true)) return true;
        counts[first] += 2;
    }
    if (sets_needed == 0) return false;
    if (counts[first] >= 3) {
        counts[first] -= 3;
        if (complete_standard(counts, static_cast<std::uint8_t>(sets_needed - 1), pair_used)) {
            return true;
        }
        counts[first] += 3;
    }
    if (first < 27 && first % 9 <= 6 && counts[first + 1] != 0 &&
        counts[first + 2] != 0) {
        --counts[first]; --counts[first + 1]; --counts[first + 2];
        if (complete_standard(counts, static_cast<std::uint8_t>(sets_needed - 1), pair_used)) {
            return true;
        }
        ++counts[first]; ++counts[first + 1]; ++counts[first + 2];
    }
    return false;
}

void match_knitted(
    const Counts& counts,
    const std::uint8_t meld_count,
    CompetitionMahjongShape& result
) noexcept {
    if (meld_count > 1) return;
    for (const auto& assignment : knitted_assignments) {
        auto residual = counts;
        bool complete_knitted = true;
        bool singles_match = meld_count == 0;
        for (std::uint8_t tile = 0; tile < 34; ++tile) {
            if (counts[tile] > 1) singles_match = false;
            if (tile >= 27) continue;
            const bool in_knitted = tile % 9 % 3 == assignment[tile / 9];
            if (!in_knitted) {
                if (counts[tile] != 0) singles_match = false;
            } else if (counts[tile] == 0) {
                complete_knitted = false;
            } else {
                --residual[tile];
            }
        }
        if (singles_match) {
            result.honors_and_knitted = true;
            bool seven_honors = true;
            for (std::uint8_t tile = 27; tile < 34; ++tile) {
                seven_honors = seven_honors && counts[tile] == 1;
            }
            result.greater_honors_and_knitted = seven_honors;
            result.knitted_straight = result.knitted_straight || complete_knitted;
        }
        if (complete_knitted && complete_standard(residual,
            static_cast<std::uint8_t>(1 - meld_count), false)) {
            result.knitted_straight = true;
        }
    }
}

}  // namespace

CompetitionMahjongShape competition_mahjong_shapes(
    const std::array<std::uint8_t, 34>& concealed_counts,
    const std::uint8_t exposed_meld_count
) noexcept {
    CompetitionMahjongShape result{};
    if (!valid_counts(concealed_counts, exposed_meld_count, 14)) return result;
    result.valid_input = true;
    auto working = concealed_counts;
    result.standard = complete_standard(working,
        static_cast<std::uint8_t>(4 - exposed_meld_count), false);
    match_knitted(concealed_counts, exposed_meld_count, result);
    if (exposed_meld_count != 0) return result;

    result.seven_pairs = true;
    for (const auto count : concealed_counts) {
        if (count % 2 != 0) { result.seven_pairs = false; break; }
    }
    constexpr std::array<std::uint8_t, 13> orphans{
        0, 8, 9, 17, 18, 26, 27, 28, 29, 30, 31, 32, 33
    };
    int pair_count = 0;
    result.thirteen_orphans = true;
    for (std::uint8_t tile = 0; tile < 34; ++tile) {
        bool required = false;
        for (const auto orphan : orphans) {
            if (orphan == tile) { required = true; break; }
        }
        if (required) {
            if (concealed_counts[tile] == 2) ++pair_count;
            else if (concealed_counts[tile] != 1) result.thirteen_orphans = false;
        } else if (concealed_counts[tile] != 0) {
            result.thirteen_orphans = false;
        }
    }
    result.thirteen_orphans = result.thirteen_orphans && pair_count == 1;
    return result;
}

CompetitionMahjongWaits competition_mahjong_waits(
    const std::array<std::uint8_t, 34>& concealed_counts,
    const std::uint8_t exposed_meld_count,
    const std::array<std::uint8_t, 34>& other_known_counts
) noexcept {
    CompetitionMahjongWaits result{};
    if (!valid_counts(concealed_counts, exposed_meld_count, 13)) return result;
    for (std::uint8_t tile = 0; tile < 34; ++tile) {
        if (other_known_counts[tile] > 4 ||
            concealed_counts[tile] + other_known_counts[tile] > 4) return result;
    }
    result.valid_input = true;
    for (std::uint8_t tile = 0; tile < 34; ++tile) {
        if (concealed_counts[tile] + other_known_counts[tile] == 4) continue;
        auto candidate = concealed_counts;
        ++candidate[tile];
        result.tiles[tile] = competition_mahjong_shapes(candidate, exposed_meld_count).any();
    }
    return result;
}

}  // namespace mocs::engine
