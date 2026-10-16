#include "go_handicap.hpp"

#include <array>
#include <cassert>
#include <cstdint>
#include <set>
#include <utility>

namespace {

void all_supported_sizes_and_counts() {
    for (const std::uint8_t size : {9, 13, 19}) {
        const int near = size == 9 ? 2 : 3;
        const int far = size - 1 - near;
        const int middle = size / 2;
        const std::array<std::pair<int, int>, 9> expected{{
            {near, far}, {far, near}, {near, near}, {far, far},
            {near, middle}, {far, middle}, {middle, far}, {middle, near},
            {middle, middle},
        }};
        for (std::uint8_t count = 2; count <= 9; ++count) {
            const auto setup = mocs::engine::make_go_handicap_setup(size, count);
            assert(setup.has_value());
            assert(setup->black_stones.size() == count);
            assert(setup->first_player == 1);
            assert(setup->black_komi_quarters == 0);

            std::set<std::pair<int, int>> unique;
            for (std::size_t i = 0; i < setup->black_stones.size(); ++i) {
                const auto& point = setup->black_stones[i];
                assert(point.x >= 0 && point.x < size);
                assert(point.y >= 0 && point.y < size);
                assert(unique.emplace(point.x, point.y).second);
                const auto expected_index = count >= 5 && count % 2 != 0 &&
                    i == setup->black_stones.size() - 1 ? 8 : i;
                assert(point.x == expected[expected_index].first);
                assert(point.y == expected[expected_index].second);
            }
            if (count >= 5 && count % 2 != 0) {
                assert(setup->black_stones.back().x == middle);
                assert(setup->black_stones.back().y == middle);
            } else {
                assert(unique.count({middle, middle}) == 0);
            }
            if (count >= 6) {
                assert(unique.count({near, middle}) == 1);
                assert(unique.count({far, middle}) == 1);
            }
            if (count >= 8) {
                assert(unique.count({middle, near}) == 1);
                assert(unique.count({middle, far}) == 1);
            }
        }
    }
}

void reject_unsupported_setups() {
    for (const std::uint8_t size : {0, 8, 10, 11, 12, 14, 18, 20, 255}) {
        assert(!mocs::engine::make_go_handicap_setup(size, 2));
    }
    for (const std::uint8_t count : {0, 1, 10, 255}) {
        assert(!mocs::engine::make_go_handicap_setup(19, count));
    }
}

}  // namespace

int main() {
    all_supported_sizes_and_counts();
    reject_unsupported_setups();
}
