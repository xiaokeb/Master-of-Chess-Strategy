#include "mocs/engine/health_check.hpp"

#include <cassert>

int main() {
    assert(
        mocs::engine::health_check() ==
        "MasterofChessStrategy Engine/1"
    );
}
