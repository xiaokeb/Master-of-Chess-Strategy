#pragma once

#include "mocs/engine/rule_engine.hpp"

#include <cstdint>
#include <cstddef>
#include <optional>
#include <vector>

namespace mocs::engine {

inline constexpr std::uint16_t go_place_action_kind = 1;
inline constexpr std::uint16_t go_pass_action_kind = 2;
inline constexpr std::uint16_t go_toggle_dead_action_kind = 3;
inline constexpr std::uint16_t go_confirm_score_action_kind = 4;
inline constexpr std::uint16_t go_resume_play_action_kind = 5;

enum class GoPhase : std::uint8_t {
    playing = 0,
    scoring = 1,
    finished = 2,
};

enum class GoStone : std::uint8_t {
    empty = 0,
    black = 1,
    white = 2,
};

struct GoPoint {
    std::int32_t x;
    std::int32_t y;
};

// Assessed area includes half of jointly bounded neutral points, not only
// exclusively occupied points. Area and komi use quarter-stone units. Komi
// is deducted from Black's half-board comparison, equivalent to adding twice
// komi to White's assessed area.
struct GoScore {
    std::int32_t black_area_quarters;
    std::int32_t white_area_quarters;
    std::int32_t black_komi_quarters;
    GameResult result;
    std::int32_t handicap_adjustment_quarters{};
};

struct GoScoringState {
    GoPhase phase;
    std::uint32_t proposal_revision;
    bool black_confirmed;
    bool white_confirmed;
    std::vector<GoPoint> dead_stones;
};

[[nodiscard]] EngineAction make_go_place(std::int32_t x, std::int32_t y) noexcept;
[[nodiscard]] EngineAction make_go_pass() noexcept;
[[nodiscard]] EngineAction make_go_toggle_dead(
    std::int32_t x, std::int32_t y, std::uint8_t player
) noexcept;
[[nodiscard]] EngineAction make_go_confirm_score(
    std::uint8_t player, std::uint32_t proposal_revision
) noexcept;
// A disputed life/death decision resumes with the side claiming death first.
[[nodiscard]] EngineAction make_go_resume_play(std::uint8_t first_player) noexcept;

// Rules-only slice. Two passes start agreement; only two confirmations of the
// same dead-stone proposal can turn its area preview into a final result.
class GoEngine final : public RuleEngine {
public:
    explicit GoEngine(std::uint8_t size = 19, std::int32_t black_komi_quarters = 15,
                      std::uint8_t handicap_stones = 0);

    [[nodiscard]] GameType game_type() const noexcept override;
    [[nodiscard]] std::uint8_t current_player() const noexcept override;
    void reset() override;
    [[nodiscard]] ActionResult apply(const EngineAction& action) override;
    [[nodiscard]] bool undo() override;
    [[nodiscard]] std::vector<EngineAction> legal_actions() const override;
    [[nodiscard]] GameResult game_result() const noexcept override;
    [[nodiscard]] std::vector<std::uint8_t> serialize() const override;
    [[nodiscard]] RestoreResult restore(const std::vector<std::uint8_t>& data) override;

    [[nodiscard]] std::uint8_t board_size() const noexcept;
    [[nodiscard]] std::optional<GoStone> stone_at(std::int32_t x, std::int32_t y) const noexcept;
    [[nodiscard]] bool ready_for_scoring() const noexcept;
    [[nodiscard]] GoPhase phase() const noexcept;
    [[nodiscard]] GoScoringState scoring_state() const;
    [[nodiscard]] std::optional<GoScore> final_score() const;
    [[nodiscard]] std::int32_t black_komi_quarters() const noexcept;
    [[nodiscard]] std::uint8_t handicap_stones() const noexcept;
    [[nodiscard]] std::vector<GoPoint> handicap_points() const;
    // SGF moves exclude agreement events. Full events preserve resume actors.
    [[nodiscard]] std::vector<EngineAction> action_history() const;
    [[nodiscard]] std::vector<EngineAction> event_history() const;
    [[nodiscard]] std::optional<GoScore> preview_area_score(
        const std::vector<GoPoint>& agreed_dead_stones,
        std::int32_t black_komi_quarters = -1
    ) const;

private:
    struct Position {
        std::vector<GoStone> board;
        std::uint8_t player;
        std::uint8_t passes;
        GoPhase phase;
        std::vector<bool> dead;
        std::uint8_t confirmations;
        std::uint32_t proposal_revision;
        GameResult result;
    };

    [[nodiscard]] bool inside(std::int32_t x, std::int32_t y) const noexcept;
    [[nodiscard]] std::size_t index(std::int32_t x, std::int32_t y) const noexcept;
    [[nodiscard]] std::vector<std::size_t> neighbors(std::size_t point) const;
    [[nodiscard]] bool placement_board(
        std::int32_t x, std::int32_t y, std::vector<GoStone>& result
    ) const;
    [[nodiscard]] bool group_has_liberty(
        const std::vector<GoStone>& board,
        std::size_t start,
        std::vector<std::size_t>& group
    ) const;
    [[nodiscard]] static std::uint64_t board_hash(const std::vector<GoStone>& board) noexcept;

    std::uint8_t size_;
    std::int32_t black_komi_quarters_;
    std::uint8_t handicap_stones_;
    std::uint32_t proposal_clock_{};
    std::size_t board_action_count_{};
    std::vector<Position> positions_;
    std::vector<std::uint64_t> hashes_;
    std::vector<EngineAction> actions_;
};

}  // namespace mocs::engine
