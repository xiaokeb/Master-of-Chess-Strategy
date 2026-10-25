#include "mocs/engine/lanzhou_fangqi.hpp"

namespace fangqi = mocs::engine::lanzhou_fangqi;

constexpr fangqi::Board board_with_one_piece() {
    fangqi::Board board{};
    board[fangqi::point_index(3, 3)] = fangqi::first_player;
    return board;
}

constexpr auto starting_board = board_with_one_piece();
static_assert(fangqi::on_board(0, 0));
static_assert(fangqi::on_board(6, 6));
static_assert(!fangqi::on_board(7, 0));
static_assert(!fangqi::on_board(0, -1));
static_assert(fangqi::valid_board(starting_board));

constexpr auto legal = fangqi::step(starting_board, fangqi::first_player, 3, 3, 3, 4);
static_assert(legal.accepted());
static_assert(legal.board[fangqi::point_index(3, 3)] == fangqi::empty);
static_assert(legal.board[fangqi::point_index(3, 4)] == fangqi::first_player);
static_assert(starting_board[fangqi::point_index(3, 3)] == fangqi::first_player);

static_assert(fangqi::step(starting_board, fangqi::first_player, 3, 3, 4, 4).error
              == fangqi::StepError::not_adjacent);
static_assert(fangqi::step(starting_board, fangqi::first_player, 3, 3, 5, 3).error
              == fangqi::StepError::not_adjacent);
static_assert(fangqi::step(starting_board, fangqi::second_player, 3, 3, 3, 4).error
              == fangqi::StepError::wrong_source);
static_assert(fangqi::step(starting_board, fangqi::first_player, 3, 3, 3, 3).error
              == fangqi::StepError::occupied_destination);
static_assert(fangqi::step(starting_board, fangqi::first_player, 3, 3, 7, 3).error
              == fangqi::StepError::out_of_bounds);
static_assert(fangqi::step(starting_board, 0, 3, 3, 3, 4).error
              == fangqi::StepError::invalid_player);

constexpr bool rejects_occupied_neighbor() {
    auto board = starting_board;
    board[fangqi::point_index(4, 3)] = fangqi::second_player;
    return fangqi::step(board, fangqi::first_player, 3, 3, 4, 3).error
           == fangqi::StepError::occupied_destination;
}
static_assert(rejects_occupied_neighbor());

constexpr bool accepts_edge_step() {
    fangqi::Board board{};
    board[fangqi::point_index(0, 0)] = fangqi::first_player;
    return fangqi::step(board, fangqi::first_player, 0, 0, 1, 0).accepted();
}
static_assert(accepts_edge_step());

constexpr bool rejects_excess_pieces() {
    fangqi::Board board{};
    for (int x = 0; x < 15; ++x) {
        board[static_cast<std::size_t>(x)] = fangqi::first_player;
    }
    return !fangqi::valid_board(board);
}
static_assert(rejects_excess_pieces());

constexpr bool rejects_step_on_invalid_board() {
    fangqi::Board board{};
    board[0] = 3;
    return fangqi::step(board, fangqi::first_player, 0, 0, 1, 0).error
           == fangqi::StepError::invalid_board;
}
static_assert(rejects_step_on_invalid_board());

constexpr bool rejects_unknown_piece() {
    fangqi::Board board{};
    board[0] = 3;
    return !fangqi::valid_board(board);
}
static_assert(rejects_unknown_piece());

constexpr bool detects_two_new_unit_squares() {
    fangqi::Board board{};
    board[fangqi::point_index(1, 3)] = fangqi::first_player;
    board[fangqi::point_index(0, 1)] = fangqi::first_player;
    board[fangqi::point_index(1, 1)] = fangqi::first_player;
    board[fangqi::point_index(2, 1)] = fangqi::first_player;
    board[fangqi::point_index(0, 2)] = fangqi::first_player;
    board[fangqi::point_index(2, 2)] = fangqi::first_player;
    const auto moved = fangqi::step(board, fangqi::first_player, 1, 3, 1, 2);
    const auto squares = fangqi::newly_formed_squares(
        board, moved.board, fangqi::first_player, 1
    );
    return moved.accepted() && squares.size == 2
           && squares.squares[0].x == 0 && squares.squares[0].y == 1
           && squares.squares[1].x == 1 && squares.squares[1].y == 1;
}
static_assert(detects_two_new_unit_squares());

constexpr bool distinguishes_square_span() {
    fangqi::Board board{};
    board[fangqi::point_index(0, 0)] = fangqi::first_player;
    board[fangqi::point_index(2, 0)] = fangqi::first_player;
    board[fangqi::point_index(0, 2)] = fangqi::first_player;
    board[fangqi::point_index(2, 2)] = fangqi::first_player;
    return fangqi::has_square(board, fangqi::first_player, 0, 0, 2)
           && !fangqi::has_square(board, fangqi::first_player, 0, 0, 1)
           && !fangqi::has_square(board, fangqi::first_player, 0, 0, 2147483647);
}
static_assert(distinguishes_square_span());

constexpr bool detects_blocked_player() {
    fangqi::Board board{};
    board[fangqi::point_index(3, 3)] = fangqi::first_player;
    board[fangqi::point_index(2, 3)] = fangqi::second_player;
    board[fangqi::point_index(4, 3)] = fangqi::second_player;
    board[fangqi::point_index(3, 2)] = fangqi::second_player;
    board[fangqi::point_index(3, 4)] = fangqi::second_player;
    return fangqi::piece_count(board, fangqi::first_player) == 1
           && !fangqi::has_step(board, fangqi::first_player)
           && fangqi::has_step(board, fangqi::second_player);
}
static_assert(detects_blocked_player());

constexpr bool removal_requires_opponent() {
    fangqi::Board board{};
    board[fangqi::point_index(3, 3)] = fangqi::first_player;
    board[fangqi::point_index(4, 3)] = fangqi::second_player;
    const auto removed = fangqi::remove_opponent_piece(board, fangqi::first_player, 4, 3);
    return removed.accepted()
           && removed.board[fangqi::point_index(4, 3)] == fangqi::empty
           && board[fangqi::point_index(4, 3)] == fangqi::second_player
           && fangqi::remove_opponent_piece(board, fangqi::first_player, 3, 3).error
                  == fangqi::RemovalError::not_opponent_piece
           && fangqi::remove_opponent_piece(board, fangqi::first_player, 7, 3).error
                  == fangqi::RemovalError::out_of_bounds;
}
static_assert(removal_requires_opponent());

constexpr bool formal_initial_rows() {
    const auto board = fangqi::initial_board();
    return fangqi::valid_board(board)
        && fangqi::piece_count(board, fangqi::red) == 14
        && fangqi::piece_count(board, fangqi::black) == 14
        && board[fangqi::point_index(6, 1)] == fangqi::black
        && board[fangqi::point_index(0, 5)] == fangqi::red
        && board[fangqi::point_index(3, 3)] == fangqi::empty;
}
static_assert(formal_initial_rows());

constexpr bool detects_all_integer_spans() {
    fangqi::Board before{};
    before[fangqi::point_index(0, 0)] = fangqi::red;
    before[fangqi::point_index(2, 0)] = fangqi::red;
    before[fangqi::point_index(0, 2)] = fangqi::red;
    before[fangqi::point_index(2, 3)] = fangqi::red;
    const auto moved = fangqi::step(before, fangqi::red, 2, 3, 2, 2);
    const auto formed = fangqi::newly_formed_squares(before, moved.board, fangqi::red);
    return formed.size == 1 && formed.squares[0].span == 2;
}
static_assert(detects_all_integer_spans());
