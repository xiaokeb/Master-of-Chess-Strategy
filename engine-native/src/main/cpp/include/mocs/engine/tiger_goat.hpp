#pragma once

#include <array>
#include <cstddef>
#include <cstdint>

namespace mocs::engine::tiger_goat {

inline constexpr std::uint8_t empty = 0;
inline constexpr std::uint8_t tiger = 1;
inline constexpr std::uint8_t goat = 2;
inline constexpr int tiger_count = 2;
inline constexpr int initial_goat_count = 22;

struct Point {
    int x;
    int y;
};

template <std::size_t N>
struct Topology {
    std::array<Point, N> points{};
    std::array<std::array<bool, N>, N> connected{};

    [[nodiscard]] constexpr bool valid() const noexcept {
        if (N < static_cast<std::size_t>(tiger_count + initial_goat_count)) {
            return false;
        }
        for (std::size_t from = 0; from < N; ++from) {
            if (connected[from][from]) {
                return false;
            }
            for (std::size_t to = 0; to < N; ++to) {
                if (connected[from][to] != connected[to][from]) {
                    return false;
                }
                if (from != to && points[from].x == points[to].x
                    && points[from].y == points[to].y) {
                    return false;
                }
            }
        }
        return true;
    }
};

template <std::size_t N>
struct Position {
    std::array<std::uint8_t, N> board{};
    std::uint8_t to_move = empty;
};

enum class StepError : std::uint8_t {
    none,
    invalid_topology,
    invalid_position,
    out_of_bounds,
    wrong_source,
    occupied_destination,
    not_connected,
    tiger_diagonal,
};

template <std::size_t N>
struct StepResult {
    StepError error;
    Position<N> position;

    [[nodiscard]] constexpr bool accepted() const noexcept {
        return error == StepError::none;
    }
};

struct Move {
    std::size_t from;
    std::size_t to;
};

template <std::size_t N>
struct StepList {
    StepError error;
    std::array<Move, N * N> moves{};
    std::size_t count = 0;

    [[nodiscard]] constexpr bool accepted() const noexcept {
        return error == StepError::none;
    }
};

template <std::size_t N>
[[nodiscard]] constexpr bool valid_position(const Position<N>& position) noexcept {
    if (position.to_move != tiger && position.to_move != goat) {
        return false;
    }
    int tigers = 0;
    int goats = 0;
    for (const auto piece : position.board) {
        if (piece == tiger) {
            ++tigers;
        } else if (piece == goat) {
            ++goats;
        } else if (piece != empty) {
            return false;
        }
    }
    return tigers == tiger_count && goats <= initial_goat_count;
}

template <std::size_t N>
[[nodiscard]] constexpr bool tiger_moves_diagonally(
    const Topology<N>& topology,
    const Position<N>& position,
    const std::size_t from,
    const std::size_t to
) noexcept {
    return position.to_move == tiger && topology.points[from].x != topology.points[to].x
           && topology.points[from].y != topology.points[to].y;
}

// Only ordinary steps are enumerated; a tiger may still have a capture available.
template <std::size_t N>
[[nodiscard]] constexpr StepList<N> legal_steps(
    const Topology<N>& topology,
    const Position<N>& position
) noexcept {
    StepList<N> result{StepError::none};
    if (!topology.valid()) {
        result.error = StepError::invalid_topology;
        return result;
    }
    if (!valid_position(position)) {
        result.error = StepError::invalid_position;
        return result;
    }
    for (std::size_t from = 0; from < N; ++from) {
        if (position.board[from] != position.to_move) {
            continue;
        }
        for (std::size_t to = 0; to < N; ++to) {
            if (position.board[to] == empty && topology.connected[from][to]
                && !tiger_moves_diagonally(topology, position, from, to)) {
                result.moves[result.count++] = {from, to};
            }
        }
    }
    return result;
}

// Movement-phase single steps only; captures, setup and terminal states are separate slices.
template <std::size_t N>
[[nodiscard]] constexpr StepResult<N> step(
    const Topology<N>& topology,
    const Position<N>& position,
    const std::size_t from,
    const std::size_t to
) noexcept {
    if (!topology.valid()) {
        return {StepError::invalid_topology, position};
    }
    if (!valid_position(position)) {
        return {StepError::invalid_position, position};
    }
    if (from >= N || to >= N) {
        return {StepError::out_of_bounds, position};
    }
    if (position.board[from] != position.to_move) {
        return {StepError::wrong_source, position};
    }
    if (position.board[to] != empty) {
        return {StepError::occupied_destination, position};
    }
    if (!topology.connected[from][to]) {
        return {StepError::not_connected, position};
    }
    if (tiger_moves_diagonally(topology, position, from, to)) {
        return {StepError::tiger_diagonal, position};
    }

    Position<N> next = position;
    next.board[to] = next.board[from];
    next.board[from] = empty;
    next.to_move = position.to_move == goat ? tiger : goat;
    return {StepError::none, next};
}

}  // namespace mocs::engine::tiger_goat
