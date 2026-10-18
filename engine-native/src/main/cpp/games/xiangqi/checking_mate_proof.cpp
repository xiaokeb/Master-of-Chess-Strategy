#include "mocs/engine/chinese_chess.hpp"

namespace mocs::engine {

CheckingMateProof ChineseChessEngine::checking_mate_proof(
    const Side attacker, const unsigned checking_move_limit, const std::size_t node_limit
) const noexcept {
    return prove_checking_mate(board_, attacker, checking_move_limit, node_limit);
}

CheckingMateProof ChineseChessEngine::prove_checking_mate(
    const Board& board, const Side attacker, const unsigned checking_move_limit,
    const std::size_t node_limit
) noexcept {
    // Eight checking moves require at most seventeen live board frames.
    if ((attacker != Side::red && attacker != Side::black) ||
        checking_move_limit > 8 || node_limit == 0 ||
        !has_general_on_board(board, attacker) ||
        !has_general_on_board(board, opposite(attacker))) {
        return {CheckingMateProofStatus::inconclusive, 0};
    }
    CheckingMateContext context{};
    context.node_limit = node_limit;
    auto status = CheckingMateProofStatus::inconclusive;
    // Short certificates get priority over long inconclusive checking lines.
    // The node budget is shared across iterative depths, not reset per pass.
    for (unsigned depth = checking_move_limit == 0 ? 0 : 1;
         depth <= checking_move_limit; ++depth) {
        status = search_checking_mate(board, attacker, attacker, depth, 0, context);
        if (status != CheckingMateProofStatus::inconclusive ||
            context.visited_nodes == context.node_limit) break;
    }
    return {status, context.visited_nodes};
}

CheckingMateProofStatus ChineseChessEngine::search_checking_mate(
    const Board& board, const Side attacker, const Side turn,
    const unsigned checks_left, const std::size_t ply, CheckingMateContext& context
) noexcept {
    using Status = CheckingMateProofStatus;
    if (context.visited_nodes == context.node_limit) return Status::inconclusive;
    ++context.visited_nodes;
    if (!has_general_on_board(board, attacker)) return Status::refuted;
    if (!has_general_on_board(board, opposite(attacker))) return Status::proven;
    for (std::size_t i = 0; i < ply; ++i) {
        if (context.path_sides[i] == turn && *context.path_boards[i] == board) {
            // A checking loop is not a finite mate strategy. Do not cache this
            // path-dependent refutation as a result for other search branches.
            return Status::refuted;
        }
    }
    if (ply == context.path_boards.size()) return Status::inconclusive;
    context.path_boards[ply] = &board;
    context.path_sides[ply] = turn;
    const bool attacking = turn == attacker;
    bool found = false;
    bool unknown = false;
    for (std::int32_t fy = 0; fy < board_height; ++fy) {
        for (std::int32_t fx = 0; fx < board_width; ++fx) {
            const auto moving = board[index(fx, fy)];
            if (!moving || moving->side != turn) continue;
            for (std::int32_t ty = 0; ty < board_height; ++ty) {
                for (std::int32_t tx = 0; tx < board_width; ++tx) {
                    if (!is_legal_move_on_board(board, turn, fx, fy, tx, ty)) continue;
                    auto next = board;
                    next[index(tx, ty)] = moving;
                    next[index(fx, fy)].reset();
                    if (attacking && !is_in_check(next, opposite(attacker))) continue;
                    found = true;
                    if (attacking && checks_left == 0) {
                        // A still-legal check at the horizon could lead to a
                        // deeper mate. No bounded negative may become refuted.
                        unknown = true;
                        continue;
                    }
                    const auto child = search_checking_mate(
                        next, attacker, opposite(turn),
                        checks_left - (attacking ? 1U : 0U), ply + 1, context);
                    if (attacking && child == Status::proven) return Status::proven;
                    if (!attacking && child == Status::refuted) return Status::refuted;
                    if (child == Status::inconclusive &&
                        context.visited_nodes == context.node_limit) return Status::inconclusive;
                    unknown = unknown || child == Status::inconclusive;
                }
            }
        }
    }
    if (!found) {
        // Rule 24.2 is checkmate, not a quiet stalemate win.
        return !attacking && is_in_check(board, turn) ? Status::proven : Status::refuted;
    }
    if (unknown) return Status::inconclusive;
    return attacking ? Status::refuted : Status::proven;
}

} // namespace mocs::engine
