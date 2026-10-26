#include "mocs/engine/lanzhou_mahjong_game.hpp"

#include <array>
#include <cstdint>
#include <cstdlib>
#include <vector>

using namespace mocs::engine;

namespace {

void require(bool value) {
    if (!value) {
        std::abort();
    }
}

bool has_action(const LanzhouMahjongGame& game, std::uint8_t player,
                LanzhouMahjongActionKind kind, std::uint8_t tile = 255) {
    for (const auto& action : game.legal_actions(player)) {
        if (action.kind == kind && action.tile == tile) {
            return true;
        }
    }
    return false;
}

void apply(LanzhouMahjongGame& game, LanzhouMahjongActionKind kind,
           std::uint8_t player, std::uint8_t tile = 255) {
    require(game.apply({kind, player, tile}).accepted);
}

void pass_others(LanzhouMahjongGame& game) {
    const auto source = *game.pending_player();
    for (std::uint8_t distance = 1; distance < 4; ++distance) {
        const auto player = static_cast<std::uint8_t>((source + distance) % 4);
        if (!game.responded(player)) {
            apply(game, LanzhouMahjongActionKind::pass, player);
        }
    }
}

// Fix selected logical wall positions, then fill the remaining four-copy inventory.
LanzhouMahjongGame::Wall wall_with_fixes(
    const std::vector<std::pair<std::size_t, std::uint8_t>>& fixes
) {
    LanzhouMahjongGame::Wall wall{};
    std::array<bool, 108> fixed{};
    std::array<std::uint8_t, 27> remaining{};
    remaining.fill(4);
    for (const auto& [position, tile] : fixes) {
        require(position < wall.size() && tile < 27 && !fixed[position]);
        require(remaining[tile] > 0);
        fixed[position] = true;
        wall[position] = tile;
        --remaining[tile];
    }
    std::size_t next = 0;
    for (std::size_t position = 0; position < wall.size(); ++position) {
        if (fixed[position]) {
            continue;
        }
        while (remaining[next] == 0) {
            ++next;
        }
        wall[position] = static_cast<std::uint8_t>(next);
        --remaining[next];
    }
    require(LanzhouMahjongGame::valid_wall(wall));
    return wall;
}

std::vector<std::pair<std::size_t, std::uint8_t>> dealer_hand_fixes(
    const std::array<std::uint8_t, 14>& tiles
) {
    std::vector<std::pair<std::size_t, std::uint8_t>> result;
    for (std::size_t index = 0; index < 13; ++index) {
        result.emplace_back(index * 4, tiles[index]);
    }
    result.emplace_back(52, tiles[13]);
    return result;
}

void test_draw_discard_and_exhaustion() {
    LanzhouMahjongGame game(LanzhouMahjongGame::shuffled_wall(39));
    require(game.phase() == LanzhouMahjongPhase::discard);
    require(game.wall_remaining() == 55);
    require(!game.apply({LanzhouMahjongActionKind::draw, 1}).accepted);
    require(!game.undo());
    std::size_t actions = 0;
    while (game.phase() != LanzhouMahjongPhase::exhaustive_draw) {
        if (game.phase() == LanzhouMahjongPhase::discard) {
            const auto player = game.current_player();
            const auto legal = game.legal_actions(player);
            bool discarded = false;
            for (const auto& action : legal) {
                if (action.kind == LanzhouMahjongActionKind::discard) {
                    require(game.apply(action).accepted);
                    discarded = true;
                    break;
                }
            }
            require(discarded);
        } else if (game.phase() == LanzhouMahjongPhase::claim) {
            pass_others(game);
        } else {
            require(game.phase() == LanzhouMahjongPhase::draw);
            apply(game, LanzhouMahjongActionKind::draw, game.current_player());
        }
        require(++actions < 200);
    }
    require(game.wall_remaining() == 0);
    require(game.outcome().next_dealer == 1);
    require(game.net_scores() == LanzhouMahjongPayment{});
    require(game.legal_actions(0).empty());
}

void test_kongs_and_claim_priority() {
    auto wall = wall_with_fixes({{0, 0}, {1, 0}, {5, 0}, {9, 0}});
    LanzhouMahjongGame game(wall);
    apply(game, LanzhouMahjongActionKind::discard, 0, 0);
    require(!has_action(game, 2, LanzhouMahjongActionKind::pung));
    require(has_action(game, 1, LanzhouMahjongActionKind::exposed_kong));
    apply(game, LanzhouMahjongActionKind::exposed_kong, 1);
    apply(game, LanzhouMahjongActionKind::pass, 3);
    apply(game, LanzhouMahjongActionKind::pass, 2);
    require(game.current_player() == 1);
    require(game.phase() == LanzhouMahjongPhase::discard);
    require(game.melds(1).size() == 1);
    require(game.melds(1).front().kind == LanzhouMahjongMeldKind::exposed_kong);
    require(game.net_scores() == LanzhouMahjongPayment{-1, 3, -1, -1});
    require(game.wall_remaining() == 54);
    require(game.discards(0).empty());

    wall = wall_with_fixes({{0, 0}, {4, 0}, {8, 0}, {12, 0}});
    LanzhouMahjongGame concealed(wall);
    require(has_action(concealed, 0, LanzhouMahjongActionKind::concealed_kong, 0));
    apply(concealed, LanzhouMahjongActionKind::concealed_kong, 0, 0);
    require(concealed.net_scores() == LanzhouMahjongPayment{6, -2, -2, -2});
    require(concealed.melds(0).front().kind == LanzhouMahjongMeldKind::concealed_kong);

    LanzhouMahjongGame added(wall_with_fixes({{0, 0}, {1, 0}, {5, 0}, {9, 0}}));
    apply(added, LanzhouMahjongActionKind::discard, 0, 0);
    apply(added, LanzhouMahjongActionKind::pung, 1);
    pass_others(added);
    require(has_action(added, 1, LanzhouMahjongActionKind::added_kong, 0));
    apply(added, LanzhouMahjongActionKind::added_kong, 1, 0);
    require(added.phase() == LanzhouMahjongPhase::rob_kong);
    pass_others(added);
    require(added.melds(1).front().kind == LanzhouMahjongMeldKind::exposed_kong);
    require(added.net_scores() == LanzhouMahjongPayment{-1, 3, -1, -1});
}

void test_win_ready_and_false_win() {
    const std::array<std::uint8_t, 14> complete{
        0, 0, 0, 1, 1, 1, 2, 2, 2, 3, 3, 3, 4, 4
    };
    LanzhouMahjongGame winner(wall_with_fixes(dealer_hand_fixes(complete)));
    require(has_action(winner, 0, LanzhouMahjongActionKind::win));
    apply(winner, LanzhouMahjongActionKind::win, 0);
    require(winner.phase() == LanzhouMahjongPhase::won);
    require(winner.outcome().winner == 0);
    require(winner.net_scores() == LanzhouMahjongPayment{6, -2, -2, -2});
    require(winner.undo());
    require(winner.phase() == LanzhouMahjongPhase::discard);

    const std::array<std::uint8_t, 14> seven_pairs{
        0, 0, 2, 2, 4, 4, 6, 6, 8, 8, 9, 9, 11, 11
    };
    LanzhouMahjongGame seven_pair_game(
        wall_with_fixes(dealer_hand_fixes(seven_pairs))
    );
    require(has_action(seven_pair_game, 0, LanzhouMahjongActionKind::win));
    apply(seven_pair_game, LanzhouMahjongActionKind::win, 0);
    require(seven_pair_game.outcome().seven_pairs);
    require(seven_pair_game.net_scores() ==
            LanzhouMahjongPayment{12, -4, -4, -4});

    LanzhouMahjongGame false_win(LanzhouMahjongGame::shuffled_wall(5));
    apply(false_win, LanzhouMahjongActionKind::win, 0);
    require(false_win.outcome().false_win);
    require(false_win.net_scores() == LanzhouMahjongPayment{-72, 24, 24, 24});

    const std::array<std::uint8_t, 14> ready_hand{
        0, 0, 0, 1, 1, 1, 2, 2, 2, 3, 3, 3, 4, 5
    };
    auto fixes = dealer_hand_fixes(ready_hand);
    fixes.emplace_back(56, 4);
    LanzhouMahjongGame ready_game(wall_with_fixes(fixes));
    require(has_action(ready_game, 0, LanzhouMahjongActionKind::ready_discard, 5));
    apply(ready_game, LanzhouMahjongActionKind::ready_discard, 0, 5);
    require(ready_game.ready(0));
    pass_others(ready_game);
    for (std::uint8_t player = 1; player < 4; ++player) {
        apply(ready_game, LanzhouMahjongActionKind::draw, player);
        bool discarded = false;
        for (const auto& action : ready_game.legal_actions(player)) {
            if (action.kind == LanzhouMahjongActionKind::discard) {
                require(ready_game.apply(action).accepted);
                discarded = true;
                break;
            }
        }
        require(discarded);
        pass_others(ready_game);
    }
    apply(ready_game, LanzhouMahjongActionKind::draw, 0);
    require(has_action(ready_game, 0, LanzhouMahjongActionKind::win));
    require(ready_game.net_scores() == LanzhouMahjongPayment{});
    apply(ready_game, LanzhouMahjongActionKind::win, 0);
    require(ready_game.outcome().ready);
    require(ready_game.net_scores() == LanzhouMahjongPayment{12, -4, -4, -4});
}

void pin_rule(std::vector<std::uint8_t>& bytes, std::uint8_t version) {
    bytes[4] = version;
    std::uint32_t sum = 2166136261U;
    for (std::size_t i = 0; i < bytes.size() - 4; ++i) {
        sum = (sum ^ bytes[i]) * 16777619U;
    }
    for (std::size_t i = 0; i < 4; ++i) {
        bytes[bytes.size() - 4 + i] = static_cast<std::uint8_t>(sum >> (i * 8));
    }
}

void test_rule_identity_preserves_legacy_replay() {
    // Fixed, non-winning three-suit hand; a concealed kong also proves past
    // transfers are retained, not multiplied by the false-win penalty.
    const std::array<std::uint8_t, 14> hand{
        0, 0, 0, 0, 3, 4, 5, 9, 10, 11, 18, 19, 20, 26,
    };
    auto fixes = dealer_hand_fixes(hand);
    fixes.emplace_back(107, 25);
    const auto wall = wall_with_fixes(fixes);
    for (std::uint8_t version = 1; version <= 2; ++version) {
        LanzhouMahjongGame game(wall, 0, 2);
        auto initial = game.serialize();
        require(initial[4] == 2);  // New rounds always use the approved rule.
        pin_rule(initial, version);  // Valid old envelope, including its checksum.
        require(game.restore(initial).restored);
        require(static_cast<std::uint8_t>(game.rule()) == version);
        require(game.serialize() == initial);
        apply(game, LanzhouMahjongActionKind::concealed_kong, 0, 0);
        const auto active = game.serialize();
        const auto kong_scores = game.net_scores();
        require(kong_scores == LanzhouMahjongPayment{12, -4, -4, -4});
        LanzhouMahjongGame continued(wall);
        require(continued.restore(active).restored);
        require(continued.serialize() == active);
        require(!has_action(continued, 0, LanzhouMahjongActionKind::win));
        apply(continued, LanzhouMahjongActionKind::win, 0);
        const auto each = version == 1 ? 16 : 48;
        require(continued.net_scores() == LanzhouMahjongPayment{
            12 - each * 3, each - 4, each - 4, each - 4});
        require(continued.outcome().false_win);
        const auto terminal = continued.serialize();
        LanzhouMahjongGame reopened(wall);
        require(reopened.restore(terminal).restored);
        require(reopened.serialize() == terminal);
        require(reopened.net_scores() == continued.net_scores());
        require(reopened.undo());
        require(reopened.serialize() == active);
        require(reopened.net_scores() == kong_scores);
        apply(reopened, LanzhouMahjongActionKind::win, 0);
        require(reopened.serialize() == terminal);
        for (const auto unknown : {std::uint8_t{0}, std::uint8_t{3}, std::uint8_t{255}}) {
            auto invalid = terminal;
            pin_rule(invalid, unknown);
            require(reopened.restore(invalid).error == EngineError::unsupported);
            require(reopened.serialize() == terminal);
        }
    }
}

void test_restore_and_corruption() {
    LanzhouMahjongGame original(LanzhouMahjongGame::shuffled_wall(102));
    const auto tile = original.legal_actions(0).front().tile;
    apply(original, LanzhouMahjongActionKind::discard, 0, tile);
    pass_others(original);
    apply(original, LanzhouMahjongActionKind::draw, 1);
    const auto saved = original.serialize();

    LanzhouMahjongGame recovered(LanzhouMahjongGame::shuffled_wall(2));
    require(recovered.restore(saved).restored);
    require(recovered.serialize() == saved);
    require(recovered.phase() == original.phase());
    require(recovered.hand(1) == original.hand(1));
    require(recovered.undo());
    require(recovered.phase() == LanzhouMahjongPhase::draw);

    auto corrupted = saved;
    corrupted[14] = 27;
    const auto before = recovered.serialize();
    require(!recovered.restore(corrupted).restored);
    require(recovered.serialize() == before);
    corrupted = saved;
    corrupted[4] = 9;
    require(recovered.restore(corrupted).error == EngineError::unsupported);
    require(recovered.serialize() == before);
}

void test_waits_do_not_require_a_fifth_owned_tile() {
    const std::array<std::uint8_t, 14> hand{
        0, 0, 0, 0, 3, 3, 3, 3, 1, 2, 9, 10, 11, 8
    };
    auto fixes = dealer_hand_fixes(hand);
    fixes.emplace_back(107, 8);
    fixes.emplace_back(106, 18);
    LanzhouMahjongGame game(wall_with_fixes(fixes));
    apply(game, LanzhouMahjongActionKind::concealed_kong, 0, 0);
    // 1,2 could form 0,1,2, but all four 0 tiles are already in our kong.
    // A 3 or 8 can still complete a different split; both must remain valid waits.
    require(game.waits_after_discard(0, 3) == std::vector<std::uint8_t>({3, 8}));
    require(has_action(game, 0, LanzhouMahjongActionKind::ready_discard, 3));

    apply(game, LanzhouMahjongActionKind::concealed_kong, 0, 3);
    require(game.waits_after_discard(0, 18).empty());
    require(!has_action(game, 0, LanzhouMahjongActionKind::ready_discard, 18));
    const auto before = game.serialize();
    require(!game.apply({LanzhouMahjongActionKind::ready_discard, 0, 18}).accepted);
    require(game.serialize() == before);
    // Simulate a checksummed v1 record written when this impossible ready was allowed.
    auto legacy = before;
    legacy.resize(legacy.size() + 3);
    const auto action_offset = before.size() - 4;
    legacy[action_offset] = static_cast<std::uint8_t>(LanzhouMahjongActionKind::ready_discard);
    legacy[action_offset + 1] = 0;
    legacy[action_offset + 2] = 18;
    legacy[122] = 3;  // The two kongs above, followed by the impossible ready discard.
    std::uint32_t checksum = 0x811c9dc5U;
    for (std::size_t index = 0; index < legacy.size() - 4; ++index) {
        checksum = (checksum ^ legacy[index]) * 16777619U;
    }
    for (std::size_t byte = 0; byte < 4; ++byte) {
        legacy[legacy.size() - 4 + byte] =
            static_cast<std::uint8_t>(checksum >> (byte * 8));
    }
    require(!game.restore(legacy).restored);
    require(game.serialize() == before);
    apply(game, LanzhouMahjongActionKind::discard, 0, 18);
    pass_others(game);
    require(game.waits(0).empty());

    const auto saved = game.serialize();
    LanzhouMahjongGame restored(LanzhouMahjongGame::shuffled_wall(32));
    require(restored.restore(saved).restored);
    require(restored.serialize() == saved);
    require(restored.waits(0).empty());
    require(game.undo());
    require(game.undo());
    require(game.undo());
    require(game.undo());
    require(game.serialize() == before);
    require(!has_action(game, 0, LanzhouMahjongActionKind::ready_discard, 18));
}

}  // namespace

int main() {
    test_rule_identity_preserves_legacy_replay();
    test_draw_discard_and_exhaustion();
    test_kongs_and_claim_priority();
    test_win_ready_and_false_win();
    test_restore_and_corruption();
    test_waits_do_not_require_a_fifth_owned_tile();
    return 0;
}
