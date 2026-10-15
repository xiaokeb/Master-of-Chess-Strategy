#include "mocs/engine/tiger_goat_game.hpp"

#include <cassert>
#include <cstdio>
#include <cstdlib>
#include <initializer_list>
#include <new>

namespace allocation_probe {
int remaining = -1;
int attempts = 0;
}

// Only this standalone executable replaces allocation; production uses the normal allocator.
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
namespace tg = mocs::engine::tiger_goat;

tg::MatchState seed(std::initializer_list<int> tigers, std::initializer_list<int> goats,
                    const std::uint8_t side) {
    tg::MatchState state{};
    state.board.fill(tg::empty);
    for (const auto point : tigers) state.board[static_cast<std::size_t>(point)] = tg::tiger;
    for (const auto point : goats) state.board[static_cast<std::size_t>(point)] = tg::goat;
    state.phase = tg::Phase::play;
    state.to_move = side;
    state.reserve = 0;
    state.captured = static_cast<std::uint8_t>(22 - goats.size());
    return state;
}

void prepare(tg::TigerGoatGame& game, const tg::ActionKind kind, const bool goat_step) {
    if (kind == tg::ActionKind::place_goat) {
        assert(game.apply({tg::ActionKind::remove_goat, 6, -1}) == tg::ApplyError::none);
    } else if (kind == tg::ActionKind::capture || kind == tg::ActionKind::end_chain) {
        assert(game.reset_to_position(seed({12, 0}, {13, 19, 23, 17, 21, 1, 2, 3, 4, 5, 6, 9}, tg::tiger)));
        if (kind == tg::ActionKind::end_chain) {
            assert(game.apply({tg::ActionKind::capture, 12, 14}) == tg::ApplyError::none);
            assert(game.state().phase == tg::Phase::capture_chain);
        }
    } else if (kind == tg::ActionKind::pass_goat) {
        assert(game.reset_to_position(seed({10, 14}, {25, 26, 27, 28, 29, 30, 31, 32, 33, 34, 35, 36}, tg::goat)));
    } else if (kind == tg::ActionKind::step) {
        assert(game.reset_to_position(seed({0, 4}, {6, 7, 8, 11, 13, 16, 17, 18},
                                          goat_step ? tg::goat : tg::tiger)));
    }
}

void verify_failure(const tg::Action action, const int failing_allocation, const bool goat_step = false) {
    tg::TigerGoatGame game;
    prepare(game, action.kind, goat_step);
    const auto before = game.serialize();
    const auto count = game.action_count();
    allocation_probe::attempts = 0;
    allocation_probe::remaining = failing_allocation;
    bool failed = false;
    try {
        static_cast<void>(game.apply(action));
    } catch (const std::bad_alloc&) {
        failed = true;
    }
    allocation_probe::remaining = -1;
    assert(failed && allocation_probe::attempts == failing_allocation + 1);
    assert(game.action_count() == count && game.serialize() == before);
    // A failed append must not introduce an extra undo frame, even on an empty history.
    tg::TigerGoatGame restored;
    assert(restored.restore(before));
    assert(game.undo() == restored.undo());
    assert(game.serialize() == restored.serialize());
    assert(game.restore(before));
    assert(game.apply(action) == tg::ApplyError::none);
    assert(game.action_count() == count + 1);
    assert(game.undo() && game.serialize() == before);
}
}

int main() {
    const tg::Action actions[] = {
        {tg::ActionKind::remove_goat, 6, -1},
        {tg::ActionKind::place_goat, -1, 6},
        {tg::ActionKind::step, 0, 1},
        {tg::ActionKind::capture, 12, 14},
        {tg::ActionKind::end_chain, -1, -1},
        {tg::ActionKind::resign, -1, -1},
        {tg::ActionKind::pass_goat, -1, -1},
    };
    for (const auto action : actions) {
        for (const int failing_allocation : {0, 1}) verify_failure(action, failing_allocation);
    }
    for (const int failing_allocation : {0, 1}) {
        verify_failure({tg::ActionKind::step, 6, 12}, failing_allocation, true);
    }
    std::puts("tiger-goat exception safety: 16 injected allocation failures passed");
}
