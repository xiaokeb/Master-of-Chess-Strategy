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
    // Only used when Pikafish's automatic rule60 would preempt the local
    // request-only referee. The recent window keeps repetition context.
    std::string rule60_safe_initial_fen;
    std::vector<std::string> rule60_safe_moves;
};

} // namespace mocs::engine
