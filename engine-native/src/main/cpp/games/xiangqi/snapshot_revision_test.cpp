#include "mocs/engine/chinese_chess.hpp"

#include <array>
#include <cstdint>
#include <cstdio>
#include <stdexcept>
#include <vector>

namespace {
using namespace mocs::engine;

void require(bool condition, const char* message) {
    if (!condition) throw std::runtime_error(message);
}

void seal(std::vector<std::uint8_t>& payload) {
    std::uint32_t crc = 0xffffffffU;
    for (const auto byte : payload) {
        crc ^= byte;
        for (int bit = 0; bit < 8; ++bit) {
            crc = (crc >> 1U) ^ ((crc & 1U) ? 0xedb88320U : 0U);
        }
    }
    crc = ~crc;
    for (unsigned shift = 0; shift < 32; shift += 8) {
        payload.push_back(static_cast<std::uint8_t>(crc >> shift));
    }
}

std::vector<std::uint8_t> versioned(const std::vector<std::uint8_t>& v3) {
    require(v3[4] == 3, "fixture must be actual v3");
    auto bytes = v3;
    bytes.resize(bytes.size() - 4);
    bytes[4] = 4;
    bytes.push_back(0);
    seal(bytes);
    return bytes;
}

std::vector<std::uint8_t> legacy_v3(const std::vector<std::uint8_t>& current) {
    require(current[4] == 4, "fixture must be actual v4");
    auto bytes = current;
    bytes.resize(bytes.size() - 5);
    bytes[4] = 3;
    seal(bytes);
    return bytes;
}

std::vector<std::uint8_t> legacy_v2(const std::vector<std::uint8_t>& v3) {
    require(v3.size() >= 108 && v3[4] == 3, "v2 conversion requires v3");
    const auto history_size = static_cast<std::size_t>(v3[10]) |
        (static_cast<std::size_t>(v3[11]) << 8U);
    const auto records_end = 102U + 11U * history_size;
    require(v3.size() == records_end + 6U &&
                v3[records_end] == 0 && v3[records_end + 1U] == 0,
            "v2 cannot represent claim events");
    // Preserve the real board, counters and every move; v2 has no claim table.
    auto bytes = v3;
    bytes.resize(records_end);
    bytes[4] = 2;
    seal(bytes);
    return bytes;
}

ChineseChessEngine legacy_cycle_fixture(const bool rotated) {
    struct Placement { int x; int y; PieceType type; Side side; };
    constexpr Placement pieces[]{
        {5, 7, PieceType::general, Side::red}, {4, 1, PieceType::general, Side::black},
        {2, 3, PieceType::soldier, Side::red}, {4, 4, PieceType::soldier, Side::red},
        {3, 4, PieceType::horse, Side::red}, {5, 4, PieceType::horse, Side::red},
        {6, 3, PieceType::cannon, Side::red}, {3, 3, PieceType::cannon, Side::red},
        {3, 2, PieceType::advisor, Side::black}, {2, 0, PieceType::elephant, Side::black},
    };
    std::vector<std::uint8_t> bytes(102, 0);
    bytes[0] = 'M'; bytes[1] = 'O'; bytes[2] = 'C'; bytes[3] = 'X';
    bytes[4] = 3;
    bytes[6] = rotated ? 1 : 0;
    for (const auto& piece : pieces) {
        const int x = rotated ? 8 - piece.x : piece.x;
        const int y = rotated ? 9 - piece.y : piece.y;
        const bool black = (piece.side == Side::black) != rotated;
        bytes[static_cast<std::size_t>(12 + 9 * y + x)] =
            static_cast<std::uint8_t>(piece.type) | (black ? 0x80U : 0U);
    }
    bytes.push_back(0);
    bytes.push_back(0);
    seal(bytes);
    ChineseChessEngine engine;
    require(engine.restore(bytes).restored && engine.serialize() == bytes,
            "legacy cycle initial position rejected or upgraded");
    return engine;
}

std::array<EngineAction, 4> checking_cycle(const bool rotated) {
    std::array<EngineAction, 4> actions{
        make_board_move(3, 3, 4, 3), make_board_move(4, 1, 3, 1),
        make_board_move(4, 3, 3, 3), make_board_move(3, 1, 4, 1),
    };
    if (rotated) for (auto& action : actions) {
        action.arguments[0] = 8 - action.arguments[0];
        action.arguments[1] = 9 - action.arguments[1];
        action.arguments[2] = 8 - action.arguments[2];
        action.arguments[3] = 9 - action.arguments[3];
    }
    return actions;
}

void legacy_v2_rule_selection(const bool rotated) {
    const auto actions = checking_cycle(rotated);
    auto original = legacy_cycle_fixture(rotated);
    const auto initial_v3 = original.serialize();
    for (const auto& action : actions)
        require(original.apply(action).accepted, "legacy first cycle rejected");
    require(original.game_result() == GameResult::ongoing, "first cycle already terminal");
    const auto live_v3 = original.serialize();
    require(live_v3[10] == 4 && live_v3[11] == 0, "real four-move history missing");

    ChineseChessEngine migrated;
    require(migrated.restore(legacy_v2(live_v3)).restored, "v2 history restore rejected");
    require(migrated.serialize() == live_v3 && migrated.game_result() == GameResult::ongoing,
            "v2 history did not retain original v3 judge and bytes");
    ChineseChessEngine reopened;
    require(reopened.restore(migrated.serialize()).restored && reopened.serialize() == live_v3,
            "reopening migrated live history changed its judge");
    for (const auto& action : actions)
        require(reopened.apply(action).accepted, "migrated second cycle rejected");
    require(reopened.game_result() == GameResult::draw,
            "v2 history was retroactively adjudicated under current rules");
    const auto terminal_v3 = reopened.serialize();
    require(terminal_v3[4] == 3, "migrated terminal did not stay v3");
    require(reopened.undo() && reopened.game_result() == GameResult::ongoing,
            "migrated terminal undo failed");
    require(reopened.serialize()[4] == 3, "undo upgraded migrated history");
    require(reopened.apply(actions.back()).accepted && reopened.serialize() == terminal_v3,
            "migrated terminal replay changed its judge or bytes");
    ChineseChessEngine terminal_reopened;
    require(terminal_reopened.restore(terminal_v3).restored &&
                terminal_reopened.serialize() == terminal_v3 &&
                terminal_reopened.game_result() == GameResult::draw,
            "reopening migrated terminal retroactively changed old verdict");

    // The same custom board without a move history explicitly starts a new judge.
    ChineseChessEngine fresh;
    require(fresh.restore(legacy_v2(initial_v3)).restored, "v2 empty history rejected");
    const auto current = fresh.serialize();
    require(current[4] == 4 && current[current.size() - 5] == 1 &&
                current[10] == 0 && current[11] == 0,
            "v2 empty history did not select current revision");
    for (int round = 0; round < 2; ++round) {
        for (const auto& action : actions)
            require(fresh.apply(action).accepted, "current cycle from v2 setup rejected");
        if (round == 0)
            require(fresh.game_result() == GameResult::ongoing, "new first cycle already terminal");
    }
    const auto expected = rotated ? GameResult::first_player_win : GameResult::second_player_win;
    require(fresh.game_result() == expected, "v2 empty history failed current continuous-kill verdict");
    const auto current_terminal = fresh.serialize();
    require(current_terminal[4] == 4 && current_terminal[current_terminal.size() - 5] == 1,
            "new cycle lost its current revision");
    terminal_reopened.reset();
    const auto reset = terminal_reopened.serialize();
    require(reset[4] == 4 && reset[reset.size() - 5] == 1 &&
                terminal_reopened.game_result() == GameResult::ongoing,
            "reset of migrated legacy game did not start current revision");
    fresh.reset();
    require(fresh.serialize() == reset, "reset of current v2 setup differs from a new standard game");
    std::printf("snapshot-revision v2 attacker=%s history->v3-draw empty->v4.1-loss undo/reopen/reset passed\n",
                rotated ? "black" : "red");
}

void rejected_atomically(ChineseChessEngine& engine, std::vector<std::uint8_t> payload,
                         EngineError expected) {
    seal(payload);
    const auto before = engine.serialize();
    const auto result = engine.restore(payload);
    require(!result.restored && result.error == expected, "wrong rejection");
    require(engine.serialize() == before, "failed restore changed live game");
}

void ongoing_and_terminal_round_trip() {
    ChineseChessEngine engine;
    const auto current_initial = engine.serialize();
    require(current_initial[4] == 4 && current_initial[current_initial.size() - 5] == 1,
            "new game did not select current revision");
    const auto v3_initial = legacy_v3(current_initial);
    const auto v4_initial = versioned(v3_initial);
    require(engine.restore(v4_initial).restored, "v4 initial restore rejected");
    require(engine.serialize() == v4_initial, "v4 downgraded or mutated");
    require(engine.apply(make_board_move(0, 6, 0, 5)).accepted, "v4 legal move rejected");
    const auto ongoing = engine.serialize();
    require(ongoing[4] == 4 && ongoing[ongoing.size() - 5] == 0, "revision marker lost");
    ChineseChessEngine reopened;
    require(reopened.restore(ongoing).restored, "v4 ongoing restore rejected");
    require(reopened.serialize() == ongoing, "v4 ongoing bytes differ");
    require(reopened.undo() && reopened.serialize() == v4_initial, "v4 undo differs");
    reopened.reset();
    require(reopened.serialize() == current_initial, "new game did not reset to current default");

    engine.reset();
    const auto last = make_board_move(2, 2, 1, 0);
    for (int cycle = 0; cycle < 2; ++cycle) {
        for (const auto& action : {make_board_move(1, 9, 2, 7), make_board_move(1, 0, 2, 2),
                                  make_board_move(2, 7, 1, 9), last}) {
            require(engine.apply(action).accepted, "quiet cycle rejected");
        }
    }
    require(engine.game_result() == GameResult::draw, "quiet cycle no longer draws");
    const auto v3_terminal = legacy_v3(engine.serialize());
    require(v3_terminal[4] == 3, "existing terminal format changed");
    require(reopened.restore(v3_terminal).restored && reopened.serialize() == v3_terminal,
            "v3 terminal no longer round trips");
    const auto v4_terminal = versioned(v3_terminal);
    require(reopened.restore(v4_terminal).restored && reopened.serialize() == v4_terminal,
            "v4 terminal no longer round trips");
    require(reopened.game_result() == GameResult::draw, "old terminal result changed");
    require(reopened.undo() && reopened.game_result() == GameResult::ongoing, "terminal undo failed");
    require(reopened.apply(last).accepted && reopened.serialize() == v4_terminal, "terminal replay differs");
    std::puts("snapshot-revision ongoing/terminal/undo/default passed");
}

void malformed_and_unknown_revisions_do_not_mutate() {
    ChineseChessEngine engine;
    require(engine.apply(make_board_move(0, 6, 0, 5)).accepted, "initial live move rejected");
    const auto valid = versioned(legacy_v3(engine.serialize()));
    for (const auto revision : {2, 255}) {
        auto payload = valid;
        payload.resize(payload.size() - 4);
        payload.back() = static_cast<std::uint8_t>(revision);
        rejected_atomically(engine, payload, EngineError::unsupported);
    }
    auto payload = valid;
    payload.resize(payload.size() - 5); // Missing rule marker, with a freshly valid CRC.
    rejected_atomically(engine, payload, EngineError::corrupted_data);
    payload = valid;
    payload.resize(payload.size() - 4);
    payload.push_back(0); // An extra rule marker is not an extension escape hatch.
    rejected_atomically(engine, payload, EngineError::corrupted_data);
    payload = valid;
    payload.resize(payload.size() - 4);
    payload[9] = static_cast<std::uint8_t>(GameResult::draw);
    rejected_atomically(engine, payload, EngineError::corrupted_data);
    payload = valid;
    payload.resize(payload.size() - 4);
    payload[4] = 5;
    rejected_atomically(engine, payload, EngineError::unsupported);
    auto damaged = valid;
    damaged[damaged.size() - 5] = 1; // Same-sized marker damage without resealing.
    const auto before = engine.serialize();
    require(!engine.restore(damaged).restored && engine.serialize() == before,
            "damaged checksum accepted or mutated game");
    std::puts("snapshot-revision malformed/unknown/forged/atomic passed");
}
} // namespace

int main() {
    try {
        ongoing_and_terminal_round_trip();
        malformed_and_unknown_revisions_do_not_mutate();
        legacy_v2_rule_selection(false);
        legacy_v2_rule_selection(true);
    } catch (const std::exception& error) {
        std::fprintf(stderr, "snapshot revision test failed: %s\n", error.what());
        return 1;
    }
    return 0;
}
