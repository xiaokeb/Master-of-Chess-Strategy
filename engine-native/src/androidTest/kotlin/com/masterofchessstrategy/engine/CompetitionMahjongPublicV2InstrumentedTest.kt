package com.masterofchessstrategy.engine

import org.junit.Assert.*
import org.junit.Test

/** Real fixed-viewer JNI queries; private test records contain only synthetic, known walls. */
class CompetitionMahjongPublicV2InstrumentedTest {
    private val closed = listOf(0,0,0,1,1,1,2,2,2,3,3,3,4,4)
    private val kongHand = listOf(31,31,31,31,0,0,0,1,1,1,2,2,2,3)
    private val kongEvent = listOf(6,0,31,0)
    private val winEvent = listOf(8,0,3,0)

    @Test fun localNewTileAndUnansweredClaimsUseActualEligibility() {
        for (viewer in 0..3) NativeCompetitionMahjongSession(925, viewerSeat = viewer).use { session ->
            assertEquals(RestoreResult.Restored, session.restore(CompetitionMahjongNativeTestRecords.record(closed, viewer)))
            val initial = session.snapshot()
            assertEquals(2, initial.publicFrameVersion)
            assertEquals(viewer == 0, initial.canDeclareWin)
            assertEquals(if (viewer == 0) 4 else null, initial.drawnTile)
            val events = listOf(listOf(1,0,0,0))
            assertEquals(RestoreResult.Restored, session.restore(CompetitionMahjongNativeTestRecords.record(closed, viewer, events)))
            val claim = session.snapshot()
            assertEquals(CompetitionMahjongRoundPhase.CLAIM, claim.phase)
            assertNull(claim.drawnTile)
            assertEquals(viewer != 0, claim.canDeclareWin)
            if (viewer != 0) {
                assertTrue(session.apply(CompetitionMahjongAction(CompetitionMahjongRoundActionKind.PASS, 0)).accepted)
                assertFalse(session.snapshot().canDeclareWin)
            }
        }
    }

    @Test fun winnerAndKongRevelationsRestoreAndUndoWithoutLeakingOtherHands() {
        for (viewer in 0..3) NativeCompetitionMahjongSession(925, viewerSeat = viewer).use { session ->
            val before = CompetitionMahjongNativeTestRecords.record(kongHand, viewer, listOf(kongEvent), listOf(3))
            val terminal = CompetitionMahjongNativeTestRecords.record(kongHand, viewer, listOf(kongEvent, winEvent), listOf(3))
            assertEquals(RestoreResult.Restored, session.restore(before))
            val playing = session.snapshot()
            assertEquals(if (viewer == 0) 31 else null, playing.seats[0].melds.single().tile)
            assertEquals(if (viewer == 0) 3 else null, playing.drawnTile)
            assertTrue(playing.winningTiles.isEmpty())
            assertEquals(RestoreResult.Restored, session.restore(terminal))
            val won = session.snapshot()
            assertEquals(2, won.publicFrameVersion)
            assertEquals(CompetitionMahjongRoundPhase.WON, won.phase)
            assertEquals(0, won.winnerSeat)
            assertEquals(31, won.seats[0].melds.single().tile)
            assertEquals(3, won.winningTile)
            assertEquals(listOf(0,0,0,1,1,1,2,2,2,3,3), won.winningTiles.sorted())
            assertFalse(won.canDeclareWin)
            assertNull(won.drawnTile)
            for (seat in won.seats) if (seat.seat != viewer) assertTrue(seat.concealedTiles.isEmpty())
            val saved = session.serialize()
            assertEquals(1, saved[4].toInt()) // MCMS unchanged.
            assertEquals(1, saved[16].toInt()) // Nested MOCM unchanged.
            NativeCompetitionMahjongSession(43, viewerSeat = viewer).use { restored ->
                assertEquals(RestoreResult.Restored, restored.restore(saved))
                assertEquals(won, restored.snapshot())
                assertArrayEquals(saved, restored.serialize())
            }
            assertTrue(session.undo())
            assertEquals(playing, session.snapshot())
            assertArrayEquals(before, session.serialize())
        }
    }

    @Test fun stoppedWinningSeatStillSeesItsNewTileButCannotDeclareAgain() {
        val bad = listOf(0,2,4,6,8,9,11,13,15,17,18,20,22,24)
        NativeCompetitionMahjongSession(925).use { session ->
            assertEquals(RestoreResult.Restored, session.restore(CompetitionMahjongNativeTestRecords.record(bad, 0)))
            val result = session.apply(CompetitionMahjongAction(CompetitionMahjongRoundActionKind.WIN, 24))
            assertTrue(result.accepted && result.falseWin)
            val view = session.snapshot()
            assertTrue(view.seats[0].winDisabled)
            assertFalse(view.canDeclareWin)
            assertEquals(24, view.drawnTile)
            assertNull(view.winningTile)
            assertTrue(view.winningTiles.isEmpty())
        }
    }

    @Test fun chowDiscardIsNotANewDrawOrActiveWinOpportunity() {
        val events = listOf(listOf(1,0,0,0), listOf(2,2,0,0), listOf(2,3,0,0), listOf(3,1,0,0))
        NativeCompetitionMahjongSession(925, viewerSeat = 1).use { session ->
            assertEquals(RestoreResult.Restored, session.restore(CompetitionMahjongNativeTestRecords.record(closed, 1, events)))
            val view = session.snapshot()
            assertEquals(CompetitionMahjongRoundPhase.DISCARD, view.phase)
            assertEquals(1, view.currentSeat)
            assertEquals(CompetitionMahjongMeldKind.CHOW, view.seats[1].melds.single().kind)
            assertNull(view.drawnTile)
            assertFalse(view.canDeclareWin)
            val attempt = session.apply(CompetitionMahjongAction(CompetitionMahjongRoundActionKind.WIN, 1))
            assertFalse(attempt.accepted)
        }
    }
}
