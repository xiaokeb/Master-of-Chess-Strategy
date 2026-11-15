#pragma once

#include "competition_mahjong_public_view.hpp"
#include "competition_mahjong_bot.hpp"

#include <array>
#include <cstdint>
#include <mutex>
#include <vector>

namespace mocs::engine {

struct CompetitionMahjongAutoAdvance {
    std::uint32_t accepted_actions{};
    bool terminal{};
};

// Four AI seats, one immutable observer. Scheduling and continuation belong to
// the application; this session never starts another hand or exposes AI actions.
class CompetitionMahjongAutoSession final {
public:
    CompetitionMahjongAutoSession(std::uint64_t wall_seed, std::uint8_t dealer,
        std::uint8_t round_wind, std::uint8_t viewer,
        std::array<std::uint8_t, 4> levels, std::uint64_t decision_seed, bool calibrated = false);
    CompetitionMahjongAutoSession(std::vector<CompetitionMahjongTile> wall,
        std::uint8_t dealer, std::uint8_t round_wind, std::uint8_t viewer,
        std::array<std::uint8_t, 4> levels, std::uint64_t decision_seed, bool calibrated = false);

    [[nodiscard]] std::uint8_t viewer_seat() const noexcept { return viewer_; }
    [[nodiscard]] CompetitionMahjongPublicView snapshot() const;
    // 1..32 accepted events. A failed batch preserves the previous round.
    [[nodiscard]] CompetitionMahjongAutoAdvance advance(std::uint32_t max_actions);
    // Benchmark entry for comparing candidates without committing to a saved policy.
    [[nodiscard]] CompetitionMahjongAutoAdvance advance(std::uint32_t max_actions,
        CompetitionMahjongBotPolicy policy);
    [[nodiscard]] bool undo();
    // Private MCMA v1 selects baseline; MCMA v2 selects calibrated bots.
    [[nodiscard]] std::vector<std::uint8_t> serialize() const;
    // Validated candidate replay; configuration must equal this session's.
    [[nodiscard]] bool restore(const std::vector<std::uint8_t>& bytes);

private:
    [[nodiscard]] std::optional<CompetitionMahjongRoundAction> choose_action(
        const CompetitionMahjongRound& round, CompetitionMahjongBotPolicy policy) const;

    const std::uint8_t viewer_;
    const std::array<std::uint8_t, 4> levels_;
    const std::uint64_t decision_seed_;
    const bool calibrated_;
    CompetitionMahjongRound round_;
    mutable std::mutex mutex_;
};

} // namespace mocs::engine
