#pragma once

#include "mocs/engine/chinese_chess.hpp"

#include <cstddef>
#include <vector>

namespace mocs::authoring {

enum class ProofStatus { complete, budget_exhausted, not_candidate };

struct ForcedWinProof {
    ProofStatus status{ProofStatus::not_candidate};
    std::size_t visited_nodes{0};
    // One representative defense per winning first move (the longest among
    // replies to the selected strategy, not an optimal mate-distance PV).
    // A line alone is not a proof: the solver checks every defensive reply.
    std::vector<std::vector<engine::EngineAction>> winning_lines;
};

// Offline content verification only; never a replacement for production AI.
// "Complete with no winning lines" means no forced win WITHIN the bound,
// not that the position is lost. Incomplete output cannot establish uniqueness.
ForcedWinProof prove_red_win(
    const engine::ChineseChessEngine& initial,
    unsigned red_move_limit,
    std::size_t node_limit
);

} // namespace mocs::authoring
