#pragma once

#include <cstdint>
#include <string>
#include <vector>

namespace mocs::engine {

enum class CompetitionMahjongFanPackKind : std::uint8_t { chow = 1, pung = 2, kong = 3 };

struct CompetitionMahjongFanPack {
    CompetitionMahjongFanPackKind kind{CompetitionMahjongFanPackKind::chow};
    int tile{}; // 0..33; Chow uses the sequence's first tile.
    int offer{1}; // Chow: claimed tile position 1..3; Pung/Kong: relative source 1..3; concealed Kong: 0.
    bool added_kong{};
};

struct CompetitionMahjongFanInput {
    std::vector<int> concealed_tiles; // Excludes win_tile; exactly 13 - 3 * packs.size().
    std::vector<CompetitionMahjongFanPack> packs;
    int win_tile{};
    bool self_drawn{};
    bool last_tile{}; // Winning draw was the wall's last ordinary tile, or discard follows it.
    bool fourth_tile{}; // All other three copies were visible before this winning tile.
    bool replacement{}; // Winning self draw directly replaces a Kong, not a Flower.
    bool robbing_kong{}; // Winning claim of a Pung's attempted promotion to a Kong.
    int seat_wind{}; // East=0, South=1, West=2, North=3.
    int round_wind{};
    int flower_count{}; // 0..8; excluded from the eight point qualification threshold.
};

enum class CompetitionMahjongFanError : std::uint8_t {
    none = 0, invalid_tile = 1, invalid_pack = 2, wrong_tile_count = 3,
    too_many_copies = 4, invalid_context = 5, not_winning = 6,
};

struct CompetitionMahjongFanEntry {
    int id{}; // Stable official 81-fan catalogue ID 1..81, Flower=81.
    std::string name;
    int points{}; // Value for one occurrence.
    int count{};
};

struct CompetitionMahjongFanResult {
    CompetitionMahjongFanError error{CompetitionMahjongFanError::none};
    bool structure_valid{};
    int fan_without_flowers{};
    int total_fan{};
    bool qualifies{};
    std::vector<CompetitionMahjongFanEntry> entries;
};

[[nodiscard]] CompetitionMahjongFanResult evaluateCompetitionMahjongFan(
    const CompetitionMahjongFanInput& input);

// Complete official catalogue in descending score order, independent of a particular hand.
[[nodiscard]] const std::vector<CompetitionMahjongFanEntry>& competitionMahjongFanCatalogue();

} // namespace mocs::engine
