#include "mocs/engine/lanzhou_mahjong.hpp"

#include <array>
#include <cstdint>
#include <cstdlib>
#include <initializer_list>

using mocs::engine::LanzhouMahjongRound;

namespace {

void require(bool condition) {
    if (!condition) {
        std::abort();
    }
}

std::size_t hand_size(const LanzhouMahjongRound::Hand& hand) {
    std::size_t count = 0;
    for (const auto copies : hand) {
        count += copies;
    }
    return count;
}

void test_initial_deal_and_turns() {
    LanzhouMahjongRound round(42);
    require(round.current_player() == 0);
    require(round.phase() == LanzhouMahjongRound::Phase::discard);
    require(round.wall_remaining() == 55);
    require(hand_size(round.hand(0)) == 14);
    for (std::uint8_t player = 1; player < 4; ++player) {
        require(hand_size(round.hand(player)) == 13);
    }

    const auto before = round.hand(0);
    require(!round.draw(0).accepted);
    require(!round.discard(1, round.legal_discards().front()).accepted);
    require(!round.discard(0, 27).accepted);
    require(round.hand(0) == before);

    const auto tile = round.legal_discards().front();
    require(round.discard(0, tile).accepted);
    require(round.current_player() == 1);
    require(round.phase() == LanzhouMahjongRound::Phase::draw);
    require(round.discards(0).size() == 1);
    require(round.discards(0).front() == tile);
    require(round.legal_discards().empty());
    require(!round.draw(2).accepted);
    require(round.draw(1).accepted);
    require(hand_size(round.hand(1)) == 14);
    require(round.wall_remaining() == 54);
}

void test_tile_conservation_and_exhaustion() {
    LanzhouMahjongRound round(2026);
    std::size_t actions = 0;
    while (round.phase() != LanzhouMahjongRound::Phase::exhausted) {
        const auto player = round.current_player();
        if (round.phase() == LanzhouMahjongRound::Phase::draw) {
            require(round.draw(player).accepted);
        } else {
            require(round.discard(player, round.legal_discards().front()).accepted);
        }
        require(++actions < 200);
    }
    require(round.wall_remaining() == 0);
    require(!round.draw(round.current_player()).accepted);
    require(!round.discard(round.current_player(), 0).accepted);

    std::array<unsigned int, 27> copies{};
    for (std::uint8_t player = 0; player < 4; ++player) {
        const auto& hand = round.hand(player);
        for (std::uint8_t tile = 0; tile < 27; ++tile) {
            copies[tile] += hand[tile];
        }
        for (const auto tile : round.discards(player)) {
            ++copies[tile];
        }
    }
    for (const auto count : copies) {
        require(count == 4);
    }
}

void test_seed_and_reset() {
    LanzhouMahjongRound first(7);
    LanzhouMahjongRound second(7);
    for (std::uint8_t player = 0; player < 4; ++player) {
        require(first.hand(player) == second.hand(player));
    }
    require(first.discard(0, first.legal_discards().front()).accepted);
    first.reset(7);
    require(first.hand(0) == second.hand(0));
    require(first.discards(0).empty());
    require(first.wall_remaining() == 55);
}

void test_closed_hand_shapes() {
    LanzhouMahjongRound::Hand one_suit{};
    one_suit[0] = 3;
    one_suit[1] = 3;
    one_suit[2] = 3;
    one_suit[3] = 3;
    one_suit[4] = 2;
    require(LanzhouMahjongRound::has_missing_suit(one_suit));
    require(LanzhouMahjongRound::is_closed_four_meld_pair(one_suit));

    LanzhouMahjongRound::Hand two_suits{};
    for (std::uint8_t tile = 0; tile < 6; ++tile) {
        two_suits[tile] = 1;
        two_suits[tile + 9] = 1;
    }
    two_suits[15] = 2;
    require(LanzhouMahjongRound::has_missing_suit(two_suits));
    require(LanzhouMahjongRound::is_closed_four_meld_pair(two_suits));

    LanzhouMahjongRound::Hand three_suits{};
    for (std::uint8_t tile = 0; tile < 6; ++tile) {
        three_suits[tile] = 1;
    }
    for (std::uint8_t tile = 9; tile < 12; ++tile) {
        three_suits[tile] = 1;
        three_suits[tile + 9] = 1;
    }
    three_suits[22] = 2;
    require(!LanzhouMahjongRound::has_missing_suit(three_suits));
    require(LanzhouMahjongRound::is_closed_four_meld_pair(three_suits));

    LanzhouMahjongRound::Hand crossing_suits{};
    crossing_suits[0] = 3;
    crossing_suits[1] = 3;
    crossing_suits[2] = 3;
    crossing_suits[4] = 2;
    crossing_suits[7] = 1;
    crossing_suits[8] = 1;
    crossing_suits[9] = 1;
    require(LanzhouMahjongRound::has_missing_suit(crossing_suits));
    require(!LanzhouMahjongRound::is_closed_four_meld_pair(crossing_suits));

    auto thirteen_tiles = one_suit;
    --thirteen_tiles[4];
    require(!LanzhouMahjongRound::is_closed_four_meld_pair(thirteen_tiles));
    const auto waits = LanzhouMahjongRound::closed_four_meld_pair_waits(thirteen_tiles);
    bool waits_for_five_wan = false;
    for (const auto tile : waits) {
        auto completed = thirteen_tiles;
        ++completed[tile];
        require(LanzhouMahjongRound::has_missing_suit(completed));
        require(LanzhouMahjongRound::is_closed_four_meld_pair(completed));
        waits_for_five_wan |= tile == 4;
    }
    require(waits_for_five_wan);
    require(LanzhouMahjongRound::closed_four_meld_pair_waits(one_suit).empty());

    auto three_suit_thirteen = three_suits;
    --three_suit_thirteen[22];
    require(LanzhouMahjongRound::closed_four_meld_pair_waits(
        three_suit_thirteen
    ).empty());

    LanzhouMahjongRound::Hand seven_pairs{};
    for (const auto tile : {0, 2, 4, 6, 8, 9, 11}) {
        seven_pairs[tile] = 2;
    }
    require(!LanzhouMahjongRound::is_closed_four_meld_pair(seven_pairs));
    require(LanzhouMahjongRound::is_closed_seven_pairs(seven_pairs));

    auto impossible_copies = one_suit;
    impossible_copies[0] = 5;
    impossible_copies[1] = 1;
    require(!LanzhouMahjongRound::is_closed_four_meld_pair(impossible_copies));
    --impossible_copies[4];
    require(LanzhouMahjongRound::closed_four_meld_pair_waits(
        impossible_copies
    ).empty());
}

void test_self_draw_candidate_and_wall_validation() {
    std::array<std::uint8_t, LanzhouMahjongRound::tile_count> wall{};
    std::array<std::uint8_t, LanzhouMahjongRound::tile_kind_count> remaining{};
    remaining.fill(4);
    const std::array<std::uint8_t, 14> dealer_tiles{
        0, 0, 0, 1, 1, 1, 2, 2, 2, 3, 3, 3, 4, 4
    };
    for (std::size_t turn = 0; turn < 13; ++turn) {
        const auto tile = dealer_tiles[turn];
        wall[turn * 4] = tile;
        --remaining[tile];
    }
    wall[52] = dealer_tiles[13];
    --remaining[dealer_tiles[13]];

    std::size_t next_kind = 0;
    for (std::size_t position = 0; position < wall.size(); ++position) {
        if ((position < 52 && position % 4 == 0) || position == 52) {
            continue;
        }
        while (remaining[next_kind] == 0) {
            ++next_kind;
        }
        wall[position] = static_cast<std::uint8_t>(next_kind);
        --remaining[next_kind];
    }

    LanzhouMahjongRound round(17);
    require(round.reset_with_wall(wall));
    require(round.can_self_draw_four_meld_pair());
    require(round.wall_remaining() == 55);

    const auto original_hand = round.hand(0);
    auto invalid_wall = wall;
    invalid_wall[0] = 27;
    require(!round.reset_with_wall(invalid_wall));
    require(round.hand(0) == original_hand);
    require(round.can_self_draw_four_meld_pair());
    invalid_wall = wall;
    invalid_wall[0] = 26;
    require(!round.reset_with_wall(invalid_wall));
    require(round.hand(0) == original_hand);

    require(round.discard(0, round.legal_discards().front()).accepted);
    require(!round.can_self_draw_four_meld_pair());
}

}  // namespace

int main() {
    test_initial_deal_and_turns();
    test_tile_conservation_and_exhaustion();
    test_seed_and_reset();
    test_closed_hand_shapes();
    test_self_draw_candidate_and_wall_validation();
    return 0;
}
