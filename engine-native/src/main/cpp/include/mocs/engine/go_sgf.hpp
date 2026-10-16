#pragma once

#include "mocs/engine/go.hpp"

#include <string>
#include <string_view>

namespace mocs::engine {

// Exports a single FF[4] Go main line. The current rules slice has no
// handicap setup, variations, annotations, or agreed terminal result.
[[nodiscard]] std::string export_go_sgf(const GoEngine& game);

// Imports one main line atomically, replaying every move through GoEngine.
// Unsupported setup, variations, rules or komi return corrupted_data.
[[nodiscard]] RestoreResult restore_go_sgf(GoEngine& game, std::string_view sgf);

}  // namespace mocs::engine
