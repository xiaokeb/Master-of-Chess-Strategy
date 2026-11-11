package com.masterofchessstrategy.games.competition_mahjong.auto.replay

import com.masterofchessstrategy.engine.*
import com.masterofchessstrategy.games.competition_mahjong.auto.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import kotlin.coroutines.coroutineContext

class CompetitionMahjongAutoReplayViewModelTest {
    private data class Stored(val archive: CompetitionMahjongAutoArchive, val savedAt: Long,
                              var favorite: Boolean = false)

    private class MemoryLibrary(values: List<Stored>) : CompetitionMahjongAutoArchiveLibrary {
        val values = linkedMapOf<String, Stored>().also { map ->
            values.forEach { map[it.archive.key] = it }
        }
        var failFavorite = false
        var failDelete = false
        override suspend fun list(viewerPlayerId: Int): List<CompetitionMahjongAutoArchiveSummary> =
            values.values.filter { it.archive.checkpoint.config.viewerPlayerId == viewerPlayerId }
                .sortedByDescending { it.savedAt }.map { it.archive.summary(it.savedAt, it.favorite) }
        override suspend fun load(key: String, viewerPlayerId: Int): CompetitionMahjongAutoArchive? =
            values[key]?.archive?.also { require(it.checkpoint.config.viewerPlayerId == viewerPlayerId) }
        override suspend fun setFavorite(key: String, viewerPlayerId: Int, favorite: Boolean) {
            if (failFavorite) throw java.io.IOException("收藏保存失败")
            val stored = checkNotNull(values[key])
            require(stored.archive.checkpoint.config.viewerPlayerId == viewerPlayerId)
            stored.favorite = favorite
        }
        override suspend fun delete(key: String, viewerPlayerId: Int) {
            if (failDelete) throw java.io.IOException("删除失败")
            val stored = checkNotNull(values[key])
            require(stored.archive.checkpoint.config.viewerPlayerId == viewerPlayerId)
            values.remove(key)
        }
    }

    private class ViewSession(override val config: CompetitionMahjongAutoConfig) : CompetitionMahjongAutoSession {
        private val delegate = AutoSessionDouble(config)
        val closed get() = delegate.closed
        override fun snapshot(): CompetitionMahjongPublicView = delegate.snapshot().let { frame ->
            frame.copy(seats = frame.seats.map { seat ->
                if (seat.seat == frame.viewerSeat)
                    seat.copy(concealedTiles = List(seat.concealedCount) { it % 9 }) else seat
            })
        }
        override fun advance(maxActions: Int): CompetitionMahjongAutoAdvance = delegate.advance(maxActions)
        override fun undo(): Boolean = delegate.undo()
        override fun serialize(): ByteArray = delegate.serialize()
        override fun restore(data: ByteArray): RestoreResult = delegate.restore(data)
        override fun close() = delegate.close()
    }

    private class TimerGate {
        data class Wait(val millis: Long, val signal: CompletableDeferred<Unit>)
        private val channel = Channel<Wait>(Channel.UNLIMITED)
        val issued = mutableListOf<Wait>()
        suspend fun wait(millis: Long) {
            val item = Wait(millis, CompletableDeferred(coroutineContext[Job]))
            issued += item
            channel.send(item)
            item.signal.await()
        }
        suspend fun next(): Wait = withTimeout(2000) {
            while (true) {
                val item = channel.receive()
                if (item.signal.isActive) return@withTimeout item
            }
            error("unreachable")
        }
    }

    private class Harness(
        stored: List<Stored> = listOf(
            Stored(CompetitionMahjongAutoArchive(autoCheckpoint(hand = 15, completed = true)), 200),
            Stored(CompetitionMahjongAutoArchive(autoCheckpoint(
                config = autoConfig(id = "81000000-0000-4000-8000-000000000002"),
                hand = 15, completed = true)), 100),
        ),
    ) {
        val library = MemoryLibrary(stored)
        val sessions = mutableListOf<ViewSession>()
        val gate = TimerGate()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val game = CompetitionMahjongAutoReplayViewModel(library, 0,
            sessionFactory = { ViewSession(it).also(sessions::add) }, parentScope = scope,
            dispatcher = Dispatchers.Unconfined, waitMillis = gate::wait)
        suspend fun until(predicate: (CompetitionMahjongAutoReplayUiState) -> Boolean) =
            withTimeout(2000) { game.state.first(predicate) }
        suspend fun firstKey(): String = until { !it.busy && it.entries.isNotEmpty() }.entries.first().key
        fun cancel() = scope.cancel()
    }

