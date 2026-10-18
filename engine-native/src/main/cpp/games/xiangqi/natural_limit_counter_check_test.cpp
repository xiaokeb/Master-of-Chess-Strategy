#include "mocs/engine/chinese_chess.hpp"

#include <array>
#include <cstdint>
#include <cstdio>
#include <stdexcept>
#include <vector>

namespace {
using mocs::engine::ChineseChessEngine;
using mocs::engine::GameResult;
using mocs::engine::NaturalLimitClaimResult;
using mocs::engine::Side;
using mocs::engine::make_board_move;

void require(bool condition, const char* message) {
    if (!condition) throw std::runtime_error(message);
}

// Each row contains one red move followed by one black move. Neither the
// check counters nor the recorded history are injected into the engine.
constexpr std::array<std::array<int, 8>, 35> checking_rounds{{
    {{0, 6, 4, 6, 4, 0, 3, 0}}, {{4, 6, 3, 6, 3, 0, 4, 0}},
    {{3, 6, 3, 7, 6, 0, 7, 0}}, {{3, 7, 4, 7, 4, 0, 3, 0}},
    {{4, 7, 3, 7, 3, 0, 4, 0}}, {{3, 7, 3, 8, 7, 0, 8, 0}},
    {{3, 8, 4, 8, 4, 0, 3, 0}}, {{4, 8, 3, 8, 3, 0, 4, 0}},
    {{3, 8, 3, 9, 8, 0, 8, 1}}, {{3, 9, 4, 9, 4, 0, 3, 0}},
    {{4, 9, 3, 9, 3, 0, 4, 0}}, {{3, 9, 3, 5, 8, 1, 8, 2}},
    {{3, 5, 4, 5, 4, 0, 3, 0}}, {{4, 5, 3, 5, 3, 0, 4, 0}},
    {{3, 5, 4, 5, 4, 0, 3, 0}}, {{4, 5, 0, 5, 3, 0, 4, 0}},
    {{0, 5, 0, 6, 4, 0, 5, 0}}, {{5, 9, 4, 9, 8, 2, 4, 2}},
    {{4, 9, 3, 9, 4, 2, 3, 2}}, {{3, 9, 4, 9, 3, 2, 3, 1}},
    {{0, 6, 1, 6, 3, 1, 4, 1}}, {{4, 9, 3, 9, 4, 1, 3, 1}},
    {{3, 9, 4, 9, 3, 1, 3, 0}}, {{1, 6, 2, 6, 3, 0, 4, 0}},
    {{4, 9, 3, 9, 4, 0, 3, 0}}, {{3, 9, 4, 9, 3, 0, 3, 3}},
    {{2, 6, 2, 7, 3, 3, 4, 3}}, {{4, 9, 3, 9, 4, 3, 3, 3}},
    {{3, 9, 4, 9, 3, 3, 3, 4}}, {{2, 7, 1, 7, 3, 4, 4, 4}},
    {{4, 9, 3, 9, 4, 4, 3, 4}}, {{3, 9, 4, 9, 3, 4, 4, 4}},
    {{4, 9, 5, 9, 4, 4, 4, 2}}, {{1, 7, 0, 7, 4, 2, 8, 2}},
    {{0, 7, 0, 6, 8, 2, 8, 3}},
}};
constexpr std::array<std::array<int, 2>, 12> red_path{{
    {{0, 6}}, {{1, 6}}, {{2, 6}}, {{3, 6}}, {{3, 7}}, {{3, 8}},
    {{3, 9}}, {{2, 9}}, {{1, 9}}, {{0, 9}}, {{0, 8}}, {{0, 7}},
}};
constexpr std::array<std::array<int, 2>, 10> black_path{{
    {{6, 0}}, {{7, 0}}, {{8, 0}}, {{8, 1}}, {{8, 2}},
    {{8, 3}}, {{7, 3}}, {{6, 3}}, {{6, 2}}, {{6, 1}},
}};

ChineseChessEngine initial(bool rotated, bool counter_check) {
    // Legacy MOCX v2 is only used for a legal initial board, with zero history.
    std::vector<std::uint8_t> bytes(12 + 90, 0);
    bytes[0] = 'M'; bytes[1] = 'O'; bytes[2] = 'C'; bytes[3] = 'X'; bytes[4] = 2;
    bytes[6] = rotated ? 1 : 0;
    const auto place = [&](int x, int y, std::uint8_t type, bool black) {
        if (rotated) { x = 8 - x; y = 9 - y; black = !black; }
        bytes[static_cast<std::size_t>(12 + y * 9 + x)] =
            static_cast<std::uint8_t>(type | (black ? 0x80U : 0U));
    };
    place(5, 9, 1, false);
    place(4, 0, 1, true);
    place(0, 6, 5, false);
    if (counter_check) {
        place(5, 2, 6, true); // An un-screened cannon does not check initially.
        place(5, 0, 5, true);
    } else {
        place(5, 4, 7, false);
        place(6, 0, 5, true);
    }
    std::uint32_t crc = 0xffffffffU;
    for (auto byte : bytes) {
        crc ^= byte;
        for (int bit = 0; bit < 8; ++bit) {
            crc = (crc >> 1U) ^ ((crc & 1U) ? 0xedb88320U : 0U);
        }
    }
    crc = ~crc;
    for (unsigned shift = 0; shift < 32; shift += 8) {
        bytes.push_back(static_cast<std::uint8_t>(crc >> shift));
    }
    ChineseChessEngine engine;
    require(engine.restore(bytes).restored, "initial board rejected");
    return engine;
}

void play(ChineseChessEngine& engine, bool rotated, int fx, int fy, int tx, int ty) {
    if (rotated) { fx = 8 - fx; fy = 9 - fy; tx = 8 - tx; ty = 9 - ty; }
    const auto result = engine.apply(make_board_move(fx, fy, tx, ty));
    if (!result.accepted) {
        std::fprintf(stderr, "rejected fixture move %d,%d -> %d,%d (error=%d)\n",
                     fx, fy, tx, ty, static_cast<int>(result.error));
    }
    require(result.accepted, "fixture move rejected");
    require(engine.game_result() == GameResult::ongoing, "fixture terminated early");
}

void both_sides_exceed_ten_checks(bool rotated) {
    auto engine = initial(rotated, false);
    const auto first = rotated ? Side::black : Side::red;
    const auto second = rotated ? Side::red : Side::black;
    for (const auto& row : checking_rounds) {
        play(engine, rotated, row[0], row[1], row[2], row[3]);
        play(engine, rotated, row[4], row[5], row[6], row[7]);
    }
    for (const auto side : {first, second}) {
        const auto review = engine.natural_limit_review(side);
        require(review.claimant_checks == 11 && review.no_capture_plies == 70 &&
                review.effective_plies == 68 && review.complete_record, "70-ply check count differs");
    }
    constexpr std::size_t quiet_rounds = 25;
    for (std::size_t round = 0; round < quiet_rounds; ++round) {
        const auto& from = red_path[round % red_path.size()];
        const auto& to = red_path[(round + 1) % red_path.size()];
        play(engine, rotated, from[0], from[1], to[0], to[1]);
        const auto& reply_from = black_path[(5 + round) % black_path.size()];
        const auto& reply_to = black_path[(6 + round) % black_path.size()];
        play(engine, rotated, reply_from[0], reply_from[1], reply_to[0], reply_to[1]);
    }
    for (const auto side : {first, second}) {
        const auto review = engine.natural_limit_review(side);
        require(review.recorded_plies == 120 && review.claimant_checks == 11 &&
                review.effective_plies == 118 && !review.eligible, "both-check deduction differs");
    }
    require(engine.claim_natural_limit() == NaturalLimitClaimResult::first_false_claim,
            "first claimant request differs");
    ChineseChessEngine restored;
    const auto saved = engine.serialize();
    require(restored.restore(saved).restored && restored.serialize() == saved, "restore differs");
    const auto& from = red_path[quiet_rounds % red_path.size()];
    const auto& to = red_path[(quiet_rounds + 1) % red_path.size()];
    play(restored, rotated, from[0], from[1], to[0], to[1]);
    require(restored.natural_limit_review(second).effective_plies == 119, "121-ply review differs");
    require(restored.claim_natural_limit() == NaturalLimitClaimResult::first_false_claim,
            "second claimant inherited a false request");
    require(engine.restore(restored.serialize()).restored, "second request restore failed");
    const auto& reply_from = black_path[(5 + quiet_rounds) % black_path.size()];
    const auto& reply_to = black_path[(6 + quiet_rounds) % black_path.size()];
    play(engine, rotated, reply_from[0], reply_from[1], reply_to[0], reply_to[1]);
    for (const auto side : {first, second}) {
        const auto review = engine.natural_limit_review(side);
        require(review.no_capture_plies == 122 && review.effective_plies == 120 &&
                review.eligible, "122-ply review differs");
    }
    require(engine.claim_natural_limit() == NaturalLimitClaimResult::draw, "eligible request failed");
    require(engine.game_result() == GameResult::draw, "eligible request did not draw");
    std::printf("both-checks rotated=%d checks=11/11 raw=122 effective=120/120 passed\n", rotated);
}

void responding_with_a_counter_check_counts_for_its_mover(bool rotated) {
    auto engine = initial(rotated, true);
    const auto first = rotated ? Side::black : Side::red;
    const auto second = rotated ? Side::red : Side::black;
    play(engine, rotated, 0, 6, 4, 6);
    require(engine.is_in_check(second), "first move must check");
    // Interpose on file 4, uncovering the other rook's check on file 5.
    play(engine, rotated, 5, 2, 4, 2);
    require(!engine.is_in_check(second) && engine.is_in_check(first), "counter-check differs");
    for (const auto side : {first, second}) {
        const auto review = engine.natural_limit_review(side);
        require(review.claimant_checks == 1 && review.recorded_plies == 2 &&
                review.effective_plies == 2, "counter-check ownership differs");
    }
    ChineseChessEngine restored;
    require(restored.restore(engine.serialize()).restored, "counter-check restore failed");
    require(restored.natural_limit_review(second).claimant_checks == 1, "restored counter-check lost");
    require(restored.undo(), "counter-check undo failed");
    require(restored.natural_limit_review(first).claimant_checks == 1 &&
            restored.natural_limit_review(second).claimant_checks == 0, "counter-check undo differs");
    play(restored, rotated, 5, 2, 4, 2);
    require(restored.natural_limit_review(second).claimant_checks == 1, "counter-check replay differs");
    std::printf("counter-check rotated=%d checks=1/1 restore-undo-replay passed\n", rotated);
}
} // namespace

int main() {
    try {
        for (const bool rotated : {false, true}) {
            both_sides_exceed_ten_checks(rotated);
            responding_with_a_counter_check_counts_for_its_mover(rotated);
        }
    } catch (const std::exception& error) {
        std::fprintf(stderr, "natural-limit counter-check failed: %s\n", error.what());
        return 1;
    }
}
