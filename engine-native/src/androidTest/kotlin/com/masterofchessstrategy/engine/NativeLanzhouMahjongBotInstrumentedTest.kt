package com.masterofchessstrategy.engine

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.masterofchessstrategy.engine.internal.NativeBindings
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream

@RunWith(AndroidJUnit4::class)
class NativeLanzhouMahjongBotInstrumentedTest {
    @Test fun allViewersAndStrategiesStopBeforeLocalActionAndKeepHandsPrivate() {
        for (viewer in 0..3) for (level in Difficulty.entries) for (seed in 1L..4L) {
            NativeLanzhouMahjongSession(seed, (viewer + 1) % 4, viewerSeat = viewer).use { game ->
                val result = game.advanceOtherSeats(level, seed)
                assertTrue(result.steps > 0)
                assertTrue(result.stop == LanzhouMahjongBotStop.VIEWER_ACTION ||
                    result.stop == LanzhouMahjongBotStop.TERMINAL)
                val snapshot = game.snapshot()
                checkPrivacy(snapshot, viewer)
                val saved = game.serialize()
                val repeated = game.advanceOtherSeats(level, seed)
                assertEquals(result.stop, repeated.stop)
                assertEquals(0, repeated.steps)
                assertArrayEquals(saved, game.serialize())
                if (result.stop == LanzhouMahjongBotStop.VIEWER_ACTION)
                    assertTrue(game.legalActions().isNotEmpty())
            }
        }
    }

    @Test fun budgetDeterminismUndoAndRestoreUseTheRealNativeJournal() {
        NativeLanzhouMahjongSession(91, 1).use { game ->
            NativeLanzhouMahjongSession(91, 1).use { same ->
                val initial = game.serialize()
                val limited = game.advanceOtherSeats(Difficulty.MEDIUM, 7, 1)
                assertEquals(LanzhouMahjongBotAdvance(LanzhouMahjongBotStop.STEP_LIMIT, 1), limited)
                assertEquals(limited, same.advanceOtherSeats(Difficulty.MEDIUM, 7, 1))
                val after = game.serialize()
                assertArrayEquals(after, same.serialize())
                assertTrue(game.undo())
                assertArrayEquals(initial, game.serialize())
                assertEquals(RestoreResult.Restored, game.restore(after))
                assertEquals(same.snapshot(), game.snapshot())
            }
        }
    }

    @Test fun localPlayAndOpponentsCanReachATerminalForAllStrategies() {
        for (level in Difficulty.entries) NativeLanzhouMahjongSession(25).use { game ->
            var localActions = 0
            while (true) {
                val result = game.advanceOtherSeats(level, 25L + localActions)
                checkPrivacy(game.snapshot(), 0)
                if (result.stop == LanzhouMahjongBotStop.TERMINAL) break
                assertEquals(LanzhouMahjongBotStop.VIEWER_ACTION, result.stop)
                assertTrue(++localActions < 150)
                val actions = game.legalActions()
                val action = actions.firstOrNull { it.kind == LanzhouMahjongActionKind.WIN }
                    ?: actions.firstOrNull { it.kind == LanzhouMahjongActionKind.DISCARD }
                    ?: actions.first()
                assertEquals(ActionResult.Accepted, game.apply(action))
            }
            assertTrue(game.snapshot().phase in listOf(LanzhouMahjongPhase.WON, LanzhouMahjongPhase.EXHAUSTIVE_DRAW))
            assertEquals(0L, game.snapshot().netScores.sum())
            val terminal = game.serialize()
            assertEquals(LanzhouMahjongBotAdvance(LanzhouMahjongBotStop.TERMINAL, 0),
                game.advanceOtherSeats(level, 0))
            assertArrayEquals(terminal, game.serialize())
        }
    }

