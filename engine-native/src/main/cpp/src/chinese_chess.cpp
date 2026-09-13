#include "mocs/engine/chinese_chess.hpp"

#include <algorithm>
#include <array>
#include <cmath>
#include <limits>
#include <utility>

namespace mocs::engine {
namespace {

constexpr std::array<std::uint8_t, 4> position_magic{
    'M',
    'O',
    'C',
    'X',
};
constexpr std::uint8_t position_version = 2;
constexpr std::uint8_t position_game_type =
    static_cast<std::uint8_t>(GameType::chinese_chess);
constexpr std::size_t position_header_size = 12;
constexpr std::size_t checksum_size = 4;
constexpr std::size_t move_record_size = 11;
constexpr std::size_t maximum_history_size = 4096;
constexpr std::uint16_t natural_limit_plies = 120;
constexpr std::int32_t winning_score = 1'000'000;
constexpr std::int32_t check_bonus = 30;
constexpr std::int32_t easy_score_window = 80;
constexpr std::size_t easy_candidate_limit = 3;
constexpr std::int32_t medium_score_window = 30;
constexpr std::size_t medium_candidate_limit = 2;

void append_u16(
    std::vector<std::uint8_t>& data,
    const std::uint16_t value
) {
    data.push_back(static_cast<std::uint8_t>(value & 0xffU));
    data.push_back(static_cast<std::uint8_t>((value >> 8U) & 0xffU));
}

void append_u32(
    std::vector<std::uint8_t>& data,
    const std::uint32_t value
) {
    for (std::uint32_t shift = 0; shift < 32U; shift += 8U) {
        data.push_back(
            static_cast<std::uint8_t>((value >> shift) & 0xffU)
        );
    }
}

[[nodiscard]] std::uint16_t read_u16(
    const std::vector<std::uint8_t>& data,
    const std::size_t offset
) noexcept {
    return static_cast<std::uint16_t>(
        static_cast<std::uint16_t>(data[offset]) |
        (static_cast<std::uint16_t>(data[offset + 1]) << 8U)
    );
}

[[nodiscard]] std::uint32_t read_u32(
    const std::vector<std::uint8_t>& data,
    const std::size_t offset
) noexcept {
    std::uint32_t value = 0;
    for (std::uint32_t byte = 0; byte < 4U; ++byte) {
        value |= static_cast<std::uint32_t>(data[offset + byte])
            << (byte * 8U);
    }
    return value;
}

[[nodiscard]] std::uint32_t crc32(
    const std::vector<std::uint8_t>& data,
    const std::size_t length
) noexcept {
    std::uint32_t crc = 0xffffffffU;
    for (std::size_t index = 0; index < length; ++index) {
        crc ^= data[index];
        for (std::uint32_t bit = 0; bit < 8U; ++bit) {
            const auto mask = static_cast<std::uint32_t>(
                -static_cast<std::int32_t>(crc & 1U)
            );
            crc = (crc >> 1U) ^ (0xedb88320U & mask);
        }
    }
    return ~crc;
}

[[nodiscard]] std::uint8_t encode_piece(
    const std::optional<Piece>& piece
) noexcept {
    if (!piece) {
        return 0;
    }
    const auto side_bit = piece->side == Side::black
        ? static_cast<std::uint8_t>(0x80)
        : static_cast<std::uint8_t>(0);
    return side_bit | static_cast<std::uint8_t>(piece->type);
}

[[nodiscard]] std::optional<Piece> decode_piece(
    const std::uint8_t encoded
) noexcept {
    if (encoded == 0 || (encoded & 0x78U) != 0) {
        return std::nullopt;
    }
    const auto type_code = static_cast<std::uint8_t>(encoded & 0x07U);
    if (type_code < static_cast<std::uint8_t>(PieceType::general) ||
        type_code > static_cast<std::uint8_t>(PieceType::soldier)) {
        return std::nullopt;
    }
    const auto side = (encoded & 0x80U) == 0 ? Side::red : Side::black;
    return Piece{static_cast<PieceType>(type_code), side};
}

[[nodiscard]] bool is_valid_result_code(const std::uint8_t code) noexcept {
    return code <= static_cast<std::uint8_t>(GameResult::draw);
}

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
    position_history_.clear();
    current_side_ = Side::red;
    no_capture_plies_ = 0;
    adjudicated_result_ = GameResult::ongoing;

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
    position_history_.push_back(PositionState{board_, current_side_});
}

ActionResult ChineseChessEngine::apply(const EngineAction& action) {
    if (action.kind != board_move_action_kind) {
        return ActionResult{false, EngineError::unsupported};
    }
    if (game_result() != GameResult::ongoing) {
        return ActionResult{false, EngineError::invalid_state};
    }

    const auto [from_x, from_y, to_x, to_y] = action.arguments;
    if (!is_legal_move(from_x, from_y, to_x, to_y)) {
        return ActionResult{false, EngineError::illegal_action};
    }

    const auto from = index(from_x, from_y);
    const auto to = index(to_x, to_y);
    const auto moving = *board_[from];
    auto next = board_;
    next[to] = moving;
    next[from].reset();
    const auto nature = classify_move(board_, next, current_side_);
    history_.push_back(MoveRecord{
        from_x,
        from_y,
        to_x,
        to_y,
        moving,
        board_[to],
        current_side_,
        no_capture_plies_,
        adjudicated_result_,
        nature,
    });

    no_capture_plies_ = board_[to]
        ? static_cast<std::uint16_t>(0)
        : static_cast<std::uint16_t>(no_capture_plies_ + 1U);
    board_ = next;
    current_side_ = opposite(current_side_);
    position_history_.push_back(PositionState{board_, current_side_});
    adjudicate_history();
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
    no_capture_plies_ = move.previous_no_capture_plies;
    adjudicated_result_ = move.previous_adjudicated_result;
    if (position_history_.size() > 1) {
        position_history_.pop_back();
    }
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

std::optional<EngineAction> ChineseChessEngine::best_move(
    const Difficulty difficulty
) const {
    if (
        (difficulty != Difficulty::easy &&
         difficulty != Difficulty::medium) ||
        game_result() != GameResult::ongoing
    ) {
        return std::nullopt;
    }

    struct ScoredAction {
        EngineAction action;
        std::int32_t score;
    };

    const auto perspective = current_side_;
    const auto search_depth =
        difficulty == Difficulty::easy ? 1 : 2;
    std::vector<ScoredAction> scored;
    for (const auto& action : legal_actions()) {
        auto next = *this;
        if (!next.apply(action).accepted) {
            continue;
        }
        auto score = next.search_score(
            search_depth - 1,
            perspective,
            std::numeric_limits<std::int32_t>::min(),
            std::numeric_limits<std::int32_t>::max()
        );
        if (
            next.game_result() == GameResult::ongoing &&
            next.is_in_check(next.current_side_)
        ) {
            score += check_bonus;
        }
        scored.push_back(ScoredAction{action, score});
    }
    if (scored.empty()) {
        return std::nullopt;
    }

    std::stable_sort(
        scored.begin(),
        scored.end(),
        [](const ScoredAction& left, const ScoredAction& right) {
            return left.score > right.score;
        }
    );
    const auto best_score = scored.front().score;
    const auto candidate_limit = difficulty == Difficulty::easy
        ? easy_candidate_limit
        : medium_candidate_limit;
    const auto score_window = difficulty == Difficulty::easy
        ? easy_score_window
        : medium_score_window;
    std::size_t candidate_count = 0;
    while (
        candidate_count < scored.size() &&
        candidate_count < candidate_limit &&
        scored[candidate_count].score >= best_score - score_window
    ) {
        ++candidate_count;
    }
    const auto selected = static_cast<std::size_t>(
        position_seed() % candidate_count
    );
    return scored[selected].action;
}

GameResult ChineseChessEngine::game_result() const noexcept {
    if (!has_general(Side::red)) {
        return GameResult::second_player_win;
    }
    if (!has_general(Side::black)) {
        return GameResult::first_player_win;
    }
    if (adjudicated_result_ != GameResult::ongoing) {
        return adjudicated_result_;
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
    if (history_.size() > maximum_history_size) {
        return {};
    }
    data.reserve(
        position_header_size + board_size +
        history_.size() * move_record_size + checksum_size
    );
    data.insert(data.end(), position_magic.begin(), position_magic.end());
    data.push_back(position_version);
    data.push_back(position_game_type);
    data.push_back(static_cast<std::uint8_t>(current_side_));
    append_u16(data, no_capture_plies_);
    data.push_back(static_cast<std::uint8_t>(adjudicated_result_));
    append_u16(data, static_cast<std::uint16_t>(history_.size()));

    for (const auto& piece : board_) {
        data.push_back(encode_piece(piece));
    }
    for (const auto& move : history_) {
        data.push_back(static_cast<std::uint8_t>(move.from_x));
        data.push_back(static_cast<std::uint8_t>(move.from_y));
        data.push_back(static_cast<std::uint8_t>(move.to_x));
        data.push_back(static_cast<std::uint8_t>(move.to_y));
        data.push_back(encode_piece(move.moved));
        data.push_back(encode_piece(move.captured));
        data.push_back(static_cast<std::uint8_t>(move.previous_side));
        append_u16(data, move.previous_no_capture_plies);
        data.push_back(
            static_cast<std::uint8_t>(move.previous_adjudicated_result)
        );
        data.push_back(static_cast<std::uint8_t>(move.nature));
    }
    append_u32(data, crc32(data, data.size()));
    return data;
}

RestoreResult ChineseChessEngine::restore(
    const std::vector<std::uint8_t>& data
) {
    if (data.size() < position_header_size + board_size + checksum_size) {
        return RestoreResult{false, EngineError::corrupted_data};
    }
    const auto payload_size = data.size() - checksum_size;
    if (read_u32(data, payload_size) != crc32(data, payload_size)) {
        return RestoreResult{false, EngineError::corrupted_data};
    }
    if (!std::equal(position_magic.begin(), position_magic.end(), data.begin())) {
        return RestoreResult{false, EngineError::corrupted_data};
    }
    if (data[4] != position_version) {
        return RestoreResult{false, EngineError::unsupported};
    }
    if (data[5] != position_game_type) {
        return RestoreResult{false, EngineError::unsupported};
    }
    if (data[6] > static_cast<std::uint8_t>(Side::black)) {
        return RestoreResult{false, EngineError::corrupted_data};
    }
    const auto restored_no_capture_plies = read_u16(data, 7);
    if (restored_no_capture_plies > natural_limit_plies) {
        return RestoreResult{false, EngineError::corrupted_data};
    }
    if (!is_valid_result_code(data[9])) {
        return RestoreResult{false, EngineError::corrupted_data};
    }
    const auto history_size = read_u16(data, 10);
    if (history_size > maximum_history_size ||
        data.size() != position_header_size + board_size +
            static_cast<std::size_t>(history_size) * move_record_size +
            checksum_size) {
        return RestoreResult{false, EngineError::corrupted_data};
    }

    Board restored{};
    std::array<std::int32_t, 2> general_counts{};
    for (std::size_t square = 0; square < board_size; ++square) {
        const auto encoded = data[position_header_size + square];
        if (encoded == 0) {
            continue;
        }
        const auto piece = decode_piece(encoded);
        if (encoded != 0 && !piece) {
            return RestoreResult{false, EngineError::corrupted_data};
        }
        restored[square] = piece;
        if (piece && piece->type == PieceType::general) {
            ++general_counts[static_cast<std::size_t>(piece->side)];
        }
    }

    if (general_counts[0] > 1 ||
        general_counts[1] > 1 ||
        general_counts[0] + general_counts[1] == 0) {
        return RestoreResult{false, EngineError::invalid_state};
    }

    std::vector<MoveRecord> restored_history;
    restored_history.reserve(history_size);
    auto offset = position_header_size + board_size;
    for (std::size_t move_index = 0; move_index < history_size; ++move_index) {
        const auto from_x = static_cast<std::int32_t>(data[offset]);
        const auto from_y = static_cast<std::int32_t>(data[offset + 1]);
        const auto to_x = static_cast<std::int32_t>(data[offset + 2]);
        const auto to_y = static_cast<std::int32_t>(data[offset + 3]);
        const auto moved_code = data[offset + 4];
        const auto captured_code = data[offset + 5];
        const auto moved = decode_piece(moved_code);
        const auto captured = decode_piece(captured_code);
        const auto previous_side_code = data[offset + 6];
        const auto previous_no_capture_plies = read_u16(data, offset + 7);
        const auto previous_result_code = data[offset + 9];
        const auto nature_code = data[offset + 10];
        if (!is_inside(from_x, from_y) ||
            !is_inside(to_x, to_y) ||
            (from_x == to_x && from_y == to_y) ||
            !moved ||
            (captured_code != 0 && !captured) ||
            previous_side_code > static_cast<std::uint8_t>(Side::black) ||
            previous_no_capture_plies > natural_limit_plies ||
            !is_valid_result_code(previous_result_code) ||
            nature_code > static_cast<std::uint8_t>(MoveNature::chase)) {
            return RestoreResult{false, EngineError::corrupted_data};
        }
        const auto previous_side = static_cast<Side>(previous_side_code);
        if (moved->side != previous_side ||
            (captured && captured->side == previous_side)) {
            return RestoreResult{false, EngineError::corrupted_data};
        }
        restored_history.push_back(MoveRecord{
            from_x,
            from_y,
            to_x,
            to_y,
            *moved,
            captured,
            previous_side,
            previous_no_capture_plies,
            static_cast<GameResult>(previous_result_code),
            static_cast<MoveNature>(nature_code),
        });
        offset += move_record_size;
    }

    auto working_board = restored;
    auto working_side = static_cast<Side>(data[6]);
    std::vector<PositionState> restored_positions(
        static_cast<std::size_t>(history_size) + 1U
    );
    restored_positions.back() = PositionState{working_board, working_side};
    for (std::size_t reverse = history_size; reverse > 0; --reverse) {
        const auto& move = restored_history[reverse - 1];
        const auto moved_at_target =
            working_board[index(move.to_x, move.to_y)];
        if (working_side != opposite(move.previous_side) ||
            !moved_at_target ||
            !(*moved_at_target == move.moved) ||
            working_board[index(move.from_x, move.from_y)]) {
            return RestoreResult{false, EngineError::corrupted_data};
        }
        working_board[index(move.from_x, move.from_y)] = move.moved;
        working_board[index(move.to_x, move.to_y)] = move.captured;
        working_side = move.previous_side;
        restored_positions[reverse - 1] =
            PositionState{working_board, working_side};
    }

    board_ = restored;
    current_side_ = static_cast<Side>(data[6]);
    history_ = std::move(restored_history);
    position_history_ = std::move(restored_positions);
    no_capture_plies_ = restored_no_capture_plies;
    adjudicated_result_ = static_cast<GameResult>(data[9]);
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

bool ChineseChessEngine::is_legal_capture(
    const Board& board,
    const Side side,
    const std::int32_t from_x,
    const std::int32_t from_y,
    const std::int32_t to_x,
    const std::int32_t to_y
) noexcept {
    const auto moving = board[index(from_x, from_y)];
    const auto target = board[index(to_x, to_y)];
    if (!moving ||
        moving->side != side ||
        !target ||
        target->side == side ||
        !piece_attacks_square(
            board,
            *moving,
            from_x,
            from_y,
            to_x,
            to_y
        )) {
        return false;
    }
    auto next = board;
    next[index(to_x, to_y)] = moving;
    next[index(from_x, from_y)].reset();
    return !is_in_check(next, side);
}

std::array<bool, ChineseChessEngine::board_size>
ChineseChessEngine::unrooted_targets(
    const Board& board,
    const Side attacker
) noexcept {
    std::array<bool, board_size> targets{};
    for (std::int32_t target_y = 0; target_y < board_height; ++target_y) {
        for (std::int32_t target_x = 0; target_x < board_width; ++target_x) {
            const auto target = board[index(target_x, target_y)];
            if (!target ||
                target->side == attacker ||
                target->type == PieceType::general) {
                continue;
            }
            for (std::int32_t from_y = 0; from_y < board_height; ++from_y) {
                for (std::int32_t from_x = 0; from_x < board_width; ++from_x) {
                    const auto attacking_piece = board[index(from_x, from_y)];
                    if (!attacking_piece ||
                        attacking_piece->side != attacker ||
                        attacking_piece->type == PieceType::general ||
                        attacking_piece->type == PieceType::soldier ||
                        !is_legal_capture(
                            board,
                            attacker,
                            from_x,
                            from_y,
                            target_x,
                            target_y
                        )) {
                        continue;
                    }

                    auto captured = board;
                    captured[index(target_x, target_y)] = attacking_piece;
                    captured[index(from_x, from_y)].reset();
                    bool can_recapture = false;
                    for (std::int32_t y = 0; y < board_height && !can_recapture; ++y) {
                        for (std::int32_t x = 0; x < board_width; ++x) {
                            if (is_legal_capture(
                                captured,
                                opposite(attacker),
                                x,
                                y,
                                target_x,
                                target_y
                            )) {
                                can_recapture = true;
                                break;
                            }
                        }
                    }
                    if (!can_recapture) {
                        targets[index(target_x, target_y)] = true;
                    }
                }
            }
        }
    }
    return targets;
}

ChineseChessEngine::MoveNature ChineseChessEngine::classify_move(
    const Board& before,
    const Board& after,
    const Side mover
) noexcept {
    if (is_in_check(after, opposite(mover))) {
        return MoveNature::check;
    }
    const auto previous_targets = unrooted_targets(before, mover);
    const auto next_targets = unrooted_targets(after, mover);
    for (std::size_t square = 0; square < board_size; ++square) {
        if (next_targets[square] && !previous_targets[square]) {
            return MoveNature::chase;
        }
    }
    return MoveNature::idle;
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

std::int32_t ChineseChessEngine::piece_value(
    const PieceType type
) noexcept {
    switch (type) {
        case PieceType::general:
            return 100'000;
        case PieceType::chariot:
            return 900;
        case PieceType::horse:
        case PieceType::cannon:
            return 450;
        case PieceType::advisor:
        case PieceType::elephant:
            return 200;
        case PieceType::soldier:
            return 100;
    }
    return 0;
}

std::int32_t ChineseChessEngine::evaluate_for(
    const Side perspective
) const noexcept {
    const auto result = game_result();
    if (result != GameResult::ongoing) {
        if (result == GameResult::draw) {
            return 0;
        }
        const auto perspective_wins =
            (perspective == Side::red &&
             result == GameResult::first_player_win) ||
            (perspective == Side::black &&
             result == GameResult::second_player_win);
        return perspective_wins ? winning_score : -winning_score;
    }

    std::int32_t score = 0;
    for (const auto& piece : board_) {
        if (!piece) {
            continue;
        }
        const auto value = piece_value(piece->type);
        score += piece->side == perspective ? value : -value;
    }
    return score;
}

std::uint64_t ChineseChessEngine::position_seed() const noexcept {
    constexpr std::uint64_t offset_basis = 1469598103934665603ULL;
    constexpr std::uint64_t prime = 1099511628211ULL;
    auto seed = offset_basis;
    for (const auto& piece : board_) {
        seed ^= encode_piece(piece);
        seed *= prime;
    }
    seed ^= static_cast<std::uint8_t>(current_side_);
    seed *= prime;
    return seed;
}

std::int32_t ChineseChessEngine::search_score(
    const std::int32_t depth,
    const Side perspective,
    std::int32_t alpha,
    std::int32_t beta
) const {
    if (depth <= 0 || game_result() != GameResult::ongoing) {
        return evaluate_for(perspective);
    }
    const auto actions = legal_actions();
    if (actions.empty()) {
        return evaluate_for(perspective);
    }

    const auto maximize = current_side_ == perspective;
    auto best = maximize
        ? std::numeric_limits<std::int32_t>::min()
        : std::numeric_limits<std::int32_t>::max();
    for (const auto& action : actions) {
        auto next = *this;
        if (!next.apply(action).accepted) {
            continue;
        }
        const auto score = next.search_score(
            depth - 1,
            perspective,
            alpha,
            beta
        );
        if (maximize) {
            best = std::max(best, score);
            alpha = std::max(alpha, best);
        } else {
            best = std::min(best, score);
            beta = std::min(beta, best);
        }
        if (beta <= alpha) {
            break;
        }
    }
    return best;
}

void ChineseChessEngine::adjudicate_history() noexcept {
    if (no_capture_plies_ >= natural_limit_plies) {
        adjudicated_result_ = GameResult::draw;
        return;
    }
    if (position_history_.size() < 3) {
        return;
    }

    std::array<std::size_t, 3> occurrences{};
    std::size_t occurrence_count = 0;
    const auto& current = position_history_.back();
    for (std::size_t cursor = position_history_.size(); cursor > 0; --cursor) {
        if (position_history_[cursor - 1] == current) {
            occurrences[occurrence_count++] = cursor - 1;
            if (occurrence_count == occurrences.size()) {
                break;
            }
        }
    }
    if (occurrence_count < occurrences.size()) {
        return;
    }

    const auto cycle_start = occurrences.back();
    const auto all_moves_match = [this, cycle_start](
        const Side side,
        const auto predicate
    ) noexcept {
        bool found = false;
        for (std::size_t i = cycle_start; i < history_.size(); ++i) {
            const auto& move = history_[i];
            if (move.previous_side != side) {
                continue;
            }
            found = true;
            if (!predicate(move.nature)) {
                return false;
            }
        }
        return found;
    };
    const auto is_check = [](const MoveNature nature) noexcept {
        return nature == MoveNature::check;
    };
    const auto is_attack = [](const MoveNature nature) noexcept {
        return nature == MoveNature::check || nature == MoveNature::chase;
    };

    const auto red_long_check = all_moves_match(Side::red, is_check);
    const auto black_long_check = all_moves_match(Side::black, is_check);
    if (red_long_check != black_long_check) {
        adjudicated_result_ = red_long_check
            ? GameResult::second_player_win
            : GameResult::first_player_win;
        return;
    }

    const auto red_attacks = all_moves_match(Side::red, is_attack);
    const auto black_attacks = all_moves_match(Side::black, is_attack);
    if (red_attacks != black_attacks) {
        adjudicated_result_ = red_attacks
            ? GameResult::second_player_win
            : GameResult::first_player_win;
        return;
    }
    adjudicated_result_ = GameResult::draw;
}

}  // namespace mocs::engine
