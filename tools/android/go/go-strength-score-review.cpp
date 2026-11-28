#include "mocs/engine/go.hpp"

#include "game/board.h"

#include <algorithm>
#include <charconv>
#include <cstdint>
#include <cstdlib>
#include <filesystem>
#include <fstream>
#include <iomanip>
#include <iostream>
#include <stdexcept>
#include <string>
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

Board analysis_board(const GoEngine& engine, std::vector<Move>& setup) {
    const auto size = engine.board_size();
    Board::initHash();
    Board board(size, size);
    for (int y = 0; y < size; ++y) {
        for (int x = 0; x < size; ++x) {
            const auto stone = engine.stone_at(x, y).value();
            if (stone == GoStone::empty) continue;
            setup.emplace_back(Location::getLoc(x, y, size),
                stone == GoStone::black ? C_BLACK : C_WHITE);
        }
    }
    if (!board.setStonesFailIfNoLibs(setup)) throw std::logic_error("invalid terminal board");
    return board;
}

void print_group_review(const GoEngine& engine) {
    const auto size = engine.board_size();
    std::vector<Move> setup;
    const auto board = analysis_board(engine, setup);
    Color area[Board::MAX_ARR_SIZE];
    // Freeze pass-alive evidence and leave other groups for review.
    board.calculateArea(area, false, false, false, false);

    bool seen[Board::MAX_ARR_SIZE]{};
    std::cout << "x,y,color,stones,liberties,status\n";
    for (const auto& stone : setup) {
        const auto head = board.chain_head[stone.loc];
        if (seen[head]) continue;
        seen[head] = true;
        int count = 0;
        bool own = true;
        bool opposing = true;
        auto loc = head;
        do {
            ++count;
            own &= area[loc] == stone.pla;
            opposing &= area[loc] == getOpp(stone.pla);
            loc = board.next_in_chain[loc];
        } while (loc != head);
        const auto status = own ? "pass_alive" : opposing ? "inside_opponent_pass_alive" : "unresolved";
        std::cout << Location::getX(stone.loc, size) << ',' << Location::getY(stone.loc, size) << ','
                  << (stone.pla == C_BLACK ? 'B' : 'W') << ',' << count << ','
                  << board.getNumLiberties(stone.loc) << ',' << status << '\n';
    }
}

bool anchors_present(const GoEngine& engine, const std::vector<GoPoint>& anchors, GoStone defender) {
    return std::all_of(anchors.begin(), anchors.end(), [&](GoPoint anchor) {
        return engine.stone_at(anchor.x, anchor.y) == defender;
    });
}

bool pass_alive(const GoEngine& engine, const std::vector<GoPoint>& anchors, GoStone defender) {
    if (!anchors_present(engine, anchors, defender)) return false;
    std::vector<Move> setup;
    const auto board = analysis_board(engine, setup);
    Color area[Board::MAX_ARR_SIZE];
    board.calculateArea(area, false, false, false, false);
    const auto color = defender == GoStone::black ? C_BLACK : C_WHITE;
    for (const auto anchor : anchors) {
        const auto start = Location::getLoc(anchor.x, anchor.y, engine.board_size());
        auto loc = start;
        do {
            if (area[loc] != color) return false;
            loc = board.next_in_chain[loc];
        } while (loc != start);
    }
    return true;
}

std::string move_label(const EngineAction& action) {
    if (action.kind == go_pass_action_kind) return "pass";
    return std::to_string(action.arguments[0]) + ":" + std::to_string(action.arguments[1]);
}

