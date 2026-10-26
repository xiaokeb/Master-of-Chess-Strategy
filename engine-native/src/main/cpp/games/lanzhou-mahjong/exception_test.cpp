// Standalone only: never link this global allocator into JNI or the application.
#include "mocs/engine/lanzhou_mahjong_bot.hpp"

#ifdef NDEBUG
#error "Compile the allocation-fault test with assertions enabled (-UNDEBUG)"
#endif

#include <algorithm>
#include <array>
#include <cstdlib>
#include <iostream>
#include <limits>
#include <new>
#include <sstream>
#include <string>
#include <utility>
#include <vector>

namespace allocation_probe {
int remaining = -1;
int attempts = 0;
}

void* operator new(std::size_t size) {
    if (allocation_probe::remaining >= 0) {
        ++allocation_probe::attempts;
        if (allocation_probe::remaining-- == 0) {
            allocation_probe::remaining = -1;
            throw std::bad_alloc{};
        }
    }
    if (void* memory = std::malloc(size == 0 ? 1 : size)) return memory;
    throw std::bad_alloc{};
}
void* operator new[](std::size_t size) { return ::operator new(size); }
void operator delete(void* memory) noexcept { std::free(memory); }
void operator delete[](void* memory) noexcept { std::free(memory); }
void operator delete(void* memory, std::size_t) noexcept { std::free(memory); }
void operator delete[](void* memory, std::size_t) noexcept { std::free(memory); }

