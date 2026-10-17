package com.masterofchessstrategy.games.go.data

import com.masterofchessstrategy.data.GameRecord
import com.masterofchessstrategy.data.StoredGameMode
import com.masterofchessstrategy.engine.GameResult
import com.masterofchessstrategy.engine.GameType
import com.masterofchessstrategy.engine.GoPhase
import com.masterofchessstrategy.games.go.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

/** Transaction and ownership doubles only; Native legality and Room rollback have device tests. */
@OptIn(ExperimentalCoroutinesApi::class)
class RoomGoSessionRepositoryTest {
    private val store = MemoryStore()
    private var now = 10L
    private fun repository(legacy: GoLocalLegacySource? = null,
        gate: (GoSavedSession) -> GoAdmittedLocalSession = ::admitDouble) = RoomGoSessionRepository(
        store, legacy, gate, Dispatchers.Unconfined, clock = { now },
    )

    @Test fun ongoingCheckpointHasStableIdentityAndNoCompletedOrRankedSideEffects() = runTest {
        val saved = session()
        val repository = repository()
        repository.save(saved)
        assertTrue(store.handled)
        assertTrue(store.records.isEmpty())
        assertTrue(store.sources.isEmpty())
        val loaded = repository.load()!!
        assertEquals(saved.identity, loaded.identity)
        assertArrayEquals(saved.engineState, loaded.engineState)
        loaded.engineState[0] = 0
        saved.engineState[0] = 0
        assertEquals('M'.code.toByte(), repository.load()!!.engineState[0])
    }

    @Test fun terminalCheckpointRecordAndSourceAreAtomicAndIdempotent() = runTest {
        val saved = session(terminal = true)
        val repository = repository()
        repository.save(saved)
        val first = store.records.getValue(saved.identity.recordId)
        assertEquals(GameType.GO, first.gameType)
        assertEquals(StoredGameMode.LOCAL_TWO_PLAYER, first.mode)
        assertNull(first.difficulty)
        assertFalse(first.isEndgame)
        assertEquals(3, first.engineFormatVersion)
        assertEquals(saved.identity, store.sources[first.recordId])
        store.records[first.recordId] = first.copy(isFavorite = true)
        now = 20
        repository.save(saved.copy(displayMode = GoScoreDisplayMode.POINTS))
        val repeated = store.records.getValue(first.recordId)
        assertEquals(10L, repeated.completedAtEpochMillis)
        assertTrue(repeated.isFavorite)
        assertEquals(1, store.records.size)
        assertEquals(saved.identity, repository.load()!!.identity)
    }

    @Test fun terminalHistoryConflictRollsBackWithoutOverwritingFavoriteOrCheckpoint() = runTest {
        val saved = session(terminal = true)
        val repository = repository()
        repository.save(saved)
        val before = store.active!!.copyOf()
        val conflict = saved.copy(engineState = saved.engineState.copyOf().apply { this[8] = 2 })
        expectFailure { repository.save(conflict) }
        assertArrayEquals(before, store.active)
        assertEquals(GameResult.FIRST_PLAYER_WIN, store.records.getValue(saved.identity.recordId).result)
    }

    @Test fun terminalUndoMustUseAnotherBranchAndNeverErasesTheOldRecord() = runTest {
        val saved = session(terminal = true)
        val repository = repository()
        repository.save(saved)
        val ongoing = saved.copy(engineState = bytes(0))
        expectFailure { repository.save(ongoing) }
        val branch = ongoing.copy(identity = saved.identity.afterTerminalUndo())
        repository.save(branch)
        assertEquals(1, store.records.size)
        repository.save(branch.copy(engineState = bytes(1)))
        assertEquals(2, store.records.size)
        assertEquals(saved.identity, store.sources[saved.identity.recordId])
        expectFailure { repository.save(saved) }
        assertEquals(branch.identity, repository.load()!!.identity)
    }

    @Test fun provenanceCannotBePromotedWithinTheSameSession() = runTest {
        val saved = session().copy(identity = GoSessionIdentity.create(GoSessionOrigin.SGF_IMPORT))
        val repository = repository()
        repository.save(saved)
        expectFailure { repository.save(saved.copy(identity = saved.identity.copy(origin = GoSessionOrigin.LOCAL))) }
        assertEquals(GoSessionOrigin.SGF_IMPORT, repository.load()!!.identity.origin)
    }

