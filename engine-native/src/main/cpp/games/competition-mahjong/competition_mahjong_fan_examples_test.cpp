#include "mocs/engine/competition_mahjong_fan.hpp"

#include <algorithm>
#include <array>
#include <cstdio>
#include <initializer_list>
#include <string>
#include <utility>
#include <vector>

using namespace mocs::engine;

namespace {

using Pack = CompetitionMahjongFanPack;
using Kind = CompetitionMahjongFanPackKind;

Pack chow(int tile) { return {Kind::chow, tile, 1, false}; }
Pack pung(int tile) { return {Kind::pung, tile, 1, false}; }
Pack kong(int tile, int offer = 1) { return {Kind::kong, tile, offer, false}; }

CompetitionMahjongFanInput exposed(std::initializer_list<Pack> packs, int pair) {
    CompetitionMahjongFanInput input{};
    input.packs = packs;
    input.concealed_tiles = {pair};
    input.win_tile = pair;
    return input;
}

CompetitionMahjongFanInput closed(std::initializer_list<int> complete, int win,
                                 bool self_drawn = false) {
    CompetitionMahjongFanInput input{};
    input.concealed_tiles = complete;
    const auto winning = std::find(input.concealed_tiles.begin(), input.concealed_tiles.end(), win);
    if (winning == input.concealed_tiles.end()) {
        std::fprintf(stderr, "Invalid fixture: winning tile not in complete hand\n");
        return input;
    }
    input.concealed_tiles.erase(winning);
    input.win_tile = win;
    input.self_drawn = self_drawn;
    return input;
}

CompetitionMahjongFanInput partial(std::initializer_list<Pack> packs,
                                  std::initializer_list<int> complete, int win,
                                  bool self_drawn = false) {
    auto input = closed(complete, win, self_drawn);
    input.packs = packs;
    return input;
}

enum Context { self_draw = 1, last = 2, replacement = 4, fourth = 8, robbing = 16 };

CompetitionMahjongFanInput context(CompetitionMahjongFanInput input, int flags,
                                  int flowers = 0, int seat_wind = 0, int round_wind = 0) {
    input.self_drawn = (flags & self_draw) != 0;
    input.last_tile = (flags & last) != 0;
    input.replacement = (flags & replacement) != 0;
    input.fourth_tile = (flags & fourth) != 0;
    input.robbing_kong = (flags & robbing) != 0;
    input.flower_count = flowers;
    input.seat_wind = seat_wind;
    input.round_wind = round_wind;
    return input;
}

CompetitionMahjongFanInput chicken() {
    // Three suit ranges, only a wind pair, one non-terminal pung, and an open-ended wait.
    return partial({chow(0), chow(12)}, {23,24,25,6,6,6,30,30}, 23);
}

struct Example {
    int id;
    const char* name;
    int points;
    CompetitionMahjongFanInput input;
    std::vector<int> excluded;
    bool expected_present{true};
};

// Own tile arrangements, based on WMO definitions; source notes are in the game documents.
std::vector<Example> examples() {
    return {
        {1, "大四喜", 88, exposed({pung(27), pung(28), pung(29), pung(30)}, 4),
            {38, 49, 60, 61, 73}},
        {2, "大三元", 88, exposed({pung(31), pung(32), pung(33), chow(0)}, 18), {54, 59}},
        {3, "绿一色", 88, exposed({chow(10), pung(14), pung(16), pung(32)}, 11), {}},
        {4, "九莲宝灯", 88, closed({0,0,0,1,2,3,4,4,5,6,7,8,8,8}, 4), {22}},
        {5, "四杠", 88, exposed({kong(0), kong(9), kong(18), kong(31)}, 4), {17, 57, 74}},
        {6, "连七对", 88, closed({0,0,1,1,2,2,3,3,4,4,5,5,6,6}, 6), {19,22,62,75,76,79}},
        {7, "十三幺", 88, closed({0,0,8,9,17,18,26,27,28,29,30,31,32,33}, 0), {18,52,62,79}},
        {8, "清幺九", 64, exposed({pung(0), pung(8), pung(9), pung(26)}, 18), {49,55,73,76}},
        {9, "小四喜", 64, exposed({pung(27), pung(28), pung(29), chow(0)}, 30), {38}},
        {10, "小三元", 64, exposed({pung(31), pung(32), chow(0), chow(9)}, 33), {54,59}},
        {11, "字一色", 64, exposed({pung(27), pung(28), pung(31), pung(32)}, 30), {49,55,73}},
        {12, "四暗刻", 64, closed({1,1,1,13,13,13,25,25,25,31,31,31,27,27}, 27, true),
            {33,49,62,66,80}},
        {13, "一色双龙会", 64, exposed({chow(0), chow(0), chow(6), chow(6)}, 4),
            {19,22,63,69,72,76}},
        {14, "一色四同顺", 48, exposed({chow(1), chow(1), chow(1), chow(1)}, 20), {23,24,64,69}},
        {15, "一色四节高", 48, exposed({pung(1), pung(2), pung(3), pung(4)}, 14), {23,24,49}},
        {16, "一色四步高", 32, exposed({chow(0), chow(1), chow(2), chow(3)}, 14), {30,71,72}},
        {17, "三杠", 32, exposed({kong(1), kong(12), kong(23), chow(6)}, 27), {57,74}},
        {18, "混幺九", 32, exposed({pung(0), pung(17), pung(27), pung(31)}, 26), {49,55,73}},
        {19, "七对", 24, closed({0,0,4,4,9,9,14,14,21,21,27,27,31,31}, 31), {62,79}},
        {20, "七星不靠", 24, closed({0,3,6,10,13,20,23,27,28,29,30,31,32,33}, 33),
            {34,52,62,79}},
        {21, "全双刻", 24, exposed({pung(1), pung(12), pung(23), pung(7)}, 14), {49,68,76}},
        {22, "清一色", 24, exposed({chow(0), chow(3), chow(6), pung(4)}, 7), {75,76}},
        {23, "一色三同顺", 24, exposed({chow(1), chow(1), chow(1), chow(13)}, 24), {24,69}},
        {24, "一色三节高", 24, exposed({pung(1), pung(2), pung(3), chow(13)}, 24), {23}},
        {25, "全大", 24, exposed({chow(6), chow(15), chow(24), pung(7)}, 25), {36,76}},
        {26, "全中", 24, exposed({chow(3), chow(12), chow(21), pung(13)}, 22), {68,76}},
        {27, "全小", 24, exposed({chow(0), chow(9), chow(18), pung(1)}, 19), {37,76}},
        {28, "清龙", 16, exposed({chow(0), chow(3), chow(6), pung(31)}, 27), {71,72}},
        {29, "三色双龙会", 16, exposed({chow(0), chow(6), chow(9), chow(15)}, 22), {63,70,72,76}},
        {30, "一色三步高", 16, exposed({chow(1), chow(2), chow(3), pung(31)}, 25), {}},
        {31, "全带五", 16, exposed({chow(2), chow(12), chow(22), pung(4)}, 13), {68}},
        {32, "三同刻", 16, exposed({pung(2), pung(11), pung(20), chow(6)}, 13), {65}},
        {33, "三暗刻", 16, closed({1,1,1,13,13,13,25,25,25,0,1,2,27,27}, 27), {66}},
        {34, "全不靠", 12, closed({0,3,6,10,13,16,20,23,27,28,29,30,31,32}, 32), {52,62,79}},
        {35, "组合龙", 12, closed({0,3,6,10,13,16,20,23,26,27,27,27,31,31}, 31), {}},
        {36, "大于五", 12, exposed({chow(5), chow(14), chow(23), pung(7)}, 24), {76}},
        {37, "小于五", 12, exposed({chow(1), chow(10), chow(19), pung(0)}, 21), {76}},
        {38, "三风刻", 12, exposed({pung(27), pung(28), pung(29), chow(12)}, 24), {73}},
        {39, "花龙", 8, exposed({chow(0), chow(12), chow(24), pung(31)}, 28), {}},
        {40, "推不倒", 8, exposed({chow(18), chow(18), pung(13), pung(33)}, 17), {75}},
        {41, "三色三同顺", 8, exposed({chow(1), chow(10), chow(19), pung(31)}, 27), {}},
        {42, "三色三节高", 8, exposed({pung(1), pung(11), pung(21), chow(6)}, 27), {}},
        {43, "无番和", 8, chicken(), {}},
        {44, "妙手回春", 8, context(chicken(), self_draw | last), {45,80}},
        {45, "海底捞月", 8, context(chicken(), last), {44}},
        {46, "杠上开花", 8, context(partial({kong(1)},
            {9,10,11,21,22,23,25,25,25,27,27}, 27), self_draw | replacement), {80}},
        {47, "抢杠和", 8, context(chicken(), robbing | fourth), {58}},
        {48, "双暗杠", 8, partial({kong(1,0), kong(13,0)},
            {18,19,20,31,31,31,27,27}, 27), {66,67}},
        {49, "碰碰和", 6, exposed({pung(1), pung(12), pung(25), pung(31)}, 27), {}},
        {50, "混一色", 6, exposed({chow(0), chow(3), pung(7), pung(31)}, 27), {75}},
        {51, "三色三步高", 6, exposed({chow(1), chow(11), chow(21), pung(31)}, 27), {}},
        {52, "五门齐", 6, exposed({chow(0), chow(12), chow(24), pung(27)}, 31), {}},
        {53, "全求人", 6, exposed({chow(0), chow(13), pung(25), pung(31)}, 28), {79}},
        {54, "双箭刻", 6, exposed({pung(31), pung(32), chow(0), chow(12)}, 23), {59}},
        {55, "全带幺", 4, exposed({chow(0), chow(15), pung(18), pung(31)}, 28), {}},
        {56, "不求人", 4, closed({0,1,2,9,10,11,21,22,23,24,25,26,27,27}, 22, true), {62,80}},
        {57, "双明杠", 4, exposed({kong(1), kong(12), chow(21), chow(6)}, 27), {74}},
        {58, "和绝张", 4, context(chicken(), fourth), {}},
        {59, "箭刻", 2, exposed({pung(31), chow(0), chow(12), chow(24)}, 27), {}},
        {60, "圈风刻", 2, context(exposed({pung(27), chow(0), chow(12), chow(24)}, 32), 0, 0, 1, 0), {}},
        {61, "门风刻", 2, context(exposed({pung(28), chow(0), chow(12), chow(24)}, 32), 0, 0, 1, 0), {}},
        {62, "门前清", 2, closed({0,1,2,9,10,11,21,22,23,24,25,26,27,27}, 22), {56}},
        {63, "平和", 2, exposed({chow(0), chow(12), chow(24), chow(2)}, 13), {76}},
        {64, "四归一", 2, partial({pung(1), chow(12), chow(21)}, {0,1,2,27,27}, 1), {}},
        {65, "双同刻", 2, exposed({pung(1), pung(10), chow(21), chow(6)}, 27), {}},
        {66, "双暗刻", 2, partial({chow(0), chow(12)}, {21,21,21,25,25,25,27,27}, 27), {}},
        {67, "暗杠", 2, partial({kong(4,0)}, {0,1,2,9,10,11,21,22,23,27,27}, 27), {74}},
        {68, "断幺", 2, exposed({chow(1), chow(12), chow(20), pung(7)}, 14), {76}},
        {69, "一般高", 1, exposed({chow(1), chow(1), chow(12), pung(31)}, 27), {}},
        {70, "喜相逢", 1, exposed({chow(0), chow(9), chow(21), pung(31)}, 27), {}},
        {71, "连六", 1, exposed({chow(0), chow(3), chow(13), pung(31)}, 27), {}},
        {72, "老少副", 1, exposed({chow(0), chow(6), chow(13), pung(31)}, 27), {}},
        {73, "幺九刻", 1, exposed({pung(0), chow(10), chow(21), chow(5)}, 27), {}},
        {74, "明杠", 1, exposed({kong(4), chow(0), chow(12), chow(24)}, 27), {67}},
        {75, "缺一门", 1, exposed({chow(0), chow(3), chow(13), pung(31)}, 27), {}},
        {76, "无字", 1, exposed({pung(1), chow(0), chow(13), chow(24)}, 23), {}},
        {77, "独听・边张", 1, partial({pung(31), chow(12), chow(24)}, {0,1,2,27,27}, 2), {78,79}},
        {78, "独听・嵌张", 1, partial({pung(31), chow(12), chow(24)}, {3,4,5,27,27}, 4), {77,79}},
        {79, "独听・单钓", 1, partial({chow(12), chow(24), pung(31)}, {0,1,2,27,27}, 27), {77,78}},
        {80, "自摸", 1, context(exposed({chow(0), chow(12), chow(24), pung(31)}, 27), self_draw), {56}},
        {81, "花牌", 1, context(closed({0,0,4,4,9,9,14,14,21,21,27,27,31,31}, 31), 0, 1), {}},
    };
}

bool require(bool condition, const Example& example, const char* message) {
    if (!condition) std::fprintf(stderr, "fan %d %s: %s\n", example.id, example.name, message);
    return condition;
}

bool valid_physical_input(const Example& example) {
    const auto& input = example.input;
    bool okay = require(input.packs.size() <= 4, example, "more than four fixed melds");
    okay &= require(input.concealed_tiles.size() + 3 * input.packs.size() == 13,
                    example, "concealed/meld counts do not represent a ready hand");
    std::array<int, 34> counts{};
    auto count = [&](int tile, int copies) {
        if (!require(tile >= 0 && tile < 34, example, "invalid tile code")) { okay = false; return; }
        counts[static_cast<std::size_t>(tile)] += copies;
    };
    count(input.win_tile, 1);
    for (int tile : input.concealed_tiles) count(tile, 1);
    for (const auto& pack : input.packs) {
        if (pack.kind == Kind::chow) {
            okay &= require(pack.tile >= 0 && pack.tile < 27 && pack.tile % 9 <= 6,
                            example, "invalid chow boundary");
            for (int offset = 0; offset < 3; ++offset) count(pack.tile + offset, 1);
        } else count(pack.tile, pack.kind == Kind::kong ? 4 : 3);
    }
    okay &= require(std::all_of(counts.begin(), counts.end(), [](int n) { return n <= 4; }),
                    example, "fixture uses a fifth physical tile");
    return okay;
}

bool check(const Example& example) {
    if (!valid_physical_input(example)) return false;
    const auto result = evaluateCompetitionMahjongFan(example.input);
    bool okay = require(result.error == CompetitionMahjongFanError::none && result.structure_valid,
                        example, "not a valid winning structure");
    const auto target = std::find_if(result.entries.begin(), result.entries.end(), [&](const auto& entry) {
        return entry.id == example.id;
    });
    okay &= require((target != result.entries.end()) == example.expected_present,
                    example, "fan presence differs from the required result");
    if (example.expected_present && target != result.entries.end()) {
        okay &= require(target->points == example.points && target->count == 1,
                        example, "fan value/count differs from the official example");
        okay &= require(target->name == example.name, example, "public ID/name mismatch");
    }
    for (int id : example.excluded) {
        const bool found = std::any_of(result.entries.begin(), result.entries.end(),
                                     [id](const auto& entry) { return entry.id == id; });
        if (found) std::fprintf(stderr, "fan %d %s: excluded ID %d was counted\n", example.id, example.name, id);
        okay &= !found;
    }
    int total = 0;
    int non_flowers = 0;
    int previous = 0;
    for (const auto& entry : result.entries) {
        okay &= require(entry.id > previous && entry.id <= 81 && entry.points > 0 && entry.count > 0,
                        example, "invalid or duplicate fan output entry");
        previous = entry.id;
        total += entry.points * entry.count;
        if (entry.id != 81) non_flowers += entry.points * entry.count;
    }
    okay &= require(total == result.total_fan && non_flowers == result.fan_without_flowers,
                    example, "reported totals differ from the awarded entries");
    okay &= require(result.qualifies == (non_flowers >= 8), example, "flower-independent threshold mismatch");
    if (!okay) {
        std::fprintf(stderr, "  actual total %d, non-flowers %d\n", result.total_fan, result.fan_without_flowers);
        for (const auto& entry : result.entries)
            std::fprintf(stderr, "  %d %s %d x %d\n", entry.id, entry.name.c_str(), entry.points, entry.count);
    }
    return okay;
}

std::vector<Example> counterexamples(const std::vector<Example>& positive) {
    std::vector<Example> cases;
    auto changed = [&](int id, CompetitionMahjongFanInput input) {
        auto example = positive.at(static_cast<std::size_t>(id - 1));
        example.input = std::move(input);
        example.excluded.clear();
        example.expected_present = false;
        cases.push_back(std::move(example));
    };
    auto input = [&](int id) { return positive.at(static_cast<std::size_t>(id - 1)).input; };

    auto not_four_winds = input(1);
    not_four_winds.packs[3] = chow(12);
    changed(1, not_four_winds);
    auto not_three_dragons = input(2);
    not_three_dragons.packs[2] = chow(12);
    changed(2, not_three_dragons);
    auto not_three_kongs = input(17);
    not_three_kongs.packs[0].kind = Kind::pung;
    changed(17, not_three_kongs);
    changed(44, context(input(44), self_draw));
    changed(45, context(input(45), 0));
    changed(46, context(input(46), self_draw));
    changed(47, context(input(47), fourth));
    changed(53, context(input(53), self_draw));
    changed(56, context(input(56), 0));
    changed(58, context(input(58), 0));
    changed(60, context(input(60), 0, 0, 1, 1));
    changed(61, context(input(61), 0, 0, 3, 0));
    changed(62, context(input(62), self_draw));
    auto not_tile_hog = input(64);
    not_tile_hog.packs[0] = pung(6);
    changed(64, not_tile_hog);
    auto not_concealed_kong = input(67);
    not_concealed_kong.packs[0].offer = 1;
    changed(67, not_concealed_kong);
    auto not_simples = input(68);
    not_simples.concealed_tiles = {27};
    not_simples.win_tile = 27;
    changed(68, not_simples);
    auto not_melded_kong = input(74);
    not_melded_kong.packs[0].offer = 0;
    changed(74, not_melded_kong);
    changed(77, partial({pung(31), chow(12), chow(24)}, {3,4,5,27,27}, 5));
    changed(78, partial({pung(31), chow(12), chow(24)}, {3,4,5,27,27}, 5));
    changed(79, partial({chow(12), chow(24), pung(31)}, {4,5,6,27,27}, 5));
    changed(81, context(input(81), 0));
    return cases;
}

int check_thresholds() {
    int failures = 0;
    // Three fixed pungs plus one concealed pung; only All Pungs (6) and Single Wait (1).
    const auto seven = partial({pung(1), pung(13), pung(25)}, {6,6,6,28,28}, 28);
    // Replacing the 7-character pung with 9-character adds exactly one Terminal Pung point.
    const auto eight = partial({pung(1), pung(13), pung(25)}, {8,8,8,28,28}, 28);
    for (int flowers = 0; flowers <= 8; ++flowers) {
        const auto below = evaluateCompetitionMahjongFan(context(seven, 0, flowers));
        const auto enough = evaluateCompetitionMahjongFan(context(eight, 0, flowers));
        if (below.error != CompetitionMahjongFanError::none || !below.structure_valid ||
            below.fan_without_flowers != 7 || below.total_fan != 7 + flowers || below.qualifies) {
            std::fprintf(stderr, "7-point boundary failed with %d flowers (actual %d/%d)\n",
                         flowers, below.fan_without_flowers, below.total_fan);
            ++failures;
        }
        if (enough.error != CompetitionMahjongFanError::none || !enough.structure_valid ||
            enough.fan_without_flowers != 8 || enough.total_fan != 8 + flowers || !enough.qualifies) {
            std::fprintf(stderr, "8-point boundary failed with %d flowers (actual %d/%d)\n",
                         flowers, enough.fan_without_flowers, enough.total_fan);
            ++failures;
        }
    }
    return failures;
}

} // namespace

int main() {
    const auto cases = examples();
    std::array<bool, 82> covered{};
    int failures = 0;
    for (const auto& example : cases) {
        if (example.id < 1 || example.id > 81 || covered[static_cast<std::size_t>(example.id)]) {
            std::fprintf(stderr, "duplicate or invalid case ID %d\n", example.id);
            ++failures;
            continue;
        }
        covered[static_cast<std::size_t>(example.id)] = true;
        if (!check(example)) ++failures;
    }
    for (int id = 1; id <= 81; ++id) {
        if (!covered[static_cast<std::size_t>(id)]) {
            std::fprintf(stderr, "missing requested fan case %d\n", id);
            ++failures;
        }
    }
    const auto negative = counterexamples(cases);
    for (const auto& example : negative) if (!check(example)) ++failures;
    failures += check_thresholds();
    std::printf("WMO fan examples: %zu positive, %zu negative, 18 threshold cases, %d failures\n",
                cases.size(), negative.size(), failures);
    return failures == 0 ? 0 : 1;
}