    @Test fun concealedKongFixtureHidesTheValueInAllThreeOpponentViews() {
        for (viewer in 0..3) NativeLanzhouMahjongSession(1, viewerSeat = viewer).use { game ->
            assertEquals(RestoreResult.Restored, game.restore(concealedKongRecord()))
            val view = game.snapshot()
            val kong = view.seats[0].melds.single()
            assertEquals(LanzhouMahjongMeldKind.CONCEALED_KONG, kong.kind)
            assertEquals(if (viewer == 0) 0 else -1, kong.tile)
            assertEquals(viewer != 0, kong.isTileHidden)
            checkPrivacy(view, viewer)
            val saved = game.serialize()
            assertTrue(game.undo())
            assertTrue(game.snapshot().seats[0].melds.isEmpty())
            assertEquals(RestoreResult.Restored, game.restore(saved))
            assertEquals(view, game.snapshot())
        }
    }

    @Test fun invalidArgumentsWrongHandlesAndClosedSessionsCannotAdvance() {
        NativeLanzhouMahjongSession(91, 1).use { game ->
            val save = game.serialize()
            for ((seed, limit) in listOf(-1L to 1, 0L to 0, 0L to 513)) {
                assertThrows(IllegalArgumentException::class.java) {
                    game.advanceOtherSeats(Difficulty.EASY, seed, limit)
                }
                assertArrayEquals(save, game.serialize())
            }
        }
        val handle = NativeBindings.createLanzhouMahjong(91, 1, 1, 0)
        try {
            val save = NativeBindings.serializeLanzhouMahjong(handle)
            for ((level, seed, limit) in listOf(Triple(-1, 0L, 1), Triple(4, 0L, 1),
                Triple(0, -1L, 1), Triple(0, 0L, 0), Triple(0, 0L, 513))) {
                assertThrows(IllegalArgumentException::class.java) {
                    NativeBindings.advanceLanzhouMahjongOtherSeats(handle, level, seed, limit)
                }
                assertArrayEquals(save, NativeBindings.serializeLanzhouMahjong(handle))
            }
        } finally { NativeBindings.releaseLanzhouMahjong(handle) }
        val chess = NativeBindings.createChineseChessEngine()
        try {
            assertThrows(IllegalStateException::class.java) {
                NativeBindings.advanceLanzhouMahjongOtherSeats(chess, 0, 0, 1)
            }
            assertTrue(NativeBindings.serializeChineseChess(chess).isNotEmpty())
        } finally { NativeBindings.releaseChineseChessEngine(chess) }
        val closed = NativeLanzhouMahjongSession(1)
        closed.close()
        closed.close()
        assertThrows(IllegalStateException::class.java) { closed.advanceOtherSeats(Difficulty.EASY, 0) }
    }

    private fun checkPrivacy(view: LanzhouMahjongPublicView, viewer: Int) {
        for (seat in view.seats) {
            assertEquals(if (seat.seat == viewer) seat.concealedCount else 0, seat.concealedTiles.size)
            for (meld in seat.melds) assertEquals(
                seat.seat != viewer && meld.kind == LanzhouMahjongMeldKind.CONCEALED_KONG,
                meld.isTileHidden,
            )
        }
    }

    /** Test-only lawful wall and action journal; no production wall injection API. */
    private fun concealedKongRecord(): ByteArray {
        val wall = IntArray(108) { -1 }
        val remaining = IntArray(27) { 4 }
        val hand = intArrayOf(0, 0, 0, 0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10)
        for ((index, tile) in hand.withIndex()) {
            wall[if (index < 13) index * 4 else 52] = tile
            remaining[tile]--
        }
        var tile = 0
        for (index in wall.indices) if (wall[index] == -1) {
            while (remaining[tile] == 0) tile++
            wall[index] = tile
            remaining[tile]--
        }
        val output = ByteArrayOutputStream()
        fun number(value: Long, count: Int) { repeat(count) { output.write((value ushr (it * 8)).toInt() and 255) } }
        output.write(byteArrayOf(76, 90, 77, 74, 1, 0))
        number(1, 8)
        wall.forEach(output::write)
        number(1, 4)
        output.write(byteArrayOf(6, 0, 0))
        var hash = 2166136261L
        output.toByteArray().forEach { hash = ((hash xor (it.toLong() and 255)) * 16777619) and 0xffffffffL }
        number(hash, 4)
        return output.toByteArray()
    }
}
