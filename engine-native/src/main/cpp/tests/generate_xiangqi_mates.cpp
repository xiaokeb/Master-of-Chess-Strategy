// Offline authoring aid only: deterministic candidates still require human
// curation, release-pack audit, and Android rule tests before publication.
#include "mocs/engine/chinese_chess.hpp"
#include "xiangqi_forced_win.hpp"

#include <array>
#include <charconv>
#include <cstdint>
#include <iostream>
#include <random>
#include <string>
#include <unordered_set>
#include <utility>
#include <vector>

namespace {

using mocs::engine::ChineseChessEngine;
using mocs::engine::GameResult;
using mocs::engine::Piece;
using mocs::engine::PieceType;
using mocs::engine::Side;

struct Placement {
    int x;
    int y;
    Piece piece;
};

std::uint32_t crc32(const std::vector<std::uint8_t>& data) {
    std::uint32_t crc = 0xffffffffU;
    for (const auto byte : data) {
        crc ^= byte;
        for (int bit = 0; bit < 8; ++bit) {
            const auto mask = static_cast<std::uint32_t>(
                -static_cast<std::int32_t>(crc & 1U)
            );
            crc = (crc >> 1U) ^ (0xedb88320U & mask);
        }
    }
    return ~crc;
}

std::vector<std::uint8_t> position(const std::vector<Placement>& pieces) {
    std::vector<std::uint8_t> data(12 + 90, 0);
    data[0] = 'M';
    data[1] = 'O';
    data[2] = 'C';
    data[3] = 'X';
    data[4] = 2;
    for (const auto& item : pieces) {
        data[12 + item.y * 9 + item.x] =
            (item.piece.side == Side::black ? 0x80U : 0U) |
            static_cast<std::uint8_t>(item.piece.type);
    }
    const auto checksum = crc32(data);
    for (int index = 0; index < 4; ++index) {
        data.push_back(static_cast<std::uint8_t>(checksum >> (index * 8)));
    }
    return data;
}

bool add_piece(
    std::vector<Placement>& pieces,
    const std::vector<std::pair<int, int>>& squares,
    const Piece piece,
    std::mt19937& rng
) {
    for (int retry = 0; retry < 16; ++retry) {
        const auto& [x, y] = squares[rng() % squares.size()];
        bool occupied = false;
        for (const auto& placed : pieces) {
            occupied |= placed.x == x && placed.y == y;
        }
        if (!occupied) {
            pieces.push_back({x, y, piece});
            return true;
        }
    }
    return false;
}

std::vector<std::pair<int, int>> board_squares() {
    std::vector<std::pair<int, int>> squares;
    for (int y = 1; y <= 8; ++y) {
        for (int x = 0; x < 9; ++x) {
            squares.emplace_back(x, y);
        }
    }
    return squares;
}

std::vector<std::pair<int, int>> soldier_squares(const Side side) {
    std::vector<std::pair<int, int>> squares;
    const int first = side == Side::red ? 0 : 3;
    const int last = side == Side::red ? 6 : 9;
    for (int y = first; y <= last; ++y) {
        for (int x = 0; x < 9; ++x) {
            const bool uncrossed = side == Side::red ? y >= 5 : y <= 4;
            if (!uncrossed || x % 2 == 0) {
                squares.emplace_back(x, y);
            }
        }
    }
    return squares;
}

const char* piece_name(const PieceType type) {
    switch (type) {
        case PieceType::general: return "GENERAL";
        case PieceType::advisor: return "ADVISOR";
        case PieceType::elephant: return "ELEPHANT";
        case PieceType::horse: return "HORSE";
        case PieceType::chariot: return "CHARIOT";
        case PieceType::cannon: return "CANNON";
        case PieceType::soldier: return "SOLDIER";
    }
    return "UNKNOWN";
}

} // namespace

