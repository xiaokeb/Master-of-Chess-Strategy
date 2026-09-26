#pragma once

#include "mocs/engine/engine_types.hpp"

#include <string>
#include <vector>

namespace mocs::engine {

// An owned snapshot: the root FEN alone cannot convey repetition/check history.
struct ChineseChessSearchPosition {
    std::string initial_fen;
    std::vector<std::string> moves;
    std::string current_fen;
    GameResult result{GameResult::ongoing};
};

} // namespace mocs::engine
