#include "mocs/engine/lanzhou_fangqi_ai.hpp"

#include <algorithm>
#include <chrono>
#include <vector>

namespace mocs::engine::lanzhou_fangqi {
namespace {

struct Profile {
    int opponent_turns;
    int node_limit;
    int branch_limit;
    int time_limit_ms;
};

constexpr Profile profile(AiLevel level) noexcept {
    switch (level) {
        case AiLevel::easy: return {0, 200, 8, 100};
        case AiLevel::medium: return {1, 1200, 10, 300};
        case AiLevel::hard: return {2, 5000, 12, 800};
        case AiLevel::master: return {3, 16000, 14, 1500};
    }
    return {0, 200, 8, 100};
}

int potential(const Board& board, std::uint8_t side) noexcept {
    int score = 0;
    for (int span = 1; span < board_width; ++span) {
        for (int y = 0; y + span < board_height; ++y) {
            for (int x = 0; x + span < board_width; ++x) {
                const std::uint8_t corners[] = {
                    board[point_index(x, y)],
                    board[point_index(x + span, y)],
                    board[point_index(x, y + span)],
                    board[point_index(x + span, y + span)],
                };
                int own = 0;
                int vacant = 0;
                for (const auto piece : corners) {
                    own += piece == side ? 1 : 0;
                    vacant += piece == empty ? 1 : 0;
                }
                if (own == 3 && vacant == 1) score += 12;
                if (own == 2 && vacant == 2) score += 2;
            }
        }
    }
    return score;
}

int evaluate(const Game& game, std::uint8_t side) noexcept {
    const auto& position = game.position();
    const auto other = side == red ? black : red;
    if (position.outcome != Outcome::ongoing) {
        const auto winner = position.outcome == Outcome::red_wins ? red : black;
        return winner == side ? 100000 : -100000;
    }
    int score = (piece_count(position.board, side) - piece_count(position.board, other)) * 1000;
    score += potential(position.board, side) - potential(position.board, other);
    score += (has_step(position.board, side) ? 20 : -20);
    score -= (has_step(position.board, other) ? 20 : -20);
    if (position.captures_remaining > 0) {
        score += (position.to_play == side ? 1 : -1)
            * position.captures_remaining * 900;
    }
    return score;
}

struct Candidate {
    Action action;
    Game next;
    int estimate;
};

std::vector<Candidate> ordered_children(
    const Game& game,
    std::uint8_t side,
    int branch_limit
) {
    std::vector<Candidate> children;
    for (const auto& action : game.legal_actions()) {
        Game next = game;
        if (next.apply(action).accepted()) {
            children.push_back({action, std::move(next), 0});
            children.back().estimate = evaluate(children.back().next, side);
        }
    }
    const bool maximizing = game.position().to_play == side;
    std::stable_sort(children.begin(), children.end(), [maximizing](
        const Candidate& left, const Candidate& right
    ) {
        return maximizing ? left.estimate > right.estimate
                          : left.estimate < right.estimate;
    });
    if (branch_limit > 0 && children.size() > static_cast<std::size_t>(branch_limit)) {
        children.resize(static_cast<std::size_t>(branch_limit));
    }
    return children;
}

int search(
    const Game& game,
    const std::uint8_t side,
    const int remaining_turns,
    int& remaining_nodes,
    int alpha,
    int beta,
    const int branch_limit,
    const std::chrono::steady_clock::time_point deadline
) {
    if (game.position().outcome != Outcome::ongoing || remaining_turns < 0
        || remaining_nodes <= 0 || std::chrono::steady_clock::now() >= deadline) {
        return evaluate(game, side);
    }
    --remaining_nodes;
    auto children = ordered_children(game, side, branch_limit);
    if (children.empty()) return evaluate(game, side);
    const bool maximizing = game.position().to_play == side;
    int best = maximizing ? -100001 : 100001;
    for (const auto& child : children) {
        const int next_turns = remaining_turns
            - (child.next.position().to_play != game.position().to_play ? 1 : 0);
        const int value = search(
            child.next, side, next_turns, remaining_nodes, alpha, beta, branch_limit,
            deadline
        );
        best = maximizing ? std::max(best, value) : std::min(best, value);
        if (maximizing) alpha = std::max(alpha, best);
        else beta = std::min(beta, best);
        if (alpha >= beta || remaining_nodes <= 0
            || std::chrono::steady_clock::now() >= deadline) break;
    }
    return best;
}

}  // namespace

std::optional<Action> choose_action(const Game& game, AiLevel level) {
    if (game.position().outcome != Outcome::ongoing) return std::nullopt;
    const auto settings = profile(level);
    const auto side = game.position().to_play;
    auto children = ordered_children(game, side, 0);
    if (children.empty()) return std::nullopt;
    if (settings.opponent_turns == 0) return children.front().action;

    const auto deadline = std::chrono::steady_clock::now()
        + std::chrono::milliseconds(settings.time_limit_ms);
    int remaining_nodes = settings.node_limit;
    int best_score = -100001;
    Action best = children.front().action;
    for (const auto& child : children) {
        const int next_turns = settings.opponent_turns
            - (child.next.position().to_play != side ? 1 : 0);
        const int score = search(child.next, side, next_turns, remaining_nodes,
            -100001, 100001, settings.branch_limit, deadline);
        if (score > best_score) {
            best_score = score;
            best = child.action;
        }
        if (remaining_nodes <= 0 || std::chrono::steady_clock::now() >= deadline) break;
    }
    return best;
}

}  // namespace mocs::engine::lanzhou_fangqi
