package com.masterofchessstrategy.games.lanzhou_mahjong.settlement

import java.io.IOException
import java.math.BigInteger

internal data class LanzhouMahjongSeatStatistics(
    val wins: Int = 0,
    val falseDeclarations: Int = 0,
    val netScore: BigInteger = BigInteger.ZERO,
)

internal data class LanzhouMahjongStatistics(
    val completedRounds: Int = 0,
    val exhaustiveDraws: Int = 0,
    val seats: List<LanzhouMahjongSeatStatistics> = List(4) { LanzhouMahjongSeatStatistics() },
)

/** Project only first receipts. Aggregate in a wider integer type than the per-round engine amounts. */
internal fun summarizeLanzhouMahjongSettlements(
    rows: List<LanzhouMahjongSettlementSummaryRow>,
): LanzhouMahjongStatistics {
    var exhaustiveDraws = 0
    val seats = MutableList(4) { LanzhouMahjongSeatStatistics() }
    for (row in rows) {
        val scores = listOf(row.seat0Score, row.seat1Score, row.seat2Score, row.seat3Score)
            .map(BigInteger::valueOf)
        val winner = row.winnerSeat
        val payer = row.payerSeat
        val validOutcome = when (row.outcome) {
            LanzhouMahjongQualifiedOutcome.WIN.name, LanzhouMahjongQualifiedOutcome.LOSS.name ->
                winner != null && winner in seats.indices &&
                    (payer == null || (payer in seats.indices && payer != winner))
            LanzhouMahjongQualifiedOutcome.FALSE_DECLARATION.name ->
                winner == null && payer != null && payer in seats.indices
            LanzhouMahjongQualifiedOutcome.EXHAUSTIVE_DRAW.name ->
                winner == null && payer == null && scores.all { it == BigInteger.ZERO }
            else -> false
        }
        if (!validOutcome || scores.fold(BigInteger.ZERO, BigInteger::add) != BigInteger.ZERO) {
            throw IOException("兰州麻将首结统计记录不完整")
        }
        if (row.outcome == LanzhouMahjongQualifiedOutcome.EXHAUSTIVE_DRAW.name) exhaustiveDraws++
        for (seat in seats.indices) {
            val previous = seats[seat]
            seats[seat] = LanzhouMahjongSeatStatistics(
                wins = previous.wins + if (winner == seat) 1 else 0,
                falseDeclarations = previous.falseDeclarations +
                    if (row.outcome == LanzhouMahjongQualifiedOutcome.FALSE_DECLARATION.name && payer == seat) 1 else 0,
                netScore = previous.netScore + scores[seat],
            )
        }
    }
    return LanzhouMahjongStatistics(rows.size, exhaustiveDraws, seats.toList())
}
