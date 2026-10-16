#include "mocs/engine/go_sgf.hpp"

#ifdef NDEBUG
#error "Go SGF tests require assertions"
#endif

#include <cassert>
#include <string>

namespace {
using mocs::engine::EngineError;
using mocs::engine::GoEngine;
using mocs::engine::GoStone;
using mocs::engine::GoPhase;
using mocs::engine::GameResult;
using mocs::engine::export_go_sgf;
using mocs::engine::make_go_pass;
using mocs::engine::make_go_place;
using mocs::engine::make_go_toggle_dead;
using mocs::engine::make_go_confirm_score;
using mocs::engine::make_go_resume_play;
using mocs::engine::restore_go_sgf;

void round_trip_and_ff4_passes() {
    GoEngine source(9);
    assert(source.apply(make_go_place(0, 0)).accepted);
    assert(source.apply(make_go_pass()).accepted);
    assert(source.apply(make_go_place(1, 1)).accepted);
    assert(source.apply(make_go_pass()).accepted);
    const auto text = export_go_sgf(source);
    assert(text == "(;FF[4]GM[1]SZ[9]RU[Chinese]KM[7.5]CA[UTF-8];B[aa];W[];B[bb];W[])");
    GoEngine restored(9);
    assert(restore_go_sgf(restored, text).restored);
    assert(restored.serialize() == source.serialize());
    assert(restored.stone_at(0, 0) == GoStone::black);
    assert(restored.current_player() == 0);
    assert(restore_go_sgf(restored,
        "(;FF[4]GM[1]SZ[9]C[escaped \\] text];B[aa];W[tt])").restored);
    assert(restored.stone_at(0, 0) == GoStone::black);
}

void unsupported_and_illegal_imports_are_atomic() {
    GoEngine game(9);
    assert(game.apply(make_go_place(2, 2)).accepted);
    const auto original = game.serialize();
    const char* invalid[] = {
        "(;FF[4]GM[1]SZ[9];B[aa];W[aa])",  // occupied point
        "(;FF[4]GM[1]SZ[13];B[aa])",       // different board
        "(;FF[4]GM[1]SZ[9]AB[aa])",        // setup not implemented
        "(;FF[4]GM[1]SZ[9]KM[6.5])",       // unsupported scoring
        "(;FF[4]GM[1]SZ[9]RU[Japanese])",  // different rules
        "(;FF[4]GM[1]SZ[9];B[zz])",        // off board
        "(;FF[4]GM[1]SZ[9];W[aa])",        // wrong side
        "(;FF[4]GM[1]SZ[9](;B[aa])(;B[bb]))",  // variation
        "(;FF[4]GM[1]SZ[9];B[aa];W[bb];B[];W[];B[cc])",  // after double pass
        "(;FF[4]GM[1]SZ[9]PL[W])",        // setup remains unsupported
        "(;FF[4]GM[1]SZ[9];PL[W])",       // cannot skip a normal turn
        "(;FF[4]GM[1]SZ[9];B[];W[];PL[W]W[aa])",  // separate resume required
        "(;FF[4]GM[1]SZ[9];B[];W[];PL[X])",
        "(;FF[4]GM[1]SZ[9];B[];W[];PL[B]PL[W])",
        "(;FF[4]GM[1]SZ[9];B[aa]",         // truncated
    };
    for (const auto* text : invalid) {
        assert(restore_go_sgf(game, text).error == EngineError::corrupted_data);
        assert(game.serialize() == original);
    }
}

void disputed_dead_stones_preserve_resumed_move_colors() {
    GoEngine source(9);
    assert(source.apply(make_go_place(0, 0)).accepted);
    assert(source.apply(make_go_place(8, 8)).accepted);
    assert(source.apply(make_go_pass()).accepted);
    assert(source.apply(make_go_pass()).accepted);
    assert(source.apply(make_go_toggle_dead(8, 8, 0)).accepted);
    assert(source.apply(make_go_confirm_score(0, source.scoring_state().proposal_revision)).accepted);
    assert(source.apply(make_go_resume_play(1)).accepted);
    assert(source.apply(make_go_place(4, 4)).accepted);
    assert(source.apply(make_go_place(3, 3)).accepted);
    const auto text = export_go_sgf(source);
    assert(text == "(;FF[4]GM[1]SZ[9]RU[Chinese]KM[7.5]CA[UTF-8];B[aa];W[ii];B[];W[];PL[W];W[ee];B[dd])");
    GoEngine restored(9);
    assert(restore_go_sgf(restored, text).restored);
    assert(restored.phase() == GoPhase::playing);
    assert(restored.current_player() == source.current_player());
    assert(restored.stone_at(4, 4) == GoStone::white);
    assert(restored.stone_at(3, 3) == GoStone::black);
    assert(restored.action_history().size() == source.action_history().size());
    assert(restored.scoring_state().dead_stones.empty());
    const auto canonical = restored.serialize();
    GoEngine again(9);
    assert(restore_go_sgf(again, export_go_sgf(restored)).restored);
    assert(again.serialize() == canonical);
}

void agreement_events_do_not_turn_into_sgf_moves_or_final_results() {
    GoEngine source(9, 0);
    assert(source.apply(make_go_place(0, 0)).accepted);
    assert(source.apply(make_go_place(8, 8)).accepted);
    assert(source.apply(make_go_pass()).accepted);
    assert(source.apply(make_go_pass()).accepted);
    assert(source.apply(make_go_toggle_dead(8, 8, 0)).accepted);
    const auto revision = source.scoring_state().proposal_revision;
    assert(source.apply(make_go_confirm_score(0, revision)).accepted);
    assert(source.apply(make_go_confirm_score(1, revision)).accepted);
    assert(source.game_result() == GameResult::first_player_win);
    const auto text = export_go_sgf(source);
    assert(text == "(;FF[4]GM[1]SZ[9]RU[Chinese]KM[0]CA[UTF-8];B[aa];W[ii];B[];W[])");
    GoEngine restored(9);
    assert(restore_go_sgf(restored, text).restored);
    assert(restored.black_komi_quarters() == 0);
    assert(restored.phase() == GoPhase::scoring);
    assert(restored.game_result() == GameResult::ongoing);
    assert(!restored.final_score());
    assert(restored.scoring_state().dead_stones.empty());
    assert(!restored.scoring_state().black_confirmed && !restored.scoring_state().white_confirmed);
}

void handicap_setup_round_trips_on_all_boards() {
    for (const auto size : {9, 13, 19}) {
        for (int count = 2; count <= 9; ++count) {
            GoEngine source(static_cast<std::uint8_t>(size), 0,
                            static_cast<std::uint8_t>(count));
            assert(source.current_player() == 1);
            assert(source.apply(make_go_place(0, 0)).accepted);
            const auto text = export_go_sgf(source);
            assert(text.find("KM[0]") != std::string::npos);
            assert(text.find("HA[" + std::to_string(count) + "]AB") != std::string::npos);
            assert(text.find("PL[W];W[aa]") != std::string::npos);
            GoEngine restored(static_cast<std::uint8_t>(size));
            assert(restore_go_sgf(restored, text).restored);
            assert(restored.serialize() == source.serialize());
            assert(restored.handicap_stones() == count);
        }
    }
}

void malformed_handicap_setup_is_atomic() {
    GoEngine game(9);
    const auto original = game.serialize();
    const char* invalid[] = {
        "(;FF[4]GM[1]SZ[9]HA[2]KM[7.5]AB[cg][gc]PL[W])",
        "(;FF[4]GM[1]SZ[9]HA[2]KM[0]AB[cg][cg]PL[W])",
        "(;FF[4]GM[1]SZ[9]HA[2]KM[0]AB[aa][gc]PL[W])",
        "(;FF[4]GM[1]SZ[9]HA[2]KM[0]AB[cg][gc]PL[B])",
        "(;FF[4]GM[1]SZ[9]HA[2]KM[0]AB[cg])",
        "(;FF[4]GM[1]SZ[9]HA[1]KM[0]AB[cg])",
    };
    for (const auto* text : invalid) {
        assert(restore_go_sgf(game, text).error == EngineError::corrupted_data);
        assert(game.serialize() == original);
    }
}

void undone_scoring_proposal_does_not_break_sgf_export() {
    GoEngine source(9, 0);
    assert(source.apply(make_go_place(0, 0)).accepted);
    assert(source.apply(make_go_place(8, 8)).accepted);
    assert(source.apply(make_go_pass()).accepted);
    assert(source.apply(make_go_pass()).accepted);
    assert(source.apply(make_go_toggle_dead(8, 8, 0)).accepted);
    assert(source.undo());
    assert(source.apply(make_go_toggle_dead(0, 0, 1)).accepted);
    const auto revision = source.scoring_state().proposal_revision;
    assert(source.apply(make_go_confirm_score(0, revision)).accepted);
    assert(source.apply(make_go_confirm_score(1, revision)).accepted);
    const auto text = export_go_sgf(source);
    assert(text == "(;FF[4]GM[1]SZ[9]RU[Chinese]KM[0]CA[UTF-8];B[aa];W[ii];B[];W[])");
    GoEngine restored(9);
    assert(restore_go_sgf(restored, text).restored);
    assert(restored.phase() == GoPhase::scoring);
}
}  // namespace

int main() {
    round_trip_and_ff4_passes();
    unsupported_and_illegal_imports_are_atomic();
    disputed_dead_stones_preserve_resumed_move_colors();
    agreement_events_do_not_turn_into_sgf_moves_or_final_results();
    handicap_setup_round_trips_on_all_boards();
    malformed_handicap_setup_is_atomic();
    undone_scoring_proposal_does_not_break_sgf_export();
}
