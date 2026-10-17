#include "competition_mahjong_bot.hpp"

#include "fan/vendor/shanten.h"

#include <algorithm>
#include <array>
#include <limits>
#include <random>
#include <vector>

namespace mocs::engine {
namespace {

mahjong::tile_t vendor_tile(const CompetitionMahjongTile tile) {
    if (tile < 27) return mahjong::make_tile(
        static_cast<mahjong::suit_t>(tile / 9 + 1),
        static_cast<mahjong::rank_t>(tile % 9 + 1));
    return mahjong::make_tile(4, static_cast<mahjong::rank_t>(tile - 26));
}

std::array<int, 34> known_counts(const CompetitionMahjongRoundView& view,
                                  const std::uint8_t player) {
    std::array<int, 34> known{};
    for (const auto tile : view.concealed[player]) ++known[tile];
    for (std::uint8_t seat = 0; seat < 4; ++seat) {
        for (const auto tile : view.discards[seat]) ++known[tile];
        for (const auto& meld : view.melds[seat]) {
            if (meld.concealed && seat != player) continue;
            if (meld.kind == CompetitionMahjongMeldKind::chow) {
                for (int n = 0; n < 3; ++n) ++known[meld.tile + n];
            } else {
                known[meld.tile] += meld.kind == CompetitionMahjongMeldKind::kong ? 4 : 3;
            }
        }
    }
    return known;
}

struct ShapeEstimate {
    int shanten{std::numeric_limits<int>::max()};
    std::array<bool, 34> useful{};
};

ShapeEstimate estimate_shape(const std::vector<CompetitionMahjongTile>& standing,
                             const std::size_t meld_count) {
    ShapeEstimate estimate{};
    std::vector<mahjong::tile_t> encoded;
    encoded.reserve(standing.size());
    for (const auto tile : standing) encoded.push_back(vendor_tile(tile));
    const auto evaluate_form = [&](auto evaluator) {
        mahjong::useful_table_t useful{};
        const int value = evaluator(encoded.data(),
            static_cast<std::intptr_t>(encoded.size()), &useful);
        if (value < estimate.shanten) {
            estimate.shanten = value;
            estimate.useful.fill(false);
        }
        if (value == estimate.shanten) {
            for (int tile = 0; tile < 34; ++tile)
                estimate.useful[static_cast<std::size_t>(tile)] |= useful[vendor_tile(
                    static_cast<CompetitionMahjongTile>(tile))];
        }
    };
    evaluate_form(mahjong::regular_shanten);
    if (meld_count == 0) {
        evaluate_form(mahjong::seven_pairs_shanten);
        evaluate_form(mahjong::thirteen_orphans_shanten);
        evaluate_form(mahjong::honors_and_knitted_tiles_shanten);
        evaluate_form(mahjong::knitted_straight_shanten);
    }
    return estimate;
}

CompetitionMahjongFanInput fan_input(const CompetitionMahjongRoundView& view,
    const std::uint8_t player, const std::vector<CompetitionMahjongTile>& standing,
    const CompetitionMahjongTile win_tile) {
    CompetitionMahjongFanInput input{};
    for (const auto tile : standing) input.concealed_tiles.push_back(tile);
    input.win_tile = win_tile;
    input.self_drawn = true;
    input.seat_wind = (player + 4 - view.dealer) % 4;
    input.round_wind = view.round_wind;
    input.flower_count = static_cast<int>(view.flowers[player].size());
    for (const auto& meld : view.melds[player]) {
        const auto kind = meld.kind == CompetitionMahjongMeldKind::chow ?
            CompetitionMahjongFanPackKind::chow :
            meld.kind == CompetitionMahjongMeldKind::pung ?
                CompetitionMahjongFanPackKind::pung : CompetitionMahjongFanPackKind::kong;
        const int offer = meld.concealed ? 0 :
            kind == CompetitionMahjongFanPackKind::chow ?
                meld.claimed_position : (meld.from_player + 4 - player) % 4;
        input.packs.push_back({kind, meld.tile, offer, meld.added_kong});
    }
    return input;
}

int discard_score(const CompetitionMahjongRoundView& view,
    const std::uint8_t player, const std::uint8_t level,
    const CompetitionMahjongTile discard, const std::array<int, 34>& known) {
    auto standing = view.concealed[player];
    const auto it = std::find(standing.begin(), standing.end(), discard);
    if (it == standing.end()) return std::numeric_limits<int>::min();
    standing.erase(it);
    const auto estimate = estimate_shape(standing, view.melds[player].size());
    int score = -1000 * estimate.shanten;
    if (level == 1) return score;
    int live = 0;
    int qualifying_live = 0;
    for (int tile = 0; tile < 34; ++tile) {
        if (!estimate.useful[static_cast<std::size_t>(tile)]) continue;
        const int available = std::max(0, 4 - known[static_cast<std::size_t>(tile)]);
        live += available;
        if (level >= 3 && estimate.shanten == 0 && available > 0) {
            const auto input = fan_input(view, player, standing,
                static_cast<CompetitionMahjongTile>(tile));
            if (evaluateCompetitionMahjongFan(input).qualifies) qualifying_live += available;
        }
    }
    score += live;
    if (level >= 3 && estimate.shanten == 0) score += qualifying_live * 8;
    return score;
}

} // namespace

std::optional<CompetitionMahjongRoundAction> competition_mahjong_choose_bot_action(
    const CompetitionMahjongRound& round, const std::uint8_t player,
    const std::uint8_t level, const std::uint64_t random_seed) {
    if (player >= 4 || level > 3) return std::nullopt;
    const auto actions = round.legal_actions(player);
    if (actions.empty()) return std::nullopt;
    for (const auto& action : actions)
        if (action.kind == CompetitionMahjongRoundActionKind::hu) return action;
    for (const auto& action : actions)
        if (action.kind == CompetitionMahjongRoundActionKind::draw) return action;
    if (level >= 2) {
        for (const auto kind : {CompetitionMahjongRoundActionKind::concealed_kong,
                                CompetitionMahjongRoundActionKind::added_kong}) {
            for (const auto& action : actions)
                if (action.kind == kind) return action;
        }
    }

    std::mt19937_64 rng(random_seed);
    std::vector<CompetitionMahjongRoundAction> discards;
    for (const auto& action : actions)
        if (action.kind == CompetitionMahjongRoundActionKind::discard)
            discards.push_back(action);
    if (!discards.empty()) {
        if (level == 0) return discards[rng() % discards.size()];
        const auto view = round.view();
        const auto known = known_counts(view, player);
        int best_score = std::numeric_limits<int>::min();
        std::vector<CompetitionMahjongRoundAction> best;
        for (const auto& action : discards) {
            const int score = discard_score(view, player, level, action.tile, known);
            if (score > best_score) {
                best_score = score;
                best.clear();
            }
            if (score == best_score) best.push_back(action);
        }
        return best[rng() % best.size()];
    }

    // On claims, novice bots decline; stronger baselines accept a Kong or Pung
    // before a Chow. This is a legal fallback, not a claim of expert play.
    if (level > 0) {
        for (const auto kind : {CompetitionMahjongRoundActionKind::exposed_kong,
                                CompetitionMahjongRoundActionKind::pung,
                                CompetitionMahjongRoundActionKind::chow}) {
            for (const auto& action : actions)
                if (action.kind == kind) return action;
        }
    }
    for (const auto& action : actions)
        if (action.kind == CompetitionMahjongRoundActionKind::pass) return action;
    return actions.front();
}

} // namespace mocs::engine
