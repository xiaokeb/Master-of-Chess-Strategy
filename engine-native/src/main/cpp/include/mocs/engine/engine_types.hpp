#pragma once

#include <array>
#include <cstdint>

namespace mocs::engine {

// Numeric values are shared with Kotlin and persisted content; never reorder them.
enum class GameType : std::uint8_t {
    chinese_chess = 0,
    go = 1,
    standard_mahjong = 2,
    lanzhou_mahjong = 3,
    lanzhou_square_chess = 4,
    tiger_and_goat = 5,
};

enum class Difficulty : std::uint8_t {
    easy = 0,
    medium = 1,
    hard = 2,
    master = 3,
};

enum class GameResult : std::uint8_t {
    ongoing = 0,
    first_player_win = 1,
    second_player_win = 2,
    draw = 3,
};

enum class EngineError : std::uint8_t {
    none = 0,
    illegal_action = 1,
    invalid_state = 2,
    corrupted_data = 3,
    unsupported = 4,
};

struct EngineAction {
    std::uint16_t kind{};
    std::array<std::int32_t, 4> arguments{};
};

struct ActionResult {
    bool accepted{};
    EngineError error{EngineError::none};
};

struct RestoreResult {
    bool restored{};
    EngineError error{EngineError::none};
};

}  // namespace mocs::engine
