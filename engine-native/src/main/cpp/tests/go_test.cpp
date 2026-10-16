#include "mocs/engine/go.hpp"

#include <algorithm>
#ifdef NDEBUG
#error Go rule tests require assertions to execute
#endif
#include <cassert>
#include <cstdint>
#include <cstdio>
#include <stdexcept>
#include <vector>

namespace {
using mocs::engine::EngineAction;
using mocs::engine::EngineError;
using mocs::engine::GameResult;
using mocs::engine::GameType;
using mocs::engine::GoEngine;
using mocs::engine::GoPhase;
using mocs::engine::GoStone;
using mocs::engine::make_go_pass;
using mocs::engine::make_go_place;
using mocs::engine::make_go_toggle_dead;
using mocs::engine::make_go_confirm_score;
using mocs::engine::make_go_resume_play;

void play(GoEngine& game, const EngineAction& action) {
    assert(game.apply(action).accepted);
}

void supported_boards_and_simple_capture() {
    for (const std::uint8_t size : {9, 13, 19}) {
        GoEngine game(size);
        assert(game.game_type() == GameType::go);
        assert(game.board_size() == size);
        assert(game.current_player() == 0);
        assert(game.legal_actions().size() == static_cast<std::size_t>(size) * size + 1);
        assert(!game.stone_at(-1, 0));
        assert(!game.stone_at(size, 0));
        play(game, make_go_place(1, 0));
        play(game, make_go_place(0, 0));
        play(game, make_go_place(0, 1));
        assert(game.stone_at(0, 0) == GoStone::empty);
        assert(game.stone_at(1, 0) == GoStone::black);
        assert(game.current_player() == 1);
        assert(game.apply(make_go_place(1, 0)).error == EngineError::illegal_action);
        assert(game.apply(make_go_place(size, 0)).error == EngineError::illegal_action);
        assert(game.current_player() == 1);
    }
    bool rejected = false;
    try { GoEngine invalid(10); } catch (const std::invalid_argument&) { rejected = true; }
    assert(rejected);
}

void suicide_and_capture_exception() {
    GoEngine game(9);
    play(game, make_go_place(1, 0));
    play(game, make_go_pass());
    play(game, make_go_place(0, 1));
    assert(game.apply(make_go_place(0, 0)).error == EngineError::illegal_action);
    assert(game.stone_at(0, 0) == GoStone::empty);
    assert(game.current_player() == 1);
}

void ko_and_global_repetition() {
    GoEngine game(9);
    play(game, make_go_place(0, 1));
    play(game, make_go_place(1, 1));
    play(game, make_go_place(1, 0));
    play(game, make_go_place(0, 2));
    play(game, make_go_place(2, 1));
    play(game, make_go_place(2, 2));
    play(game, make_go_place(8, 8));
    play(game, make_go_place(1, 3));
    play(game, make_go_place(1, 2));
    assert(game.stone_at(1, 1) == GoStone::empty);
    assert(game.apply(make_go_place(1, 1)).error == EngineError::illegal_action);
    assert(game.stone_at(1, 2) == GoStone::black);
    const auto moves = game.legal_actions();
    assert(std::none_of(moves.begin(), moves.end(), [](const EngineAction& move) {
        return move.kind == mocs::engine::go_place_action_kind &&
            move.arguments[0] == 1 && move.arguments[1] == 1;
    }));
    GoEngine restored(9);
    assert(restored.restore(game.serialize()).restored);
    assert(restored.apply(make_go_place(1, 1)).error == EngineError::illegal_action);
    assert(restored.undo());
    play(restored, make_go_place(1, 2));
}

void passes_undo_and_atomic_restore() {
    GoEngine game(13);
    play(game, make_go_place(3, 3));
    play(game, make_go_pass());
    const auto one_pass = game.serialize();
    play(game, make_go_pass());
    assert(game.ready_for_scoring());
    assert(game.game_result() == GameResult::ongoing);
    const auto scoring_actions = game.legal_actions();
    assert(!scoring_actions.empty());
    assert(std::none_of(scoring_actions.begin(), scoring_actions.end(), [](const EngineAction& a) {
        return a.kind == mocs::engine::go_place_action_kind ||
               a.kind == mocs::engine::go_pass_action_kind;
    }));
    assert(game.apply(make_go_place(4, 4)).error == EngineError::invalid_state);
    assert(game.undo());
    assert(!game.ready_for_scoring());
    assert(game.current_player() == 0);

    GoEngine restored(13);
    assert(restored.restore(one_pass).restored);
    assert(restored.serialize() == one_pass);
    assert(restored.stone_at(3, 3) == GoStone::black);
    auto bad = one_pass;
    bad.back() = 255;
    assert(restored.restore(bad).error == EngineError::corrupted_data);
    assert(restored.serialize() == one_pass);
    assert(GoEngine(9).restore(one_pass).error == EngineError::corrupted_data);
    assert(restored.undo());
    assert(restored.undo());
    assert(!restored.undo());
    assert(restored.stone_at(3, 3) == GoStone::empty);
    restored.reset();
    assert(restored.current_player() == 0);
}

void area_score_requires_agreed_dead_stones() {
    GoEngine game(9);
    play(game, make_go_place(0, 0));
    play(game, make_go_place(8, 8));
    assert(!game.preview_area_score({}));
    play(game, make_go_pass());
    play(game, make_go_pass());
    const auto even = game.preview_area_score({}, 0);
    assert(even);
    assert(even->black_area_quarters == 162);
    assert(even->white_area_quarters == 162);
    assert(even->result == GameResult::draw);
    const auto normal = game.preview_area_score({});
    assert(normal && normal->white_area_quarters == 162);
    assert(normal->black_komi_quarters == 15);
    assert(normal->result == GameResult::second_player_win);
    const auto dead_white = game.preview_area_score({{8, 8}, {8, 8}});
    assert(dead_white && dead_white->black_area_quarters == 324);
    assert(dead_white->white_area_quarters == 0);
    assert(dead_white->result == GameResult::first_player_win);
    assert(!game.preview_area_score({{1, 1}}));
    assert(!game.preview_area_score({{9, 0}}));
    assert(!game.preview_area_score({}, 4));
    assert(game.stone_at(8, 8) == GoStone::white);
}

void two_confirmations_finalize_the_same_whole_group() {
    GoEngine game(9);
    play(game, make_go_place(0, 0));
    play(game, make_go_place(8, 8));
    play(game, make_go_place(1, 0));
    play(game, make_go_place(7, 8));
    play(game, make_go_pass());
    play(game, make_go_pass());
    const auto initial_revision = game.scoring_state().proposal_revision;
    assert(game.phase() == GoPhase::scoring);
    assert(!game.final_score());
    play(game, make_go_confirm_score(0, initial_revision));
    assert(game.scoring_state().black_confirmed);
    assert(!game.scoring_state().white_confirmed);
    assert(game.game_result() == GameResult::ongoing);
    play(game, make_go_toggle_dead(8, 8, 1));
    auto proposal = game.scoring_state();
    assert(!proposal.black_confirmed && !proposal.white_confirmed);
    assert(proposal.dead_stones.size() == 2);
    assert(proposal.dead_stones[0].x == 7 && proposal.dead_stones[0].y == 8);
    assert(proposal.dead_stones[1].x == 8 && proposal.dead_stones[1].y == 8);
    const auto pending = game.serialize();
    assert(game.apply(make_go_confirm_score(0, initial_revision)).error ==
           EngineError::illegal_action);
    assert(game.serialize() == pending);
    play(game, make_go_confirm_score(1, proposal.proposal_revision));
    assert(game.current_player() == 0);  // agreement does not consume a board turn
    assert(game.game_result() == GameResult::ongoing);
    const auto one_confirmation = game.serialize();
    assert(game.apply(make_go_confirm_score(1, proposal.proposal_revision)).error ==
           EngineError::illegal_action);
    assert(game.serialize() == one_confirmation);
    play(game, make_go_confirm_score(0, proposal.proposal_revision));
    assert(game.phase() == GoPhase::finished);
    assert(game.scoring_state().black_confirmed && game.scoring_state().white_confirmed);
    assert(game.game_result() == GameResult::first_player_win);
    const auto score = game.final_score();
    assert(score && score->black_area_quarters == 324 && score->white_area_quarters == 0);
    assert(score->black_komi_quarters == 15 && score->result == game.game_result());
    assert(game.stone_at(8, 8) == GoStone::white);  // record retains the original stones
    assert(game.action_history().size() == 6);
    assert(game.event_history().size() == 10);
    assert(game.legal_actions().empty());
    const auto finished = game.serialize();
    for (const auto action : {make_go_place(2, 2), make_go_pass(),
                             make_go_resume_play(0), make_go_toggle_dead(0, 0, 0),
                             make_go_confirm_score(0, proposal.proposal_revision)}) {
        assert(game.apply(action).error == EngineError::invalid_state);
        assert(game.serialize() == finished);
    }
    GoEngine restored(9);
    assert(restored.restore(finished).restored);
    assert(restored.serialize() == finished);
    assert(restored.final_score()->black_area_quarters == 324);
    assert(restored.undo());
    assert(restored.serialize() == one_confirmation);
    assert(!restored.final_score());
    assert(restored.game_result() == GameResult::ongoing);
    assert(restored.scoring_state().white_confirmed);
    assert(restored.undo());
    assert(restored.serialize() == pending);
    assert(restored.undo());  // restore the previous proposal and its black confirmation
    assert(restored.scoring_state().dead_stones.empty());
    assert(restored.scoring_state().black_confirmed);
    assert(restored.scoring_state().proposal_revision == initial_revision);
}

void empty_dead_proposal_and_komi_are_formal_results() {
    static_assert(static_cast<std::uint8_t>(GoPhase::playing) == 0);
    static_assert(static_cast<std::uint8_t>(GoPhase::scoring) == 1);
    static_assert(static_cast<std::uint8_t>(GoPhase::finished) == 2);
    for (const std::uint8_t size : {9, 13, 19}) {
        for (const std::int32_t komi : {0, 15}) {
            GoEngine game(size, komi);
            play(game, make_go_pass());
            play(game, make_go_pass());
            const auto revision = game.scoring_state().proposal_revision;
            play(game, make_go_confirm_score(0, revision));
            assert(!game.final_score());
            play(game, make_go_confirm_score(1, revision));
            const auto score = game.final_score();
            assert(score && score->black_area_quarters == 2 * size * size);
            assert(score->white_area_quarters == 2 * size * size);
            assert(score->result == (komi == 0 ? GameResult::draw :
                                                GameResult::second_player_win));
            GoEngine restored(size);
            assert(restored.restore(game.serialize()).restored);
            assert(restored.black_komi_quarters() == komi);
            assert(restored.game_result() == score->result);
            restored.reset();
            assert(restored.phase() == GoPhase::playing);
            assert(restored.scoring_state().dead_stones.empty());
            assert(!restored.final_score());
            assert(restored.black_komi_quarters() == komi);
        }
    }
    bool rejected = false;
    try { GoEngine invalid(9, 4); } catch (const std::invalid_argument&) { rejected = true; }
    assert(rejected);
}

void shared_dame_and_exclusive_regions_use_chinese_counting() {
    GoEngine game(9, 0);
    // Two intact vertical groups divide the board into 18 Black-only points,
    // 36 White-only points, and 9 shared dame. Each side has 9 live stones.
    for (std::int32_t y = 0; y < 9; ++y) {
        play(game, make_go_place(2, y));
        play(game, make_go_place(4, y));
    }
    play(game, make_go_pass());
    play(game, make_go_pass());
    const auto preview = game.preview_area_score({}, 0);
    assert(preview);
    assert(preview->black_area_quarters == 126);  // 9 + 18 + 9/2
    assert(preview->white_area_quarters == 198);  // 9 + 36 + 9/2
    assert(preview->black_area_quarters + preview->white_area_quarters == 324);
    const auto revision = game.scoring_state().proposal_revision;
    play(game, make_go_confirm_score(0, revision));
    play(game, make_go_confirm_score(1, revision));
    assert(game.final_score()->black_area_quarters == preview->black_area_quarters);
    assert(game.final_score()->white_area_quarters == preview->white_area_quarters);
    assert(game.game_result() == GameResult::second_player_win);
}

void dispute_resume_preserves_board_and_undoes_atomically() {
    for (const std::uint8_t first_player : {0, 1}) {
        GoEngine game(9);
        play(game, make_go_place(0, 0));
        play(game, make_go_place(8, 8));
        play(game, make_go_pass());
        play(game, make_go_pass());
        play(game, make_go_toggle_dead(8, 8, 0));
        play(game, make_go_confirm_score(0, game.scoring_state().proposal_revision));
        const auto before = game.serialize();
        play(game, make_go_resume_play(first_player));
        assert(game.phase() == GoPhase::playing);
        assert(!game.ready_for_scoring());
        assert(game.current_player() == first_player);
        assert(game.scoring_state().dead_stones.empty());
        assert(!game.scoring_state().black_confirmed && !game.scoring_state().white_confirmed);
        assert(game.stone_at(8, 8) == GoStone::white);
        const auto resumed = game.serialize();
        GoEngine restored(9);
        assert(restored.restore(resumed).restored);
        assert(restored.current_player() == first_player);
        play(restored, make_go_place(4, 4));
        assert(restored.stone_at(4, 4) == (first_player == 0 ? GoStone::black : GoStone::white));
        assert(restored.undo());
        assert(restored.serialize() == resumed);
        assert(restored.undo());
        assert(restored.serialize() == before);
        assert(restored.scoring_state().black_confirmed);
        play(game, make_go_pass());
        assert(!game.ready_for_scoring());  // old double pass must not survive resume
        play(game, make_go_pass());
        assert(game.phase() == GoPhase::scoring);
        assert(game.scoring_state().dead_stones.empty());
        assert(!game.scoring_state().black_confirmed && !game.scoring_state().white_confirmed);
    }
}

void dispute_resume_does_not_erase_ko_history() {
    GoEngine game(9);
    for (const auto action : {make_go_place(0, 1), make_go_place(1, 1),
                             make_go_place(1, 0), make_go_place(0, 2),
                             make_go_place(2, 1), make_go_place(2, 2),
                             make_go_place(8, 8), make_go_place(1, 3),
                             make_go_place(1, 2), make_go_pass(), make_go_pass()}) {
        play(game, action);
    }
    play(game, make_go_toggle_dead(1, 2, 1));
    play(game, make_go_resume_play(1));
    const auto before = game.serialize();
    assert(game.apply(make_go_place(1, 1)).error == EngineError::illegal_action);
    assert(game.serialize() == before);
    GoEngine restored(9);
    assert(restored.restore(before).restored);
    assert(restored.apply(make_go_place(1, 1)).error == EngineError::illegal_action);
}

void malformed_agreement_and_legacy_restore_are_atomic() {
    GoEngine game(9);
    const auto empty = game.serialize();
    for (const auto action : {make_go_toggle_dead(0, 0, 0), make_go_confirm_score(0, 0),
                             make_go_resume_play(0)}) {
        assert(!game.apply(action).accepted);
        assert(game.serialize() == empty);
    }
    play(game, make_go_place(0, 0));
    play(game, make_go_pass());
    play(game, make_go_pass());
    const auto pending = game.serialize();
    const auto revision = game.scoring_state().proposal_revision;
    for (const auto action : {make_go_toggle_dead(0, 0, 2), make_go_toggle_dead(1, 1, 0),
                             make_go_toggle_dead(-1, 0, 0), make_go_confirm_score(2, revision),
                             make_go_confirm_score(0, revision + 1), make_go_resume_play(2),
                             EngineAction{mocs::engine::go_confirm_score_action_kind,
                                          {0, static_cast<std::int32_t>(revision), 1, 0}}}) {
        assert(game.apply(action).error == EngineError::illegal_action);
        assert(game.serialize() == pending);
    }
    play(game, make_go_confirm_score(0, revision));
    play(game, make_go_confirm_score(1, revision));
    const auto finished = game.serialize();
    std::vector<std::vector<std::uint8_t>> bad;
    bad.push_back(finished);
    bad.back().pop_back();
    bad.push_back(finished);
    bad.back()[6] = 4;  // unsupported komi
    bad.push_back(finished);
    bad.back()[16] = 99;  // unsupported event
    bad.push_back(finished);
    bad.back()[16 + (game.event_history().size() - 1) * 21 + 5] = 0;  // stale confirmation
    bad.push_back(finished);
    bad.back()[12] = 0;  // count/length mismatch
    for (std::size_t sample = 0; sample < bad.size(); ++sample) {
        const auto result = game.restore(bad[sample]);
        if (result.error != EngineError::corrupted_data) {
            std::fprintf(stderr, "Malformed Go save sample %zu accepted=%u error=%u\n",
                         sample, static_cast<unsigned>(result.restored),
                         static_cast<unsigned>(result.error));
        }
        assert(result.error == EngineError::corrupted_data);
        assert(game.serialize() == finished);
    }
    const std::vector<std::uint8_t> legacy{
        'M', 'O', 'C', 'G', 1, 9, 4, 0, 0, 0,
        1, 0, 0, 1, 8, 8, 2, 0, 0, 2, 0, 0
    };
    assert(game.restore(legacy).restored);
    assert(game.serialize()[4] == 3);
    assert(game.phase() == GoPhase::scoring);
    assert(game.game_result() == GameResult::ongoing);
    assert(game.scoring_state().dead_stones.empty());
    assert(game.black_komi_quarters() == 15);
    assert(game.stone_at(8, 8) == GoStone::white);
    assert(game.undo());
    assert(game.phase() == GoPhase::playing);
}
}  // namespace

int main() {
    supported_boards_and_simple_capture();
    suicide_and_capture_exception();
    ko_and_global_repetition();
    passes_undo_and_atomic_restore();
    area_score_requires_agreed_dead_stones();
    two_confirmations_finalize_the_same_whole_group();
    empty_dead_proposal_and_komi_are_formal_results();
    shared_dame_and_exclusive_regions_use_chinese_counting();
    dispute_resume_preserves_board_and_undoes_atomically();
    dispute_resume_does_not_erase_ko_history();
    malformed_agreement_and_legacy_restore_are_atomic();
}
