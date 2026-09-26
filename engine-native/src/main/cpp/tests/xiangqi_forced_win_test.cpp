#include "xiangqi_forced_win.hpp"

#include <array>
#include <cassert>
#include <cstdio>
#include <stdexcept>
#include <vector>

using namespace mocs::engine;
using namespace mocs::authoring;

namespace {

struct Placement { int x, y; Side side; PieceType type; };

ChineseChessEngine position(std::initializer_list<Placement> pieces) {
    std::vector<std::uint8_t> bytes(102, 0);
    bytes[0] = 'M'; bytes[1] = 'O'; bytes[2] = 'C'; bytes[3] = 'X'; bytes[4] = 2;
    for (const auto& p : pieces) {
        bytes[12 + p.y * 9 + p.x] = static_cast<std::uint8_t>(p.type) |
            (p.side == Side::black ? 0x80 : 0);
    }
    std::uint32_t crc = 0xffffffffU;
    for (const auto byte : bytes) {
        crc ^= byte;
        for (int i = 0; i < 8; ++i) crc = (crc >> 1) ^ ((crc & 1) ? 0xedb88320U : 0);
    }
    crc = ~crc;
    for (int i = 0; i < 4; ++i) bytes.push_back(static_cast<std::uint8_t>(crc >> (8 * i)));
    ChineseChessEngine board;
    assert(board.restore(bytes).restored);
    return board;
}

ChineseChessEngine two_move_position(bool second) {
    if (second) return position({
        {4, 9, Side::red, PieceType::general}, {3, 0, Side::black, PieceType::general},
        {4, 2, Side::red, PieceType::soldier}, {6, 8, Side::red, PieceType::chariot},
        {5, 6, Side::red, PieceType::horse}, {3, 8, Side::red, PieceType::cannon},
        {4, 1, Side::black, PieceType::advisor}, {2, 5, Side::black, PieceType::soldier},
    });
    return position({
        {4, 8, Side::red, PieceType::general}, {5, 2, Side::black, PieceType::general},
        {7, 1, Side::red, PieceType::soldier}, {8, 6, Side::red, PieceType::chariot},
        {8, 7, Side::red, PieceType::horse}, {5, 3, Side::red, PieceType::cannon},
        {4, 1, Side::black, PieceType::advisor},
    });
}

bool wins_in_one(const ChineseChessEngine& initial) {
    if (initial.game_result() != GameResult::ongoing) {
        return initial.game_result() == GameResult::first_player_win;
    }
    for (const auto& move : initial.legal_actions()) {
        auto child = initial;
        assert(child.apply(move).accepted);
        if (child.game_result() == GameResult::first_player_win) return true;
    }
    return false;
}

void two_move_proofs_match_independent_exhaustive_oracle() {
    unsigned cooperative_traps = 0;
    for (const bool second : {false, true}) {
        const auto board = two_move_position(second);
        const auto before = board.serialize();
        const auto shallow = prove_red_win(board, 1, 10000);
        assert(shallow.status == ProofStatus::complete && shallow.winning_lines.empty());
        const auto proof = prove_red_win(board, 2, 200000);
        assert(proof.status == ProofStatus::complete && proof.winning_lines.size() == 1);
        const std::array<int, 4> expected = second ? std::array<int, 4>{4, 2, 4, 1}
                                                 : std::array<int, 4>{7, 1, 6, 1};
        assert(proof.winning_lines.front().front().arguments == expected);
        assert(proof.winning_lines.front().size() == 3);
        auto replay = board;
        for (const auto& move : proof.winning_lines.front()) assert(replay.apply(move).accepted);
        assert(replay.game_result() == GameResult::first_player_win);

        // A separate fixed-depth oracle uses copies and no production solver
        // recursion/undo, so a shared traversal error cannot validate itself.
        std::vector<std::array<int, 4>> roots;
        for (const auto& first : board.legal_actions()) {
            auto black = board;
            assert(black.apply(first).accepted);
            assert(black.game_result() != GameResult::first_player_win);
            if (black.game_result() != GameResult::ongoing) continue;
            bool every = true, some = false;
            const auto replies = black.legal_actions();
            assert(!replies.empty());
            for (const auto& reply : replies) {
                auto red = black;
                assert(red.apply(reply).accepted);
                const bool wins = wins_in_one(red);
                every &= wins;
                some |= wins;
            }
            if (every) roots.push_back(first.arguments);
            if (some && !every) ++cooperative_traps;
        }
        assert(roots.size() == 1 && roots.front() == expected);
        assert(board.serialize() == before);

        // An exact budget succeeds, one fewer node is explicitly unknown.
        assert(prove_red_win(board, 2, proof.visited_nodes).status == ProofStatus::complete);
        const auto limited = prove_red_win(board, 2, proof.visited_nodes - 1);
        assert(limited.status == ProofStatus::budget_exhausted);
        assert(limited.visited_nodes == proof.visited_nodes - 1);
        assert(board.serialize() == before);
        std::printf("two-move fixture=%d nodes=%zu oracle_roots=%zu\n",
                    second, proof.visited_nodes, roots.size());
    }
    assert(cooperative_traps > 0);
    std::printf("cooperative-only first moves rejected=%u\n", cooperative_traps);
}

void one_move_and_terminal_boundaries() {
    auto board = position({
        {4, 9, Side::red, PieceType::general}, {4, 0, Side::black, PieceType::general},
        {4, 5, Side::red, PieceType::soldier}, {1, 1, Side::red, PieceType::horse},
        {7, 1, Side::red, PieceType::horse}, {0, 2, Side::red, PieceType::chariot},
    });
    const auto proof = prove_red_win(board, 1, 10000);
    assert(proof.status == ProofStatus::complete && proof.winning_lines.size() == 1);
    assert(proof.winning_lines.front().size() == 1);
    assert(board.apply(proof.winning_lines.front().front()).accepted);
    assert(prove_red_win(board, 1, 10000).status == ProofStatus::not_candidate);
    assert(board.undo());
    assert(board.apply(make_board_move(0, 2, 0, 3)).accepted);
    assert(prove_red_win(board, 1, 10000).status == ProofStatus::not_candidate);

    // A non-checking immobilization is also a win under the challenge rules.
    const auto stalemate = position({
        {5, 9, Side::red, PieceType::general}, {3, 3, Side::red, PieceType::chariot},
        {4, 1, Side::black, PieceType::general},
    });
    const auto forced = prove_red_win(stalemate, 2, 200000);
    assert(forced.status == ProofStatus::complete && !forced.winning_lines.empty());
    bool has_stalemate = false;
    for (const auto& line : forced.winning_lines) {
        auto result = stalemate;
        for (const auto& move : line) assert(result.apply(move).accepted);
        assert(result.game_result() == GameResult::first_player_win);
        has_stalemate |= !result.is_in_check(Side::black);
    }
    assert(has_stalemate);

    ChineseChessEngine initial;
    assert(prove_red_win(initial, 1, 10000).winning_lines.empty());
    assert(prove_red_win(initial, 2, 1).status == ProofStatus::budget_exhausted);
    for (const unsigned limit : {0U, 5U}) {
        bool rejected = false;
        try { prove_red_win(initial, limit, 1000); }
        catch (const std::invalid_argument&) { rejected = true; }
        assert(rejected);
    }
    bool rejected = false;
    try { prove_red_win(initial, 1, 0); }
    catch (const std::invalid_argument&) { rejected = true; }
    assert(rejected);
}

} // namespace

int main() {
    two_move_proofs_match_independent_exhaustive_oracle();
    one_move_and_terminal_boundaries();
    std::puts("forced-win authoring tests passed");
}