void probe_first_replies(GoEngine& engine, const std::vector<GoPoint>& anchors) {
    const auto defender = engine.stone_at(anchors.front().x, anchors.front().y);
    if (!defender || *defender == GoStone::empty || !anchors_present(engine, anchors, *defender)) {
        throw std::invalid_argument("expected same-color group anchors");
    }
    const auto claimant = *defender == GoStone::black ? 1 : 0;
    if (!engine.apply(make_go_resume_play(claimant)).accepted) {
        throw std::runtime_error("cannot resume play from the original agreement");
    }
    const auto attacks = engine.legal_actions();
    std::size_t covered = 0;
    std::cout << "attack,reply\n";
    for (const auto& attack : attacks) {
        // A double pass advances the proposal revision, including after undo.
        auto branch = engine;
        if (!branch.apply(attack).accepted) throw std::logic_error("listed first move was rejected");
        std::string witness = "-";
        // Once the attacker captures an original group, a new stone on its anchor is unrelated.
        if (anchors_present(branch, anchors, *defender)) {
            for (const auto& reply : branch.legal_actions()) {
                if (!branch.apply(reply).accepted) throw std::logic_error("listed reply was rejected");
                if (pass_alive(branch, anchors, *defender)) {
                    const auto record = branch.serialize();
                    GoEngine replay(branch.board_size());
                    if (!replay.restore(record).restored || replay.serialize() != record ||
                        !pass_alive(replay, anchors, *defender)) {
                        throw std::runtime_error("cannot replay the pass-alive reply");
                    }
                    witness = move_label(reply);
                }
                if (!branch.undo()) throw std::logic_error("cannot undo the reply");
                if (witness != "-") break;
            }
        }
        if (witness != "-") ++covered;
        std::cout << move_label(attack) << ',' << witness << '\n';
    }
    const bool proven = !attacks.empty() && covered == attacks.size();
    std::cout << "coverage," << covered << ',' << attacks.size() << ',' << (proven ? "alive" : "unknown") << '\n';
}

void probe_gap(GoEngine& engine, const std::vector<GoPoint>& anchors, std::string_view opening_attack) {
    const auto defender = engine.stone_at(anchors.front().x, anchors.front().y);
    if (!defender || *defender == GoStone::empty || !anchors_present(engine, anchors, *defender)) {
        throw std::invalid_argument("expected same-color group anchors");
    }
    if (!engine.apply(make_go_resume_play(*defender == GoStone::black ? 1 : 0)).accepted) {
        throw std::runtime_error("cannot resume play from the original agreement");
    }
    auto attack = make_go_pass();
    if (opening_attack != "pass") {
        const auto move = point(opening_attack);
        attack = make_go_place(move.x, move.y);
    }
    if (!engine.apply(attack).accepted) throw std::invalid_argument("specified attack is illegal");

    struct Reply {
        EngineAction attack;
        EngineAction defense;
    };
    constexpr std::size_t leaf_limit = 30'000;
    std::size_t leaves = 0;
    const auto validate_leaf = [&](const GoEngine& candidate) {
        if (leaves >= leaf_limit) return false;
        ++leaves;
        if (!pass_alive(candidate, anchors, *defender)) return false;
        const auto record = candidate.serialize();
        GoEngine replay(candidate.board_size());
        if (!replay.restore(record).restored || replay.serialize() != record ||
            !pass_alive(replay, anchors, *defender)) {
            throw std::runtime_error("cannot replay the pass-alive leaf");
        }
        return true;
    };

    if (anchors_present(engine, anchors, *defender)) {
        for (const auto& first : engine.legal_actions()) {
            auto after_first = engine;
            if (!after_first.apply(first).accepted) throw std::logic_error("listed defense was rejected");
            if (validate_leaf(after_first)) {
                std::cout << "proven," << move_label(attack) << ',' << move_label(first) << ',' << leaves << '\n';
                return;
            }
            // A second pair of virtual moves must not invent play beyond the resumed branch.
            if (leaves >= leaf_limit) break;
            if (after_first.phase() != GoPhase::playing) continue;
            std::vector<Reply> witnesses;
            const auto counterattacks = after_first.legal_actions();
            for (const auto& counter : counterattacks) {
                auto after_counter = after_first;
                if (!after_counter.apply(counter).accepted) throw std::logic_error("listed counterattack was rejected");
                if (after_counter.phase() != GoPhase::playing ||
                    !anchors_present(after_counter, anchors, *defender)) break;
                bool found = false;
                for (const auto& second : after_counter.legal_actions()) {
                    if (!after_counter.apply(second).accepted) throw std::logic_error("listed last defense was rejected");
                    const bool proven = validate_leaf(after_counter);
                    if (!after_counter.undo()) throw std::logic_error("cannot undo the last defense");
                    if (proven) {
                        witnesses.push_back({counter, second});
                        found = true;
                        break;
                    }
                    if (leaves >= leaf_limit) break;
                }
                if (!found || leaves >= leaf_limit) break;
            }
            if (!counterattacks.empty() && witnesses.size() == counterattacks.size()) {
                std::cout << "proven," << move_label(attack) << ',' << move_label(first) << ',' << leaves << '\n';
                std::cout << "counterattack,response\n";
                for (const auto& reply : witnesses) {
                    std::cout << move_label(reply.attack) << ',' << move_label(reply.defense) << '\n';
                }
                return;
            }
            if (leaves >= leaf_limit) break;
        }
    }
    std::cout << "unknown," << move_label(attack) << ",-," << leaves << '\n';
}

