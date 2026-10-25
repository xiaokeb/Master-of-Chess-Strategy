#include "mocs/engine/lanzhou_fangqi.hpp"

#include <cstdlib>
#include <iostream>
#include <string>

namespace fq = mocs::engine::lanzhou_fangqi;

namespace {
int checks = 0;
void require(bool condition, const char* message) {
    ++checks;
    if (!condition) {
        std::cerr << "FAIL: " << message << '\n';
        std::exit(1);
    }
}

void put(fq::Board& board, std::uint8_t side, int x, int y) {
    board[fq::point_index(x, y)] = side;
}

fq::Game seed(const fq::Board& board, std::uint8_t side = fq::red) {
    auto game = fq::Game::from_position(board, side);
    require(game.has_value(), "fixture must be a valid board");
    return *game;
}

fq::Board two_square_board(int enemies = 3) {
    fq::Board board{};
    for (const auto point : {0, 2, 14, 8, 9, 15, 23}) {
        board[static_cast<std::size_t>(point)] = fq::red;
    }
    if (enemies > 0) put(board, fq::black, 5, 5);
    if (enemies > 1) put(board, fq::black, 6, 5);
    if (enemies > 2) put(board, fq::black, 6, 6);
    return board;
}

void official_opening_and_illegal_actions() {
    fq::Game game;
    require(game.position().board == fq::initial_board(), "official 14 + 14 initial rows");
    require(game.position().to_play == fq::red, "red moves first");
    require(game.position().captures_remaining == 0, "initial squares grant no captures");
    require(game.legal_actions().size() == 7, "only red front row has seven initial exits");
    const auto unchanged = game.serialize();
    require(game.move(fq::black, 0, 1, 0, 2).error == fq::ActionError::wrong_turn,
        "wrong side cannot act");
    require(game.move(fq::red, 0, 5, 1, 4).error == fq::ActionError::not_adjacent,
        "diagonal line is absent");
    require(game.move(fq::red, 0, 5, 0, 3).error == fq::ActionError::not_adjacent,
        "jump is absent");
    require(game.move(fq::red, 0, 5, 0, 6).error == fq::ActionError::occupied_destination,
        "own occupied destination rejected");
    require(game.move(fq::red, 0, 0, 0, 2).error == fq::ActionError::wrong_source,
        "enemy source rejected");
    require(game.capture(fq::red, 0, 0).error == fq::ActionError::no_capture_entitlement,
        "cannot remove without a new square");
    require(game.move(fq::red, -1, 5, 0, 4).error == fq::ActionError::out_of_bounds,
        "negative coordinates rejected");
    require(game.apply({fq::ActionKind::move, fq::red, 49, 1}).error
        == fq::ActionError::out_of_bounds, "invalid point index rejected");
    require(game.serialize() == unchanged, "every invalid action is atomic");
    require(game.move(fq::red, 0, 5, 0, 4).accepted(), "legal opening step");
    require(game.position().to_play == fq::black, "ordinary move changes side");
    require(game.position().captures_remaining == 0, "old squares are not rewarded again");
    require(game.undo() && game.serialize() == unchanged, "undo opening restores history");
    require(!game.undo(), "cannot undo before initial position");
}

void multi_capture_and_pending_restore() {
    auto game = seed(two_square_board());
    const auto original = game.serialize();
    const auto result = game.move(fq::red, 2, 3, 2, 2);
    require(result.accepted() && result.formed.size == 2, "one step forms unit and span-two squares");
    require(result.formed.squares[0].span == 1 && result.formed.squares[1].span == 2,
        "all integer spans are enumerated deterministically");
    require(game.position().to_play == fq::red && game.position().captures_remaining == 2,
        "two captures stay with mover");
    require(game.legal_actions().size() == 3, "player may choose any of the three enemy pieces");
    require(game.move(fq::red, 2, 2, 3, 2).error == fq::ActionError::capture_required,
        "cannot step during selection");
    const auto pending = game.serialize();
    require(game.capture(fq::red, 2, 2).error == fq::ActionError::not_opponent_piece,
        "own corner cannot be captured");
    require(game.capture(fq::red, 4, 4).error == fq::ActionError::not_opponent_piece,
        "empty target cannot be captured");
    require(game.capture(fq::black, 5, 5).error == fq::ActionError::wrong_turn,
        "opponent cannot choose captures");
    require(game.serialize() == pending, "invalid selection does not consume entitlement");
    require(game.capture(fq::red, 6, 6).accepted(), "player chosen first target is removed");
    require(game.position().board[fq::point_index(6, 6)] == fq::empty
        && game.position().board[fq::point_index(5, 5)] == fq::black,
        "engine does not choose another target");
    require(game.position().to_play == fq::red && game.position().captures_remaining == 1,
        "one remaining target keeps same side");
    const auto after_one = game.serialize();
    require(game.capture(fq::red, 6, 6).error == fq::ActionError::not_opponent_piece,
        "removed target cannot be selected twice");
    require(game.serialize() == after_one, "repeated target is atomic");
    fq::RestoreError error;
    auto restored = fq::Game::deserialize(after_one, error);
    require(restored && error == fq::RestoreError::none && restored->serialize() == after_one,
        "pending capture snapshot round trips");
    require(restored->position().captures_remaining == 1 && restored->history_size() == 3,
        "pending entitlement and intermediate history are rebuilt");
    require(restored->capture(fq::red, 5, 5).accepted(), "restored player selects final target");
    require(restored->position().to_play == fq::black && restored->position().captures_remaining == 0,
        "side changes only after final selected removal");
    require(restored->undo() && restored->serialize() == original,
        "undo completed multi-capture removes full turn and history");
    require(game.undo() && game.serialize() == original, "undo partly selected turn returns to seed");
}

void reforming_old_square() {
    fq::Board board{};
    put(board, fq::red, 0, 0); put(board, fq::red, 1, 0);
    put(board, fq::red, 0, 1); put(board, fq::red, 1, 1);
    put(board, fq::black, 5, 5); put(board, fq::black, 6, 6);
    auto game = seed(board);
    require(game.position().captures_remaining == 0, "seed square grants no entitlement");
    require(game.move(fq::red, 1, 1, 1, 2).accepted(), "old square may be broken");
    require(game.move(fq::black, 5, 5, 5, 4).accepted(), "enemy moves in between");
    const auto reformed = game.move(fq::red, 1, 2, 1, 1);
    require(reformed.accepted() && reformed.formed.size == 1
        && game.position().captures_remaining == 1,
        "reforming same historic four corners grants new capture");
    require(game.capture(fq::red, 6, 6).accepted(), "reformed square entitlement is usable");
}

void square_corner_geometry() {
    fq::Board board{};
    put(board, fq::red, 0, 0); put(board, fq::red, 3, 0);
    put(board, fq::red, 0, 3); put(board, fq::red, 3, 4);
    put(board, fq::black, 1, 1); put(board, fq::black, 6, 6);
    auto game = seed(board);
    auto moved = game.move(fq::red, 3, 4, 3, 3);
    require(moved.accepted() && moved.formed.size == 1 && moved.formed.squares[0].span == 3,
        "interior enemy does not invalidate a larger corner-only square");
    fq::Board diagonal{};
    put(diagonal, fq::red, 3, 1); put(diagonal, fq::red, 1, 3);
    put(diagonal, fq::red, 5, 3); put(diagonal, fq::red, 3, 6);
    put(diagonal, fq::black, 6, 0);
    auto diamond = seed(diagonal);
    moved = diamond.move(fq::red, 3, 6, 3, 5);
    require(moved.accepted() && moved.formed.size == 0,
        "rotated diamond is not an axis aligned square");
    fq::Board edge{};
    put(edge, fq::red, 0, 0); put(edge, fq::red, 6, 0);
    put(edge, fq::red, 0, 6); put(edge, fq::red, 5, 6);
    put(edge, fq::black, 3, 3);
    auto maximum = seed(edge);
    moved = maximum.move(fq::red, 5, 6, 6, 6);
    require(moved.accepted() && moved.formed.size == 1 && moved.formed.squares[0].span == 6,
        "maximum span-six board edge square is rewarded");
}

void capture_in_existing_enemy_square_is_allowed() {
    auto board = two_square_board(0);
    put(board, fq::black, 5, 5); put(board, fq::black, 6, 5);
    put(board, fq::black, 5, 6); put(board, fq::black, 6, 6);
    auto game = seed(board);
    require(game.move(fq::red, 2, 3, 2, 2).accepted(), "two-square entitlement built");
    require(game.capture(fq::red, 5, 5).accepted(), "enemy square corner has no capture protection");
    require(game.capture(fq::red, 6, 6).accepted(), "second chosen corner may also be removed");
}

void first_repeat_and_undo_restore() {
    fq::Board board{};
    put(board, fq::red, 0, 0); put(board, fq::black, 6, 6);
    auto game = seed(board);
    require(game.move(fq::red, 0, 0, 1, 0).accepted(), "cycle first move");
    require(game.move(fq::black, 6, 6, 5, 6).accepted(), "cycle second move");
    require(game.move(fq::red, 1, 0, 0, 0).accepted(), "cycle third move");
    const auto before_repeat = game.serialize();
    require(game.move(fq::black, 5, 6, 6, 6).error == fq::ActionError::repeated_position,
        "initial full state cannot reappear for the first time");
    require(game.serialize() == before_repeat && game.history_size() == 4,
        "repeat rejection is atomic");
    for (const auto& action : game.legal_actions()) {
        require(!(action.from == 47 && action.to == 48), "forbidden cycle not offered as legal move");
    }
    fq::RestoreError error;
    auto restored = fq::Game::deserialize(before_repeat, error);
    require(restored && restored->move(fq::black, 5, 6, 6, 6).error
        == fq::ActionError::repeated_position, "restored entire history rejects same repeat");
    require(restored->undo() && restored->history_size() == 3, "undo also removes historical state");
    require(restored->move(fq::red, 1, 0, 0, 0).accepted(),
        "state deleted by undo may legally be reached again");
    auto forged = before_repeat;
    const auto count_start = forged.find('|', forged.find('|', forged.find('|') + 1) + 1);
    const auto count_end = forged.find('|', count_start + 1);
    // Locate count after the 49-character seed rather than relying on current outcome.
    const auto action_count = forged.find('|', count_end + 1);
    require(action_count != std::string::npos, "snapshot count delimiter exists");
    forged.replace(count_end + 1, action_count - count_end - 1, "4");
    forged += ";m,2,47,48";
    require(!fq::Game::deserialize(forged, error) && error == fq::RestoreError::illegal_action,
        "restore cannot smuggle a forbidden repetition");
}

void terminal_conditions_and_undo() {
    auto game = seed(two_square_board(1));
    const auto original = game.serialize();
    require(game.move(fq::red, 2, 3, 2, 2).accepted() && game.position().captures_remaining == 1,
        "capture count capped by surviving enemies");
    require(game.position().outcome == fq::Outcome::ongoing, "win waits for selected capture");
    require(game.capture(fq::red, 5, 5).accepted() && game.position().outcome == fq::Outcome::red_wins,
        "taking last enemy wins immediately");
    require(game.legal_actions().empty(), "terminal game has no legal actions");
    require(game.move(fq::black, 5, 5, 5, 4).error == fq::ActionError::game_over,
        "terminal state rejects further moves");
    fq::RestoreError error;
    auto restored = fq::Game::deserialize(game.serialize(), error);
    require(restored && restored->position().outcome == fq::Outcome::red_wins,
        "terminal outcome is reconstructed by replay");
    require(restored->undo() && restored->serialize() == original, "undo terminal multi-capture turn");
    fq::Board blocked{};
    put(blocked, fq::black, 0, 0);
    put(blocked, fq::red, 1, 0); put(blocked, fq::red, 0, 2);
    auto trap = seed(blocked);
    require(trap.move(fq::red, 0, 2, 0, 1).accepted(), "blocking step remains legal");
    require(trap.position().outcome == fq::Outcome::red_wins, "enemy with no adjacent exit loses");
    auto blocked_black = seed(trap.position().board, fq::black);
    require(blocked_black.position().outcome == fq::Outcome::red_wins, "blocked terminal seed recognized");
    fq::Board black_only{};
    put(black_only, fq::black, 3, 3);
    require(seed(black_only).position().outcome == fq::Outcome::black_wins,
        "empty current side is already defeated");
    require(seed(black_only, fq::black).position().outcome == fq::Outcome::black_wins,
        "empty opponent is already defeated");
    require(!fq::Game::from_position(fq::Board{}, fq::red), "empty-empty seed has no winner and is invalid");
}

void strict_restore_rejects_bad_data() {
    fq::Game game;
    fq::RestoreError error;
    require(fq::Game::deserialize(game.serialize(), error).has_value(), "empty transcript snapshot restores");
    const auto valid = game.serialize();
    auto malformed = valid;
    malformed.replace(9, 1, "2");
    require(!fq::Game::deserialize(malformed, error) && error == fq::RestoreError::unsupported_version,
        "unknown version explicitly rejected");
    require(!fq::Game::deserialize(valid + "unexpected", error) && error == fq::RestoreError::malformed,
        "unconsumed trailing bytes rejected");
    require(!fq::Game::deserialize(valid.substr(0, valid.size() - 1), error), "truncated delimiter rejected");
    malformed = valid;
    malformed.replace(malformed.rfind("|0|"), 3, "|01|");
    require(!fq::Game::deserialize(malformed, error), "noncanonical count rejected");
    malformed = valid;
    malformed[13] = '3';
    require(!fq::Game::deserialize(malformed, error) && error == fq::RestoreError::invalid_initial_position,
        "invalid board token rejected");
    malformed = valid;
    malformed.replace(malformed.rfind("|0|"), 3, "|1|");
    malformed += "c,1,0,0";
    require(!fq::Game::deserialize(malformed, error) && error == fq::RestoreError::illegal_action,
        "forged capture cannot bypass entitlement");
    malformed = valid;
    malformed.replace(malformed.rfind("|0|"), 3, "|1|");
    malformed += "m,2,7,14";
    require(!fq::Game::deserialize(malformed, error) && error == fq::RestoreError::illegal_action,
        "forged side cannot bypass turn");
    require(!fq::Game::deserialize(std::string(1024 * 1024 + 1, 'x'), error)
        && error == fq::RestoreError::resource_limit, "oversized restore is bounded");
    malformed = valid;
    malformed.replace(malformed.rfind("|0|"), 3, "|16385|");
    require(!fq::Game::deserialize(malformed, error) && error == fq::RestoreError::resource_limit,
        "excessive replay count is bounded");
}

void deterministic_playout_round_trips() {
    fq::Game game;
    std::uint32_t random = 0x01991001U;
    for (int turn = 0; turn < 120 && game.position().outcome == fq::Outcome::ongoing; ++turn) {
        const auto legal = game.legal_actions();
        require(!legal.empty(), "ongoing playout must have an action");
        random = random * 1664525U + 1013904223U;
        require(game.apply(legal[random % legal.size()]).accepted(), "enumerated action executes");
        require(fq::valid_board(game.position().board), "playout preserves piece bounds");
        fq::RestoreError error;
        const auto snapshot = game.serialize();
        auto restored = fq::Game::deserialize(snapshot, error);
        require(restored && restored->serialize() == snapshot
            && restored->position().board == game.position().board
            && restored->position().to_play == game.position().to_play
            && restored->position().captures_remaining == game.position().captures_remaining
            && restored->position().outcome == game.position().outcome,
            "each legal move or selected capture round trips by full replay");
    }
}
}  // namespace

int main() {
    official_opening_and_illegal_actions();
    multi_capture_and_pending_restore();
    reforming_old_square();
    square_corner_geometry();
    capture_in_existing_enemy_square_is_allowed();
    first_repeat_and_undo_restore();
    terminal_conditions_and_undo();
    strict_restore_rejects_bad_data();
    deterministic_playout_round_trips();
    std::cout << "Lanzhou fangqi full-turn tests passed (" << checks << " checks).\n";
}
