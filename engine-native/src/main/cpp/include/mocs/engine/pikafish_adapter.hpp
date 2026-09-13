#pragma once

#include "mocs/engine/engine_types.hpp"

#include <cstdint>
#include <optional>
#include <string>
#include <string_view>
#include <vector>

namespace mocs::engine {

inline constexpr std::uintmax_t pikafish_network_size = 50'706'378;
inline constexpr std::int64_t pikafish_move_time_millis = 1'200;

/**
 * Converts and validates a Pikafish UCI move against the authoritative rule
 * engine move list. Invalid or stale engine output is never applied.
 */
[[nodiscard]] std::optional<EngineAction> decode_pikafish_move(
    std::string_view move,
    const std::vector<EngineAction>& legal_actions
) noexcept;

/**
 * Runs the bundled Pikafish engine for one position.
 *
 * Calls are serialized because Pikafish owns mutable search threads and a
 * transposition table. The network path must point to the verified private
 * app copy of the bundled NNUE asset.
 */
[[nodiscard]] std::optional<EngineAction> choose_pikafish_move(
    const std::string& fen,
    const std::vector<EngineAction>& legal_actions,
    const std::string& network_path
);

}  // namespace mocs::engine
