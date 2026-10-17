#include "mocs/engine/competition_mahjong.hpp"

#include <algorithm>
#include <array>
#include <stdexcept>
#include <utility>

namespace mocs::engine {
namespace {
constexpr std::uint8_t first_flower = 34;
constexpr std::uint8_t tile_kinds = 42;

[[nodiscard]] bool is_flower(const CompetitionMahjongTile tile) noexcept {
    return tile >= first_flower;
}
}  // namespace

std::vector<CompetitionMahjongTile> competition_mahjong_canonical_wall() {
    std::vector<CompetitionMahjongTile> wall;
    wall.reserve(competition_mahjong_tile_count);
    for (std::uint8_t tile = 0; tile < first_flower; ++tile) {
        for (int copy = 0; copy < 4; ++copy) wall.push_back(tile);
    }
    for (std::uint8_t tile = first_flower; tile < tile_kinds; ++tile) {
        wall.push_back(tile);
    }
    return wall;
}

bool competition_mahjong_valid_wall(
    const std::vector<CompetitionMahjongTile>& wall
) noexcept {
    if (wall.size() != competition_mahjong_tile_count) return false;
    std::array<std::uint8_t, tile_kinds> counts{};
    for (const auto tile : wall) {
        if (tile >= tile_kinds) return false;
        ++counts[tile];
    }
    for (std::uint8_t tile = 0; tile < tile_kinds; ++tile) {
        if (counts[tile] != (tile < first_flower ? 4 : 1)) return false;
    }
    return true;
}

CompetitionMahjongHand::CompetitionMahjongHand(
    std::vector<CompetitionMahjongTile> wall,
    const std::uint8_t dealer
) : wall_(std::move(wall)), back_(wall_.size()), dealer_(dealer),
    current_player_(dealer) {
    if (!competition_mahjong_valid_wall(wall_) || dealer >= competition_mahjong_player_count) {
        throw std::invalid_argument("invalid competition Mahjong wall or dealer");
    }

    // The caller supplies wall order after the dice/wall-break step.
    for (int packet = 0; packet < 3; ++packet) {
        for (std::uint8_t offset = 0; offset < competition_mahjong_player_count; ++offset) {
            const auto player = static_cast<std::uint8_t>((dealer + offset) % 4);
            for (int tile = 0; tile < 4; ++tile) concealed_[player].push_back(take_front());
        }
    }
    concealed_[dealer].push_back(take_front());
    concealed_[dealer].push_back(take_front());
    for (std::uint8_t offset = 1; offset < competition_mahjong_player_count; ++offset) {
        concealed_[(dealer + offset) % 4].push_back(take_front());
    }
    for (std::uint8_t offset = 0; offset < competition_mahjong_player_count; ++offset) {
        replace_initial_flowers(static_cast<std::uint8_t>((dealer + offset) % 4));
    }
}

CompetitionMahjongTile CompetitionMahjongHand::take_front() {
    return wall_[front_++];
}

CompetitionMahjongTile CompetitionMahjongHand::take_back() {
    return wall_[--back_];
}

bool CompetitionMahjongHand::receive(
    const std::uint8_t player,
    CompetitionMahjongTile tile
) {
    while (is_flower(tile)) {
        flowers_[player].push_back(tile);
        if (front_ == back_) return false;
        tile = take_back();
    }
    concealed_[player].push_back(tile);
    return true;
}

void CompetitionMahjongHand::replace_initial_flowers(const std::uint8_t player) {
    auto& hand = concealed_[player];
    for (std::size_t index = 0; index < hand.size();) {
        if (!is_flower(hand[index])) {
            ++index;
            continue;
        }
        flowers_[player].push_back(hand[index]);
        hand.erase(hand.begin() + static_cast<std::ptrdiff_t>(index));
        if (!receive(player, take_back())) {
            throw std::logic_error("initial flower replacement exhausted the wall");
        }
    }
}

std::uint8_t CompetitionMahjongHand::current_player() const noexcept {
    return current_player_;
}

std::uint8_t CompetitionMahjongHand::dealer() const noexcept { return dealer_; }

CompetitionMahjongPhase CompetitionMahjongHand::phase() const noexcept {
    return phase_;
}

std::size_t CompetitionMahjongHand::wall_remaining() const noexcept {
    return back_ - front_;
}

const std::vector<CompetitionMahjongTile>& CompetitionMahjongHand::concealed(
    const std::uint8_t player
) const {
    return concealed_.at(player);
}

const std::vector<CompetitionMahjongTile>& CompetitionMahjongHand::flowers(
    const std::uint8_t player
) const {
    return flowers_.at(player);
}

const std::vector<CompetitionMahjongTile>& CompetitionMahjongHand::discards(
    const std::uint8_t player
) const {
    return discards_.at(player);
}

const std::vector<CompetitionMahjongMeld>& CompetitionMahjongHand::melds(
    const std::uint8_t player
) const {
    return melds_.at(player);
}

std::optional<CompetitionMahjongTile> CompetitionMahjongHand::pending_discard() const noexcept {
    if (phase_ != CompetitionMahjongPhase::claim) return std::nullopt;
    return claim_tile_;
}

std::optional<std::uint8_t> CompetitionMahjongHand::pending_discarder() const noexcept {
    if (phase_ != CompetitionMahjongPhase::claim) return std::nullopt;
    return discarder_;
}

CompetitionMahjongActionResult CompetitionMahjongHand::draw() {
    if (phase_ != CompetitionMahjongPhase::draw) {
        return {false, CompetitionMahjongError::wrong_phase};
    }
    if (front_ == back_) {
        phase_ = CompetitionMahjongPhase::exhaustive_draw;
        return {true, CompetitionMahjongError::none};
    }
    if (!receive(current_player_, take_front())) {
        phase_ = CompetitionMahjongPhase::exhaustive_draw;
        return {true, CompetitionMahjongError::none};
    }
    phase_ = CompetitionMahjongPhase::discard;
    return {true, CompetitionMahjongError::none};
}

CompetitionMahjongActionResult CompetitionMahjongHand::discard(
    const CompetitionMahjongTile tile
) {
    if (phase_ != CompetitionMahjongPhase::discard) {
        return {false, CompetitionMahjongError::wrong_phase};
    }
    auto& hand = concealed_[current_player_];
    const auto found = std::find(hand.begin(), hand.end(), tile);
    if (found == hand.end()) {
        return {false, CompetitionMahjongError::tile_not_in_hand};
    }
    hand.erase(found);
    discards_[current_player_].push_back(tile);
    discarder_ = current_player_;
    claim_tile_ = tile;
    claims_ = {};
    current_player_ = static_cast<std::uint8_t>((current_player_ + 1) % 4);
    phase_ = CompetitionMahjongPhase::claim;
    return {true, CompetitionMahjongError::none};
}

bool CompetitionMahjongHand::valid_claim(
    const std::uint8_t player,
    const CompetitionMahjongClaimKind kind,
    const CompetitionMahjongTile sequence_start
) const {
    if (sequence_start != 0 && kind != CompetitionMahjongClaimKind::chow) return false;
    if (kind == CompetitionMahjongClaimKind::pass) return true;
    if (claim_tile_ >= first_flower) return false;
    const auto& hand = concealed_[player];
    if (kind == CompetitionMahjongClaimKind::pung) {
        return std::count(hand.begin(), hand.end(), claim_tile_) >= 2;
    }
    if (kind == CompetitionMahjongClaimKind::kong) {
        return wall_remaining() > 0 &&
            std::count(hand.begin(), hand.end(), claim_tile_) >= 3;
    }
    if (kind != CompetitionMahjongClaimKind::chow || player != current_player_ ||
        claim_tile_ >= 27 || sequence_start >= 27 || sequence_start % 9 > 6 ||
        claim_tile_ < sequence_start || claim_tile_ > sequence_start + 2) {
        return false;
    }
    for (std::uint8_t tile = sequence_start; tile < sequence_start + 3; ++tile) {
        if (tile != claim_tile_ && std::find(hand.begin(), hand.end(), tile) == hand.end()) {
            return false;
        }
    }
    return true;
}

CompetitionMahjongActionResult CompetitionMahjongHand::submit_claim(
    const std::uint8_t player,
    const CompetitionMahjongClaimKind kind,
    const CompetitionMahjongTile sequence_start
) {
    if (phase_ != CompetitionMahjongPhase::claim) {
        return {false, CompetitionMahjongError::wrong_phase};
    }
    if (player >= competition_mahjong_player_count || player == discarder_ ||
        !valid_claim(player, kind, sequence_start)) {
        return {false, CompetitionMahjongError::invalid_claim};
    }
    if (claims_[player].responded) {
        return {false, CompetitionMahjongError::already_responded};
    }
    claims_[player] = {true, kind, sequence_start};
    std::uint8_t responses = 0;
    for (std::uint8_t offset = 1; offset < competition_mahjong_player_count; ++offset) {
        if (claims_[(discarder_ + offset) % 4].responded) ++responses;
    }
    if (responses == 3) resolve_claims();
    return {true, CompetitionMahjongError::none};
}

void CompetitionMahjongHand::resolve_claims() {
    std::uint8_t winner = competition_mahjong_player_count;
    for (std::uint8_t offset = 1; offset < competition_mahjong_player_count; ++offset) {
        const auto player = static_cast<std::uint8_t>((discarder_ + offset) % 4);
        const auto kind = claims_[player].kind;
        if (kind == CompetitionMahjongClaimKind::pung ||
            kind == CompetitionMahjongClaimKind::kong) {
            winner = player;
            break;
        }
    }
    if (winner == competition_mahjong_player_count &&
        claims_[current_player_].kind == CompetitionMahjongClaimKind::chow) {
        winner = current_player_;
    }
    if (winner == competition_mahjong_player_count) {
        phase_ = CompetitionMahjongPhase::draw;
        return;
    }

    const auto intent = claims_[winner];
    auto& hand = concealed_[winner];
    const auto remove_one = [&hand](const CompetitionMahjongTile tile) {
        hand.erase(std::find(hand.begin(), hand.end(), tile));
    };
    if (intent.kind == CompetitionMahjongClaimKind::chow) {
        for (std::uint8_t tile = intent.sequence_start;
             tile < intent.sequence_start + 3; ++tile) {
            if (tile != claim_tile_) remove_one(tile);
        }
        melds_[winner].push_back({CompetitionMahjongMeldKind::chow, claim_tile_,
                                  intent.sequence_start, discarder_});
    } else {
        const bool kong = intent.kind == CompetitionMahjongClaimKind::kong;
        for (int copy = 0; copy < (kong ? 3 : 2); ++copy) remove_one(claim_tile_);
        melds_[winner].push_back({kong ? CompetitionMahjongMeldKind::kong :
                                       CompetitionMahjongMeldKind::pung,
                                  claim_tile_, 0, discarder_});
    }
    discards_[discarder_].pop_back();
    current_player_ = winner;
    phase_ = CompetitionMahjongPhase::discard;
    if (intent.kind == CompetitionMahjongClaimKind::kong &&
        !receive(winner, take_back())) {
        phase_ = CompetitionMahjongPhase::exhaustive_draw;
    }
}

}  // namespace mocs::engine
