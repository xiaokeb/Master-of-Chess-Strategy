#include "competition_mahjong_round.hpp"

#include "mocs/engine/competition_mahjong_settlement.hpp"
#include "mocs/engine/competition_mahjong_shape.hpp"

#include <algorithm>
#include <array>
#include <random>
#include <stdexcept>

namespace mocs::engine {
namespace {

constexpr std::size_t max_actions = 4096;

std::array<std::uint8_t, 34> own_meld_counts(
    const std::vector<CompetitionMahjongRoundMeld>& melds) {
    std::array<std::uint8_t, 34> counts{};
    for (const auto& meld : melds) {
        if (meld.kind == CompetitionMahjongMeldKind::chow) {
            for (int n = 0; n < 3; ++n) ++counts[meld.tile + n];
        } else {
            counts[meld.tile] += meld.kind == CompetitionMahjongMeldKind::kong ? 4 : 3;
        }
    }
    return counts;
}

bool remove_tiles(std::vector<CompetitionMahjongTile>& hand,
                  CompetitionMahjongTile tile, int count) {
    if (std::count(hand.begin(), hand.end(), tile) < count) return false;
    while (count-- > 0) hand.erase(std::find(hand.begin(), hand.end(), tile));
    return true;
}

std::uint32_t checksum(const std::vector<std::uint8_t>& data, std::size_t size) {
    std::uint32_t hash = 2166136261u;
    for (std::size_t i = 0; i < size; ++i) hash = (hash ^ data[i]) * 16777619u;
    return hash;
}

void put32(std::vector<std::uint8_t>& out, std::uint32_t value) {
    for (int n = 0; n < 4; ++n) out.push_back(static_cast<std::uint8_t>(value >> (n * 8)));
}

std::uint32_t get32(const std::vector<std::uint8_t>& data, std::size_t offset) {
    std::uint32_t value = 0;
    for (int n = 0; n < 4; ++n) value |= std::uint32_t(data[offset + n]) << (n * 8);
    return value;
}

} // namespace

std::vector<CompetitionMahjongTile> competition_mahjong_shuffled_wall(
    std::uint64_t seed) {
    auto wall = competition_mahjong_canonical_wall();
    std::mt19937_64 rng(seed);
    std::shuffle(wall.begin(), wall.end(), rng);
    return wall;
}

CompetitionMahjongRound::CompetitionMahjongRound(
    std::vector<CompetitionMahjongTile> wall, std::uint8_t dealer,
    std::uint8_t round_wind)
    : initial_wall_(std::move(wall)), back_(initial_wall_.size()),
      dealer_(dealer), round_wind_(round_wind), current_player_(dealer) {
    if (!competition_mahjong_valid_wall(initial_wall_) || dealer >= 4 || round_wind >= 4)
        throw std::invalid_argument("invalid Mahjong wall, dealer or round wind");
    for (int repeat = 0; repeat < 3; ++repeat) {
        for (int distance = 0; distance < 4; ++distance) {
            const auto player = static_cast<std::uint8_t>((dealer + distance) % 4);
            for (int n = 0; n < 4; ++n) concealed_[player].push_back(take_front());
        }
    }
    concealed_[dealer].push_back(take_front());
    concealed_[dealer].push_back(take_front());
    for (int distance = 1; distance < 4; ++distance)
        concealed_[(dealer + distance) % 4].push_back(take_front());
    for (int distance = 0; distance < 4; ++distance)
        replace_initial_flowers(static_cast<std::uint8_t>((dealer + distance) % 4));
    last_drawn_tile_ = concealed_[dealer].back();
    can_self_kong_ = true;
}

CompetitionMahjongTile CompetitionMahjongRound::take_front() {
    return initial_wall_[front_++];
}

CompetitionMahjongTile CompetitionMahjongRound::take_back() {
    return initial_wall_[--back_];
}

bool CompetitionMahjongRound::receive(const std::uint8_t player,
    const CompetitionMahjongTile tile, const bool kong_replacement) {
    if (tile < 34) {
        concealed_[player].push_back(tile);
        last_drawn_tile_ = tile;
        last_draw_was_kong_replacement_ = kong_replacement;
        last_draw_was_wall_last_ = front_ == back_;
        return true;
    }
    flowers_[player].push_back(tile);
    if (front_ == back_) {
        phase_ = CompetitionMahjongRoundPhase::exhaustive_draw;
        return false;
    }
    return receive(player, take_back(), false);
}

void CompetitionMahjongRound::replace_initial_flowers(const std::uint8_t player) {
    for (std::size_t i = 0; i < concealed_[player].size();) {
        if (concealed_[player][i] < 34) { ++i; continue; }
        flowers_[player].push_back(concealed_[player][i]);
        concealed_[player].erase(concealed_[player].begin() + static_cast<std::ptrdiff_t>(i));
        while (front_ < back_) {
            const auto tile = take_back();
            if (tile < 34) { concealed_[player].push_back(tile); break; }
            flowers_[player].push_back(tile);
        }
    }
}

CompetitionMahjongRoundView CompetitionMahjongRound::view() const {
    CompetitionMahjongRoundView result{};
    result.phase = phase_;
    result.dealer = dealer_;
    result.round_wind = round_wind_;
    result.current_player = current_player_;
    result.wall_remaining = back_ - front_;
    if (phase_ == CompetitionMahjongRoundPhase::claim ||
        phase_ == CompetitionMahjongRoundPhase::rob_kong) {
        result.pending_tile = pending_tile_;
        result.pending_player = pending_player_;
    }
    result.concealed = concealed_;
    result.flowers = flowers_;
    result.discards = discards_;
    result.melds = melds_;
    result.win_disabled = win_disabled_;
    result.score_delta = score_delta_;
    result.winner = winner_;
    result.discarder = discarder_;
    result.winning_fan = winning_fan_;
    if (phase_ == CompetitionMahjongRoundPhase::discard && can_self_kong_) {
        result.drawn_tile = last_drawn_tile_;
        result.can_declare_win[current_player_] = !win_disabled_[current_player_];
    } else if (phase_ == CompetitionMahjongRoundPhase::claim ||
               phase_ == CompetitionMahjongRoundPhase::rob_kong) {
        for (std::uint8_t seat = 0; seat < 4; ++seat)
            result.can_declare_win[seat] = seat != pending_player_ &&
                !claims_[seat].responded && !win_disabled_[seat];
    } else if (phase_ == CompetitionMahjongRoundPhase::won) {
        result.winning_tile = discarder_ ? pending_tile_ : last_drawn_tile_;
    }
    return result;
}

bool CompetitionMahjongRound::can_kong(const CompetitionMahjongRoundAction& action) const {
    if (phase_ != CompetitionMahjongRoundPhase::discard ||
        action.player != current_player_ || !can_self_kong_ || front_ == back_ ||
        action.tile >= 34) return false;
    if (action.kind == CompetitionMahjongRoundActionKind::concealed_kong)
        return std::count(concealed_[action.player].begin(),
                          concealed_[action.player].end(), action.tile) == 4;
    if (action.kind != CompetitionMahjongRoundActionKind::added_kong ||
        std::count(concealed_[action.player].begin(),
                   concealed_[action.player].end(), action.tile) == 0) return false;
    return std::any_of(melds_[action.player].begin(), melds_[action.player].end(),
        [&](const CompetitionMahjongRoundMeld& meld) {
            return meld.kind == CompetitionMahjongMeldKind::pung && meld.tile == action.tile;
        });
}

bool CompetitionMahjongRound::can_claim(const CompetitionMahjongRoundAction& action) const {
    if (phase_ != CompetitionMahjongRoundPhase::claim ||
        action.player >= 4 || action.player == pending_player_ ||
        claims_[action.player].responded || action.tile != pending_tile_ ||
        melds_[action.player].size() >= 4) return false;
    const auto& hand = concealed_[action.player];
    switch (action.kind) {
    case CompetitionMahjongRoundActionKind::chow:
        if (action.player != (pending_player_ + 1) % 4 || pending_tile_ >= 27 ||
            action.sequence_start >= 27 || action.sequence_start / 9 != pending_tile_ / 9 ||
            action.sequence_start % 9 > 6 || pending_tile_ < action.sequence_start ||
            pending_tile_ > action.sequence_start + 2) return false;
        for (int n = 0; n < 3; ++n) {
            const auto tile = static_cast<CompetitionMahjongTile>(action.sequence_start + n);
            if (tile != pending_tile_ &&
                std::find(hand.begin(), hand.end(), tile) == hand.end()) return false;
        }
        return true;
    case CompetitionMahjongRoundActionKind::pung:
        return std::count(hand.begin(), hand.end(), pending_tile_) >= 2;
    case CompetitionMahjongRoundActionKind::exposed_kong:
        return front_ < back_ && std::count(hand.begin(), hand.end(), pending_tile_) >= 3;
    default: return false;
    }
}

CompetitionMahjongFanResult CompetitionMahjongRound::evaluate_hu(
    const std::uint8_t player, const CompetitionMahjongTile tile,
    const bool self_drawn, const bool robbing_kong) const {
    CompetitionMahjongFanInput input{};
    input.win_tile = tile;
    input.self_drawn = self_drawn;
    input.robbing_kong = robbing_kong;
    input.last_tile = self_drawn ? last_draw_was_wall_last_ : last_discard_was_wall_last_;
    input.replacement = self_drawn && last_draw_was_kong_replacement_;
    input.seat_wind = (player + 4 - dealer_) % 4;
    input.round_wind = round_wind_;
    input.flower_count = static_cast<int>(flowers_[player].size());
    std::array<int, 34> visible{};
    for (std::uint8_t seat = 0; seat < 4; ++seat) {
        for (const auto discard : discards_[seat]) if (discard < 34) ++visible[discard];
        for (const auto& meld : melds_[seat]) {
            if (meld.kind == CompetitionMahjongMeldKind::chow) {
                for (int n = 0; n < 3; ++n) ++visible[meld.tile + n];
            } else {
                visible[meld.tile] += meld.kind == CompetitionMahjongMeldKind::kong ? 4 : 3;
            }
        }
    }
    if (!self_drawn && !robbing_kong && visible[tile] > 0) --visible[tile];
    input.fourth_tile = visible[tile] == 3;
    for (const auto hand_tile : concealed_[player]) input.concealed_tiles.push_back(hand_tile);
    if (self_drawn) {
        const auto it = std::find(input.concealed_tiles.begin(), input.concealed_tiles.end(), tile);
        if (it == input.concealed_tiles.end()) return {};
        input.concealed_tiles.erase(it);
    }
    for (const auto& meld : melds_[player]) {
        const auto kind = meld.kind == CompetitionMahjongMeldKind::chow ?
            CompetitionMahjongFanPackKind::chow :
            meld.kind == CompetitionMahjongMeldKind::pung ?
                CompetitionMahjongFanPackKind::pung : CompetitionMahjongFanPackKind::kong;
        const int normalized_offer = meld.concealed ? 0 :
            kind == CompetitionMahjongFanPackKind::chow ?
                static_cast<int>(meld.claimed_position) :
                (meld.from_player + 4 - player) % 4;
        input.packs.push_back({kind, meld.tile, normalized_offer, meld.added_kong});
    }
    return evaluateCompetitionMahjongFan(input);
}

std::vector<CompetitionMahjongRoundAction> CompetitionMahjongRound::legal_actions(
    const std::uint8_t player) const {
    std::vector<CompetitionMahjongRoundAction> out;
    if (player >= 4 || phase_ == CompetitionMahjongRoundPhase::won ||
        phase_ == CompetitionMahjongRoundPhase::exhaustive_draw) return out;
    const auto add = [&](CompetitionMahjongRoundActionKind kind,
                         CompetitionMahjongTile tile = 0,
                         CompetitionMahjongTile start = 0) {
        out.push_back({kind, player, tile, start});
    };
    if (phase_ == CompetitionMahjongRoundPhase::draw) {
        if (player == current_player_ && front_ < back_)
            add(CompetitionMahjongRoundActionKind::draw);
        return out;
    }
    if (phase_ == CompetitionMahjongRoundPhase::discard) {
        if (player != current_player_) return out;
        std::array<bool, 34> seen{};
        for (const auto tile : concealed_[player]) {
            if (seen[tile]) continue;
            seen[tile] = true;
            add(CompetitionMahjongRoundActionKind::discard, tile);
            for (const auto kind : {CompetitionMahjongRoundActionKind::concealed_kong,
                                    CompetitionMahjongRoundActionKind::added_kong})
                if (can_kong({kind, player, tile, 0})) add(kind, tile);
        }
        if (can_self_kong_ && !win_disabled_[player] &&
            evaluate_hu(player, last_drawn_tile_, true, false).qualifies)
            add(CompetitionMahjongRoundActionKind::hu, last_drawn_tile_);
        return out;
    }
    if (player == pending_player_ || claims_[player].responded) return out;
    add(CompetitionMahjongRoundActionKind::pass, pending_tile_);
    if (!win_disabled_[player] &&
        evaluate_hu(player, pending_tile_, false,
            phase_ == CompetitionMahjongRoundPhase::rob_kong).qualifies)
        add(CompetitionMahjongRoundActionKind::hu, pending_tile_);
    if (phase_ == CompetitionMahjongRoundPhase::claim) {
        for (const auto kind : {CompetitionMahjongRoundActionKind::pung,
                                CompetitionMahjongRoundActionKind::exposed_kong})
            if (can_claim({kind, player, pending_tile_, 0})) add(kind, pending_tile_);
        if (pending_tile_ < 27) {
            const int first = std::max<int>(pending_tile_ / 9 * 9, pending_tile_ - 2);
            const int last = std::min<int>(pending_tile_, pending_tile_ / 9 * 9 + 6);
            for (int start = first; start <= last; ++start)
                if (can_claim({CompetitionMahjongRoundActionKind::chow, player,
                    pending_tile_, static_cast<CompetitionMahjongTile>(start)}))
                    add(CompetitionMahjongRoundActionKind::chow, pending_tile_,
                        static_cast<CompetitionMahjongTile>(start));
        }
    }
    return out;
}

void CompetitionMahjongRound::record_false_hu(const std::uint8_t player,
                                              const bool below_minimum) {
    const auto penalty = competition_mahjong_score_false_hu(player,
        below_minimum ? CompetitionMahjongFalseHuKind::below_minimum :
                        CompetitionMahjongFalseHuKind::not_waiting);
    for (std::uint8_t seat = 0; seat < 4; ++seat) score_delta_[seat] += penalty.delta[seat];
    win_disabled_[player] = true;
}

void CompetitionMahjongRound::settle_win(const std::uint8_t winner,
    const std::optional<std::uint8_t> discarder, const CompetitionMahjongTile tile,
    const CompetitionMahjongFanResult& fan) {
    const auto settlement = competition_mahjong_score_win(winner, discarder,
        static_cast<std::uint16_t>(fan.fan_without_flowers),
        static_cast<std::uint8_t>(flowers_[winner].size()));
    for (std::uint8_t seat = 0; seat < 4; ++seat)
        score_delta_[seat] += settlement.delta[seat];
    if (discarder && phase_ == CompetitionMahjongRoundPhase::claim) {
        discards_[*discarder].pop_back();
        concealed_[winner].push_back(tile);
    }
    if (phase_ == CompetitionMahjongRoundPhase::rob_kong) {
        remove_tiles(concealed_[pending_player_], tile, 1);
        concealed_[winner].push_back(tile);
    }
    winner_ = winner;
    discarder_ = discarder;
    winning_fan_ = fan;
    current_player_ = winner;
    phase_ = CompetitionMahjongRoundPhase::won;
}

void CompetitionMahjongRound::complete_added_kong() {
    auto& melds = melds_[pending_player_];
    auto it = std::find_if(melds.begin(), melds.end(), [&](const auto& meld) {
        return meld.kind == CompetitionMahjongMeldKind::pung && meld.tile == pending_tile_;
    });
    remove_tiles(concealed_[pending_player_], pending_tile_, 1);
    it->kind = CompetitionMahjongMeldKind::kong;
    it->added_kong = true;
    phase_ = CompetitionMahjongRoundPhase::discard;
    current_player_ = pending_player_;
    (void) receive(current_player_, take_back(), true);
    can_self_kong_ = true;
}

void CompetitionMahjongRound::resolve_claims() {
    for (int distance = 1; distance <= 3; ++distance) {
        const auto player = static_cast<std::uint8_t>((pending_player_ + distance) % 4);
        if (claims_[player].action.kind == CompetitionMahjongRoundActionKind::hu) {
            settle_win(player, pending_player_, pending_tile_, claims_[player].fan);
            return;
        }
    }
    if (phase_ == CompetitionMahjongRoundPhase::rob_kong) {
        complete_added_kong();
        return;
    }
    for (const auto kind : {CompetitionMahjongRoundActionKind::exposed_kong,
                            CompetitionMahjongRoundActionKind::pung,
                            CompetitionMahjongRoundActionKind::chow}) {
        for (int distance = 1; distance <= 3; ++distance) {
            const auto player = static_cast<std::uint8_t>((pending_player_ + distance) % 4);
            const auto action = claims_[player].action;
            if (action.kind != kind) continue;
            auto& hand = concealed_[player];
            if (kind == CompetitionMahjongRoundActionKind::chow) {
                for (int n = 0; n < 3; ++n) {
                    const auto tile = static_cast<CompetitionMahjongTile>(action.sequence_start + n);
                    if (tile != pending_tile_) remove_tiles(hand, tile, 1);
                }
                melds_[player].push_back({CompetitionMahjongMeldKind::chow,
                    action.sequence_start, pending_player_,
                    static_cast<std::uint8_t>(pending_tile_ - action.sequence_start + 1),
                    false, false});
            } else {
                const bool kong = kind == CompetitionMahjongRoundActionKind::exposed_kong;
                remove_tiles(hand, pending_tile_, kong ? 3 : 2);
                melds_[player].push_back({kong ? CompetitionMahjongMeldKind::kong :
                    CompetitionMahjongMeldKind::pung, pending_tile_, pending_player_, 0,
                    false, false});
            }
            discards_[pending_player_].pop_back();
            current_player_ = player;
            phase_ = CompetitionMahjongRoundPhase::discard;
            can_self_kong_ = kind == CompetitionMahjongRoundActionKind::exposed_kong;
            last_draw_was_wall_last_ = false;
            last_draw_was_kong_replacement_ = false;
            if (can_self_kong_) (void) receive(player, take_back(), true);
            return;
        }
    }
    current_player_ = static_cast<std::uint8_t>((pending_player_ + 1) % 4);
    phase_ = front_ == back_ ? CompetitionMahjongRoundPhase::exhaustive_draw :
                              CompetitionMahjongRoundPhase::draw;
}

CompetitionMahjongRoundActionResult CompetitionMahjongRound::execute(
    const CompetitionMahjongRoundAction& action) {
    if (action.player >= 4) return {false, CompetitionMahjongRoundError::wrong_player, false};
    if (phase_ == CompetitionMahjongRoundPhase::won ||
        phase_ == CompetitionMahjongRoundPhase::exhaustive_draw)
        return {false, CompetitionMahjongRoundError::wrong_phase, false};
    if (phase_ == CompetitionMahjongRoundPhase::draw) {
        if (action.player != current_player_) return {false, CompetitionMahjongRoundError::wrong_player, false};
        if (action.kind != CompetitionMahjongRoundActionKind::draw)
            return {false, CompetitionMahjongRoundError::wrong_phase, false};
        if (front_ == back_) return {false, CompetitionMahjongRoundError::illegal_action, false};
        phase_ = CompetitionMahjongRoundPhase::discard;
        (void) receive(action.player, take_front(), false);
        can_self_kong_ = true;
        return {true, CompetitionMahjongRoundError::none, false};
    }
    if (phase_ == CompetitionMahjongRoundPhase::discard) {
        if (action.player != current_player_) return {false, CompetitionMahjongRoundError::wrong_player, false};
        if (action.kind == CompetitionMahjongRoundActionKind::discard) {
            if (action.tile >= 34 || !remove_tiles(concealed_[action.player], action.tile, 1))
                return {false, CompetitionMahjongRoundError::illegal_action, false};
            discards_[action.player].push_back(action.tile);
            pending_tile_ = action.tile;
            pending_player_ = action.player;
            last_discard_was_wall_last_ = last_draw_was_wall_last_;
            claims_ = {};
            phase_ = CompetitionMahjongRoundPhase::claim;
            return {true, CompetitionMahjongRoundError::none, false};
        }
        if (action.kind == CompetitionMahjongRoundActionKind::hu) {
            if (!can_self_kong_ || win_disabled_[action.player] || action.tile >= 34)
                return {false, CompetitionMahjongRoundError::illegal_action, false};
            const auto fan = action.tile == last_drawn_tile_ ?
                evaluate_hu(action.player, action.tile, true, false) :
                CompetitionMahjongFanResult{};
            if (fan.qualifies) settle_win(action.player, std::nullopt, action.tile, fan);
            else {
                auto counts = std::array<std::uint8_t, 34>{};
                auto hand = concealed_[action.player];
                remove_tiles(hand, last_drawn_tile_, 1);
                for (const auto tile : hand) ++counts[tile];
                const auto waits = competition_mahjong_waits(counts,
                    static_cast<std::uint8_t>(melds_[action.player].size()),
                    own_meld_counts(melds_[action.player]));
                record_false_hu(action.player, waits.valid_input &&
                    std::any_of(waits.tiles.begin(), waits.tiles.end(), [](bool value) { return value; }));
            }
            return {true, CompetitionMahjongRoundError::none, !fan.qualifies};
        }
        if (action.kind == CompetitionMahjongRoundActionKind::concealed_kong) {
            if (!can_kong(action)) return {false, CompetitionMahjongRoundError::illegal_action, false};
            remove_tiles(concealed_[action.player], action.tile, 4);
            melds_[action.player].push_back({CompetitionMahjongMeldKind::kong,
                action.tile, action.player, 0, true, false});
            (void) receive(action.player, take_back(), true);
            can_self_kong_ = true;
            return {true, CompetitionMahjongRoundError::none, false};
        }
        if (action.kind == CompetitionMahjongRoundActionKind::added_kong) {
            if (!can_kong(action)) return {false, CompetitionMahjongRoundError::illegal_action, false};
            pending_player_ = action.player;
            pending_tile_ = action.tile;
            claims_ = {};
            phase_ = CompetitionMahjongRoundPhase::rob_kong;
            return {true, CompetitionMahjongRoundError::none, false};
        }
        return {false, CompetitionMahjongRoundError::wrong_phase, false};
    }
    if (action.player == pending_player_) return {false, CompetitionMahjongRoundError::wrong_player, false};
    if (claims_[action.player].responded)
        return {false, CompetitionMahjongRoundError::already_responded, false};
    const bool pass = action.kind == CompetitionMahjongRoundActionKind::pass;
    const bool hu = action.kind == CompetitionMahjongRoundActionKind::hu;
    if ((pass || !hu) && action.tile != pending_tile_)
        return {false, CompetitionMahjongRoundError::illegal_action, false};
    if (hu && action.tile >= 34)
        return {false, CompetitionMahjongRoundError::illegal_action, false};
    if (!pass && !hu && !can_claim(action))
        return {false, CompetitionMahjongRoundError::illegal_action, false};
    if (hu && win_disabled_[action.player])
        return {false, CompetitionMahjongRoundError::illegal_action, false};
    auto& claim = claims_[action.player];
    claim.responded = true;
    claim.action = action;
    bool false_hu = false;
    if (hu) {
        if (action.tile == pending_tile_)
            claim.fan = evaluate_hu(action.player, pending_tile_, false,
                phase_ == CompetitionMahjongRoundPhase::rob_kong);
        if (!claim.fan.qualifies) {
            auto counts = std::array<std::uint8_t, 34>{};
            for (const auto tile : concealed_[action.player]) ++counts[tile];
            const auto waits = competition_mahjong_waits(counts,
                static_cast<std::uint8_t>(melds_[action.player].size()),
                own_meld_counts(melds_[action.player]));
            record_false_hu(action.player, waits.valid_input &&
                std::any_of(waits.tiles.begin(), waits.tiles.end(), [](bool value) { return value; }));
            claim.action.kind = CompetitionMahjongRoundActionKind::pass;
            false_hu = true;
        }
    }
    bool all_responded = true;
    for (std::uint8_t seat = 0; seat < 4; ++seat)
        if (seat != pending_player_ && !claims_[seat].responded) all_responded = false;
    if (all_responded) resolve_claims();
    return {true, CompetitionMahjongRoundError::none, false_hu};
}

CompetitionMahjongRoundActionResult CompetitionMahjongRound::apply(
    const CompetitionMahjongRoundAction& action) {
    if (journal_.size() >= max_actions)
        return {false, CompetitionMahjongRoundError::action_limit, false};
    auto candidate = *this;
    const auto result = candidate.execute(action);
    if (!result.accepted) return result;
    candidate.journal_.push_back(action);
    *this = std::move(candidate);
    return result;
}

bool CompetitionMahjongRound::undo() {
    if (journal_.empty()) return false;
    CompetitionMahjongRound candidate(initial_wall_, dealer_, round_wind_);
    for (std::size_t i = 0; i + 1 < journal_.size(); ++i)
        if (!candidate.apply(journal_[i]).accepted) return false;
    *this = std::move(candidate);
    return true;
}

std::vector<std::uint8_t> CompetitionMahjongRound::serialize() const {
    std::vector<std::uint8_t> out{'M','O','C','M',1,dealer_,round_wind_};
    out.insert(out.end(), initial_wall_.begin(), initial_wall_.end());
    put32(out, static_cast<std::uint32_t>(journal_.size()));
    for (const auto& action : journal_) {
        out.push_back(static_cast<std::uint8_t>(action.kind));
        out.push_back(action.player);
        out.push_back(action.tile);
        out.push_back(action.sequence_start);
    }
    put32(out, checksum(out, out.size()));
    return out;
}

bool CompetitionMahjongRound::restore(const std::vector<std::uint8_t>& data) {
    constexpr std::size_t header_size = 7 + competition_mahjong_tile_count + 4;
    if (data.size() < header_size + 4 ||
        data[0] != 'M' || data[1] != 'O' || data[2] != 'C' || data[3] != 'M' ||
        data[4] != 1) return false;
    const auto count = get32(data, 7 + competition_mahjong_tile_count);
    if (count > max_actions || data.size() != header_size + 4 * count + 4 ||
        get32(data, data.size() - 4) != checksum(data, data.size() - 4)) return false;
    try {
        CompetitionMahjongRound candidate(
            std::vector<CompetitionMahjongTile>(data.begin() + 7,
                data.begin() + 7 + competition_mahjong_tile_count), data[5], data[6]);
        for (std::size_t i = 0; i < count; ++i) {
            const std::size_t offset = header_size + 4 * i;
            if (data[offset] > static_cast<std::uint8_t>(CompetitionMahjongRoundActionKind::hu))
                return false;
            if (!candidate.apply({static_cast<CompetitionMahjongRoundActionKind>(data[offset]),
                data[offset + 1], data[offset + 2], data[offset + 3]}).accepted) return false;
        }
        *this = std::move(candidate);
        return true;
    } catch (const std::invalid_argument&) {
        return false;
    }
}

std::uint8_t CompetitionMahjongRound::next_dealer() const noexcept {
    return static_cast<std::uint8_t>((dealer_ + 1) % 4);
}

} // namespace mocs::engine
