package com.masterofchessstrategy.games.competition_mahjong.auto

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.masterofchessstrategy.engine.Difficulty
import com.masterofchessstrategy.engine.NativeCompetitionMahjongAutoSession
import com.masterofchessstrategy.games.competition_mahjong.match.policy.CompetitionMahjongPlayPolicy
import com.masterofchessstrategy.games.competition_mahjong.match.policy.CompetitionMahjongUnlockProgress
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException

/** Real JNI + App transactions; isolated byte store, not a claim of production Room or process death. */
@RunWith(AndroidJUnit4::class)
class CompetitionMahjongAutoControllerInstrumentedTest {
    @Test fun realNativeProgressMatchesIndependentSessionAndRestoresWithoutExtraEvent() = runBlocking {
        val config = fixtureConfig(viewer = 2, levels = listOf(0, 1, 2, 3))
        val store = IsolatedAutoByteStore()
        val controller = CompetitionMahjongAutoController(config, store, fixturePolicy())
        try {
            assertTrue(controller.open())
            val expectedRecord = NativeCompetitionMahjongAutoSession(config.handConfig(0, 0, false)).use { expected ->
                repeat(8) {
                    assertTrue(controller.step(0, 0))
                    assertEquals(1, expected.advance(1).acceptedActions)
                    assertEquals(expected.snapshot(), controller.state.value.frame)
                }
                expected.serialize()
            }
            val frame = checkNotNull(controller.state.value.frame)
            assertEquals(2, frame.publicFrameVersion)
            assertTrue(frame.seats.filter { it.seat != frame.viewerSeat }.all { it.concealedTiles.isEmpty() })
            assertArrayEquals(expectedRecord, checkNotNull(store.load(2)).engineRecord)
            val bytes = store.bytes!!.copyOf()
            controller.close()
            val resumed = CompetitionMahjongAutoController(config, store, fixturePolicy())
            try {
                assertTrue(resumed.open())
                assertEquals(frame, resumed.state.value.frame)
                assertEquals(8, resumed.state.value.eventsAfterOpening)
                assertArrayEquals(bytes, store.bytes)
            } finally { resumed.close() }
        } finally { controller.close() }
    }

    @Test fun legalSpecifiedOpeningIsImmutableAcrossUndoSaveAndRestore() = runBlocking {
        val config = fixtureConfig()
        val opening = NativeCompetitionMahjongAutoSession(config.handConfig(0, 0, true)).use { expected ->
            repeat(8) { assertFalse(expected.advance(1).terminal) }
            expected.serialize()
        }
        val store = IsolatedAutoByteStore()
        val controller = CompetitionMahjongAutoController(config, store, fixturePolicy(), openingRecord = opening)
        try {
            assertTrue(controller.open())
            assertFalse(controller.undo(0, 0))
            assertTrue(controller.step(0, 0))
            assertTrue(controller.undo(0, 0))
            assertEquals(0, controller.state.value.eventsAfterOpening)
            assertArrayEquals(opening, checkNotNull(store.load(0)).engineRecord)
            assertFalse(controller.undo(0, 0))
            val frame = controller.state.value.frame
            controller.close()
            val resumed = CompetitionMahjongAutoController(config, store, fixturePolicy())
            try {
                assertTrue(resumed.open())
                assertEquals(frame, resumed.state.value.frame)
                assertFalse(resumed.undo(0, 0))
                assertArrayEquals(opening, checkNotNull(store.load(0)).openingRecord)
            } finally { resumed.close() }
        } finally { controller.close() }
    }

    @Test fun actualTerminalEventSaveFailureRetriesOnceAndUndoRemovesNativeResult() = runBlocking {
        val config = fixtureConfig()
        val native = config.handConfig(0, 0, false)
        val baseline: ByteArray
        val beforeTerminal: ByteArray
        val terminal: ByteArray
        NativeCompetitionMahjongAutoSession(native).use { expected ->
            baseline = expected.serialize()
            var actions = 0
            while (!expected.snapshot().isAutoTerminal()) {
                actions += expected.advance(32).acceptedActions
                assertTrue("Native event budget exceeded", actions <= 4096)
            }
            terminal = expected.serialize()
            assertTrue(expected.undo())
            assertFalse(expected.snapshot().isAutoTerminal())
            beforeTerminal = expected.serialize()
        }
        val store = IsolatedAutoByteStore()
        store.save(CompetitionMahjongAutoCheckpoint(config, CompetitionMahjongAutoSettings(), 0, 0, emptyList(), baseline, beforeTerminal))
        val controller = CompetitionMahjongAutoController(config, store, fixturePolicy())
        try {
            assertTrue(controller.open())
            val before = store.bytes!!.copyOf()
            store.failNext = true
            assertFalse(controller.step(0, 0))
            assertTrue(controller.state.value.needsSave)
            assertFalse(controller.step(0, 0))
            assertArrayEquals(before, store.bytes)
            assertTrue(controller.retrySave())
            val saved = checkNotNull(store.load(0))
            assertArrayEquals(terminal, saved.engineRecord)
            assertEquals(1, saved.completedHands.size)
            saved.validateFrame(checkNotNull(controller.state.value.frame))
            assertTrue(controller.undo(0, 0))
            assertEquals(0, checkNotNull(store.load(0)).completedHands.size)
            assertArrayEquals(beforeTerminal, checkNotNull(store.load(0)).engineRecord)
        } finally { controller.close() }
    }

    private fun fixtureConfig(viewer: Int = 0, levels: List<Int> = List(4) { 0 }) =
        CompetitionMahjongAutoRunConfig("82000000-0000-4000-8000-000000000001", 73, viewer, levels, 109)

    /** Test-only full permission snapshot. Production must supply trusted progress. */
    private fun fixturePolicy() = CompetitionMahjongPlayPolicy.fromProgress(
        CompetitionMahjongUnlockProgress(true, 10, 15, 20), Difficulty.entries.associateWith { it.code },
    )

    private class IsolatedAutoByteStore : CompetitionMahjongAutoStore {
        var bytes: ByteArray? = null
        var failNext = false
        override suspend fun load(viewerPlayerId: Int): CompetitionMahjongAutoCheckpoint? = bytes?.let {
            CompetitionMahjongAutoCheckpointCodec.decode(it, viewerPlayerId) ?: throw IOException("隔离活动损坏")
        }
        override suspend fun save(checkpoint: CompetitionMahjongAutoCheckpoint, archive: CompetitionMahjongAutoArchive?) {
            check(archive == null) { "这些单盘夹具不生成完整赛事归档" }
            if (failNext) { failNext = false; throw IOException("隔离保存失败") }
            bytes = CompetitionMahjongAutoCheckpointCodec.encode(checkpoint)
        }
    }
}