    @Test fun everyWriteFailureRollsBackRecordSourceCheckpointAndMigrationMarker() = runTest {
        for (step in listOf("source", "active", "marker")) {
            store.failAt = step
            expectFailure { repository().save(session(terminal = true)) }
            assertNull(store.active)
            assertFalse(store.handled)
            assertTrue(store.records.isEmpty())
            assertTrue(store.sources.isEmpty())
        }
    }

    @Test fun successfulLegacyMigrationRetainsIdentitySourceAndOriginalBytes() = runTest {
        val saved = session(terminal = true).copy(identity = GoSessionIdentity.fromLegacy(byteArrayOf(4, 5)))
        val legacy = Legacy(saved)
        val original = saved.engineState.copyOf()
        val repository = repository(legacy)
        assertEquals(saved.identity, repository.load()!!.identity)
        assertTrue(store.handled)
        assertEquals(1, store.records.size)
        assertEquals(saved.identity, store.sources[saved.identity.recordId])
        assertEquals(saved.identity, repository.load()!!.identity)
        assertEquals(1, legacy.loads)
        assertArrayEquals(original, legacy.saved!!.engineState)
        assertEquals(0, legacy.writes)
        assertEquals(0, legacy.clears)
    }

    @Test fun invalidLegacyAdmissionDoesNotSealOrEraseTheRecoverableSource() = runTest {
        val legacy = Legacy(session())
        expectFailure { repository(legacy) { throw IllegalArgumentException("bad native history") }.load() }
        assertFalse(store.handled)
        assertNull(store.active)
        assertTrue(store.records.isEmpty())
        assertNotNull(legacy.saved)
        assertNotNull(repository(legacy).load())
        assertEquals(2, legacy.loads)
    }

    @Test fun clearSealsLegacyFallbackButRetainsEveryCompletedRecord() = runTest {
        val saved = session(terminal = true)
        val legacy = Legacy(saved)
        val repository = repository(legacy)
        repository.load()
        repository.clear()
        assertNull(repository.load())
        assertEquals(1, legacy.loads)
        assertEquals(1, store.records.size)
        assertEquals(0, legacy.clears)
        assertNotNull(legacy.saved)
    }

    @Test fun absentLegacyIsSealedAndCannotResurrectFromALaterFile() = runTest {
        val legacy = Legacy(null)
        val repository = repository(legacy)
        assertNull(repository.load())
        legacy.saved = session()
        assertNull(repository.load())
        assertTrue(store.handled)
        assertEquals(1, legacy.loads)
    }

    @Test fun terminalRowRequiresMatchingRecordAndSourceOnRead() = runTest {
        val saved = session(terminal = true)
        val repository = repository()
        repository.save(saved)
        store.sources.clear()
        expectFailure { repository.load() }
        assertNotNull(store.active)
        assertEquals(1, store.records.size)
        expectFailure { repository.save(saved) }
        store.records.clear()
        expectFailure { repository.load() }
        assertNotNull(store.active)
    }

    @Test fun orphanSourceCannotBeSilentlyRepairedByAnUnrelatedSave() = runTest {
        val saved = session(terminal = true)
        store.sources[saved.identity.recordId] = saved.identity
        expectFailure { repository().save(saved) }
        assertNull(store.active)
        assertFalse(store.handled)
        assertEquals(1, store.sources.size)
    }

    @Test fun corruptedRoomEnvelopeIsPreservedAndNeverFallsBackToLegacy() = runTest {
        val saved = session()
        val legacy = Legacy(saved)
        store.active = byteArrayOf(0, 1, 2)
        expectFailure { repository(legacy).load() }
        assertArrayEquals(byteArrayOf(0, 1, 2), store.active)
        assertFalse(store.handled)
        assertEquals(0, legacy.loads)
    }

    @Test fun cancellationRollsBackAnInsertedTerminalRecordAndSource() = runTest {
        val gate = CompletableDeferred<Unit>()
        store.writeGate = gate
        val pending = launch { repository().save(session(terminal = true)) }
        runCurrent()
        assertEquals(1, store.records.size) // The double exposes uncommitted rows to this test only.
        pending.cancel(CancellationException("page closed"))
        pending.join()
        assertNull(store.active)
        assertFalse(store.handled)
        assertTrue(store.records.isEmpty())
        assertTrue(store.sources.isEmpty())
    }

