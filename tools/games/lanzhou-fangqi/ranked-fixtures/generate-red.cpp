#include "mocs/engine/lanzhou_fangqi.hpp"
#include "mocs/engine/lanzhou_fangqi_ai.hpp"
#include <chrono>
#include <iostream>
int main() {
    namespace fq = mocs::engine::lanzhou_fangqi;
    fq::Game game;
    const auto deadline = std::chrono::steady_clock::now() + std::chrono::seconds(30);
    for (int count = 0; count < 2048 && game.position().outcome == fq::Outcome::ongoing; ++count) {
        if (std::chrono::steady_clock::now() >= deadline) return 2;
        const auto level = game.position().to_play == fq::red ? fq::AiLevel::medium : fq::AiLevel::easy;
        const auto move = fq::choose_action(game, level);
        if (!move || !game.apply(*move).accepted()) return 3;
    }
    if (game.position().outcome == fq::Outcome::ongoing) return 4;
    const auto bytes = game.serialize();
    fq::RestoreError error{};
    const auto restored = fq::Game::deserialize(bytes, error);
    if (!restored || restored->serialize() != bytes || restored->position().outcome != game.position().outcome) return 5;
    std::cerr << "actions=" << game.actions().size() << " outcome=" << static_cast<int>(game.position().outcome) << '\n';
    std::cout << bytes;
}
