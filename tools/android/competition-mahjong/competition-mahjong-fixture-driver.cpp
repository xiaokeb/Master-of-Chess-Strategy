#include "competition_mahjong_bot.hpp"
#include "competition_mahjong_round.hpp"

#include <algorithm>
#include <array>
#include <cstdint>
#include <fstream>
#include <future>
#include <iomanip>
#include <iostream>
#include <stdexcept>
#include <string>
#include <vector>

using namespace mocs::engine;

namespace {
constexpr std::uint64_t positive = 0x7fffffffffffffffULL;
constexpr std::array<std::array<int, 4>, 4> players{{
    {{0, 1, 2, 3}}, {{1, 0, 3, 2}}, {{2, 3, 1, 0}}, {{3, 2, 0, 1}}
}};

std::uint64_t hand_seed(std::uint64_t seed, int hand) {
    if (hand == 0) return seed;
    auto value = seed + hand * 0x9e3779b97f4a7c15ULL;
    value = (value ^ (value >> 30)) * 0xbf58476d1ce4e5b9ULL;
    value = (value ^ (value >> 27)) * 0x94d049bb133111ebULL;
    return (value ^ (value >> 31)) & positive;
}

// Mirrors POSITION_SEEDED_V1. The Android replay gate independently checks the resulting journals.
std::uint64_t public_seed(const CompetitionMahjongRoundView& view) {
    std::uint64_t hash = 1125899906842597ULL;
    const auto add = [&](int value) { hash = hash * 31 + static_cast<std::uint64_t>(value); };
    add(static_cast<int>(view.phase));
    add(view.dealer);
    add(view.round_wind);
    add(view.current_player);
    add(static_cast<int>(view.wall_remaining));
    add(view.pending_tile ? *view.pending_tile : -1);
    add(view.pending_player ? *view.pending_player : -1);
    for (const auto score : view.score_delta) add(score);
    for (int seat = 0; seat < 4; ++seat) {
        add(seat);
        add(static_cast<int>(view.concealed[seat].size()));
        add(view.win_disabled[seat]);
        add(static_cast<int>(view.flowers[seat].size()));
        for (const auto tile : view.flowers[seat]) add(tile);
        add(static_cast<int>(view.discards[seat].size()));
        for (const auto tile : view.discards[seat]) add(tile);
        add(static_cast<int>(view.melds[seat].size()));
        for (const auto& meld : view.melds[seat]) {
            add(static_cast<int>(meld.kind));
            add(meld.from_player == seat ? -1 : meld.tile);
            add(meld.from_player);
            add(meld.claimed_position);
            add(meld.added_kong);
        }
    }
    return hash & positive;
}

bool terminal(CompetitionMahjongRoundPhase phase) {
    return phase == CompetitionMahjongRoundPhase::won || phase == CompetitionMahjongRoundPhase::exhaustive_draw;
}

struct Series {
    std::array<std::int64_t, 4> score{};
    std::array<std::vector<std::uint16_t>, 16> decisions;
};

Series run_series(std::uint64_t seed, int level) {
    Series result;
    for (int hand = 0; hand < 16; ++hand) {
        const auto& players_at_seats = players[hand / 4];
        const int viewer = static_cast<int>(std::find(players_at_seats.begin(), players_at_seats.end(), 0) -
            players_at_seats.begin());
        CompetitionMahjongRound round(competition_mahjong_shuffled_wall(hand_seed(seed, hand)), hand % 4, hand / 4);
        for (int step = 0; step < 4096; ++step) {
            const auto view = round.view();
            if (terminal(view.phase)) break;
            const auto random = public_seed(view);
            int seat = viewer;
            const bool local = !round.legal_actions(viewer).empty();
            if (!local) {
                for (seat = 0; seat < 4; ++seat) {
                    if (seat != viewer && !round.legal_actions(seat).empty()) break;
                }
                if (seat == 4) throw std::runtime_error("Stalled round");
            }
            const auto choice = competition_mahjong_choose_bot_action(round, seat, local ? 3 : level, random);
            if (!choice || !round.apply(*choice).accepted) throw std::runtime_error("Rejected action");
            if (local) {
                if (choice->tile > 33 || choice->sequence_start > 33) throw std::runtime_error("Invalid local action");
                result.decisions[hand].push_back(static_cast<std::uint16_t>(static_cast<int>(choice->kind) |
                    (choice->tile << 4) | (choice->sequence_start << 10)));
            }
        }
        const auto finished = round.view();
        if (!terminal(finished.phase)) throw std::runtime_error("Round action budget exceeded");
        for (int seat = 0; seat < 4; ++seat) result.score[players_at_seats[seat]] += finished.score_delta[seat];
    }
    return result;
}

void generate(const std::string& directory, int level, int wanted) {
    std::ofstream output(directory + "/level-" + std::to_string(level) + ".tsv");
    if (!output) throw std::runtime_error("Could not open output");
    int wins = 0;
    for (int trial = 1; trial <= 400; ++trial) {
        const auto seed = (level + 1) * 1000000ULL + trial;
        const auto result = run_series(seed, level);
        if (trial % 10 == 0) std::cout << "level " << level << " trial " << trial << " wins " << wins << std::endl;
        if (result.score[0] <= *std::max_element(result.score.begin() + 1, result.score.end())) continue;
        output << std::dec << seed;
        for (auto score : result.score) output << '\t' << score;
        for (const auto& hand : result.decisions) {
            output << '\t';
            for (auto action : hand) output << std::hex << std::setw(4) << std::setfill('0') << action;
        }
        output << std::endl;
        if (++wins == wanted) return;
    }
    throw std::runtime_error("Not enough winning seeds for level " + std::to_string(level));
}
}

int main(int argc, char** argv) {
    if (argc != 2) return 2;
    try {
        std::array<std::future<void>, 3> results;
        constexpr std::array counts{10, 15, 20};
        for (int level = 0; level < 3; ++level) {
            results[level] = std::async(std::launch::async, generate, argv[1], level, counts[level]);
        }
        for (auto& result : results) result.get();
    } catch (const std::exception& failure) {
        std::cerr << failure.what() << '\n';
        return 1;
    }
}
