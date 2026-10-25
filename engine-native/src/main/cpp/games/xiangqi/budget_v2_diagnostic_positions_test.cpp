// Rules-only preflight. No choose, NNUE load, sampling, registration or match.
#include "budget_v2_diagnostic_positions.hpp"

#include <iostream>
#include <stdexcept>
#include <string>

namespace {
unsigned checks = 0;
void require(bool condition, const char* message) {
    ++checks;
    if (!condition) throw std::runtime_error(message);
}
}  // namespace

int main() {
    using namespace mocs::engine;
    using namespace mocs::engine::budget_v2;
    try {
        require(diagnostic_positions().size() == 4, "exactly four fixed diagnostic positions");
        for (const auto& item : diagnostic_positions()) {
            auto engine = make_diagnostic_position(item.id);
            const auto before = engine.serialize();
            const auto position = engine.search_position();
            require(before[4] == 4 && before[before.size() - 5] == 1, "rules v4/revision 1");
            require(position.current_fen == item.expected_fen, "fixed original FEN");
            require(position.result == GameResult::ongoing, "nonterminal search snapshot");
            require(position.moves.size() == item.history_plies, "real replay history retained");
            require(engine.is_in_check(Side::red) == item.red_in_check, "checked fixture status");
            require(!engine.is_in_check(Side::black), "nonmoving side not already checked");
            ChineseChessEngine restored;
            require(restored.restore(before).restored, "actual rules restore");
            require(restored.serialize() == before, "canonical restore bytes unchanged");
            require(restored.search_position().moves == position.moves, "restore retains full move history");
            const auto roots = engine.legal_actions();
            require(!roots.empty(), "authoritative legal roots exist");
            require(engine.serialize() == before, "root enumeration is read-only");
            for (const auto& action : roots) {
                auto branch = engine;
                require(branch.apply(action).accepted, "every root accepted by actual rule engine");
                require(branch.undo(), "root move can be undone");
                require(branch.serialize() == before, "root branch undo restores exact history bytes");
            }
            if (item.history_plies == 4) {
                auto rewind = engine;
                for (unsigned ply = 0; ply < 4; ++ply) require(rewind.undo(), "all four original plies undo");
                require(rewind.serialize() == ChineseChessEngine{}.serialize(), "four plies came from standard initial state");
            }
            std::cout << "PASS " << item.name << " history=" << position.moves.size()
                << " roots=" << roots.size() << " checked=" << item.red_in_check << '\n';
        }
        bool rejected = false;
        try { (void)make_diagnostic_position(static_cast<DiagnosticPositionId>(-1)); }
        catch (const std::invalid_argument&) { rejected = true; }
        require(rejected, "unknown position rejected without fallback");
        std::cout << "Diagnostic rules-only preflight: " << checks << " checks passed; no search.\n";
        return 0;
    } catch (const std::exception& error) {
        std::cerr << "FAIL diagnostic rules-only preflight: " << error.what() << '\n';
        return 1;
    }
}
