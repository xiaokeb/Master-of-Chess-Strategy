#include "mocs/engine/competition_mahjong_settlement.hpp"

namespace mocs::engine {

CompetitionMahjongSettlement competition_mahjong_score_win(
    const std::uint8_t winner,
    const std::optional<std::uint8_t> discarder,
    const std::uint16_t fan_without_flowers,
    const std::uint8_t flower_count
) noexcept {
    CompetitionMahjongSettlement result{};
    if (winner >= 4 || (discarder && (*discarder >= 4 || *discarder == winner)) ||
        fan_without_flowers < 8 || flower_count > 8) {
        return result;
    }
    const auto basic = static_cast<std::int32_t>(fan_without_flowers + flower_count);
    for (std::uint8_t player = 0; player < 4; ++player) {
        if (player == winner) continue;
        const auto payment = static_cast<std::int32_t>(8 +
            (discarder ? (player == *discarder ? basic : 0) : basic));
        result.delta[player] = -payment;
        result.delta[winner] += payment;
    }
    result.valid = true;
    return result;
}

CompetitionMahjongSettlement competition_mahjong_score_false_hu(
    const std::uint8_t offender,
    const CompetitionMahjongFalseHuKind kind
) noexcept {
    CompetitionMahjongSettlement result{};
    if (offender >= 4 || (kind != CompetitionMahjongFalseHuKind::below_minimum &&
                          kind != CompetitionMahjongFalseHuKind::not_waiting)) {
        return result;
    }
    const std::int32_t penalty = kind == CompetitionMahjongFalseHuKind::below_minimum ? 10 : 20;
    for (std::uint8_t player = 0; player < 4; ++player) {
        if (player == offender) continue;
        result.delta[player] = penalty;
        result.delta[offender] -= penalty;
    }
    result.valid = true;
    return result;
}

}  // namespace mocs::engine
