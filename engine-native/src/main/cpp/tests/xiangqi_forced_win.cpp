#include "xiangqi_forced_win.hpp"

#include <stdexcept>
#include <utility>

namespace mocs::authoring {
namespace {

using namespace engine;
enum class Outcome { win, refuted, unknown };
struct Branch {
    Outcome outcome;
    std::vector<EngineAction> line;
};

class Search {
public:
    explicit Search(std::size_t limit) : limit_(limit) {}

    Branch visit(ChineseChessEngine& board, unsigned red_moves) {
        if (visited == limit_) return {Outcome::unknown, {}};
        ++visited;
        const auto result = board.game_result();
        if (result != GameResult::ongoing) {
            return {result == GameResult::first_player_win ? Outcome::win : Outcome::refuted, {}};
        }
        // The challenge ends immediately after the player's final non-winning
        // move; do not let a later cooperative Black move manufacture success.
        if (red_moves == 0) return {Outcome::refuted, {}};
        const bool red = board.current_player() == 0;
        Branch longest{Outcome::win, {}};
        for (const auto& action : board.legal_actions()) {
            auto child = descend(board, action, red_moves - (red ? 1U : 0U));
            if (child.outcome == Outcome::unknown) return child;
            if (red && child.outcome == Outcome::win) return child;
            if (!red && child.outcome == Outcome::refuted) return child;
            if (!red && child.line.size() > longest.line.size()) longest = std::move(child);
        }
        return red ? Branch{Outcome::refuted, {}} : longest;
    }

    Branch descend(ChineseChessEngine& board, const EngineAction& action, unsigned red_moves) {
        if (!board.apply(action).accepted) throw std::logic_error("solver legal move rejected");
        auto child = visit(board, red_moves);
        if (!board.undo()) throw std::logic_error("solver undo failed");
        if (child.outcome == Outcome::win) child.line.insert(child.line.begin(), action);
        return child;
    }

    std::size_t visited{0};

private:
    std::size_t limit_;
};

} // namespace

ForcedWinProof prove_red_win(
    const engine::ChineseChessEngine& initial,
    const unsigned red_move_limit,
    const std::size_t node_limit
) {
    if (red_move_limit < 1 || red_move_limit > 4 || node_limit == 0) {
        throw std::invalid_argument("proof requires 1..4 Red moves and a positive node budget");
    }
    ForcedWinProof proof;
    if (initial.current_player() != 0 || initial.game_result() != GameResult::ongoing ||
        initial.is_in_check(engine::Side::black)) return proof;

    auto board = initial; // Never mutate the caller, including on exhausted searches.
    Search search(node_limit);
    proof.status = ProofStatus::complete;
    for (const auto& action : board.legal_actions()) {
        auto branch = search.descend(board, action, red_move_limit - 1);
        if (branch.outcome == Outcome::unknown) {
            proof.status = ProofStatus::budget_exhausted;
            break;
        }
        if (branch.outcome == Outcome::win) proof.winning_lines.push_back(std::move(branch.line));
    }
    proof.visited_nodes = search.visited;
    return proof;
}

} // namespace mocs::authoring
