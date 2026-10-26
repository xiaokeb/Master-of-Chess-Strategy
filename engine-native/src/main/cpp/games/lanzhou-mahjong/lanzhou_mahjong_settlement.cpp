#include "mocs/engine/lanzhou_mahjong_settlement.hpp"

#include <limits>

namespace mocs::engine {
namespace {

std::optional<std::int64_t> multiplied_unit(
    std::int64_t base, std::uint8_t multiplier_log2
) noexcept {
    if (base <= 0 || multiplier_log2 > 30 ||
        base > std::numeric_limits<std::int64_t>::max() /
                   (std::int64_t{3} << multiplier_log2)) {
        return std::nullopt;
    }
    return base * (std::int64_t{1} << multiplier_log2);
}

std::optional<LanzhouMahjongPayment> three_pay_one(
    std::uint8_t winner, std::int64_t each
) noexcept {
    if (winner >= 4 || each <= 0 ||
        each > std::numeric_limits<std::int64_t>::max() / 3) {
        return std::nullopt;
    }
    LanzhouMahjongPayment result{};
    for (std::uint8_t player = 0; player < 4; ++player) {
        if (player != winner) {
            result[player] = -each;
        }
    }
    result[winner] = each * 3;
    return result;
}

}  // namespace

std::optional<LanzhouMahjongPayment> lanzhou_kong_payment(
    std::uint8_t player, bool concealed, std::int64_t base
) noexcept {
    if (base <= 0 || base > std::numeric_limits<std::int64_t>::max() / 6) {
        return std::nullopt;
    }
    return three_pay_one(player, concealed ? base * 2 : base);
}

std::optional<LanzhouMahjongPayment> lanzhou_win_payment(
    std::uint8_t winner, std::optional<std::uint8_t> payer,
    std::int64_t base, std::uint8_t bonus_fan
) noexcept {
    if (winner >= 4 || (payer && (*payer >= 4 || *payer == winner))) {
        return std::nullopt;
    }
    if (bonus_fan >= 30) {
        return std::nullopt;
    }
    const auto unit = multiplied_unit(base, static_cast<std::uint8_t>(bonus_fan + 1));
    if (!unit) {
        return std::nullopt;
    }
    if (!payer) {
        return three_pay_one(winner, *unit);
    }
    if (*unit > std::numeric_limits<std::int64_t>::max() / 3) {
        return std::nullopt;
    }
    LanzhouMahjongPayment result{};
    result[winner] = *unit * 3 / 2;
    result[*payer] = -result[winner];
    return result;
}

std::optional<LanzhouMahjongPayment> lanzhou_false_win_payment(
    std::uint8_t declarer, std::int64_t base, std::uint8_t max_fan
) noexcept {
    if (declarer >= 4) {
        return std::nullopt;
    }
    const auto unit = multiplied_unit(base, max_fan);
    if (!unit) {
        return std::nullopt;
    }
    auto result = three_pay_one(declarer, *unit);
    if (!result) {
        return std::nullopt;
    }
    for (auto& amount : *result) {
        amount = -amount;
    }
    return result;
}

std::optional<LanzhouMahjongPayment> lanzhou_project_false_win_payment(
    std::uint8_t declarer, std::int64_t base
) noexcept {
    if (base <= 0 || base > std::numeric_limits<std::int64_t>::max() / 72) {
        return std::nullopt;
    }
    auto result = three_pay_one(declarer, base * 24);
    if (result) {
        for (auto& amount : *result) amount = -amount;
    }
    return result;
}

bool lanzhou_add_payment(
    LanzhouMahjongPayment& total, const LanzhouMahjongPayment& delta
) noexcept {
    for (std::size_t player = 0; player < total.size(); ++player) {
        if ((delta[player] > 0 &&
             total[player] > std::numeric_limits<std::int64_t>::max() - delta[player]) ||
            (delta[player] < 0 &&
             total[player] < std::numeric_limits<std::int64_t>::min() - delta[player])) {
            return false;
        }
    }
    for (std::size_t player = 0; player < total.size(); ++player) {
        total[player] += delta[player];
    }
    return true;
}

}  // namespace mocs::engine
