package com.masterofchessstrategy.engine

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeLanzhouMahjongInstrumentedTest {
    @Test fun onlyFixedViewerSeesConcealedTilesAndCanAct() {
        NativeLanzhouMahjongSession(20261002, viewerSeat = 2).use { session ->
            val state = session.snapshot()
            assertEquals(LanzhouMahjongPhase.DISCARD, state.phase)
            assertEquals(0, state.currentSeat)
            assertEquals(2, state.seats[2].seat)
            assertEquals(state.seats[2].concealedCount, state.seats[2].concealedTiles.size)
            assertTrue(state.seats[2].concealedTiles.isNotEmpty())
            (0..3).filter { it != 2 }.forEach { opponent ->
                assertTrue(state.seats[opponent].concealedCount > 0)
                assertTrue(state.seats[opponent].concealedTiles.isEmpty())
            }
            assertTrue(session.legalActions().isEmpty())
            assertTrue(session.apply(LanzhouMahjongAction(LanzhouMahjongActionKind.DRAW))
                is ActionResult.Rejected)
            assertEquals(state, session.snapshot())
        }
    }

    @Test fun localDiscardUndoAndRestoreKeepPublicViewAndRejectCorruption() {
        NativeLanzhouMahjongSession(20261002).use { session ->
            val start = session.snapshot()
            assertEquals(14, start.seats[0].concealedCount)
            val discard = session.legalActions().first {
                it.kind == LanzhouMahjongActionKind.DISCARD
            }
            assertEquals(ActionResult.Accepted, session.apply(discard))
            val after = session.snapshot()
            assertEquals(LanzhouMahjongPhase.CLAIM, after.phase)
            assertEquals(13, after.seats[0].concealedCount)
            assertTrue(after.seats.drop(1).all { it.concealedTiles.isEmpty() })
            val saved = session.serialize()
            assertTrue(saved.isNotEmpty())
            assertEquals(RestoreResult.Rejected(EngineError.CORRUPTED_DATA),
                session.restore(saved.copyOf(saved.size - 1)))
            assertEquals(after, session.snapshot())
            assertTrue(session.undo())
            assertEquals(start, session.snapshot())
            assertEquals(RestoreResult.Restored, session.restore(saved))
            assertEquals(after, session.snapshot())
            NativeLanzhouMahjongSession(20261002).use { fresh ->
                assertEquals(RestoreResult.Restored, fresh.restore(saved))
                assertEquals(after, fresh.snapshot())
            }
            NativeLanzhouMahjongSession(20261003, viewerSeat = 2).use { otherViewer ->
                assertEquals(RestoreResult.Restored, otherViewer.restore(saved))
                val restored = otherViewer.snapshot()
                assertEquals(after.phase, restored.phase)
                assertEquals(after.seats[2].concealedCount, restored.seats[2].concealedTiles.size)
                assertTrue(restored.seats.filter { it.seat != 2 }
                    .all { it.concealedTiles.isEmpty() })
            }
        }
    }
}
