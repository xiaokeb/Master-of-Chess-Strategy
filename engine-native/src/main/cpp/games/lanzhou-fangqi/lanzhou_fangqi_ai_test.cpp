#include "mocs/engine/lanzhou_fangqi_ai.hpp"

#include <algorithm>
#include <cstdlib>
#include <iostream>

namespace fq = mocs::engine::lanzhou_fangqi;

namespace {
void require(bool condition, const char* message) {
    if (!condition) {
        std::cerr << "FAIL: " << message << '\n';
        std::exit(1);
    }
}

bool same_action(const fq::Action& a, const fq::Action& b) {
    return a.kind == b.kind && a.player == b.player
        && a.from == b.from && a.to == b.to;
}

bool listed(const fq::Game& game, const fq::Action& chosen) {
    const auto legal = game.legal_actions();
    return std::any_of(legal.begin(), legal.end(), [&](const fq::Action& action) {
        return same_action(action, chosen);
    });
}
}  // namespace

int main() {
    fq::Game game;
    const auto initial_snapshot = game.serialize();
    for (const auto level : {
             fq::AiLevel::easy, fq::AiLevel::medium,
             fq::AiLevel::hard, fq::AiLevel::master,
         }) {
        const auto chosen = fq::choose_action(game, level);
        require(chosen && listed(game, *chosen), "every level chooses a legal opening action");
        require(game.serialize() == initial_snapshot, "search does not mutate the game");
    }
    const auto first = fq::choose_action(game, fq::AiLevel::easy);
    const auto second = fq::choose_action(game, fq::AiLevel::easy);
    require(first && second && same_action(*first, *second), "same state is deterministic");

    fq::Board board{};
    board[fq::point_index(0, 0)] = fq::red;
    board[fq::point_index(1, 0)] = fq::red;
    board[fq::point_index(0, 1)] = fq::red;
    board[fq::point_index(1, 2)] = fq::red;
    board[fq::point_index(5, 5)] = fq::black;
    auto seeded = fq::Game::from_position(board, fq::red);
    require(seeded.has_value(), "capture fixture loads");
    require(seeded->move(fq::red, 1, 2, 1, 1).accepted(), "fixture forms a square");
    require(seeded->position().captures_remaining == 1, "capture is pending");
    const auto capture = fq::choose_action(*seeded, fq::AiLevel::master);
    require(capture && capture->kind == fq::ActionKind::capture
        && listed(*seeded, *capture), "AI chooses a legal pending capture");
    require(seeded->capture(fq::red, 5, 5).accepted(), "capture completes fixture");
    require(!fq::choose_action(*seeded, fq::AiLevel::master),
        "terminal game offers no AI action");
    std::cout << "Lanzhou fangqi AI tests passed.\n";
}
