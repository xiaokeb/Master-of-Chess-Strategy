// Offline admission probe only. This executable has deliberately no search command.
#include "mocs/engine/chinese_chess.hpp"
#include "mocs/engine/pikafish_difficulty.hpp"

#include <array>
#include <exception>
#include <iostream>
#include <string_view>

#if !defined(MOCS_BUDGET_V2_PROTOCOL) || !defined(MOCS_BUDGET_V2_RULE_IDENTITY) || \
    !defined(MOCS_BUDGET_V2_SEARCH_IDENTITY) || !defined(MOCS_BUDGET_V2_SPEC_SHA256) || \
    !defined(MOCS_BUDGET_V2_EXPOSURE_SHA256)
#error "budget_v2 entry requires all externally reviewed identity bindings"
#endif

namespace {
constexpr std::string_view protocol = "calibration-budget-v2-20261022-admission";

bool digest(const std::string_view value) noexcept {
    if (value.size() != 64) return false;
    for (const auto character : value) {
        if (!((character >= '0' && character <= '9') || (character >= 'a' && character <= 'f')))
            return false;
    }
    return true;
}

int reject() {
    // Never print a success-shaped result on an unsuccessful check.
    std::cerr << "budget_v2 validate-plan rejected\n";
    return 2;
}
} // namespace

int main(const int argc, const char* const argv[]) {
    if (argc != 7 || std::string_view(argv[1]) != "validate-plan" ||
        std::string_view(MOCS_BUDGET_V2_PROTOCOL) != protocol ||
        std::string_view(argv[2]) != protocol) return reject();

    constexpr std::array<std::string_view, 4> bindings{
        MOCS_BUDGET_V2_RULE_IDENTITY, MOCS_BUDGET_V2_SEARCH_IDENTITY,
        MOCS_BUDGET_V2_SPEC_SHA256, MOCS_BUDGET_V2_EXPOSURE_SHA256};
    for (std::size_t index = 0; index < bindings.size(); ++index) {
        if (!digest(bindings[index]) || !digest(argv[index + 3]) ||
            std::string_view(argv[index + 3]) != bindings[index]) return reject();
    }

    try {
        // Construction/reset and serialization do not initialize Pikafish or load NNUE.
        const mocs::engine::ChineseChessEngine game;
        const auto saved = game.serialize();
        if (saved.size() < 17 || saved[0] != 'M' || saved[1] != 'O' ||
            saved[2] != 'C' || saved[3] != 'X' || saved[4] != 4 ||
            saved[saved.size() - 5] != 1) return reject();
        for (const auto difficulty : {mocs::engine::Difficulty::easy,
                                     mocs::engine::Difficulty::medium,
                                     mocs::engine::Difficulty::hard,
                                     mocs::engine::Difficulty::master}) {
            mocs::engine::validate_pikafish_profile(mocs::engine::pikafish_profile(difficulty));
        }
    } catch (const std::exception&) {
        return reject();
    } catch (...) {
        return reject();
    }

    // All interpolated strings have been restricted to a fixed protocol or lowercase hex.
    std::cout << "{\"type\":\"budget_v2_native_plan\",\"protocol\":\"" << protocol
              << "\",\"rule_profile\":{\"format_version\":4,\"revision\":1},"
              << "\"rule_identity\":\"" << bindings[0]
              << "\",\"search_source_identity\":\"" << bindings[1]
              << "\",\"spec_sha256\":\"" << bindings[2]
              << "\",\"exposure_sha256\":\"" << bindings[3]
              << "\",\"search_authorized\":false,\"search_performed\":false,"
              << "\"strength_accepted\":false}\n";
    return std::cout ? 0 : 2;
}
