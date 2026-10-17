#include "mocs/engine/competition_mahjong_fan.hpp"

#include "fan/vendor/fan_calculator.h"

#include <algorithm>
#include <array>
#include <cstddef>

namespace mocs::engine {
namespace {

mahjong::tile_t vendor_tile(const int tile) {
    if (tile < 27) return mahjong::make_tile(static_cast<mahjong::suit_t>(tile / 9 + 1),
                                             static_cast<mahjong::rank_t>(tile % 9 + 1));
    return mahjong::make_tile(4, static_cast<mahjong::rank_t>(tile - 26));
}

int official_value(const int id) {
    if (id == mahjong::TWO_CONCEALED_KONGS) return 8;
    if (id == mahjong::CONCEALED_KONG_AND_MELDED_KONG) return 6;
    return mahjong::fan_value<>::table[id];
}

int official_id(const int vendor_id) {
    // WMO lists Two Concealed Kongs as #48 (8 points), before the six-point fans.
    if (vendor_id == mahjong::TWO_CONCEALED_KONGS) return 48;
    if (vendor_id >= mahjong::ALL_PUNGS && vendor_id <= mahjong::MELDED_HAND)
        return vendor_id + 1;
    return vendor_id;
}

} // namespace

CompetitionMahjongFanResult evaluateCompetitionMahjongFan(
    const CompetitionMahjongFanInput& input) {
    CompetitionMahjongFanResult result{};
    if (input.win_tile < 0 || input.win_tile >= 34 || input.seat_wind < 0 ||
        input.seat_wind > 3 || input.round_wind < 0 || input.round_wind > 3 ||
        input.flower_count < 0 || input.flower_count > 8) {
        result.error = CompetitionMahjongFanError::invalid_context;
        return result;
    }
    if (input.packs.size() > 4) {
        result.error = CompetitionMahjongFanError::invalid_pack;
        return result;
    }
    if (input.concealed_tiles.size() != 13 - 3 * input.packs.size()) {
        result.error = CompetitionMahjongFanError::wrong_tile_count;
        return result;
    }
    if ((input.replacement && (!input.self_drawn || input.robbing_kong)) ||
        (input.robbing_kong && input.self_drawn)) {
        result.error = CompetitionMahjongFanError::invalid_context;
        return result;
    }

    std::array<int, 34> counts{};
    ++counts[static_cast<std::size_t>(input.win_tile)];
    mahjong::calculate_param_t param{};
    param.win_tile = vendor_tile(input.win_tile);
    param.hand_tiles.pack_count = static_cast<std::intptr_t>(input.packs.size());
    param.hand_tiles.tile_count = static_cast<std::intptr_t>(input.concealed_tiles.size());
    for (std::size_t i = 0; i < input.concealed_tiles.size(); ++i) {
        const int tile = input.concealed_tiles[i];
        if (tile < 0 || tile >= 34) {
            result.error = CompetitionMahjongFanError::invalid_tile;
            return result;
        }
        ++counts[static_cast<std::size_t>(tile)];
        param.hand_tiles.standing_tiles[i] = vendor_tile(tile);
    }
    for (std::size_t i = 0; i < input.packs.size(); ++i) {
        const auto& pack = input.packs[i];
        if (pack.tile < 0 || pack.tile >= 34) {
            result.error = CompetitionMahjongFanError::invalid_pack;
            return result;
        }
        int type = 0;
        int tile = pack.tile;
        if (pack.kind == CompetitionMahjongFanPackKind::chow) {
            if (tile >= 27 || tile % 9 > 6 || pack.offer < 1 || pack.offer > 3 ||
                pack.added_kong) {
                result.error = CompetitionMahjongFanError::invalid_pack;
                return result;
            }
            for (int n = 0; n < 3; ++n) ++counts[static_cast<std::size_t>(tile + n)];
            ++tile; // The vendor encodes a Chow by its middle tile.
            type = PACK_TYPE_CHOW;
        } else if (pack.kind == CompetitionMahjongFanPackKind::pung) {
            if (pack.offer < 1 || pack.offer > 3 || pack.added_kong) {
                result.error = CompetitionMahjongFanError::invalid_pack;
                return result;
            }
            counts[static_cast<std::size_t>(tile)] += 3;
            type = PACK_TYPE_PUNG;
        } else if (pack.kind == CompetitionMahjongFanPackKind::kong) {
            if (pack.offer < 0 || pack.offer > 3 || (pack.added_kong && pack.offer == 0)) {
                result.error = CompetitionMahjongFanError::invalid_pack;
                return result;
            }
            counts[static_cast<std::size_t>(tile)] += 4;
            type = PACK_TYPE_KONG;
        } else {
            result.error = CompetitionMahjongFanError::invalid_pack;
            return result;
        }
        auto encoded = mahjong::make_pack(static_cast<std::uint8_t>(pack.offer),
            static_cast<std::uint8_t>(type), vendor_tile(tile));
        if (pack.added_kong) encoded = mahjong::promote_pung_to_kong(
            mahjong::make_pack(static_cast<std::uint8_t>(pack.offer), PACK_TYPE_PUNG,
                               vendor_tile(tile)));
        param.hand_tiles.fixed_packs[i] = encoded;
    }
    for (const int count : counts) {
        if (count > 4) {
            result.error = CompetitionMahjongFanError::too_many_copies;
            return result;
        }
    }
    param.flower_count = static_cast<std::uint8_t>(input.flower_count);
    param.seat_wind = static_cast<mahjong::wind_t>(input.seat_wind);
    param.prevalent_wind = static_cast<mahjong::wind_t>(input.round_wind);
    param.win_flag = input.self_drawn ? WIN_FLAG_SELF_DRAWN : WIN_FLAG_DISCARD;
    if (input.last_tile) param.win_flag |= WIN_FLAG_WALL_LAST;
    if (input.fourth_tile) param.win_flag |= WIN_FLAG_LAST_TILE;
    if (input.replacement || input.robbing_kong) param.win_flag |= WIN_FLAG_KONG_INVOLVED;

    mahjong::fan_table_t table{};
    const int vendor_total = mahjong::calculate_fan(&param, &table);
    if (vendor_total < 0) {
        result.error = vendor_total == ERROR_NOT_WIN ?
            CompetitionMahjongFanError::not_winning : CompetitionMahjongFanError::invalid_context;
        return result;
    }
    result.structure_valid = true;
    const bool fully_concealed_self_draw = input.self_drawn &&
        std::all_of(input.packs.begin(), input.packs.end(), [](const auto& pack) {
            return pack.kind == CompetitionMahjongFanPackKind::kong && pack.offer == 0;
        });
    if (fully_concealed_self_draw) {
        // WMO #4/6/7/12/19/20/34 allow #56, including special winning forms.
        // Last Tile Draw/Out with Replacement Tile exclude #80, not #56.
        table[mahjong::FULLY_CONCEALED_HAND] = 1;
        table[mahjong::SELF_DRAWN] = 0;
    }
    for (int id = 1; id < mahjong::FAN_TABLE_SIZE; ++id) {
        if (table[id] == 0) continue;
        const int points = official_value(id);
        // The vendor models the rulebook's mixed-Kong subcase as an extra enum.
        // Keep the public catalogue at its official 81 entries.
        const bool mixed_kongs = id == mahjong::CONCEALED_KONG_AND_MELDED_KONG;
        result.entries.push_back({mixed_kongs ? official_id(mahjong::TWO_MELDED_KONGS) : official_id(id),
            mixed_kongs ? "明暗杠" : mahjong::fan_name<>::text[id], points, table[id]});
        if (id != mahjong::FLOWER_TILES) result.fan_without_flowers += points * table[id];
    }
    // Official IDs differ from vendor enumeration order after the 2021 Kong remap.
    std::sort(result.entries.begin(), result.entries.end(), [](const auto& first, const auto& second) {
        return first.id < second.id;
    });
    result.total_fan = result.fan_without_flowers + input.flower_count;
    result.qualifies = result.fan_without_flowers >= 8;
    return result;
}

const std::vector<CompetitionMahjongFanEntry>& competitionMahjongFanCatalogue() {
    static const auto catalogue = [] {
        std::vector<CompetitionMahjongFanEntry> entries;
        entries.reserve(81);
        for (int id = 1; id <= mahjong::FLOWER_TILES; ++id) {
            entries.push_back({official_id(id), mahjong::fan_name<>::text[id], official_value(id), 1});
        }
        std::sort(entries.begin(), entries.end(), [](const auto& first, const auto& second) {
            return first.id < second.id;
        });
        return entries;
    }();
    return catalogue;
}

} // namespace mocs::engine
