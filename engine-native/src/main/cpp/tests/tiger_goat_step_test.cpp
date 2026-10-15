#include "mocs/engine/tiger_goat.hpp"

namespace rules = mocs::engine::tiger_goat;

constexpr std::size_t node_count = 30;

constexpr rules::Topology<node_count> sample_topology() {
    rules::Topology<node_count> topology{};
    for (std::size_t i = 0; i < node_count; ++i) {
        topology.points[i] = {static_cast<int>(i % 5), static_cast<int>(i / 5)};
    }
    topology.connected[0][1] = topology.connected[1][0] = true;
    topology.connected[0][5] = topology.connected[5][0] = true;
    topology.connected[0][6] = topology.connected[6][0] = true;
    topology.connected[1][2] = topology.connected[2][1] = true;
    return topology;
}

constexpr rules::Position<node_count> sample_position() {
    rules::Position<node_count> position{};
    position.board[0] = rules::tiger;
    position.board[29] = rules::tiger;
    position.board[1] = rules::goat;
    for (std::size_t i = 7; i <= 27; ++i) {
        position.board[i] = rules::goat;
    }
    position.to_move = rules::goat;
    return position;
}

constexpr auto topology = sample_topology();
constexpr auto position = sample_position();
static_assert(topology.valid());
static_assert(rules::valid_position(position));

constexpr auto goat_step = rules::step(topology, position, 1, 2);
static_assert(goat_step.accepted());
static_assert(goat_step.position.board[1] == rules::empty);
static_assert(goat_step.position.board[2] == rules::goat);
static_assert(goat_step.position.to_move == rules::tiger);
static_assert(position.board[1] == rules::goat);

constexpr auto goat_options = rules::legal_steps(topology, position);
static_assert(goat_options.accepted());
static_assert(goat_options.count == 1);
static_assert(goat_options.moves[0].from == 1 && goat_options.moves[0].to == 2);

constexpr auto tiger_step = rules::step(topology, goat_step.position, 0, 5);
static_assert(tiger_step.accepted());
static_assert(tiger_step.position.board[5] == rules::tiger);
static_assert(tiger_step.position.to_move == rules::goat);

constexpr auto tiger_options = rules::legal_steps(topology, goat_step.position);
static_assert(tiger_options.accepted());
static_assert(tiger_options.count == 2);
static_assert(tiger_options.moves[0].from == 0 && tiger_options.moves[0].to == 1);
static_assert(tiger_options.moves[1].from == 0 && tiger_options.moves[1].to == 5);

static_assert(rules::step(topology, position, 0, 5).error == rules::StepError::wrong_source);
static_assert(rules::step(topology, position, 1, 0).error
              == rules::StepError::occupied_destination);
static_assert(rules::step(topology, position, 1, 5).error == rules::StepError::not_connected);
static_assert(rules::step(topology, position, 1, 30).error == rules::StepError::out_of_bounds);
static_assert(rules::step(topology, goat_step.position, 0, 6).error
              == rules::StepError::tiger_diagonal);

constexpr bool rejects_invalid_topology() {
    auto invalid = topology;
    invalid.connected[1][0] = false;
    return rules::step(invalid, position, 1, 2).error == rules::StepError::invalid_topology;
}
static_assert(rejects_invalid_topology());

constexpr bool rejects_invalid_topology_in_list() {
    auto invalid = topology;
    invalid.connected[1][0] = false;
    const auto options = rules::legal_steps(invalid, position);
    return options.error == rules::StepError::invalid_topology && options.count == 0;
}
static_assert(rejects_invalid_topology_in_list());

constexpr bool rejects_invalid_position() {
    auto invalid = position;
    invalid.board[3] = 3;
    return rules::step(topology, invalid, 1, 2).error == rules::StepError::invalid_position;
}
static_assert(rejects_invalid_position());

constexpr bool rejects_invalid_position_in_list() {
    auto invalid = position;
    invalid.board[3] = 3;
    const auto options = rules::legal_steps(topology, invalid);
    return options.error == rules::StepError::invalid_position && options.count == 0;
}
static_assert(rejects_invalid_position_in_list());

constexpr bool reports_no_ordinary_steps() {
    auto isolated = topology;
    for (std::size_t i = 0; i < node_count; ++i) {
        isolated.connected[0][i] = false;
        isolated.connected[i][0] = false;
    }
    auto tiger_turn = position;
    tiger_turn.to_move = rules::tiger;
    const auto options = rules::legal_steps(isolated, tiger_turn);
    return options.accepted() && options.count == 0;
}
static_assert(reports_no_ordinary_steps());

constexpr bool rejects_excess_goats() {
    auto invalid = position;
    invalid.board[3] = rules::goat;
    return !rules::valid_position(invalid);
}
static_assert(rejects_excess_goats());

constexpr bool rejects_missing_tiger() {
    auto invalid = position;
    invalid.board[29] = rules::empty;
    return !rules::valid_position(invalid);
}
static_assert(rejects_missing_tiger());

constexpr bool rejects_unspecified_turn() {
    auto invalid = position;
    invalid.to_move = rules::empty;
    return !rules::valid_position(invalid);
}
static_assert(rejects_unspecified_turn());
