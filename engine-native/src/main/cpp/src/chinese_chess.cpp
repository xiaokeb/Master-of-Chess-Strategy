#include "mocs/engine/chinese_chess.hpp"
#include "mocs/engine/pikafish_adapter.hpp"

#include <algorithm>
#include <array>
#include <bitset>
#include <cmath>
#include <limits>
#include <unordered_map>
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
constexpr std::size_t hard_candidate_limit = 1;
constexpr std::size_t easy_node_limit = 10'000;
constexpr std::size_t medium_node_limit = 50'000;
constexpr std::size_t hard_node_limit = 150'000;

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

[[nodiscard]] char fen_symbol(const Piece piece) noexcept {
    char symbol = ' ';
    switch (piece.type) {
        case PieceType::general:
            symbol = 'k';
            break;
        case PieceType::advisor:
            symbol = 'a';
            break;
        case PieceType::elephant:
            symbol = 'b';
            break;
        case PieceType::horse:
            symbol = 'n';
            break;
        case PieceType::chariot:
            symbol = 'r';
            break;
        case PieceType::cannon:
            symbol = 'c';
            break;
        case PieceType::soldier:
            symbol = 'p';
            break;
    }
    return piece.side == Side::red
        ? static_cast<char>(symbol - ('a' - 'A'))
        : symbol;
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
    const auto nature = classify_move(board_, next, current_side_, moving.type);
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

std::string ChineseChessEngine::fen() const {
    return encode_fen(
        board_,
        current_side_,
        no_capture_plies_,
        history_.size()
    );
}

ChineseChessSearchPosition ChineseChessEngine::search_position() const {
    const auto& first = position_history_.front();
    const auto first_clock = history_.empty()
        ? no_capture_plies_ : history_.front().previous_no_capture_plies;
    ChineseChessSearchPosition result{
        encode_fen(first.board, first.side, first_clock, 0), {}, fen(), game_result()};
    result.moves.reserve(history_.size());
    for (const auto& move : history_) {
        result.moves.push_back(std::string{
            static_cast<char>('a' + move.from_x), static_cast<char>('9' - move.from_y),
            static_cast<char>('a' + move.to_x), static_cast<char>('9' - move.to_y)});
    }
    return result;
}

std::string ChineseChessEngine::encode_fen(
    const Board& board,
    const Side side,
    const std::uint16_t no_capture_plies,
    const std::size_t completed_plies
) {
    std::string value;
    value.reserve(96);
    for (std::int32_t y = 0; y < board_height; ++y) {
        std::int32_t empty_count = 0;
        for (std::int32_t x = 0; x < board_width; ++x) {
            const auto piece = board[index(x, y)];
            if (!piece) {
                ++empty_count;
                continue;
            }
            if (empty_count > 0) {
                value.push_back(static_cast<char>('0' + empty_count));
                empty_count = 0;
            }
            value.push_back(fen_symbol(*piece));
        }
        if (empty_count > 0) {
            value.push_back(static_cast<char>('0' + empty_count));
        }
        if (y + 1 < board_height) {
            value.push_back('/');
        }
    }
    value += side == Side::red ? " w - - " : " b - - ";
    value += std::to_string(no_capture_plies);
    value.push_back(' ');
    // A fullmove is completed after Black's turn, including a custom position
    // that starts with Black. The initial side follows from parity/current side.
    const bool initially_black = (side == Side::black) != (completed_plies % 2 != 0);
    value += std::to_string((completed_plies + (initially_black ? 1 : 0)) / 2 + 1);
    return value;
}

std::optional<EngineAction> ChineseChessEngine::best_move(
    const Difficulty difficulty
) const {
    if (
        (difficulty != Difficulty::easy &&
         difficulty != Difficulty::medium &&
         difficulty != Difficulty::hard) ||
        game_result() != GameResult::ongoing
    ) {
        return std::nullopt;
    }

    struct ScoredAction {
        EngineAction action;
        std::int32_t score;
    };

    const auto perspective = current_side_;
    const auto search_depth = difficulty == Difficulty::easy
        ? 1
        : (difficulty == Difficulty::medium ? 2 : 3);
    SearchContext search_context{
        0,
        difficulty == Difficulty::easy
            ? easy_node_limit
            : (
                difficulty == Difficulty::medium
                    ? medium_node_limit
                    : hard_node_limit
            ),
    };
    std::vector<ScoredAction> scored;
    for (const auto& action : legal_actions()) {
        auto next = *this;
        if (!next.apply(action).accepted) {
            continue;
        }
        const auto next_result = next.game_result();
        auto score = next_result == GameResult::ongoing
            ? next.search_score(
                search_depth - 1,
                perspective,
                std::numeric_limits<std::int32_t>::min(),
                std::numeric_limits<std::int32_t>::max(),
                search_context
            )
            : next.evaluate_for(perspective);
        if (
            next_result == GameResult::ongoing &&
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
        : (
            difficulty == Difficulty::medium
                ? medium_candidate_limit
                : hard_candidate_limit
        );
    const auto score_window = difficulty == Difficulty::easy
        ? easy_score_window
        : (difficulty == Difficulty::medium ? medium_score_window : 0);
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

    // A checksum detects accidental damage, not a self-consistent but
    // impossible move list. Replay the reconstructed positions through the
    // same legality gate used by live play before trusting imported history.
    auto expected_no_capture_plies = history_size > 0
        ? restored_history.front().previous_no_capture_plies
        : restored_no_capture_plies;
    if (history_size > 0 && expected_no_capture_plies == natural_limit_plies) {
        return RestoreResult{false, EngineError::corrupted_data};
    }

    // Rebuild the trusted prefix as live play would. A valid next move must
    // not follow a repetition result, even if its saved "previous result"
    // field has been forged back to ongoing with a fresh CRC.
    ChineseChessEngine prefix;
    prefix.board_ = restored_positions.front().board;
    prefix.current_side_ = restored_positions.front().side;
    prefix.no_capture_plies_ = expected_no_capture_plies;
    prefix.history_.clear();
    prefix.position_history_.clear();
    prefix.position_history_.push_back(restored_positions.front());
    prefix.history_.reserve(history_size);
    prefix.position_history_.reserve(static_cast<std::size_t>(history_size) + 1U);
    const auto position_key = [](const PositionState& position) {
        std::string key;
        key.reserve(board_size + 1U);
        key.push_back(static_cast<char>(position.side));
        for (const auto& piece : position.board) {
            key.push_back(static_cast<char>(encode_piece(piece)));
        }
        return key;
    };
    std::unordered_map<std::string, std::size_t> occurrences;
    occurrences.emplace(position_key(restored_positions.front()), 1U);
    for (std::size_t i = 0; i < history_size; ++i) {
        auto& move = restored_history[i];
        const auto& before = restored_positions[i];
        const auto& after = restored_positions[i + 1];
        if (
            before.side != move.previous_side ||
            !has_general_on_board(before.board, Side::red) ||
            !has_general_on_board(before.board, Side::black) ||
            !before.board[index(move.from_x, move.from_y)] ||
            !(*before.board[index(move.from_x, move.from_y)] == move.moved) ||
            !(before.board[index(move.to_x, move.to_y)] == move.captured) ||
            move.previous_no_capture_plies != expected_no_capture_plies ||
            move.previous_adjudicated_result != GameResult::ongoing ||
            !is_legal_move_on_board(
                before.board,
                move.previous_side,
                move.from_x,
                move.from_y,
                move.to_x,
                move.to_y
            )
        ) {
            return RestoreResult{false, EngineError::corrupted_data};
        }
        expected_no_capture_plies = move.captured
            ? 0
            : static_cast<std::uint16_t>(expected_no_capture_plies + 1U);
        if (
            expected_no_capture_plies > natural_limit_plies ||
            (expected_no_capture_plies == natural_limit_plies && i + 1 < history_size) ||
            after.side != opposite(move.previous_side)
        ) {
            return RestoreResult{false, EngineError::corrupted_data};
        }
        // Move nature is derived from the board, not trusted from a saved
        // byte; this also reapplies corrected rules to older valid saves.
        move.nature = classify_move(
            before.board, after.board, move.previous_side, move.moved.type
        );
        prefix.history_.push_back(move);
        prefix.position_history_.push_back(after);
        prefix.board_ = after.board;
        prefix.current_side_ = after.side;
        prefix.no_capture_plies_ = expected_no_capture_plies;
        const auto repetition_count = ++occurrences[position_key(after)];
        if (repetition_count >= 3U && i + 1 < history_size) {
            prefix.adjudicated_result_ = GameResult::ongoing;
            prefix.adjudicate_history();
            if (prefix.game_result() != GameResult::ongoing) {
                return RestoreResult{false, EngineError::corrupted_data};
            }
        }
    }
    if (expected_no_capture_plies != restored_no_capture_plies) {
        return RestoreResult{false, EngineError::corrupted_data};
    }

    board_ = restored;
    current_side_ = static_cast<Side>(data[6]);
    history_ = std::move(restored_history);
    position_history_ = std::move(restored_positions);
    no_capture_plies_ = restored_no_capture_plies;
    // The saved outcome is not authoritative: replayed history and the
    // current rules must decide it, including after a ruleset correction.
    adjudicated_result_ = GameResult::ongoing;
    adjudicate_history();
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

bool ChineseChessEngine::is_legal_move_on_board(
    const Board& board,
    const Side side,
    const std::int32_t from_x,
    const std::int32_t from_y,
    const std::int32_t to_x,
    const std::int32_t to_y
) noexcept {
    if (!is_inside(from_x, from_y) ||
        !is_inside(to_x, to_y) ||
        (from_x == to_x && from_y == to_y)) {
        return false;
    }
    const auto moving = board[index(from_x, from_y)];
    const auto target = board[index(to_x, to_y)];
    if (!moving ||
        moving->side != side ||
        (target && target->side == side) ||
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

bool ChineseChessEngine::has_legal_action_on_board(
    const Board& board,
    const Side side
) noexcept {
    for (std::int32_t from_y = 0; from_y < board_height; ++from_y) {
        for (std::int32_t from_x = 0; from_x < board_width; ++from_x) {
            const auto piece = board[index(from_x, from_y)];
            if (!piece || piece->side != side) {
                continue;
            }
            for (std::int32_t to_y = 0; to_y < board_height; ++to_y) {
                for (std::int32_t to_x = 0; to_x < board_width; ++to_x) {
                    if (is_legal_move_on_board(
                            board,
                            side,
                            from_x,
                            from_y,
                            to_x,
                            to_y
                        )) {
                        return true;
                    }
                }
            }
        }
    }
    return false;
}

bool ChineseChessEngine::has_general_on_board(
    const Board& board,
    const Side side
) noexcept {
    return std::any_of(
        board.begin(),
        board.end(),
        [side](const auto& piece) {
            return piece &&
                piece->side == side &&
                piece->type == PieceType::general;
        }
    );
}

bool ChineseChessEngine::has_mating_move(
    const Board& board,
    const Side attacker
) noexcept {
    const auto defender = opposite(attacker);
    for (std::int32_t from_y = 0; from_y < board_height; ++from_y) {
        for (std::int32_t from_x = 0; from_x < board_width; ++from_x) {
            const auto piece = board[index(from_x, from_y)];
            if (!piece || piece->side != attacker) {
                continue;
            }
            for (std::int32_t to_y = 0; to_y < board_height; ++to_y) {
                for (std::int32_t to_x = 0; to_x < board_width; ++to_x) {
                    if (!is_legal_move_on_board(
                            board,
                            attacker,
                            from_x,
                            from_y,
                            to_x,
                            to_y
                        )) {
                        continue;
                    }
                    auto next = board;
                    next[index(to_x, to_y)] = piece;
                    next[index(from_x, from_y)].reset();
                    if (
                        !has_general_on_board(next, defender) ||
                        (
                            is_in_check(next, defender) &&
                            !has_legal_action_on_board(next, defender)
                        )
                    ) {
                        return true;
                    }
                }
            }
        }
    }
    return false;
}

std::int32_t ChineseChessEngine::exchange_gain(
    const Board& board,
    const Side perspective,
    const Side side_to_capture,
    const std::int32_t target_x,
    const std::int32_t target_y
) noexcept {
    const auto target = board[index(target_x, target_y)];
    if (!target || target->side == side_to_capture) {
        return 0;
    }
    auto best = 0;
    for (std::int32_t from_y = 0; from_y < board_height; ++from_y) {
        for (std::int32_t from_x = 0; from_x < board_width; ++from_x) {
            if (!is_legal_capture(
                    board,
                    side_to_capture,
                    from_x,
                    from_y,
                    target_x,
                    target_y
                )) {
                continue;
            }
            const auto moving = board[index(from_x, from_y)];
            auto next = board;
            next[index(target_x, target_y)] = moving;
            next[index(from_x, from_y)].reset();
            const auto immediate = side_to_capture == perspective
                ? piece_value(target->type)
                : -piece_value(target->type);
            const auto score = immediate + exchange_gain(
                next,
                perspective,
                opposite(side_to_capture),
                target_x,
                target_y
            );
            best = side_to_capture == perspective
                ? std::max(best, score)
                : std::min(best, score);
        }
    }
    return best;
}

std::array<ChineseChessEngine::ChaseKind, ChineseChessEngine::board_size>
ChineseChessEngine::chase_targets(
    const Board& board,
    const Side attacker
) noexcept {
    std::array<ChaseKind, board_size> targets{};
    for (std::int32_t target_y = 0; target_y < board_height; ++target_y) {
        for (std::int32_t target_x = 0; target_x < board_width; ++target_x) {
            const auto target = board[index(target_x, target_y)];
            if (!target ||
                target->side == attacker ||
                target->type == PieceType::general) {
                continue;
            }

            bool has_regular_source = false;
            bool has_joint_source = false;
            bool has_direct_source = false;
            std::size_t profitable_sources = 0;
            for (std::int32_t from_y = 0; from_y < board_height; ++from_y) {
                for (std::int32_t from_x = 0; from_x < board_width; ++from_x) {
                    const auto moving = board[index(from_x, from_y)];
                    if (!moving ||
                        moving->side != attacker ||
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

                    // A mutual same-kind capture is an invitation to
                    // exchange, not a chase when taking it loses no material.
                    if (
                        moving->type == target->type &&
                        is_legal_capture(
                            board,
                            opposite(attacker),
                            target_x,
                            target_y,
                            from_x,
                            from_y
                        )
                    ) {
                        continue;
                    }

                    auto after_capture = board;
                    after_capture[index(target_x, target_y)] = moving;
                    after_capture[index(from_x, from_y)].reset();
                    if (has_mating_move(
                            after_capture,
                            opposite(attacker)
                        )) {
                        continue;
                    }
                    const auto gain = piece_value(target->type) +
                        exchange_gain(
                            after_capture,
                            attacker,
                            opposite(attacker),
                            target_x,
                            target_y
                        );
                    if (gain <= 0) {
                        continue;
                    }
                    ++profitable_sources;
                    const auto regular_source =
                        moving->type != PieceType::general &&
                        moving->type != PieceType::soldier;
                    has_regular_source = has_regular_source || regular_source;

                    bool can_recapture = false;
                    for (
                        std::int32_t y = 0;
                        y < board_height && !can_recapture;
                        ++y
                    ) {
                        for (std::int32_t x = 0; x < board_width; ++x) {
                            if (is_legal_capture(
                                    after_capture,
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
                    const auto direct_gain = piece_value(target->type) -
                        (can_recapture ? piece_value(moving->type) : 0);
                    if (regular_source && direct_gain > 0) {
                        has_direct_source = true;
                    } else if (regular_source || can_recapture) {
                        has_joint_source = true;
                    }
                }
            }

            if (has_direct_source) {
                targets[index(target_x, target_y)] = ChaseKind::direct;
            } else if (
                has_regular_source &&
                has_joint_source &&
                profitable_sources >= 1
            ) {
                targets[index(target_x, target_y)] = ChaseKind::joint;
            }
        }
    }
    return targets;
}

ChineseChessEngine::TacticalAnalysis
ChineseChessEngine::analyze_tactical_move(
    const Board& before,
    const Board& after,
    const Side mover,
    const PieceType moving_type
) noexcept {
    TacticalAnalysis result{};
    result.check = is_in_check(after, opposite(mover));
    if (result.check) {
        return result;
    }
    // Rule 26.1.2: a general escaping check does not gain a chase or kill
    // merely because its move uncovers another piece's threat.
    if (moving_type == PieceType::general && is_in_check(before, mover)) {
        return result;
    }
    result.kill =
        has_mating_move(after, mover) &&
        !has_mating_move(before, mover);
    if (result.kill) {
        return result;
    }
    const auto before_targets = chase_targets(before, mover);
    const auto after_targets = chase_targets(after, mover);
    for (std::size_t square = 0; square < board_size; ++square) {
        if (
            after_targets[square] != ChaseKind::none &&
            before_targets[square] == ChaseKind::none
        ) {
            result.chase_targets[square] = after_targets[square];
        }
    }
    return result;
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
    const Side mover,
    const PieceType moving_type
) noexcept {
    if (is_in_check(after, opposite(mover))) {
        return MoveNature::check;
    }
    if (moving_type == PieceType::general && is_in_check(before, mover)) {
        return MoveNature::idle;
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
    return is_legal_move_on_board(
        board_,
        current_side_,
        from_x,
        from_y,
        to_x,
        to_y
    );
}

bool ChineseChessEngine::has_general(const Side side) const noexcept {
    return has_general_on_board(board_, side);
}

bool ChineseChessEngine::has_legal_action() const noexcept {
    return has_legal_action_on_board(board_, current_side_);
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

    return material_score(perspective);
}

std::int32_t ChineseChessEngine::material_score(
    const Side perspective
) const noexcept {
    std::int32_t score = 0;
    for (std::size_t square = 0; square < board_.size(); ++square) {
        const auto& piece = board_[square];
        if (!piece) {
            continue;
        }
        auto value = piece_value(piece->type);
        if (piece->type == PieceType::soldier) {
            const auto y = static_cast<std::int32_t>(
                square / static_cast<std::size_t>(board_width)
            );
            const auto crossed_river =
                (piece->side == Side::red && y <= 4) ||
                (piece->side == Side::black && y >= 5);
            if (crossed_river) {
                value += 30;
            }
        }
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
    std::int32_t beta,
    SearchContext& context
) const {
    if (context.visited_nodes >= context.node_limit) {
        return material_score(perspective);
    }
    ++context.visited_nodes;
    if (
        !has_general(Side::red) ||
        !has_general(Side::black) ||
        adjudicated_result_ != GameResult::ongoing
    ) {
        return evaluate_for(perspective);
    }
    if (depth <= 0) {
        // Xiangqi stalemate is a loss just like checkmate. At the horizon,
        // stop the scan at the first legal action; never require check before
        // recognizing an immobilized side's loss.
        if (!has_legal_action()) {
            return evaluate_for(perspective);
        }
        return material_score(perspective);
    }
    auto actions = legal_actions();
    if (actions.empty()) {
        return evaluate_for(perspective);
    }
    std::stable_sort(
        actions.begin(),
        actions.end(),
        [this](const EngineAction& left, const EngineAction& right) {
            const auto left_target = index(
                left.arguments[2],
                left.arguments[3]
            );
            const auto right_target = index(
                right.arguments[2],
                right.arguments[3]
            );
            const auto left_value = board_[left_target]
                ? piece_value(board_[left_target]->type)
                : 0;
            const auto right_value = board_[right_target]
                ? piece_value(board_[right_target]->type)
                : 0;
            return left_value > right_value;
        }
    );

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
            beta,
            context
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

std::optional<GameResult> ChineseChessEngine::adjudicate_2020_cycle(
    const std::size_t cycle_start
) const noexcept {
    struct SideProfile {
        bool found{false};
        bool all_check{true};
        bool all_attack{true};
        bool only_chase{true};
        bool all_chase_direct{true};
        bool all_chase_joint{true};
        std::bitset<board_size> direct_chased{};
        std::bitset<board_size> joint_chased{};

        [[nodiscard]] bool prohibited() const noexcept {
            // Rule 24.11 explicitly allows a long chase to alternate among
            // several pieces. Exact position repetition already bounds the
            // target set, so every attacking move need not share one ID.
            return found && all_attack;
        }
    };

    std::array<std::int16_t, board_size> piece_ids{};
    piece_ids.fill(-1);
    std::array<PieceType, board_size> piece_types{};
    std::array<bool, board_size> piece_type_present{};
    std::array<std::size_t, board_size> last_moved_ply{};
    last_moved_ply.fill(std::numeric_limits<std::size_t>::max());
    std::array<bool, board_size> last_move_crossed_river{};
    std::size_t next_piece_id = 0;
    const auto& cycle_board = position_history_[cycle_start].board;
    for (std::size_t square = 0; square < board_size; ++square) {
        if (!cycle_board[square]) {
            continue;
        }
        piece_ids[square] = static_cast<std::int16_t>(next_piece_id);
        piece_types[next_piece_id] = cycle_board[square]->type;
        piece_type_present[next_piece_id] = true;
        ++next_piece_id;
    }

    std::array<SideProfile, 2> profiles{};
    for (std::size_t ply = cycle_start; ply < history_.size(); ++ply) {
        const auto& move = history_[ply];
        const auto from = index(move.from_x, move.from_y);
        const auto to = index(move.to_x, move.to_y);
        const auto moved_id = piece_ids[from];
        if (moved_id < 0) {
            return std::nullopt;
        }
        piece_ids[to] = moved_id;
        piece_ids[from] = -1;
        const auto moved_id_index = static_cast<std::size_t>(moved_id);
        last_moved_ply[moved_id_index] = ply;
        if (move.moved.type == PieceType::soldier) {
            const auto was_crossed = move.moved.side == Side::red
                ? move.from_y <= 4
                : move.from_y >= 5;
            const auto is_crossed = move.moved.side == Side::red
                ? move.to_y <= 4
                : move.to_y >= 5;
            last_move_crossed_river[moved_id_index] =
                !was_crossed && is_crossed;
        } else {
            last_move_crossed_river[moved_id_index] = false;
        }

        const auto analysis = analyze_tactical_move(
            position_history_[ply].board,
            position_history_[ply + 1].board,
            move.previous_side,
            move.moved.type
        );
        std::bitset<board_size> direct_targets;
        std::bitset<board_size> joint_targets;
        const auto& after_board = position_history_[ply + 1].board;
        for (std::size_t square = 0; square < board_size; ++square) {
            const auto chase_kind = analysis.chase_targets[square];
            if (chase_kind == ChaseKind::none || piece_ids[square] < 0) {
                continue;
            }
            const auto target = after_board[square];
            const auto target_id = static_cast<std::size_t>(piece_ids[square]);
            if (!target || target->type == PieceType::general) {
                continue;
            }
            if (target->type == PieceType::soldier) {
                const auto target_y = static_cast<std::int32_t>(
                    square / static_cast<std::size_t>(board_width)
                );
                const auto crossed = target->side == Side::red
                    ? target_y <= 4
                    : target_y >= 5;
                const auto just_crossed =
                    last_moved_ply[target_id] !=
                        std::numeric_limits<std::size_t>::max() &&
                    last_moved_ply[target_id] + 1 == ply &&
                    last_move_crossed_river[target_id];
                if (!crossed || just_crossed) {
                    continue;
                }
            }
            if (chase_kind == ChaseKind::direct) {
                direct_targets.set(target_id);
            } else {
                joint_targets.set(target_id);
            }
        }
        const auto chase_targets_for_move = direct_targets | joint_targets;
        auto& profile = profiles[static_cast<std::size_t>(move.previous_side)];
        profile.found = true;
        if (analysis.check) {
            profile.only_chase = false;
            profile.all_chase_direct = false;
            profile.all_chase_joint = false;
            continue;
        }
        profile.all_check = false;
        if (analysis.kill) {
            profile.only_chase = false;
            profile.all_chase_direct = false;
            profile.all_chase_joint = false;
            continue;
        }
        if (chase_targets_for_move.none()) {
            profile.all_attack = false;
            profile.only_chase = false;
            profile.all_chase_direct = false;
            profile.all_chase_joint = false;
            continue;
        }
        profile.all_chase_direct =
            profile.all_chase_direct &&
            direct_targets.any() &&
            joint_targets.none();
        profile.all_chase_joint =
            profile.all_chase_joint &&
            joint_targets.any() &&
            direct_targets.none();
        profile.direct_chased |= direct_targets;
        profile.joint_chased |= joint_targets;
    }

    const auto& red = profiles[static_cast<std::size_t>(Side::red)];
    const auto& black = profiles[static_cast<std::size_t>(Side::black)];
    if (red.all_check != black.all_check) {
        return red.all_check
            ? GameResult::second_player_win
            : GameResult::first_player_win;
    }

    const auto red_prohibited = red.prohibited();
    const auto black_prohibited = black.prohibited();
    if (red_prohibited != black_prohibited) {
        return red_prohibited
            ? GameResult::second_player_win
            : GameResult::first_player_win;
    }
    if (!red_prohibited) {
        return std::nullopt;
    }

    const auto has_type = [&piece_types, &piece_type_present](
        const std::bitset<board_size>& targets,
        const PieceType type
    ) noexcept {
        for (std::size_t id = 0; id < board_size; ++id) {
            if (
                targets.test(id) &&
                piece_type_present[id] &&
                piece_types[id] == type
            ) {
                return true;
            }
        }
        return false;
    };
    const auto has_non_chariot = [&piece_types, &piece_type_present](
        const std::bitset<board_size>& targets
    ) noexcept {
        for (std::size_t id = 0; id < board_size; ++id) {
            if (
                targets.test(id) &&
                piece_type_present[id] &&
                piece_types[id] != PieceType::chariot
            ) {
                return true;
            }
        }
        return false;
    };
    const auto red_direct_over_joint =
        red.only_chase && red.all_chase_direct &&
        black.only_chase && black.all_chase_joint &&
        (
            (
                has_type(red.direct_chased, PieceType::chariot) &&
                has_type(black.joint_chased, PieceType::chariot)
            ) ||
            (
                has_non_chariot(red.direct_chased) &&
                has_non_chariot(black.joint_chased)
            )
        );
    const auto black_direct_over_joint =
        black.only_chase && black.all_chase_direct &&
        red.only_chase && red.all_chase_joint &&
        (
            (
                has_type(black.direct_chased, PieceType::chariot) &&
                has_type(red.joint_chased, PieceType::chariot)
            ) ||
            (
                has_non_chariot(black.direct_chased) &&
                has_non_chariot(red.joint_chased)
            )
        );
    if (red_direct_over_joint != black_direct_over_joint) {
        return red_direct_over_joint
            ? GameResult::second_player_win
            : GameResult::first_player_win;
    }
    return GameResult::draw;
}

void ChineseChessEngine::adjudicate_history() noexcept {
    if (no_capture_plies_ >= natural_limit_plies) {
        // A mating or stalemating move ends the game before the natural-limit
        // draw can override it. The normal game_result() path awards the win.
        if (has_legal_action()) {
            adjudicated_result_ = GameResult::draw;
        }
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
    const auto local_2020_result = adjudicate_2020_cycle(cycle_start);
    const auto cycle_no_capture_plies = cycle_start < history_.size()
        ? history_[cycle_start].previous_no_capture_plies
        : no_capture_plies_;
    std::vector<std::string> pikafish_moves;
    pikafish_moves.reserve(history_.size() - cycle_start);
    for (std::size_t i = cycle_start; i < history_.size(); ++i) {
        const auto& move = history_[i];
        std::string encoded{"a0a0"};
        encoded[0] = static_cast<char>('a' + move.from_x);
        encoded[1] = static_cast<char>('0' + 9 - move.from_y);
        encoded[2] = static_cast<char>('a' + move.to_x);
        encoded[3] = static_cast<char>('0' + 9 - move.to_y);
        pikafish_moves.push_back(std::move(encoded));
    }
    const auto pikafish_result = adjudicate_pikafish_repetition(
        encode_fen(
            position_history_[cycle_start].board,
            position_history_[cycle_start].side,
            cycle_no_capture_plies,
            cycle_start
        ),
        pikafish_moves
    );
    // Pikafish is the mature baseline for checks and direct chases. Keep its
    // decisive result when both judges recognize the cycle. The local 2020
    // overlay extends a Pikafish draw with kill and joint-chase outcomes.
    if (
        pikafish_result &&
        *pikafish_result != GameResult::draw
    ) {
        adjudicated_result_ = *pikafish_result;
        return;
    }
    if (
        local_2020_result &&
        *local_2020_result != GameResult::draw
    ) {
        adjudicated_result_ = *local_2020_result;
        return;
    }
    if (pikafish_result || local_2020_result) {
        adjudicated_result_ = GameResult::draw;
        return;
    }

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
