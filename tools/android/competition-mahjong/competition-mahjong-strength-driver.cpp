#include "competition_mahjong_auto_session.hpp"

#include <algorithm>
#include <array>
#include <chrono>
#include <cstdint>
#include <iostream>
#include <stdexcept>
#include <string>
#include <string_view>

namespace {
using mocs::engine::CompetitionMahjongAutoSession;
using mocs::engine::CompetitionMahjongRoundPhase;

using Levels = std::array<std::uint8_t, 4>;
constexpr std::array<std::array<int, 4>, 4> players_at_seats{{
    {{0, 1, 2, 3}}, {{1, 0, 3, 2}}, {{2, 3, 1, 0}}, {{3, 2, 0, 1}},
}};

// Same hand seed derivation and fixed seating as the production automatic series.
std::uint64_t seed_at(std::uint64_t seed, int index) {
    if (index == 0) return seed;
    auto value = seed + index * 0x9e3779b97f4a7c15ULL;
    value = (value ^ (value >> 30)) * 0xbf58476d1ce4e5b9ULL;
    value = (value ^ (value >> 27)) * 0x94d049bb133111ebULL;
    return (value ^ (value >> 31)) & 0x7fffffffffffffffULL;
}

void run_series(int lower, std::uint64_t seed, int rotation) {
    const auto start = std::chrono::steady_clock::now();
    std::array<std::int64_t, 4> points{};
    int total_events = 0;
    int draws = 0;
    for (int hand = 0; hand < 16; ++hand) {
        const auto& seating = players_at_seats[hand / 4];
        Levels levels{};
        for (int seat = 0; seat < 4; ++seat) {
            const int player = seating[seat];
            const bool higher = (player - rotation + 4) % 4 < 2;
            levels[seat] = static_cast<std::uint8_t>(lower + higher);
        }
        const auto viewer = static_cast<std::uint8_t>(std::find(seating.begin(), seating.end(), 0) - seating.begin());
        CompetitionMahjongAutoSession game(seed_at(seed, hand), hand % 4, hand / 4, viewer, levels,
            seed_at(seed ^ 0x431c9dfe29753c01ULL, hand));
        bool finished = false;
        for (int events = 0; events < 4096;) {
            const auto advanced = game.advance(32);
            if (advanced.accepted_actions == 0 || advanced.accepted_actions > 32)
                throw std::runtime_error("Invalid progress at hand " + std::to_string(hand));
            events += advanced.accepted_actions;
            if (advanced.terminal) {
                total_events += events;
                finished = true;
                break;
            }
        }
        if (!finished) throw std::runtime_error("Event budget exceeded at hand " + std::to_string(hand));
        const auto result = game.snapshot();
        if (result.phase == CompetitionMahjongRoundPhase::exhaustive_draw) ++draws;
        else if (result.phase != CompetitionMahjongRoundPhase::won)
            throw std::runtime_error("Missing terminal result");
        for (int seat = 0; seat < 4; ++seat) points[seating[seat]] += result.score_delta[seat];
    }
    const auto elapsed = std::chrono::duration_cast<std::chrono::milliseconds>(
        std::chrono::steady_clock::now() - start).count();
    std::cout << lower << '\t' << seed << '\t' << rotation;
    for (const auto score : points) std::cout << '\t' << score;
    std::cout << '\t' << draws << '\t' << total_events << '\t' << elapsed << std::endl;
}
} // namespace

int main(int argc, char** argv) {
    // The diagnostic and production APK use the same four strategies and native automatic session.
    if (argc != 2 || std::string_view(argv[1]) != "diagnostic-v1") return 2;
    std::cout << "lower\tseed\trotation\tp0\tp1\tp2\tp3\tdraws\tevents\telapsed_ms" << std::endl;
    for (int lower = 0; lower < 3; ++lower) {
        for (std::uint64_t seed = 71425001; seed <= 71425008; ++seed) {
            for (int rotation = 0; rotation < 4; ++rotation) {
                try {
                    run_series(lower, seed, rotation);
                } catch (const std::exception& failure) {
                    std::cerr << lower << '/' << seed << '/' << rotation << ": " << failure.what() << '\n';
                    return 1;
                }
            }
        }
    }
}