int main(int argc, char** argv) {
    // Keep the original one-move candidate stream as the default. Two-move
    // mode proves wins against all defenses, rather than a cooperative PV.
    int red_moves = 1, requested = 20, attempts = 20000;
    const auto read = [](const char* value, int& destination, int maximum) {
        const std::string text(value);
        const auto result = std::from_chars(text.data(), text.data() + text.size(), destination);
        return result.ec == std::errc{} && result.ptr == text.data() + text.size() &&
            destination >= 1 && destination <= maximum;
    };
    if (argc > 4 || (argc > 1 && !read(argv[1], red_moves, 2)) ||
        (argc > 2 && !read(argv[2], requested, 100)) ||
        (argc > 3 && !read(argv[3], attempts, 1000000))) {
        std::cerr << "usage: mocs_generate_xiangqi_mates [red-moves:1..2] "
                     "[candidates:1..100] [attempts:1..1000000]\n";
        return 2;
    }
    std::mt19937 rng(20260925U);
    const auto mobile = board_squares();
    const auto red_soldiers = soldier_squares(Side::red);
    const auto black_soldiers = soldier_squares(Side::black);
    const std::vector<std::pair<int, int>> red_palace{
        {3, 9}, {4, 9}, {5, 9}, {3, 8}, {4, 8}, {5, 8}, {3, 7}, {4, 7}, {5, 7},
    };
    const std::vector<std::pair<int, int>> black_palace{
        {3, 0}, {4, 0}, {5, 0}, {3, 1}, {4, 1}, {5, 1}, {3, 2}, {4, 2}, {5, 2},
    };
    const std::vector<std::pair<int, int>> black_advisors{
        {3, 0}, {5, 0}, {4, 1}, {3, 2}, {5, 2},
    };
    const std::vector<std::pair<int, int>> black_elephants{
        {2, 0}, {6, 0}, {0, 2}, {4, 2}, {8, 2}, {2, 4}, {6, 4},
    };
    std::unordered_set<std::string> seen;
    int found = 0;
    int exhausted = 0;
    for (int attempt = 0; attempt < attempts && found < requested; ++attempt) {
        std::vector<Placement> pieces;
        bool placed = add_piece(pieces, red_palace, {PieceType::general, Side::red}, rng) &&
            add_piece(pieces, black_palace, {PieceType::general, Side::black}, rng) &&
            add_piece(pieces, red_soldiers, {PieceType::soldier, Side::red}, rng) &&
            add_piece(pieces, mobile, {PieceType::chariot, Side::red}, rng) &&
            add_piece(pieces, mobile, {PieceType::horse, Side::red}, rng);
        if (rng() % 2 == 0) {
            placed &= add_piece(pieces, mobile, {PieceType::cannon, Side::red}, rng);
        }
        placed &= add_piece(pieces, black_advisors, {PieceType::advisor, Side::black}, rng);
        if (rng() % 2 == 0) {
            placed &= add_piece(pieces, black_elephants, {PieceType::elephant, Side::black}, rng);
        }
        if (rng() % 2 == 0) {
            placed &= add_piece(pieces, black_soldiers, {PieceType::soldier, Side::black}, rng);
        }
        if (!placed) continue;

        ChineseChessEngine engine;
        if (!engine.restore(position(pieces)).restored ||
            engine.game_result() != GameResult::ongoing ||
            engine.is_in_check(Side::red) ||
            engine.is_in_check(Side::black)) {
            continue;
        }
        const auto actions = engine.legal_actions();
        if (actions.size() < 5 || actions.size() > 45) continue;
        const auto initial_fen = engine.fen();
        if (red_moves == 2) {
            const auto shallow = mocs::authoring::prove_red_win(engine, 1, 1000);
            if (shallow.status != mocs::authoring::ProofStatus::complete ||
                !shallow.winning_lines.empty()) continue;
            const auto proof = mocs::authoring::prove_red_win(engine, 2, 200000);
            if (proof.status == mocs::authoring::ProofStatus::budget_exhausted) {
                ++exhausted;
                continue; // Unknown is neither a refutation nor proof of uniqueness.
            }
            if (proof.status != mocs::authoring::ProofStatus::complete ||
                proof.winning_lines.size() != 1 || !seen.insert(initial_fen).second) continue;
            ++found;
            std::cout << "CANDIDATE " << found << " attempt=" << attempt
                      << " fen=" << initial_fen << '\n';
            std::cout << "PROOF|red_moves=2|unique_first=true|all_defenses=true|nodes="
                      << proof.visited_nodes << '\n';
            for (const auto& item : pieces) {
                std::cout << "PIECE|" << item.x << '|' << item.y << '|'
                          << (item.piece.side == Side::red ? "RED" : "BLACK")
                          << '|' << piece_name(item.piece.type) << '\n';
            }
            for (const auto& move : proof.winning_lines.front()) {
                const auto& a = move.arguments;
                std::cout << "MOVE|" << a[0] << '|' << a[1] << '|' << a[2] << '|' << a[3] << '\n';
            }
            continue;
        }
        int winners = 0;
        std::array<int, 4> winning_move{};
        for (const auto& action : actions) {
            const auto& [from_x, from_y, to_x, to_y] = action.arguments;
            const auto target = engine.piece_at(to_x, to_y);
            if (target && target->type == PieceType::general) continue;
            if (!engine.apply(action).accepted) {
                std::cerr << "legal action rejected at attempt=" << attempt << '\n';
                return 2;
            }
            if (engine.game_result() == GameResult::first_player_win &&
                engine.is_in_check(Side::black)) {
                ++winners;
                winning_move = action.arguments;
            }
            if (!engine.undo() || engine.fen() != initial_fen) {
                std::cerr << "undo changed candidate at attempt=" << attempt << '\n';
                return 2;
            }
            if (winners > 1) break;
        }
        if (winners != 1 || !seen.insert(initial_fen).second) continue;
        ++found;
        std::cout << "CANDIDATE " << found << " attempt=" << attempt
                  << " fen=" << initial_fen << '\n';
        for (const auto& item : pieces) {
            std::cout << "PIECE|" << item.x << '|' << item.y << '|'
                      << (item.piece.side == Side::red ? "RED" : "BLACK")
                      << '|' << piece_name(item.piece.type) << '\n';
        }
        std::cout << "MOVE|" << winning_move[0] << '|' << winning_move[1]
                  << '|' << winning_move[2] << '|' << winning_move[3] << '\n';
    }
    std::cerr << "generated=" << found << " exhausted=" << exhausted << '\n';
    return found == requested ? 0 : 1;
}
