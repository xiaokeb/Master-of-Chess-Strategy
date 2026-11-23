#include "competition_mahjong_auto_session.hpp"

#include <algorithm>
#include <array>
#include <charconv>
#include <chrono>
#include <cstdint>
#include <iostream>
#include <stdexcept>
#include <string>
#include <string_view>

namespace {
using mocs::engine::CompetitionMahjongAutoSession;
using mocs::engine::CompetitionMahjongBotPolicy;
using mocs::engine::CompetitionMahjongFanRules;
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

void run_series(int lower, std::uint64_t seed, int rotation, CompetitionMahjongBotPolicy policy,
                CompetitionMahjongFanRules scoring) {
    const auto start = std::chrono::steady_clock::now();
    const bool wmo = scoring != CompetitionMahjongFanRules::legacy_v1;
    const auto decision_seed = wmo ? seed : seed ^ 0x431c9dfe29753c01ULL;
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
            seed_at(decision_seed, hand), wmo, scoring);
        if (wmo) {
            const auto record = game.serialize();
            const auto version = static_cast<std::uint8_t>(scoring);
            if (record[4] != version + 1 || record[28] != version)
                throw std::runtime_error("Automatic or round scoring version differs from the cohort");
        }
        bool finished = false;
        for (int events = 0; events < 4096;) {
            const auto budget = static_cast<std::uint32_t>(std::min(32, 4096 - events));
            const auto advanced = wmo ? game.advance(budget) : game.advance(budget, policy);
            if (advanced.accepted_actions == 0 || advanced.accepted_actions > budget)
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
    if (argc != 2 && argc != 5) return 2;
    const std::string_view profile = argv[1];
    const bool kong_revision = profile == "held-out-wmo-kongs-v1";
    const bool wmo = profile == "held-out-wmo-calibrated-v1" || kong_revision;
    const auto scoring = kong_revision ? CompetitionMahjongFanRules::wmo_2021_kongs :
        wmo ? CompetitionMahjongFanRules::wmo_2021 : CompetitionMahjongFanRules::legacy_v1;
    auto policy = CompetitionMahjongBotPolicy::baseline;
    if (profile == "diagnostic-v2") policy = CompetitionMahjongBotPolicy::diagnostic_v2;
    else if (profile == "diagnostic-v3") policy = CompetitionMahjongBotPolicy::diagnostic_v3;
    else if (profile == "diagnostic-v4") policy = CompetitionMahjongBotPolicy::diagnostic_v4;
    else if (profile == "held-out-v5" || profile == "training-medium-control") policy = CompetitionMahjongBotPolicy::candidate_v5;
    else if (profile == "training-medium-bucket-8" || profile == "held-out-medium-bucket-8")
        policy = CompetitionMahjongBotPolicy::medium_live_bucket_8;
    else if (profile == "training-medium-bucket-4") policy = CompetitionMahjongBotPolicy::medium_live_bucket_4;
    else if (profile != "diagnostic-v1" && !wmo) return 2;
    const bool held_out = profile == "held-out-v5" || profile == "held-out-medium-bucket-8" || wmo;
    const bool medium_training = profile == "training-medium-control" ||
        profile == "training-medium-bucket-8" || profile == "training-medium-bucket-4";
    std::uint64_t first = 71425001ULL;
    if (profile == "held-out-v5") first = 4285151001ULL;
    if (medium_training) first = 71551001ULL;
    if (profile == "held-out-medium-bucket-8") first = 5920152401ULL;
    if (wmo) first = kong_revision ? 9261122001ULL : 9226102001ULL;
    const int seed_count = held_out ? 24 : 8;
    int selected_lower = -1;
    int selected_rotation = -1;
    std::uint64_t selected_seed = 0;
    if (argc == 5) {
        const auto parse = [](std::string_view input, auto& value) {
            const auto [end, error] = std::from_chars(input.data(), input.data() + input.size(), value);
            return error == std::errc{} && end == input.data() + input.size();
        };
        if (!parse(argv[2], selected_lower) || selected_lower < 0 || selected_lower > 2 ||
            !parse(argv[3], selected_seed) || selected_seed < first || selected_seed >= first + seed_count ||
            !parse(argv[4], selected_rotation) || selected_rotation < 0 || selected_rotation > 3) return 2;
    }
    std::cout << "lower\tseed\trotation\tp0\tp1\tp2\tp3\tdraws\tevents\telapsed_ms" << std::endl;
    for (int lower = 0; lower < 3; ++lower) {
        for (std::uint64_t seed = first; seed < first + seed_count; ++seed) {
            for (int rotation = 0; rotation < 4; ++rotation) {
                if (argc == 5 && (lower != selected_lower || seed != selected_seed || rotation != selected_rotation)) continue;
                try {
                    run_series(lower, seed, rotation, policy, scoring);
                } catch (const std::exception& failure) {
                    std::cerr << lower << '/' << seed << '/' << rotation << ": " << failure.what() << '\n';
                    return 1;
                }
            }
        }
    }
}
