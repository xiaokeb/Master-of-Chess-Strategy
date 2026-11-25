package com.masterofchessstrategy.games.lanzhou_mahjong.settlement

import com.masterofchessstrategy.engine.ActionResult
import com.masterofchessstrategy.engine.Difficulty
import com.masterofchessstrategy.engine.LanzhouMahjongAction
import com.masterofchessstrategy.engine.LanzhouMahjongActionKind
import com.masterofchessstrategy.engine.LanzhouMahjongBotStop
import com.masterofchessstrategy.engine.NativeLanzhouMahjongSession
import com.masterofchessstrategy.games.lanzhou_mahjong.LanzhouMahjongSessionRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Test

class LanzhouMahjongNativeSettlementInstrumentedTest {
    private val replay = LanzhouMahjongNativeSettlementReplay()
    private val policy = LanzhouMahjongSettlementPolicy(replay)

    @Test
    fun realFalseDeclarationReplaysFourSeatPenaltyAndPreservesFirstReceipt() {
        val seed = 5L
        NativeLanzhouMahjongSession(seed, 0, 1, 0).use { session ->
            val creation = creation(seed, session)
            assertEquals(ActionResult.Accepted, session.apply(LanzhouMahjongAction(LanzhouMahjongActionKind.WIN)))
            val terminal = record(seed, session)
            val first = settle(creation, terminal)
            assertEquals(LanzhouMahjongQualifiedOutcome.FALSE_DECLARATION, first.outcome)
            assertEquals(listOf(-72L, 24L, 24L, 24L), first.netScores)
            assertEquals(0, first.payerSeat)
            assertFalse(first.isQualifiedWin)

            val retried = policy.firstTerminal(
                creation, LanzhouMahjongTerminalCandidate(creation.sessionId, terminal, 300), first,
            ) as LanzhouMahjongFirstSettlement.AlreadySettled
            assertSame(first, retried.receipt)
            assertEquals(200L, first.terminal.capturedAt)
        }
    }

    @Test
    fun seededFullRoundWithViewerWinUsesNativeWinnerAndPayments() {
        verifyNormalTerminal(1, LanzhouMahjongQualifiedOutcome.WIN, 0, listOf(6L, -2L, -2L, -2L))
    }

    @Test
    fun seededExhaustiveDrawDoesNotQualifyAsWin() {
        verifyNormalTerminal(5, LanzhouMahjongQualifiedOutcome.EXHAUSTIVE_DRAW, null, List(4) { 0L })
    }

    private fun verifyNormalTerminal(
        seed: Long,
        expectedOutcome: LanzhouMahjongQualifiedOutcome,
        expectedWinner: Int?,
        expectedPayments: List<Long>,
    ) {
        NativeLanzhouMahjongSession(seed, 0, 1, 0).use { session ->
            val creation = creation(seed, session)
            repeat(240) { turn ->
                when (session.advanceOtherSeats(Difficulty.EASY, seed + turn).stop) {
                    LanzhouMahjongBotStop.TERMINAL -> {
                        val view = session.snapshot()
                        val receipt = settle(creation, record(seed, session))
                        assertFalse(view.outcome.falseWin)
                        assertEquals(expectedWinner, receipt.winnerSeat)
                        assertEquals(expectedPayments, receipt.netScores)
                        assertEquals(expectedOutcome, receipt.outcome)
                        return
                    }
                    LanzhouMahjongBotStop.VIEWER_ACTION -> {
                        val legal = session.legalActions()
                        val choice = legal.firstOrNull { it.kind == LanzhouMahjongActionKind.WIN }
                            ?: legal.first { it.kind in ordinaryActions }
                        assertEquals(ActionResult.Accepted, session.apply(choice))
                    }
                    else -> error("Native bot did not finish or yield a legal turn")
                }
            }
            error("Seeded round did not reach terminal within 240 viewer turns")
        }
    }

    private fun record(seed: Long, session: NativeLanzhouMahjongSession) =
        LanzhouMahjongSessionRecord(Difficulty.EASY, 0, seed, 0, 1, seed, session.serialize())

    private fun creation(seed: Long, session: NativeLanzhouMahjongSession): LanzhouMahjongFormalCreation {
        val initial = record(seed, session)
        return LanzhouMahjongFormalCreation(
            replay.sessionId(initial), LanzhouMahjongCreationOrigin.AUTHORIZED_STANDARD_HUMAN, initial, 100,
        )
    }

    private fun settle(creation: LanzhouMahjongFormalCreation, record: LanzhouMahjongSessionRecord) =
        (policy.firstTerminal(creation, LanzhouMahjongTerminalCandidate(creation.sessionId, record, 200))
            as LanzhouMahjongFirstSettlement.Insert).receipt

    private companion object {
        val ordinaryActions = setOf(
            LanzhouMahjongActionKind.DRAW, LanzhouMahjongActionKind.DISCARD, LanzhouMahjongActionKind.PASS,
        )
    }
}
