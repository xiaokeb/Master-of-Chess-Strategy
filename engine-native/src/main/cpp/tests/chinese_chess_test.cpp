#include "mocs/engine/chinese_chess.hpp"

#include <cassert>
#include <cstddef>
#include <cstdint>
#include <vector>

namespace {

using mocs::engine::ChineseChessEngine;
using mocs::engine::EngineError;
using mocs::engine::Piece;
using mocs::engine::PieceType;
using mocs::engine::Side;
using mocs::engine::make_board_move;

void initial_position_is_stable() {
    const ChineseChessEngine engine;
    const Piece red_general{PieceType::general, Side::red};
    const Piece black_general{PieceType::general, Side::black};
    const Piece red_cannon{PieceType::cannon, Side::red};
    const Piece red_soldier{PieceType::soldier, Side::red};

    assert(engine.current_player() == 0);
    assert(engine.piece_at(4, 9) == red_general);
    assert(engine.piece_at(4, 0) == black_general);
    assert(engine.piece_at(1, 7) == red_cannon);
    assert(engine.piece_at(0, 6) == red_soldier);
    assert(!engine.piece_at(1, 6));
    assert(!engine.legal_actions().empty());
}

void soldier_moves_forward_but_not_sideways_before_the_river() {
    ChineseChessEngine engine;

    const auto sideways = engine.apply(make_board_move(0, 6, 1, 6));
    assert(!sideways.accepted);
    assert(sideways.error == EngineError::illegal_action);

    const auto forward = engine.apply(make_board_move(0, 6, 0, 5));
    assert(forward.accepted);
    assert(engine.current_player() == 1);
}

void horse_leg_and_cannon_screen_are_enforced() {
    ChineseChessEngine engine;

    const auto blocked_horse = engine.apply(make_board_move(1, 9, 3, 8));
    assert(!blocked_horse.accepted);

    const auto horse = engine.apply(make_board_move(1, 9, 2, 7));
    assert(horse.accepted);
    assert(engine.undo());

    const auto cannon_capture = engine.apply(make_board_move(1, 7, 1, 0));
    const Piece red_cannon{PieceType::cannon, Side::red};
    assert(cannon_capture.accepted);
    assert(engine.piece_at(1, 0) == red_cannon);
}

std::vector<std::uint8_t> facing_generals_position() {
    constexpr std::size_t header_size = 6;
    constexpr std::size_t board_size = 90;
    std::vector<std::uint8_t> data(header_size + board_size, 0);
    data[0] = 'M';
    data[1] = 'O';
    data[2] = 'C';
    data[3] = 'X';
    data[4] = 1;
    data[5] = 0;

    const auto square = [](const std::int32_t x, const std::int32_t y) {
        return header_size + static_cast<std::size_t>(y * 9 + x);
    };
    data[square(4, 9)] = static_cast<std::uint8_t>(PieceType::general);
    data[square(4, 0)] =
        0x80U | static_cast<std::uint8_t>(PieceType::general);
    data[square(4, 5)] = static_cast<std::uint8_t>(PieceType::chariot);
    return data;
}

void moving_the_only_screen_between_generals_is_illegal() {
    ChineseChessEngine engine;
    const auto restored = engine.restore(facing_generals_position());
    assert(restored.restored);

    const auto exposes_generals = engine.apply(make_board_move(4, 5, 3, 5));
    assert(!exposes_generals.accepted);
    assert(exposes_generals.error == EngineError::illegal_action);
}

void undo_and_serialization_restore_state() {
    ChineseChessEngine engine;
    const auto initial = engine.serialize();

    assert(engine.apply(make_board_move(0, 6, 0, 5)).accepted);
    const auto moved = engine.serialize();
    assert(moved != initial);

    ChineseChessEngine restored;
    const auto result = restored.restore(moved);
    assert(result.restored);
    assert(result.error == EngineError::none);
    assert(restored.serialize() == moved);

    assert(engine.undo());
    assert(engine.serialize() == initial);
}

void corrupted_positions_are_rejected() {
    ChineseChessEngine engine;
    auto data = engine.serialize();
    data[0] = 'B';

    const auto result = engine.restore(data);
    assert(!result.restored);
    assert(result.error == EngineError::corrupted_data);
}

}  // namespace

int main() {
    initial_position_is_stable();
    soldier_moves_forward_but_not_sideways_before_the_river();
    horse_leg_and_cannon_screen_are_enforced();
    moving_the_only_screen_between_generals_is_illegal();
    undo_and_serialization_restore_state();
    corrupted_positions_are_rejected();
}
