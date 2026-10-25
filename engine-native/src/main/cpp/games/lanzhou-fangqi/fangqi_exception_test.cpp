#include "mocs/engine/lanzhou_fangqi.hpp"

#ifdef NDEBUG
#error "Fangqi exception tests must be compiled with assertions enabled (-UNDEBUG)"
#endif

#include <algorithm>
#include <cstdlib>
#include <iostream>
#include <limits>
#include <new>
#include <string>
#include <utility>
#include <vector>

namespace allocation_probe {
int remaining = -1;
int attempts = 0;
}

// This allocator belongs only to the standalone fault-injection executable.
void* operator new(const std::size_t size) {
    if (allocation_probe::remaining >= 0) {
        ++allocation_probe::attempts;
        if (allocation_probe::remaining-- == 0) {
            allocation_probe::remaining = -1;
            throw std::bad_alloc{};
        }
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
namespace fq = mocs::engine::lanzhou_fangqi;
int checks = 0;
int injected_failures = 0;

void require(bool condition, const char* message) {
    ++checks;
    if (!condition) {
        std::cerr << "FAIL: " << message << '\n';
        std::exit(1);
    }
}

bool same_action(const fq::Action& left, const fq::Action& right) {
    return left.kind == right.kind && left.player == right.player
        && left.from == right.from && left.to == right.to;
}

bool same_actions(const std::vector<fq::Action>& left, const std::vector<fq::Action>& right) {
    return left.size() == right.size() && std::equal(left.begin(), left.end(), right.begin(), same_action);
}

bool same_position(const fq::Position& left, const fq::Position& right) {
    return left.board == right.board && left.to_play == right.to_play
        && left.captures_remaining == right.captures_remaining && left.outcome == right.outcome;
}

std::vector<std::string> undo_trace(fq::Game game) {
    std::vector<std::string> result;
    while (game.undo()) result.push_back(game.serialize());
    return result;
}

struct Observation {
    std::string saved;
    fq::Position position;
    std::vector<fq::Action> actions;
    std::vector<fq::Action> legal;
    std::size_t history_size;
    std::vector<std::string> undo;
};

Observation observe(const fq::Game& game) {
    return {game.serialize(), game.position(), game.actions(), game.legal_actions(),
        game.history_size(), undo_trace(game)};
}

void require_same(const fq::Game& actual, const Observation& expected) {
    require(actual.serialize() == expected.saved, "failed action preserves canonical serialized history");
    require(same_position(actual.position(), expected.position), "failed action preserves board/player/pending/outcome");
    require(same_actions(actual.actions(), expected.actions), "failed action preserves every accepted action");
    require(same_actions(actual.legal_actions(), expected.legal), "failed action preserves the legal action set/order");
    require(actual.history_size() == expected.history_size, "failed action preserves complete history size");
    require(undo_trace(actual) == expected.undo, "failed action preserves complete whole-turn undo behavior");
}

enum class Stage { opening, multi_square_move, first_capture, last_capture, terminal_capture };

fq::Game fixture(const Stage stage) {
    if (stage == Stage::opening) return fq::Game{};
    fq::Board board{};
    for (const auto point : {0, 2, 14, 8, 9, 15, 23}) board[static_cast<std::size_t>(point)] = fq::red;
    board[40] = fq::black;
    if (stage != Stage::terminal_capture) {
        board[41] = fq::black;
        board[48] = fq::black;
    }
    auto seed = fq::Game::from_position(board, fq::red);
    require(seed.has_value(), "fault fixture is a valid authored position");
    auto game = std::move(*seed);
    if (stage != Stage::multi_square_move) {
        require(game.move(fq::red, 2, 3, 2, 2).accepted(), "fixture creates manual capture entitlement");
    }
    if (stage == Stage::last_capture) {
        require(game.capture(fq::red, 6, 6).accepted(), "fixture records the first selected capture");
    }
    return game;
}

fq::Action action_for(const Stage stage) {
    switch (stage) {
        case Stage::opening: return {fq::ActionKind::move, fq::red, 35, 28};
        case Stage::multi_square_move: return {fq::ActionKind::move, fq::red, 23, 16};
        case Stage::first_capture: return {fq::ActionKind::capture, fq::red, 48, 48};
        case Stage::last_capture: return {fq::ActionKind::capture, fq::red, 40, 40};
        case Stage::terminal_capture: return {fq::ActionKind::capture, fq::red, 40, 40};
    }
    std::abort();
}

void every_actual_allocation_failure(const Stage stage) {
    const auto action = action_for(stage);
    auto reference = fixture(stage);
    allocation_probe::attempts = 0;
    allocation_probe::remaining = std::numeric_limits<int>::max();
    const bool accepted = reference.apply(action).accepted();
    allocation_probe::remaining = -1;
    const int allocations = allocation_probe::attempts;
    require(accepted && allocations > 0 && allocations < 128, "control action succeeds with a bounded measured allocation count");
    const auto expected_after = observe(reference);

    for (int failing_allocation = 0; failing_allocation < allocations; ++failing_allocation) {
        auto game = fixture(stage);
        const auto before = observe(game);
        allocation_probe::attempts = 0;
        allocation_probe::remaining = failing_allocation;
        bool failed = false;
        try { static_cast<void>(game.apply(action)); }
        catch (const std::bad_alloc&) { failed = true; }
        allocation_probe::remaining = -1;
        require(failed && allocation_probe::attempts == failing_allocation + 1,
            "each measured allocation point actually throws, rather than silently succeeding");
        ++injected_failures;
        require_same(game, before);
        require(game.apply(action).accepted(), "same legal action can be retried after allocation failure");
        require_same(game, expected_after);
        auto expected_undo = reference;
        require(game.undo() == expected_undo.undo(), "retry retains whole-turn undo capability");
        require_same(game, observe(expected_undo));
    }
}

void illegal_actions_still_reject_without_mutation() {
    fq::Game opening;
    const auto before = observe(opening);
    allocation_probe::attempts = 0;
    allocation_probe::remaining = 0;
    const auto wrong_turn = opening.apply({fq::ActionKind::move, fq::black, 7, 14});
    allocation_probe::remaining = -1;
    require(wrong_turn.error == fq::ActionError::wrong_turn && allocation_probe::attempts == 0,
        "invalid move rejects before any allocation or provisional state");
    require_same(opening, before);

    fq::Board board{};
    board[0] = fq::red; board[48] = fq::black;
    auto seed = fq::Game::from_position(board, fq::red);
    require(seed.has_value(), "repetition fixture loads");
    auto cycle = std::move(*seed);
    require(cycle.move(fq::red, 0, 0, 1, 0).accepted(), "cycle first move");
    require(cycle.move(fq::black, 6, 6, 5, 6).accepted(), "cycle second move");
    require(cycle.move(fq::red, 1, 0, 0, 0).accepted(), "cycle third move");
    const auto before_repeat = observe(cycle);
    allocation_probe::attempts = 0;
    allocation_probe::remaining = 0;
    const auto repeated = cycle.apply({fq::ActionKind::move, fq::black, 47, 48});
    allocation_probe::remaining = -1;
    require(repeated.error == fq::ActionError::repeated_position && allocation_probe::attempts == 0,
        "first repetition remains rejected before allocation");
    require_same(cycle, before_repeat);
}
} // namespace

int main() {
    for (const auto stage : {Stage::opening, Stage::multi_square_move, Stage::first_capture,
                            Stage::last_capture, Stage::terminal_capture}) {
        every_actual_allocation_failure(stage);
    }
    illegal_actions_still_reject_without_mutation();
    std::cout << "Fangqi exception safety: " << injected_failures
              << " injected allocation failures, " << checks << " checks passed.\n";
}