    @Test fun callerMutationWhileTransactionWaitsCannotChangeTheFrozenCheckpoint() = runTest {
        val gate = CompletableDeferred<Unit>()
        store.writeGate = gate
        val saved = session(terminal = true)
        val expected = saved.engineState.copyOf()
        val repository = repository()
        val pending = launch { repository.save(saved) }
        runCurrent()
        saved.engineState.fill(0)
        gate.complete(Unit)
        pending.join()
        assertArrayEquals(expected, repository.load()!!.engineState)
        assertArrayEquals(expected, store.records.getValue(saved.identity.recordId).engineState)
    }

    @Test fun invalidClockRollsBackAllTerminalWrites() = runTest {
        now = -1
        expectFailure { repository().save(session(terminal = true)) }
        assertNull(store.active)
        assertFalse(store.handled)
        assertTrue(store.records.isEmpty())
    }

    @Test fun newGameCannotOverwriteATerminalCheckpointWhoseArchiveIsMissing() = runTest {
        val broken = GoSessionFileCodec.encode(session(terminal = true))
        store.active = broken.copyOf()
        expectFailure { repository().save(session()) }
        assertArrayEquals(broken, store.active)
        assertFalse(store.handled)
    }

    private fun session(terminal: Boolean = false) = GoSavedSession(9, bytes(if (terminal) 1 else 0), GoScoreDisplayMode.STONES)

    private fun bytes(marker: Int) = ByteArray(16).apply {
        "MOCG".toByteArray().copyInto(this)
        this[4] = 3; this[5] = 9; this[6] = 15; this[8] = marker.toByte()
    }

    private fun admitDouble(session: GoSavedSession): GoAdmittedLocalSession {
        val marker = session.engineState[8].toInt()
        return GoAdmittedLocalSession(session, if (marker == 0) GoPhase.PLAYING else GoPhase.FINISHED,
            when (marker) { 0 -> GameResult.ONGOING; 1 -> GameResult.FIRST_PLAYER_WIN; else -> GameResult.SECOND_PLAYER_WIN }, 0)
    }

    private suspend fun expectFailure(block: suspend () -> Unit) {
        try { block(); fail("expected admission/transaction rejection") }
        catch (_: IllegalArgumentException) { } catch (_: IOException) { }
    }

    private class Legacy(var saved: GoSavedSession?) : GoSessionRepository, GoLocalLegacySource {
        var loads = 0; var writes = 0; var clears = 0
        override suspend fun load(): GoSavedSession? { loads++; return saved }
        override suspend fun readCommitted() = load()
        override suspend fun save(session: GoSavedSession) { writes++; saved = session }
        override suspend fun clear() { clears++; saved = null }
    }

    private class MemoryStore : GoLocalSessionStore {
        private val mutex = Mutex()
        var active: ByteArray? = null
        var handled = false
        var failAt: String? = null
        var writeGate: CompletableDeferred<Unit>? = null
        val records = mutableMapOf<String, GameRecord>()
        val sources = mutableMapOf<String, GoSessionIdentity>()

        override suspend fun <T> transaction(block: suspend GoLocalSessionTransaction.() -> T): T = mutex.withLock {
            val oldActive = active?.copyOf()
            val oldHandled = handled
            val oldRecords = records.mapValues { it.value.defensiveCopy() }
            val oldSources = sources.toMap()
            try { block(object : GoLocalSessionTransaction {
                override suspend fun readActive() = active?.copyOf()
                override suspend fun writeActive(envelope: ByteArray, updatedAtEpochMillis: Long) {
                    writeGate?.await()
                    fault("active"); active = envelope.copyOf()
                }
                override suspend fun clearActive() { active = null }
                override suspend fun legacyHandled() = handled
                override suspend fun markLegacyHandled() { fault("marker"); handled = true }
                override suspend fun findRecord(recordId: String) = records[recordId]?.defensiveCopy()
                override suspend fun findSource(recordId: String) = sources[recordId]
                override suspend fun insertRecord(record: GameRecord, source: GoSessionIdentity) {
                    records[record.recordId] = record.defensiveCopy()
                    fault("source"); sources[record.recordId] = source
                }
            }) } catch (failure: Throwable) {
                active = oldActive; handled = oldHandled
                records.clear(); records.putAll(oldRecords)
                sources.clear(); sources.putAll(oldSources)
                throw failure
            }
        }

        private fun fault(step: String) { if (failAt == step) throw IOException("injected $step failure") }
    }
}
