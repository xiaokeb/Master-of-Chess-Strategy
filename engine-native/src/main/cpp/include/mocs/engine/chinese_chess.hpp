#pragma once

#include "mocs/engine/rule_engine.hpp"

#include <array>
#include <cstddef>
#include <cstdint>
#include <optional>
#include <vector>

namespace mocs::engine {

inline constexpr std::uint16_t board_move_action_kind = 0;

enum class Side : std::uint8_t {
    red = 0,
    black = 1,
};

// Values form part of the versioned position format and must not be reordered.
enum class PieceType : std::uint8_t {
    general = 1,
    advisor = 2,
    elephant = 3,
    horse = 4,
    chariot = 5,
    cannon = 6,
    soldier = 7,
};

struct Piece {
    PieceType type;
    Side side;

    [[nodiscard]] constexpr bool operator==(
        const Piece& other
    ) const noexcept {
        return type == other.type && side == other.side;
    }
};

[[nodiscard]] EngineAction make_board_move(
    std::int32_t from_x,
    std::int32_t from_y,
    std::int32_t to_x,
    std::int32_t to_y
) noexcept;

/** First vertical slice of the Chinese chess rules engine. */
class ChineseChessEngine final : public RuleEngine {
public:
    ChineseChessEngine();

    [[nodiscard]] GameType game_type() const noexcept override;
    [[nodiscard]] std::uint8_t current_player() const noexcept override;
    void reset() override;
    [[nodiscard]] ActionResult apply(const EngineAction& action) override;
    [[nodiscard]] bool undo() override;
    [[nodiscard]] std::vector<EngineAction> legal_actions() const override;
    [[nodiscard]] GameResult game_result() const noexcept override;
    [[nodiscard]] std::vector<std::uint8_t> serialize() const override;
    [[nodiscard]] RestoreResult restore(
        const std::vector<std::uint8_t>& data
    ) override;

    [[nodiscard]] std::optional<Piece> piece_at(
        std::int32_t x,
        std::int32_t y
    ) const noexcept;
    [[nodiscard]] bool is_in_check(Side side) const noexcept;

private:
    static constexpr std::int32_t board_width = 9;
    static constexpr std::int32_t board_height = 10;
    static constexpr std::size_t board_size =
        static_cast<std::size_t>(board_width * board_height);

    using Board = std::array<std::optional<Piece>, board_size>;

    struct MoveRecord {
        std::int32_t from_x;
        std::int32_t from_y;
        std::int32_t to_x;
        std::int32_t to_y;
        Piece moved;
        std::optional<Piece> captured;
        Side previous_side;
    };

    [[nodiscard]] static bool is_inside(
        std::int32_t x,
        std::int32_t y
    ) noexcept;
    [[nodiscard]] static std::size_t index(
        std::int32_t x,
        std::int32_t y
    ) noexcept;
    [[nodiscard]] static Side opposite(Side side) noexcept;
    [[nodiscard]] static bool is_in_palace(
        Side side,
        std::int32_t x,
        std::int32_t y
    ) noexcept;
    [[nodiscard]] static std::int32_t path_blockers(
        const Board& board,
        std::int32_t from_x,
        std::int32_t from_y,
        std::int32_t to_x,
        std::int32_t to_y
    ) noexcept;
    [[nodiscard]] static bool piece_attacks_square(
        const Board& board,
        const Piece& piece,
        std::int32_t from_x,
        std::int32_t from_y,
        std::int32_t to_x,
        std::int32_t to_y
    ) noexcept;
    [[nodiscard]] static bool is_in_check(
        const Board& board,
        Side side
    ) noexcept;
    [[nodiscard]] bool is_legal_move(
        std::int32_t from_x,
        std::int32_t from_y,
        std::int32_t to_x,
        std::int32_t to_y
    ) const noexcept;
    [[nodiscard]] bool has_general(Side side) const noexcept;
    [[nodiscard]] bool has_legal_action() const noexcept;

    Board board_{};
    Side current_side_{Side::red};
    std::vector<MoveRecord> history_;
};

}  // namespace mocs::engine
