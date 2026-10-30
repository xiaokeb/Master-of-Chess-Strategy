#include "mocs/engine/lanzhou_fangqi.hpp"
#include "mocs/engine/lanzhou_fangqi_ai.hpp"
#include <chrono>
#include <iostream>

// Bounded QA self-play from the real standard root; never substitutes a hand-edited terminal board.
int main() {
    namespace fq = mocs::engine::lanzhou_fangqi;
    fq::Game game;
    const auto deadline = std::chrono::steady_clock::now() + std::chrono::seconds(30);
    for (int count = 0; count < 2048 && game.position().outcome == fq::Outcome::ongoing; ++count) {
        if (std::chrono::steady_clock::now() >= deadline) { std::cerr << "deadline\n"; return 2; }
        const auto move = fq::choose_action(game, fq::AiLevel::easy);
        if (!move || !game.apply(*move).accepted()) { std::cerr << "no accepted move\n"; return 3; }
    }
    if (game.position().outcome == fq::Outcome::ongoing) { std::cerr << "action limit\n"; return 4; }
    const auto bytes = game.serialize();
    fq::RestoreError error{};
    const auto restored = fq::Game::deserialize(bytes, error);
    if (!restored || restored->serialize() != bytes || restored->position().outcome != game.position().outcome) return 5;
    std::cerr << "actions=" << game.actions().size() << " outcome=" << static_cast<int>(game.position().outcome) << '\n';
    std::cout << bytes;
}
