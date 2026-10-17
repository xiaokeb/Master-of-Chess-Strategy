#pragma once

#include "competition_mahjong_round.hpp"

#include <array>
#include <cstdint>
#include <optional>
#include <vector>

namespace mocs::engine {

struct CompetitionMahjongPublicMeld {
    CompetitionMahjongMeldKind kind{};
    std::optional<CompetitionMahjongTile> tile; // Another seat's concealed Kong is hidden until the round ends.
    std::uint8_t from_player{};
    std::uint8_t claimed_position{};
    bool added_kong{};
};

struct CompetitionMahjongPublicSeat {
    std::size_t concealed_count{};
    std::vector<CompetitionMahjongTile> local_concealed; // Empty for every other seat.
    std::vector<CompetitionMahjongTile> flowers;
    std::vector<CompetitionMahjongTile> discards;
    std::vector<CompetitionMahjongPublicMeld> melds;
    bool win_disabled{};
};

struct CompetitionMahjongPublicView {
    CompetitionMahjongRoundPhase phase{};
    std::uint8_t viewer{};
    std::uint8_t dealer{};
    std::uint8_t round_wind{};
    std::uint8_t current_player{};
    std::size_t wall_remaining{};
    std::optional<CompetitionMahjongTile> pending_tile;
    std::optional<std::uint8_t> pending_player;
    std::array<CompetitionMahjongPublicSeat, 4> seats{};
    std::array<std::int32_t, 4> score_delta{};
    std::optional<std::uint8_t> winner;
    std::optional<std::uint8_t> discarder;
    CompetitionMahjongFanResult winning_fan;
    bool can_declare_win{};
    std::optional<CompetitionMahjongTile> drawn_tile; // Only the viewer's current draw.
    std::optional<CompetitionMahjongTile> winning_tile;
    std::vector<CompetitionMahjongTile> winning_tiles; // Winner's concealed hand, including the winning tile; empty before a win.
};

// The only RoundView projection that may cross the JNI/App presentation boundary.
[[nodiscard]] CompetitionMahjongPublicView competition_mahjong_public_view(
    const CompetitionMahjongRound& round, std::uint8_t viewer);

} // namespace mocs::engine
