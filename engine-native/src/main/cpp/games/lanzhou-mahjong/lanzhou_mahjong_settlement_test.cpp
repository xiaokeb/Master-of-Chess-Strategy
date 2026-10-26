#include "mocs/engine/lanzhou_mahjong_settlement.hpp"

#include <cstdint>
#include <cstdlib>
#include <limits>

using namespace mocs::engine;

namespace {
void require(bool value) {
    if (!value) {
        std::abort();
    }
}

void verify_zero_sum(const LanzhouMahjongPayment& payment) {
    std::int64_t total = 0;
    for (const auto amount : payment) {
        total += amount;
    }
    require(total == 0);
}
}  // namespace

int main() {
    const auto exposed = lanzhou_kong_payment(1, false, 2);
    require(exposed.has_value());
    require(*exposed == LanzhouMahjongPayment{-2, 6, -2, -2});
    verify_zero_sum(*exposed);

    const auto concealed = lanzhou_kong_payment(1, true, 2);
    require(concealed.has_value());
    require(*concealed == LanzhouMahjongPayment{-4, 12, -4, -4});
    verify_zero_sum(*concealed);

    const auto self_draw = lanzhou_win_payment(2, std::nullopt, 3, 2);
    require(self_draw.has_value());
    require(*self_draw == LanzhouMahjongPayment{-24, -24, 72, -24});
    verify_zero_sum(*self_draw);

    const auto discard_win = lanzhou_win_payment(2, 0, 3, 1);
    require(discard_win.has_value());
    require(*discard_win == LanzhouMahjongPayment{-18, 0, 18, 0});
    verify_zero_sum(*discard_win);

    const auto false_win = lanzhou_false_win_payment(3, 1, 3);
    require(false_win.has_value());
    require(*false_win == LanzhouMahjongPayment{8, 8, 8, -24});
    verify_zero_sum(*false_win);

    for (std::uint8_t seat = 0; seat < 4; ++seat) {
        const auto penalty = lanzhou_project_false_win_payment(seat, 2);
        require(penalty.has_value());
        for (std::uint8_t other = 0; other < 4; ++other) {
            require((*penalty)[other] == (other == seat ? -144 : 48));
        }
        verify_zero_sum(*penalty);
    }
    const auto maximum = std::numeric_limits<std::int64_t>::max() / 72;
    require(lanzhou_project_false_win_payment(0, maximum).has_value());
    require(!lanzhou_project_false_win_payment(0, maximum + 1));
    require(!lanzhou_project_false_win_payment(4, 1));
    require(!lanzhou_project_false_win_payment(0, 0));
    require(!lanzhou_project_false_win_payment(0, -1));
    auto with_kong = *concealed;
    require(lanzhou_add_payment(with_kong, *lanzhou_project_false_win_payment(1, 2)));
    require(with_kong == LanzhouMahjongPayment{44, -132, 44, 44});

    LanzhouMahjongPayment running{1, -1, 0, 0};
    require(lanzhou_add_payment(running, *discard_win));
    require(running == LanzhouMahjongPayment{-17, -1, 18, 0});
    running[0] = std::numeric_limits<std::int64_t>::max();
    const auto unchanged = running;
    require(!lanzhou_add_payment(running, *false_win));
    require(running == unchanged);

    require(!lanzhou_kong_payment(4, false, 1));
    require(!lanzhou_win_payment(1, 1, 1, 0));
    require(!lanzhou_false_win_payment(0, 0, 3));
    require(!lanzhou_win_payment(0, std::nullopt, 1, 31));
    return 0;
}
