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
    const std::string& network_path,
    Difficulty difficulty = Difficulty::master,
    std::optional<std::uint64_t> selection_seed = std::nullopt
);

// Clear search history between calibration games; keep the verified NNUE loaded.
void reset_pikafish_search();

/**
 * Replays one completed repetition window through Pikafish's rule judge.
 *
 * The initial FEN and UCI moves must describe a legal history. A terminal
 * result is returned only when Pikafish can validate and adjudicate it;
 * malformed or non-terminal input returns std::nullopt so the caller can use
 * its conservative local fallback.
 */
[[nodiscard]] std::optional<GameResult> adjudicate_pikafish_repetition(
    const std::string& initial_fen,
    const std::vector<std::string>& moves
) noexcept;

}  // namespace mocs::engine
