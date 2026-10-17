#pragma once

#include <array>
#include <cstddef>
#include <cstdint>
#include <optional>
#include <vector>

namespace mocs::engine {

// 0-26: three numbered suits, 27-33: honors, 34-41: eight unique flowers.
using CompetitionMahjongTile = std::uint8_t;
inline constexpr std::size_t competition_mahjong_tile_count = 144;
inline constexpr std::uint8_t competition_mahjong_player_count = 4;

enum class CompetitionMahjongPhase : std::uint8_t {
    discard = 0,
    claim = 1,
    draw = 2,
    exhaustive_draw = 3,
};

enum class CompetitionMahjongMeldKind : std::uint8_t {
    chow = 1,
    pung = 2,
    kong = 3,
};

enum class CompetitionMahjongClaimKind : std::uint8_t {
    pass = 0,
    chow = 1,
    pung = 2,
    kong = 3,
};

struct CompetitionMahjongMeld {
    CompetitionMahjongMeldKind kind{};
    CompetitionMahjongTile tile{};
    CompetitionMahjongTile sequence_start{};
    std::uint8_t from_player{};
};

enum class CompetitionMahjongError : std::uint8_t {
    none = 0,
    wrong_phase = 1,
    tile_not_in_hand = 2,
    invalid_claim = 3,
    already_responded = 4,
};

struct CompetitionMahjongActionResult {
    bool accepted{};
    CompetitionMahjongError error{CompetitionMahjongError::none};
};

[[nodiscard]] std::vector<CompetitionMahjongTile> competition_mahjong_canonical_wall();
[[nodiscard]] bool competition_mahjong_valid_wall(
    const std::vector<CompetitionMahjongTile>& wall
) noexcept;

// Rules-only slice. Hu, scoring, concealed/added Kongs, and complete
// declaration priorities are not enabled until a validated Hu evaluator exists.
class CompetitionMahjongHand final {
public:
    explicit CompetitionMahjongHand(
        std::vector<CompetitionMahjongTile> wall,
        std::uint8_t dealer = 0
    );

    [[nodiscard]] std::uint8_t current_player() const noexcept;
    [[nodiscard]] std::uint8_t dealer() const noexcept;
    [[nodiscard]] CompetitionMahjongPhase phase() const noexcept;
    [[nodiscard]] std::size_t wall_remaining() const noexcept;
    [[nodiscard]] const std::vector<CompetitionMahjongTile>& concealed(
        std::uint8_t player
    ) const;
    [[nodiscard]] const std::vector<CompetitionMahjongTile>& flowers(
        std::uint8_t player
    ) const;
    [[nodiscard]] const std::vector<CompetitionMahjongTile>& discards(
        std::uint8_t player
    ) const;
    [[nodiscard]] const std::vector<CompetitionMahjongMeld>& melds(
        std::uint8_t player
    ) const;
    [[nodiscard]] std::optional<CompetitionMahjongTile> pending_discard() const noexcept;
    [[nodiscard]] std::optional<std::uint8_t> pending_discarder() const noexcept;

    [[nodiscard]] CompetitionMahjongActionResult draw();
    [[nodiscard]] CompetitionMahjongActionResult discard(CompetitionMahjongTile tile);
    [[nodiscard]] CompetitionMahjongActionResult submit_claim(
        std::uint8_t player,
        CompetitionMahjongClaimKind kind,
        CompetitionMahjongTile sequence_start = 0
    );

private:
    [[nodiscard]] CompetitionMahjongTile take_front();
    [[nodiscard]] CompetitionMahjongTile take_back();
    [[nodiscard]] bool receive(std::uint8_t player, CompetitionMahjongTile tile);
    void replace_initial_flowers(std::uint8_t player);
    [[nodiscard]] bool valid_claim(
        std::uint8_t player,
        CompetitionMahjongClaimKind kind,
        CompetitionMahjongTile sequence_start
    ) const;
    void resolve_claims();

    struct ClaimIntent {
        bool responded{};
        CompetitionMahjongClaimKind kind{CompetitionMahjongClaimKind::pass};
        CompetitionMahjongTile sequence_start{};
    };

    std::vector<CompetitionMahjongTile> wall_;
    std::size_t front_{};
    std::size_t back_{};
    std::array<std::vector<CompetitionMahjongTile>, competition_mahjong_player_count>
        concealed_{};
    std::array<std::vector<CompetitionMahjongTile>, competition_mahjong_player_count>
        flowers_{};
    std::array<std::vector<CompetitionMahjongTile>, competition_mahjong_player_count>
        discards_{};
    std::array<std::vector<CompetitionMahjongMeld>, competition_mahjong_player_count>
        melds_{};
    std::array<ClaimIntent, competition_mahjong_player_count> claims_{};
    std::uint8_t dealer_{};
    std::uint8_t current_player_{};
    std::uint8_t discarder_{};
    CompetitionMahjongTile claim_tile_{};
    CompetitionMahjongPhase phase_{CompetitionMahjongPhase::discard};
};

}  // namespace mocs::engine
