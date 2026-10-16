#include "mocs/engine/go_sgf.hpp"
#include "../games/go/go_handicap.hpp"

#include <charconv>
#include <cctype>
#include <cstddef>
#include <cstdint>
#include <string>
#include <string_view>
#include <stdexcept>
#include <utility>
#include <vector>

namespace mocs::engine {
namespace {
constexpr std::size_t max_sgf_bytes = 1'000'000;

struct Property {
    std::string name;
    std::vector<std::string> values;
};

using Node = std::vector<Property>;

class Parser {
public:
    explicit Parser(const std::string_view input) : input_(input) {}

    [[nodiscard]] bool take(const char expected) {
        skip_space();
        if (at_ == input_.size() || input_[at_] != expected) return false;
        ++at_;
        return true;
    }

    [[nodiscard]] bool parse_node(Node& node) {
        if (!take(';')) return false;
        while (true) {
            skip_space();
            if (at_ == input_.size()) return false;
            const auto first = input_[at_];
            if (first == ';' || first == ')' || first == '(') return true;
            if (first < 'A' || first > 'Z') return false;
            Property property;
            do {
                property.name.push_back(input_[at_++]);
            } while (at_ < input_.size() && input_[at_] >= 'A' && input_[at_] <= 'Z');
            skip_space();
            if (at_ == input_.size() || input_[at_] != '[') return false;
            do {
                ++at_;
                std::string value;
                bool closed = false;
                while (at_ < input_.size()) {
                    const char next = input_[at_++];
                    if (next == ']') {
                        closed = true;
                        break;
                    }
                    if (next == '\\') {
                        if (at_ == input_.size()) return false;
                        value.push_back(input_[at_++]);
                    } else {
                        value.push_back(next);
                    }
                }
                if (!closed) return false;
                property.values.push_back(std::move(value));
                skip_space();
            } while (at_ < input_.size() && input_[at_] == '[');
            node.push_back(std::move(property));
        }
    }

    [[nodiscard]] char peek() {
        skip_space();
        return at_ < input_.size() ? input_[at_] : '\0';
    }

    [[nodiscard]] bool done() {
        skip_space();
        return at_ == input_.size();
    }

private:
    void skip_space() {
        while (at_ < input_.size() &&
               (input_[at_] == ' ' || input_[at_] == '\t' ||
                input_[at_] == '\r' || input_[at_] == '\n')) {
            ++at_;
        }
    }

