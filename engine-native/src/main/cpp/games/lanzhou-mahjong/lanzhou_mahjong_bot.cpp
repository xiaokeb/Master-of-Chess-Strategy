#include "mocs/engine/lanzhou_mahjong_bot.hpp"

#include <algorithm>
#include <array>
#include <limits>
#include <vector>

namespace mocs::engine {
namespace {

std::optional<LanzhouMahjongAction> find_action(
    const std::vector<LanzhouMahjongAction>& actions,
    LanzhouMahjongActionKind kind
) {
    const auto found = std::find_if(actions.begin(), actions.end(),
        [kind](const auto& action) { return action.kind == kind; });
    if (found == actions.end()) {
        return std::nullopt;
    }
    return *found;
}

bool can_commit_meld(const LanzhouMahjongGame& game, std::uint8_t player,
                     std::uint8_t tile) {
    std::array<bool, 3> committed{};
    committed[tile / 9] = true;
    for (const auto& meld : game.melds(player)) committed[meld.tile / 9] = true;
    return std::count(committed.begin(), committed.end(), true) <= 2;
}

int discard_score(const LanzhouMahjongGame& game, std::uint8_t player,
                  std::uint8_t tile, bool ready, Difficulty difficulty) {
    const auto& hand = game.hand(player);
    std::array<int, 3> suit_counts{};
    for (std::uint8_t kind = 0; kind < 27; ++kind) {
        suit_counts[kind / 9] += hand[kind];
    }
    std::array<bool, 3> committed{};
    for (const auto& meld : game.melds(player)) committed[meld.tile / 9] = true;
    const auto suit = tile / 9;
    const auto rank = tile % 9;
    int score = 0;
    if ((suit_counts[0] || committed[0]) &&
        (suit_counts[1] || committed[1]) &&
        (suit_counts[2] || committed[2])) {
        int target = -1;
        for (int candidate = 0; candidate < 3; ++candidate) {
            if (!committed[candidate] &&
                (target < 0 || suit_counts[candidate] < suit_counts[target])) {
                target = candidate;
            }
        }
        if (suit == target) score += 1000;
    }
    score -= hand[tile] >= 2 ? 18 : 0;
    if (rank > 0) score -= hand[tile - 1] * 5;
    if (rank < 8) score -= hand[tile + 1] * 5;
    if (rank > 1) score -= hand[tile - 2] * 2;
    if (rank < 7) score -= hand[tile + 2] * 2;
    if (ready) score += difficulty == Difficulty::medium ? 150 : 300;

    if (difficulty == Difficulty::master) {
        score += static_cast<int>(game.waits_after_discard(player, tile).size()) * 25;
    }
    return score;
}

}  // namespace

std::optional<LanzhouMahjongAction> LanzhouMahjongBot::choose(
    const LanzhouMahjongGame& game, std::uint8_t player,
    Difficulty difficulty, std::uint64_t choice_seed
) {
    if (player >= 4 || static_cast<std::uint8_t>(difficulty) > 3) {
        return std::nullopt;
    }
    const auto actions = game.legal_actions(player);
    if (actions.empty()) {
        return std::nullopt;
    }
    if (const auto win = find_action(actions, LanzhouMahjongActionKind::win)) {
        return win;
    }
    if (const auto draw = find_action(actions, LanzhouMahjongActionKind::draw)) {
        return draw;
    }
    if (game.phase() == LanzhouMahjongPhase::claim ||
        game.phase() == LanzhouMahjongPhase::rob_kong) {
        if (difficulty >= Difficulty::hard && game.phase() == LanzhouMahjongPhase::claim) {
            if (const auto kong = find_action(actions,
                    LanzhouMahjongActionKind::exposed_kong)) {
                if (can_commit_meld(game, player, *game.pending_tile())) return kong;
            }
            if (const auto pung = find_action(actions,
                    LanzhouMahjongActionKind::pung)) {
                const auto tile = *game.pending_tile();
                if (can_commit_meld(game, player, tile)) return pung;
            }
        }
        return find_action(actions, LanzhouMahjongActionKind::pass);
    }
    if (difficulty >= Difficulty::hard) {
        for (const auto& action : actions) {
            if (action.kind == LanzhouMahjongActionKind::concealed_kong &&
                can_commit_meld(game, player, action.tile)) return action;
        }
        if (const auto kong = find_action(actions,
                LanzhouMahjongActionKind::added_kong)) return kong;
    }

    std::vector<LanzhouMahjongAction> discards;
    for (const auto& action : actions) {
        if (action.kind == LanzhouMahjongActionKind::discard ||
            action.kind == LanzhouMahjongActionKind::ready_discard) {
            discards.push_back(action);
        }
    }
    if (discards.empty()) {
        return std::nullopt;
    }
    if (difficulty == Difficulty::easy) {
        std::vector<LanzhouMahjongAction> plain;
        for (const auto& action : discards) {
            if (action.kind == LanzhouMahjongActionKind::discard) {
                plain.push_back(action);
            }
        }
        return plain[choice_seed % plain.size()];
    }

    int best_score = std::numeric_limits<int>::min();
    LanzhouMahjongAction best = discards.front();
    for (const auto& action : discards) {
        const int score = discard_score(
            game, player, action.tile,
            action.kind == LanzhouMahjongActionKind::ready_discard,
            difficulty
        );
        if (score > best_score || (score == best_score && action.tile < best.tile)) {
            best_score = score;
            best = action;
        }
    }
    return best;
}

LanzhouMahjongAutoResult LanzhouMahjongBot::advance_other_seats(
    LanzhouMahjongGame& game, std::uint8_t viewer,
    Difficulty difficulty, std::uint64_t choice_seed,
    std::uint16_t step_limit
) {
    if (viewer >= 4 || static_cast<std::uint8_t>(difficulty) > 3 ||
        step_limit == 0) {
        return {LanzhouMahjongAutoStop::rejected, 0};
    }
    for (std::uint16_t steps = 0; ; ++steps) {
        if (game.phase() == LanzhouMahjongPhase::won ||
            game.phase() == LanzhouMahjongPhase::exhaustive_draw) {
            return {LanzhouMahjongAutoStop::terminal, steps};
        }
        std::uint8_t player = game.current_player();
        if (game.phase() == LanzhouMahjongPhase::claim ||
            game.phase() == LanzhouMahjongPhase::rob_kong) {
            const auto source = *game.pending_player();
            bool found = false;
            for (std::uint8_t distance = 1; distance < 4; ++distance) {
                const auto seat = static_cast<std::uint8_t>((source + distance) % 4);
                if (!game.responded(seat)) {
                    player = seat;
                    found = true;
                    break;
                }
            }
            if (!found) return {LanzhouMahjongAutoStop::rejected, steps};
        }
        if (player == viewer) {
            return {LanzhouMahjongAutoStop::viewer_action, steps};
        }
        if (steps == step_limit) {
            return {LanzhouMahjongAutoStop::step_limit, steps};
        }
        const auto action = choose(game, player, difficulty, choice_seed + steps);
        if (!action || !game.apply(*action).accepted) {
            return {LanzhouMahjongAutoStop::rejected, steps};
        }
    }
}

}  // namespace mocs::engine
