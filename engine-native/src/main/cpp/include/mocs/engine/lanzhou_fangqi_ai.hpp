#pragma once

#include "mocs/engine/lanzhou_fangqi.hpp"

#include <optional>

namespace mocs::engine::lanzhou_fangqi {

enum class AiLevel : std::uint8_t { easy, medium, hard, master };

// Returns an action from Game::legal_actions(), or no action for a terminal game.
[[nodiscard]] std::optional<Action> choose_action(const Game& game, AiLevel level);

}  // namespace mocs::engine::lanzhou_fangqi
