#pragma once

#include "mocs/engine/competition_mahjong.hpp"
#include "mocs/engine/competition_mahjong_fan.hpp"

#include <array>
#include <cstdint>
#include <optional>
#include <vector>

namespace mocs::engine {

enum class CompetitionMahjongRoundPhase : std::uint8_t {
    discard = 0, draw = 1, claim = 2, rob_kong = 3, won = 4, exhaustive_draw = 5,
};

enum class CompetitionMahjongRoundActionKind : std::uint8_t {
    draw = 0, discard = 1, pass = 2, chow = 3, pung = 4,
    exposed_kong = 5, concealed_kong = 6, added_kong = 7, hu = 8,
};

enum class CompetitionMahjongRoundError : std::uint8_t {
    none = 0, wrong_phase = 1, wrong_player = 2, illegal_action = 3,
    already_responded = 4, corrupted_data = 5, action_limit = 6,
};

struct CompetitionMahjongRoundAction {
    CompetitionMahjongRoundActionKind kind{};
    std::uint8_t player{};
    CompetitionMahjongTile tile{};
    CompetitionMahjongTile sequence_start{};
};

struct CompetitionMahjongRoundActionResult {
    bool accepted{};
    CompetitionMahjongRoundError error{CompetitionMahjongRoundError::none};
    bool false_hu{};
};

struct CompetitionMahjongRoundMeld {
    CompetitionMahjongMeldKind kind{};
    CompetitionMahjongTile tile{}; // Chow: start tile; Pung/Kong: repeated tile.
    std::uint8_t from_player{};
    std::uint8_t claimed_position{}; // Chow only: claimed tile's position 1..3.
    bool concealed{};
    bool added_kong{};
};

struct CompetitionMahjongRoundView {
    CompetitionMahjongRoundPhase phase{};
    std::uint8_t dealer{};
    std::uint8_t round_wind{};
    std::uint8_t current_player{};
    std::size_t wall_remaining{};
    std::optional<CompetitionMahjongTile> pending_tile;
    std::optional<std::uint8_t> pending_player;
    std::array<std::vector<CompetitionMahjongTile>, 4> concealed;
    std::array<std::vector<CompetitionMahjongTile>, 4> flowers;
    std::array<std::vector<CompetitionMahjongTile>, 4> discards;
    std::array<std::vector<CompetitionMahjongRoundMeld>, 4> melds;
    std::array<bool, 4> win_disabled{};
    std::array<std::int32_t, 4> score_delta{};
    std::optional<std::uint8_t> winner;
    std::optional<std::uint8_t> discarder;
    CompetitionMahjongFanResult winning_fan;
    std::optional<CompetitionMahjongTile> drawn_tile; // Current seat's latest draw, only before discard after drawing.
    std::array<bool, 4> can_declare_win{}; // Phase eligibility, independent of the eight-point threshold.
    std::optional<CompetitionMahjongTile> winning_tile;
};

[[nodiscard]] std::vector<CompetitionMahjongTile> competition_mahjong_shuffled_wall(
    std::uint64_t seed);

// Complete action lifecycle. The fan evaluator decides Hu and its eight-point threshold.
// The UI must present only its own seat's concealed tiles.
class CompetitionMahjongRound final {
public:
    explicit CompetitionMahjongRound(
        std::vector<CompetitionMahjongTile> wall,
        std::uint8_t dealer = 0,
        std::uint8_t round_wind = 0);

    [[nodiscard]] CompetitionMahjongRoundView view() const;
    [[nodiscard]] std::vector<CompetitionMahjongRoundAction> legal_actions(
        std::uint8_t player) const;
    [[nodiscard]] CompetitionMahjongRoundActionResult apply(
        const CompetitionMahjongRoundAction& action);
    [[nodiscard]] bool undo();
    [[nodiscard]] std::vector<std::uint8_t> serialize() const;
    [[nodiscard]] bool restore(const std::vector<std::uint8_t>& data);
    [[nodiscard]] std::uint8_t next_dealer() const noexcept;

private:
    struct ClaimIntent {
        bool responded{};
        CompetitionMahjongRoundAction action{};
        CompetitionMahjongFanResult fan{};
    };

    [[nodiscard]] CompetitionMahjongRoundActionResult execute(
        const CompetitionMahjongRoundAction& action);
    [[nodiscard]] CompetitionMahjongTile take_front();
    [[nodiscard]] CompetitionMahjongTile take_back();
    [[nodiscard]] bool receive(std::uint8_t player, CompetitionMahjongTile tile,
                               bool kong_replacement);
    void replace_initial_flowers(std::uint8_t player);
    [[nodiscard]] bool can_claim(const CompetitionMahjongRoundAction& action) const;
    [[nodiscard]] bool can_kong(const CompetitionMahjongRoundAction& action) const;
    [[nodiscard]] CompetitionMahjongFanResult evaluate_hu(
        std::uint8_t player, CompetitionMahjongTile tile, bool self_drawn,
        bool robbing_kong) const;
    void record_false_hu(std::uint8_t player, bool below_minimum);
    void resolve_claims();
    void settle_win(std::uint8_t winner, std::optional<std::uint8_t> discarder,
                    CompetitionMahjongTile tile, const CompetitionMahjongFanResult& fan);
    void complete_added_kong();

    std::vector<CompetitionMahjongTile> initial_wall_;
    std::size_t front_{};
    std::size_t back_{};
    std::array<std::vector<CompetitionMahjongTile>, 4> concealed_{};
    std::array<std::vector<CompetitionMahjongTile>, 4> flowers_{};
    std::array<std::vector<CompetitionMahjongTile>, 4> discards_{};
    std::array<std::vector<CompetitionMahjongRoundMeld>, 4> melds_{};
    std::array<ClaimIntent, 4> claims_{};
    std::array<bool, 4> win_disabled_{};
    std::array<std::int32_t, 4> score_delta_{};
    std::vector<CompetitionMahjongRoundAction> journal_;
    CompetitionMahjongFanResult winning_fan_{};
    std::uint8_t dealer_{};
    std::uint8_t round_wind_{};
    std::uint8_t current_player_{};
    std::uint8_t pending_player_{};
    CompetitionMahjongTile pending_tile_{};
    CompetitionMahjongTile last_drawn_tile_{};
    bool can_self_kong_{};
    bool last_draw_was_kong_replacement_{};
    bool last_draw_was_wall_last_{};
    bool last_discard_was_wall_last_{};
    std::optional<std::uint8_t> winner_;
    std::optional<std::uint8_t> discarder_;
    CompetitionMahjongRoundPhase phase_{CompetitionMahjongRoundPhase::discard};
};

}  // namespace mocs::engine
