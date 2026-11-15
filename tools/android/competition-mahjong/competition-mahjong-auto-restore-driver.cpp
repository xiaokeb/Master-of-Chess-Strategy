#include "competition_mahjong_auto_session.hpp"

#include <array>
#include <chrono>
#include <cstdint>
#include <iostream>
#include <stdexcept>
#include <utility>
#include <vector>

namespace {
using mocs::engine::CompetitionMahjongAutoSession;
using Record = std::vector<std::uint8_t>;

struct Sample {
    std::uint64_t seed;
    std::uint8_t dealer;
    std::uint8_t wind;
    std::uint8_t viewer;
    std::array<std::uint8_t, 4> levels;
    bool calibrated;
    Record terminal;

    CompetitionMahjongAutoSession create() const {
        return {seed, dealer, wind, viewer, levels, seed + 31, calibrated};
    }
};

void print_bytes(const Record& record) {
    constexpr char hex[] = "0123456789abcdef";
    for (const auto byte : record) std::cout << hex[byte >> 4] << hex[byte & 0xf];
}
} // namespace

int main() {
    try {
        std::vector<Sample> samples;
        for (const bool calibrated : {false, true}) {
            for (std::uint8_t hand = 0; hand < 16; ++hand) {
                const auto dealer = static_cast<std::uint8_t>(hand % 4);
                const auto wind = static_cast<std::uint8_t>(hand / 4);
                const auto viewer = static_cast<std::uint8_t>((hand + wind) % 4);
                std::array<std::uint8_t, 4> levels{};
                for (std::size_t seat = 0; seat < levels.size(); ++seat)
                    levels[seat] = static_cast<std::uint8_t>((seat + wind + hand) % 4);
                Sample sample{51201ULL + hand, dealer, wind, viewer, levels, calibrated, {}};
                auto session = sample.create();
                for (int actions = 0; actions < 4096;) {
                    const auto step = session.advance(32);
                    if (step.accepted_actions == 0) throw std::runtime_error("no automatic progress");
                    actions += static_cast<int>(step.accepted_actions);
                    if (step.terminal) {
                        sample.terminal = session.serialize();
                        break;
                    }
                }
                if (sample.terminal.empty()) throw std::runtime_error("missing terminal record");
                std::cout << calibrated << '\t' << static_cast<unsigned>(hand) << '\t';
                print_bytes(sample.terminal);
                std::cout << '\n';
                samples.push_back(std::move(sample));
            }
        }
        for (int repeat = 0; repeat < 3; ++repeat) {
            const auto start = std::chrono::steady_clock::now();
            for (const auto& sample : samples) {
                auto session = sample.create();
                if (!session.restore(sample.terminal) || session.serialize() != sample.terminal)
                    throw std::runtime_error("automatic restore changed the journal");
            }
            const auto elapsed = std::chrono::duration_cast<std::chrono::microseconds>(
                std::chrono::steady_clock::now() - start).count();
            std::cerr << "restore_us=" << elapsed << '\n';
        }
        return 0;
    } catch (const std::exception& failure) {
        std::cerr << failure.what() << '\n';
        return 1;
    }
}
