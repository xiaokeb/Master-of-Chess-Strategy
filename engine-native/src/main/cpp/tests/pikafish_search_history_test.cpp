// Runtime integration test: invoke on Android with the verified NNUE path.
#include "mocs/engine/chinese_chess.hpp"
#include "mocs/engine/pikafish_adapter.hpp"

#include <algorithm>
#include <cassert>
#include <iostream>
#include <stdexcept>

using namespace mocs::engine;

int main(int argc, const char* argv[]) {
    if (argc != 2) {
        std::cerr << "usage: mocs_pikafish_search_history_test <verified NNUE path>\n";
        return 2;
    }
    ChineseChessEngine engine;
    assert(engine.apply(make_board_move(0, 6, 0, 5)).accepted);
    assert(engine.apply(make_board_move(0, 3, 0, 4)).accepted);
    assert(engine.apply(make_board_move(0, 5, 0, 4)).accepted);
    const auto state = engine.serialize();
    const auto snapshot = engine.search_position();
    const auto legal = engine.legal_actions();
    const auto choose = [&](const ChineseChessSearchPosition& position) {
        return choose_pikafish_move(position, legal, argv[1], Difficulty::easy, 20260926);
    };
    const auto check_legal = [&](const auto& move) {
        assert(move);
        assert(std::any_of(legal.begin(), legal.end(), [&](const auto& action) {
            return action.arguments == move->arguments;
        }));
    };
    check_legal(choose(snapshot));
    for (const auto result : {GameResult::draw, GameResult::first_player_win, GameResult::second_player_win}) {
        auto finished = snapshot;
        finished.result = result;
        // Geometric moves can remain on a terminal board; never search it.
        assert(!choose(finished));
    }
    for (int variant = 0; variant < 4; ++variant) {
        auto bad = snapshot;
        switch (variant) {
            case 0: bad.moves.push_back("a9a0"); break;
            case 1: bad.moves.clear(); break;
            case 2: bad.current_fen = bad.initial_fen; break;
            case 3:
                bad.current_fen = bad.current_fen.substr(0, bad.current_fen.find(' ')) + " w - - 0 1";
                break;
        }
        bool rejected = false;
        try { (void)choose(bad); }
        catch (const std::invalid_argument&) { rejected = true; }
        assert(rejected);
    }
    // A rejected replay must not poison the next legitimate search.
    check_legal(choose(snapshot));
    assert(engine.serialize() == state);
    ChineseChessEngine restored;
    assert(restored.restore(state).restored);
    check_legal(choose(restored.search_position()));
    std::cout << "Pikafish history replay and rejection checks passed\n";
}
