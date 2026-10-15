#pragma once

#include "mocs/engine/tiger_goat.hpp"

namespace mocs::engine::tiger_goat {

inline constexpr std::size_t official_point_count = 37;
inline constexpr std::size_t official_printed_edge_count = 76;
inline constexpr std::size_t official_orthogonal_edge_count = 52;
inline constexpr std::array<std::size_t, 2> opening_tiger_points{10, 14};
inline constexpr std::array<std::size_t, 8> opening_goat_points{
    6, 7, 8, 11, 13, 16, 17, 18,
};

// Rimau-rimau B geometry, redrawn with integer coordinates; see geometry.md.
[[nodiscard]] constexpr Topology<official_point_count> official_topology() noexcept {
    Topology<official_point_count> topology{};
    for (std::size_t point = 0; point < 25; ++point) {
        topology.points[point] = {
            static_cast<int>(point % 5), static_cast<int>(point / 5),
        };
    }
    constexpr std::array<Point, 12> mountain_points{{
        {-1, 1}, {-1, 2}, {-1, 3}, {-2, 0}, {-2, 2}, {-2, 4},
        {5, 1}, {5, 2}, {5, 3}, {6, 0}, {6, 2}, {6, 4},
    }};
    for (std::size_t point = 0; point < mountain_points.size(); ++point) {
        topology.points[25 + point] = mountain_points[point];
    }
    const auto connect = [&topology](const std::size_t from, const std::size_t to) {
        topology.connected[from][to] = topology.connected[to][from] = true;
    };
    for (std::size_t y = 0; y < 5; ++y) {
        for (std::size_t x = 0; x < 5; ++x) {
            const std::size_t point = y * 5 + x;
            if (x < 4) {
                connect(point, point + 1);
            }
            if (y < 4) {
                connect(point, point + 5);
            }
            if (y < 4 && (x + y) % 2 == 0) {
                if (x < 4) {
                    connect(point, point + 6);
                }
                if (x > 0) {
                    connect(point, point + 4);
                }
            }
        }
    }
    for (std::size_t side = 0; side < 2; ++side) {
        const std::size_t apex = side == 0 ? 10 : 14;
        const std::size_t middle = side == 0 ? 25 : 31;
        const std::size_t base = middle + 3;
        for (std::size_t row = 0; row < 3; ++row) {
            connect(apex, middle + row);
            connect(middle + row, base + row);
        }
        connect(middle, middle + 1);
        connect(middle + 1, middle + 2);
        connect(base, base + 1);
        connect(base + 1, base + 2);
    }
    return topology;
}

// Eight goats are on the board; the complete game state keeps fourteen in reserve.
[[nodiscard]] constexpr Position<official_point_count> opening_position() noexcept {
    Position<official_point_count> position{};
    for (const auto point : opening_tiger_points) {
        position.board[point] = tiger;
    }
    for (const auto point : opening_goat_points) {
        position.board[point] = goat;
    }
    position.to_move = tiger;
    return position;
}

}  // namespace mocs::engine::tiger_goat