    @Test fun refreshSortsNewestFirstAndFavoritesFilterDoesNotReloadPrivateBytes() = runBlocking {
        val harness = Harness()
        try {
            var state = harness.until { !it.busy && it.entries.size == 2 }
            assertEquals(listOf(200L, 100L), state.entries.map { it.savedAtEpochMillis })
            assertEquals(2, state.visibleEntries.size)
            harness.game.setFavorite(state.entries[1].key, true).join()
            state = harness.until { !it.busy && it.entries[1].favorite }
            harness.game.showFavorites(true)
            state = harness.game.state.value
            assertEquals(1, state.visibleEntries.size)
            assertTrue(state.visibleEntries.single().favorite)
        } finally { harness.cancel() }
    }

    @Test fun selectPublishesOnlyPublicTableAndStableAiNames() = runBlocking {
        val harness = Harness()
        try {
            val key = harness.firstKey()
            harness.game.select(key).join()
            val state = harness.until { !it.busy && it.selectedKey == key }
            val table = checkNotNull(state.table)
            assertEquals(listOf("玩家1AI", "玩家2AI", "玩家3AI", "玩家4AI"), table.seats.map { it.name })
            assertTrue(table.availableActions.isEmpty())
            assertEquals(13, table.seats[table.localSeat].visibleHand!!.size)
            assertTrue(table.seats.filter { it.seat != table.localSeat }.all { it.visibleHand == null })
            assertEquals("棋谱回放 · 事件 0 / 3", table.status)
            assertEquals(17, harness.sessions.size)
        } finally { harness.cancel() }
    }

    @Test fun manualForwardBackwardAndHandSelectionStopPlayback() = runBlocking {
        val harness = Harness()
        try {
            harness.game.select(harness.firstKey()).join()
            harness.game.forward().join()
            assertEquals(1, harness.game.state.value.replay.eventIndex)
            harness.game.backward().join()
            assertEquals(0, harness.game.state.value.replay.eventIndex)
            harness.game.selectHand(8).join()
            assertEquals(8, harness.game.state.value.replay.handIndex)
            assertEquals(0, harness.game.state.value.replay.eventIndex)
            assertFalse(harness.game.state.value.playing)
        } finally { harness.cancel() }
    }

    @Test fun speedChangeCancelsOldTimerAndPlaybackAdvancesOnlyAfterDelay() = runBlocking {
        val harness = Harness()
        try {
            harness.game.select(harness.firstKey()).join()
            harness.game.setSpeed(2f)
            harness.game.togglePlayback()
            val first = harness.gate.next()
            assertEquals(200L, first.millis)
            first.signal.complete(Unit)
            harness.until { it.replay.eventIndex == 1 && !it.busy }
            val old = harness.gate.next()
            harness.game.setSpeed(4f)
            assertTrue(old.signal.isCancelled)
            assertEquals(100L, harness.gate.next().millis)
            assertEquals(1, harness.game.state.value.replay.eventIndex)
        } finally { harness.cancel() }
    }

    @Test fun playbackCrossesAllHandsAndStopsAtExactArchiveEnd() = runBlocking {
        val harness = Harness()
        try {
            harness.game.select(harness.firstKey()).join()
            harness.game.setSpeed(4f)
            harness.game.togglePlayback()
            repeat(63) {
                harness.gate.next().signal.complete(Unit)
                yield()
            }
            val state = harness.until { it.replay.atEnd && !it.busy }
            assertFalse(state.playing)
            assertEquals(15, state.replay.handIndex)
            assertEquals(3, state.replay.eventIndex)
            assertFalse(state.replay.canForward)
        } finally { harness.cancel() }
    }