class JointLifeSearch {
public:
    JointLifeSearch(const std::vector<GoPoint>& anchors, GoStone defender, bool focus_last_move)
        : anchors_(anchors), defender_(defender), focus_last_move_(focus_last_move) {}

    bool defend(const GoEngine& position, int remaining, std::vector<EngineAction>& path) {
        if (position.phase() != GoPhase::playing || !anchors_present(position, anchors_, defender_))
            return false;
        for (const auto& reply : ordered_actions(position, path.back())) {
            if (visited == limit) return false;
            ++visited;
            auto branch = position;
            if (!branch.apply(reply).accepted) throw std::logic_error("listed deep reply was rejected");
            path.push_back(reply);
            if (pass_alive(branch, anchors_, defender_)) {
                const auto bytes = branch.serialize();
                GoEngine replay(branch.board_size());
                if (!replay.restore(bytes).restored || replay.serialize() != bytes ||
                    !pass_alive(replay, anchors_, defender_))
                    throw std::logic_error("deep life leaf cannot be replayed");
                witnesses.push_back(path);
                path.pop_back();
                return true;
            }
            if (remaining > 1 && branch.phase() == GoPhase::playing) {
                const auto start = witnesses.size();
                const auto attacks = ordered_actions(branch, reply);
                const bool covered = !attacks.empty() && std::all_of(attacks.begin(), attacks.end(), [&](const auto& attack) {
                    auto counter = branch;
                    if (!counter.apply(attack).accepted) throw std::logic_error("listed deep attack was rejected");
                    path.push_back(attack);
                    const bool won = defend(counter, remaining - 1, path);
                    path.pop_back();
                    return won;
                });
                if (covered) {
                    path.pop_back();
                    return true;
                }
                witnesses.resize(start);
            }
            path.pop_back();
        }
        return false;
    }

    static constexpr std::size_t limit = 60'000;
    std::size_t visited = 0;
    std::vector<std::vector<EngineAction>> witnesses;

private:
    auto ordered_actions(const GoEngine& position, const EngineAction& last_move) const -> std::vector<EngineAction> {
        auto actions = position.legal_actions();
        // Only reorder. A proof still has to cover every legal opponent reply, including pass.
        const auto distance = [&](const EngineAction& action) {
            if (action.kind == go_pass_action_kind) return 100;
            auto closest = 100;
            for (const auto anchor : anchors_)
                closest = std::min(closest, std::abs(action.arguments[0] - anchor.x) +
                    std::abs(action.arguments[1] - anchor.y));
            if (focus_last_move_ && last_move.kind == go_place_action_kind) {
                const auto local = std::abs(action.arguments[0] - last_move.arguments[0]) +
                    std::abs(action.arguments[1] - last_move.arguments[1]);
                return std::min(local, closest + 2);
            }
            return closest;
        };
        std::stable_sort(actions.begin(), actions.end(), [&](const auto& left, const auto& right) {
            return distance(left) < distance(right);
        });
        return actions;
    }

    const std::vector<GoPoint>& anchors_;
    GoStone defender_;
    bool focus_last_move_;
};

