#include "competition_mahjong_auto_session.hpp"

#include <algorithm>
#include <array>
#include <cassert>
#include <cstdint>
#include <cstdio>
#include <numeric>
#include <stdexcept>
#include <vector>

using namespace mocs::engine;

namespace {
constexpr std::array<std::uint8_t, 4> mixed_levels{0, 1, 2, 3};

std::uint32_t read32(const std::vector<std::uint8_t>& bytes, const std::size_t offset) {
    std::uint32_t value = 0;
    for (int i = 0; i < 4; ++i) value |= std::uint32_t(bytes.at(offset + i)) << (8 * i);
    return value;
}

void write32(std::vector<std::uint8_t>& bytes, const std::size_t offset,
             const std::uint32_t value) {
    for (int i = 0; i < 4; ++i) bytes.at(offset + i) = static_cast<std::uint8_t>(value >> (8 * i));
}

void refresh_crc(std::vector<std::uint8_t>& bytes) {
    std::uint32_t crc = 0xffffffffU;
    for (std::size_t i = 0; i < bytes.size() - 4; ++i) {
        crc ^= bytes[i];
        for (int bit = 0; bit < 8; ++bit)
            crc = (crc >> 1) ^ ((crc & 1) ? 0xedb88320U : 0U);
    }
    write32(bytes, bytes.size() - 4, ~crc);
}

CompetitionMahjongRound private_round(const CompetitionMahjongAutoSession& session) {
    const auto record = session.serialize();
    CompetitionMahjongRound round(competition_mahjong_shuffled_wall(1));
    assert(round.restore({record.begin() + 24, record.end() - 4}));
    return round;
}

void check_projection(const CompetitionMahjongAutoSession& session) {
    const auto view = session.snapshot();
    assert(view.viewer == session.viewer_seat());
    assert(std::accumulate(view.score_delta.begin(), view.score_delta.end(), std::int64_t{}) == 0);
    std::size_t allocated = view.wall_remaining;
    for (std::uint8_t seat = 0; seat < 4; ++seat) {
        const auto& player = view.seats[seat];
        if (seat == view.viewer) assert(player.local_concealed.size() == player.concealed_count);
        else assert(player.local_concealed.empty());
        allocated += player.concealed_count + player.flowers.size() + player.discards.size();
        for (const auto& meld : player.melds) {
            allocated += meld.kind == CompetitionMahjongMeldKind::kong ? 4 : 3;
            if (!meld.tile) assert(meld.kind == CompetitionMahjongMeldKind::kong && seat != view.viewer);
        }
    }
    assert(allocated == 144);
    if (view.drawn_tile) assert(view.current_player == view.viewer);
    if (view.phase != CompetitionMahjongRoundPhase::won) assert(view.winning_tiles.empty());
}

void invalid_setup_and_budgets() {
    for (const auto field : {0, 1, 2, 3}) {
        bool threw = false;
        try {
            auto levels = mixed_levels;
            if (field == 3) levels[2] = 4;
            CompetitionMahjongAutoSession session(344, field == 0 ? 4 : 0,
                field == 1 ? 4 : 0, field == 2 ? 4 : 0, levels, 11);
        } catch (const std::invalid_argument&) { threw = true; }
        assert(threw);
    }
    CompetitionMahjongAutoSession session(344, 0, 0, 0, mixed_levels, 11);
    const auto before = session.serialize();
    for (const std::uint32_t budget : {0U, 33U, 0xffffffffU}) {
        bool threw = false;
        try { (void)session.advance(budget); }
        catch (const std::invalid_argument&) { threw = true; }
        assert(threw && session.serialize() == before);
    }
    assert(!session.undo());
}

void viewer_is_automatic_and_private_changes_do_not_influence_it() {
    auto wall = competition_mahjong_shuffled_wall(344);
    auto changed = wall;
    std::size_t first_opponent = 4;
    while (first_opponent < 8 && changed[first_opponent] >= 34) ++first_opponent;
    std::size_t other = 8;
    while (other < 12 && (changed[other] >= 34 || changed[first_opponent] == changed[other])) ++other;
    assert(first_opponent < 8 && other < 12);
    std::swap(changed[first_opponent], changed[other]); // Different opponents, identical observer frame.
    CompetitionMahjongAutoSession original(wall, 0, 0, 0, mixed_levels, 9821);
    CompetitionMahjongAutoSession altered(changed, 0, 0, 0, mixed_levels, 9821);
    assert(original.snapshot().seats[0].local_concealed == altered.snapshot().seats[0].local_concealed);
    assert(original.advance(1).accepted_actions == 1);
    assert(altered.advance(1).accepted_actions == 1);
    const auto first = original.serialize();
    const auto second = altered.serialize();
    constexpr std::size_t first_event = 24 + 155;
    assert(read32(first, 24 + 151) == 1 && read32(second, 24 + 151) == 1);
    assert(first.at(first_event + 1) == 0); // Observer's decision did not stop the driver.
    assert(std::equal(first.begin() + first_event, first.begin() + first_event + 4,
                      second.begin() + first_event));
    check_projection(original);
}

void observer_selection_does_not_change_the_game() {
    CompetitionMahjongAutoSession reference(344, 0, 0, 0, mixed_levels, 55);
    const auto reference_advance = reference.advance(32);
    const auto record = reference.serialize();
    const std::vector<std::uint8_t> payload(record.begin() + 24, record.end() - 4);
    for (std::uint8_t viewer = 1; viewer < 4; ++viewer) {
        CompetitionMahjongAutoSession session(344, 0, 0, viewer, mixed_levels, 55);
        const auto advanced = session.advance(32);
        assert(advanced.accepted_actions == reference_advance.accepted_actions);
        assert(advanced.terminal == reference_advance.terminal);
        const auto other = session.serialize();
        assert(std::vector<std::uint8_t>(other.begin() + 24, other.end() - 4) == payload);
        check_projection(session);
    }
}

void all_four_seats_are_driven() {
    CompetitionMahjongAutoSession session(344, 0, 0, 0, {0, 0, 0, 0}, 123);
    const auto advanced = session.advance(32);
    assert(advanced.accepted_actions == 32 && !advanced.terminal);
    const auto bytes = session.serialize();
    assert(read32(bytes, 24 + 151) == 32);
    std::array<bool, 4> acted{};
    for (std::size_t event = 0; event < 32; ++event)
        acted.at(bytes.at(24 + 155 + 4 * event + 1)) = true;
    assert(std::all_of(acted.begin(), acted.end(), [](const bool value) { return value; }));
    check_projection(session);
}

void batching_restore_and_undo_are_deterministic() {
    CompetitionMahjongAutoSession single(344, 0, 2, 3, mixed_levels, 87);
    CompetitionMahjongAutoSession batch(344, 0, 2, 3, mixed_levels, 87);
    const auto advanced = batch.advance(32);
    for (std::uint32_t i = 0; i < advanced.accepted_actions; ++i)
        assert(single.advance(1).accepted_actions == 1);
    assert(single.serialize() == batch.serialize());
    const auto saved = single.serialize();
    CompetitionMahjongAutoSession restored(999, 3, 3, 3, mixed_levels, 87);
    assert(restored.restore(saved) && restored.serialize() == saved);
    assert(single.advance(17).accepted_actions == restored.advance(17).accepted_actions);
    assert(single.serialize() == restored.serialize());
    const auto after = single.serialize();
    assert(single.undo());
    assert(single.advance(1).accepted_actions == 1);
    assert(single.serialize() == after);
}

void rejection_preserves_the_existing_round() {
    CompetitionMahjongAutoSession session(344, 0, 0, 2, mixed_levels, 9821);
    assert(session.advance(3).accepted_actions == 3);
    const auto good = session.serialize();
    const auto reject = [&](const std::vector<std::uint8_t>& bad) {
        assert(!session.restore(bad));
        assert(session.serialize() == good);
    };
    reject({});
    reject(std::vector<std::uint8_t>(27, 0));
    reject(std::vector<std::uint8_t>(1024 * 1024 + 1, 0));
    for (const auto offset : {0, 4, 5, 6, 7, 8, 9, 10, 11, 12, 20}) {
        auto bad = good;
        bad.at(offset) ^= 1;
        refresh_crc(bad);
        reject(bad);
    }
    auto bad = good;
    bad.back() ^= 1;
    reject(bad);
    bad = good;
    bad.push_back(0);
    reject(bad);
    bad = good;
    bad.pop_back();
    reject(bad);
    bad = good;
    bad.at(24 + 4) = 2; // Valid outer CRC cannot authorize an unknown inner version.
    refresh_crc(bad);
    reject(bad);
    // A repaired MOCM checksum and MCMA CRC still cannot authorize an illegal event.
    bad = good;
    bad.at(24 + 155) = 255;
    std::uint32_t fnv = 2166136261U;
    for (std::size_t i = 24; i < bad.size() - 8; ++i) fnv = (fnv ^ bad[i]) * 16777619U;
    write32(bad, bad.size() - 8, fnv);
    refresh_crc(bad);
    reject(bad);
    assert(session.restore(good));
}

void complete_games_and_restore_terminal_frames() {
    for (std::uint8_t viewer = 0; viewer < 4; ++viewer) {
        CompetitionMahjongAutoSession session(4000 + viewer, viewer, viewer, viewer,
                                              mixed_levels, 7000 + viewer);
        bool finished = false;
        for (int batch = 0; batch < 128; ++batch) {
            const auto advanced = session.advance(32);
            assert(advanced.accepted_actions > 0 && advanced.accepted_actions <= 32);
            check_projection(session);
            if (advanced.terminal) { finished = true; break; }
        }
        assert(finished);
        const auto saved = session.serialize();
        const auto stopped = session.advance(32);
        assert(stopped.accepted_actions == 0 && stopped.terminal && session.serialize() == saved);
        CompetitionMahjongAutoSession restored(1, 0, 0, viewer, mixed_levels, 7000 + viewer);
        assert(restored.restore(saved));
        assert(restored.serialize() == saved);
        assert(restored.snapshot().phase == session.snapshot().phase);
        assert(restored.snapshot().winning_tiles == session.snapshot().winning_tiles);
        assert(restored.snapshot().score_delta == session.snapshot().score_delta);
        assert(private_round(session).next_dealer() == (viewer + 1) % 4);
        assert(session.undo());
        assert(session.advance(1).terminal && session.serialize() == saved);
    }
}
} // namespace

int main() {
    invalid_setup_and_budgets();
    viewer_is_automatic_and_private_changes_do_not_influence_it();
    observer_selection_does_not_change_the_game();
    all_four_seats_are_driven();
    batching_restore_and_undo_are_deterministic();
    rejection_preserves_the_existing_round();
    complete_games_and_restore_terminal_frames();
    std::puts("automatic Mahjong: 7 groups passed, including 4 complete games");
    return 0;
}
