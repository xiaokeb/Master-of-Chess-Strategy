#pragma once

#include "mocs/engine/go.hpp"

#include <array>
#include <cstddef>
#include <cstdint>
#include <optional>
#include <vector>

namespace mocs::engine {

// Engine player 0 is Black and player 1 is White. Setup stones are not moves.
struct GoHandicapSetup {
    std::vector<GoPoint> black_stones;
    std::uint8_t first_player;
    std::int32_t black_komi_quarters;
};

// Returns a fixed-star setup for 2-9 stones on 9, 13, or 19 lines.
// Coordinates are zero-based from the upper-left intersection.
[[nodiscard]] inline std::optional<GoHandicapSetup> make_go_handicap_setup(
    std::uint8_t board_size,
    std::uint8_t stone_count
) {
    if ((board_size != 9 && board_size != 13 && board_size != 19) ||
        stone_count < 2 || stone_count > 9) return std::nullopt;
    const auto near = static_cast<std::int32_t>(board_size == 9 ? 2 : 3);
    const auto far = static_cast<std::int32_t>(board_size - 1) - near;
    const auto middle = static_cast<std::int32_t>(board_size / 2);
    const std::array<GoPoint, 9> stars{{
        {near, far}, {far, near}, {near, near}, {far, far},
        {near, middle}, {far, middle}, {middle, far}, {middle, near},
        {middle, middle},
    }};
    GoHandicapSetup setup{{}, 1, 0};
    setup.black_stones.reserve(stone_count);
    const auto side_count = static_cast<std::size_t>(
        stone_count >= 5 && stone_count % 2 != 0 ? stone_count - 1 : stone_count);
    for (std::size_t i = 0; i < side_count; ++i) setup.black_stones.push_back(stars[i]);
    if (side_count != stone_count) setup.black_stones.push_back(stars[8]);
    return setup;
}

}  // namespace mocs::engine