void probe_deep_gap(GoEngine& engine, const std::vector<GoPoint>& anchors, std::string_view opening_attack,
                    bool focus_last_move) {
    const auto defender = engine.stone_at(anchors.front().x, anchors.front().y);
    if (!defender || *defender == GoStone::empty || !anchors_present(engine, anchors, *defender))
        throw std::invalid_argument("expected same-color group anchors");
    if (!engine.apply(make_go_resume_play(*defender == GoStone::black ? 1 : 0)).accepted)
        throw std::runtime_error("cannot resume play from the original agreement");
    auto attack = make_go_pass();
    if (opening_attack != "pass") {
        const auto move = point(opening_attack);
        attack = make_go_place(move.x, move.y);
    }
    if (!engine.apply(attack).accepted) throw std::invalid_argument("specified attack is illegal");
    JointLifeSearch search(anchors, *defender, focus_last_move);
    std::vector<EngineAction> path{attack};
    const bool covered = search.defend(engine, 3, path);
    std::cout << "deep," << (covered ? "proven" : search.visited == search.limit ? "budget" : "unknown") << ','
              << move_label(attack) << ',' << search.visited << ',' << search.witnesses.size() << '\n';
    for (const auto& witness : search.witnesses) {
        std::cout << "leaf";
        for (const auto& action : witness) std::cout << ',' << move_label(action);
        std::cout << '\n';
    }
}

void probe_atari(GoEngine& engine, const std::vector<GoPoint>& anchors) {
    const auto defender = engine.stone_at(anchors.front().x, anchors.front().y);
    if (!defender || *defender == GoStone::empty) throw std::invalid_argument("expected a group anchor");
    const auto attacker = *defender == GoStone::black ? GoStone::white : GoStone::black;
    const auto color = attacker == GoStone::black ? C_BLACK : C_WHITE;
    const auto size = engine.board_size();
    std::vector<Move> setup;
    const auto original = analysis_board(engine, setup);
    Color area[Board::MAX_ARR_SIZE];
    original.calculateArea(area, false, false, false, false);
    std::vector<GoPoint> group;
    std::vector<Loc> heads;
    auto liberty = Board::NULL_LOC;
    for (const auto anchor : anchors) {
        if (engine.stone_at(anchor.x, anchor.y) != defender) {
            throw std::invalid_argument("expected group anchors of the same color");
        }
        const auto loc = Location::getLoc(anchor.x, anchor.y, size);
        const auto head = original.chain_head[loc];
        if (std::find(heads.begin(), heads.end(), head) != heads.end()) {
            throw std::invalid_argument("repeated group anchor");
        }
        heads.push_back(head);
        if (original.getNumLiberties(loc) != 1) {
            std::cout << "result,unknown,liberty-count\n";
            return;
        }
        auto stone = loc;
        do {
            if (area[stone] != color) {
                std::cout << "result,unknown,original-territory\n";
                return;
            }
            group.push_back({Location::getX(stone, size), Location::getY(stone, size)});
            for (int offset = 0; offset < 4; ++offset) {
                const auto neighbor = stone + original.adj_offsets[offset];
                if (original.colors[neighbor] != C_EMPTY) continue;
                if (liberty != Board::NULL_LOC && liberty != neighbor) {
                    std::cout << "result,unknown,separate-liberties\n";
                    return;
                }
                liberty = neighbor;
            }
            stone = original.next_in_chain[stone];
        } while (stone != loc);
    }
    if (liberty == Board::NULL_LOC) throw std::logic_error("missing original liberty");
    const GoPoint capture{Location::getX(liberty, size), Location::getY(liberty, size)};
    const auto capture_action = make_go_place(capture.x, capture.y);
    if (!engine.apply(make_go_resume_play(attacker == GoStone::black ? 0 : 1)).accepted ||
        !engine.apply(capture_action).accepted ||
        !anchors_present(engine, group, GoStone::empty)) {
        std::cout << "result,unknown,capture\n";
        return;
    }
    const auto held = [&](const GoEngine& candidate) {
        if (candidate.stone_at(capture.x, capture.y) != attacker) return false;
        std::vector<Move> current;
        const auto board = analysis_board(candidate, current);
        Color controlled[Board::MAX_ARR_SIZE];
        board.calculateArea(controlled, false, false, false, false);
        return controlled[liberty] == color && std::all_of(group.begin(), group.end(), [&](GoPoint point) {
            return controlled[Location::getLoc(point.x, point.y, size)] == color;
        });
    };
    if (!held(engine)) {
        std::cout << "result,unknown,territory-after-capture\n";
        return;
    }
    const auto captured_record = engine.serialize();
    const auto replies = engine.legal_actions();
    std::cout << "capture," << move_label(capture_action) << ',' << group.size() << ',' << replies.size() << '\n';
    if (replies.empty()) {
        std::cout << "result,unknown,no-defenses\n";
        return;
    }
    for (const auto& reply : replies) {
        auto branch = engine;
        if (!branch.apply(reply).accepted) throw std::logic_error("listed immediate defense was rejected");
        if (!held(branch)) {
            std::cout << "result,unknown,reply," << move_label(reply) << '\n';
            return;
        }
        const auto record = branch.serialize();
        GoEngine replay(size);
        if (!replay.restore(record).restored || replay.serialize() != record || !held(replay)) {
            throw std::runtime_error("cannot replay the enclosed capture");
        }
    }
    if (engine.serialize() != captured_record) throw std::logic_error("defense mutated the capture root");
    std::cout << "result,dead,held-territory\n";
}

