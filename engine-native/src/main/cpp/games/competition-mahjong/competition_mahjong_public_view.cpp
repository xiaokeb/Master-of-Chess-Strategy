#include "competition_mahjong_public_view.hpp"

#include <stdexcept>

namespace mocs::engine {

CompetitionMahjongPublicView competition_mahjong_public_view(
    const CompetitionMahjongRound& round, const std::uint8_t viewer) {
    if (viewer >= 4) throw std::invalid_argument("invalid viewer seat");
    const auto internal = round.view();
    CompetitionMahjongPublicView result{};
    result.phase = internal.phase;
    result.viewer = viewer;
    result.dealer = internal.dealer;
    result.round_wind = internal.round_wind;
    result.current_player = internal.current_player;
    result.wall_remaining = internal.wall_remaining;
    result.pending_tile = internal.pending_tile;
    result.pending_player = internal.pending_player;
    result.score_delta = internal.score_delta;
    result.winner = internal.winner;
    result.discarder = internal.discarder;
    result.winning_fan = internal.winning_fan;
    result.can_declare_win = internal.can_declare_win[viewer];
    if (viewer == internal.current_player) result.drawn_tile = internal.drawn_tile;
    const bool terminal = internal.phase == CompetitionMahjongRoundPhase::won ||
        internal.phase == CompetitionMahjongRoundPhase::exhaustive_draw;
    if (internal.phase == CompetitionMahjongRoundPhase::won && internal.winner) {
        result.winning_tile = internal.winning_tile;
        result.winning_tiles = internal.concealed[*internal.winner];
    }
    for (std::uint8_t seat = 0; seat < 4; ++seat) {
        auto& public_seat = result.seats[seat];
        public_seat.concealed_count = internal.concealed[seat].size();
        if (seat == viewer) public_seat.local_concealed = internal.concealed[seat];
        public_seat.flowers = internal.flowers[seat];
        public_seat.discards = internal.discards[seat];
        public_seat.win_disabled = internal.win_disabled[seat];
        for (const auto& meld : internal.melds[seat]) {
            const bool hidden = meld.concealed && seat != viewer && !terminal;
            public_seat.melds.push_back({meld.kind,
                hidden ? std::nullopt : std::optional<CompetitionMahjongTile>(meld.tile),
                meld.from_player, meld.claimed_position, meld.added_kong});
        }
    }
    return result;
}

} // namespace mocs::engine
