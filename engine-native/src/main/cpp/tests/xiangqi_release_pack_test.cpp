#include "xiangqi_forced_win.hpp"

#include <array>
#include <cstdint>
#include <fstream>
#include <iostream>
#include <stdexcept>
#include <string>
#include <vector>

namespace {

using mocs::authoring::ProofStatus;
using mocs::authoring::prove_red_win;
using mocs::engine::ChineseChessEngine;
using mocs::engine::GameResult;
using mocs::engine::PieceType;
using mocs::engine::Side;
using mocs::engine::make_board_move;

struct Placement {
    int x;
    int y;
    Side side;
    PieceType type;
};

struct Move {
    int from_x;
    int from_y;
    int to_x;
    int to_y;
};

struct Level {
    std::string id;
    std::string theme;
    int max_player_moves{0};
    int expected_pieces{0};
    std::vector<Placement> pieces;
    std::vector<Move> variation;
};

void require(const bool condition, const std::string& message) {
    if (!condition) throw std::runtime_error(message);
}

std::vector<std::string> fields(const std::string& line) {
    std::vector<std::string> result;
    std::size_t start = 0;
    do {
        const auto end = line.find('|', start);
        result.push_back(line.substr(start, end - start));
        if (end == std::string::npos) break;
        start = end + 1;
    } while (true);
    return result;
}

PieceType piece_type(const std::string& value) {
    if (value == "GENERAL") return PieceType::general;
    if (value == "ADVISOR") return PieceType::advisor;
    if (value == "ELEPHANT") return PieceType::elephant;
    if (value == "HORSE") return PieceType::horse;
    if (value == "CHARIOT") return PieceType::chariot;
    if (value == "CANNON") return PieceType::cannon;
    if (value == "SOLDIER") return PieceType::soldier;
    throw std::runtime_error("unknown piece type: " + value);
}

std::vector<Level> read_pack(const char* path) {
    std::ifstream input(path);
    require(input.good(), std::string("cannot open pack: ") + path);
    std::vector<Level> levels;
    Level current;
    bool active = false;
    std::string line;
    while (std::getline(input, line)) {
        if (line.empty() || line.front() == '#') continue;
        const auto item = fields(line);
        if (item[0] == "LEVEL") {
            require(!active && item.size() == 12, "invalid LEVEL record");
            current = Level{};
            current.id = item[1];
            current.theme = item[5];
            require(item[6] == "RED", current.id + ": expected red to move");
            current.max_player_moves = std::stoi(item[7]);
            current.expected_pieces = std::stoi(item[10]);
            active = true;
        } else if (item[0] == "PIECE") {
            require(active && item.size() == 5, "invalid PIECE record");
            current.pieces.push_back(Placement{
                std::stoi(item[1]), std::stoi(item[2]),
                item[3] == "RED" ? Side::red : Side::black,
                piece_type(item[4])});
            require(item[3] == "RED" || item[3] == "BLACK", "invalid piece side");
        } else if (item[0] == "MOVE") {
            require(active && item.size() == 5, "invalid MOVE record");
            current.variation.push_back(Move{
                std::stoi(item[1]), std::stoi(item[2]),
                std::stoi(item[3]), std::stoi(item[4])});
        } else if (item[0] == "END") {
            require(active && item.size() == 1 && !current.variation.empty(), "invalid END record");
            require(static_cast<int>(current.pieces.size()) == current.expected_pieces,
                    current.id + ": piece count differs from manifest");
            levels.push_back(std::move(current));
            active = false;
        } else {
            require(!active && (item[0] == "MOCS-XQ-ENDGAMES" ||
                    item[0] == "LICENSE" || item[0] == "AUTHOR"),
                    "unknown release-pack record: " + item[0]);
        }
    }
    require(!active && levels.size() == 35, "expected complete 35-level release pack");
    return levels;
}

ChineseChessEngine position(const Level& level) {
    // MOCX v2 is a stable fixture encoding; the engine validates the board on restore.
    std::vector<std::uint8_t> bytes(12 + 90, 0);
    bytes[0] = 'M'; bytes[1] = 'O'; bytes[2] = 'C'; bytes[3] = 'X'; bytes[4] = 2;
    for (const auto& piece : level.pieces) {
        require(piece.x >= 0 && piece.x < 9 && piece.y >= 0 && piece.y < 10,
                level.id + ": piece out of bounds");
        const auto index = static_cast<std::size_t>(12 + piece.y * 9 + piece.x);
        require(bytes[index] == 0, level.id + ": duplicate square");
        bytes[index] = static_cast<std::uint8_t>(piece.type) |
            (piece.side == Side::black ? 0x80U : 0U);
    }
    std::uint32_t crc = 0xffffffffU;
    for (const auto byte : bytes) {
        crc ^= byte;
        for (int bit = 0; bit < 8; ++bit) {
            crc = (crc >> 1U) ^ ((crc & 1U) != 0U ? 0xedb88320U : 0U);
        }
    }
    crc = ~crc;
    for (int byte = 0; byte < 4; ++byte) {
        bytes.push_back(static_cast<std::uint8_t>(crc >> (8 * byte)));
    }
    ChineseChessEngine engine;
    require(engine.restore(bytes).restored, level.id + ": native restore rejected board");
    require(engine.game_result() == GameResult::ongoing, level.id + ": board is terminal");
    return engine;
}

void audit(const Level& level, std::size_t& visited_nodes) {
    const auto board = position(level);
    const auto before = board.serialize();
    auto replay = board;
    for (const auto& move : level.variation) {
        require(replay.apply(make_board_move(
            move.from_x, move.from_y, move.to_x, move.to_y)).accepted,
            level.id + ": main-line move rejected");
    }
    require(replay.game_result() == GameResult::first_player_win,
            level.id + ": main line does not win");
    require(static_cast<int>((level.variation.size() + 1) / 2) <= level.max_player_moves,
            level.id + ": main line exceeds move limit");

    if (level.theme == "唯一一步杀" || level.theme == "两步强制胜") {
        const auto one = prove_red_win(board, 1, 1000000);
        require(one.status == ProofStatus::complete, level.id + ": one-move proof incomplete");
        visited_nodes += one.visited_nodes;
        const auto expected = make_board_move(
            level.variation.front().from_x, level.variation.front().from_y,
            level.variation.front().to_x, level.variation.front().to_y);
        if (level.theme == "唯一一步杀") {
            require(one.winning_lines.size() == 1 &&
                    one.winning_lines.front().front().arguments == expected.arguments,
                    level.id + ": one-move win is not unique");
            require(replay.is_in_check(Side::black), level.id + ": win is stalemate, not mate");
        } else {
            require(one.winning_lines.empty(), level.id + ": wins earlier than two moves");
            const auto two = prove_red_win(board, 2, 1000000);
            require(two.status == ProofStatus::complete && two.winning_lines.size() == 1 &&
                    two.winning_lines.front().front().arguments == expected.arguments,
                    level.id + ": two-move full-defense proof failed");
            visited_nodes += two.visited_nodes;
        }
    }
    require(board.serialize() == before, level.id + ": proof mutated initial board");
}

} // namespace

int main(const int argc, char** argv) {
    try {
        require(argc == 2, "usage: mocs_xiangqi_release_pack_test <endgames-v5.txt>");
        const auto levels = read_pack(argv[1]);
        std::size_t visited_nodes = 0;
        for (const auto& level : levels) audit(level, visited_nodes);
        std::cout << "release-pack levels=" << levels.size()
                  << " native-proof-nodes=" << visited_nodes << "\n";
        return 0;
    } catch (const std::exception& error) {
        std::cerr << "release-pack audit failed: " << error.what() << "\n";
        return 1;
    }
}
