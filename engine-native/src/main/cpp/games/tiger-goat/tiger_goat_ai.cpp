#include "mocs/engine/tiger_goat_ai.hpp"

#include <algorithm>

namespace mocs::engine::tiger_goat {
namespace {

constexpr int win_score = 100000;
constexpr int infinity = win_score + 1;

int evaluate(const MatchState& state, const std::uint8_t root_side) noexcept {
    if (state.outcome != Outcome::ongoing) {
        const auto winner = state.outcome == Outcome::tiger_wins ? tiger : goat;
        return winner == root_side ? win_score : -win_score;
    }
    const auto& topology = official_topology();
    int freedom = 0;
    int blocked = 0;
    int supported_goats = 0;
    for (std::size_t from = 0; from < official_point_count; ++from) {
        int exits = 0;
        bool supported = false;
        for (std::size_t to = 0; to < official_point_count; ++to) {
            if (!topology.connected[from][to]) continue;
            const bool straight = topology.points[from].x == topology.points[to].x
                || topology.points[from].y == topology.points[to].y;
            exits += straight && state.board[to] == empty ? 1 : 0;
            supported |= state.board[to] == goat;
        }
        if (state.board[from] == tiger) {
            freedom += exits;
            blocked += exits == 0 ? 1 : 0;
        } else if (state.board[from] == goat && supported) {
            ++supported_goats;
        }
    }
    // Captures matter to tiger; connected goats and restricted tiger movement
    // matter to goat. Only the rules engine decides actual wins or legal jumps.
    const int tiger_score = state.captured * 120 + freedom * 12
        - blocked * 80 - supported_goats * 2;
    return root_side == tiger ? tiger_score : -tiger_score;
}

class UndoAction {
public:
    explicit UndoAction(TigerGoatGame& game) noexcept : game_(game) {}
    ~UndoAction() { static_cast<void>(game_.undo()); }
    UndoAction(const UndoAction&) = delete;
    UndoAction& operator=(const UndoAction&) = delete;
private:
    TigerGoatGame& game_;
};

struct Search {
    SearchLimits limits;
    SearchCancellation cancellation;
    std::chrono::steady_clock::time_point started;
    std::uint8_t root_side;
    SearchResult result;
    bool interrupted = false;

    bool stop_requested() {
        if (interrupted) return true;
        if (cancellation.requested && cancellation.requested(cancellation.context)) {
            result.stop = SearchStop::cancelled;
        } else if (std::chrono::steady_clock::now() - started >= limits.time_limit) {
            result.stop = SearchStop::time_limit;
        } else if (result.visited_nodes >= limits.node_limit) {
            result.stop = SearchStop::node_limit;
        } else {
            return false;
        }
        interrupted = true;
        return true;
    }

    bool apply(TigerGoatGame& game, const Action& action) {
        if (stop_requested()) return false;
        ++result.visited_nodes;
        if (game.apply(action) == ApplyError::none) return true;
        // A full existing history can reject even an enumerated board action.
        // Do not reinterpret that storage limit as a win, pass, or searched leaf.
        result.stop = SearchStop::rule_rejected;
        interrupted = true;
        return false;
    }

    int value(TigerGoatGame& game, const unsigned turns, int alpha, int beta) {
        if (stop_requested()) return 0;
        if (game.state().outcome != Outcome::ongoing || turns == 0) {
            return evaluate(game.state(), root_side);
        }
        const auto actions = game.legal_actions();
        if (stop_requested()) return 0;
        if (actions.empty()) return evaluate(game.state(), root_side);
        const auto side = game.state().to_move;
        const bool maximizing = side == root_side;
        int best = maximizing ? -infinity : infinity;
        for (const auto& action : actions) {
            if (!apply(game, action)) return 0;
            UndoAction undo(game);
            const unsigned next_turns = turns - (game.state().to_move != side ? 1u : 0u);
            const int child = value(game, next_turns, alpha, beta);
            if (interrupted) return 0;
            best = maximizing ? std::max(best, child) : std::min(best, child);
            if (maximizing) alpha = std::max(alpha, best);
            else beta = std::min(beta, best);
            if (alpha >= beta) break;
        }
        return best;
    }

    void iteration(TigerGoatGame& game, const std::vector<Action>& actions,
                   const unsigned turns) {
        int best_score = -infinity;
        Action best = actions.front();
        int alpha = -infinity;
        for (const auto& action : actions) {
            if (!apply(game, action)) return;
            UndoAction undo(game);
            if (!result.action) result.action = action;
            const unsigned next_turns = turns
                - (game.state().to_move != root_side ? 1u : 0u);
            const int score = value(game, next_turns, alpha, infinity);
            if (interrupted) return;
            if (score > best_score) {
                best_score = score;
                best = action;
            }
            alpha = std::max(alpha, best_score);
        }
        // Publish only a complete root iteration, never a favourable prefix.
        result.action = best;
        result.score = best_score;
        result.completed_turn_depth = turns;
        result.stop = SearchStop::depth_completed;
    }
};

}  // namespace

SearchResult search_action(const TigerGoatGame& game, const SearchLimits limits,
                           const SearchCancellation cancellation) {
    if (limits.turn_depth == 0 || limits.turn_depth > maximum_search_turn_depth
        || limits.time_limit.count() < 0 || limits.time_limit > maximum_search_time) {
        SearchResult result;
        result.stop = SearchStop::invalid_limits;
        return result;
    }
    if (game.state().outcome != Outcome::ongoing) return {};
    Search search{limits, cancellation, std::chrono::steady_clock::now(),
                  game.state().to_move, {}, false};
    if (search.stop_requested()) return search.result;
    TigerGoatGame working = game;
    if (search.stop_requested()) return search.result;
    const auto actions = working.legal_actions();
    if (search.stop_requested()) return search.result;
    if (actions.empty()) return search.result;
    // Same-side recursion can only be a tiger chain. Each continuation removes
    // a goat and forbids revisiting a landing; end_chain changes side. Therefore
    // it is finite without inventing an action-count cutoff or changing rules.
    for (unsigned depth = 1; depth <= limits.turn_depth; ++depth) {
        search.iteration(working, actions, depth);
        if (search.interrupted) break;
    }
    return search.result;
}

}  // namespace mocs::engine::tiger_goat
