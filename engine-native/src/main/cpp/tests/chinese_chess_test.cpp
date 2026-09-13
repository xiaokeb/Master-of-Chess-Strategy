#include "mocs/engine/chinese_chess.hpp"

#include <cassert>
#include <algorithm>
#include <cstddef>
#include <cstdint>
#include <vector>

namespace {

using mocs::engine::ChineseChessEngine;
using mocs::engine::Difficulty;
using mocs::engine::EngineError;
using mocs::engine::GameResult;
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

struct PositionedPiece {
    std::int32_t x;
    std::int32_t y;
    Piece piece;
};

std::uint32_t crc32(
    const std::vector<std::uint8_t>& data,
    const std::size_t length
) {
    std::uint32_t crc = 0xffffffffU;
    for (std::size_t index = 0; index < length; ++index) {
        crc ^= data[index];
        for (std::uint32_t bit = 0; bit < 8U; ++bit) {
            const auto mask = static_cast<std::uint32_t>(
                -static_cast<std::int32_t>(crc & 1U)
            );
            crc = (crc >> 1U) ^ (0xedb88320U & mask);
        }
    }
    return ~crc;
}

void append_u32(
    std::vector<std::uint8_t>& data,
    const std::uint32_t value
) {
    for (std::uint32_t shift = 0; shift < 32U; shift += 8U) {
        data.push_back(
            static_cast<std::uint8_t>((value >> shift) & 0xffU)
        );
    }
}

std::vector<std::uint8_t> custom_position(
    const Side side,
    const std::vector<PositionedPiece>& pieces,
    const std::uint16_t no_capture_plies = 0
) {
    constexpr std::size_t header_size = 12;
    constexpr std::size_t board_size = 90;
    std::vector<std::uint8_t> data(header_size + board_size, 0);
    data[0] = 'M';
    data[1] = 'O';
    data[2] = 'C';
    data[3] = 'X';
    data[4] = 2;
    data[5] = 0;
    data[6] = static_cast<std::uint8_t>(side);
    data[7] = static_cast<std::uint8_t>(no_capture_plies & 0xffU);
    data[8] = static_cast<std::uint8_t>((no_capture_plies >> 8U) & 0xffU);
    data[9] = static_cast<std::uint8_t>(GameResult::ongoing);
    data[10] = 0;
    data[11] = 0;

    const auto square = [](const std::int32_t x, const std::int32_t y) {
        return header_size + static_cast<std::size_t>(y * 9 + x);
    };
    for (const auto& positioned : pieces) {
        const auto side_bit = positioned.piece.side == Side::black
            ? static_cast<std::uint8_t>(0x80U)
            : static_cast<std::uint8_t>(0);
        data[square(positioned.x, positioned.y)] =
            side_bit | static_cast<std::uint8_t>(positioned.piece.type);
    }
    append_u32(data, crc32(data, data.size()));
    return data;
}

void moving_the_only_screen_between_generals_is_illegal() {
    ChineseChessEngine engine;
    const auto restored = engine.restore(
        custom_position(
            Side::red,
            {
                {4, 9, {PieceType::general, Side::red}},
                {4, 0, {PieceType::general, Side::black}},
                {4, 5, {PieceType::chariot, Side::red}},
            }
        )
    );
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

void wrong_game_type_is_rejected_after_checksum_validation() {
    ChineseChessEngine engine;
    auto data = engine.serialize();
    data[5] = 1;
    data.resize(data.size() - 4);
    append_u32(data, crc32(data, data.size()));

    const auto result = engine.restore(data);
    assert(!result.restored);
    assert(result.error == EngineError::unsupported);
}

void repeated_long_check_loses_for_the_checking_side() {
    ChineseChessEngine engine;
    assert(
        engine.restore(
            custom_position(
                Side::red,
                {
                    {4, 9, {PieceType::general, Side::red}},
                    {4, 0, {PieceType::general, Side::black}},
                    {4, 5, {PieceType::soldier, Side::red}},
                    {3, 1, {PieceType::chariot, Side::red}},
                }
            )
        ).restored
    );

    for (int cycle = 0; cycle < 2; ++cycle) {
        assert(engine.apply(make_board_move(3, 1, 4, 1)).accepted);
        assert(engine.apply(make_board_move(4, 0, 3, 0)).accepted);
        assert(engine.apply(make_board_move(4, 1, 3, 1)).accepted);
        assert(engine.apply(make_board_move(3, 0, 4, 0)).accepted);
    }

    assert(engine.game_result() == GameResult::second_player_win);

    ChineseChessEngine restored;
    assert(restored.restore(engine.serialize()).restored);
    assert(restored.game_result() == GameResult::second_player_win);
    assert(restored.undo());
    assert(restored.game_result() == GameResult::ongoing);

    assert(engine.undo());
    assert(engine.game_result() == GameResult::ongoing);
}

void repeated_unrooted_chase_loses_for_the_chasing_side() {
    ChineseChessEngine engine;
    assert(
        engine.restore(
            custom_position(
                Side::red,
                {
                    {4, 9, {PieceType::general, Side::red}},
                    {5, 0, {PieceType::general, Side::black}},
                    {3, 2, {PieceType::chariot, Side::red}},
                    {4, 1, {PieceType::advisor, Side::black}},
                }
            )
        ).restored
    );

    for (int cycle = 0; cycle < 2; ++cycle) {
        assert(engine.apply(make_board_move(3, 2, 4, 2)).accepted);
        assert(engine.apply(make_board_move(4, 1, 3, 0)).accepted);
        assert(engine.apply(make_board_move(4, 2, 3, 2)).accepted);
        assert(engine.apply(make_board_move(3, 0, 4, 1)).accepted);
    }

    assert(engine.game_result() == GameResult::second_player_win);
}

void repeated_idle_moves_are_drawn_instead_of_treated_as_long_block() {
    ChineseChessEngine engine;
    assert(
        engine.restore(
            custom_position(
                Side::red,
                {
                    {5, 9, {PieceType::general, Side::red}},
                    {5, 0, {PieceType::general, Side::black}},
                    {5, 5, {PieceType::soldier, Side::red}},
                    {3, 9, {PieceType::advisor, Side::red}},
                    {3, 0, {PieceType::advisor, Side::black}},
                }
            )
        ).restored
    );

    for (int cycle = 0; cycle < 2; ++cycle) {
        assert(engine.apply(make_board_move(3, 9, 4, 8)).accepted);
        assert(engine.apply(make_board_move(3, 0, 4, 1)).accepted);
        assert(engine.apply(make_board_move(4, 8, 3, 9)).accepted);
        assert(engine.apply(make_board_move(4, 1, 3, 0)).accepted);
    }

    assert(engine.game_result() == GameResult::draw);
}

void sixty_rounds_without_capture_reaches_the_natural_limit() {
    ChineseChessEngine engine;
    assert(
        engine.restore(
            custom_position(
                Side::red,
                {
                    {5, 9, {PieceType::general, Side::red}},
                    {5, 0, {PieceType::general, Side::black}},
                    {5, 5, {PieceType::soldier, Side::red}},
                    {0, 5, {PieceType::chariot, Side::red}},
                },
                119
            )
        ).restored
    );

    assert(engine.apply(make_board_move(0, 5, 1, 5)).accepted);
    assert(engine.game_result() == GameResult::draw);
}

void capture_resets_the_natural_limit_counter() {
    ChineseChessEngine engine;
    assert(
        engine.restore(
            custom_position(
                Side::red,
                {
                    {5, 9, {PieceType::general, Side::red}},
                    {5, 0, {PieceType::general, Side::black}},
                    {5, 5, {PieceType::soldier, Side::red}},
                    {0, 5, {PieceType::chariot, Side::red}},
                    {1, 5, {PieceType::advisor, Side::black}},
                },
                119
            )
        ).restored
    );

    assert(engine.apply(make_board_move(0, 5, 1, 5)).accepted);
    assert(engine.game_result() == GameResult::ongoing);
}

void easy_ai_returns_legal_move_without_mutating_position() {
    ChineseChessEngine engine;
    assert(engine.apply(make_board_move(0, 6, 0, 5)).accepted);
    const auto before = engine.serialize();
    const auto legal = engine.legal_actions();

    const auto selected = engine.best_move(Difficulty::easy);

    assert(selected);
    assert(
        std::any_of(
            legal.begin(),
            legal.end(),
            [&selected](const auto& action) {
                return action.kind == selected->kind &&
                    action.arguments == selected->arguments;
            }
        )
    );
    assert(engine.serialize() == before);
    const auto medium = engine.best_move(Difficulty::medium);
    assert(medium);
    assert(
        std::any_of(
            legal.begin(),
            legal.end(),
            [&medium](const auto& action) {
                return action.kind == medium->kind &&
                    action.arguments == medium->arguments;
            }
        )
    );
    assert(engine.serialize() == before);
    assert(!engine.best_move(Difficulty::hard));
}

}  // namespace

int main() {
    initial_position_is_stable();
    soldier_moves_forward_but_not_sideways_before_the_river();
    horse_leg_and_cannon_screen_are_enforced();
    moving_the_only_screen_between_generals_is_illegal();
    undo_and_serialization_restore_state();
    corrupted_positions_are_rejected();
    wrong_game_type_is_rejected_after_checksum_validation();
    repeated_long_check_loses_for_the_checking_side();
    repeated_unrooted_chase_loses_for_the_chasing_side();
    repeated_idle_moves_are_drawn_instead_of_treated_as_long_block();
    sixty_rounds_without_capture_reaches_the_natural_limit();
    capture_resets_the_natural_limit_counter();
    easy_ai_returns_legal_move_without_mutating_position();
}
