#include "mocs/engine/tiger_goat_game.hpp"

#include <algorithm>
#include <utility>

namespace mocs::engine::tiger_goat {
namespace {

constexpr std::uint8_t absent_point = 255;
constexpr std::uint8_t save_version = 2;
constexpr std::uint8_t board_version = 1;
constexpr std::size_t save_header_size = 49;

[[nodiscard]] bool on_board(const int point) noexcept {
    return point >= 0 && point < static_cast<int>(official_point_count);
}

[[nodiscard]] bool orthogonal(const Point& from, const Point& to) noexcept {
    return from.x == to.x || from.y == to.y;
}

[[nodiscard]] bool between(const int value, const int first, const int last) noexcept {
    return (first < value && value < last) || (last < value && value < first);
}

[[nodiscard]] std::uint32_t checksum(const std::vector<std::uint8_t>& bytes,
                                     const std::size_t end) noexcept {
    std::uint32_t hash = 2166136261u;
    for (std::size_t index = 0; index < end; ++index) {
        hash = (hash ^ bytes[index]) * 16777619u;
    }
    return hash;
}

void write_u32(std::vector<std::uint8_t>& bytes, const std::uint32_t value) {
    for (int shift = 0; shift < 32; shift += 8) {
        bytes.push_back(static_cast<std::uint8_t>(value >> shift));
    }
}

[[nodiscard]] std::uint32_t read_u32(const std::vector<std::uint8_t>& bytes,
                                     const std::size_t offset) noexcept {
    std::uint32_t value = 0;
    for (int shift = 0; shift < 32; shift += 8) {
        value |= static_cast<std::uint32_t>(bytes[offset + shift / 8]) << shift;
    }
    return value;
}

[[nodiscard]] bool same_opening_board(const MatchState& position) noexcept {
    return position.board == opening_position().board;
}

}  // namespace

TigerGoatGame::TigerGoatGame() { reset(); }

void TigerGoatGame::reset() noexcept {
    seed_ = {};
    seed_.board = opening_position().board;
    state_ = seed_;
    undo_states_.clear();
    actions_.clear();
}

bool TigerGoatGame::valid_seed(const MatchState& position) noexcept {
    if (position.to_move != tiger && position.to_move != goat) {
        return false;
    }
    if (position.phase != Phase::opening_removal && position.phase != Phase::play) {
        return false;
    }
    if (position.outcome != Outcome::ongoing || position.chain_tiger != -1
        || position.chain_visited != 0 || position.reserve > 14 || position.captured > 22) {
        return false;
    }
    int tigers = 0;
    int goats = 0;
    for (const auto piece : position.board) {
        if (piece == tiger) {
            ++tigers;
        } else if (piece == goat) {
            ++goats;
        } else if (piece != empty) {
            return false;
        }
    }
    if (tigers != tiger_count || goats + position.reserve + position.captured != 22) {
        return false;
    }
    if (position.phase == Phase::play && position.captured == 0) {
        return false;
    }
    return position.phase != Phase::opening_removal
           || (position.to_move == tiger && position.reserve == 14
               && position.captured == 0 && same_opening_board(position));
}

bool TigerGoatGame::reset_to_position(const MatchState& position) {
    if (!valid_seed(position)) {
        return false;
    }
    seed_ = position;
    state_ = position;
    undo_states_.clear();
    actions_.clear();
    if (state_.phase == Phase::play) {
        adjudicate();
    }
    return true;
}

int TigerGoatGame::jumped_goat(const int from, const int to) const noexcept {
    if (!on_board(from) || !on_board(to) || from == to) {
        return -1;
    }
    const auto& topology = official_topology();
    const auto& start = topology.points[static_cast<std::size_t>(from)];
    const auto& landing = topology.points[static_cast<std::size_t>(to)];
    if (!orthogonal(start, landing)) {
        return -1;
    }
    for (int middle = 0; middle < static_cast<int>(official_point_count); ++middle) {
        const auto& crossed = topology.points[static_cast<std::size_t>(middle)];
        if (!topology.connected[static_cast<std::size_t>(from)][static_cast<std::size_t>(middle)]
            || !topology.connected[static_cast<std::size_t>(middle)]
                                  [static_cast<std::size_t>(to)]) {
            continue;
        }
        if ((start.y == landing.y && crossed.y == start.y
             && between(crossed.x, start.x, landing.x))
            || (start.x == landing.x && crossed.x == start.x
                && between(crossed.y, start.y, landing.y))) {
            return middle;
        }
    }
    return -1;
}

bool TigerGoatGame::can_capture(const int from, const int to,
                                 const bool honor_chain) const noexcept {
    if (!on_board(from) || !on_board(to) || state_.board[static_cast<std::size_t>(from)] != tiger
        || state_.board[static_cast<std::size_t>(to)] != empty) {
        return false;
    }
    if (honor_chain && state_.phase == Phase::capture_chain
        && (from != state_.chain_tiger
            || (state_.chain_visited & (std::uint64_t{1} << to)) != 0)) {
        return false;
    }
    const int middle = jumped_goat(from, to);
    return middle >= 0 && state_.board[static_cast<std::size_t>(middle)] == goat;
}

std::vector<Action> TigerGoatGame::legal_actions() const {
    std::vector<Action> actions;
    if (state_.phase == Phase::finished) {
        return actions;
    }
    if (state_.phase == Phase::opening_removal) {
        for (int point = 0; point < static_cast<int>(official_point_count); ++point) {
            if (state_.board[static_cast<std::size_t>(point)] == goat) {
                actions.push_back({ActionKind::remove_goat, point, -1});
            }
        }
        return actions;
    }
    if (state_.phase == Phase::capture_chain) {
        for (int to = 0; to < static_cast<int>(official_point_count); ++to) {
            if (can_capture(state_.chain_tiger, to, true)) {
                actions.push_back({ActionKind::capture, state_.chain_tiger, to});
            }
        }
        actions.push_back({ActionKind::end_chain, -1, -1});
        return actions;
    }
    if (state_.to_move == goat && state_.reserve > 0) {
        for (int to = 0; to < static_cast<int>(official_point_count); ++to) {
            if (state_.board[static_cast<std::size_t>(to)] == empty) {
                actions.push_back({ActionKind::place_goat, -1, to});
            }
        }
        return actions;
    }
    const auto& topology = official_topology();
    for (int from = 0; from < static_cast<int>(official_point_count); ++from) {
        if (state_.board[static_cast<std::size_t>(from)] != state_.to_move) {
            continue;
        }
        for (int to = 0; to < static_cast<int>(official_point_count); ++to) {
            if (state_.board[static_cast<std::size_t>(to)] != empty) {
                continue;
            }
            if (topology.connected[static_cast<std::size_t>(from)]
                                  [static_cast<std::size_t>(to)]
                && (state_.to_move == goat
                    || orthogonal(topology.points[static_cast<std::size_t>(from)],
                                  topology.points[static_cast<std::size_t>(to)]))) {
                actions.push_back({ActionKind::step, from, to});
            }
            if (state_.to_move == tiger && can_capture(from, to, false)) {
                actions.push_back({ActionKind::capture, from, to});
            }
        }
    }
    if (actions.empty() && state_.to_move == goat) {
        actions.push_back({ActionKind::pass_goat, -1, -1});
    }
    return actions;
}

bool TigerGoatGame::goat_has_step() const noexcept {
    const auto& topology = official_topology();
    for (std::size_t from = 0; from < official_point_count; ++from) {
        if (state_.board[from] != goat) {
            continue;
        }
        for (std::size_t to = 0; to < official_point_count; ++to) {
            if (state_.board[to] == empty && topology.connected[from][to]) {
                return true;
            }
        }
    }
    return false;
}

bool TigerGoatGame::tiger_has_action() const noexcept {
    const auto& topology = official_topology();
    for (int from = 0; from < static_cast<int>(official_point_count); ++from) {
        if (state_.board[static_cast<std::size_t>(from)] != tiger) {
            continue;
        }
        for (int to = 0; to < static_cast<int>(official_point_count); ++to) {
            if (state_.board[static_cast<std::size_t>(to)] != empty) {
                continue;
            }
            if ((topology.connected[static_cast<std::size_t>(from)]
                                   [static_cast<std::size_t>(to)]
                 && orthogonal(topology.points[static_cast<std::size_t>(from)],
                               topology.points[static_cast<std::size_t>(to)]))
                || can_capture(from, to, false)) {
                return true;
            }
        }
    }
    return false;
}

void TigerGoatGame::adjudicate() noexcept {
    int goats = state_.reserve;
    for (const auto piece : state_.board) {
        goats += piece == goat;
    }
    if (goats < 8) {
        state_.outcome = Outcome::tiger_wins;
    } else if (!tiger_has_action()) {
        state_.outcome = Outcome::goat_wins;
    }
    if (state_.outcome != Outcome::ongoing) {
        state_.phase = Phase::finished;
        state_.chain_tiger = -1;
        state_.chain_visited = 0;
    }
}

void TigerGoatGame::finish_tiger_action() noexcept {
    state_.phase = Phase::play;
    state_.to_move = goat;
    state_.chain_tiger = -1;
    state_.chain_visited = 0;
    adjudicate();
}

void TigerGoatGame::record_action(const Action& action) {
    undo_states_.push_back(state_);
    try {
        actions_.push_back(action);
    } catch (...) {
        // Keep both histories aligned if the second allocation fails.
        undo_states_.pop_back();
        throw;
    }
}

ApplyError TigerGoatGame::apply(const Action& action) {
    if (state_.phase == Phase::finished) {
        return ApplyError::finished;
    }
    if (actions_.size() >= max_actions) {
        return ApplyError::history_limit;
    }
    if (action.kind == ActionKind::pass_goat) {
        // A blocked goat side must yield; this does not introduce a new win condition.
        if (state_.phase != Phase::play || state_.to_move != goat
            || state_.reserve != 0 || goat_has_step()) {
            return ApplyError::wrong_phase;
        }
        if (action.from != -1 || action.to != -1) {
            return ApplyError::invalid_point;
        }
        record_action(action);
        state_.to_move = tiger;
        adjudicate();
        return ApplyError::none;
    }
    if (action.kind == ActionKind::resign) {
        if (action.from != -1 || action.to != -1) {
            return ApplyError::invalid_point;
        }
        record_action(action);
        state_.outcome = state_.to_move == goat ? Outcome::tiger_wins : Outcome::goat_wins;
        state_.phase = Phase::finished;
        state_.chain_tiger = -1;
        state_.chain_visited = 0;
        return ApplyError::none;
    }
    if (action.kind == ActionKind::end_chain) {
        if (state_.phase != Phase::capture_chain) {
            return ApplyError::wrong_phase;
        }
        if (action.from != -1 || action.to != -1) {
            return ApplyError::invalid_point;
        }
        record_action(action);
        finish_tiger_action();
        return ApplyError::none;
    }
    if (action.kind == ActionKind::remove_goat) {
        if (state_.phase != Phase::opening_removal || state_.to_move != tiger) {
            return ApplyError::wrong_phase;
        }
        if (!on_board(action.from) || action.to != -1) {
            return ApplyError::invalid_point;
        }
        if (state_.board[static_cast<std::size_t>(action.from)] != goat) {
            return ApplyError::wrong_source;
        }
        record_action(action);
        state_.board[static_cast<std::size_t>(action.from)] = empty;
        ++state_.captured;
        state_.phase = Phase::play;
        state_.to_move = goat;
        adjudicate();
        return ApplyError::none;
    }
    if (action.kind == ActionKind::place_goat) {
        if (state_.phase != Phase::play || state_.to_move != goat || state_.reserve == 0) {
            return ApplyError::wrong_phase;
        }
        if (action.from != -1 || !on_board(action.to)) {
            return ApplyError::invalid_point;
        }
        if (state_.board[static_cast<std::size_t>(action.to)] != empty) {
            return ApplyError::occupied;
        }
        record_action(action);
        state_.board[static_cast<std::size_t>(action.to)] = goat;
        --state_.reserve;
        state_.to_move = tiger;
        adjudicate();
        return ApplyError::none;
    }
    if (action.kind != ActionKind::step && action.kind != ActionKind::capture) {
        return ApplyError::wrong_phase;
    }
    if (state_.phase == Phase::opening_removal
        || (state_.phase == Phase::capture_chain && action.kind != ActionKind::capture)
        || (state_.to_move == goat && state_.reserve > 0)) {
        return ApplyError::wrong_phase;
    }
    if (!on_board(action.from) || !on_board(action.to)) {
        return ApplyError::invalid_point;
    }
    if (state_.board[static_cast<std::size_t>(action.from)] != state_.to_move
        || (state_.phase == Phase::capture_chain && action.from != state_.chain_tiger)) {
        return ApplyError::wrong_source;
    }
    if (state_.board[static_cast<std::size_t>(action.to)] != empty) {
        return ApplyError::occupied;
    }
    const auto& topology = official_topology();
    if (action.kind == ActionKind::step) {
        if (!topology.connected[static_cast<std::size_t>(action.from)]
                               [static_cast<std::size_t>(action.to)]) {
            return ApplyError::not_connected;
        }
        if (state_.to_move == tiger
            && !orthogonal(topology.points[static_cast<std::size_t>(action.from)],
                           topology.points[static_cast<std::size_t>(action.to)])) {
            return ApplyError::tiger_diagonal;
        }
        record_action(action);
        state_.board[static_cast<std::size_t>(action.from)] = empty;
        state_.board[static_cast<std::size_t>(action.to)] = state_.to_move;
        if (state_.to_move == tiger) {
            finish_tiger_action();
        } else {
            state_.to_move = tiger;
            adjudicate();
        }
        return ApplyError::none;
    }
    if (state_.to_move != tiger) {
        return ApplyError::wrong_phase;
    }
    if (state_.phase == Phase::capture_chain
        && (state_.chain_visited & (std::uint64_t{1} << action.to)) != 0) {
        return ApplyError::visited_landing;
    }
    const int crossed = jumped_goat(action.from, action.to);
    if (crossed < 0) {
        return ApplyError::not_connected;
    }
    if (state_.board[static_cast<std::size_t>(crossed)] != goat) {
        return ApplyError::no_goat_to_capture;
    }
    record_action(action);
    state_.board[static_cast<std::size_t>(action.from)] = empty;
    state_.board[static_cast<std::size_t>(crossed)] = empty;
    state_.board[static_cast<std::size_t>(action.to)] = tiger;
    ++state_.captured;
    if (state_.phase != Phase::capture_chain) {
        state_.chain_visited = std::uint64_t{1} << action.from;
    }
    state_.chain_visited |= std::uint64_t{1} << action.to;
    state_.chain_tiger = static_cast<std::int8_t>(action.to);
    state_.phase = Phase::capture_chain;
    adjudicate();
    if (state_.phase != Phase::finished) {
        bool continuation = false;
        for (int to = 0; to < static_cast<int>(official_point_count); ++to) {
            continuation |= can_capture(action.to, to, true);
        }
        if (!continuation) {
            finish_tiger_action();
        }
    }
    return ApplyError::none;
}

bool TigerGoatGame::undo() noexcept {
    if (undo_states_.empty()) {
        return false;
    }
    state_ = undo_states_.back();
    undo_states_.pop_back();
    actions_.pop_back();
    return true;
}

std::vector<std::uint8_t> TigerGoatGame::serialize() const {
    std::vector<std::uint8_t> bytes;
    bytes.reserve(save_header_size + actions_.size() * 3 + 4);
    bytes.insert(bytes.end(), {'M', 'O', 'C', 'T', save_version, board_version,
                               static_cast<std::uint8_t>(seed_.phase), seed_.to_move,
                               seed_.reserve, seed_.captured});
    bytes.insert(bytes.end(), seed_.board.begin(), seed_.board.end());
    bytes.push_back(static_cast<std::uint8_t>(actions_.size()));
    bytes.push_back(static_cast<std::uint8_t>(actions_.size() >> 8));
    for (const auto& action : actions_) {
        bytes.push_back(static_cast<std::uint8_t>(action.kind));
        bytes.push_back(action.from < 0 ? absent_point
                                        : static_cast<std::uint8_t>(action.from));
        bytes.push_back(action.to < 0 ? absent_point
                                      : static_cast<std::uint8_t>(action.to));
    }
    write_u32(bytes, checksum(bytes, bytes.size()));
    return bytes;
}

bool TigerGoatGame::restore(const std::vector<std::uint8_t>& bytes) {
    if (bytes.size() < save_header_size + 4 || bytes[0] != 'M' || bytes[1] != 'O'
        || bytes[2] != 'C' || bytes[3] != 'T' || (bytes[4] != 1 && bytes[4] != save_version)
        || bytes[5] != board_version) {
        return false;
    }
    const std::size_t count = static_cast<std::size_t>(bytes[47])
                              | static_cast<std::size_t>(bytes[48]) << 8;
    if (count > max_actions || bytes.size() != save_header_size + count * 3 + 4
        || read_u32(bytes, bytes.size() - 4) != checksum(bytes, bytes.size() - 4)) {
        return false;
    }
    MatchState seed{};
    seed.phase = static_cast<Phase>(bytes[6]);
    seed.to_move = bytes[7];
    seed.reserve = bytes[8];
    seed.captured = bytes[9];
    std::copy_n(bytes.begin() + 10, official_point_count, seed.board.begin());
    TigerGoatGame candidate;
    if (!candidate.reset_to_position(seed)) {
        return false;
    }
    for (std::size_t index = 0; index < count; ++index) {
        const std::size_t offset = save_header_size + index * 3;
        if (bytes[4] == 1 && bytes[offset] > static_cast<std::uint8_t>(ActionKind::resign)) {
            return false;
        }
        const auto point = [](const std::uint8_t value) -> int {
            return value == absent_point ? -1 : value;
        };
        const Action action{static_cast<ActionKind>(bytes[offset]),
                            point(bytes[offset + 1]), point(bytes[offset + 2])};
        if (candidate.apply(action) != ApplyError::none) {
            return false;
        }
    }
    seed_ = candidate.seed_;
    state_ = candidate.state_;
    undo_states_.swap(candidate.undo_states_);
    actions_.swap(candidate.actions_);
    return true;
}

}  // namespace mocs::engine::tiger_goat