enum class CaptureFollowup { split_capture, hold_after_capture, close_liberty };

void probe_capture_followup(GoEngine& engine, GoPoint opening, const std::vector<GoPoint>& anchors,
                           CaptureFollowup mode) {
    const auto defender = engine.stone_at(anchors.front().x, anchors.front().y);
    if (!defender || *defender == GoStone::empty) throw std::invalid_argument("expected a group anchor");
    const auto attacker = *defender == GoStone::black ? GoStone::white : GoStone::black;
    const auto color = attacker == GoStone::black ? C_BLACK : C_WHITE;
    const auto size = engine.board_size();
    if (opening.x < 0 || opening.y < 0 || opening.x >= size || opening.y >= size) {
        throw std::invalid_argument("first move is outside the board");
    }
    const bool close_liberty = mode == CaptureFollowup::close_liberty;
    const bool split_capture = mode == CaptureFollowup::split_capture;
    const auto opening_loc = Location::getLoc(opening.x, opening.y, size);
    bool closes_target_liberty = false;
    std::vector<Move> setup;
    const auto original = analysis_board(engine, setup);
    Color original_area[Board::MAX_ARR_SIZE];
    original.calculateArea(original_area, false, false, false, false);
    std::vector<GoPoint> targets;
    std::vector<Loc> heads;
    for (const auto anchor : anchors) {
        if (engine.stone_at(anchor.x, anchor.y) != defender) {
            throw std::invalid_argument("expected group anchors of the same color");
        }
        const auto loc = Location::getLoc(anchor.x, anchor.y, size);
        const auto head = original.chain_head[loc];
        if (std::find(heads.begin(), heads.end(), head) != heads.end()) {
            throw std::invalid_argument("repeated group anchor");
        }
        heads.push_back(head);
        if (original.getNumLiberties(loc) > 2) {
            std::cout << "result,unknown,liberty-count\n";
            return;
        }
        auto stone = loc;
        do {
            if (original_area[stone] != color) {
                std::cout << "result,unknown,original-territory\n";
                return;
            }
            targets.push_back({Location::getX(stone, size), Location::getY(stone, size)});
            if (close_liberty && original.getNumLiberties(loc) == 2 &&
                original.colors[opening_loc] == C_EMPTY) {
                for (int offset = 0; offset < 4; ++offset) {
                    closes_target_liberty |= stone + original.adj_offsets[offset] == opening_loc;
                }
            }
            stone = original.next_in_chain[stone];
        } while (stone != loc);
    }
    const auto controlled = [&](const GoEngine& candidate) {
        if (candidate.stone_at(opening.x, opening.y) != attacker) return false;
        std::vector<Move> stones;
        const auto board = analysis_board(candidate, stones);
        Color area[Board::MAX_ARR_SIZE];
        board.calculateArea(area, false, false, false, false);
        return area[opening_loc] == color &&
            std::all_of(targets.begin(), targets.end(), [&](GoPoint target) {
                return area[Location::getLoc(target.x, target.y, size)] == color;
            });
    };
    const auto attack = make_go_place(opening.x, opening.y);
    if (!engine.apply(make_go_resume_play(attacker == GoStone::black ? 0 : 1)).accepted ||
        !engine.apply(attack).accepted) {
        throw std::invalid_argument("specified first capture is illegal");
    }
    const auto captured = std::count_if(targets.begin(), targets.end(), [&](GoPoint target) {
        return engine.stone_at(target.x, target.y) == GoStone::empty;
    });
    const bool captured_all = static_cast<std::size_t>(captured) == targets.size();
    if (close_liberty && (!closes_target_liberty || captured != 0)) {
        std::cout << "result,unknown,first-liberty\n";
        return;
    }
    if (!close_liberty && (captured == 0 || captured_all == split_capture)) {
        std::cout << "result,unknown,first-capture\n";
        return;
    }

    const auto defenses = engine.legal_actions();
    if (defenses.empty()) throw std::logic_error("first capture has no legal reply");
    std::cout << (close_liberty ? "first-liberty," : "first-capture,")
              << move_label(attack) << ',' << captured << ',' << defenses.size() << '\n';
    std::cout << "defense," << (mode == CaptureFollowup::hold_after_capture ? "hold" : "capture") << ",verified-replies\n";
    for (const auto& defense : defenses) {
        auto after_defense = engine;
        if (!after_defense.apply(defense).accepted) throw std::logic_error("listed defense was rejected");
        bool proven = false;
        for (const auto& finishing_move : after_defense.legal_actions()) {
            if (finishing_move.kind != go_place_action_kind) continue;
            auto finished = after_defense;
            if (!finished.apply(finishing_move).accepted) throw std::logic_error("listed capture was rejected");
            if ((mode != CaptureFollowup::hold_after_capture && !anchors_present(finished, targets, GoStone::empty)) ||
                !controlled(finished)) continue;
            const auto replies = finished.legal_actions();
            if (replies.empty()) continue;
            proven = std::all_of(replies.begin(), replies.end(), [&](const auto& reply) {
                auto leaf = finished;
                if (!leaf.apply(reply).accepted) throw std::logic_error("listed final defense was rejected");
                // Only the split-capture protocol additionally requires the points to stay unoccupied.
                if ((split_capture && !anchors_present(leaf, targets, GoStone::empty)) || !controlled(leaf)) return false;
                const auto record = leaf.serialize();
                GoEngine replay(size);
                if (!replay.restore(record).restored || replay.serialize() != record || !controlled(replay)) {
                    throw std::runtime_error("cannot replay the capture follow-up");
                }
                return true;
            });
            if (!proven) continue;
            std::cout << move_label(defense) << ',' << move_label(finishing_move) << ',' << replies.size() << '\n';
            break;
        }
        if (!proven) {
            std::cout << "result,unknown,defense," << move_label(defense) << '\n';
            return;
        }
    }
    std::cout << "result,dead,held-territory\n";
}