    @Test fun invalidCandidateArchivePreservesCurrentReplay() = runBlocking {
        val valid = Stored(CompetitionMahjongAutoArchive(autoCheckpoint(hand = 15, completed = true)), 200)
        val invalid = Stored(CompetitionMahjongAutoArchive(autoCheckpoint(
            config = autoConfig(id = "81000000-0000-4000-8000-000000000003"),
            hand = 15, completed = true, winnerPlayer = { 0 })), 100)
        val harness = Harness(listOf(valid, invalid))
        try {
            harness.game.select(harness.firstKey()).join()
            val oldKey = harness.game.state.value.selectedKey
            val oldFrame = harness.game.state.value.replay.frame
            harness.game.select(invalid.archive.key).join()
            val state = harness.until { !it.busy }
            assertEquals(oldKey, state.selectedKey)
            assertEquals(oldFrame, state.replay.frame)
            assertNotNull(state.error)
        } finally { harness.cancel() }
    }

    @Test fun explicitExportCopiesPrivatePackageAndCancellationPreservesArchive() = runBlocking {
        val harness = Harness()
        try {
            val key = harness.firstKey()
            var received: CompetitionMahjongAutoExport? = null
            harness.game.export(key) { value -> received = value; false }.join()
            val state = harness.until { !it.busy }
            assertNotNull(received)
            assertNotNull(CompetitionMahjongAutoExportCodec.decode(received!!.bytes, 0))
            assertTrue(harness.library.values.containsKey(key))
            assertNotNull(state.error)
        } finally { harness.cancel() }
    }

    @Test fun coroutineCancelledExportClearsBusyAndReleasesTransaction() = runBlocking {
        val harness = Harness()
        try {
            val key = harness.firstKey()
            val entered = CompletableDeferred<Unit>()
            val export = harness.game.export(key) {
                entered.complete(Unit)
                awaitCancellation()
            }
            entered.await()
            assertTrue(harness.game.state.value.busy)
            export.cancelAndJoin()
            assertFalse(harness.game.state.value.busy)
            assertTrue(harness.library.values.containsKey(key))

            harness.game.delete(key).join()
            assertFalse(harness.library.values.containsKey(key))
        } finally { harness.cancel() }
    }

    @Test fun favoriteAndDeleteFailuresPreserveListAndSelectedReplay() = runBlocking {
        val harness = Harness()
        try {
            val key = harness.firstKey()
            harness.game.select(key).join()
            harness.library.failFavorite = true
            harness.game.setFavorite(key, true).join()
            assertFalse(harness.game.state.value.entries.first { it.key == key }.favorite)
            assertEquals(key, harness.game.state.value.selectedKey)
            harness.library.failDelete = true
            harness.game.delete(key).join()
            assertTrue(harness.library.values.containsKey(key))
            assertEquals(key, harness.game.state.value.selectedKey)
        } finally { harness.cancel() }
    }

    @Test fun confirmedDeleteClosesSelectedReplayButNotOtherArchives() = runBlocking {
        val harness = Harness()
        try {
            val key = harness.firstKey()
            harness.game.select(key).join()
            harness.game.delete(key).join()
            val state = harness.until { !it.busy && it.entries.size == 1 }
            assertNull(state.selectedKey)
            assertNull(state.table)
            assertFalse(harness.library.values.containsKey(key))
            assertTrue(harness.sessions.all { it.closed })
        } finally { harness.cancel() }
    }

    @Test fun parentCancellationClosesNativeAndMarksStateClosed() = runBlocking {
        val harness = Harness()
        harness.game.select(harness.firstKey()).join()
        harness.cancel()
        harness.until { it.closed }
        assertTrue(harness.sessions.all { it.closed })
        assertFalse(harness.game.state.value.playing)
    }

    @Test fun leaveClosesReplayBeforeCallback() = runBlocking {
        val harness = Harness()
        try {
            harness.game.select(harness.firstKey()).join()
            var callbackSawClosed = false
            harness.game.leave { callbackSawClosed = harness.sessions.all { it.closed } }.join()
            assertTrue(callbackSawClosed)
            assertNull(harness.game.state.value.selectedKey)
        } finally { harness.cancel() }
    }
}
