#include "mocs/engine/lanzhou_fangqi.hpp"

#include <algorithm>
#include <charconv>
#include <type_traits>

namespace mocs::engine::lanzhou_fangqi {
namespace {

// A separate test executable may lower only the write ceiling. Restore keeps the real format bound.
#if defined(MOCS_FANGQI_TEST_HISTORY_ACTIONS)
#if !defined(MOCS_FANGQI_HISTORY_LIMIT_TEST_TARGET)
#error "A reduced Fangqi history ceiling is restricted to the isolated test target"
#endif
static_assert(MOCS_FANGQI_TEST_HISTORY_ACTIONS > 0
              && MOCS_FANGQI_TEST_HISTORY_ACTIONS <= max_history_actions);
constexpr std::size_t write_history_limit = MOCS_FANGQI_TEST_HISTORY_ACTIONS;
#else
constexpr std::size_t write_history_limit = max_history_actions;
#endif

constexpr std::uint8_t opponent(std::uint8_t player) noexcept {
    return player == red ? black : red;
}

bool same_position(const Position& a, const Position& b) noexcept {
    return a.board == b.board && a.to_play == b.to_play
        && a.captures_remaining == b.captures_remaining;
}

bool parse_integer(std::string_view token, int& value) {
    if (token.empty()) return false;
    // Canonical nonnegative integers avoid alternate encodings and partial parses.
    if (token.size() > 1 && token.front() == '0') return false;
    for (const char c : token) if (c < '0' || c > '9') return false;
    const auto parsed = std::from_chars(token.data(), token.data() + token.size(), value);
    return parsed.ec == std::errc{} && parsed.ptr == token.data() + token.size();
}

bool take_token(std::string_view& input, char delimiter, std::string_view& token) {
    const auto end = input.find(delimiter);
    if (end == std::string_view::npos) return false;
    token = input.substr(0, end);
    input.remove_prefix(end + 1);
    return true;
}

ActionError step_error(StepError error) noexcept {
    switch (error) {
        case StepError::out_of_bounds: return ActionError::out_of_bounds;
        case StepError::wrong_source: return ActionError::wrong_source;
        case StepError::occupied_destination: return ActionError::occupied_destination;
        case StepError::not_adjacent: return ActionError::not_adjacent;
        case StepError::invalid_player: return ActionError::wrong_turn;
        case StepError::none: return ActionError::none;
        case StepError::invalid_board: return ActionError::wrong_source;
    }
    return ActionError::wrong_source;
}

// Only one position and the append sizes are saved; no complete history copy is needed.
class ApplyRollback final {
public:
    ApplyRollback(Position& current, std::vector<Action>& actions,
                  std::vector<Position>& history) noexcept
        : current_(current), actions_(actions), history_(history),
          previous_(current), action_size_(actions.size()), history_size_(history.size()) {}

    ApplyRollback(const ApplyRollback&) = delete;
    ApplyRollback& operator=(const ApplyRollback&) = delete;

    ~ApplyRollback() noexcept {
        if (committed_) return;
        while (actions_.size() > action_size_) actions_.pop_back();
        while (history_.size() > history_size_) history_.pop_back();
        current_ = previous_;
    }