void probe_resumption(GoEngine& engine, int argc, char** argv) {
    if (argc < 6) throw std::invalid_argument("--resume-probe requires a side and at least one move");
    const std::string_view side = argv[4];
    if (side != "B" && side != "W") throw std::invalid_argument("expected B or W to resume");
    if (!engine.apply(make_go_resume_play(side == "B" ? 0 : 1)).accepted) {
        throw std::runtime_error("cannot resume play from the original agreement");
    }
    for (int i = 5; i < argc; ++i) {
        const std::string_view move = argv[i];
        const auto player = engine.current_player();
        const auto action = [&] {
            if (move == "pass") return make_go_pass();
            const auto p = point(move);
            return make_go_place(p.x, p.y);
        }();
        if (!engine.apply(action).accepted) {
            throw std::invalid_argument("illegal continuation at step " + std::to_string(i - 4));
        }
        std::cout << "probe," << i - 4 << ',' << (player == 0 ? 'B' : 'W') << ',' << move << '\n';
    }
    const auto record = engine.serialize();
    GoEngine replay(engine.board_size());
    if (!replay.restore(record).restored || replay.serialize() != record) {
        throw std::runtime_error("cannot replay the continuation");
    }
    print_board(replay);
    print_group_review(replay);
}
} // namespace