    std::string_view input_;
    std::size_t at_{};
};

[[nodiscard]] bool parse_size(const std::string& text, std::uint8_t& size) {
    unsigned value = 0;
    const auto parsed = std::from_chars(text.data(), text.data() + text.size(), value);
    if (parsed.ec != std::errc{} || parsed.ptr != text.data() + text.size() ||
        (value != 9 && value != 13 && value != 19)) {
        return false;
    }
    size = static_cast<std::uint8_t>(value);
    return true;
}

[[nodiscard]] bool parse_root(
    const Node& root, const std::uint8_t expected_size, std::int32_t& komi_quarters,
    std::uint8_t& handicap_stones
) {
    bool ff = false;
    bool gm = false;
    bool sz = false;
    bool km = false;
    bool ru = false;
    bool ha = false;
    const Property* setup_stones = nullptr;
    const Property* first_player = nullptr;
    for (const auto& property : root) {
        const auto& name = property.name;
        if (name == "B" || name == "W" || name == "AW" ||
            name == "AE" || name == "KO" || name == "RE") {
            return false;
        }
        if (name == "AB") {
            if (setup_stones) return false;
            setup_stones = &property;
            continue;
        }
        if (name == "PL") {
            if (first_player || property.values.size() != 1) return false;
            first_player = &property;
            continue;
        }
        if (name != "FF" && name != "GM" && name != "SZ" &&
            name != "KM" && name != "RU" && name != "HA") {
            continue;
        }
        if (property.values.size() != 1) return false;
        const auto& value = property.values.front();
        if (name == "FF") {
            if (ff || value != "4") return false;
            ff = true;
        } else if (name == "GM") {
            if (gm || value != "1") return false;
            gm = true;
        } else if (name == "SZ") {
            std::uint8_t parsed_size = 0;
            if (sz || !parse_size(value, parsed_size) || parsed_size != expected_size) {
                return false;
            }
            sz = true;
        } else if (name == "KM") {
            if (km || (value != "7.5" && value != "0")) return false;
            komi_quarters = value == "0" ? 0 : 15;
            km = true;
        } else if (name == "RU") {
            if (ru || value != "Chinese") return false;
            ru = true;
        } else if (name == "HA") {
            unsigned count = 0;
            const auto parsed = std::from_chars(value.data(), value.data() + value.size(), count);
            if (ha || parsed.ec != std::errc{} || parsed.ptr != value.data() + value.size() ||
                count < 2 || count > 9) return false;
            handicap_stones = static_cast<std::uint8_t>(count);
            ha = true;
        }
    }
    if (handicap_stones != 0) {
        if (!km) komi_quarters = 0;
        if (komi_quarters != 0 || !setup_stones ||
            setup_stones->values.size() != handicap_stones ||
            (first_player && first_player->values.front() != "W")) return false;
        const auto setup = make_go_handicap_setup(expected_size, handicap_stones);
        std::vector<bool> seen(static_cast<std::size_t>(expected_size) * expected_size, false);
        for (const auto& value : setup_stones->values) {
            if (value.size() != 2 || value[0] < 'a' || value[1] < 'a' ||
                value[0] >= 'a' + expected_size || value[1] >= 'a' + expected_size) return false;
            const auto point = static_cast<std::size_t>(value[1] - 'a') * expected_size +
                static_cast<std::size_t>(value[0] - 'a');
            if (seen[point]) return false;
            seen[point] = true;
        }
        for (const auto point : setup->black_stones) {
            if (!seen[static_cast<std::size_t>(point.y) * expected_size + point.x]) return false;
        }
    } else if (setup_stones || (first_player && first_player->values.front() != "B")) {
        return false;
    }
    return ff && gm && sz;
}

[[nodiscard]] bool apply_node(const Node& node, GoEngine& game) {
    const Property* move = nullptr;
    const Property* resume_player = nullptr;
    for (const auto& property : node) {
        const auto& name = property.name;
        if (name == "AB" || name == "AW" || name == "AE" ||
            name == "KO" || name == "FF" || name == "GM" || name == "SZ" ||
            name == "KM" || name == "RU" || name == "RE" || name == "HA") {
            return false;
        }
        if (name == "B" || name == "W") {
            if (move || property.values.size() != 1) return false;
            move = &property;
        } else if (name == "PL") {
            if (resume_player || property.values.size() != 1) return false;
            resume_player = &property;
        }
    }
    // A separate PL node records the first side after a dead-stone dispute.
    // Elsewhere PL cannot bypass turn order, and it never shares a move node.
    if (resume_player) {
        const auto& side = resume_player->values.front();
        return !move && game.phase() == GoPhase::scoring &&
            (side == "B" || side == "W") &&
            game.apply(make_go_resume_play(side == "B" ? 0 : 1)).accepted;
    }
    if (!move) return true;
    if ((move->name == "B" ? 0 : 1) != game.current_player()) return false;
    const auto& value = move->values.front();
    if (value.empty() || value == "tt") return game.apply(make_go_pass()).accepted;
    if (value.size() != 2 || value[0] < 'a' || value[1] < 'a' ||
        value[0] >= 'a' + game.board_size() ||
        value[1] >= 'a' + game.board_size()) {
        return false;
    }
    return game.apply(make_go_place(value[0] - 'a', value[1] - 'a')).accepted;
}

[[nodiscard]] RestoreResult bad_sgf() noexcept {
    return {false, EngineError::corrupted_data};
}
}  // namespace

std::string export_go_sgf(const GoEngine& game) {
    std::string sgf = "(;FF[4]GM[1]SZ[" + std::to_string(game.board_size()) +
        "]RU[Chinese]KM[" + (game.black_komi_quarters() == 0 ? "0" : "7.5") +
        "]CA[UTF-8]";
    if (game.handicap_stones() != 0) {
        sgf += "HA[" + std::to_string(game.handicap_stones()) + "]AB";
        for (const auto point : game.handicap_points()) {
            sgf += '[';
            sgf += static_cast<char>('a' + point.x);
            sgf += static_cast<char>('a' + point.y);
            sgf += ']';
        }
        sgf += "PL[W]";
    }
    GoEngine replay(game.board_size(), game.black_komi_quarters(), game.handicap_stones());
    for (const auto& action : game.event_history()) {
        if (action.kind == go_place_action_kind || action.kind == go_pass_action_kind) {
            sgf += replay.current_player() == 0 ? ";B[" : ";W[";
            if (action.kind == go_place_action_kind) {
                sgf += static_cast<char>('a' + action.arguments[0]);
                sgf += static_cast<char>('a' + action.arguments[1]);
            }
            sgf += ']';
        } else if (action.kind == go_resume_play_action_kind) {
            sgf += action.arguments[0] == 0 ? ";PL[B]" : ";PL[W]";
        } else {
            // SGF carries moves and resume turns, but not scoring proposals.
            // Replaying an omitted proposal can also reuse a revision after undo.
            continue;
        }
        if (!replay.apply(action).accepted) throw std::logic_error("Invalid Go event history");
    }
    sgf += ')';
    return sgf;
}

RestoreResult restore_go_sgf(GoEngine& game, const std::string_view sgf) {
    if (sgf.empty() || sgf.size() > max_sgf_bytes) return bad_sgf();
    Parser parser(sgf);
    if (!parser.take('(')) return bad_sgf();
    Node root;
    std::int32_t komi_quarters = 15;
    std::uint8_t handicap_stones = 0;
    if (!parser.parse_node(root) ||
        !parse_root(root, game.board_size(), komi_quarters, handicap_stones)) {
        return bad_sgf();
    }
    GoEngine candidate(game.board_size(), komi_quarters, handicap_stones);
    while (parser.peek() == ';') {
        Node node;
        if (!parser.parse_node(node) || !apply_node(node, candidate)) return bad_sgf();
    }
    if (!parser.take(')') || !parser.done()) return bad_sgf();
    return game.restore(candidate.serialize());
}

}  // namespace mocs::engine
