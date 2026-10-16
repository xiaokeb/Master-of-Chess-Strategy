#include "mocs/engine/go.hpp"

#include <cstdint>
#include <stdexcept>

namespace {
using mocs::engine::EngineError;
using mocs::engine::GameResult;
using mocs::engine::GoEngine;
using mocs::engine::GoPhase;
using mocs::engine::make_go_confirm_score;
using mocs::engine::make_go_pass;
using mocs::engine::make_go_place;
using mocs::engine::make_go_toggle_dead;

void require(const bool condition, const char* message) {
    if (!condition) throw std::runtime_error(message);
}

void stale_confirmation_cannot_bind_to_a_different_proposal() {
    GoEngine game(9);
    require(game.apply(make_go_place(0, 0)).accepted, "black setup move failed");
    require(game.apply(make_go_place(8, 8)).accepted, "white setup move failed");
    require(game.apply(make_go_pass()).accepted, "first pass failed");
    require(game.apply(make_go_pass()).accepted, "second pass failed");

    require(game.apply(make_go_toggle_dead(8, 8, 0)).accepted, "proposal A failed");
    const auto old_revision = game.scoring_state().proposal_revision;
    require(game.undo(), "undo proposal A failed");
    const auto after_undo = game.serialize();
    GoEngine recovered_after_undo(9);
    require(recovered_after_undo.restore(after_undo).restored,
            "save after undo could not be restored");
    require(recovered_after_undo.apply(make_go_toggle_dead(0, 0, 1)).accepted,
            "proposal B after save and restore failed");
    require(recovered_after_undo.scoring_state().proposal_revision != old_revision,
            "save and restore reused an undone proposal revision");
    require(recovered_after_undo.apply(make_go_confirm_score(0, old_revision)).error ==
                EngineError::illegal_action,
            "save and restore accepted a stale confirmation");

    require(game.apply(make_go_toggle_dead(0, 0, 1)).accepted, "proposal B failed");
    const auto new_revision = game.scoring_state().proposal_revision;
    require(new_revision != old_revision, "undo reused the proposal revision");

    const auto pending = game.serialize();
    require(game.apply(make_go_confirm_score(0, old_revision)).error ==
                EngineError::illegal_action,
            "stale confirmation accepted for a different dead-stone set");
    require(game.serialize() == pending, "rejected confirmation changed the game");

    GoEngine restored(9);
    require(restored.restore(pending).restored, "proposal B restore failed");
    require(restored.scoring_state().proposal_revision == new_revision,
            "restore changed the active proposal revision");
    require(restored.apply(make_go_confirm_score(0, old_revision)).error ==
                EngineError::illegal_action,
            "restore accepted the stale confirmation");
    require(restored.serialize() == pending, "restore changed after rejected confirmation");
    require(restored.apply(make_go_confirm_score(0, new_revision)).accepted,
            "first current confirmation failed");
    require(restored.game_result() == GameResult::ongoing,
            "one confirmation ended the game");
    require(restored.apply(make_go_confirm_score(1, new_revision)).accepted,
            "second current confirmation failed");
    require(restored.phase() == GoPhase::finished, "two confirmations did not finish");
}

void zero_komi_preview_matches_final_result() {
    GoEngine game(9, 0);
    require(game.apply(make_go_place(0, 0)).accepted, "black move failed");
    require(game.apply(make_go_place(8, 8)).accepted, "white move failed");
    require(game.apply(make_go_pass()).accepted, "first pass failed");
    require(game.apply(make_go_pass()).accepted, "second pass failed");

    const auto preview = game.preview_area_score({});
    require(preview.has_value(), "zero-komi preview unavailable");
    require(preview->black_komi_quarters == 0, "preview used nonzero komi");
    require(preview->result == GameResult::draw, "zero-komi preview was not even");
    const auto revision = game.scoring_state().proposal_revision;
    require(game.apply(make_go_confirm_score(0, revision)).accepted,
            "first zero-komi confirmation failed");
    require(!game.final_score().has_value(), "one confirmation produced a final score");
    require(game.apply(make_go_confirm_score(1, revision)).accepted,
            "second zero-komi confirmation failed");

    const auto final = game.final_score();
    require(final.has_value(), "confirmed zero-komi score unavailable");
    require(final->black_area_quarters == preview->black_area_quarters &&
                final->white_area_quarters == preview->white_area_quarters &&
                final->black_komi_quarters == preview->black_komi_quarters &&
                final->result == preview->result,
            "preview and confirmed zero-komi scores differ");
}

void handicap_is_setup_and_has_separate_counting_adjustment() {
    for (const auto size : {9, 13, 19}) {
        for (std::uint8_t count = 2; count <= 9; ++count) {
            GoEngine game(static_cast<std::uint8_t>(size), 0, count);
            require(game.handicap_points().size() == count, "handicap setup count differs");
            require(game.current_player() == 1, "white must play after handicap setup");
            require(game.event_history().empty(), "setup stones became moves");
            const auto initial = game.serialize();
            require(game.apply(make_go_pass()).accepted, "white handicap pass failed");
            require(game.apply(make_go_pass()).accepted, "black handicap pass failed");
            const auto preview = game.preview_area_score({});
            require(preview.has_value(), "handicap score unavailable");
            require(preview->black_komi_quarters == 0, "handicap game used ordinary komi");
            require(preview->handicap_adjustment_quarters == 4 * count,
                    "handicap return was not n full area points");
            const auto revision = game.scoring_state().proposal_revision;
            require(game.apply(make_go_confirm_score(0, revision)).accepted,
                    "black handicap score confirmation failed");
            require(game.apply(make_go_confirm_score(1, revision)).accepted,
                    "white handicap score confirmation failed");
            require(game.final_score()->result == preview->result,
                    "handicap preview differs from final result");
            GoEngine restored(static_cast<std::uint8_t>(size));
            require(restored.restore(game.serialize()).restored, "handicap restore failed");
            require(restored.serialize() == game.serialize(), "handicap save was not stable");
            restored.reset();
            require(restored.serialize().size() == initial.size(),
                    "reset did not remove handicap moves");
            require(restored.current_player() == 1, "reset lost handicap first player");
        }
    }
}
}  // namespace

int main() {
    stale_confirmation_cannot_bind_to_a_different_proposal();
    zero_komi_preview_matches_final_result();
    handicap_is_setup_and_has_separate_counting_adjustment();
}
