#pragma once

#include "mocs/engine/lanzhou_mahjong.hpp"
#include "mocs/engine/lanzhou_mahjong_settlement.hpp"

#include <array>
#include <cstdint>
#include <optional>
#include <vector>

namespace mocs::engine {

// Also the LZMJ envelope version: replay must never change historical penalties.
enum class LanzhouMahjongRule : std::uint8_t {
    legacy_false_win_8 = 1, project_false_win_24 = 2,
};

enum class LanzhouMahjongPhase : std::uint8_t {
    discard = 0, draw = 1, claim = 2, rob_kong = 3, won = 4, exhaustive_draw = 5,
};
enum class LanzhouMahjongMeldKind : std::uint8_t {
    pung = 0, exposed_kong = 1, concealed_kong = 2,
};
struct LanzhouMahjongMeld {
    LanzhouMahjongMeldKind kind{};
    std::uint8_t tile{};
    std::uint8_t from_player{};
};
enum class LanzhouMahjongActionKind : std::uint8_t {
    draw = 0, discard = 1, ready_discard = 2, pass = 3, pung = 4,
    exposed_kong = 5, concealed_kong = 6, added_kong = 7, win = 8,
};
struct LanzhouMahjongAction {
    LanzhouMahjongActionKind kind{};
    std::uint8_t player{};
    std::uint8_t tile{255};
};
struct LanzhouMahjongOutcome {
    std::optional<std::uint8_t> winner;
    std::optional<std::uint8_t> payer;
    std::uint8_t next_dealer{};
    bool false_win{};
    bool seven_pairs{};
    bool ready{};
    bool kong_draw{};
    bool last_tile{};
};

// One local four-seat round. It exposes its own result, never the two-seat GameResult.
class LanzhouMahjongGame final {
public:
    using Hand = LanzhouMahjongRound::Hand;
    using Wall = std::array<std::uint8_t, 108>;
    explicit LanzhouMahjongGame(Wall wall, std::uint8_t dealer = 0, std::int64_t base = 1);
    [[nodiscard]] static Wall shuffled_wall(std::uint64_t seed);
    [[nodiscard]] static bool valid_wall(const Wall& wall) noexcept;

    [[nodiscard]] LanzhouMahjongPhase phase() const noexcept;
    [[nodiscard]] std::uint8_t current_player() const noexcept;
    [[nodiscard]] std::uint8_t dealer() const noexcept;
    [[nodiscard]] LanzhouMahjongRule rule() const noexcept { return rule_; }
    [[nodiscard]] std::size_t wall_remaining() const noexcept;
    [[nodiscard]] const Hand& hand(std::uint8_t player) const;
    [[nodiscard]] const std::vector<LanzhouMahjongMeld>& melds(std::uint8_t player) const;
    [[nodiscard]] const std::vector<std::uint8_t>& discards(std::uint8_t player) const;
    [[nodiscard]] bool ready(std::uint8_t player) const;
    [[nodiscard]] bool responded(std::uint8_t player) const;
    [[nodiscard]] std::optional<std::uint8_t> pending_tile() const noexcept;
    [[nodiscard]] std::optional<std::uint8_t> pending_player() const noexcept;
    [[nodiscard]] std::vector<std::uint8_t> waits(std::uint8_t player) const;
    [[nodiscard]] std::vector<std::uint8_t> waits_after_discard(
        std::uint8_t player, std::uint8_t tile
    ) const;
    [[nodiscard]] std::vector<LanzhouMahjongAction> legal_actions(std::uint8_t player) const;
    [[nodiscard]] const LanzhouMahjongPayment& net_scores() const noexcept;
    [[nodiscard]] const LanzhouMahjongOutcome& outcome() const noexcept;

    [[nodiscard]] ActionResult apply(const LanzhouMahjongAction& action);
    [[nodiscard]] bool undo();
    [[nodiscard]] std::vector<std::uint8_t> serialize() const;
    [[nodiscard]] RestoreResult restore(const std::vector<std::uint8_t>& data);

private:
    struct Claim { bool responded{}; LanzhouMahjongActionKind kind{LanzhouMahjongActionKind::pass}; };
    struct State {
        std::array<Hand, 4> hands{};
        std::array<std::vector<LanzhouMahjongMeld>, 4> melds;
        std::array<std::vector<std::uint8_t>, 4> discards;
        std::array<bool, 4> ready{};
        std::array<std::vector<std::uint8_t>, 4> locked_waits;
        std::array<Claim, 4> claims{};
        LanzhouMahjongPayment scores{};
        LanzhouMahjongOutcome outcome{};
        std::size_t front{};
        std::size_t back{108};
        std::uint8_t current{};
        LanzhouMahjongPhase phase{LanzhouMahjongPhase::discard};
        std::optional<std::uint8_t> pending_tile;
        std::optional<std::uint8_t> pending_player;
        std::optional<std::uint8_t> drawn_tile;
        bool kong_draw{};
        bool last_draw{};
        bool pending_last_tile{};
    };
    [[nodiscard]] bool perform(const LanzhouMahjongAction& action);
    [[nodiscard]] bool valid_action(const LanzhouMahjongAction& action) const;
    [[nodiscard]] bool can_win(std::uint8_t player, std::optional<std::uint8_t> offered) const;
    [[nodiscard]] std::vector<std::uint8_t> waits_for(
        const Hand& hand, const std::vector<LanzhouMahjongMeld>& melds
    ) const;
    [[nodiscard]] bool add_payment(const std::optional<LanzhouMahjongPayment>& payment);
    [[nodiscard]] bool resolve_claims();
    [[nodiscard]] bool finish_win(std::uint8_t player, std::optional<std::uint8_t> payer);
    void receive_draw(bool replacement);
    void start_claim(std::uint8_t player, std::uint8_t tile, bool rob_kong);

    Wall wall_;
    std::uint8_t dealer_{};
    std::int64_t base_{1};
    LanzhouMahjongRule rule_{LanzhouMahjongRule::project_false_win_24};
    State state_;
    std::vector<State> history_;
    std::vector<LanzhouMahjongAction> actions_;
};

}  // namespace mocs::engine
