#pragma once

#include "mocs/engine/engine_types.hpp"

#include <array>
#include <cstddef>
#include <cstdint>
#include <vector>

namespace mocs::engine {

// Tile ids 0..8 are wan, 9..17 are tiao, and 18..26 are tong.
class LanzhouMahjongRound {
public:
    static constexpr std::uint8_t player_count = 4;
    static constexpr std::uint8_t tile_kind_count = 27;
    static constexpr std::size_t tile_count = 108;
    using Hand = std::array<std::uint8_t, tile_kind_count>;

    enum class Phase : std::uint8_t { discard, draw, exhausted };

    explicit LanzhouMahjongRound(std::uint64_t seed);

    void reset(std::uint64_t seed);
    [[nodiscard]] bool reset_with_wall(const std::array<std::uint8_t, tile_count>& wall);
    [[nodiscard]] std::uint8_t current_player() const noexcept;
    [[nodiscard]] Phase phase() const noexcept;
    [[nodiscard]] std::size_t wall_remaining() const noexcept;
    [[nodiscard]] const Hand& hand(std::uint8_t player) const;
    [[nodiscard]] const std::vector<std::uint8_t>& discards(std::uint8_t player) const;
    [[nodiscard]] std::vector<std::uint8_t> legal_discards() const;
    [[nodiscard]] static bool has_missing_suit(const Hand& hand) noexcept;
    [[nodiscard]] static bool is_closed_four_meld_pair(const Hand& hand);
    [[nodiscard]] static bool is_closed_seven_pairs(const Hand& hand) noexcept;
    [[nodiscard]] static bool is_concealed_four_meld_pair(
        const Hand& hand, std::uint8_t exposed_melds
    );
    [[nodiscard]] static std::vector<std::uint8_t> closed_four_meld_pair_waits(
        const Hand& hand
    );
    [[nodiscard]] bool can_self_draw_four_meld_pair() const;

    [[nodiscard]] ActionResult draw(std::uint8_t player);
    [[nodiscard]] ActionResult discard(std::uint8_t player, std::uint8_t tile);

private:
    void deal_from_wall();

    std::array<std::uint8_t, tile_count> wall_{};
    std::array<Hand, player_count> hands_{};
    std::array<std::vector<std::uint8_t>, player_count> discards_{};
    std::size_t next_tile_{};
    std::uint8_t current_player_{};
    Phase phase_{Phase::discard};
};

}  // namespace mocs::engine
