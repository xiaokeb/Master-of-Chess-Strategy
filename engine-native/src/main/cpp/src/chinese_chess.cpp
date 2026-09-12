#include "mocs/engine/chinese_chess.hpp"

#include <algorithm>
#include <array>
#include <cmath>
#include <utility>

namespace mocs::engine {
namespace {

constexpr std::array<std::uint8_t, 4> position_magic{
    'M',
    'O',
    'C',
    'X',
};
constexpr std::uint8_t position_version = 1;
constexpr std::size_t position_header_size = 6;

}  // namespace

EngineAction make_board_move(
    const std::int32_t from_x,
    const std::int32_t from_y,
    const std::int32_t to_x,
    const std::int32_t to_y
) noexcept {
    return EngineAction{
        board_move_action_kind,
        {from_x, from_y, to_x, to_y},
    };
}

ChineseChessEngine::ChineseChessEngine() {
    reset();
}

GameType ChineseChessEngine::game_type() const noexcept {
    return GameType::chinese_chess;
}

std::uint8_t ChineseChessEngine::current_player() const noexcept {
    return static_cast<std::uint8_t>(current_side_);
}

void ChineseChessEngine::reset() {
    board_.fill(std::nullopt);
    history_.clear();
    current_side_ = Side::red;

    constexpr std::array<PieceType, board_width> back_rank{
        PieceType::chariot,
        PieceType::horse,
        PieceType::elephant,
        PieceType::advisor,
        PieceType::general,
        PieceType::advisor,
        PieceType::elephant,
        PieceType::horse,
        PieceType::chariot,
    };

    for (std::int32_t x = 0; x < board_width; ++x) {
        board_[index(x, 0)] = Piece{back_rank[static_cast<std::size_t>(x)], Side::black};
        board_[index(x, 9)] = Piece{back_rank[static_cast<std::size_t>(x)], Side::red};
    }

    for (const auto x : {1, 7}) {
        board_[index(x, 2)] = Piece{PieceType::cannon, Side::black};
        board_[index(x, 7)] = Piece{PieceType::cannon, Side::red};
    }

    for (const auto x : {0, 2, 4, 6, 8}) {
        board_[index(x, 3)] = Piece{PieceType::soldier, Side::black};
        board_[index(x, 6)] = Piece{PieceType::soldier, Side::red};
    }
}

ActionResult ChineseChessEngine::apply(const EngineAction& action) {
    if (action.kind != board_move_action_kind) {
        return ActionResult{false, EngineError::unsupported};
    }

    const auto [from_x, from_y, to_x, to_y] = action.arguments;
    if (!is_legal_move(from_x, from_y, to_x, to_y)) {
        return ActionResult{false, EngineError::illegal_action};
    }

    const auto from = index(from_x, from_y);
    const auto to = index(to_x, to_y);
    const auto moving = *board_[from];
    history_.push_back(MoveRecord{
        from_x,
        from_y,
        to_x,
        to_y,
        moving,
        board_[to],
        current_side_,
    });

    board_[to] = moving;
    board_[from].reset();
    current_side_ = opposite(current_side_);
    return ActionResult{true, EngineError::none};
}

bool ChineseChessEngine::undo() {
    if (history_.empty()) {
        return false;
    }

    const auto move = history_.back();
    history_.pop_back();
    board_[index(move.from_x, move.from_y)] = move.moved;
    board_[index(move.to_x, move.to_y)] = move.captured;
    current_side_ = move.previous_side;
    return true;
}

std::vector<EngineAction> ChineseChessEngine::legal_actions() const {
    std::vector<EngineAction> actions;
    for (std::int32_t from_y = 0; from_y < board_height; ++from_y) {
        for (std::int32_t from_x = 0; from_x < board_width; ++from_x) {
            const auto piece = board_[index(from_x, from_y)];
            if (!piece || piece->side != current_side_) {
                continue;
            }
            for (std::int32_t to_y = 0; to_y < board_height; ++to_y) {
                for (std::int32_t to_x = 0; to_x < board_width; ++to_x) {
                    if (is_legal_move(from_x, from_y, to_x, to_y)) {
                        actions.push_back(
                            make_board_move(from_x, from_y, to_x, to_y)
                        );
                    }
                }
            }
        }
    }
    return actions;
}

GameResult ChineseChessEngine::game_result() const noexcept {
    if (!has_general(Side::red)) {
        return GameResult::second_player_win;
    }
    if (!has_general(Side::black)) {
        return GameResult::first_player_win;
    }
    if (has_legal_action()) {
        return GameResult::ongoing;
    }
    return current_side_ == Side::red
        ? GameResult::second_player_win
        : GameResult::first_player_win;
}

std::vector<std::uint8_t> ChineseChessEngine::serialize() const {
    std::vector<std::uint8_t> data;
    data.reserve(position_header_size + board_size);
    data.insert(data.end(), position_magic.begin(), position_magic.end());
    data.push_back(position_version);
    data.push_back(static_cast<std::uint8_t>(current_side_));

    for (const auto& piece : board_) {
        if (!piece) {
            data.push_back(0);
            continue;
        }
        const auto side_bit = piece->side == Side::black
            ? static_cast<std::uint8_t>(0x80)
            : static_cast<std::uint8_t>(0);
        data.push_back(
            side_bit | static_cast<std::uint8_t>(piece->type)
        );
    }
    return data;
}

RestoreResult ChineseChessEngine::restore(
    const std::vector<std::uint8_t>& data
) {
    if (data.size() != position_header_size + board_size) {
        return RestoreResult{false, EngineError::corrupted_data};
    }
    if (!std::equal(position_magic.begin(), position_magic.end(), data.begin())) {
        return RestoreResult{false, EngineError::corrupted_data};
    }
    if (data[4] != position_version) {
        return RestoreResult{false, EngineError::unsupported};
    }
    if (data[5] > static_cast<std::uint8_t>(Side::black)) {
        return RestoreResult{false, EngineError::corrupted_data};
    }

    Board restored{};
    std::array<std::int32_t, 2> general_counts{};
    for (std::size_t square = 0; square < board_size; ++square) {
        const auto encoded = data[position_header_size + square];
        if (encoded == 0) {
            continue;
        }
        if ((encoded & 0x78U) != 0) {
            return RestoreResult{false, EngineError::corrupted_data};
        }

        const auto type_code = static_cast<std::uint8_t>(encoded & 0x07U);
        if (type_code < static_cast<std::uint8_t>(PieceType::general) ||
            type_code > static_cast<std::uint8_t>(PieceType::soldier)) {
            return RestoreResult{false, EngineError::corrupted_data};
        }

        const auto side = (encoded & 0x80U) == 0 ? Side::red : Side::black;
        const auto type = static_cast<PieceType>(type_code);
        restored[square] = Piece{type, side};
        if (type == PieceType::general) {
            ++general_counts[static_cast<std::size_t>(side)];
        }
    }

    if (general_counts[0] > 1 ||
        general_counts[1] > 1 ||
        general_counts[0] + general_counts[1] == 0) {
        return RestoreResult{false, EngineError::invalid_state};
    }

    board_ = restored;
    current_side_ = static_cast<Side>(data[5]);
    history_.clear();
    return RestoreResult{true, EngineError::none};
}

std::optional<Piece> ChineseChessEngine::piece_at(
    const std::int32_t x,
    const std::int32_t y
) const noexcept {
    if (!is_inside(x, y)) {
        return std::nullopt;
    }
    return board_[index(x, y)];
}

bool ChineseChessEngine::is_in_check(const Side side) const noexcept {
    return is_in_check(board_, side);
}

bool ChineseChessEngine::is_inside(
    const std::int32_t x,
    const std::int32_t y
) noexcept {
    return x >= 0 && x < board_width && y >= 0 && y < board_height;
}

std::size_t ChineseChessEngine::index(
    const std::int32_t x,
    const std::int32_t y
) noexcept {
    return static_cast<std::size_t>(y * board_width + x);
}

Side ChineseChessEngine::opposite(const Side side) noexcept {
    return side == Side::red ? Side::black : Side::red;
}

bool ChineseChessEngine::is_in_palace(
    const Side side,
    const std::int32_t x,
    const std::int32_t y
) noexcept {
    if (x < 3 || x > 5) {
        return false;
    }
    return side == Side::red ? y >= 7 && y <= 9 : y >= 0 && y <= 2;
}

std::int32_t ChineseChessEngine::path_blockers(
    const Board& board,
    const std::int32_t from_x,
    const std::int32_t from_y,
    const std::int32_t to_x,
    const std::int32_t to_y
) noexcept {
    if (from_x != to_x && from_y != to_y) {
        return -1;
    }

    const auto step_x = (to_x > from_x) - (to_x < from_x);
    const auto step_y = (to_y > from_y) - (to_y < from_y);
    auto x = from_x + step_x;
    auto y = from_y + step_y;
    std::int32_t blockers = 0;
    while (x != to_x || y != to_y) {
        if (board[index(x, y)]) {
            ++blockers;
        }
        x += step_x;
        y += step_y;
    }
    return blockers;
}

bool ChineseChessEngine::piece_attacks_square(
    const Board& board,
    const Piece& piece,
    const std::int32_t from_x,
    const std::int32_t from_y,
    const std::int32_t to_x,
    const std::int32_t to_y
) noexcept {
    const auto dx = to_x - from_x;
    const auto dy = to_y - from_y;
    const auto absolute_x = std::abs(dx);
    const auto absolute_y = std::abs(dy);
    const auto target = board[index(to_x, to_y)];

    switch (piece.type) {
        case PieceType::general:
            if (target &&
                target->type == PieceType::general &&
                target->side != piece.side &&
                from_x == to_x) {
                return path_blockers(board, from_x, from_y, to_x, to_y) == 0;
            }
            return absolute_x + absolute_y == 1 &&
                is_in_palace(piece.side, to_x, to_y);

        case PieceType::advisor:
            return absolute_x == 1 &&
                absolute_y == 1 &&
                is_in_palace(piece.side, to_x, to_y);

        case PieceType::elephant: {
            if (absolute_x != 2 || absolute_y != 2) {
                return false;
            }
            const auto stays_home = piece.side == Side::red
                ? to_y >= 5
                : to_y <= 4;
            const auto eye_x = from_x + dx / 2;
            const auto eye_y = from_y + dy / 2;
            return stays_home && !board[index(eye_x, eye_y)];
        }

        case PieceType::horse: {
            if (!((absolute_x == 2 && absolute_y == 1) ||
                  (absolute_x == 1 && absolute_y == 2))) {
                return false;
            }
            const auto leg_x = absolute_x == 2
                ? from_x + (dx > 0 ? 1 : -1)
                : from_x;
            const auto leg_y = absolute_y == 2
                ? from_y + (dy > 0 ? 1 : -1)
                : from_y;
            return !board[index(leg_x, leg_y)];
        }

        case PieceType::chariot:
            return path_blockers(board, from_x, from_y, to_x, to_y) == 0;

        case PieceType::cannon: {
            const auto blockers =
                path_blockers(board, from_x, from_y, to_x, to_y);
            return target ? blockers == 1 : blockers == 0;
        }

        case PieceType::soldier: {
            const auto forward = piece.side == Side::red ? -1 : 1;
            if (dx == 0 && dy == forward) {
                return true;
            }
            const auto crossed_river = piece.side == Side::red
                ? from_y <= 4
                : from_y >= 5;
            return crossed_river && absolute_x == 1 && dy == 0;
        }
    }
    return false;
}

bool ChineseChessEngine::is_in_check(
    const Board& board,
    const Side side
) noexcept {
    std::optional<std::pair<std::int32_t, std::int32_t>> general;
    for (std::int32_t y = 0; y < board_height && !general; ++y) {
        for (std::int32_t x = 0; x < board_width; ++x) {
            const auto piece = board[index(x, y)];
            if (piece &&
                piece->side == side &&
                piece->type == PieceType::general) {
                general = std::pair{x, y};
                break;
            }
        }
    }
    if (!general) {
        return true;
    }

    const auto [general_x, general_y] = *general;
    for (std::int32_t y = 0; y < board_height; ++y) {
        for (std::int32_t x = 0; x < board_width; ++x) {
            const auto piece = board[index(x, y)];
            if (piece &&
                piece->side != side &&
                piece_attacks_square(
                    board,
                    *piece,
                    x,
                    y,
                    general_x,
                    general_y
                )) {
                return true;
            }
        }
    }
    return false;
}

bool ChineseChessEngine::is_legal_move(
    const std::int32_t from_x,
    const std::int32_t from_y,
    const std::int32_t to_x,
    const std::int32_t to_y
) const noexcept {
    if (!is_inside(from_x, from_y) ||
        !is_inside(to_x, to_y) ||
        (from_x == to_x && from_y == to_y)) {
        return false;
    }

    const auto moving = board_[index(from_x, from_y)];
    const auto target = board_[index(to_x, to_y)];
    if (!moving ||
        moving->side != current_side_ ||
        (target && target->side == moving->side) ||
        !piece_attacks_square(
            board_,
            *moving,
            from_x,
            from_y,
            to_x,
            to_y
        )) {
        return false;
    }

    auto next = board_;
    next[index(to_x, to_y)] = moving;
    next[index(from_x, from_y)].reset();
    return !is_in_check(next, moving->side);
}

bool ChineseChessEngine::has_general(const Side side) const noexcept {
    return std::any_of(
        board_.begin(),
        board_.end(),
        [side](const auto& piece) {
            return piece &&
                piece->side == side &&
                piece->type == PieceType::general;
        }
    );
}

bool ChineseChessEngine::has_legal_action() const noexcept {
    for (std::int32_t from_y = 0; from_y < board_height; ++from_y) {
        for (std::int32_t from_x = 0; from_x < board_width; ++from_x) {
            const auto piece = board_[index(from_x, from_y)];
            if (!piece || piece->side != current_side_) {
                continue;
            }
            for (std::int32_t to_y = 0; to_y < board_height; ++to_y) {
                for (std::int32_t to_x = 0; to_x < board_width; ++to_x) {
                    if (is_legal_move(from_x, from_y, to_x, to_y)) {
                        return true;
                    }
                }
            }
        }
    }
    return false;
}

}  // namespace mocs::engine
