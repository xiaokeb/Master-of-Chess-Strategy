#include "budget_v2_diagnostic_positions.hpp"

#include <algorithm>
#include <cstdint>
#include <stdexcept>

namespace mocs::engine::budget_v2 {
namespace {

constexpr std::array<DiagnosticPositionDescriptor, 4> catalog{{
    {DiagnosticPositionId::suite1_first, "suite1-opening-1",
     "engine-native/src/main/cpp/tests/benchmark_xiangqi_ai.cpp:openings[0]",
     "rnbakabr1/9/1c4nc1/p1p1p1p1p/9/9/P1P1P1P1P/1C2C1N2/9/RNBAKAB1R w - - 4 3", 4, false},
    {DiagnosticPositionId::suite1_second, "suite1-opening-2",
     "engine-native/src/main/cpp/tests/benchmark_xiangqi_ai.cpp:openings[1]",
     "r2akabnr/9/1cn1b2c1/p1p1p1p1p/9/9/P1P1P1P1P/1CN1B2C1/9/R1BAKA1NR w - - 4 3", 4, false},
    {DiagnosticPositionId::qsearch_initial, "qsearch-initial",
     "engine-native/src/main/cpp/games/xiangqi/pikafish_budget_safety_test.cpp:positions[0]",
     "rnbakabnr/9/1c5c1/p1p1p1p1p/9/9/P1P1P1P1P/1C5C1/9/RNBAKABNR w - - 0 1", 0, false},
    {DiagnosticPositionId::qsearch_checked, "qsearch-checked",
     "engine-native/src/main/cpp/games/xiangqi/pikafish_budget_safety_test.cpp:positions[1]",
     "4k4/9/9/9/9/9/9/4r4/3R5/3AKA3 w - - 0 1", 0, true},
}};

using Move = std::array<std::int32_t, 4>;
constexpr std::array<std::array<Move, 4>, 2> historical_lines{{
    {{{7,7,4,7}, {7,0,6,2}, {7,9,6,7}, {8,0,7,0}}},
    {{{6,9,4,7}, {2,0,4,2}, {1,9,2,7}, {1,0,2,2}}},
}};

std::uint32_t checksum(const std::vector<std::uint8_t>& bytes, std::size_t length) noexcept {
    std::uint32_t crc = 0xffffffffU;
    for (std::size_t i = 0; i < length; ++i) {
        crc ^= bytes[i];
        for (unsigned bit = 0; bit < 8; ++bit) {
            crc = (crc >> 1U) ^ ((crc & 1U) ? 0xedb88320U : 0U);
        }
    }
    return ~crc;
}

void import_checked_fixture(ChineseChessEngine& engine) {
    auto bytes = engine.serialize();
    // The only custom import in this catalog has no moves or claims. Preserve
    // the real serializer's revision trailer; fail if that contract changes.
    if (bytes.size() != 109 || bytes[4] != 4 || bytes[bytes.size() - 5] != 1) {
        throw std::runtime_error("diagnostic fixture requires rules v4/revision 1");
    }
    std::fill(bytes.begin() + 12, bytes.begin() + 102, std::uint8_t{0});
    const auto place = [&bytes](int x, int y, PieceType type, Side side) {
        bytes[12 + static_cast<std::size_t>(y * 9 + x)] = static_cast<std::uint8_t>(type)
            | (side == Side::black ? std::uint8_t{0x80} : std::uint8_t{0});
    };
    place(4, 0, PieceType::general, Side::black);
    place(4, 7, PieceType::chariot, Side::black);
    place(3, 8, PieceType::chariot, Side::red);
    place(3, 9, PieceType::advisor, Side::red);
    place(4, 9, PieceType::general, Side::red);
    place(5, 9, PieceType::advisor, Side::red);
    const auto payload = bytes.size() - 4;
    const auto crc = checksum(bytes, payload);
    for (unsigned byte = 0; byte < 4; ++byte) {
        bytes[payload + byte] = static_cast<std::uint8_t>(crc >> (byte * 8U));
    }
    if (!engine.restore(bytes).restored || engine.serialize() != bytes) {
        throw std::runtime_error("checked diagnostic fixture failed canonical rule admission");
    }
}

}  // namespace

const std::array<DiagnosticPositionDescriptor, 4>& diagnostic_positions() noexcept { return catalog; }

ChineseChessEngine make_diagnostic_position(DiagnosticPositionId id) {
    const auto descriptor = std::find_if(catalog.begin(), catalog.end(),
        [id](const auto& item) { return item.id == id; });
    if (descriptor == catalog.end()) throw std::invalid_argument("unknown diagnostic position");
    ChineseChessEngine engine;
    if (id == DiagnosticPositionId::suite1_first || id == DiagnosticPositionId::suite1_second) {
        const auto line = id == DiagnosticPositionId::suite1_first ? 0U : 1U;
        for (const auto& move : historical_lines[line]) {
            if (!engine.apply(make_board_move(move[0], move[1], move[2], move[3])).accepted) {
                throw std::runtime_error("historical diagnostic opening is no longer legal");
            }
        }
    } else if (id == DiagnosticPositionId::qsearch_checked) {
        import_checked_fixture(engine);
    }
    const auto history = engine.search_position();
    if (engine.fen() != descriptor->expected_fen || engine.current_player() != 0
        || engine.game_result() != GameResult::ongoing || engine.legal_actions().empty()
        || history.moves.size() != descriptor->history_plies
        || engine.is_in_check(Side::red) != descriptor->red_in_check) {
        throw std::runtime_error("diagnostic position differs from its fixed source");
    }
    return engine;
}

}  // namespace mocs::engine::budget_v2
