#include "go_katago_position.hpp"

#include <algorithm>
#include <iterator>
#include <stdexcept>
#include <vector>

namespace mocs::engine::go_ai {
namespace {
Player kata_player(const std::uint8_t player) {
    if (player > 1) throw std::runtime_error("Invalid Go player");
    return player == 0 ? P_BLACK : P_WHITE;
}

Color kata_stone(const GoStone stone) {
    switch (stone) {
        case GoStone::empty: return C_EMPTY;
        case GoStone::black: return P_BLACK;
        case GoStone::white: return P_WHITE;
    }
    throw std::runtime_error("Invalid Go stone");
}
}  // namespace

KataGoPosition::KataGoPosition(const GoEngine& source)
    : board(source.board_size(), source.board_size()), history(), player(kata_player(source.current_player())) {
    if (source.phase() != GoPhase::playing) throw std::runtime_error("Go agreement is not an AI turn");
    const auto original = source.serialize();
    GoEngine replay(source.board_size(), source.black_komi_quarters(), source.handicap_stones());
    for (int y = 0; y < source.board_size(); ++y) for (int x = 0; x < source.board_size(); ++x) {
        const auto stone = replay.stone_at(x, y).value();
        if (stone != GoStone::empty && !board.setStone(
                Location::getLoc(x, y, board.x_size), kata_stone(stone))) {
            throw std::runtime_error("Could not reproduce Go fixed setup");
        }
    }
    const Rules rules(Rules::KO_POSITIONAL, Rules::SCORING_AREA, Rules::TAX_NONE,
        false, false, Rules::WHB_ZERO, false,
        source.black_komi_quarters() / 2.0f + source.handicap_stones());
    history = BoardHistory(board, kata_player(replay.current_player()), rules, 0, BoardHistoryModes{});
    for (auto action : source.event_history()) {
        const Player actor = kata_player(replay.current_player());
        if (action.kind == go_confirm_score_action_kind) {
            // Undo can leave a monotonic clock above the clock reconstructed from accepted events.
            action = make_go_confirm_score(static_cast<std::uint8_t>(action.arguments[0]),
                                           replay.scoring_state().proposal_revision);
        }
        if (!replay.apply(action).accepted) throw std::runtime_error("Could not reproduce accepted Go event");
        if (action.kind == go_place_action_kind || action.kind == go_pass_action_kind) {
            const Loc loc = action.kind == go_pass_action_kind ? Board::PASS_LOC :
                Location::getLoc(action.arguments[0], action.arguments[1], board.x_size);
            if (!history.isLegal(board, loc, actor)) throw std::runtime_error("KataGo history rejects accepted Go move");
            history.makeBoardMoveAssumeLegal(board, loc, actor, nullptr);
        } else if (action.kind == go_resume_play_action_kind) {
            resume_without_erasing_ko(kata_player(replay.current_player()));
        }
    }
    require_native_agreement(source);
    if (source.serialize() != original) throw std::runtime_error("AI conversion mutated Go session");
}

void KataGoPosition::resume_without_erasing_ko(const Player first_player) {
    // Pinned KataGo v1.18.1 exposes these fields. Keep moves/recent boards/ko hashes intact.
    history.presumedNextMovePla = first_player;
    history.consecutiveEndingPasses = 0;
    history.isGameFinished = false;
    history.isPastNormalPhaseEnd = false;
    history.isScored = false;
    history.isNoResult = false;
    history.isResignation = false;
    history.winner = C_EMPTY;
    history.finalWhiteMinusBlackScore = 0.0f;
    history.numApproxValidTurnsThisPhase = std::min(history.numApproxValidTurnsThisPhase, 1);
    history.numConsecValidTurnsThisGame = std::min(history.numConsecValidTurnsThisGame, 1);
    if (history.rules.koRule != Rules::KO_POSITIONAL || history.encorePhase != 0)
        throw std::runtime_error("Unsupported Go ko continuation");
    std::fill(std::begin(history.superKoBanned), std::end(history.superKoBanned), false);
    // A changed first player needs a new ban mask. Positional/non-encore hashes are pos_hash.
    for (int y = 0; y < board.y_size; ++y) for (int x = 0; x < board.x_size; ++x) {
        const Loc loc = Location::getLoc(x, y, board.x_size);
        if (board.isLegalIgnoringKo(loc, first_player, false)) {
            const Hash128 after = board.getPosHashAfterMove(loc, first_player);
            history.superKoBanned[loc] = history.numberOfKoHashOccurrencesInHistory(after, nullptr) > 0;
        }
    }
}

void KataGoPosition::require_native_agreement(const GoEngine& source) const {
    const float expected_komi = source.black_komi_quarters() / 2.0f + source.handicap_stones();
    if (source.phase() != GoPhase::playing || player != kata_player(source.current_player()) ||
        history.presumedNextMovePla != player || history.isGameFinished ||
        board.x_size != source.board_size() || board.y_size != source.board_size() ||
        history.rules.koRule != Rules::KO_POSITIONAL || history.rules.scoringRule != Rules::SCORING_AREA ||
        history.rules.taxRule != Rules::TAX_NONE || history.rules.multiStoneSuicideLegal ||
        history.rules.hasButton || history.rules.friendlyPassOk ||
        history.rules.whiteHandicapBonusRule != Rules::WHB_ZERO || history.encorePhase != 0 ||
        history.rules.komi != expected_komi || history.whiteBonusScore != 0.0f ||
        history.whiteHandicapBonusScore != 0.0f) {
        throw std::runtime_error("Go AI position metadata mismatch");
    }
    const int size = source.board_size();
    std::vector<bool> legal(size * size + 1, false);
    for (const auto& action : source.legal_actions()) {
        if (action.kind == go_pass_action_kind) legal.back() = true;
        else if (action.kind == go_place_action_kind) legal[action.arguments[1] * size + action.arguments[0]] = true;
        else throw std::runtime_error("Agreement action leaked into AI turn");
    }
    for (int y = 0; y < size; ++y) for (int x = 0; x < size; ++x) {
        const Loc loc = Location::getLoc(x, y, size);
        if (board.colors[loc] != kata_stone(source.stone_at(x, y).value()) ||
            history.isLegal(board, loc, player) != legal[y * size + x]) {
            throw std::runtime_error("Go AI board or root legality mismatch");
        }
    }
    if (history.isLegal(board, Board::PASS_LOC, player) != legal.back())
        throw std::runtime_error("Go AI pass legality mismatch");
}

}  // namespace mocs::engine::go_ai