int main(int argc, char** argv) {
    try {
        if (argc < 3) {
            throw std::invalid_argument(
                "use: go-strength-score-review size input.mocg "
                "[--groups | --resume-probe B|W x,y|pass [...] | --first-replies x,y [...] "
                "| --joint-gap attack x,y [...] | --joint-gap-deep attack x,y [...] "
                "| --joint-gap-focus attack x,y [...] | --atari-proof x,y [x,y ...] "
                "| --two-capture-proof attack x,y [x,y ...] | --capture-and-hold attack x,y [x,y ...] "
                "| --enclose-in-two attack x,y [x,y ...] "
                "| output.mocg [x,y ...]]");
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
        if (std::string_view(argv[3]) == "--groups") {
            if (argc != 4) throw std::invalid_argument("--groups takes no coordinates");
            print_group_review(engine);
            return 0;
        }
        const std::string_view mode = argv[3];
        if (mode != "--resume-probe" && mode != "--first-replies" && mode != "--single-gap" && mode != "--joint-gap" &&
            mode != "--atari-proof" && mode != "--two-capture-proof" && mode != "--capture-and-hold" &&
            mode != "--enclose-in-two" && mode != "--joint-gap-deep" && mode != "--joint-gap-focus" &&
            std::filesystem::exists(argv[3])) {
            throw std::invalid_argument("review output already exists");
        }
        if (!engine.undo() || !engine.undo() || engine.phase() != GoPhase::scoring) {
            throw std::runtime_error("cannot reopen the raw agreement");
        }
        if (mode == "--resume-probe") {
            probe_resumption(engine, argc, argv);
            return 0;
        }
        if (mode == "--first-replies") {
            if (argc < 5) throw std::invalid_argument("--first-replies requires one or more group anchors");
            std::vector<GoPoint> anchors;
            for (int i = 4; i < argc; ++i) anchors.push_back(point(argv[i]));
            probe_first_replies(engine, anchors);
            return 0;
        }
        if (mode == "--single-gap") {
            if (argc != 6) throw std::invalid_argument("--single-gap requires one group anchor and one attack");
            probe_gap(engine, {point(argv[4])}, argv[5]);
            return 0;
        }
        if (mode == "--joint-gap" || mode == "--joint-gap-deep" || mode == "--joint-gap-focus") {
            if (argc < 6) throw std::invalid_argument("--joint-gap requires one attack and one or more group anchors");
            std::vector<GoPoint> anchors;
            for (int i = 5; i < argc; ++i) anchors.push_back(point(argv[i]));
            if (mode == "--joint-gap") probe_gap(engine, anchors, argv[4]);
            else probe_deep_gap(engine, anchors, argv[4], mode == "--joint-gap-focus");
            return 0;
        }
        if (mode == "--atari-proof") {
            if (argc < 5) throw std::invalid_argument("--atari-proof requires a group anchor");
            std::vector<GoPoint> anchors;
            for (int index = 4; index < argc; ++index) anchors.push_back(point(argv[index]));
            probe_atari(engine, anchors);
            return 0;
        }
        if (mode == "--two-capture-proof" || mode == "--capture-and-hold" || mode == "--enclose-in-two") {
            if (argc < 6) throw std::invalid_argument("capture follow-up requires the first move and group anchors");
            std::vector<GoPoint> anchors;
            for (int index = 5; index < argc; ++index) anchors.push_back(point(argv[index]));
            const auto followup = mode == "--enclose-in-two" ? CaptureFollowup::close_liberty :
                mode == "--two-capture-proof" ? CaptureFollowup::split_capture : CaptureFollowup::hold_after_capture;
            probe_capture_followup(engine, point(argv[4]), anchors, followup);
            return 0;
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
