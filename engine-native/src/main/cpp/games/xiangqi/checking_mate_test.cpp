#include "mocs/engine/chinese_chess.hpp"

#include <chrono>
#include <cstdint>
#include <cstdio>
#include <initializer_list>
#include <stdexcept>
#include <vector>

namespace {
using namespace mocs::engine;
struct Placement { int x; int y; PieceType type; Side side; };

void require(bool condition, const char* message) {
    if (!condition) throw std::runtime_error(message);
}

void append_checksum(std::vector<std::uint8_t>& bytes) {
    std::uint32_t crc = 0xffffffffU;
    for (const auto byte : bytes) {
        crc ^= byte;
        for (int bit = 0; bit < 8; ++bit) {
            crc = (crc >> 1U) ^ ((crc & 1U) ? 0xedb88320U : 0U);
        }
    }
    crc = ~crc;
    for (unsigned shift = 0; shift < 32; shift += 8) {
        bytes.push_back(static_cast<std::uint8_t>(crc >> shift));
    }
}

ChineseChessEngine position(bool rotated, std::initializer_list<Placement> pieces) {
    std::vector<std::uint8_t> bytes(12 + 90, 0);
    bytes[0] = 'M'; bytes[1] = 'O'; bytes[2] = 'C'; bytes[3] = 'X'; bytes[4] = 2;
    bytes[6] = rotated ? 1 : 0;
    for (const auto& piece : pieces) {
        const int x = rotated ? 8 - piece.x : piece.x;
        const int y = rotated ? 9 - piece.y : piece.y;
        const bool black = (piece.side == Side::black) != rotated;
        bytes[static_cast<std::size_t>(12 + y * 9 + x)] =
            static_cast<std::uint8_t>(piece.type) | (black ? 0x80U : 0U);
    }
    append_checksum(bytes);
    ChineseChessEngine engine;
    require(engine.restore(bytes).restored, "initial board rejected");
    return engine;
}

CheckingMateProof verify(const ChineseChessEngine& engine, Side attacker,
                        unsigned depth, std::size_t budget, CheckingMateProofStatus expected,
                        const char* label) {
    const auto before = engine.serialize();
    const auto start = std::chrono::steady_clock::now();
    const auto proof = engine.checking_mate_proof(attacker, depth, budget);
    const auto micros = std::chrono::duration_cast<std::chrono::microseconds>(
        std::chrono::steady_clock::now() - start).count();
    std::printf("%s side=%d depth=%u status=%d nodes=%zu us=%lld\n", label,
                static_cast<int>(attacker), depth, static_cast<int>(proof.status),
                proof.visited_nodes, static_cast<long long>(micros));
    require(proof.status == expected, "proof status differs");
    require(proof.visited_nodes <= budget, "proof exceeded node budget");
    require(before == engine.serialize(), "proof mutated the caller");
    return proof;
}

// Independent shallow oracle uses the public apply/terminal path rather than
// the solver's board recursion. Every legal defensive reply must be covered.
bool public_checking_mate(const ChineseChessEngine& engine, Side attacker,
                          unsigned checks_left) {
    const auto defender = attacker == Side::red ? Side::black : Side::red;
    const bool attacking = engine.current_player() == static_cast<std::uint8_t>(attacker);
    const auto actions = engine.legal_actions();
    if (engine.game_result() != GameResult::ongoing) {
        const auto win = attacker == Side::red ? GameResult::first_player_win
                                              : GameResult::second_player_win;
        return engine.game_result() == win && engine.is_in_check(defender);
    }
    bool found = false;
    for (const auto& action : actions) {
        auto next = engine;
        require(next.apply(action).accepted, "oracle legal action rejected");
        if (attacking && (!next.is_in_check(defender) || checks_left == 0)) continue;
        found = true;
        const bool child = public_checking_mate(next, attacker,
                                                checks_left - (attacking ? 1U : 0U));
        if (attacking && child) return true;
        if (!attacking && !child) return false;
    }
    return !attacking && found;
}

void verify_checking_lines(bool rotated) {
    const auto attacker = rotated ? Side::black : Side::red;
    const auto mate = position(rotated, {
        {4, 9, PieceType::general, Side::red}, {4, 0, PieceType::general, Side::black},
        {4, 5, PieceType::soldier, Side::red}, {0, 2, PieceType::chariot, Side::red},
        {1, 1, PieceType::horse, Side::red}, {7, 1, PieceType::horse, Side::red},
    });
    verify(mate, attacker, 1, 512, CheckingMateProofStatus::proven, "immediate-mate");
    require(public_checking_mate(mate, attacker, 1), "public path does not confirm immediate mate");
    const auto chain = position(rotated, {
        {4, 9, PieceType::general, Side::red}, {3, 0, PieceType::general, Side::black},
        {3, 3, PieceType::soldier, Side::red}, {6, 4, PieceType::chariot, Side::red},
        {5, 8, PieceType::cannon, Side::red}, {4, 1, PieceType::advisor, Side::black},
        {8, 8, PieceType::soldier, Side::black},
    });
    verify(chain, attacker, 1, 512, CheckingMateProofStatus::inconclusive, "short-horizon");
    verify(chain, attacker, 2, 512, CheckingMateProofStatus::proven, "two-check-mate");
    require(!public_checking_mate(chain, attacker, 1), "chain has an unexpected one-check mate");
    require(public_checking_mate(chain, attacker, 2), "public replies refute two-check mate");
    verify(chain, attacker, 4, 1, CheckingMateProofStatus::inconclusive, "budget-one");
    verify(chain, attacker, 0, 512, CheckingMateProofStatus::inconclusive, "depth-zero");
    verify(chain, attacker, 9, 512, CheckingMateProofStatus::inconclusive, "invalid-depth");
    verify(chain, attacker, 4, 0, CheckingMateProofStatus::inconclusive, "budget-zero");
    const auto idle = position(rotated, {
        {4, 9, PieceType::general, Side::red}, {4, 0, PieceType::general, Side::black},
        {4, 5, PieceType::soldier, Side::red},
    });
    verify(idle, attacker, 4, 512, CheckingMateProofStatus::refuted, "no-checking-move");
    const auto chase = position(rotated, {
        {4, 9, PieceType::general, Side::red}, {4, 0, PieceType::general, Side::black},
        {4, 5, PieceType::soldier, Side::red}, {3, 1, PieceType::chariot, Side::red},
    });
    verify(chase, attacker, 4, 512, CheckingMateProofStatus::refuted, "checks-with-capture-defense");
    require(!public_checking_mate(chase, attacker, 2), "capture defense was not covered");

    const auto stalemate = position(rotated, {
        {4, 9, PieceType::general, Side::red}, {5, 0, PieceType::general, Side::black},
        {4, 1, PieceType::chariot, Side::red},
    });
    require(!stalemate.is_in_check(rotated ? Side::red : Side::black), "fixture must be quiet");
    const auto quiet = stalemate.checking_mate_proof(attacker, 1, 512);
    require(quiet.status != CheckingMateProofStatus::proven, "quiet stalemate became mate proof");

    // Moving a cannon off the first horse's leg creates a new two-check
    // threat. A crossed pawn blocks that cannon's earlier checking route.
    auto setup = position(rotated, {
        {5, 7, PieceType::general, Side::red}, {5, 1, PieceType::general, Side::black},
        {3, 3, PieceType::soldier, Side::red}, {4, 4, PieceType::soldier, Side::red},
        {3, 5, PieceType::horse, Side::red}, {5, 2, PieceType::horse, Side::red},
        {6, 3, PieceType::cannon, Side::red}, {3, 4, PieceType::cannon, Side::red},
        {4, 1, PieceType::advisor, Side::black}, {8, 2, PieceType::elephant, Side::black},
    });
    verify(setup, attacker, 4, 512, CheckingMateProofStatus::refuted, "before-quiet-setup");
    require(!public_checking_mate(setup, attacker, 2), "old position already has the mate");
    require(setup.apply(rotated ? make_board_move(5, 5, 8, 5) : make_board_move(3, 4, 0, 4)).accepted,
            "quiet setup move rejected");
    require(!setup.is_in_check(rotated ? Side::red : Side::black), "setup unexpectedly checks");
    verify(setup, attacker, 1, 512, CheckingMateProofStatus::inconclusive, "after-setup-not-immediate");
    verify(setup, attacker, 4, 512, CheckingMateProofStatus::proven, "after-setup-two-check-mate");
    // The proof intentionally ignores the live turn. Restore the same board
    // with the attacker to move for the independent public-path confirmation.
    auto bytes = setup.serialize();
    bytes.resize(12 + 90);
    bytes[4] = 2;
    bytes[6] = static_cast<std::uint8_t>(attacker);
    bytes[7] = 0;
    bytes[8] = 0;
    bytes[9] = 0;
    bytes[10] = 0;
    bytes[11] = 0;
    append_checksum(bytes);
    ChineseChessEngine hypothetical;
    require(hypothetical.restore(bytes).restored, "hypothetical board rejected");
    require(!public_checking_mate(hypothetical, attacker, 1), "quiet setup creates immediate mate");
    require(public_checking_mate(hypothetical, attacker, 2), "public path refutes quiet setup mate");
}
} // namespace

int main() {
    try {
        verify_checking_lines(false);
        verify_checking_lines(true);
    } catch (const std::exception& error) {
        std::fprintf(stderr, "checking-mate test failed: %s\n", error.what());
        return 1;
    }
    return 0;
}
