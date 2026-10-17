#pragma once

#include <array>
#include <cstdint>

namespace mocs::engine {

struct CompetitionMahjongShape {
    bool valid_input{};
    bool standard{};
    bool seven_pairs{};
    bool thirteen_orphans{};
    bool honors_and_knitted{};
    bool greater_honors_and_knitted{};
    bool knitted_straight{};

    [[nodiscard]] constexpr bool any() const noexcept {
        return valid_input && (standard || seven_pairs || thirteen_orphans ||
            honors_and_knitted || greater_honors_and_knitted || knitted_straight);
    }
};

// Concealed counts exclude flowers and tiles already represented by exposed melds.
// Meld count includes declared concealed Kongs; each Kong occupies one set.
// honors_and_knitted also matches its greater form; scoring must apply exclusions.
// Structural matching never proves the eight-point Hu requirement.
[[nodiscard]] CompetitionMahjongShape competition_mahjong_shapes(
    const std::array<std::uint8_t, 34>& concealed_counts,
    std::uint8_t exposed_meld_count
) noexcept;

struct CompetitionMahjongWaits {
    bool valid_input{};
    std::array<bool, 34> tiles{};
};

// Query a 13 - 3 * meld_count tile hand. Known tiles exclude this concealed hand
// and can include declared melds or other visible tiles, to rule out a fifth copy.
// Returned waits are structural candidates, not guaranteed eight-point wins.
[[nodiscard]] CompetitionMahjongWaits competition_mahjong_waits(
    const std::array<std::uint8_t, 34>& concealed_counts,
    std::uint8_t exposed_meld_count,
    const std::array<std::uint8_t, 34>& other_known_counts = {}
) noexcept;

}  // namespace mocs::engine
