#include "mocs/engine/lanzhou_fangqi_ai.hpp"

#include <cstdlib>
#include <iostream>
#include <limits>
#include <string>
#include <vector>

#if defined(NDEBUG)
#error "Fangqi resource tests require assertions enabled"
#endif
#if !defined(MOCS_FANGQI_HISTORY_LIMIT_TEST_TARGET) || !defined(MOCS_FANGQI_TEST_HISTORY_ACTIONS)
#error "Compile only as an isolated reduced-write-limit test executable"
#endif
static_assert(MOCS_FANGQI_TEST_HISTORY_ACTIONS == 6);

namespace fq = mocs::engine::lanzhou_fangqi;
static_assert(fq::max_history_actions == 16'384);
static_assert(fq::max_snapshot_bytes == 1024 * 1024);
static_assert(fq::complete_turn_fits(16'383, 0));
static_assert(!fq::complete_turn_fits(16'384, 0));
static_assert(fq::complete_turn_fits(16'380, 3));
static_assert(!fq::complete_turn_fits(16'381, 3));
static_assert(!fq::complete_turn_fits(std::numeric_limits<std::size_t>::max(), 0));
static_assert(!fq::complete_turn_fits(0, -1));
static_assert(!fq::complete_turn_fits(0, std::numeric_limits<int>::max()));

namespace {
std::size_t checks = 0;
void require(const bool value, const char* message) {
    ++checks;
    if (!value) { std::cerr << "FAILED: " << message << '\n'; std::exit(1); }
}

fq::Action move(const std::uint8_t side, int x, int y, int to_x, int to_y) {
    return {fq::ActionKind::move, side, static_cast<int>(fq::point_index(x, y)),
            static_cast<int>(fq::point_index(to_x, to_y))};
}
fq::Action capture(const std::uint8_t side, int x, int y) {
    const auto point = static_cast<int>(fq::point_index(x, y));
    return {fq::ActionKind::capture, side, point, point};
}
bool same_actions(const std::vector<fq::Action>& a, const std::vector<fq::Action>& b) {
    if (a.size() != b.size()) return false;
    for (std::size_t i = 0; i < a.size(); ++i) {
        if (a[i].kind != b[i].kind || a[i].player != b[i].player ||
            a[i].from != b[i].from || a[i].to != b[i].to) return false;
    }
    return true;
}

void accepted(fq::Game& game, const fq::Action& action) {
    require(game.apply(action).accepted(), "authored legal action accepted within write capacity");
}

void reject_unchanged(fq::Game& game, const fq::Action& action) {
    const auto saved = game.serialize();
    const auto before = game.position();
    const auto actions = game.actions();
    const auto history = game.history_size();
    const auto legal = game.legal_actions();
    require(game.apply(action).error == fq::ActionError::resource_limit, "capacity rejection is explicit");
    require(game.serialize() == saved, "capacity rejection leaves canonical bytes unchanged");
    require(game.position().board == before.board && game.position().to_play == before.to_play &&
        game.position().captures_remaining == before.captures_remaining &&
        game.position().outcome == before.outcome, "capacity rejection leaves complete position unchanged");
    require(same_actions(game.actions(), actions) && game.history_size() == history,
        "capacity rejection leaves action and repetition history unchanged");
    require(same_actions(game.legal_actions(), legal), "resource limits do not change rule-legal moves");
    fq::RestoreError error;
    const auto restored = fq::Game::deserialize(saved, error);
    require(restored && error == fq::RestoreError::none && restored->serialize() == saved,
        "retained snapshot still passes full rule replay");
}

fq::Board square_board(const bool three_black = true) {
    fq::Board board{};
    for (const int point : {0, 2, 14, 8, 9, 15, 23, 4}) board[static_cast<std::size_t>(point)] = fq::red;
    board[fq::point_index(5, 5)] = fq::black;
    if (three_black) {
        board[fq::point_index(6, 5)] = fq::black;
        board[fq::point_index(6, 6)] = fq::black;
    }
    return board;
}

void six_quiet_steps_and_undo() {
    fq::Game game;
    for (const auto action : {
        move(fq::red, 0, 5, 0, 4), move(fq::black, 0, 1, 0, 2),
        move(fq::red, 0, 4, 0, 3), move(fq::black, 0, 2, 1, 2),
        move(fq::red, 0, 3, 1, 3), move(fq::black, 1, 2, 2, 2),
    }) accepted(game, action);
    require(game.actions().size() == 6 && game.position().outcome == fq::Outcome::ongoing,
        "isolated test ceiling reached without turning a legal position into a loss");
    require(!game.legal_actions().empty(), "ordinary rule moves remain at the recording ceiling");
    reject_unchanged(game, move(fq::red, 1, 3, 2, 3));
    require(game.apply(move(fq::black, 2, 2, 3, 2)).error == fq::ActionError::wrong_turn,
        "rule error precedence is preserved before resource checks");
    const auto bytes = game.serialize();
    for (const auto level : {fq::AiLevel::easy, fq::AiLevel::medium, fq::AiLevel::hard, fq::AiLevel::master}) {
        require(!fq::choose_action(game, level), "AI has no recordable child at the test ceiling");
        require(game.serialize() == bytes, "capacity-exhausted AI does not modify the game");
    }
    require(game.undo() && game.actions().size() == 5, "whole-turn undo reclaims recording space");
    accepted(game, move(fq::black, 1, 2, 0, 2));
    require(game.actions().size() == 6 && game.position().outcome == fq::Outcome::ongoing,
        "an alternative legal turn can continue after undo");
}

void reserve_the_whole_capture_chain() {
    auto game = fq::Game::from_position(square_board(), fq::red);
    require(game.has_value(), "multi-capture seed accepted by real rules");
    for (const auto action : {
        move(fq::red, 4, 0, 4, 1), move(fq::black, 5, 5, 5, 4),
        move(fq::red, 4, 1, 4, 0), move(fq::black, 5, 4, 4, 4),
    }) accepted(*game, action);
    require(game->actions().size() == 4, "two slots remain before the proposed move");
    // The move forms two new squares: it needs one move plus two player-selected captures.
    reject_unchanged(*game, move(fq::red, 2, 3, 2, 2));
    require(game->position().captures_remaining == 0 && game->position().outcome == fq::Outcome::ongoing,
        "a refused move creates neither stranded capture entitlement nor a false loss");

    auto exact = fq::Game::from_position(square_board(), fq::black);
    require(exact.has_value(), "exact-capacity seed accepted");
    for (const auto action : {
        move(fq::black, 5, 5, 5, 4), move(fq::red, 4, 0, 4, 1), move(fq::black, 5, 4, 4, 4),
    }) accepted(*exact, action);
    accepted(*exact, move(fq::red, 2, 3, 2, 2));
    require(exact->position().captures_remaining == 2, "exact capacity reserves both requested captures");
    accepted(*exact, capture(fq::red, 6, 6));
    require(exact->position().captures_remaining == 1, "first selection preserves one reserved capture");
    accepted(*exact, capture(fq::red, 6, 5));
    require(exact->actions().size() == 6 && exact->position().captures_remaining == 0 &&
        exact->position().to_play == fq::black && exact->position().outcome == fq::Outcome::ongoing,
        "the entire selected chain finishes at exact capacity without changing rule adjudication");
}

std::string recorded_fixture(const fq::Board& board, const std::vector<fq::Action>& actions) {
    std::string result = "MOCS-LFQ|1|1|";
    for (const auto piece : board) result += static_cast<char>('0' + piece);
    result += '|' + std::to_string(actions.size()) + '|';
    for (std::size_t i = 0; i < actions.size(); ++i) {
        if (i != 0) result += ';';
        const auto& action = actions[i];
        result += action.kind == fq::ActionKind::move ? 'm' : 'c';
        result += ',' + std::to_string(action.player) + ',' + std::to_string(action.from) + ',' + std::to_string(action.to);
    }
    return result;
}

void old_partial_history_remains_recoverable() {
    // Six authored legal actions from an old unlimited writer, independently replayed by deserialize.
    // This is the isolated six-action boundary, not a synthetic claim of a valid 16,384-step game.
    const auto bytes = recorded_fixture(square_board(), {
        move(fq::red, 4, 0, 4, 1), move(fq::black, 5, 5, 5, 4),
        move(fq::red, 4, 1, 4, 0), move(fq::black, 5, 4, 4, 4),
        move(fq::red, 2, 3, 2, 2), capture(fq::red, 6, 6),
    });
    fq::RestoreError error;
    auto game = fq::Game::deserialize(bytes, error);
    require(game && error == fq::RestoreError::none && game->serialize() == bytes,
        "old legal partial capture history is restored byte-for-byte");
    require(game->actions().size() == 6 && game->position().captures_remaining == 1,
        "restore does not remove a pending capture to satisfy the new-write policy");
    reject_unchanged(*game, capture(fq::red, 6, 5));
    require(game->undo() && game->actions().size() == 4 && game->position().captures_remaining == 0,
        "whole-turn undo recovers the old boundary history");
    accepted(*game, move(fq::red, 4, 0, 5, 0));
    require(game->position().outcome == fq::Outcome::ongoing, "recovered history can continue legally");
}

void a_real_capture_win_at_the_ceiling_still_wins() {
    auto game = fq::Game::from_position(square_board(false), fq::red);
    require(game.has_value(), "single-enemy seed accepted");
    for (const auto action : {
        move(fq::red, 4, 0, 4, 1), move(fq::black, 5, 5, 5, 4),
        move(fq::red, 4, 1, 4, 0), move(fq::black, 5, 4, 4, 4),
        move(fq::red, 2, 3, 2, 2), capture(fq::red, 4, 4),
    }) accepted(*game, action);
    require(game->actions().size() == 6 && game->position().outcome == fq::Outcome::red_wins,
        "actual capture terminal result is preserved at exact capacity");
    require(game->apply(move(fq::black, 4, 4, 4, 5)).error == fq::ActionError::game_over,
        "terminal rule rejection still precedes the recording ceiling");
}

void real_restore_header_bounds_are_still_enforced() {
    std::string over_count = "MOCS-LFQ|1|1|";
    for (const auto piece : fq::initial_board()) over_count += static_cast<char>('0' + piece);
    over_count += "|16385|";
    fq::RestoreError error;
    require(!fq::Game::deserialize(over_count, error) && error == fq::RestoreError::resource_limit,
        "a declared count above the real production bound is rejected before replay");
    require(!fq::Game::deserialize(std::string(fq::max_snapshot_bytes + 1, '0'), error) &&
        error == fq::RestoreError::resource_limit, "real one-MiB restore limit remains enforced");
}
}  // namespace

int main() {
    six_quiet_steps_and_undo();
    reserve_the_whole_capture_chain();
    old_partial_history_remains_recoverable();
    a_real_capture_win_at_the_ceiling_still_wins();
    real_restore_header_bounds_are_still_enforced();
    std::cout << "Fangqi history capacity (isolated write ceiling 6; production ceiling 16384): "
              << checks << " checks passed.\n";
}
