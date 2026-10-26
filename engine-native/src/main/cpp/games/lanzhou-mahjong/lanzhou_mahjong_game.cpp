#include "mocs/engine/lanzhou_mahjong_game.hpp"

#include <algorithm>
#include <limits>
#include <stdexcept>
#include <type_traits>
#include <utility>

namespace mocs::engine {
namespace {

std::uint64_t next_random(std::uint64_t& state) {
    state += 0x9e3779b97f4a7c15ULL;
    auto value = state;
    value = (value ^ (value >> 30)) * 0xbf58476d1ce4e5b9ULL;
    value = (value ^ (value >> 27)) * 0x94d049bb133111ebULL;
    return value ^ (value >> 31);
}

bool contains(const std::vector<std::uint8_t>& tiles, std::uint8_t tile) {
    return std::find(tiles.begin(), tiles.end(), tile) != tiles.end();
}

void write_number(std::vector<std::uint8_t>& output,
                  std::uint64_t value, std::size_t bytes) {
    for (std::size_t index = 0; index < bytes; ++index) {
        output.push_back(static_cast<std::uint8_t>(value & 0xffU));
        value >>= 8;
    }
}

std::uint64_t read_number(const std::vector<std::uint8_t>& input,
                          std::size_t offset, std::size_t bytes) {
    std::uint64_t value = 0;
    for (std::size_t index = 0; index < bytes; ++index) {
        value |= std::uint64_t{input[offset + index]} << (8 * index);
    }
    return value;
}

std::uint32_t checksum(const std::vector<std::uint8_t>& bytes,
                       std::size_t length) {
    std::uint32_t value = 2166136261U;
    for (std::size_t index = 0; index < length; ++index) {
        value = (value ^ bytes[index]) * 16777619U;
    }
    return value;
}

}  // namespace

LanzhouMahjongGame::LanzhouMahjongGame(
    Wall wall, std::uint8_t dealer, std::int64_t base
) : wall_(std::move(wall)), dealer_(dealer), base_(base) {
    if (!valid_wall(wall_) || dealer >= 4 || base <= 0 ||
        base > std::numeric_limits<std::int64_t>::max() / 96) {
        throw std::invalid_argument("invalid Lanzhou Mahjong round setup");
    }
    state_.current = dealer_;
    state_.outcome.next_dealer = dealer_;
    for (std::uint8_t turn = 0; turn < 13; ++turn) {
        for (std::uint8_t offset = 0; offset < 4; ++offset) {
            const auto player = static_cast<std::uint8_t>((dealer_ + offset) % 4);
            ++state_.hands[player][wall_[state_.front++]];
        }
    }
    ++state_.hands[dealer_][wall_[state_.front++]];
}

LanzhouMahjongGame::Wall LanzhouMahjongGame::shuffled_wall(std::uint64_t seed) {
    Wall result{};
    for (std::size_t index = 0; index < result.size(); ++index) {
        result[index] = static_cast<std::uint8_t>(index / 4);
    }
    for (std::size_t remaining = result.size(); remaining > 1; --remaining) {
        std::swap(result[remaining - 1], result[next_random(seed) % remaining]);
    }
    return result;
}

bool LanzhouMahjongGame::valid_wall(const Wall& wall) noexcept {
    Hand counts{};
    for (const auto tile : wall) {
        if (tile >= 27 || ++counts[tile] > 4) {
            return false;
        }
    }
    return true;
}

LanzhouMahjongPhase LanzhouMahjongGame::phase() const noexcept {
    return state_.phase;
}

std::uint8_t LanzhouMahjongGame::current_player() const noexcept {
    return state_.current;
}

std::uint8_t LanzhouMahjongGame::dealer() const noexcept {
    return dealer_;
}

std::size_t LanzhouMahjongGame::wall_remaining() const noexcept {
    return state_.back - state_.front;
}

const LanzhouMahjongGame::Hand& LanzhouMahjongGame::hand(
    std::uint8_t player
) const {
    if (player >= 4) {
        throw std::out_of_range("invalid Lanzhou Mahjong seat");
    }
    return state_.hands[player];
}

const std::vector<LanzhouMahjongMeld>& LanzhouMahjongGame::melds(
    std::uint8_t player
) const {
    if (player >= 4) {
        throw std::out_of_range("invalid Lanzhou Mahjong seat");
    }
    return state_.melds[player];
}

const std::vector<std::uint8_t>& LanzhouMahjongGame::discards(
    std::uint8_t player
) const {
    if (player >= 4) {
        throw std::out_of_range("invalid Lanzhou Mahjong seat");
    }
    return state_.discards[player];
}

bool LanzhouMahjongGame::ready(std::uint8_t player) const {
    if (player >= 4) {
        throw std::out_of_range("invalid Lanzhou Mahjong seat");
    }
    return state_.ready[player];
}

bool LanzhouMahjongGame::responded(std::uint8_t player) const {
    if (player >= 4) {
        throw std::out_of_range("invalid Lanzhou Mahjong seat");
    }
    return state_.claims[player].responded;
}

std::optional<std::uint8_t> LanzhouMahjongGame::pending_tile() const noexcept {
    return state_.pending_tile;
}

std::optional<std::uint8_t> LanzhouMahjongGame::pending_player() const noexcept {
    return state_.pending_player;
}

std::vector<std::uint8_t> LanzhouMahjongGame::waits_for(
    const Hand& hand, const std::vector<LanzhouMahjongMeld>& melds
) const {
    if (melds.size() > 4) {
        return {};
    }
    std::size_t count = 0;
    for (const auto copies : hand) {
        if (copies > 4) {
            return {};
        }
        count += copies;
    }
    if (count != 13 - 3 * melds.size()) {
        return {};
    }
    std::array<bool, 3> suit_present{};
    auto physical = hand;
    for (const auto& meld : melds) {
        if (meld.tile >= physical.size()) return {};
        physical[meld.tile] += meld.kind == LanzhouMahjongMeldKind::pung ? 3 : 4;
        if (physical[meld.tile] > 4) return {};
        suit_present[meld.tile / 9] = true;
    }
    std::vector<std::uint8_t> result;
    auto candidate = hand;
    for (std::uint8_t tile = 0; tile < 27; ++tile) {
        if (physical[tile] >= 4) {
            continue;
        }
        ++candidate[tile];
        auto present = suit_present;
        for (std::uint8_t kind = 0; kind < 27; ++kind) {
            present[kind / 9] |= candidate[kind] != 0;
        }
        if (std::find(present.begin(), present.end(), false) != present.end() &&
            (LanzhouMahjongRound::is_concealed_four_meld_pair(
                 candidate, static_cast<std::uint8_t>(melds.size())
             ) || (melds.empty() &&
                   LanzhouMahjongRound::is_closed_seven_pairs(candidate)))) {
            result.push_back(tile);
        }
        --candidate[tile];
    }
    return result;
}

std::vector<std::uint8_t> LanzhouMahjongGame::waits(
    std::uint8_t player
) const {
    if (player >= 4) {
        throw std::out_of_range("invalid Lanzhou Mahjong seat");
    }
    if (state_.ready[player]) {
        return state_.locked_waits[player];
    }
    return waits_for(state_.hands[player], state_.melds[player]);
}

std::vector<std::uint8_t> LanzhouMahjongGame::waits_after_discard(
    std::uint8_t player, std::uint8_t tile
) const {
    if (player >= 4 || tile >= 27 || state_.hands[player][tile] == 0) {
        return {};
    }
    auto after = state_.hands[player];
    --after[tile];
    return waits_for(after, state_.melds[player]);
}

bool LanzhouMahjongGame::can_win(
    std::uint8_t player, std::optional<std::uint8_t> offered
) const {
    if (player >= 4 || state_.melds[player].size() > 4) {
        return false;
    }
    auto concealed = state_.hands[player];
    if (offered) {
        if (*offered >= 27 || concealed[*offered] >= 4) {
            return false;
        }
        ++concealed[*offered];
    }
    if (state_.ready[player]) {
        const auto winning_tile = offered ? offered : state_.drawn_tile;
        if (!winning_tile || !contains(state_.locked_waits[player], *winning_tile)) {
            return false;
        }
    }
    std::array<bool, 3> present{};
    for (std::uint8_t tile = 0; tile < 27; ++tile) {
        present[tile / 9] |= concealed[tile] != 0;
    }
    for (const auto& meld : state_.melds[player]) {
        present[meld.tile / 9] = true;
    }
    return std::find(present.begin(), present.end(), false) != present.end() &&
           (LanzhouMahjongRound::is_concealed_four_meld_pair(
                concealed, static_cast<std::uint8_t>(state_.melds[player].size())
            ) || (state_.melds[player].empty() &&
                  LanzhouMahjongRound::is_closed_seven_pairs(concealed)));
}

std::vector<LanzhouMahjongAction> LanzhouMahjongGame::legal_actions(
    std::uint8_t player
) const {
    std::vector<LanzhouMahjongAction> result;
    if (player >= 4) {
        return result;
    }
    if (state_.phase == LanzhouMahjongPhase::draw &&
        player == state_.current && wall_remaining() > 0) {
        result.push_back({LanzhouMahjongActionKind::draw, player});
    } else if (state_.phase == LanzhouMahjongPhase::discard &&
               player == state_.current) {
        if (can_win(player, std::nullopt)) {
            result.push_back({LanzhouMahjongActionKind::win, player});
        }
        if (state_.ready[player]) {
            if (state_.drawn_tile && state_.hands[player][*state_.drawn_tile] != 0) {
                result.push_back({LanzhouMahjongActionKind::discard, player,
                                  *state_.drawn_tile});
            }
            if (wall_remaining() > 0) {
                for (std::uint8_t tile = 0; tile < 27; ++tile) {
                    if (state_.hands[player][tile] == 4) {
                        auto after = state_.hands[player];
                        after[tile] -= 4;
                        auto melds_after = state_.melds[player];
                        melds_after.push_back({
                            LanzhouMahjongMeldKind::concealed_kong, tile, player
                        });
                        if (waits_for(after, melds_after) ==
                            state_.locked_waits[player]) {
                            result.push_back({
                                LanzhouMahjongActionKind::concealed_kong,
                                player, tile
                            });
                        }
                    }
                    if (state_.hands[player][tile] > 0) {
                        for (const auto& meld : state_.melds[player]) {
                            if (meld.kind != LanzhouMahjongMeldKind::pung ||
                                meld.tile != tile) {
                                continue;
                            }
                            auto after = state_.hands[player];
                            --after[tile];
                            if (waits_for(after, state_.melds[player]) ==
                                state_.locked_waits[player]) {
                                result.push_back({
                                    LanzhouMahjongActionKind::added_kong,
                                    player, tile
                                });
                            }
                            break;
                        }
                    }
                }
            }
            return result;
        }
        for (std::uint8_t tile = 0; tile < 27; ++tile) {
            if (state_.hands[player][tile] == 0) {
                continue;
            }
            result.push_back({LanzhouMahjongActionKind::discard, player, tile});
            auto after = state_.hands[player];
            --after[tile];
            if (!waits_for(after, state_.melds[player]).empty()) {
                result.push_back({LanzhouMahjongActionKind::ready_discard,
                                  player, tile});
            }
            if (wall_remaining() > 0) {
                if (state_.hands[player][tile] == 4) {
                    result.push_back({LanzhouMahjongActionKind::concealed_kong,
                                      player, tile});
                }
                for (const auto& meld : state_.melds[player]) {
                    if (meld.kind == LanzhouMahjongMeldKind::pung &&
                        meld.tile == tile) {
                        result.push_back({LanzhouMahjongActionKind::added_kong,
                                          player, tile});
                        break;
                    }
                }
            }
        }
    } else if ((state_.phase == LanzhouMahjongPhase::claim ||
                state_.phase == LanzhouMahjongPhase::rob_kong) &&
               player != *state_.pending_player &&
               !state_.claims[player].responded) {
        result.push_back({LanzhouMahjongActionKind::pass, player});
        if (can_win(player, state_.pending_tile)) {
            result.push_back({LanzhouMahjongActionKind::win, player});
        }
        if (state_.phase == LanzhouMahjongPhase::claim &&
            !state_.ready[player]) {
            const auto tile = *state_.pending_tile;
            if (state_.hands[player][tile] >= 2) {
                result.push_back({LanzhouMahjongActionKind::pung, player});
            }
            if (state_.hands[player][tile] >= 3 && wall_remaining() > 0) {
                result.push_back({LanzhouMahjongActionKind::exposed_kong, player});
            }
        }
    }
    return result;
}

const LanzhouMahjongPayment& LanzhouMahjongGame::net_scores() const noexcept {
    return state_.scores;
}

const LanzhouMahjongOutcome& LanzhouMahjongGame::outcome() const noexcept {
    return state_.outcome;
}

bool LanzhouMahjongGame::valid_action(const LanzhouMahjongAction& action) const {
    if (action.player >= 4) {
        return false;
    }
    if (action.kind == LanzhouMahjongActionKind::win) {
        if (state_.phase == LanzhouMahjongPhase::discard) {
            return action.player == state_.current;
        }
        if (state_.phase == LanzhouMahjongPhase::claim ||
            state_.phase == LanzhouMahjongPhase::rob_kong) {
            return action.player != *state_.pending_player &&
                   !state_.claims[action.player].responded;
        }
        return false;
    }
    const auto candidates = legal_actions(action.player);
    return std::any_of(candidates.begin(), candidates.end(), [&](const auto& candidate) {
        return candidate.kind == action.kind && candidate.tile == action.tile;
    });
}

ActionResult LanzhouMahjongGame::apply(const LanzhouMahjongAction& action) {
    static_assert(std::is_nothrow_move_assignable_v<State>);
    static_assert(std::is_nothrow_move_constructible_v<State>);
    static_assert(std::is_nothrow_copy_constructible_v<LanzhouMahjongAction>);
    if (!valid_action(action)) {
        return {false, EngineError::illegal_action};
    }
    // Copy only the current position, before any mutation. Moving it back cannot allocate.
    auto before = state_;
    const auto old_history_size = history_.size();
    const auto old_action_size = actions_.size();
    try {
        if (!perform(action)) {
            state_ = std::move(before);
            return {false, EngineError::invalid_state};
        }
        // Append the POD action first. A failed history allocation leaves before intact;
        // after its noexcept move succeeds there are no remaining throwing operations.
        actions_.push_back(action);
        history_.push_back(std::move(before));
    } catch (...) {
        if (history_.size() > old_history_size) {
            state_ = std::move(history_.back());
            history_.pop_back();
        } else {
            state_ = std::move(before);
        }
        if (actions_.size() > old_action_size) actions_.pop_back();
        throw;
    }
    return {true, EngineError::none};
}

bool LanzhouMahjongGame::undo() {
    static_assert(std::is_nothrow_move_assignable_v<State>);
    if (history_.empty() || history_.size() != actions_.size()) {
        return false;
    }
    state_ = std::move(history_.back());
    history_.pop_back();
    actions_.pop_back();
    return true;
}

bool LanzhouMahjongGame::add_payment(
    const std::optional<LanzhouMahjongPayment>& payment
) {
    return payment && lanzhou_add_payment(state_.scores, *payment);
}

void LanzhouMahjongGame::receive_draw(bool replacement) {
    if (state_.front == state_.back) {
        state_.phase = LanzhouMahjongPhase::exhaustive_draw;
        state_.scores.fill(0);
        state_.outcome.next_dealer = static_cast<std::uint8_t>((dealer_ + 1) % 4);
        return;
    }
    const auto tile = replacement ? wall_[--state_.back] : wall_[state_.front++];
    ++state_.hands[state_.current][tile];
    state_.drawn_tile = tile;
    state_.last_draw = state_.front == state_.back;
    state_.kong_draw = replacement;
    state_.phase = LanzhouMahjongPhase::discard;
}

void LanzhouMahjongGame::start_claim(
    std::uint8_t player, std::uint8_t tile, bool rob_kong
) {
    state_.pending_player = player;
    state_.pending_tile = tile;
    state_.claims = {};
    state_.phase = rob_kong ? LanzhouMahjongPhase::rob_kong :
                              LanzhouMahjongPhase::claim;
}

bool LanzhouMahjongGame::finish_win(
    std::uint8_t player, std::optional<std::uint8_t> payer
) {
    const bool robbed_kong = payer && state_.phase == LanzhouMahjongPhase::rob_kong;
    const bool last_tile = payer ? state_.pending_last_tile : state_.last_draw;
    const bool kong_draw = !payer && state_.kong_draw;
    auto winning_hand = state_.hands[player];
    if (payer) {
        ++winning_hand[*state_.pending_tile];
    }
    const bool seven_pairs = state_.melds[player].empty() &&
        LanzhouMahjongRound::is_closed_seven_pairs(winning_hand);
    const auto bonus = static_cast<std::uint8_t>(
        static_cast<int>(state_.ready[player]) +
        static_cast<int>(kong_draw) + static_cast<int>(last_tile) +
        static_cast<int>(seven_pairs)
    );
    if (!add_payment(lanzhou_win_payment(player, payer, base_, bonus))) {
        return false;
    }
    if (payer) {
        const auto tile = *state_.pending_tile;
        if (robbed_kong) {
            --state_.hands[*payer][tile];
        } else {
            state_.discards[*payer].pop_back();
        }
        ++state_.hands[player][tile];
    }
    state_.outcome.winner = player;
    state_.outcome.payer = payer;
    state_.outcome.next_dealer = player == dealer_ ? dealer_ :
        static_cast<std::uint8_t>((dealer_ + 1) % 4);
    state_.outcome.seven_pairs = seven_pairs;
    state_.outcome.ready = state_.ready[player];
    state_.outcome.kong_draw = kong_draw;
    state_.outcome.last_tile = last_tile;
    state_.pending_tile.reset();
    state_.pending_player.reset();
    state_.phase = LanzhouMahjongPhase::won;
    return true;
}

bool LanzhouMahjongGame::resolve_claims() {
    const auto source = *state_.pending_player;
    for (std::uint8_t distance = 1; distance < 4; ++distance) {
        const auto player = static_cast<std::uint8_t>((source + distance) % 4);
        if (state_.claims[player].kind == LanzhouMahjongActionKind::win) {
            return finish_win(player, source);
        }
    }
    if (state_.phase == LanzhouMahjongPhase::rob_kong) {
        const auto tile = *state_.pending_tile;
        auto& hand = state_.hands[source];
        --hand[tile];
        for (auto& meld : state_.melds[source]) {
            if (meld.kind == LanzhouMahjongMeldKind::pung && meld.tile == tile) {
                meld.kind = LanzhouMahjongMeldKind::exposed_kong;
                break;
            }
        }
        if (!add_payment(lanzhou_kong_payment(source, false, base_))) {
            return false;
        }
        state_.pending_tile.reset();
        state_.pending_player.reset();
        receive_draw(true);
        return true;
    }
    for (const auto kind : {LanzhouMahjongActionKind::exposed_kong,
                            LanzhouMahjongActionKind::pung}) {
        for (std::uint8_t distance = 1; distance < 4; ++distance) {
            const auto player = static_cast<std::uint8_t>((source + distance) % 4);
            if (state_.claims[player].kind != kind) {
                continue;
            }
            const auto tile = *state_.pending_tile;
            state_.discards[source].pop_back();
            state_.hands[player][tile] -= kind == LanzhouMahjongActionKind::pung ? 2 : 3;
            state_.melds[player].push_back({
                kind == LanzhouMahjongActionKind::pung ?
                    LanzhouMahjongMeldKind::pung : LanzhouMahjongMeldKind::exposed_kong,
                tile, source
            });
            state_.current = player;
            state_.pending_tile.reset();
            state_.pending_player.reset();
            state_.drawn_tile.reset();
            state_.last_draw = false;
            state_.kong_draw = false;
            if (kind == LanzhouMahjongActionKind::exposed_kong) {
                if (!add_payment(lanzhou_kong_payment(player, false, base_))) {
                    return false;
                }
                receive_draw(true);
            } else {
                state_.phase = LanzhouMahjongPhase::discard;
            }
            return true;
        }
    }
    state_.pending_tile.reset();
    state_.pending_player.reset();
    if (state_.front == state_.back) {
        state_.phase = LanzhouMahjongPhase::exhaustive_draw;
        state_.scores.fill(0);
        state_.outcome.next_dealer = static_cast<std::uint8_t>((dealer_ + 1) % 4);
    } else {
        state_.current = static_cast<std::uint8_t>((source + 1) % 4);
        state_.phase = LanzhouMahjongPhase::draw;
    }
    return true;
}

bool LanzhouMahjongGame::perform(const LanzhouMahjongAction& action) {
    const auto player = action.player;
    const auto tile = action.tile;
    switch (action.kind) {
        case LanzhouMahjongActionKind::draw:
            receive_draw(false);
            return true;
        case LanzhouMahjongActionKind::discard:
        case LanzhouMahjongActionKind::ready_discard: {
            --state_.hands[player][tile];
            state_.discards[player].push_back(tile);
            if (action.kind == LanzhouMahjongActionKind::ready_discard) {
                state_.ready[player] = true;
                state_.locked_waits[player] = waits_for(
                    state_.hands[player], state_.melds[player]
                );
            }
            state_.pending_last_tile = state_.last_draw;
            state_.last_draw = false;
            state_.drawn_tile.reset();
            state_.kong_draw = false;
            start_claim(player, tile, false);
            return true;
        }
        case LanzhouMahjongActionKind::pass:
        case LanzhouMahjongActionKind::pung:
        case LanzhouMahjongActionKind::exposed_kong:
            state_.claims[player] = {true, action.kind};
            break;
        case LanzhouMahjongActionKind::concealed_kong:
            state_.hands[player][tile] -= 4;
            state_.melds[player].push_back({
                LanzhouMahjongMeldKind::concealed_kong, tile, player
            });
            if (!add_payment(lanzhou_kong_payment(player, true, base_))) {
                return false;
            }
            receive_draw(true);
            return true;
        case LanzhouMahjongActionKind::added_kong:
            state_.pending_last_tile = false;
            start_claim(player, tile, true);
            return true;
        case LanzhouMahjongActionKind::win:
            if (can_win(player, state_.phase == LanzhouMahjongPhase::discard ?
                                std::nullopt : state_.pending_tile)) {
                if (state_.phase == LanzhouMahjongPhase::discard) {
                    return finish_win(player, std::nullopt);
                }
                state_.claims[player] = {true, action.kind};
                break;
            }
            if (!add_payment(rule_ == LanzhouMahjongRule::legacy_false_win_8
                    ? lanzhou_false_win_payment(player, base_, 3)
                    : lanzhou_project_false_win_payment(player, base_))) {
                return false;
            }
            state_.outcome.false_win = true;
            state_.outcome.payer = player;
            state_.outcome.next_dealer = dealer_;
            state_.phase = LanzhouMahjongPhase::won;
            return true;
    }
    for (std::uint8_t seat = 0; seat < 4; ++seat) {
        if (seat != *state_.pending_player && !state_.claims[seat].responded) {
            return true;
        }
    }
    return resolve_claims();
}

std::vector<std::uint8_t> LanzhouMahjongGame::serialize() const {
    std::vector<std::uint8_t> output;
    output.reserve(130 + actions_.size() * 3);
    output.insert(output.end(), {'L', 'Z', 'M', 'J',
        static_cast<std::uint8_t>(rule_), dealer_});
    write_number(output, static_cast<std::uint64_t>(base_), 8);
    output.insert(output.end(), wall_.begin(), wall_.end());
    write_number(output, actions_.size(), 4);
    for (const auto& action : actions_) {
        output.push_back(static_cast<std::uint8_t>(action.kind));
        output.push_back(action.player);
        output.push_back(action.tile);
    }
    write_number(output, checksum(output, output.size()), 4);
    return output;
}

RestoreResult LanzhouMahjongGame::restore(
    const std::vector<std::uint8_t>& data
) {
    constexpr std::size_t minimum_size = 130;
    if (data.size() < minimum_size || data[0] != 'L' || data[1] != 'Z' ||
        data[2] != 'M' || data[3] != 'J') {
        return {false, EngineError::corrupted_data};
    }
    if (data[4] != static_cast<std::uint8_t>(LanzhouMahjongRule::legacy_false_win_8) &&
        data[4] != static_cast<std::uint8_t>(LanzhouMahjongRule::project_false_win_24)) {
        return {false, EngineError::unsupported};
    }
    const auto action_count = read_number(data, 122, 4);
    if (action_count > 1000 ||
        data.size() != minimum_size + action_count * 3 ||
        checksum(data, data.size() - 4) !=
            read_number(data, data.size() - 4, 4)) {
        return {false, EngineError::corrupted_data};
    }
    const auto raw_base = read_number(data, 6, 8);
    if (raw_base == 0 ||
        raw_base > static_cast<std::uint64_t>(
            std::numeric_limits<std::int64_t>::max() / 96)) {
        return {false, EngineError::corrupted_data};
    }
    Wall wall{};
    std::copy_n(data.begin() + 14, wall.size(), wall.begin());
    if (!valid_wall(wall) || data[5] >= 4) {
        return {false, EngineError::corrupted_data};
    }
    LanzhouMahjongGame replay(wall, data[5], static_cast<std::int64_t>(raw_base));
    replay.rule_ = static_cast<LanzhouMahjongRule>(data[4]);
    for (std::size_t index = 0; index < action_count; ++index) {
        const auto offset = 126 + index * 3;
        if (data[offset] > static_cast<std::uint8_t>(LanzhouMahjongActionKind::win)) {
            return {false, EngineError::corrupted_data};
        }
        const LanzhouMahjongAction action{
            static_cast<LanzhouMahjongActionKind>(data[offset]),
            data[offset + 1], data[offset + 2]
        };
        if (!replay.apply(action).accepted) {
            return {false, EngineError::corrupted_data};
        }
    }
    *this = std::move(replay);
    return {true, EngineError::none};
}

}  // namespace mocs::engine
