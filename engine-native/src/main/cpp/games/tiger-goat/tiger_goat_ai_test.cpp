#include "mocs/engine/tiger_goat_ai.hpp"

#include <algorithm>
#include <cassert>
#include <cstdio>
#include <cstdlib>
#include <initializer_list>
#include <new>
#include <utility>

// This allocator override belongs only to this standalone test executable.
namespace allocation_probe {
int remaining = -1;
}
void* operator new(const std::size_t size) {
    if (allocation_probe::remaining >= 0 && allocation_probe::remaining-- == 0) {
        allocation_probe::remaining = -1;
        throw std::bad_alloc{};
    }
    if (void* memory = std::malloc(size == 0 ? 1 : size)) return memory;
    throw std::bad_alloc{};
}
void* operator new[](const std::size_t size) { return ::operator new(size); }
void operator delete(void* memory) noexcept { std::free(memory); }
void operator delete[](void* memory) noexcept { std::free(memory); }
void operator delete(void* memory, std::size_t) noexcept { std::free(memory); }
void operator delete[](void* memory, std::size_t) noexcept { std::free(memory); }

namespace {
namespace tg = mocs::engine::tiger_goat;

tg::SearchLimits limits(const unsigned depth = 2) {
    return {depth, 1000000, std::chrono::milliseconds{30000}};
}

tg::MatchState seed(const std::initializer_list<int> tigers,
                   const std::initializer_list<int> goats,
                   const std::uint8_t side = tg::tiger,
                   const std::uint8_t reserve = 0) {
    tg::MatchState state{};
    state.board.fill(tg::empty);
    for (const int point : tigers) state.board[static_cast<std::size_t>(point)] = tg::tiger;
    for (const int point : goats) state.board[static_cast<std::size_t>(point)] = tg::goat;
    state.phase = tg::Phase::play;
    state.to_move = side;
    state.reserve = reserve;
    state.captured = static_cast<std::uint8_t>(22 - goats.size() - reserve);
    return state;
}

void verify_legal_unchanged(tg::TigerGoatGame& game, const tg::SearchResult& result,
                          const std::vector<std::uint8_t>& original) {
    assert(result.action);
    const auto actions = game.legal_actions();
    assert(std::find(actions.begin(), actions.end(), *result.action) != actions.end());
    assert(game.serialize() == original);
    assert(game.apply(*result.action) == tg::ApplyError::none);
    assert(game.undo() && game.serialize() == original);
}

// Deliberately independent unpruned oracle for small fixtures. Its leaf formula
// follows the documented public heuristic, not the search implementation.
int reference_leaf(const tg::MatchState& state, const std::uint8_t side) {
    if (state.outcome != tg::Outcome::ongoing) {
        const bool tiger_won = state.outcome == tg::Outcome::tiger_wins;
        return tiger_won == (side == tg::tiger) ? 100000 : -100000;
    }
    const auto board = tg::official_topology();
    int total = 120 * state.captured;
    for (std::size_t point = 0; point < tg::official_point_count; ++point) {
        if (state.board[point] == tg::tiger) {
            int free = 0;
            for (std::size_t next = 0; next < tg::official_point_count; ++next) {
                if (board.connected[point][next] && state.board[next] == tg::empty
                    && (board.points[point].x == board.points[next].x
                        || board.points[point].y == board.points[next].y)) ++free;
            }
            total += free == 0 ? -80 : 12 * free;
        } else if (state.board[point] == tg::goat) {
            for (std::size_t next = 0; next < tg::official_point_count; ++next) {
                if (board.connected[point][next] && state.board[next] == tg::goat) {
                    total -= 2;
                    break;
                }
            }
        }
    }
    return side == tg::tiger ? total : -total;
}

int reference_value(tg::TigerGoatGame game, const unsigned turns,
                    const std::uint8_t root) {
    if (turns == 0 || game.state().outcome != tg::Outcome::ongoing) {
        return reference_leaf(game.state(), root);
    }
    const auto actions = game.legal_actions();
    assert(!actions.empty());
    const auto side = game.state().to_move;
    int best = side == root ? -100001 : 100001;
    for (const auto& action : actions) {
        tg::TigerGoatGame child = game;
        assert(child.apply(action) == tg::ApplyError::none);
        const auto next = turns - (child.state().to_move == side ? 0u : 1u);
        const int score = reference_value(std::move(child), next, root);
        best = side == root ? std::max(best, score) : std::min(best, score);
    }
    return best;
}

void test_terminal_and_invalid_limits() {
    tg::TigerGoatGame game;
    assert(game.reset_to_position(seed({0, 4}, {1, 2, 3, 5, 9, 10, 14})));
    const auto result = tg::search_action(game, limits());
    assert(result.stop == tg::SearchStop::no_legal_action && !result.action);
    assert(!result.score && result.visited_nodes == 0);
    assert(game.reset_to_position(seed({0, 4}, {1, 2, 3, 5, 9, 10, 14, 15, 16, 17})));
    assert(tg::search_action(game).stop == tg::SearchStop::no_legal_action);
    game.reset();
    for (const unsigned depth : {0u, tg::maximum_search_turn_depth + 1u}) {
        auto invalid = limits(depth);
        assert(tg::search_action(game, invalid).stop == tg::SearchStop::invalid_limits);
    }
    auto invalid = limits();
    invalid.time_limit = std::chrono::milliseconds{-1};
    assert(tg::search_action(game, invalid).stop == tg::SearchStop::invalid_limits);
    invalid.time_limit = tg::maximum_search_time + std::chrono::milliseconds{1};
    assert(tg::search_action(game, invalid).stop == tg::SearchStop::invalid_limits);
}

void test_opening_and_goat_placement() {
    tg::TigerGoatGame game;
    auto original = game.serialize();
    auto result = tg::search_action(game, limits(1));
    assert(result.stop == tg::SearchStop::depth_completed && result.completed_turn_depth == 1);
    assert(result.action->kind == tg::ActionKind::remove_goat);
    verify_legal_unchanged(game, result, original);
    assert(game.apply(*result.action) == tg::ApplyError::none);
    original = game.serialize();
    result = tg::search_action(game, limits(2));
    assert(result.stop == tg::SearchStop::depth_completed);
    assert(result.action->kind == tg::ActionKind::place_goat);
    assert(*result.score == reference_value(game, 2, tg::goat));
    verify_legal_unchanged(game, result, original);
}

void test_goat_steps_and_max_min_oracle() {
    for (const auto side : {tg::tiger, tg::goat}) {
        tg::TigerGoatGame game;
        assert(game.reset_to_position(seed({0, 4}, {6, 7, 8, 16, 17, 18, 20, 24}, side)));
        const auto before = game.serialize();
        const auto result = tg::search_action(game, limits(2));
        assert(result.stop == tg::SearchStop::depth_completed);
        assert(*result.score == reference_value(game, 2, side));
        tg::TigerGoatGame chosen = game;
        assert(chosen.apply(*result.action) == tg::ApplyError::none);
        assert(reference_value(chosen, 1, side) == *result.score);
        verify_legal_unchanged(game, result, before);
    }
}

void test_complete_same_side_chain_and_chain_entry() {
    tg::TigerGoatGame game;
    assert(game.reset_to_position(seed({12, 0}, {13, 19, 1, 2, 3, 4, 5, 6, 9})));
    const auto before = game.serialize();
    const auto result = tg::search_action(game, limits(1));
    assert(result.stop == tg::SearchStop::depth_completed && *result.score == 100000);
    assert((*result.action == tg::Action{tg::ActionKind::capture, 12, 14}));
    verify_legal_unchanged(game, result, before);
    assert(game.apply(*result.action) == tg::ApplyError::none);
    assert(game.state().phase == tg::Phase::capture_chain && game.state().to_move == tg::tiger);
    const auto chain = game.serialize();
    const auto continuation = tg::search_action(game, limits(1));
    assert(*continuation.score == 100000);
    assert((*continuation.action == tg::Action{tg::ActionKind::capture, 14, 24}));
    verify_legal_unchanged(game, continuation, chain);
}

void test_voluntary_end_and_visited_landing() {
    tg::TigerGoatGame game;
    assert(game.reset_to_position(seed({12, 0}, {13, 19, 23, 17, 21, 1, 2, 3, 4, 5, 6, 9})));
    assert(game.apply({tg::ActionKind::capture, 12, 14}) == tg::ApplyError::none);
    auto actions = game.legal_actions();
    assert(std::find(actions.begin(), actions.end(), tg::Action{tg::ActionKind::end_chain, -1, -1}) != actions.end());
    auto before = game.serialize();
    auto result = tg::search_action(game, limits(2));
    assert(result.stop == tg::SearchStop::depth_completed);
    assert(*result.score == reference_value(game, 2, tg::tiger));
    verify_legal_unchanged(game, result, before);
    assert(game.apply({tg::ActionKind::capture, 14, 24}) == tg::ApplyError::none);
    assert(game.apply({tg::ActionKind::capture, 24, 22}) == tg::ApplyError::none);
    actions = game.legal_actions();
    assert(std::find(actions.begin(), actions.end(), tg::Action{tg::ActionKind::capture, 22, 12}) == actions.end());
    before = game.serialize();
    result = tg::search_action(game, limits(1));
    verify_legal_unchanged(game, result, before);
}

void test_goat_forced_pass() {
    tg::TigerGoatGame game;
    assert(game.reset_to_position(seed({10, 14}, {25, 26, 27, 28, 29, 30, 31, 32, 33, 34, 35, 36}, tg::goat)));
    const auto before = game.serialize();
    const auto result = tg::search_action(game, limits(2));
    assert(result.stop == tg::SearchStop::depth_completed);
    assert((*result.action == tg::Action{tg::ActionKind::pass_goat, -1, -1}));
    assert(*result.score == reference_value(game, 2, tg::goat));
    verify_legal_unchanged(game, result, before);
}

struct CancelProbe {
    std::uint64_t calls = 0;
    std::uint64_t stop_at = 0;
    static bool poll(void* context) noexcept {
        auto& probe = *static_cast<CancelProbe*>(context);
        return ++probe.calls >= probe.stop_at && probe.stop_at != 0;
    }
};

void test_budget_and_cancellation() {
    tg::TigerGoatGame game;
    const auto original = game.serialize();
    auto budget = limits(3);
    budget.node_limit = 0;
    auto result = tg::search_action(game, budget);
    assert(result.stop == tg::SearchStop::node_limit && result.visited_nodes == 0 && !result.action);
    budget = limits(3);
    budget.time_limit = std::chrono::milliseconds{0};
    result = tg::search_action(game, budget);
    assert(result.stop == tg::SearchStop::time_limit && result.visited_nodes == 0 && !result.action);
    CancelProbe cancellation{0, 1};
    result = tg::search_action(game, limits(), {CancelProbe::poll, &cancellation});
    assert(result.stop == tg::SearchStop::cancelled && !result.action);
    budget = limits(3);
    budget.node_limit = 2;
    result = tg::search_action(game, budget);
    assert(result.stop == tg::SearchStop::node_limit && result.visited_nodes == 2);
    assert(result.completed_turn_depth == 0 && !result.score);
    verify_legal_unchanged(game, result, original);
    assert(game.serialize() == original);
}

void test_complete_iteration_survives_interrupt_and_repeatability() {
    tg::TigerGoatGame game;
    const auto before = game.serialize();
    CancelProbe count_only{};
    const auto complete = tg::search_action(game, limits(1), {CancelProbe::poll, &count_only});
    assert(complete.stop == tg::SearchStop::depth_completed);
    CancelProbe cancel{0, count_only.calls + 3};
    auto interrupted = tg::search_action(game, limits(3), {CancelProbe::poll, &cancel});
    assert(interrupted.stop == tg::SearchStop::cancelled && interrupted.completed_turn_depth == 1);
    assert(interrupted.action == complete.action && interrupted.score == complete.score);
    auto budget = limits(3);
    budget.node_limit = complete.visited_nodes + 1;
    interrupted = tg::search_action(game, budget);
    assert(interrupted.stop == tg::SearchStop::node_limit && interrupted.completed_turn_depth == 1);
    assert(interrupted.action == complete.action && interrupted.score == complete.score);
    const auto repeat = tg::search_action(game, limits(1));
    assert(repeat.action == complete.action && repeat.score == complete.score);
    assert(repeat.visited_nodes == complete.visited_nodes && game.serialize() == before);
}

void test_allocation_failure_preserves_history() {
    tg::TigerGoatGame game;
    assert(game.apply({tg::ActionKind::remove_goat, 6, -1}) == tg::ApplyError::none);
    const auto before = game.serialize();
    unsigned failures = 0;
    for (int fail_at = 0; fail_at < 32; ++fail_at) {
        allocation_probe::remaining = fail_at;
        try {
            static_cast<void>(tg::search_action(game, limits(2)));
        } catch (const std::bad_alloc&) {
            ++failures;
        }
        allocation_probe::remaining = -1;
        assert(game.serialize() == before && game.action_count() == 1);
    }
    assert(failures >= 8);
    assert(game.undo() && game.state().phase == tg::Phase::opening_removal);
}

void test_full_history_is_not_a_searchable_action() {
    tg::TigerGoatGame game;
    assert(game.reset_to_position(seed({0, 4}, {6, 7, 8, 16, 17, 18, 20, 24})));
    const tg::Action cycle[] = {
        {tg::ActionKind::step, 0, 1}, {tg::ActionKind::step, 6, 11},
        {tg::ActionKind::step, 1, 0}, {tg::ActionKind::step, 11, 6},
    };
    for (unsigned index = 0; index < 65535; ++index) {
        assert(game.apply(cycle[index % 4]) == tg::ApplyError::none);
    }
    const auto before = game.serialize();
    const auto result = tg::search_action(game, limits(1));
    assert(result.stop == tg::SearchStop::rule_rejected && !result.action);
    assert(!result.score && result.visited_nodes == 1 && game.serialize() == before);
}

}  // namespace

int main() {
    test_terminal_and_invalid_limits();
    test_opening_and_goat_placement();
    test_goat_steps_and_max_min_oracle();
    test_complete_same_side_chain_and_chain_entry();
    test_voluntary_end_and_visited_landing();
    test_goat_forced_pass();
    test_budget_and_cancellation();
    test_complete_iteration_survives_interrupt_and_repeatability();
    test_allocation_failure_preserves_history();
    test_full_history_is_not_a_searchable_action();
    std::puts("tiger-goat AI: 10 scenario groups passed");
}
