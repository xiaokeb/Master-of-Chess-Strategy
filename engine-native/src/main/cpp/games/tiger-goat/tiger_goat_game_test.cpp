#include "mocs/engine/tiger_goat_game.hpp"
#include "mocs/engine/tiger_goat_wire.hpp"

#include <algorithm>
#include <cassert>
#include <cstdint>
#include <cstdio>
#include <initializer_list>
#include <vector>

namespace rules = mocs::engine::tiger_goat;

namespace {

bool contains(const std::vector<rules::Action>& actions, const rules::Action target) {
    return std::find(actions.begin(), actions.end(), target) != actions.end();
}

void update_checksum(std::vector<std::uint8_t>& bytes) {
    std::uint32_t hash = 2166136261u;
    for (std::size_t index = 0; index < bytes.size() - 4; ++index) {
        hash = (hash ^ bytes[index]) * 16777619u;
    }
    for (int shift = 0; shift < 32; shift += 8) {
        bytes[bytes.size() - 4 + static_cast<std::size_t>(shift / 8)] =
            static_cast<std::uint8_t>(hash >> shift);
    }
}

void assert_rejected(rules::TigerGoatGame& game, const rules::Action action,
                     const rules::ApplyError error) {
    const auto saved = game.serialize();
    const auto count = game.action_count();
    assert(game.apply(action) == error);
    assert(game.serialize() == saved && game.action_count() == count);
}

rules::MatchState position_with(const std::initializer_list<int> tiger_points,
                                const std::initializer_list<int> goat_points,
                                const int reserve = 0) {
    rules::MatchState position{};
    position.board.fill(rules::empty);
    for (const int point : tiger_points) {
        position.board[static_cast<std::size_t>(point)] = rules::tiger;
    }
    for (const int point : goat_points) {
        assert(position.board[static_cast<std::size_t>(point)] == rules::empty);
        position.board[static_cast<std::size_t>(point)] = rules::goat;
    }
    position.reserve = static_cast<std::uint8_t>(reserve);
    position.captured = static_cast<std::uint8_t>(22 - reserve - goat_points.size());
    position.phase = rules::Phase::play;
    position.to_move = rules::tiger;
    return position;
}

void test_opening_and_placement() {
    rules::TigerGoatGame game;
    assert(game.state().phase == rules::Phase::opening_removal);
    assert(game.state().reserve == 14);
    assert(game.legal_actions().size() == 8);
    const auto original = game.serialize();
    assert(game.apply({rules::ActionKind::place_goat, -1, 0})
           == rules::ApplyError::wrong_phase);
    assert(game.serialize() == original);
    assert(game.apply({rules::ActionKind::remove_goat, 6, -1})
           == rules::ApplyError::none);
    assert(game.state().board[6] == rules::empty);
    assert(game.state().reserve == 14 && game.state().captured == 1);
    assert(game.state().to_move == rules::goat);
    assert(game.state().outcome == rules::Outcome::ongoing);
    assert(game.apply({rules::ActionKind::step, 7, 6}) == rules::ApplyError::wrong_phase);
    assert(game.apply({rules::ActionKind::place_goat, -1, 6}) == rules::ApplyError::none);
    assert(game.state().reserve == 13);
    assert(game.state().board[6] == rules::goat);
    assert(game.state().to_move == rules::tiger);
    assert(game.undo());
    assert(game.state().reserve == 14 && game.state().board[6] == rules::empty);
    assert(game.undo());
    assert(game.state().phase == rules::Phase::opening_removal);
    assert(!game.undo());
}

void test_normal_steps_and_placement_end() {
    rules::TigerGoatGame game;
    auto seed = position_with({10, 14},
                              {6, 7, 8, 11, 13, 16, 17, 18, 0, 1,
                               2, 3, 4, 5, 9, 15, 19, 20, 21, 23}, 1);
    seed.to_move = rules::goat;
    assert(game.reset_to_position(seed));
    assert(contains(game.legal_actions(), {rules::ActionKind::place_goat, -1, 22}));
    assert(game.apply({rules::ActionKind::place_goat, -1, 22}) == rules::ApplyError::none);
    assert(game.state().reserve == 0);
    assert(game.state().to_move == rules::tiger);
    const auto tiger_actions = game.legal_actions();
    const auto move = std::find_if(tiger_actions.begin(), tiger_actions.end(),
                                   [](const rules::Action& action) {
                                       return action.kind == rules::ActionKind::step;
                                   });
    assert(move != tiger_actions.end());
    assert(game.apply(*move) == rules::ApplyError::none);
    assert(game.state().to_move == rules::goat);
    assert(game.state().reserve == 0);
    const auto goat_actions = game.legal_actions();
    assert(std::any_of(goat_actions.begin(), goat_actions.end(),
                       [](const rules::Action& action) {
                           return action.kind == rules::ActionKind::step;
                       }));
}

void test_capture_chain_turning_and_undo() {
    rules::TigerGoatGame game;
    // The third landing still has a legal onward jump (22 -> 20 over 21).
    // Thus 22 -> 12 tests a visited landing while the chain remains active.
    const auto seed = position_with({12, 0}, {13, 19, 23, 17, 21, 1, 2, 3, 4, 5, 6, 9});
    assert(game.reset_to_position(seed));
    assert(contains(game.legal_actions(), {rules::ActionKind::capture, 12, 14}));
    assert(game.apply({rules::ActionKind::capture, 12, 14}) == rules::ApplyError::none);
    assert(game.state().phase == rules::Phase::capture_chain);
    assert(game.state().board[13] == rules::empty && game.state().board[14] == rules::tiger);
    assert(contains(game.legal_actions(), {rules::ActionKind::capture, 14, 24}));
    assert(contains(game.legal_actions(), {rules::ActionKind::end_chain, -1, -1}));
    assert(game.apply({rules::ActionKind::step, 14, 13}) == rules::ApplyError::wrong_phase);
    const auto saved = game.serialize();
    rules::TigerGoatGame restored;
    assert(restored.restore(saved));
    assert(restored.serialize() == saved);
    assert(restored.state().chain_visited == game.state().chain_visited);
    assert(restored.apply({rules::ActionKind::capture, 14, 24}) == rules::ApplyError::none);
    assert(restored.state().board[19] == rules::empty);
    assert(restored.apply({rules::ActionKind::capture, 24, 22}) == rules::ApplyError::none);
    assert(restored.state().phase == rules::Phase::capture_chain);
    assert(contains(restored.legal_actions(), {rules::ActionKind::capture, 22, 20}));
    const auto before_revisit = restored.serialize();
    assert(restored.apply({rules::ActionKind::capture, 22, 12})
           == rules::ApplyError::visited_landing);
    assert(restored.serialize() == before_revisit);
    assert(restored.undo());
    assert(restored.state().phase == rules::Phase::capture_chain);
    assert(restored.state().board[23] == rules::goat);
    assert(game.apply({rules::ActionKind::end_chain, -1, -1}) == rules::ApplyError::none);
    assert(game.state().to_move == rules::goat);
    assert(game.state().phase == rules::Phase::play);
    assert(game.undo());
    assert(game.state().phase == rules::Phase::capture_chain);
}

void test_capture_chain_automatic_end() {
    rules::TigerGoatGame game;
    const auto seed = position_with({6, 14}, {7, 13, 17, 11, 0, 1, 2, 3, 4, 5, 9, 19});
    assert(game.reset_to_position(seed));
    assert(game.apply({rules::ActionKind::capture, 6, 8}) == rules::ApplyError::none);
    assert(game.apply({rules::ActionKind::capture, 8, 18}) == rules::ApplyError::none);
    assert(game.apply({rules::ActionKind::capture, 18, 16}) == rules::ApplyError::none);
    assert(game.state().phase == rules::Phase::play);
    assert(game.state().to_move == rules::goat);
    assert(game.state().chain_tiger == -1 && game.state().chain_visited == 0);
    assert(game.apply({rules::ActionKind::capture, 16, 6}) == rules::ApplyError::wrong_source);
    assert(game.undo());
    assert(game.state().phase == rules::Phase::capture_chain);
    assert(game.state().board[17] == rules::goat);
    assert(game.state().chain_tiger == 18);
}

void test_new_turn_can_revisit() {
    rules::TigerGoatGame game;
    assert(game.reset_to_position(position_with(
        {12, 0}, {13, 19, 23, 17, 21, 1, 2, 3, 4, 5, 6, 8, 9})));
    assert(game.apply({rules::ActionKind::capture, 12, 14}) == rules::ApplyError::none);
    assert(game.apply({rules::ActionKind::end_chain, -1, -1}) == rules::ApplyError::none);
    assert(game.state().chain_visited == 0 && game.state().chain_tiger == -1);
    assert(game.apply({rules::ActionKind::step, 8, 13}) == rules::ApplyError::none);
    assert(contains(game.legal_actions(), {rules::ActionKind::capture, 14, 12}));
    assert(game.apply({rules::ActionKind::capture, 14, 12}) == rules::ApplyError::none);
    assert(game.state().board[13] == rules::empty && game.state().board[12] == rules::tiger);
}

void test_blocked_goats_forced_pass_and_restore() {
    rules::TigerGoatGame game;
    const auto seed = position_with({5, 14}, {25, 26, 27, 28, 29, 30, 31, 32, 33, 34, 35, 36});
    assert(game.reset_to_position(seed));
    assert_rejected(game, {rules::ActionKind::pass_goat, -1, -1}, rules::ApplyError::wrong_phase);
    // A legal tiger step closes the last goat exit; both tigers can still move/capture.
    assert(game.apply({rules::ActionKind::step, 5, 10}) == rules::ApplyError::none);
    assert(game.state().outcome == rules::Outcome::ongoing);
    const std::vector<rules::Action> forced{{rules::ActionKind::pass_goat, -1, -1}};
    assert(game.legal_actions() == forced);
    assert_rejected(game, {rules::ActionKind::pass_goat, 0, -1}, rules::ApplyError::invalid_point);
    const auto blocked = game.serialize();
    assert(game.apply(forced.front()) == rules::ApplyError::none);
    assert(game.state().to_move == rules::tiger && game.state().outcome == rules::Outcome::ongoing);
    const auto passed = game.serialize();
    assert(passed[4] == 2);
    assert(game.apply({rules::ActionKind::step, 10, 11}) == rules::ApplyError::none);
    assert(!contains(game.legal_actions(), forced.front()));
    assert(game.undo() && game.serialize() == passed);
    assert(game.undo() && game.serialize() == blocked && game.legal_actions() == forced);
    rules::TigerGoatGame restored;
    assert(restored.restore(passed) && restored.serialize() == passed);
    assert(restored.undo() && restored.serialize() == blocked);
    assert(restored.legal_actions() == forced);
    assert(restored.undo() && restored.action_count() == 0);
    assert(restored.state().board == seed.board && restored.state().to_move == seed.to_move);

    auto placing = position_with({10, 14}, {25, 26, 27, 28, 29, 31, 32, 33, 34, 35, 36}, 1);
    placing.to_move = rules::goat;
    assert(game.reset_to_position(placing));
    assert_rejected(game, forced.front(), rules::ApplyError::wrong_phase);
    assert(contains(game.legal_actions(), {rules::ActionKind::place_goat, -1, 30}));
    assert(game.apply({rules::ActionKind::place_goat, -1, 30}) == rules::ApplyError::none);
    assert(game.state().reserve == 0 && game.state().to_move == rules::tiger);
    assert(game.state().outcome == rules::Outcome::ongoing);

    auto mobile = position_with({0, 4}, {6, 7, 8, 11, 13, 16, 17, 18});
    mobile.to_move = rules::goat;
    assert(game.reset_to_position(mobile));
    assert_rejected(game, forced.front(), rules::ApplyError::wrong_phase);
    game.reset();
    assert_rejected(game, forced.front(), rules::ApplyError::wrong_phase);
}

void test_v1_compatibility_and_v2_rejection() {
    rules::TigerGoatGame original;
    assert(original.apply({rules::ActionKind::remove_goat, 6, -1}) == rules::ApplyError::none);
    auto legacy = original.serialize();
    legacy[4] = 1;
    update_checksum(legacy);
    rules::TigerGoatGame restored;
    assert(restored.restore(legacy));
    assert(restored.serialize() == original.serialize());
    assert(restored.undo() && restored.state().phase == rules::Phase::opening_removal);

    assert(original.reset_to_position(position_with(
        {5, 14}, {25, 26, 27, 28, 29, 30, 31, 32, 33, 34, 35, 36})));
    assert(original.apply({rules::ActionKind::step, 5, 10}) == rules::ApplyError::none);
    assert(original.apply({rules::ActionKind::pass_goat, -1, -1}) == rules::ApplyError::none);
    const auto before = restored.serialize();
    auto invalid = original.serialize();
    invalid[4] = 1; // V1 never defined action 6, even with a correct checksum.
    update_checksum(invalid);
    assert(!restored.restore(invalid) && restored.serialize() == before);
    invalid[4] = 3;
    update_checksum(invalid);
    assert(!restored.restore(invalid) && restored.serialize() == before);
    invalid = original.serialize();
    invalid[52] = 255; // Unknown action, valid checksum.
    update_checksum(invalid);
    assert(!restored.restore(invalid) && restored.serialize() == before);

    assert(original.reset_to_position(position_with({0, 4}, {1, 2, 3, 5, 9, 10, 14})));
    legacy = original.serialize();
    legacy[4] = 1;
    update_checksum(legacy);
    assert(restored.restore(legacy));
    assert(restored.state().outcome == rules::Outcome::tiger_wins);
    assert(restored.legal_actions().empty());
}

void test_capture_directions_and_mountains() {
    const int jumps[][3] = {
        {6, 7, 8}, {8, 7, 6}, {6, 11, 16}, {16, 11, 6},
        {10, 26, 29}, {29, 26, 10}, {14, 32, 35}, {35, 32, 14},
    };
    for (const auto& jump : jumps) {
        auto seed = position_with({jump[0], 4}, {jump[1], 20, 21, 22, 23, 24, 28, 30, 34, 36});
        rules::TigerGoatGame game;
        assert(game.reset_to_position(seed));
        const rules::Action action{rules::ActionKind::capture, jump[0], jump[2]};
        assert(contains(game.legal_actions(), action));
        const auto before = game.serialize();
        assert(game.apply(action) == rules::ApplyError::none);
        assert(game.state().board[static_cast<std::size_t>(jump[0])] == rules::empty);
        assert(game.state().board[static_cast<std::size_t>(jump[1])] == rules::empty);
        assert(game.state().board[static_cast<std::size_t>(jump[2])] == rules::tiger);
        assert(game.state().captured == seed.captured + 1);
        rules::TigerGoatGame restored;
        assert(restored.restore(game.serialize()));
        assert(restored.undo());
        assert(restored.serialize() == before);
    }
}

void test_step_and_capture_rejections() {
    rules::TigerGoatGame game;
    auto seed = position_with({6, 14}, {7, 11, 20, 21, 22, 23, 24, 28, 30, 34, 36});
    assert(game.reset_to_position(seed));
    assert_rejected(game, {rules::ActionKind::step, -1, 0}, rules::ApplyError::invalid_point);
    assert_rejected(game, {rules::ActionKind::step, 6, 37}, rules::ApplyError::invalid_point);
    assert_rejected(game, {rules::ActionKind::step, 7, 8}, rules::ApplyError::wrong_source);
    assert_rejected(game, {rules::ActionKind::step, 6, 7}, rules::ApplyError::occupied);
    assert_rejected(game, {rules::ActionKind::step, 6, 8}, rules::ApplyError::not_connected);
    assert_rejected(game, {rules::ActionKind::step, 6, 12}, rules::ApplyError::tiger_diagonal);
    assert_rejected(game, {rules::ActionKind::capture, 6, 18}, rules::ApplyError::not_connected);
    assert_rejected(game, {rules::ActionKind::capture, 14, 12}, rules::ApplyError::no_goat_to_capture);
    assert_rejected(game, {rules::ActionKind::end_chain, -1, -1}, rules::ApplyError::wrong_phase);
    assert(game.apply({rules::ActionKind::step, 6, 5}) == rules::ApplyError::none);
    assert(game.state().to_move == rules::goat);
    assert_rejected(game, {rules::ActionKind::capture, 5, 15}, rules::ApplyError::wrong_source);

    seed = position_with({0, 4}, {6, 1, 2, 3, 9, 20, 21, 22, 23, 24, 28});
    seed.to_move = rules::goat;
    assert(game.reset_to_position(seed));
    assert(contains(game.legal_actions(), {rules::ActionKind::step, 6, 12}));
    assert(game.apply({rules::ActionKind::step, 6, 12}) == rules::ApplyError::none);
    assert(game.state().to_move == rules::tiger);

    game.reset();
    assert_rejected(game, {rules::ActionKind::remove_goat, 10, -1}, rules::ApplyError::wrong_source);
    assert_rejected(game, {rules::ActionKind::remove_goat, 6, 0}, rules::ApplyError::invalid_point);
    assert(game.apply({rules::ActionKind::remove_goat, 6, -1}) == rules::ApplyError::none);
    assert_rejected(game, {rules::ActionKind::remove_goat, 7, -1}, rules::ApplyError::wrong_phase);
    assert_rejected(game, {rules::ActionKind::place_goat, -1, 7}, rules::ApplyError::occupied);
}

void test_seed_validation_and_save_boundaries() {
    rules::TigerGoatGame game;
    const auto before = game.serialize();
    auto seed = game.initial_state();
    seed.phase = rules::Phase::play;
    assert(!game.reset_to_position(seed)); // Opening removal cannot be bypassed.
    seed = position_with({10, 14}, {6, 7, 8, 11, 13, 16, 17, 18});
    seed.reserve = 15;
    assert(!game.reset_to_position(seed));
    seed = position_with({10, 14}, {6, 7, 8, 11, 13, 16, 17, 18});
    seed.board[0] = 3;
    assert(!game.reset_to_position(seed));
    assert(game.serialize() == before);
    for (std::size_t length = 0; length < before.size(); ++length) {
        assert(!game.restore(std::vector<std::uint8_t>(before.begin(), before.begin() + length)));
        assert(game.serialize() == before);
    }
    auto trailing = before;
    trailing.push_back(0);
    assert(!game.restore(trailing));
    auto wrong_board_version = before;
    wrong_board_version[5] = 2;
    assert(!game.restore(wrong_board_version));
    assert(game.serialize() == before);
}

void test_wire_contract() {
    const auto geometry = rules::wire_geometry();
    assert(geometry.size() == 229);
    assert(geometry[0] == 1 && geometry[1] == 37 && geometry[2] == 76);
    assert(geometry[3 + 25 * 2] == -1 && geometry[4 + 25 * 2] == 1);
    rules::TigerGoatGame game;
    auto snapshot = rules::wire_snapshot(game);
    assert(snapshot.size() == rules::snapshot_field_count);
    assert(snapshot[37] == 0 && snapshot[38] == 1 && snapshot[39] == 14);
    assert(snapshot[40] == 0 && snapshot[41] == 0 && snapshot[42] == -1 && snapshot[43] == 0);
    assert(std::all_of(snapshot.begin() + 44, snapshot.end(), [](const int value) { return value == 0; }));
    const auto actions = rules::wire_legal_actions(game);
    assert(actions.size() == 24);
    for (std::size_t index = 0; index < actions.size(); index += 3) {
        assert(actions[index] == 0 && actions[index + 2] == -1);
    }
    assert(game.reset_to_position(position_with({12, 0}, {13, 19, 23, 17, 21, 1, 2, 3, 4, 5, 6, 9})));
    assert(game.apply({rules::ActionKind::capture, 12, 14}) == rules::ApplyError::none);
    snapshot = rules::wire_snapshot(game);
    assert(snapshot[37] == 2 && snapshot[42] == 14 && snapshot[43] == 1);
    assert(snapshot[44 + 12] == 1 && snapshot[44 + 14] == 1);
    assert(std::count(snapshot.begin() + 44, snapshot.end(), 1) == 2);
}

void test_terminal_and_restore_rejection() {
    rules::TigerGoatGame game;
    const auto exactly_eight = position_with({10, 14}, {6, 7, 8, 11, 13, 16, 17, 18});
    assert(game.reset_to_position(exactly_eight));
    assert(game.state().outcome == rules::Outcome::ongoing);
    const auto tiger_win = position_with({0, 4}, {1, 2, 3, 5, 9, 10, 14});
    assert(game.reset_to_position(tiger_win));
    assert(game.state().outcome == rules::Outcome::tiger_wins);
    assert(game.legal_actions().empty());
    const auto before = game.serialize();
    auto damaged = before;
    damaged[10] ^= 1;
    assert(!game.restore(damaged));
    assert(game.serialize() == before);
    damaged = before;
    damaged[4] = 200;
    assert(!game.restore(damaged));

    rules::TigerGoatGame saved_opening;
    assert(saved_opening.apply({rules::ActionKind::remove_goat, 6, -1})
           == rules::ApplyError::none);
    damaged = saved_opening.serialize();
    damaged[49] = static_cast<std::uint8_t>(rules::ActionKind::place_goat);
    std::uint32_t hash = 2166136261u;
    for (std::size_t index = 0; index < damaged.size() - 4; ++index) {
        hash = (hash ^ damaged[index]) * 16777619u;
    }
    for (int shift = 0; shift < 32; shift += 8) {
        damaged[damaged.size() - 4 + static_cast<std::size_t>(shift / 8)] =
            static_cast<std::uint8_t>(hash >> shift);
    }
    assert(!game.restore(damaged));
    assert(game.serialize() == before);

    const auto trapped = position_with({0, 4}, {1, 2, 3, 5, 9, 10, 14, 15, 16, 17});
    assert(game.reset_to_position(trapped));
    assert(game.state().outcome == rules::Outcome::goat_wins);
    assert(game.apply({rules::ActionKind::resign, -1, -1}) == rules::ApplyError::finished);

    game.reset();
    assert(game.apply({rules::ActionKind::resign, -1, -1}) == rules::ApplyError::none);
    assert(game.state().outcome == rules::Outcome::goat_wins);
    assert(game.undo());
    assert(game.state().phase == rules::Phase::opening_removal);
}

}  // namespace

int main() {
    test_opening_and_placement();
    test_normal_steps_and_placement_end();
    test_capture_chain_turning_and_undo();
    test_capture_chain_automatic_end();
    test_new_turn_can_revisit();
    test_blocked_goats_forced_pass_and_restore();
    test_v1_compatibility_and_v2_rejection();
    test_capture_directions_and_mountains();
    test_step_and_capture_rejections();
    test_seed_validation_and_save_boundaries();
    test_wire_contract();
    test_terminal_and_restore_rejection();
    std::puts("tiger-goat game: 12 scenario groups passed");
}