namespace {
using namespace mocs::engine;
using Game = LanzhouMahjongGame;
using Kind = LanzhouMahjongActionKind;
using Bytes = std::vector<std::uint8_t>;
int checks = 0;
int injected_failures = 0;

void require(bool condition, const char* message) {
    ++checks;
    if (!condition) {
        allocation_probe::remaining = -1;
        std::cerr << "FAIL: " << message << '\n';
        std::exit(1);
    }
}

void accepted(Game& game, LanzhouMahjongAction action) {
    require(game.apply(action).accepted, "fixture action must actually be accepted");
}

LanzhouMahjongAction first_discard(const Game& game) {
    const auto actions = game.legal_actions(game.current_player());
    const auto found = std::find_if(actions.begin(), actions.end(),
        [](const auto& action) { return action.kind == Kind::discard; });
    require(found != actions.end(), "fixture must have an actual discard");
    return *found;
}

Game::Wall fixed_dealer_hand(const std::array<std::uint8_t, 14>& hand) {
    Game::Wall wall{};
    std::array<bool, 108> fixed{};
    std::array<unsigned, 27> remaining{};
    remaining.fill(4);
    for (std::size_t i = 0; i < hand.size(); ++i) {
        const auto position = i < 13 ? i * 4 : 52;
        require(hand[i] < remaining.size() && remaining[hand[i]] > 0, "authored hand keeps four-copy inventory");
        fixed[position] = true;
        wall[position] = hand[i];
        --remaining[hand[i]];
    }
    std::size_t next = 0;
    for (std::size_t i = 0; i < wall.size(); ++i) {
        if (fixed[i]) continue;
        while (next < remaining.size() && remaining[next] == 0) ++next;
        require(next < remaining.size(), "authored wall has enough remaining tiles");
        wall[i] = static_cast<std::uint8_t>(next);
        --remaining[next];
    }
    require(Game::valid_wall(wall), "authored fixture is a legal complete wall");
    return wall;
}

void pin_legacy_rule(Bytes& bytes) {
    require(bytes.size() == 130 && bytes[4] == 2, "legacy fixture begins at an actual zero-action new game");
    bytes[4] = 1;
    std::uint32_t sum = 0x811c9dc5U;
    for (std::size_t i = 0; i < bytes.size() - 4; ++i) sum = (sum ^ bytes[i]) * 16777619U;
    for (unsigned i = 0; i < 4; ++i) bytes[bytes.size() - 4 + i] = static_cast<std::uint8_t>(sum >> (i * 8U));
}

enum class Stage { opening_discard, pending_pass, resolving_pass, ready_discard, concealed_kong,
                   false_win, legacy_false_win, false_win_after_kong };

Game fixture(Stage stage) {
    if (stage == Stage::ready_discard) {
        return Game(fixed_dealer_hand({0,0,0,1,1,1,2,2,2,3,3,3,4,5}));
    }
    if (stage == Stage::concealed_kong || stage == Stage::false_win_after_kong) {
        Game game(fixed_dealer_hand({0,0,0,0,3,4,5,9,10,11,18,19,20,26}), 0, 2);
        if (stage == Stage::false_win_after_kong) accepted(game, {Kind::concealed_kong, 0, 0});
        return game;
    }
    Game game(Game::shuffled_wall(stage == Stage::false_win || stage == Stage::legacy_false_win ? 5 : 321));
    if (stage == Stage::legacy_false_win) {
        auto bytes = game.serialize();
        pin_legacy_rule(bytes);
        require(game.restore(bytes).restored && game.serialize() == bytes, "legacy initial record truly restores canonically");
    }
    if (stage == Stage::pending_pass || stage == Stage::resolving_pass) {
        accepted(game, first_discard(game));
        require(game.phase() == LanzhouMahjongPhase::claim, "fixture has pending responses");
        if (stage == Stage::resolving_pass) {
            accepted(game, {Kind::pass, 1});
            accepted(game, {Kind::pass, 2});
        }
    }
    return game;
}

LanzhouMahjongAction action_for(const Game& game, Stage stage) {
    switch (stage) {
        case Stage::opening_discard: return first_discard(game);
        case Stage::pending_pass: return {Kind::pass, 1};
        case Stage::resolving_pass: return {Kind::pass, 3};
        case Stage::ready_discard: return {Kind::ready_discard, 0, 5};
        case Stage::concealed_kong: return {Kind::concealed_kong, 0, 0};
        case Stage::false_win:
        case Stage::legacy_false_win:
        case Stage::false_win_after_kong: return {Kind::win, 0};
    }
    std::abort();
}

std::string public_state(const Game& game) {
    std::ostringstream out;
    out << int(game.phase()) << ',' << int(game.current_player()) << ',' << int(game.dealer())
        << ',' << int(game.rule()) << ',' << game.wall_remaining() << ','
        << (game.pending_tile() ? int(*game.pending_tile()) : -1) << ','
        << (game.pending_player() ? int(*game.pending_player()) : -1) << ';';
    const auto& result = game.outcome();
    out << (result.winner ? int(*result.winner) : -1) << ','
        << (result.payer ? int(*result.payer) : -1) << ',' << int(result.next_dealer)
        << ',' << result.false_win << ',' << result.seven_pairs << ',' << result.ready
        << ',' << result.kong_draw << ',' << result.last_tile << ';';
    for (const auto score : game.net_scores()) out << score << ',';
    for (std::uint8_t player = 0; player < 4; ++player) {
        out << ';' << game.ready(player) << ',' << game.responded(player) << ':';
        for (const auto count : game.hand(player)) out << int(count) << ',';
        out << '/';
        for (const auto& meld : game.melds(player)) out << int(meld.kind) << ',' << int(meld.tile) << ',' << int(meld.from_player) << ':';
        out << '/';
        for (const auto tile : game.discards(player)) out << int(tile) << ',';
        out << '/';
        for (const auto tile : game.waits(player)) out << int(tile) << ',';
        out << '/';
        for (const auto& action : game.legal_actions(player)) out << int(action.kind) << ',' << int(action.player) << ',' << int(action.tile) << ':';
    }
    return out.str();
}

std::vector<Bytes> undo_trace(Game game) {
    std::vector<Bytes> result;
    while (game.undo()) result.push_back(game.serialize());
    return result;
}

struct Observation { Bytes bytes; std::string view; std::vector<Bytes> undo; };

Observation observe(const Game& game) { return {game.serialize(), public_state(game), undo_trace(game)}; }

void require_same(const Game& game, const Observation& expected) {
    require(game.serialize() == expected.bytes, "failed operation preserves exact serialized action history");
    require(public_state(game) == expected.view, "failed operation preserves public hands/melds/discards/claims/scores/legal actions");
    require(undo_trace(game) == expected.undo, "failed operation preserves the entire undo stack");
    Game restored(Game::shuffled_wall(77));
    require(restored.restore(game.serialize()).restored, "failed operation leaves a legally restorable record");
    require(public_state(restored) == expected.view && undo_trace(restored) == expected.undo,
        "restored history reproduces full public state and undo behavior");
}

void every_action_allocation_failure(Stage stage) {
    auto control = fixture(stage);
    const auto action = action_for(control, stage);
    allocation_probe::attempts = 0;
    allocation_probe::remaining = std::numeric_limits<int>::max();
    const auto accepted_result = control.apply(action);
    allocation_probe::remaining = -1;
    const int allocations = allocation_probe::attempts;
    require(accepted_result.accepted && allocations > 0 && allocations < 8192,
        "control action succeeds with a measured bounded allocation count");
    if (stage == Stage::false_win) {
        require(control.outcome().false_win && control.net_scores() == LanzhouMahjongPayment{-72,24,24,24},
            "new-rule control really pays each other seat 24 times");
    } else if (stage == Stage::legacy_false_win) {
        require(control.rule() == LanzhouMahjongRule::legacy_false_win_8 && control.outcome().false_win
            && control.net_scores() == LanzhouMahjongPayment{-24,8,8,8}, "legacy control retains each-seat eight-times penalty");
    } else if (stage == Stage::false_win_after_kong) {
        require(control.outcome().false_win && control.net_scores() == LanzhouMahjongPayment{-132,44,44,44},
            "new-rule false win retains the earlier base-two concealed-kong transfers");
    }
    const auto after = observe(control);
    for (int index = 0; index < allocations; ++index) {
        auto game = fixture(stage);
        const auto before = observe(game);
        allocation_probe::attempts = 0;
        allocation_probe::remaining = index;
        bool failed = false;
        try { static_cast<void>(game.apply(action)); } catch (const std::bad_alloc&) { failed = true; }
        allocation_probe::remaining = -1;
        require(failed && allocation_probe::attempts == index + 1, "every actual allocation point throws once");
        ++injected_failures;
        require_same(game, before);
        require(game.apply(action).accepted, "same legal operation can continue after allocation failure");
        require_same(game, after);
    }
    std::cout << "PASS action stage=" << int(stage) << " allocation_points=" << allocations << '\n';
}

void undo_never_allocates() {
    auto game = fixture(Stage::resolving_pass);
    const auto trace = undo_trace(game);
    require(!trace.empty(), "undo test has nonempty histories with allocated discards");
    for (const auto& bytes : trace) {
        allocation_probe::attempts = 0;
        allocation_probe::remaining = 0;
        bool undone = false;
        bool failed = false;
        try { undone = game.undo(); } catch (const std::bad_alloc&) { failed = true; }
        allocation_probe::remaining = -1;
        require(!failed && undone && allocation_probe::attempts == 0, "undo moves state without any allocation");
        require(game.serialize() == bytes, "allocation-free undo restores exact prior bytes");
    }
    require(!game.undo(), "undo of empty history is a safe no-op");
}

void bot_failure_keeps_only_fully_committed_prefixes() {
    constexpr std::uint16_t limit = 6;
    constexpr std::uint64_t seed = 321;
    auto stepwise = fixture(Stage::pending_pass);
    std::vector<Observation> prefixes{observe(stepwise)};
    for (std::uint16_t step = 0; step < limit; ++step) {
        const auto one = LanzhouMahjongBot::advance_other_seats(stepwise, 0, Difficulty::easy, seed + step, 1);
        require(one.stop != LanzhouMahjongAutoStop::rejected && one.steps <= 1, "one-step bot fixture stays valid");
        if (one.steps == 0) break;
        prefixes.push_back(observe(stepwise));
        if (one.stop != LanzhouMahjongAutoStop::step_limit) break;
    }
    auto control = fixture(Stage::pending_pass);
    allocation_probe::attempts = 0;
    allocation_probe::remaining = std::numeric_limits<int>::max();
    const auto result = LanzhouMahjongBot::advance_other_seats(control, 0, Difficulty::easy, seed, limit);
    allocation_probe::remaining = -1;
    const int allocations = allocation_probe::attempts;
    require(result.steps > 0 && result.steps + 1U == prefixes.size() && allocations > 0 && allocations < 8192,
        "full bot call agrees with actual one-step prefix controls");
    const auto after = observe(control);
    require_same(control, prefixes.back());
    for (int index = 0; index < allocations; ++index) {
        auto game = fixture(Stage::pending_pass);
        allocation_probe::attempts = 0;
        allocation_probe::remaining = index;
        bool failed = false;
        try { static_cast<void>(LanzhouMahjongBot::advance_other_seats(game, 0, Difficulty::easy, seed, limit)); }
        catch (const std::bad_alloc&) { failed = true; }
        allocation_probe::remaining = -1;
        require(failed && allocation_probe::attempts == index + 1, "every bot allocation point throws once");
        ++injected_failures;
        const auto saved = game.serialize();
        const auto found = std::find_if(prefixes.begin(), prefixes.end(),
            [&saved](const auto& item) { return item.bytes == saved; });
        require(found != prefixes.end(), "bot failure leaves only a completely committed action prefix");
        require_same(game, *found);
        const auto committed = static_cast<std::uint16_t>(std::distance(prefixes.begin(), found));
        if (committed < result.steps) {
            const auto tail = LanzhouMahjongBot::advance_other_seats(game, 0, Difficulty::easy, seed + committed,
                static_cast<std::uint16_t>(limit - committed));
            require(tail.steps + committed == result.steps, "continued bot applies exactly the missing steps");
        }
        require_same(game, after);
    }
    std::cout << "PASS bot committed-prefix allocation_points=" << allocations << '\n';
}
}  // namespace

int main() {
    for (const auto stage : {Stage::opening_discard, Stage::pending_pass, Stage::resolving_pass,
        Stage::ready_discard, Stage::concealed_kong, Stage::false_win,
        Stage::legacy_false_win, Stage::false_win_after_kong}) {
        every_action_allocation_failure(stage);
    }
    undo_never_allocates();
    bot_failure_keeps_only_fully_committed_prefixes();
    std::cout << "Lanzhou Mahjong exception safety: " << injected_failures
        << " injected allocation failures, " << checks << " checks passed.\n";
}
