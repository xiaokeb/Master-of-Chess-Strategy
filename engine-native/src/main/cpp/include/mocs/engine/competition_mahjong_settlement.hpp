#pragma once

#include <array>
#include <cstdint>
#include <optional>

namespace mocs::engine {

enum class CompetitionMahjongFalseHuKind : std::uint8_t {
    below_minimum = 0,
    not_waiting = 1,
};

struct CompetitionMahjongSettlement {
    bool valid{};
    std::array<std::int32_t, 4> delta{};
};

// The hand evaluator must supply fan_without_flowers after exclusion rules.
// A discarded win has discarder set; a self-draw has no discarder.
[[nodiscard]] CompetitionMahjongSettlement competition_mahjong_score_win(
    std::uint8_t winner,
    std::optional<std::uint8_t> discarder,
    std::uint16_t fan_without_flowers,
    std::uint8_t flower_count
) noexcept;

[[nodiscard]] CompetitionMahjongSettlement competition_mahjong_score_false_hu(
    std::uint8_t offender,
    CompetitionMahjongFalseHuKind kind
) noexcept;

}  // namespace mocs::engine
