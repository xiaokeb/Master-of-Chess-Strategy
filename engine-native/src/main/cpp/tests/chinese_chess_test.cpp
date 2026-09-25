#include "mocs/engine/chinese_chess.hpp"
#include "mocs/engine/pikafish_adapter.hpp"

#include <cassert>
#include <algorithm>
#include <cstddef>
#include <cstdint>
#include <cstdio>
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
using mocs::engine::adjudicate_pikafish_repetition;
using mocs::engine::decode_pikafish_move;

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
    assert(
        engine.fen() ==
        "rnbakabnr/9/1c5c1/p1p1p1p1p/9/9/"
        "P1P1P1P1P/1C5C1/9/RNBAKABNR w - - 0 1"
    );
}

void pikafish_coordinates_are_converted_and_validated() {
    const auto legal = std::vector{
        make_board_move(0, 6, 0, 5),
        make_board_move(2, 6, 2, 5),
    };

    const auto decoded = decode_pikafish_move("a3a4", legal);
    assert(decoded);
    assert(decoded->arguments == legal.front().arguments);
    assert(!decode_pikafish_move("a3b3", legal));
    assert(!decode_pikafish_move("(none)", legal));
}

void pikafish_history_judge_validates_repetition_windows() {
    const auto idle_result = adjudicate_pikafish_repetition(
        "rnbakabnr/9/1c5c1/p1p1p1p1p/9/9/"
        "P1P1P1P1P/1C5C1/9/RNBAKABNR w - - 0 1",
        {
            "b0c2", "b9c7", "c2b0", "c7b9",
            "b0c2", "b9c7", "c2b0", "c7b9",
        }
    );
    assert(idle_result == GameResult::draw);

    const auto checking_result = adjudicate_pikafish_repetition(
        "4k4/3R5/9/9/9/4P4/9/9/9/4K4 w - - 0 1",
        {
            "d8e8", "e9d9", "e8d8", "d9e9",
            "d8e8", "e9d9", "e8d8", "d9e9",
        }
    );
    assert(checking_result == GameResult::second_player_win);
    assert(
        !adjudicate_pikafish_repetition(
            "not-a-position",
            {"a0a1"}
        )
    );
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

void legacy_seed_endgames_have_a_winning_first_move() {
    std::size_t seed_index = 0;
    const auto verify = [&seed_index](
        const std::vector<PositionedPiece>& pieces
    ) {
        ++seed_index;
        ChineseChessEngine engine;
        assert(engine.restore(custom_position(Side::red, pieces)).restored);
        assert(engine.game_result() == GameResult::ongoing);
        assert(engine.apply(make_board_move(3, 1, 4, 1)).accepted);
        if (engine.game_result() != GameResult::first_player_win) {
            std::fprintf(stderr, "Legacy endgame seed %zu has no winning line\n", seed_index);
        }
        assert(engine.game_result() == GameResult::first_player_win);
    };

    verify({
        {4, 9, {PieceType::general, Side::red}},
        {4, 0, {PieceType::general, Side::black}},
        {4, 5, {PieceType::soldier, Side::red}},
        {3, 1, {PieceType::chariot, Side::red}},
        {0, 1, {PieceType::chariot, Side::red}},
        {2, 2, {PieceType::horse, Side::red}},
        {6, 2, {PieceType::horse, Side::red}},
    });
    verify({
        {4, 9, {PieceType::general, Side::red}},
        {4, 0, {PieceType::general, Side::black}},
        {4, 5, {PieceType::soldier, Side::red}},
        {3, 1, {PieceType::chariot, Side::red}},
        {0, 1, {PieceType::chariot, Side::red}},
        {2, 2, {PieceType::horse, Side::red}},
        {6, 2, {PieceType::horse, Side::red}},
        {0, 3, {PieceType::soldier, Side::black}},
        {2, 3, {PieceType::soldier, Side::black}},
        {6, 3, {PieceType::soldier, Side::black}},
        {8, 3, {PieceType::soldier, Side::black}},
    });
    verify({
        {4, 9, {PieceType::general, Side::red}},
        {4, 0, {PieceType::general, Side::black}},
        {4, 5, {PieceType::soldier, Side::red}},
        {3, 1, {PieceType::chariot, Side::red}},
        {4, 3, {PieceType::cannon, Side::red}},
        {2, 2, {PieceType::horse, Side::red}},
        {6, 2, {PieceType::horse, Side::red}},
    });
    verify({
        {4, 9, {PieceType::general, Side::red}},
        {4, 0, {PieceType::general, Side::black}},
        {4, 5, {PieceType::soldier, Side::red}},
        {3, 1, {PieceType::chariot, Side::red}},
        {4, 3, {PieceType::cannon, Side::red}},
        {2, 2, {PieceType::horse, Side::red}},
        {6, 2, {PieceType::horse, Side::red}},
        {0, 0, {PieceType::chariot, Side::black}},
        {8, 0, {PieceType::chariot, Side::black}},
        {3, 9, {PieceType::advisor, Side::red}},
        {5, 9, {PieceType::advisor, Side::red}},
    });
    verify({
        {4, 9, {PieceType::general, Side::red}},
        {4, 0, {PieceType::general, Side::black}},
        {4, 5, {PieceType::soldier, Side::red}},
        {3, 1, {PieceType::chariot, Side::red}},
        {0, 1, {PieceType::chariot, Side::red}},
        {2, 2, {PieceType::horse, Side::red}},
        {6, 2, {PieceType::horse, Side::red}},
        {2, 0, {PieceType::elephant, Side::black}},
        {6, 0, {PieceType::elephant, Side::black}},
        {1, 2, {PieceType::cannon, Side::black}},
        {7, 2, {PieceType::cannon, Side::black}},
        {0, 3, {PieceType::soldier, Side::black}},
        {2, 3, {PieceType::soldier, Side::black}},
        {6, 3, {PieceType::soldier, Side::black}},
        {8, 3, {PieceType::soldier, Side::black}},
    });
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

void repeated_joint_chase_loses_against_idle_defense() {
    ChineseChessEngine engine;
    assert(
        engine.restore(
            custom_position(
                Side::black,
                {
                    {4, 9, {PieceType::general, Side::red}},
                    {4, 0, {PieceType::general, Side::black}},
                    {4, 8, {PieceType::advisor, Side::red}},
                    {0, 5, {PieceType::chariot, Side::red}},
                    {3, 5, {PieceType::cannon, Side::red}},
                    {4, 3, {PieceType::horse, Side::black}},
                    {5, 1, {PieceType::chariot, Side::black}},
                }
            )
        ).restored
    );

    for (int cycle = 0; cycle < 2; ++cycle) {
        assert(engine.apply(make_board_move(5, 1, 3, 1)).accepted);
        assert(engine.apply(make_board_move(3, 5, 5, 5)).accepted);
        assert(engine.apply(make_board_move(3, 1, 5, 1)).accepted);
        assert(engine.apply(make_board_move(5, 5, 3, 5)).accepted);
    }

    assert(engine.game_result() == GameResult::first_player_win);
}

void repeated_alternating_multi_target_chase_loses() {
    ChineseChessEngine engine;
    assert(
        engine.restore(
            custom_position(
                Side::red,
                {
                    {4, 9, {PieceType::general, Side::red}},
                    {4, 0, {PieceType::general, Side::black}},
                    {4, 5, {PieceType::soldier, Side::red}},
                    {0, 2, {PieceType::chariot, Side::red}},
                    {8, 5, {PieceType::chariot, Side::red}},
                    {3, 1, {PieceType::cannon, Side::black}},
                    {5, 4, {PieceType::cannon, Side::black}},
                }
            )
        ).restored
    );

    for (int cycle = 0; cycle < 2; ++cycle) {
        assert(engine.apply(make_board_move(0, 2, 0, 1)).accepted);
        assert(engine.apply(make_board_move(3, 1, 3, 2)).accepted);
        assert(engine.apply(make_board_move(8, 5, 8, 4)).accepted);
        assert(engine.apply(make_board_move(5, 4, 5, 5)).accepted);
        assert(engine.apply(make_board_move(0, 1, 0, 2)).accepted);
        assert(engine.apply(make_board_move(3, 2, 3, 1)).accepted);
        assert(engine.apply(make_board_move(8, 4, 8, 5)).accepted);
        assert(engine.apply(make_board_move(5, 5, 5, 4)).accepted);
    }

    assert(engine.game_result() == GameResult::second_player_win);
}

void repeated_equal_exchange_is_drawn() {
    ChineseChessEngine engine;
    assert(
        engine.restore(
            custom_position(
                Side::red,
                {
                    {4, 9, {PieceType::general, Side::red}},
                    {4, 0, {PieceType::general, Side::black}},
                    {4, 5, {PieceType::soldier, Side::red}},
                    {0, 2, {PieceType::chariot, Side::red}},
                    {3, 1, {PieceType::chariot, Side::black}},
                }
            )
        ).restored
    );

    for (int cycle = 0; cycle < 2; ++cycle) {
        assert(engine.apply(make_board_move(0, 2, 0, 1)).accepted);
        assert(engine.apply(make_board_move(3, 1, 3, 2)).accepted);
        assert(engine.apply(make_board_move(0, 1, 0, 2)).accepted);
        assert(engine.apply(make_board_move(3, 2, 3, 1)).accepted);
    }

    assert(engine.game_result() == GameResult::draw);
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
    const auto hard = engine.best_move(Difficulty::hard);
    assert(hard);
    assert(
        std::any_of(
            legal.begin(),
            legal.end(),
            [&hard](const auto& action) {
                return action.kind == hard->kind &&
                    action.arguments == hard->arguments;
            }
        )
    );
    assert(engine.serialize() == before);
    assert(!engine.best_move(Difficulty::master));
}

void unique_seed_mates_are_checked_and_unambiguous() {
    for (int variant = 0; variant < 5; ++variant) {
        const auto rook_x = variant == 1 || variant == 3 ? 8 : 0;
        const auto rook_y = variant <= 1 ? 2 : variant <= 3 ? 3 : 4;
        const auto soldier_y = variant <= 1 ? 5 : variant <= 3 ? 6 : 7;
        std::vector<PositionedPiece> pieces{
            {4, 9, {PieceType::general, Side::red}},
            {4, 0, {PieceType::general, Side::black}},
            {4, soldier_y, {PieceType::soldier, Side::red}},
            {1, 1, {PieceType::horse, Side::red}},
            {7, 1, {PieceType::horse, Side::red}},
            {rook_x, rook_y, {PieceType::chariot, Side::red}},
        };
        if (variant >= 2) {
            pieces.push_back({0, 6, {PieceType::soldier, Side::black}});
            pieces.push_back({8, 6, {PieceType::soldier, Side::black}});
        }
        if (variant >= 3) {
            pieces.push_back({2, 6, {PieceType::soldier, Side::black}});
            pieces.push_back({6, 6, {PieceType::soldier, Side::black}});
        }
        if (variant >= 4) {
            pieces.push_back({1, 7, {PieceType::cannon, Side::black}});
            pieces.push_back({7, 7, {PieceType::cannon, Side::black}});
        }
        ChineseChessEngine candidate;
        const auto restored = candidate.restore(custom_position(Side::red, pieces));
        assert(restored.restored);
        assert(candidate.game_result() == GameResult::ongoing);
        assert(!candidate.is_in_check(Side::black));
        int winning_moves = 0;
        for (const auto& action : candidate.legal_actions()) {
            auto next = candidate;
            if (next.apply(action).accepted &&
                next.game_result() == GameResult::first_player_win) {
                ++winning_moves;
                assert(action.arguments[0] == rook_x);
                assert(action.arguments[1] == rook_y);
                assert(action.arguments[2] == 4);
                assert(action.arguments[3] == rook_y);
                assert(next.is_in_check(Side::black));
            }
        }
        assert(winning_moves == 1);
    }
}

}  // namespace

int main() {
    initial_position_is_stable();
    pikafish_coordinates_are_converted_and_validated();
    pikafish_history_judge_validates_repetition_windows();
    soldier_moves_forward_but_not_sideways_before_the_river();
    horse_leg_and_cannon_screen_are_enforced();
    moving_the_only_screen_between_generals_is_illegal();
    undo_and_serialization_restore_state();
    corrupted_positions_are_rejected();
    legacy_seed_endgames_have_a_winning_first_move();
    unique_seed_mates_are_checked_and_unambiguous();
    wrong_game_type_is_rejected_after_checksum_validation();
    repeated_long_check_loses_for_the_checking_side();
    repeated_unrooted_chase_loses_for_the_chasing_side();
    repeated_joint_chase_loses_against_idle_defense();
    repeated_alternating_multi_target_chase_loses();
    repeated_equal_exchange_is_drawn();
    repeated_idle_moves_are_drawn_instead_of_treated_as_long_block();
    sixty_rounds_without_capture_reaches_the_natural_limit();
    capture_resets_the_natural_limit_counter();
    easy_ai_returns_legal_move_without_mutating_position();
}
