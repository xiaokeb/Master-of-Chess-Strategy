#include "mocs/engine/chinese_chess.hpp"

#include <array>
#include <cstdint>
#include <cstdio>
#include <stdexcept>
#include <vector>

namespace {
using namespace mocs::engine;
struct Placement { int x; int y; PieceType type; Side side; };

void require(bool condition, const char* message) {
    if (!condition) throw std::runtime_error(message);
}

void seal(std::vector<std::uint8_t>& bytes) {
    std::uint32_t crc = 0xffffffffU;
    for (const auto byte : bytes) {
        crc ^= byte;
        for (int bit = 0; bit < 8; ++bit) {
            crc = (crc >> 1U) ^ ((crc & 1U) ? 0xedb88320U : 0U);
        }
    }
    crc = ~crc;
    for (unsigned shift = 0; shift < 32; shift += 8) bytes.push_back(static_cast<std::uint8_t>(crc >> shift));
}

ChineseChessEngine fixture(bool rotated, bool old_rules) {
    std::vector<std::uint8_t> bytes(12 + 90, 0);
    bytes[0] = 'M'; bytes[1] = 'O'; bytes[2] = 'C'; bytes[3] = 'X';
    bytes[4] = old_rules ? 3 : 2;
    bytes[6] = rotated ? 1 : 0;
    const Placement pieces[]{
        {5, 7, PieceType::general, Side::red}, {4, 1, PieceType::general, Side::black},
        {2, 3, PieceType::soldier, Side::red}, {4, 4, PieceType::soldier, Side::red},
        {3, 4, PieceType::horse, Side::red}, {5, 4, PieceType::horse, Side::red},
        {6, 3, PieceType::cannon, Side::red}, {3, 3, PieceType::cannon, Side::red},
        {3, 2, PieceType::advisor, Side::black}, {2, 0, PieceType::elephant, Side::black},
    };
    for (const auto& piece : pieces) {
        const int x = rotated ? 8 - piece.x : piece.x;
        const int y = rotated ? 9 - piece.y : piece.y;
        const bool black = (piece.side == Side::black) != rotated;
        bytes[static_cast<std::size_t>(12 + y * 9 + x)] =
            static_cast<std::uint8_t>(piece.type) | (black ? 0x80U : 0U);
    }
    if (old_rules) { bytes.push_back(0); bytes.push_back(0); }
    seal(bytes);
    ChineseChessEngine engine;
    require(engine.restore(bytes).restored, "cycle fixture rejected");
    require(engine.game_result() == GameResult::ongoing, "fixture already terminal");
    return engine;
}

std::array<EngineAction, 4> cycle(bool rotated) {
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

void verify_cycle(bool rotated, bool old_rules) {
    const auto attacker = rotated ? Side::black : Side::red;
    const auto defender = rotated ? Side::red : Side::black;
    const auto actions = cycle(rotated);
    auto engine = fixture(rotated, old_rules);
    const auto initial_fen = engine.fen();
    require(engine.checking_mate_proof(attacker).status == CheckingMateProofStatus::refuted,
            "initial threat was not fully refuted");
    require(engine.apply(actions[0]).accepted, "quiet kill rejected");
    require(!engine.is_in_check(defender), "quiet kill unexpectedly checks");
    require(engine.checking_mate_proof(attacker, 1).status == CheckingMateProofStatus::inconclusive,
            "quiet kill is an immediate mate instead of a continuous mate");
    require(engine.checking_mate_proof(attacker).status == CheckingMateProofStatus::proven,
            "continuous kill not proved");
    require(engine.apply(actions[1]).accepted, "first defense rejected");
    require(engine.checking_mate_proof(attacker).status == CheckingMateProofStatus::refuted,
            "defense did not fully refute kill");
    require(engine.apply(actions[2]).accepted && engine.is_in_check(defender), "return check rejected");
    require(engine.apply(actions[3]).accepted, "second defense rejected");
    require(engine.game_result() == GameResult::ongoing, "one cycle ended prematurely");
    // Reopen a live old/new game before the second repetition; preserve the
    // revision and exact bytes instead of upgrading a v3 terminal implicitly.
    const auto live = engine.serialize();
    ChineseChessEngine reopened;
    require(reopened.restore(live).restored && reopened.serialize() == live, "live restore changed revision");
    for (const auto& action : actions) require(reopened.apply(action).accepted, "second cycle rejected");
    const auto expected = old_rules ? GameResult::draw
        : rotated ? GameResult::first_player_win : GameResult::second_player_win;
    require(reopened.game_result() == expected, "old/new cycle result differs");
    require(reopened.fen().substr(0, reopened.fen().find(' ')) == initial_fen.substr(0, initial_fen.find(' ')),
            "cycle did not close its board");
    const auto terminal = reopened.serialize();
    require(terminal[4] == (old_rules ? 3 : 4), "unexpected serialized version");
    if (!old_rules) require(terminal[terminal.size() - 5] == 1, "current revision marker missing");
    ChineseChessEngine terminal_reopen;
    require(terminal_reopen.restore(terminal).restored && terminal_reopen.serialize() == terminal,
            "terminal restore changed result or bytes");
    require(terminal_reopen.game_result() == expected, "terminal result not retained");
    require(terminal_reopen.undo() && terminal_reopen.game_result() == GameResult::ongoing,
            "terminal undo failed");
    require(terminal_reopen.apply(actions[3]).accepted && terminal_reopen.serialize() == terminal,
            "terminal replay changed verdict");
    require(!terminal_reopen.apply(actions[0]).accepted, "play continued after terminal");
    std::printf("checking-mate-cycle attacker=%s rules=%s result=%d restore/undo passed\n",
                rotated ? "black" : "red", old_rules ? "v3" : "v4.1", static_cast<int>(expected));
}
} // namespace

int main() {
    try {
        for (bool rotated : {false, true}) for (bool old_rules : {true, false}) verify_cycle(rotated, old_rules);
    } catch (const std::exception& error) {
        std::fprintf(stderr, "checking mate cycle test failed: %s\n", error.what());
        return 1;
    }
    return 0;
}