    void commit() noexcept { committed_ = true; }

private:
    static_assert(std::is_nothrow_copy_constructible_v<Position>
                  && std::is_nothrow_copy_assignable_v<Position>);
    static_assert(std::is_nothrow_destructible_v<Position>
                  && std::is_nothrow_destructible_v<Action>);
    Position& current_;
    std::vector<Action>& actions_;
    std::vector<Position>& history_;
    Position previous_;
    std::size_t action_size_;
    std::size_t history_size_;
    bool committed_{false};
};

}  // namespace

Game::Game() : Game(initial_board(), red) {}

Game::Game(const Board& board, std::uint8_t to_play) {
    initial_.board = board;
    initial_.to_play = to_play;
    current_ = initial_;
    history_.push_back(current_);
    adjudicate();
    initial_ = current_;
    history_.back() = current_;
}

std::optional<Game> Game::from_position(const Board& board, std::uint8_t to_play) {
    if (!valid_board(board) || (to_play != red && to_play != black)
        || piece_count(board, red) + piece_count(board, black) == 0) {
        return std::nullopt;
    }
    return Game(board, to_play);
}

bool Game::repeated(const Position& candidate) const noexcept {
    return std::any_of(history_.begin(), history_.end(), [&](const Position& old) {
        return same_position(old, candidate);
    });
}

ActionResult Game::preview(const Action& action, Position& next) const {
    if (current_.outcome != Outcome::ongoing) return {ActionError::game_over, {}};
    if (action.player != current_.to_play) return {ActionError::wrong_turn, {}};
    if (action.from < 0 || action.from >= static_cast<int>(point_count)
        || action.to < 0 || action.to >= static_cast<int>(point_count)) {
        return {ActionError::out_of_bounds, {}};
    }
    next = current_;
    SquareList formed{};
    if (action.kind == ActionKind::move) {
        if (current_.captures_remaining > 0) return {ActionError::capture_required, {}};
        const auto moved = step(current_.board, action.player,
            action.from % board_width, action.from / board_width,
            action.to % board_width, action.to / board_width);
        if (!moved.accepted()) return {step_error(moved.error), {}};
        formed = newly_formed_squares(current_.board, moved.board, action.player);
        next.board = moved.board;
        next.captures_remaining = std::min(static_cast<int>(formed.size),
            piece_count(next.board, opponent(action.player)));
    } else if (action.kind == ActionKind::capture) {
        if (current_.captures_remaining == 0) {
            return {ActionError::no_capture_entitlement, {}};
        }
        if (action.from != action.to) return {ActionError::not_opponent_piece, {}};
        const auto removed = remove_opponent_piece(current_.board, action.player,
            action.to % board_width, action.to / board_width);
        if (!removed.accepted()) return {ActionError::not_opponent_piece, {}};
        next.board = removed.board;
        --next.captures_remaining;
    } else {
        return {ActionError::wrong_source, {}};
    }
    if (next.captures_remaining == 0) next.to_play = opponent(action.player);
    if (repeated(next)) return {ActionError::repeated_position, {}};
    return {ActionError::none, formed};
}

std::vector<Action> Game::legal_actions() const {
    std::vector<Action> result;
    if (current_.outcome != Outcome::ongoing) return result;
    if (current_.captures_remaining > 0) {
        for (int point = 0; point < static_cast<int>(point_count); ++point) {
            if (current_.board[static_cast<std::size_t>(point)] != opponent(current_.to_play)) {
                continue;
            }
            Action action{ActionKind::capture, current_.to_play, point, point};
            Position next;
            if (preview(action, next).accepted()) result.push_back(action);
        }
        return result;
    }
    for (int from = 0; from < static_cast<int>(point_count); ++from) {
        if (current_.board[static_cast<std::size_t>(from)] != current_.to_play) continue;
        const int x = from % board_width;
        const int y = from / board_width;
        constexpr int dx[] = {-1, 1, 0, 0};
        constexpr int dy[] = {0, 0, -1, 1};
        for (int direction = 0; direction < 4; ++direction) {
            if (!on_board(x + dx[direction], y + dy[direction])) continue;
            Action action{ActionKind::move, current_.to_play, from,
                static_cast<int>(point_index(x + dx[direction], y + dy[direction]))};
            Position next;
            if (preview(action, next).accepted()) result.push_back(action);
        }
    }
    return result;
}

void Game::adjudicate() {
    current_.outcome = Outcome::ongoing;
    const auto side = current_.to_play;
    std::uint8_t winner = empty;
    if (piece_count(current_.board, side) == 0) winner = opponent(side);
    else if (piece_count(current_.board, opponent(side)) == 0) winner = side;
    else if (current_.captures_remaining == 0 && legal_actions().empty()) {
        winner = opponent(side);
    }
    if (winner != empty) current_.outcome = winner == red ? Outcome::red_wins : Outcome::black_wins;
}

ActionResult Game::apply(const Action& action) {
    return apply_impl(action, true);
}

ActionResult Game::apply_impl(const Action& action, const bool reserve_complete_turn) {
    Position next;
    const auto result = preview(action, next);
    if (!result.accepted()) return result;
    // This is a recording resource limit, not a rule that makes a side lose its legal moves.
    // Replaying a bounded old snapshot may end mid-capture; preserve it so undo/backup remain possible.
    const auto limit = reserve_complete_turn ? write_history_limit : max_history_actions;
    const auto captures_to_reserve = reserve_complete_turn ? next.captures_remaining : 0;
    if (!complete_turn_fits(actions_.size(), captures_to_reserve, limit)) {
        return {ActionError::resource_limit, {}};
    }
    ApplyRollback rollback(current_, actions_, history_);
    current_ = next;
    actions_.push_back(action);
    history_.push_back(current_);
    // Wait until every requested capture is selected before checking blocked enemy pieces.
    adjudicate();
    history_.back() = current_;
    rollback.commit();
    return result;
}

ActionResult Game::move(std::uint8_t player, int from_x, int from_y, int to_x, int to_y) {
    if (!on_board(from_x, from_y) || !on_board(to_x, to_y)) {
        return {ActionError::out_of_bounds, {}};
    }
    return apply({ActionKind::move, player, static_cast<int>(point_index(from_x, from_y)),
        static_cast<int>(point_index(to_x, to_y))});
}

ActionResult Game::capture(std::uint8_t player, int x, int y) {
    if (!on_board(x, y)) return {ActionError::out_of_bounds, {}};
    const auto point = static_cast<int>(point_index(x, y));
    return apply({ActionKind::capture, player, point, point});
}

bool Game::undo() {
    if (actions_.empty()) return false;
    std::size_t start = actions_.size() - 1;
    while (start > 0 && actions_[start].kind != ActionKind::move) --start;
    actions_.resize(start);
    history_.resize(start + 1);
    current_ = history_.back();
    return true;
}

std::string Game::serialize() const {
    std::string result = "MOCS-LFQ|1|" + std::to_string(initial_.to_play) + '|';
    for (const auto piece : initial_.board) result += static_cast<char>('0' + piece);
    result += '|' + std::to_string(actions_.size()) + '|';
    for (std::size_t i = 0; i < actions_.size(); ++i) {
        if (i != 0) result += ';';
        const auto& action = actions_[i];
        result += action.kind == ActionKind::move ? 'm' : 'c';
        result += ',' + std::to_string(action.player) + ',' + std::to_string(action.from)
            + ',' + std::to_string(action.to);
    }
    return result;
}

std::optional<Game> Game::deserialize(std::string_view snapshot, RestoreError& error) {
    error = RestoreError::malformed;
    // Defensive restore limits bound memory and replay work; callers retain oversized data.
    if (snapshot.size() > max_snapshot_bytes) {
        error = RestoreError::resource_limit;
        return std::nullopt;
    }
    std::string_view token;
    if (!take_token(snapshot, '|', token) || token != "MOCS-LFQ") return std::nullopt;
    if (!take_token(snapshot, '|', token)) return std::nullopt;
    if (token != "1") { error = RestoreError::unsupported_version; return std::nullopt; }
    int player;
    if (!take_token(snapshot, '|', token) || !parse_integer(token, player)) return std::nullopt;
    if (player != red && player != black) {
        error = RestoreError::invalid_initial_position;
        return std::nullopt;
    }
    if (!take_token(snapshot, '|', token) || token.size() != point_count) return std::nullopt;
    Board board{};
    for (std::size_t i = 0; i < board.size(); ++i) {
        if (token[i] < '0' || token[i] > '2') {
            error = RestoreError::invalid_initial_position;
            return std::nullopt;
        }
        board[i] = static_cast<std::uint8_t>(token[i] - '0');
    }
    auto game = from_position(board, static_cast<std::uint8_t>(player));
    if (!game) { error = RestoreError::invalid_initial_position; return std::nullopt; }
    int count;
    if (!take_token(snapshot, '|', token) || !parse_integer(token, count)) return std::nullopt;
    if (static_cast<std::size_t>(count) > max_history_actions) {
        error = RestoreError::resource_limit;
        return std::nullopt;
    }
    for (int i = 0; i < count; ++i) {
        std::string_view encoded;
        if (i + 1 < count) {
            if (!take_token(snapshot, ';', encoded)) return std::nullopt;
        } else { encoded = snapshot; snapshot = {}; }
        std::string_view part;
        Action action;
        if (!take_token(encoded, ',', part) || (part != "m" && part != "c")) return std::nullopt;
        action.kind = part == "m" ? ActionKind::move : ActionKind::capture;
        if (!take_token(encoded, ',', part) || !parse_integer(part, player)) return std::nullopt;
        if (player != red && player != black) { error = RestoreError::illegal_action; return std::nullopt; }
        action.player = static_cast<std::uint8_t>(player);
        if (!take_token(encoded, ',', part) || !parse_integer(part, action.from)
            || !parse_integer(encoded, action.to)) return std::nullopt;
        const auto replayed = game->apply_impl(action, false);
        if (!replayed.accepted()) {
            error = replayed.error == ActionError::resource_limit
                ? RestoreError::resource_limit : RestoreError::illegal_action;
            return std::nullopt;
        }
    }
    if (!snapshot.empty()) return std::nullopt;
    error = RestoreError::none;
    return game;
}

}  // namespace mocs::engine::lanzhou_fangqi
