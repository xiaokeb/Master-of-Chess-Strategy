#include "mocs/engine/lanzhou_mahjong.hpp"

#include <stdexcept>
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

bool melds_complete(LanzhouMahjongRound::Hand& tiles) {
    std::size_t first = 0;
    while (first < tiles.size() && tiles[first] == 0) {
        ++first;
    }
    if (first == tiles.size()) {
        return true;
    }

    if (tiles[first] >= 3) {
        tiles[first] -= 3;
        const bool complete = melds_complete(tiles);
        tiles[first] += 3;
        if (complete) {
            return true;
        }
    }
    if (first % 9 <= 6 && tiles[first + 1] > 0 && tiles[first + 2] > 0) {
        --tiles[first];
        --tiles[first + 1];
        --tiles[first + 2];
        const bool complete = melds_complete(tiles);
        ++tiles[first];
        ++tiles[first + 1];
        ++tiles[first + 2];
        if (complete) {
            return true;
        }
    }
    return false;
}

}  // namespace

LanzhouMahjongRound::LanzhouMahjongRound(std::uint64_t seed) {
    reset(seed);
}

void LanzhouMahjongRound::reset(std::uint64_t seed) {
    for (std::size_t index = 0; index < tile_count; ++index) {
        wall_[index] = static_cast<std::uint8_t>(index / 4);
    }
    for (std::size_t remaining = tile_count; remaining > 1; --remaining) {
        const auto index = next_random(seed) % remaining;
        std::swap(wall_[remaining - 1], wall_[index]);
    }
    deal_from_wall();
}

bool LanzhouMahjongRound::reset_with_wall(
    const std::array<std::uint8_t, tile_count>& wall
) {
    Hand counts{};
    for (const auto tile : wall) {
        if (tile >= tile_kind_count || ++counts[tile] > 4) {
            return false;
        }
    }
    wall_ = wall;
    deal_from_wall();
    return true;
}

void LanzhouMahjongRound::deal_from_wall() {
    for (auto& hand : hands_) {
        hand.fill(0);
    }
    for (auto& pile : discards_) {
        pile.clear();
    }
    next_tile_ = 0;
    for (std::uint8_t turn = 0; turn < 13; ++turn) {
        for (std::uint8_t player = 0; player < player_count; ++player) {
            ++hands_[player][wall_[next_tile_++]];
        }
    }
    ++hands_[0][wall_[next_tile_++]];
    current_player_ = 0;
    phase_ = Phase::discard;
}

std::uint8_t LanzhouMahjongRound::current_player() const noexcept {
    return current_player_;
}

LanzhouMahjongRound::Phase LanzhouMahjongRound::phase() const noexcept {
    return phase_;
}

std::size_t LanzhouMahjongRound::wall_remaining() const noexcept {
    return tile_count - next_tile_;
}

const LanzhouMahjongRound::Hand& LanzhouMahjongRound::hand(
    std::uint8_t player
) const {
    if (player >= player_count) {
        throw std::out_of_range("invalid Lanzhou Mahjong player");
    }
    return hands_[player];
}

const std::vector<std::uint8_t>& LanzhouMahjongRound::discards(
    std::uint8_t player
) const {
    if (player >= player_count) {
        throw std::out_of_range("invalid Lanzhou Mahjong player");
    }
    return discards_[player];
}

std::vector<std::uint8_t> LanzhouMahjongRound::legal_discards() const {
    std::vector<std::uint8_t> result;
    if (phase_ != Phase::discard) {
        return result;
    }
    for (std::uint8_t tile = 0; tile < tile_kind_count; ++tile) {
        if (hands_[current_player_][tile] != 0) {
            result.push_back(tile);
        }
    }
    return result;
}

bool LanzhouMahjongRound::has_missing_suit(const Hand& hand) noexcept {
    for (std::size_t suit = 0; suit < 3; ++suit) {
        bool present = false;
        for (std::size_t rank = 0; rank < 9; ++rank) {
            present |= hand[suit * 9 + rank] != 0;
        }
        if (!present) {
            return true;
        }
    }
    return false;
}

bool LanzhouMahjongRound::is_closed_four_meld_pair(const Hand& hand) {
    return is_concealed_four_meld_pair(hand, 0);
}

bool LanzhouMahjongRound::is_closed_seven_pairs(const Hand& hand) noexcept {
    std::size_t pairs = 0;
    for (const auto copies : hand) {
        if (copies > 4 || copies % 2 != 0) {
            return false;
        }
        pairs += copies / 2;
    }
    return pairs == 7;
}

bool LanzhouMahjongRound::is_concealed_four_meld_pair(
    const Hand& hand, std::uint8_t exposed_melds
) {
    if (exposed_melds > 4) {
        return false;
    }
    std::size_t tile_total = 0;
    for (const auto copies : hand) {
        if (copies > 4) {
            return false;
        }
        tile_total += copies;
    }
    if (tile_total != 14 - 3 * exposed_melds) {
        return false;
    }

    for (std::size_t pair = 0; pair < hand.size(); ++pair) {
        if (hand[pair] < 2) {
            continue;
        }
        auto remaining = hand;
        remaining[pair] -= 2;
        if (melds_complete(remaining)) {
            return true;
        }
    }
    return false;
}

std::vector<std::uint8_t> LanzhouMahjongRound::closed_four_meld_pair_waits(
    const Hand& hand
) {
    std::size_t tile_total = 0;
    for (const auto copies : hand) {
        if (copies > 4) {
            return {};
        }
        tile_total += copies;
    }
    if (tile_total != 13) {
        return {};
    }

    std::vector<std::uint8_t> waits;
    auto candidate = hand;
    for (std::uint8_t tile = 0; tile < tile_kind_count; ++tile) {
        if (candidate[tile] == 4) {
            continue;
        }
        ++candidate[tile];
        if (has_missing_suit(candidate) && is_closed_four_meld_pair(candidate)) {
            waits.push_back(tile);
        }
        --candidate[tile];
    }
    return waits;
}

bool LanzhouMahjongRound::can_self_draw_four_meld_pair() const {
    if (phase_ != Phase::discard) {
        return false;
    }
    const auto& current_hand = hands_[current_player_];
    return has_missing_suit(current_hand) &&
           is_closed_four_meld_pair(current_hand);
}

ActionResult LanzhouMahjongRound::draw(std::uint8_t player) {
    if (phase_ != Phase::draw || player != current_player_) {
        return {false, EngineError::illegal_action};
    }
    if (next_tile_ == tile_count) {
        return {false, EngineError::invalid_state};
    }
    ++hands_[player][wall_[next_tile_++]];
    phase_ = Phase::discard;
    return {true, EngineError::none};
}

ActionResult LanzhouMahjongRound::discard(
    std::uint8_t player,
    std::uint8_t tile
) {
    if (phase_ != Phase::discard || player != current_player_ ||
        tile >= tile_kind_count || hands_[player][tile] == 0) {
        return {false, EngineError::illegal_action};
    }
    --hands_[player][tile];
    discards_[player].push_back(tile);
    if (next_tile_ == tile_count) {
        phase_ = Phase::exhausted;
    } else {
        current_player_ = static_cast<std::uint8_t>((player + 1) % player_count);
        phase_ = Phase::draw;
    }
    return {true, EngineError::none};
}

}  // namespace mocs::engine
