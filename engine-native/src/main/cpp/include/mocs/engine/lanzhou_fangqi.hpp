#pragma once

#include <array>
#include <cstddef>
#include <cstdint>
#include <optional>
#include <string>
#include <string_view>
#include <vector>

namespace mocs::engine::lanzhou_fangqi {

inline constexpr int board_width = 7;
inline constexpr int board_height = 7;
inline constexpr std::size_t point_count = 49;
inline constexpr std::uint8_t empty = 0;
inline constexpr std::uint8_t first_player = 1;
inline constexpr std::uint8_t second_player = 2;
inline constexpr std::uint8_t red = first_player;
inline constexpr std::uint8_t black = second_player;
inline constexpr int pieces_per_player = 14;
inline constexpr std::size_t max_history_actions = 16'384;
inline constexpr std::size_t max_snapshot_bytes = 1024 * 1024;

// Count the current action and every capture still owed, without overflow or mutation.
[[nodiscard]] constexpr bool complete_turn_fits(
    const std::size_t recorded_actions,
    const int captures_after_action,
    const std::size_t limit = max_history_actions
) noexcept {
    return captures_after_action >= 0 && recorded_actions < limit
        && static_cast<std::size_t>(captures_after_action) < limit - recorded_actions;
}

using Board = std::array<std::uint8_t, point_count>;

enum class StepError : std::uint8_t {
    none,
    invalid_board,
    invalid_player,
    out_of_bounds,
    wrong_source,
    occupied_destination,
    not_adjacent,
};

struct StepResult {
    StepError error;
    Board board;

    [[nodiscard]] constexpr bool accepted() const noexcept {
        return error == StepError::none;
    }
};

struct Square {
    int x;
    int y;
    int span;
};

struct SquareList {
    // All integer spans together have 6² + 5² + ... + 1² candidate squares.
    std::array<Square, 91> squares{};
    std::size_t size{};
};

enum class RemovalError : std::uint8_t {
    none,
    invalid_board,
    invalid_player,
    out_of_bounds,
    not_opponent_piece,
};

struct RemovalResult {
    RemovalError error;
    Board board;

