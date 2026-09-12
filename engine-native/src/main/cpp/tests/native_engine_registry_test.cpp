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
    assert(!registry.find_chinese_chess(0));
    assert(!registry.find_chinese_chess(-1));

    assert(registry.release(first));
    assert(!registry.find_chinese_chess(first));
    assert(!registry.release(first));
    assert(registry.find_chinese_chess(second));
    assert(registry.release(second));
}
