#pragma once

#include "mocs/engine/tiger_goat_board.hpp"

#include <array>
#include <cstddef>
#include <cstdint>
#include <vector>

namespace mocs::engine::tiger_goat {

enum class Phase : std::uint8_t {
    opening_removal,
    play,
    capture_chain,
    finished,
};

enum class Outcome : std::uint8_t {
    ongoing,
    tiger_wins,
    goat_wins,
};

enum class ActionKind : std::uint8_t {
    remove_goat,
    place_goat,
    step,
    capture,
    end_chain,
    resign,
    pass_goat,
};

struct Action {
    ActionKind kind;
    int from = -1;
    int to = -1;

    [[nodiscard]] bool operator==(const Action& other) const noexcept {
        return kind == other.kind && from == other.from && to == other.to;
    }
};

struct MatchState {
    std::array<std::uint8_t, official_point_count> board{};
    std::uint8_t to_move = tiger;
    std::uint8_t reserve = 14;
    std::uint8_t captured = 0;
    Phase phase = Phase::opening_removal;
    Outcome outcome = Outcome::ongoing;
    std::int8_t chain_tiger = -1;
    std::uint64_t chain_visited = 0;
};

enum class ApplyError : std::uint8_t {
    none,
    finished,
    wrong_phase,
    invalid_point,
    wrong_source,
    occupied,
    not_connected,
    tiger_diagonal,
    no_goat_to_capture,
    visited_landing,
    history_limit,
};

class TigerGoatGame {
public:
    TigerGoatGame();

    [[nodiscard]] const MatchState& state() const noexcept { return state_; }
    [[nodiscard]] const MatchState& initial_state() const noexcept { return seed_; }
    [[nodiscard]] std::size_t action_count() const noexcept { return actions_.size(); }
    [[nodiscard]] std::vector<Action> legal_actions() const;
    [[nodiscard]] ApplyError apply(const Action& action);
    [[nodiscard]] bool undo() noexcept;
    void reset() noexcept;

    // Analysis positions start a new history; a pending capture chain is restored by replay only.
    [[nodiscard]] bool reset_to_position(const MatchState& position);
    [[nodiscard]] std::vector<std::uint8_t> serialize() const;
    [[nodiscard]] bool restore(const std::vector<std::uint8_t>& bytes);

private:
    static constexpr std::size_t max_actions = 65535;

    MatchState seed_{};
    MatchState state_{};
    std::vector<MatchState> undo_states_;
    std::vector<Action> actions_;

    [[nodiscard]] static bool valid_seed(const MatchState& position) noexcept;
    [[nodiscard]] bool tiger_has_action() const noexcept;
    [[nodiscard]] bool goat_has_step() const noexcept;
    [[nodiscard]] bool can_capture(int from, int to, bool honor_chain) const noexcept;
    [[nodiscard]] int jumped_goat(int from, int to) const noexcept;
    void record_action(const Action& action);
    void adjudicate() noexcept;
    void finish_tiger_action() noexcept;
};

}  // namespace mocs::engine::tiger_goat
