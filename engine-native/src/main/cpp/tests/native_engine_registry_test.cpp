#include "mocs/engine/native_engine_registry.hpp"

#include <cassert>

int main() {
    auto& registry = mocs::engine::NativeEngineRegistry::instance();

    const auto first = registry.create_chinese_chess();
    const auto second = registry.create_chinese_chess();
    assert(first > 0);
    assert(second > first);
    assert(registry.find_chinese_chess(first));
    assert(registry.find_chinese_chess(second));
    // Cross-type release must preserve the other live session.
    const auto tiger_goat = registry.create_tiger_goat();
    assert(tiger_goat > second);
    assert(registry.find_tiger_goat(tiger_goat));
    assert(!registry.find_tiger_goat(first));
    assert(!registry.find_tiger_goat(0));
    assert(!registry.find_tiger_goat(-1));
    assert(!registry.find_chinese_chess(tiger_goat));
    assert(!registry.release_chinese_chess(tiger_goat));
    assert(!registry.release_tiger_goat(first));
    assert(!registry.release_chinese_chess(0));
    assert(!registry.release_chinese_chess(-1));
    assert(!registry.release_tiger_goat(0));
    assert(!registry.release_tiger_goat(-1));
    assert(registry.find_chinese_chess(first));
    assert(registry.find_chinese_chess(second));
    assert(registry.find_tiger_goat(tiger_goat));

    // Releasing a handle cannot invalidate a lookup's retained shared owner.
    const auto retained_tiger_goat = registry.find_tiger_goat(tiger_goat);
    assert(registry.release_tiger_goat(tiger_goat));
    assert(!registry.release_tiger_goat(tiger_goat));
    assert(!registry.find_tiger_goat(tiger_goat));
    assert(!registry.release(tiger_goat));
    {
        std::lock_guard lock(retained_tiger_goat->mutex);
        assert(retained_tiger_goat->game.state().reserve == 14);
        assert(retained_tiger_goat->game.apply({
            mocs::engine::tiger_goat::ActionKind::remove_goat, 6, -1
        }) == mocs::engine::tiger_goat::ApplyError::none);
        assert(retained_tiger_goat->game.state().reserve == 14);
    }

    // All registered games share one monotonic handle space, even after release.
    const auto generic_tiger_goat = registry.create_tiger_goat();
    assert(generic_tiger_goat > tiger_goat);
    assert(registry.release(generic_tiger_goat));
    assert(!registry.find_tiger_goat(generic_tiger_goat));
    assert(!registry.release(generic_tiger_goat));
    assert(registry.find_chinese_chess(first));
    assert(registry.find_chinese_chess(second));

    const auto typed_chinese = registry.create_chinese_chess();
    assert(typed_chinese > generic_tiger_goat);
    const auto retained_chinese = registry.find_chinese_chess(typed_chinese);
    assert(!registry.release_tiger_goat(typed_chinese));
    assert(registry.release_chinese_chess(typed_chinese));
    assert(!registry.release_chinese_chess(typed_chinese));
    assert(!registry.find_chinese_chess(typed_chinese));
    assert(retained_chinese && retained_chinese->current_player() == 0);

    assert(!registry.find_chinese_chess(0));
    assert(!registry.find_chinese_chess(-1));

    assert(registry.release(first));
    assert(!registry.find_chinese_chess(first));
    assert(!registry.release(first));
    assert(registry.find_chinese_chess(second));
    assert(registry.release(second));
}
