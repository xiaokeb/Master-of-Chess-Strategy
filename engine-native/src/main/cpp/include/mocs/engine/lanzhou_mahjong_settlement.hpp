#pragma once

#include <array>
#include <cstdint>
#include <optional>

namespace mocs::engine {

using LanzhouMahjongPayment = std::array<std::int64_t, 4>;

// All payments are zero-sum. Invalid seats, base amounts or overflow return nullopt.
[[nodiscard]] std::optional<LanzhouMahjongPayment> lanzhou_kong_payment(
    std::uint8_t player, bool concealed, std::int64_t base
) noexcept;
[[nodiscard]] std::optional<LanzhouMahjongPayment> lanzhou_win_payment(
    std::uint8_t winner, std::optional<std::uint8_t> payer,
    std::int64_t base, std::uint8_t bonus_fan
) noexcept;
[[nodiscard]] std::optional<LanzhouMahjongPayment> lanzhou_false_win_payment(
    std::uint8_t declarer, std::int64_t base, std::uint8_t max_fan
) noexcept;
[[nodiscard]] bool lanzhou_add_payment(
    LanzhouMahjongPayment& total, const LanzhouMahjongPayment& delta
) noexcept;

// Project table rule: each of the other three seats receives exactly base * 24.
[[nodiscard]] std::optional<LanzhouMahjongPayment> lanzhou_project_false_win_payment(
    std::uint8_t declarer, std::int64_t base
) noexcept;

}  // namespace mocs::engine