    [[nodiscard]] constexpr bool accepted() const noexcept {
        return error == RemovalError::none;
    }
};

[[nodiscard]] constexpr bool on_board(const int x, const int y) noexcept {
    return x >= 0 && x < board_width && y >= 0 && y < board_height;
}

[[nodiscard]] constexpr std::size_t point_index(const int x, const int y) noexcept {
    return static_cast<std::size_t>(y * board_width + x);
}

[[nodiscard]] constexpr bool valid_board(const Board& board) noexcept {
    int first_count = 0;
    int second_count = 0;
    for (const auto piece : board) {
        if (piece == first_player) {
            ++first_count;
        } else if (piece == second_player) {
            ++second_count;
        } else if (piece != empty) {
            return false;
        }
    }
    return first_count <= pieces_per_player && second_count <= pieces_per_player;
}

[[nodiscard]] constexpr int piece_count(
    const Board& board,
    const std::uint8_t player
) noexcept {
    if (player != first_player && player != second_player) {
        return 0;
    }
    int count = 0;
    for (const auto piece : board) {
        if (piece == player) {
            ++count;
        }
    }
    return count;
}

[[nodiscard]] constexpr bool has_step(
    const Board& board,
    const std::uint8_t player
) noexcept {
    if (!valid_board(board) || (player != first_player && player != second_player)) {
        return false;
    }
    for (int y = 0; y < board_height; ++y) {
        for (int x = 0; x < board_width; ++x) {
            if (board[point_index(x, y)] != player) {
                continue;
            }
            if ((x > 0 && board[point_index(x - 1, y)] == empty)
                || (x + 1 < board_width && board[point_index(x + 1, y)] == empty)
                || (y > 0 && board[point_index(x, y - 1)] == empty)
                || (y + 1 < board_height && board[point_index(x, y + 1)] == empty)) {
                return true;
            }
        }
    }
    return false;
}

// Only the four corners matter; pieces inside or on an edge do not invalidate a square.
[[nodiscard]] constexpr bool has_square(
    const Board& board,
    const std::uint8_t player,
    const int x,
    const int y,
    const int span
) noexcept {
    if ((player != first_player && player != second_player) || span < 1
        || span >= board_width || !on_board(x, y)
        || x > board_width - 1 - span || y > board_height - 1 - span) {
        return false;
    }
    return board[point_index(x, y)] == player
           && board[point_index(x + span, y)] == player
           && board[point_index(x, y + span)] == player
           && board[point_index(x + span, y + span)] == player;
}

[[nodiscard]] constexpr SquareList newly_formed_squares(
    const Board& before,
    const Board& after,
    const std::uint8_t player,
    const int span
) noexcept {
    SquareList result{};
    if (!valid_board(before) || !valid_board(after) || span < 1 || span >= board_width) {
        return result;
    }
    for (int y = 0; y + span < board_height; ++y) {
        for (int x = 0; x + span < board_width; ++x) {
            if (has_square(after, player, x, y, span)
                && !has_square(before, player, x, y, span)) {
                result.squares[result.size++] = {x, y, span};
            }
        }
    }
    return result;
}

[[nodiscard]] constexpr SquareList newly_formed_squares(
    const Board& before,
    const Board& after,
    const std::uint8_t player
) noexcept {
    SquareList result{};
    for (int span = 1; span < board_width; ++span) {
        const auto part = newly_formed_squares(before, after, player, span);
        for (std::size_t i = 0; i < part.size; ++i) {
            result.squares[result.size++] = part.squares[i];
        }
    }
    return result;
}

// This primitive does not grant capture entitlement; Game::capture checks it.
[[nodiscard]] constexpr RemovalResult remove_opponent_piece(
    const Board& board,
    const std::uint8_t player,
    const int x,
    const int y
) noexcept {
    if (!valid_board(board)) {
        return {RemovalError::invalid_board, board};
    }
    if (player != first_player && player != second_player) {
        return {RemovalError::invalid_player, board};
    }
    if (!on_board(x, y)) {
        return {RemovalError::out_of_bounds, board};
    }
    const auto opponent = player == first_player ? second_player : first_player;
    if (board[point_index(x, y)] != opponent) {
        return {RemovalError::not_opponent_piece, board};
    }
    Board next = board;
    next[point_index(x, y)] = empty;
    return {RemovalError::none, next};
}

// Geometry-only primitive. Game::move also handles turns, captures and history.
[[nodiscard]] constexpr StepResult step(
    const Board& board,
    const std::uint8_t player,
    const int from_x,
    const int from_y,
    const int to_x,
    const int to_y
) noexcept {
    if (!valid_board(board)) {
        return {StepError::invalid_board, board};
    }
    if (player != first_player && player != second_player) {
        return {StepError::invalid_player, board};
    }
    if (!on_board(from_x, from_y) || !on_board(to_x, to_y)) {
        return {StepError::out_of_bounds, board};
    }
    if (board[point_index(from_x, from_y)] != player) {
        return {StepError::wrong_source, board};
    }
    if (board[point_index(to_x, to_y)] != empty) {
        return {StepError::occupied_destination, board};
    }
    const auto dx = from_x > to_x ? from_x - to_x : to_x - from_x;
    const auto dy = from_y > to_y ? from_y - to_y : to_y - from_y;
    if (dx + dy != 1) {
        return {StepError::not_adjacent, board};
    }

    Board next = board;
    next[point_index(from_x, from_y)] = empty;
    next[point_index(to_x, to_y)] = player;
    return {StepError::none, next};
}

[[nodiscard]] constexpr Board initial_board() noexcept {
    Board board{};
    for (int y = 0; y < board_height; ++y) {
        for (int x = 0; x < board_width; ++x) {
            if (y < 2) board[point_index(x, y)] = black;
            if (y > 4) board[point_index(x, y)] = red;
        }
    }
    return board;
}

enum class Outcome : std::uint8_t { ongoing, red_wins, black_wins };
enum class ActionKind : std::uint8_t { move, capture };
enum class ActionError : std::uint8_t {
    none,
    game_over,
    wrong_turn,
    out_of_bounds,
    wrong_source,
    occupied_destination,
    not_adjacent,
    capture_required,
    no_capture_entitlement,
    not_opponent_piece,
    repeated_position,
    resource_limit,
};

struct Position {
    Board board{};
    std::uint8_t to_play{red};
    int captures_remaining{};
    Outcome outcome{Outcome::ongoing};
};

// Point indices are row-major. A capture uses from == to == the selected enemy point.
struct Action {
    ActionKind kind{ActionKind::move};
    std::uint8_t player{red};
    int from{};
    int to{};
};

struct ActionResult {
    ActionError error{ActionError::none};
    SquareList formed{};
    [[nodiscard]] bool accepted() const noexcept { return error == ActionError::none; }
};

enum class RestoreError : std::uint8_t {
    none, malformed, unsupported_version, invalid_initial_position,
    illegal_action, resource_limit,
};

class Game {
public:
    Game();
    // Validated, non-capturing seed for puzzles. Terminal seeds are permitted.
    [[nodiscard]] static std::optional<Game> from_position(
        const Board& board, std::uint8_t to_play
    );
    [[nodiscard]] const Position& position() const noexcept { return current_; }
    [[nodiscard]] const std::vector<Action>& actions() const noexcept { return actions_; }
    [[nodiscard]] std::size_t history_size() const noexcept { return history_.size(); }
    [[nodiscard]] std::vector<Action> legal_actions() const;
    ActionResult move(std::uint8_t player, int from_x, int from_y, int to_x, int to_y);
    ActionResult capture(std::uint8_t player, int x, int y);
    ActionResult apply(const Action& action);
    // Undo the latest turn, including every selected capture or its pending remainder.
    bool undo();
    [[nodiscard]] std::string serialize() const;
    [[nodiscard]] static std::optional<Game> deserialize(
        std::string_view snapshot, RestoreError& error
    );

private:
    Game(const Board& board, std::uint8_t to_play);
    [[nodiscard]] bool repeated(const Position& candidate) const noexcept;
    ActionResult preview(const Action& action, Position& next) const;
    ActionResult apply_impl(const Action& action, bool reserve_complete_turn);
    void adjudicate();
    Position initial_{};
    Position current_{};
    std::vector<Position> history_;
    std::vector<Action> actions_;
};

}  // namespace mocs::engine::lanzhou_fangqi
