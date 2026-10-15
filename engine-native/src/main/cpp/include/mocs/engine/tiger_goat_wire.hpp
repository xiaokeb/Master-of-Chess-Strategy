#pragma once

#include "mocs/engine/tiger_goat_game.hpp"

#include <vector>

namespace mocs::engine::tiger_goat {

inline constexpr int wire_version = 1;
inline constexpr std::size_t snapshot_field_count = 81;

// Geometry: version, point count, edge count, xy pairs, undirected edge pairs.
[[nodiscard]] inline std::vector<int> wire_geometry() {
    const auto topology = official_topology();
    std::vector<int> fields{wire_version, static_cast<int>(official_point_count),
                            static_cast<int>(official_printed_edge_count)};
    for (const auto& point : topology.points) {
        fields.push_back(point.x);
        fields.push_back(point.y);
    }
    for (std::size_t from = 0; from < official_point_count; ++from) {
        for (std::size_t to = from + 1; to < official_point_count; ++to) {
            if (topology.connected[from][to]) {
                fields.push_back(static_cast<int>(from));
                fields.push_back(static_cast<int>(to));
            }
        }
    }
    return fields;
}

// Board[37], phase, side, reserve, captured, outcome, chain tiger, action count,
// then one visited flag per point. One call produces all fields of a snapshot.
[[nodiscard]] inline std::vector<int> wire_snapshot(const TigerGoatGame& game) {
    const auto& state = game.state();
    std::vector<int> fields(state.board.begin(), state.board.end());
    fields.insert(fields.end(), {
        static_cast<int>(state.phase), state.to_move, state.reserve, state.captured,
        static_cast<int>(state.outcome), state.chain_tiger,
        static_cast<int>(game.action_count()),
    });
    for (std::size_t point = 0; point < official_point_count; ++point) {
        fields.push_back((state.chain_visited & (std::uint64_t{1} << point)) != 0 ? 1 : 0);
    }
    return fields;
}

[[nodiscard]] inline std::vector<int> wire_legal_actions(const TigerGoatGame& game) {
    std::vector<int> fields;
    const auto actions = game.legal_actions();
    fields.reserve(actions.size() * 3);
    for (const auto& action : actions) {
        fields.insert(fields.end(), {static_cast<int>(action.kind), action.from, action.to});
    }
    return fields;
}

}  // namespace mocs::engine::tiger_goat
