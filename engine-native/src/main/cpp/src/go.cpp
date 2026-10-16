#include "mocs/engine/go.hpp"
#include "../games/go/go_handicap.hpp"

#include <algorithm>
#include <cstddef>
#include <cstdint>
#include <stdexcept>
#include <utility>

namespace mocs::engine {
namespace {
constexpr std::uint8_t format_version = 3;
constexpr std::size_t legacy_header_size = 10;
constexpr std::size_t v2_header_size = 11;
constexpr std::size_t header_size = 16;
constexpr std::size_t action_size = 17;
constexpr std::size_t v3_action_size = 21;
constexpr std::size_t max_board_actions = 4'096;
constexpr std::size_t max_events = 16'384;

void append_u32(std::vector<std::uint8_t>& data, const std::uint32_t value) {
    for (unsigned shift = 0; shift < 32; shift += 8) {
        data.push_back(static_cast<std::uint8_t>(value >> shift));
    }
}

[[nodiscard]] std::uint32_t read_u32(
    const std::vector<std::uint8_t>& data, const std::size_t at
) {
    std::uint32_t value = 0;
    for (unsigned offset = 0; offset < 4; ++offset) {
        value |= static_cast<std::uint32_t>(data[at + offset]) << (8 * offset);
    }
    return value;
}

[[nodiscard]] GoStone player_stone(const std::uint8_t player) noexcept {
    return player == 0 ? GoStone::black : GoStone::white;
}
}  // namespace

EngineAction make_go_place(const std::int32_t x, const std::int32_t y) noexcept {
    return {go_place_action_kind, {x, y, 0, 0}};
}

EngineAction make_go_pass() noexcept {
    return {go_pass_action_kind, {0, 0, 0, 0}};
}

EngineAction make_go_toggle_dead(
    const std::int32_t x, const std::int32_t y, const std::uint8_t player
) noexcept {
    return {go_toggle_dead_action_kind, {x, y, player, 0}};
}

EngineAction make_go_confirm_score(
    const std::uint8_t player, const std::uint32_t proposal_revision
) noexcept {
    return {go_confirm_score_action_kind,
            {player, static_cast<std::int32_t>(proposal_revision), 0, 0}};
}

EngineAction make_go_resume_play(const std::uint8_t first_player) noexcept {
    return {go_resume_play_action_kind, {first_player, 0, 0, 0}};
}

GoEngine::GoEngine(const std::uint8_t size, const std::int32_t black_komi_quarters,
                   const std::uint8_t handicap_stones)
    : size_(size), black_komi_quarters_(black_komi_quarters),
      handicap_stones_(handicap_stones) {
    if (size != 9 && size != 13 && size != 19) {
        throw std::invalid_argument("Go board size must be 9, 13, or 19");
    }
    if (black_komi_quarters != 0 && black_komi_quarters != 15) {
        throw std::invalid_argument("Go komi must be zero or 3.75 stones");
    }
    if (handicap_stones != 0 &&
        (handicap_stones < 2 || handicap_stones > 9 || black_komi_quarters != 0)) {
        throw std::invalid_argument("Go handicap must be 2-9 stones with zero komi");
    }
    reset();
}

GameType GoEngine::game_type() const noexcept { return GameType::go; }

std::uint8_t GoEngine::current_player() const noexcept {
    return positions_.back().player;
}

void GoEngine::reset() {
    positions_.clear();
    hashes_.clear();
    actions_.clear();
    board_action_count_ = 0;
    proposal_clock_ = 0;
    positions_.push_back({
        std::vector<GoStone>(static_cast<std::size_t>(size_) * size_, GoStone::empty),
        0, 0, GoPhase::playing,
        std::vector<bool>(static_cast<std::size_t>(size_) * size_, false),
        0, 0, GameResult::ongoing
    });
    if (handicap_stones_ != 0) {
        const auto setup = make_go_handicap_setup(size_, handicap_stones_);
        for (const auto point : setup->black_stones) {
            positions_.back().board[index(point.x, point.y)] = GoStone::black;
        }
        positions_.back().player = setup->first_player;
    }
    hashes_.push_back(board_hash(positions_.back().board));
}

std::uint64_t GoEngine::board_hash(const std::vector<GoStone>& board) noexcept {
    std::uint64_t hash = 14695981039346656037ULL;
    for (const auto stone : board) {
        hash ^= static_cast<std::uint8_t>(stone);
        hash *= 1099511628211ULL;
    }
    return hash;
}

bool GoEngine::inside(const std::int32_t x, const std::int32_t y) const noexcept {
    return x >= 0 && y >= 0 && x < size_ && y < size_;
}

std::size_t GoEngine::index(const std::int32_t x, const std::int32_t y) const noexcept {
    return static_cast<std::size_t>(y) * size_ + static_cast<std::size_t>(x);
}

std::vector<std::size_t> GoEngine::neighbors(const std::size_t point) const {
    const auto x = static_cast<std::int32_t>(point % size_);
    const auto y = static_cast<std::int32_t>(point / size_);
    std::vector<std::size_t> result;
    result.reserve(4);
    if (x > 0) result.push_back(index(x - 1, y));
    if (x + 1 < size_) result.push_back(index(x + 1, y));
    if (y > 0) result.push_back(index(x, y - 1));
    if (y + 1 < size_) result.push_back(index(x, y + 1));
    return result;
}

bool GoEngine::group_has_liberty(
    const std::vector<GoStone>& board,
    const std::size_t start,
    std::vector<std::size_t>& group
) const {
    const auto color = board[start];
    std::vector<bool> visited(board.size(), false);
    group.clear();
    group.push_back(start);
    visited[start] = true;
    bool has_liberty = false;
    for (std::size_t cursor = 0; cursor < group.size(); ++cursor) {
        for (const auto neighbor : neighbors(group[cursor])) {
            if (board[neighbor] == GoStone::empty) {
                has_liberty = true;
            } else if (board[neighbor] == color && !visited[neighbor]) {
                visited[neighbor] = true;
                group.push_back(neighbor);
            }
        }
    }
    return has_liberty;
}

bool GoEngine::placement_board(
    const std::int32_t x,
    const std::int32_t y,
    std::vector<GoStone>& result
) const {
    if (!inside(x, y) || phase() != GoPhase::playing) return false;
    const auto point = index(x, y);
    const auto& old = positions_.back().board;
    if (old[point] != GoStone::empty) return false;

    result = old;
    const auto color = player_stone(current_player());
    const auto opponent = color == GoStone::black ? GoStone::white : GoStone::black;
    result[point] = color;
    std::vector<std::size_t> group;
    for (const auto neighbor : neighbors(point)) {
        if (result[neighbor] == opponent &&
            !group_has_liberty(result, neighbor, group)) {
            for (const auto captured : group) result[captured] = GoStone::empty;
        }
    }
    if (!group_has_liberty(result, point, group)) return false;

    // A pass never repeats a board illegally; only a placement is compared.
    const auto hash = board_hash(result);
    for (std::size_t i = 0; i < positions_.size(); ++i) {
        if (hashes_[i] == hash && positions_[i].board == result) return false;
    }
    return true;
}

ActionResult GoEngine::apply(const EngineAction& action) {
    if (phase() == GoPhase::finished ||
        (actions_.size() >= max_events && action.kind != go_confirm_score_action_kind)) {
        return {false, EngineError::invalid_state};
    }
    if (phase() == GoPhase::scoring) {
        auto next = positions_.back();
        if (action.kind == go_toggle_dead_action_kind) {
            if (actions_.size() >= max_events - 2 || proposal_clock_ >= 0x7FFFFFFFU) {
                return {false, EngineError::invalid_state};
            }
            if (!inside(action.arguments[0], action.arguments[1]) ||
                action.arguments[2] < 0 || action.arguments[2] > 1 ||
                action.arguments[3] != 0) {
                return {false, EngineError::illegal_action};
            }
            const auto point = index(action.arguments[0], action.arguments[1]);
            if (next.board[point] == GoStone::empty) {
                return {false, EngineError::illegal_action};
            }
            std::vector<std::size_t> group;
            (void)group_has_liberty(next.board, point, group);
            const bool mark = !next.dead[point];
            for (const auto member : group) next.dead[member] = mark;
            next.confirmations = 0;
            next.proposal_revision = ++proposal_clock_;
        } else if (action.kind == go_confirm_score_action_kind) {
            if (action.arguments[0] < 0 || action.arguments[0] > 1 ||
                action.arguments[1] < 0 ||
                static_cast<std::uint32_t>(action.arguments[1]) != next.proposal_revision ||
                action.arguments[2] != 0 || action.arguments[3] != 0) {
                return {false, EngineError::illegal_action};
            }
            const auto bit = static_cast<std::uint8_t>(1U << action.arguments[0]);
            if ((next.confirmations & bit) != 0) return {false, EngineError::illegal_action};
            next.confirmations |= bit;
            if (next.confirmations == 3) {
                const auto score = preview_area_score(scoring_state().dead_stones,
                                                      black_komi_quarters_);
                if (!score) return {false, EngineError::invalid_state};
                next.phase = GoPhase::finished;
                next.result = score->result;
            }
        } else if (action.kind == go_resume_play_action_kind) {
            if (actions_.size() >= max_events - 4 || board_action_count_ >= max_board_actions) {
                return {false, EngineError::invalid_state};
            }
            if (action.arguments[0] < 0 || action.arguments[0] > 1 ||
                action.arguments[1] != 0 || action.arguments[2] != 0 ||
                action.arguments[3] != 0) {
                return {false, EngineError::illegal_action};
            }
            next.player = static_cast<std::uint8_t>(action.arguments[0]);
            next.passes = 0;
            next.phase = GoPhase::playing;
            std::fill(next.dead.begin(), next.dead.end(), false);
            next.confirmations = 0;
            next.proposal_revision = 0;
        } else {
            return {false, EngineError::invalid_state};
        }
        const auto hash = hashes_.back();
        positions_.push_back(std::move(next));
        hashes_.push_back(hash);
        actions_.push_back(action);
        return {true, EngineError::none};
    }
    if (board_action_count_ >= max_board_actions && action.kind != go_pass_action_kind) {
        return {false, EngineError::invalid_state};
    }
    if (actions_.size() >= max_events - 4 && action.kind != go_pass_action_kind) {
        return {false, EngineError::invalid_state};
    }
    if (action.kind == go_pass_action_kind && action.arguments == make_go_pass().arguments) {
        auto next = positions_.back();
        const auto hash = hashes_.back();
        next.player ^= 1;
        ++next.passes;
        if (next.passes == 2) {
            if (proposal_clock_ >= 0x7FFFFFFFU) return {false, EngineError::invalid_state};
            next.phase = GoPhase::scoring;
            next.proposal_revision = ++proposal_clock_;
        }
        positions_.push_back(std::move(next));
        hashes_.push_back(hash);
        actions_.push_back(action);
        ++board_action_count_;
        return {true, EngineError::none};
    }
    if (action.kind != go_place_action_kind || action.arguments[2] != 0 ||
        action.arguments[3] != 0) {
        return {false, EngineError::illegal_action};
    }
    std::vector<GoStone> board;
    if (!placement_board(action.arguments[0], action.arguments[1], board)) {
        return {false, EngineError::illegal_action};
    }
    const auto hash = board_hash(board);
    auto next = positions_.back();
    next.board = std::move(board);
    next.player ^= 1;
    next.passes = 0;
    positions_.push_back(std::move(next));
    hashes_.push_back(hash);
    actions_.push_back(action);
    ++board_action_count_;
    return {true, EngineError::none};
}

bool GoEngine::undo() {
    if (actions_.empty()) return false;
    if (actions_.back().kind == go_place_action_kind ||
        actions_.back().kind == go_pass_action_kind) --board_action_count_;
    actions_.pop_back();
    positions_.pop_back();
    hashes_.pop_back();
    return true;
}

std::vector<EngineAction> GoEngine::legal_actions() const {
    std::vector<EngineAction> result;
    if (phase() == GoPhase::finished) return result;
    if (phase() == GoPhase::scoring) {
        const auto& position = positions_.back();
        std::vector<bool> visited(position.board.size(), false);
        std::vector<std::size_t> group;
        for (std::size_t point = 0; point < position.board.size() &&
             actions_.size() < max_events - 2 && proposal_clock_ < 0x7FFFFFFFU; ++point) {
            if (visited[point] || position.board[point] == GoStone::empty) continue;
            (void)group_has_liberty(position.board, point, group);
            for (const auto member : group) visited[member] = true;
            for (std::uint8_t player = 0; player < 2; ++player) {
                result.push_back(make_go_toggle_dead(static_cast<std::int32_t>(point % size_),
                                                    static_cast<std::int32_t>(point / size_),
                                                    player));
            }
        }
        for (std::uint8_t player = 0; player < 2; ++player) {
            if ((position.confirmations & (1U << player)) == 0) {
                result.push_back(make_go_confirm_score(player, position.proposal_revision));
            }
            if (actions_.size() < max_events - 4 && board_action_count_ < max_board_actions) {
                result.push_back(make_go_resume_play(player));
            }
        }
        return result;
    }
    if (actions_.size() >= max_events) return result;
    if (board_action_count_ >= max_board_actions || actions_.size() >= max_events - 4) {
        result.push_back(make_go_pass());
        return result;
    }
    result.reserve(static_cast<std::size_t>(size_) * size_ + 1);
    std::vector<GoStone> board;
    for (std::int32_t y = 0; y < size_; ++y) {
        for (std::int32_t x = 0; x < size_; ++x) {
            if (placement_board(x, y, board)) result.push_back(make_go_place(x, y));
        }
    }
    result.push_back(make_go_pass());
    return result;
}

GameResult GoEngine::game_result() const noexcept { return positions_.back().result; }

std::vector<std::uint8_t> GoEngine::serialize() const {
    std::vector<std::uint8_t> result{'M', 'O', 'C', 'G', format_version, size_,
                                  static_cast<std::uint8_t>(black_komi_quarters_),
                                  handicap_stones_};
    const auto count = static_cast<std::uint32_t>(actions_.size());
    append_u32(result, proposal_clock_);
    append_u32(result, count);
    for (std::size_t i = 0; i < actions_.size(); ++i) {
        const auto& action = actions_[i];
        result.push_back(static_cast<std::uint8_t>(action.kind));
        for (const auto argument : action.arguments) {
            append_u32(result, static_cast<std::uint32_t>(argument));
        }
        append_u32(result, positions_[i + 1].proposal_revision);
    }
    return result;
}

RestoreResult GoEngine::restore(const std::vector<std::uint8_t>& data) {
    if (data.size() < legacy_header_size || data[0] != 'M' || data[1] != 'O' ||
        data[2] != 'C' || data[3] != 'G' || data[4] < 1 || data[4] > format_version ||
        data[5] != size_) {
        return {false, EngineError::corrupted_data};
    }
    const bool legacy = data[4] == 1;
    const auto start = legacy ? legacy_header_size :
        data[4] == 2 ? v2_header_size : header_size;
    const auto record_size = legacy ? 3 : data[4] == 2 ? action_size : v3_action_size;
    if (data.size() < start || (!legacy && data[6] != 0 && data[6] != 15)) {
        return {false, EngineError::corrupted_data};
    }
    const auto handicap = data[4] == 3 ? data[7] : 0;
    if (handicap != 0 && (handicap < 2 || handicap > 9 || data[6] != 0)) {
        return {false, EngineError::corrupted_data};
    }
    const auto count = read_u32(data, start - 4);
    if (count > (legacy ? max_board_actions : max_events + 2) ||
        data.size() != start + record_size * count) {
        return {false, EngineError::corrupted_data};
    }
    GoEngine candidate(size_, legacy ? 15 : data[6], static_cast<std::uint8_t>(handicap));
    for (std::size_t i = start; i < data.size(); i += record_size) {
        EngineAction action{};
        if (!legacy) {
            action.kind = data[i];
            for (std::size_t argument = 0; argument < 4; ++argument) {
                const auto value = read_u32(data, i + 1 + argument * 4);
                // All supported coordinates, actors and revisions are nonnegative.
                if (value > 0x7FFFFFFFU) return {false, EngineError::corrupted_data};
                action.arguments[argument] = static_cast<std::int32_t>(value);
            }
        } else if (data[i] == go_place_action_kind) {
            action = make_go_place(data[i + 1], data[i + 2]);
        } else if (data[i] == go_pass_action_kind && data[i + 1] == 0 && data[i + 2] == 0) {
            action = make_go_pass();
        } else {
            return {false, EngineError::corrupted_data};
        }
        const bool new_proposal = action.kind == go_toggle_dead_action_kind ||
            (action.kind == go_pass_action_kind && candidate.positions_.back().passes == 1);
        if (new_proposal) {
            const auto revision = data[4] == 3 ? read_u32(data, i + action_size) :
                static_cast<std::uint32_t>((i - start) / record_size + 1);
            if (revision == 0 || revision <= candidate.proposal_clock_ || revision > 0x7FFFFFFFU) {
                return {false, EngineError::corrupted_data};
            }
            candidate.proposal_clock_ = revision - 1;
        }
        if (!candidate.apply(action).accepted ||
            (data[4] == 3 && candidate.positions_.back().proposal_revision !=
             read_u32(data, i + action_size))) {
            return {false, EngineError::corrupted_data};
        }
    }
    if (data[4] == 3) {
        const auto clock = read_u32(data, 8);
        if (clock < candidate.proposal_clock_ || clock > 0x7FFFFFFFU) {
            return {false, EngineError::corrupted_data};
        }
        candidate.proposal_clock_ = clock;
    }
    positions_ = std::move(candidate.positions_);
    hashes_ = std::move(candidate.hashes_);
    actions_ = std::move(candidate.actions_);
    board_action_count_ = candidate.board_action_count_;
    black_komi_quarters_ = candidate.black_komi_quarters_;
    handicap_stones_ = candidate.handicap_stones_;
    proposal_clock_ = candidate.proposal_clock_;
    return {true, EngineError::none};
}

std::uint8_t GoEngine::board_size() const noexcept { return size_; }

std::optional<GoStone> GoEngine::stone_at(
    const std::int32_t x, const std::int32_t y
) const noexcept {
    if (!inside(x, y)) return std::nullopt;
    return positions_.back().board[index(x, y)];
}

bool GoEngine::ready_for_scoring() const noexcept {
    return phase() != GoPhase::playing;
}

GoPhase GoEngine::phase() const noexcept { return positions_.back().phase; }

GoScoringState GoEngine::scoring_state() const {
    const auto& position = positions_.back();
    GoScoringState result{position.phase, position.proposal_revision,
                          (position.confirmations & 1) != 0,
                          (position.confirmations & 2) != 0, {}};
    for (std::size_t point = 0; point < position.dead.size(); ++point) {
        if (position.dead[point]) result.dead_stones.push_back({
            static_cast<std::int32_t>(point % size_), static_cast<std::int32_t>(point / size_)
        });
    }
    return result;
}

std::optional<GoScore> GoEngine::final_score() const {
    if (phase() != GoPhase::finished) return std::nullopt;
    return preview_area_score(scoring_state().dead_stones, black_komi_quarters_);
}

std::int32_t GoEngine::black_komi_quarters() const noexcept { return black_komi_quarters_; }

std::uint8_t GoEngine::handicap_stones() const noexcept { return handicap_stones_; }

std::vector<GoPoint> GoEngine::handicap_points() const {
    if (handicap_stones_ == 0) return {};
    return make_go_handicap_setup(size_, handicap_stones_)->black_stones;
}

std::vector<EngineAction> GoEngine::action_history() const {
    std::vector<EngineAction> moves;
    for (const auto& action : actions_) {
        if (action.kind == go_place_action_kind || action.kind == go_pass_action_kind) {
            moves.push_back(action);
        }
    }
    return moves;
}

std::vector<EngineAction> GoEngine::event_history() const { return actions_; }

std::optional<GoScore> GoEngine::preview_area_score(
    const std::vector<GoPoint>& agreed_dead_stones,
    const std::int32_t requested_komi
) const {
    const auto black_komi_quarters = requested_komi == -1 ? black_komi_quarters_ : requested_komi;
    if (!ready_for_scoring() ||
        (black_komi_quarters != 0 && black_komi_quarters != 15) ||
        (handicap_stones_ != 0 && black_komi_quarters != 0)) {
        return std::nullopt;
    }
    auto board = positions_.back().board;
    for (const auto point : agreed_dead_stones) {
        if (!inside(point.x, point.y) ||
            positions_.back().board[index(point.x, point.y)] == GoStone::empty) {
            return std::nullopt;
        }
    }
    std::vector<std::size_t> group;
    for (const auto point : agreed_dead_stones) {
        const auto start = index(point.x, point.y);
        if (board[start] == GoStone::empty) continue;
        (void)group_has_liberty(board, start, group);
        for (const auto member : group) board[member] = GoStone::empty;
    }

    std::int32_t black = 0;
    std::int32_t white = 0;
    std::vector<bool> visited(board.size(), false);
    for (std::size_t start = 0; start < board.size(); ++start) {
        if (board[start] == GoStone::black) {
            black += 4;
            continue;
        }
        if (board[start] == GoStone::white) {
            white += 4;
            continue;
        }
        if (visited[start]) continue;
        std::vector<std::size_t> region{start};
        visited[start] = true;
        bool touches_black = false;
        bool touches_white = false;
        for (std::size_t cursor = 0; cursor < region.size(); ++cursor) {
            for (const auto neighbor : neighbors(region[cursor])) {
                if (board[neighbor] == GoStone::black) {
                    touches_black = true;
                } else if (board[neighbor] == GoStone::white) {
                    touches_white = true;
                } else if (!visited[neighbor]) {
                    visited[neighbor] = true;
                    region.push_back(neighbor);
                }
            }
        }
        const auto points = static_cast<std::int32_t>(region.size());
        if (touches_black == touches_white) {
            // Chinese counting splits shared dame equally. An unbounded empty
            // board is also split evenly, so each assessment uses half-board
            // comparison rather than claiming that either color occupies it.
            black += 2 * points;
            white += 2 * points;
        } else if (touches_black) {
            black += 4 * points;
        } else if (touches_white) {
            white += 4 * points;
        }
    }
    // Chinese handicap counting returns n full-area points (n/2 stones in
    // the half-board method), independently of the ordinary komi.
    const auto handicap_return = 4 * handicap_stones_;
    const auto effective_white = white + 2 * black_komi_quarters + handicap_return;
    const auto result = black > effective_white ? GameResult::first_player_win :
        black < effective_white ? GameResult::second_player_win : GameResult::draw;
    return GoScore{black, white, black_komi_quarters, result, handicap_return};
}

}  // namespace mocs::engine
