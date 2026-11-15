#include "mocs/engine/go.hpp"

#include <algorithm>
#include <charconv>
#include <cstdint>
#include <filesystem>
#include <fstream>
#include <iomanip>
#include <iostream>
#include <stdexcept>
#include <string_view>
#include <system_error>
#include <vector>

namespace {
using namespace mocs::engine;

int number(std::string_view text) {
    int result{};
    const auto [end, error] = std::from_chars(text.data(), text.data() + text.size(), result);
    if (error != std::errc{} || end != text.data() + text.size()) {
        throw std::invalid_argument("invalid coordinate or board size");
    }
    return result;
}

GoPoint point(std::string_view text) {
    const auto comma = text.find(',');
    if (comma == std::string_view::npos) throw std::invalid_argument("expected x,y");
    return {number(text.substr(0, comma)), number(text.substr(comma + 1))};
}

std::vector<std::uint8_t> read_record(const std::filesystem::path& path) {
    const auto size = std::filesystem::file_size(path);
    if (size == 0 || size > 1024 * 1024) throw std::invalid_argument("invalid MOCG size");
    std::vector<std::uint8_t> result(size);
    std::ifstream input(path, std::ios::binary);
    if (!input.read(reinterpret_cast<char*>(result.data()), static_cast<std::streamsize>(size))) {
        throw std::runtime_error("cannot read MOCG");
    }
    return result;
}

void print_board(const GoEngine& engine) {
    const auto size = engine.board_size();
    std::cout << "   ";
    for (int x = 0; x < size; ++x) std::cout << std::setw(3) << x;
    std::cout << '\n';
    for (int y = 0; y < size; ++y) {
        std::cout << std::setw(3) << y;
        for (int x = 0; x < size; ++x) {
            const auto stone = engine.stone_at(x, y).value();
            const char symbol = stone == GoStone::black ? 'B' : stone == GoStone::white ? 'W' : '.';
            std::cout << std::setw(3) << symbol;
        }
        std::cout << '\n';
    }
}

void print_score(std::string_view label, const GoScore& score, std::size_t dead) {
    std::cout << label << ',' << static_cast<int>(score.result) << ',' << score.black_area_quarters << ','
              << score.white_area_quarters << ',' << score.black_komi_quarters << ',' << dead << '\n';
}
} // namespace

int main(int argc, char** argv) {
    try {
        if (argc < 3) {
            throw std::invalid_argument("use: go-strength-score-review size input.mocg [output.mocg [x,y ...]]");
        }
        const auto size = number(argv[1]);
        if (size != 9 && size != 13 && size != 19) throw std::invalid_argument("unsupported board size");
        GoEngine engine(static_cast<std::uint8_t>(size));
        const auto original = read_record(argv[2]);
        if (!engine.restore(original).restored || engine.serialize() != original || engine.board_size() != size ||
            engine.black_komi_quarters() != 15 || engine.handicap_stones() != 0) {
            throw std::invalid_argument("incompatible match record");
        }
        const auto before = engine.final_score();
        const auto events = engine.event_history();
        const auto count = events.size();
        if (!before || count < 4 || !engine.scoring_state().dead_stones.empty() ||
            events[count - 4].kind != go_pass_action_kind || events[count - 3].kind != go_pass_action_kind ||
            events[count - 2].kind != go_confirm_score_action_kind || events.back().kind != go_confirm_score_action_kind) {
            throw std::invalid_argument("expected the raw double-pass agreement");
        }
        print_score("raw", *before, 0);
        if (argc == 3) {
            print_board(engine);
            return 0;
        }
        if (std::filesystem::exists(argv[3])) throw std::invalid_argument("review output already exists");
        if (!engine.undo() || !engine.undo() || engine.phase() != GoPhase::scoring) {
            throw std::runtime_error("cannot reopen the raw agreement");
        }
        for (int i = 4; i < argc; ++i) {
            const auto p = point(argv[i]);
            const auto prior = engine.scoring_state().dead_stones;
            if (std::any_of(prior.begin(), prior.end(), [p](GoPoint q) { return p.x == q.x && p.y == q.y; }) ||
                !engine.apply(make_go_toggle_dead(p.x, p.y, 0)).accepted) {
                throw std::invalid_argument("invalid or repeated dead group");
            }
        }
        const auto proposal = engine.scoring_state();
        if (!engine.apply(make_go_confirm_score(0, proposal.proposal_revision)).accepted ||
            !engine.apply(make_go_confirm_score(1, proposal.proposal_revision)).accepted) {
            throw std::runtime_error("cannot confirm the reviewed proposal");
        }
        const auto reviewed = engine.serialize();
        GoEngine verifier(static_cast<std::uint8_t>(size));
        if (!verifier.restore(reviewed).restored || verifier.serialize() != reviewed || !verifier.final_score()) {
            throw std::runtime_error("cannot replay the reviewed agreement");
        }
        std::ofstream output(argv[3], std::ios::binary);
        if (!output.write(reinterpret_cast<const char*>(reviewed.data()), static_cast<std::streamsize>(reviewed.size()))) {
            throw std::runtime_error("cannot write the reviewed MOCG");
        }
        output.close();
        if (!output) throw std::runtime_error("cannot close the reviewed MOCG");
        print_score("reviewed", *verifier.final_score(), verifier.scoring_state().dead_stones.size());
        return 0;
    } catch (const std::exception& failure) {
        std::cerr << failure.what() << '\n';
        return 1;
    }
}
