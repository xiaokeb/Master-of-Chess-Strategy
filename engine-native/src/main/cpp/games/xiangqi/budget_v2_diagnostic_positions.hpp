#pragma once

#include "mocs/engine/chinese_chess.hpp"

#include <array>
#include <cstddef>

namespace mocs::engine::budget_v2 {

enum class DiagnosticPositionId { suite1_first, suite1_second, qsearch_initial, qsearch_checked };

struct DiagnosticPositionDescriptor {
    DiagnosticPositionId id;
    const char* name;
    const char* source;
    const char* expected_fen;
    std::size_t history_plies;
    bool red_in_check;
};

// Fixed, previously exposed inputs. This catalog neither samples nor searches.
[[nodiscard]] const std::array<DiagnosticPositionDescriptor, 4>& diagnostic_positions() noexcept;

// Replays the two historical four-ply lines. The checked fixture is imported
// as a rules-v4/revision-1 position, never by downgrading to legacy adjudication.
[[nodiscard]] ChineseChessEngine make_diagnostic_position(DiagnosticPositionId id);

}  // namespace mocs::engine::budget_v2
