#include "go_katago_position.hpp"

#include <iostream>
#include <stdexcept>

namespace {
using namespace mocs::engine;
using mocs::engine::go_ai::KataGoPosition;
int cases = 0;

void require(const bool condition, const char* message) {
    if (!condition) throw std::runtime_error(message);
}

void apply(GoEngine& game, const EngineAction& action) {
    require(game.apply(action).accepted, "Native history fixture rejected");
}

void check(GoEngine& game) {
    const auto saved = game.serialize();
    const KataGoPosition position(game);
    position.require_native_agreement(game);
    require(saved == game.serialize(), "History conversion changed source");
    ++cases;
}

GoEngine ko_fixture() {
    GoEngine game(9);
    for (const GoPoint point : {GoPoint{1,0}, {1,1}, {0,1}, {2,0}, {1,2},
                               {3,1}, {8,8}, {2,2}, {2,1}})
        apply(game, make_go_place(point.x, point.y));
    return game;
}

void expect_agreement_rejected(GoEngine& game) {
    bool rejected = false;
    try { const KataGoPosition position(game); }
    catch (const std::runtime_error&) { rejected = true; }
    require(rejected, "Agreement was exposed as an AI turn");
    ++cases;
}
}  // namespace

int main() {
    try {
        Board::initHash();
        for (const std::uint8_t size : {9, 13, 19}) {
            GoEngine zero_komi(size, 0);
            check(zero_komi);
        }
        for (const std::uint8_t size : {9, 13, 19}) for (const std::uint8_t handicap : {0, 2, 3, 4, 5, 6, 7, 8, 9}) {
            GoEngine game(size, handicap == 0 ? 15 : 0, handicap);
            check(game);
            apply(game, make_go_place(1, 1));
            apply(game, make_go_place(size - 1, size - 1));
            apply(game, make_go_pass());
            check(game);
            GoEngine restored(size);
            require(restored.restore(game.serialize()).restored, "Native restore failed");
            check(restored);
        }
        for (const std::uint8_t first_player : {0, 1}) {
            GoEngine game = ko_fixture();
            check(game);
            require(!game.apply(make_go_place(1, 1)).accepted, "Immediate ko fixture is wrong");
            const KataGoPosition ko(game);
            require(!ko.history.isLegal(ko.board, Location::getLoc(1, 1, 9), P_WHITE), "KataGo permits immediate ko");
            apply(game, make_go_pass());
            apply(game, make_go_pass());
            expect_agreement_rejected(game);
            apply(game, make_go_toggle_dead(2, 0, 0));
            apply(game, make_go_confirm_score(1, game.scoring_state().proposal_revision));
            apply(game, make_go_resume_play(first_player));
            check(game);
            const KataGoPosition resumed(game);
            require(resumed.history.moveHistory.size() == 11 && resumed.history.koHashHistory.size() == 12,
                    "Resume erased or inserted board history");
            require(resumed.board.colors[Location::getLoc(2, 0, 9)] == P_WHITE,
                    "Resume removed an unagreed dead stone");
            if (first_player == 1) require(!game.apply(make_go_place(1, 1)).accepted,
                                          "Double pass/resume erased global ko");
            apply(game, make_go_pass());
            check(game);
        }
        GoEngine revised(9);
        apply(revised, make_go_place(0, 0));
        apply(revised, make_go_place(1, 0));
        apply(revised, make_go_pass());
        apply(revised, make_go_pass());
        apply(revised, make_go_toggle_dead(1, 0, 0));
        require(revised.undo(), "Fixture undo failed");
        apply(revised, make_go_toggle_dead(1, 0, 0));
        apply(revised, make_go_confirm_score(0, revised.scoring_state().proposal_revision));
        apply(revised, make_go_resume_play(1));
        check(revised);
        apply(revised, make_go_pass());
        apply(revised, make_go_pass());
        apply(revised, make_go_resume_play(0));
        check(revised);
        apply(revised, make_go_pass());
        apply(revised, make_go_pass());
        apply(revised, make_go_confirm_score(0, revised.scoring_state().proposal_revision));
        apply(revised, make_go_confirm_score(1, revised.scoring_state().proposal_revision));
        expect_agreement_rejected(revised);
        std::cout << "{\"type\":\"history-complete\",\"cases\":" << cases << "}" << std::endl;
        return 0;
    } catch (const std::exception& error) {
        std::cerr << "Go KataGo history test failed: " << error.what() << '\n';
        return 1;
    }
}
