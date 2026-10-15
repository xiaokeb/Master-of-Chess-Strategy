#include "mocs/engine/tiger_goat_board.hpp"

namespace rules = mocs::engine::tiger_goat;

namespace {

constexpr auto topology = rules::official_topology();
constexpr auto opening = rules::opening_position();

// Independently transcribed from the source board: every printed segment occurs once.
constexpr std::array<rules::Move, 76> expected_edges{{
    {0, 1}, {1, 2}, {2, 3}, {3, 4},
    {5, 6}, {6, 7}, {7, 8}, {8, 9},
    {10, 11}, {11, 12}, {12, 13}, {13, 14},
    {15, 16}, {16, 17}, {17, 18}, {18, 19},
    {20, 21}, {21, 22}, {22, 23}, {23, 24},
    {0, 5}, {5, 10}, {10, 15}, {15, 20},
    {1, 6}, {6, 11}, {11, 16}, {16, 21},
    {2, 7}, {7, 12}, {12, 17}, {17, 22},
    {3, 8}, {8, 13}, {13, 18}, {18, 23},
    {4, 9}, {9, 14}, {14, 19}, {19, 24},
    {0, 6}, {6, 12}, {2, 6}, {6, 10},
    {2, 8}, {8, 14}, {4, 8}, {8, 12},
    {10, 16}, {16, 22}, {12, 16}, {16, 20},
    {12, 18}, {18, 24}, {14, 18}, {18, 22},
    {10, 25}, {25, 28}, {10, 26}, {26, 29}, {10, 27}, {27, 30},
    {25, 26}, {26, 27}, {28, 29}, {29, 30},
    {14, 31}, {31, 34}, {14, 32}, {32, 35}, {14, 33}, {33, 36},
    {31, 32}, {32, 33}, {34, 35}, {35, 36},
}};

constexpr std::array<rules::Point, 37> expected_points{{
    {0, 0}, {1, 0}, {2, 0}, {3, 0}, {4, 0},
    {0, 1}, {1, 1}, {2, 1}, {3, 1}, {4, 1},
    {0, 2}, {1, 2}, {2, 2}, {3, 2}, {4, 2},
    {0, 3}, {1, 3}, {2, 3}, {3, 3}, {4, 3},
    {0, 4}, {1, 4}, {2, 4}, {3, 4}, {4, 4},
    {-1, 1}, {-1, 2}, {-1, 3}, {-2, 0}, {-2, 2}, {-2, 4},
    {5, 1}, {5, 2}, {5, 3}, {6, 0}, {6, 2}, {6, 4},
}};

constexpr bool has_exact_points() {
    for (std::size_t point = 0; point < expected_points.size(); ++point) {
        if (topology.points[point].x != expected_points[point].x
            || topology.points[point].y != expected_points[point].y) {
            return false;
        }
    }
    return true;
}

constexpr bool has_exact_edges() {
    std::size_t printed = 0;
    std::size_t orthogonal = 0;
    for (std::size_t from = 0; from < rules::official_point_count; ++from) {
        for (std::size_t to = from + 1; to < rules::official_point_count; ++to) {
            if (topology.connected[from][to]) {
                ++printed;
                if (topology.points[from].x == topology.points[to].x
                    || topology.points[from].y == topology.points[to].y) {
                    ++orthogonal;
                }
            }
        }
    }
    if (printed != 76 || orthogonal != 52) {
        return false;
    }
    for (std::size_t i = 0; i < expected_edges.size(); ++i) {
        const auto edge = expected_edges[i];
        if (!topology.connected[edge.from][edge.to]) {
            return false;
        }
        for (std::size_t j = 0; j < i; ++j) {
            const auto prior = expected_edges[j];
            if ((edge.from == prior.from && edge.to == prior.to)
                || (edge.from == prior.to && edge.to == prior.from)) {
                return false;
            }
        }
    }
    return true;
}

constexpr bool has_exact_opening() {
    std::size_t tigers = 0;
    std::size_t goats = 0;
    std::size_t vacancies = 0;
    for (std::size_t point = 0; point < rules::official_point_count; ++point) {
        const bool tiger_expected = point == 10 || point == 14;
        const bool goat_expected = point == 6 || point == 7 || point == 8
            || point == 11 || point == 13 || point == 16 || point == 17 || point == 18;
        const auto expected = tiger_expected ? rules::tiger
            : goat_expected ? rules::goat : rules::empty;
        if (opening.board[point] != expected) {
            return false;
        }
        tigers += opening.board[point] == rules::tiger;
        goats += opening.board[point] == rules::goat;
        vacancies += opening.board[point] == rules::empty;
    }
    return tigers == 2 && goats == 8 && vacancies == 27 && opening.to_move == rules::tiger;
}

constexpr bool printed_edge_has_the_confirmed_piece_permissions(const rules::Move edge) {
    for (std::size_t direction = 0; direction < 2; ++direction) {
        const auto from = direction == 0 ? edge.from : edge.to;
        const auto to = direction == 0 ? edge.to : edge.from;
        rules::Position<rules::official_point_count> position{};
        std::size_t spare = 0;
        while (spare == from || spare == to) {
            ++spare;
        }
        position.board[spare] = rules::tiger;
        position.board[from] = rules::tiger;
        position.to_move = rules::tiger;
        const auto tiger_step = rules::step(topology, position, from, to);
        const bool orthogonal = topology.points[from].x == topology.points[to].x
            || topology.points[from].y == topology.points[to].y;
        if (tiger_step.error != (orthogonal ? rules::StepError::none
                                           : rules::StepError::tiger_diagonal)) {
            return false;
        }
        position.board[from] = rules::goat;
        std::size_t other = spare + 1;
        while (other == from || other == to) {
            ++other;
        }
        position.board[other] = rules::tiger;
        position.to_move = rules::goat;
        if (!rules::step(topology, position, from, to).accepted()) {
            return false;
        }
    }
    return true;
}

// Evaluate each edge separately within the compiler's normal constexpr step limit.
template <std::size_t N>
struct EdgePermissions : EdgePermissions<N - 1> {
    static_assert(printed_edge_has_the_confirmed_piece_permissions(expected_edges[N - 1]));
};

template <>
struct EdgePermissions<0> {};

static_assert(rules::official_point_count == 37);
static_assert(rules::official_printed_edge_count == 76);
static_assert(rules::official_orthogonal_edge_count == 52);
static_assert(topology.valid());
static_assert(has_exact_points());
static_assert(has_exact_edges());
static_assert(rules::valid_position(opening));
static_assert(has_exact_opening());
static_assert(sizeof(EdgePermissions<expected_edges.size()>) > 0);

// Spatial proximity is insufficient: only the printed neighbouring segments connect.
static_assert(!topology.connected[1][5]);
static_assert(!topology.connected[0][2]);
static_assert(!topology.connected[10][29]);
static_assert(!topology.connected[25][27]);
static_assert(!topology.connected[28][30]);
static_assert(!topology.connected[25][29]);
static_assert(!topology.connected[31][35]);

}  // namespace
