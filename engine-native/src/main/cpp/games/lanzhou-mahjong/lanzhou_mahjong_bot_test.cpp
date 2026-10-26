#include "mocs/engine/lanzhou_mahjong_bot.hpp"

#include <algorithm>
#include <array>
#include <cstdint>
#include <cstdlib>
#include <vector>

using namespace mocs::engine;

namespace {

void require(bool value) {
    if (!value) std::abort();
}

bool is_legal(const LanzhouMahjongGame& game,
              const LanzhouMahjongAction& action) {
    const auto actions = game.legal_actions(action.player);
    return std::any_of(actions.begin(), actions.end(), [&](const auto& legal) {
        return legal.kind == action.kind && legal.tile == action.tile;
    });
}

void test_viewer_boundary() {
    for (std::uint8_t level = 0; level < 4; ++level) {
        for (std::uint64_t seed = 1; seed <= 24; ++seed) {
            const auto viewer = static_cast<std::uint8_t>(seed % 4);
            LanzhouMahjongGame game(
                LanzhouMahjongGame::shuffled_wall(seed),
                static_cast<std::uint8_t>((viewer + 1) % 4));
            const auto before = game.serialize();
            const auto invalid = LanzhouMahjongBot::advance_other_seats(
                game, 4, static_cast<Difficulty>(level), seed);
            require(invalid.stop == LanzhouMahjongAutoStop::rejected);
            require(game.serialize() == before);

            const auto result = LanzhouMahjongBot::advance_other_seats(
                game, viewer, static_cast<Difficulty>(level), seed);
            require(result.stop == LanzhouMahjongAutoStop::viewer_action ||
                    result.stop == LanzhouMahjongAutoStop::terminal);
            require(result.steps > 0);
            if (result.stop == LanzhouMahjongAutoStop::viewer_action) {
                require(!game.legal_actions(viewer).empty());
                const auto after = game.serialize();
                const auto repeat = LanzhouMahjongBot::advance_other_seats(
                    game, viewer, static_cast<Difficulty>(level), seed);
                require(repeat.stop == LanzhouMahjongAutoStop::viewer_action);
                require(repeat.steps == 0);
                require(game.serialize() == after);
            }
        }
    }
}

void test_full_rounds() {
    for (std::uint8_t level = 0; level < 4; ++level) {
        for (std::uint64_t seed = 25; seed <= 36; ++seed) {
            const auto viewer = static_cast<std::uint8_t>(seed % 4);
            LanzhouMahjongGame game(LanzhouMahjongGame::shuffled_wall(seed));
            std::uint32_t viewer_turns = 0;
            while (true) {
                const auto before = game.serialize();
                const auto result = LanzhouMahjongBot::advance_other_seats(
                    game, viewer, static_cast<Difficulty>(level), seed + viewer_turns);
                require(result.stop == LanzhouMahjongAutoStop::viewer_action ||
                        result.stop == LanzhouMahjongAutoStop::terminal);
                if (result.stop == LanzhouMahjongAutoStop::terminal) {
                    const auto terminal = game.serialize();
                    const auto again = LanzhouMahjongBot::advance_other_seats(
                        game, viewer, static_cast<Difficulty>(level), seed);
                    require(again.stop == LanzhouMahjongAutoStop::terminal);
                    require(again.steps == 0 && game.serialize() == terminal);
                    break;
                }
                require(++viewer_turns < 100);
                const auto action = LanzhouMahjongBot::choose(
                    game, viewer, static_cast<Difficulty>(level), seed + viewer_turns);
                require(action.has_value() && is_legal(game, *action));
                require(game.apply(*action).accepted);
                require(game.serialize() != before);
            }
        }
    }
}

void test_step_limit_and_determinism() {
    LanzhouMahjongGame game(LanzhouMahjongGame::shuffled_wall(91), 1);
    const auto initial = game.serialize();
    const auto limited = LanzhouMahjongBot::advance_other_seats(
        game, 0, Difficulty::medium, 7, 1);
    require(limited.stop == LanzhouMahjongAutoStop::step_limit);
    require(limited.steps == 1 && game.serialize() != initial);

    LanzhouMahjongGame same(LanzhouMahjongGame::shuffled_wall(91), 1);
    const auto same_limited = LanzhouMahjongBot::advance_other_seats(
        same, 0, Difficulty::medium, 7, 1);
    require(same_limited.stop == limited.stop);
    require(same.serialize() == game.serialize());
    require(!LanzhouMahjongBot::choose(game, 4, Difficulty::medium, 7));

    LanzhouMahjongGame viewer_next(LanzhouMahjongGame::shuffled_wall(91), 3);
    const auto viewer_boundary = LanzhouMahjongBot::advance_other_seats(
        viewer_next, 0, Difficulty::easy, 7, 1);
    require(viewer_boundary.stop == LanzhouMahjongAutoStop::viewer_action);
    require(viewer_boundary.steps == 1);
}

LanzhouMahjongGame::Wall fixed_dealer_hand(
    const std::array<std::uint8_t, 14>& tiles,
    const std::vector<std::pair<std::size_t, std::uint8_t>>& extra = {}
) {
    LanzhouMahjongGame::Wall wall{};
    std::array<bool, 108> fixed{};
    std::array<std::uint8_t, 27> remaining{};
    remaining.fill(4);
    auto place = [&](std::size_t position, std::uint8_t tile) {
        require(position < 108 && tile < 27 && !fixed[position] && remaining[tile]);
        fixed[position] = true;
        wall[position] = tile;
        --remaining[tile];
    };
    for (std::size_t index = 0; index < 13; ++index) place(index * 4, tiles[index]);
    place(52, tiles[13]);
    for (const auto& [position, tile] : extra) place(position, tile);
    std::size_t next = 0;
    for (std::size_t position = 0; position < wall.size(); ++position) {
        if (fixed[position]) continue;
        while (!remaining[next]) ++next;
        wall[position] = static_cast<std::uint8_t>(next);
        --remaining[next];
    }
    require(LanzhouMahjongGame::valid_wall(wall));
    return wall;
}

void test_final_budget_action_and_fixed_suits() {
    LanzhouMahjongGame terminal(fixed_dealer_hand({
        0, 0, 0, 1, 1, 1, 2, 2, 2, 3, 3, 3, 4, 4,
    }), 1);
    const auto finish = LanzhouMahjongBot::advance_other_seats(
        terminal, 0, Difficulty::easy, 7, 1);
    require(finish.stop == LanzhouMahjongAutoStop::terminal && finish.steps == 1);

    for (const auto difficulty : {Difficulty::hard, Difficulty::master}) {
        LanzhouMahjongGame game(fixed_dealer_hand({
            0, 0, 0, 0, 9, 9, 9, 9, 18, 18, 18, 18, 1, 2,
        }, {{107, 1}, {106, 2}}));
        require(game.apply({LanzhouMahjongActionKind::concealed_kong, 0, 0}).accepted);
        require(game.apply({LanzhouMahjongActionKind::concealed_kong, 0, 9}).accepted);
        const auto choice = LanzhouMahjongBot::choose(game, 0, difficulty, 7);
        require(choice && choice->kind == LanzhouMahjongActionKind::discard);
        require(choice->tile == 18);
        const auto before = game.serialize();
        static_cast<void>(game.waits_after_discard(0, 18));
        require(game.serialize() == before);
    }
}

}  // namespace

int main() {
    test_viewer_boundary();
    test_full_rounds();
    test_step_limit_and_determinism();
    test_final_budget_action_and_fixed_suits();
    return 0;
}
