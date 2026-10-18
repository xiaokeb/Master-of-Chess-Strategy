package com.masterofchessstrategy.games.go.data

import com.masterofchessstrategy.engine.*
import com.masterofchessstrategy.games.go.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

/** Rollback store and admission doubles, not Room/Native proof or ranked participation. */
class GoAiCheckpointRepositoryTest {
    private val store = MemoryStore()
    private var now = 10L
    private fun repository(legacy: GoAiLegacySource? = null,
        admission: (GoAiSavedMatch) -> GoAdmittedAiCheckpoint = ::admitDouble) =
        GoAiCheckpointRepository(store, legacy, admission, Dispatchers.Unconfined) { now }
    private fun match(id: String = "one", terminal: Boolean = false, moves: Int = 0,
        automatic: Boolean = false, reserved: Boolean = false, undo: Int = 0, hint: Int = 0,
        human: GoPlayer? = GoPlayer.BLACK, series: Boolean = false, payload: Int = 0) =
        GoAiSavedMatch(id, GoAiMatchConfig(9, humanPlayer = human),
            byteArrayOf(9, if (terminal) 1 else 0, moves.toByte(), payload.toByte()), undo, hint,
            reserved, human ?: GoPlayer.BLACK, automatic || human == null,
            autoContinueEnabled = series, autoContinueMaxGames = if (series) 5 else 1,
            autoContinueGameNumber = if (series) 2 else 1)
    private fun bytes(match: GoAiSavedMatch) = GoAiMatchFileCodec.encode(match)
    private fun recordId(match: GoAiSavedMatch) = admitDouble(match).recordId

    @Test fun ongoingSaveOwnsAllPolicyBytesAndHasNoArchiveOrRewardWriter() = runTest {
        val match = match(automatic = true, undo = 1, hint = 2)
        val repository = repository()
        repository.save(match)
        assertTrue(store.handled)
        assertTrue(store.archives.isEmpty())
        assertEquals(GoAiCheckpointOrigin.UNVERIFIED_SESSION, store.active!!.lineage.origin)
        val loaded = repository.load()!!
        assertArrayEquals(bytes(match), bytes(loaded))
        loaded.copyBytes().fill(0)
        store.active!!.copyBytes().fill(0)
        assertArrayEquals(bytes(match), bytes(repository.load()!!))
    }

    @Test fun terminalArchiveAndCheckpointAreAtomicAndRepeatedSaveKeepsFirstTimestamp() = runTest {
        val match = match(terminal = true, moves = 5)
        val repository = repository()
        repository.save(match)
        val archive = store.archives.getValue(recordId(match))
        assertEquals(10L, archive.completedAtEpochMillis)
        now = 20
        repository.save(match(terminal = true, moves = 5, reserved = true))
        assertSame(archive, store.archives.getValue(recordId(match)))
        assertTrue(repository.load()!!.completionReserved)
        assertEquals(1, store.archives.size)
    }

    @Test fun endingAutomaticSeriesKeepsTheOriginalFullTerminalSource() = runTest {
        val original = match(terminal = true, human = null, series = true)
        val repository = repository()
        repository.save(original)
        repository.save(match(terminal = true, human = null, reserved = true))
        val archived = GoAiMatchFileCodec.decode(store.archives.getValue(recordId(original)).checkpoint.copyBytes())!!
        assertTrue(archived.autoContinueEnabled)
        assertEquals(2, archived.autoContinueGameNumber)
        assertFalse(repository.load()!!.autoContinueEnabled)
    }

    @Test fun terminalUndoNeedsNewIdentityAndPreservesParentAndConservativeLineage() = runTest {
        val original = match(terminal = true, moves = 4)
        val repository = repository()
        repository.save(original)
        failed { repository.save(match(moves = 4, undo = 1)) }
        repository.save(match(id = "child", moves = 4, undo = 1))
        assertEquals(1, store.archives.size)
        assertEquals(GoAiCheckpointLineage(GoAiCheckpointOrigin.TERMINAL_CONTINUATION,
            recordId(original)), store.active!!.lineage)
        repository.save(match(id = "child", terminal = true, moves = 4, undo = 1))
        assertEquals(2, store.archives.size)
        assertArrayEquals(bytes(original), store.archives.getValue(recordId(original)).checkpoint.copyBytes())
    }

    @Test fun aNewInitialPositionAfterTerminalIsNotMislabelledAsAHistoricalContinuation() = runTest {
        val repository = repository()
        repository.save(match(terminal = true))
        repository.save(match(id = "restart"))
        assertEquals(GoAiCheckpointLineage(GoAiCheckpointOrigin.UNVERIFIED_SESSION), store.active!!.lineage)
        assertEquals(1, store.archives.size)
    }

    @Test fun continuationCannotLoadOrWriteWhenItsParentArchiveIsMissingOrOngoing() = runTest {
        val repository = repository()
        val parent = match(terminal = true, moves = 5)
        repository.save(parent)
        repository.save(match(id = "child", moves = 5, undo = 1))
        val childBytes = store.active!!.copyBytes()
        val archive = store.archives.remove(recordId(parent))!!
        failed { repository.load() }
        failed { repository.save(match(id = "child", moves = 6, undo = 1)) }
        assertArrayEquals(childBytes, store.active!!.copyBytes())
        store.archives[archive.recordId] = GoAiCheckpointArchive(archive.recordId,
            GoAiCheckpointEntry(bytes(match(moves = 5)), archive.checkpoint.lineage), 10)
        failed { repository.load() }
    }

    @Test fun terminalCannotEnableOrRenumberASeriesAndEndedSeriesCannotBeReactivated() = runTest {
        val repository = repository()
        val terminal = match(terminal = true, human = null)
        repository.save(terminal)
        failed { repository.save(match(terminal = true, human = null, series = true)) }
        repository.clear()
        val original = match(id = "series", terminal = true, human = null, series = true)
        repository.save(original)
        val renumbered = GoAiSavedMatch(original.matchId, original.config, original.copyBytes(), automaticUsed = true,
            autoContinueEnabled = true, autoContinueMaxGames = 5, autoContinueGameNumber = 3)
        failed { repository.save(renumbered) }
        repository.save(match(id = "series", terminal = true, human = null))
        // Comparison against the first archive alone is insufficient after a series was ended.
        failed { repository.save(original) }
    }

    @Test fun sameIdentityCannotDropAutomaticParticipationOrUsedQuotas() = runTest {
        val repository = repository()
        val original = match(automatic = true, undo = 2, hint = 3)
        repository.save(original)
        for (invalid in listOf(match(undo = 2, hint = 3), match(automatic = true, undo = 1, hint = 3),
            match(automatic = true, undo = 2, hint = 2))) {
            failed { repository.save(invalid) }
            assertArrayEquals(bytes(original), bytes(repository.load()!!))
        }
    }

    @Test fun reservedTerminalCannotBeUnsealedOrRewrittenWithAnotherLegalHistory() = runTest {
        val repository = repository()
        val original = match(terminal = true, reserved = true)
        repository.save(original)
        failed { repository.save(match(terminal = true)) }
        failed { repository.save(match(terminal = true, reserved = true, payload = 1)) }
        assertArrayEquals(bytes(original), bytes(repository.load()!!))
    }

    @Test fun damagedOrMissingOldArchiveCannotBeHiddenByWritingANewMatch() = runTest {
        val original = match(terminal = true)
        val repository = repository()
        repository.save(original)
        store.archives.clear()
        failed { repository.save(match(id = "new")) }
        assertArrayEquals(bytes(original), store.active!!.copyBytes())
    }

    @Test fun eachStoreFailureRollsBackTheCheckpointArchiveAndPermanentSeal() = runTest {
        for (step in listOf("archive", "active", "marker")) {
            store.failAt = step
            failed { repository().save(match(terminal = true)) }
            assertNull(store.active)
            assertFalse(store.handled)
            assertTrue(store.archives.isEmpty())
        }
    }

    @Test fun cancellationAfterEachWriteRollsBackArchiveActiveAndSealTogether() = runTest {
        for (step in listOf("archive", "active", "marker")) {
            val cancellation = CancellationException("cancel after $step")
            store.failAt = step
            store.failure = cancellation
            try { repository().save(match(terminal = true)); fail("Cancellation swallowed") }
            catch (actual: CancellationException) { assertSame(cancellation, actual) }
            assertNull(store.active)
            assertFalse(store.handled)
            assertTrue(store.archives.isEmpty())
        }
    }

    @Test fun nativeRejectionDoesNotMigrateOrSealAnyLegacyData() = runTest {
        var reads = 0
        val repository = repository(GoAiLegacySource { reads++; match() }) { throw IOException("Native reject") }
        failed { repository.save(match()) }
        assertEquals(0, reads)
        assertNull(store.active)
        assertFalse(store.handled)
    }

    @Test fun successfulLegacyImportIsOnceOnlyLosslessAndExplicitlyUnverified() = runTest {
        val legacy = match(terminal = true, automatic = true, undo = 3, hint = 4)
        var reads = 0
        val repository = repository(GoAiLegacySource { reads++; legacy })
        assertArrayEquals(bytes(legacy), bytes(repository.load()!!))
        assertArrayEquals(bytes(legacy), bytes(repository.load()!!))
        assertEquals(1, reads)
        assertEquals(GoAiCheckpointOrigin.LEGACY_UNVERIFIED, store.active!!.lineage.origin)
        assertEquals(1, store.archives.size)
    }

    @Test fun firstDirectSaveArchivesOldLegacyTerminalInTheSameTransaction() = runTest {
        val legacy = match(id = "old", terminal = true)
        repository(GoAiLegacySource { legacy }).save(match(id = "new"))
        assertEquals(1, store.archives.size)
        assertArrayEquals(bytes(legacy), store.archives.getValue(recordId(legacy)).checkpoint.copyBytes())
        assertEquals("new", GoAiMatchFileCodec.decode(store.active!!.copyBytes())!!.matchId)
    }

    @Test fun failedDirectSaveRollsBackEvenThePrecedingLegacyImport() = runTest {
        val legacy = match(id = "old", terminal = true)
        store.failOnActiveWriteNumber = 2
        failed { repository(GoAiLegacySource { legacy }).save(match(id = "new")) }
        assertNull(store.active)
        assertTrue(store.archives.isEmpty())
        assertFalse(store.handled)
    }

    @Test fun corruptLegacyAndCancellationLeaveNoSuccessfulSeal() = runTest {
        for (problem in listOf(IOException("bad file"), CancellationException("cancel migration"))) {
            try {
                repository(GoAiLegacySource { throw problem }).load()
                fail("Legacy failure swallowed")
            } catch (actual: Exception) { assertSame(problem, actual) }
            assertFalse(store.handled)
            assertNull(store.active)
        }
    }

    @Test fun clearSealsAbsentLegacyWithoutErasingAnyCompletedParent() = runTest {
        val repository = repository()
        repository.save(match(terminal = true))
        repository.clear()
        assertNull(repository.load())
        assertEquals(1, store.archives.size)
        assertTrue(store.handled)
        var reads = 0
        assertNull(repository(GoAiLegacySource { reads++; match() }).load())
        assertEquals(0, reads)
    }

    private suspend fun failed(block: suspend () -> Any?) {
        try { block(); fail("Invalid transaction accepted") } catch (_: IllegalArgumentException) { }
        catch (_: IOException) { }
    }

    private fun admitDouble(match: GoAiSavedMatch): GoAdmittedAiCheckpoint {
        GoAiMatchFileCodec.encode(match)
        val bytes = match.copyBytes()
        val terminal = bytes[1] == 1.toByte()
        val moves = bytes[2].toInt() and 255
        return GoAdmittedAiCheckpoint(match, if (terminal) GoPhase.FINISHED else GoPhase.PLAYING,
            if (terminal) GameResult.FIRST_PLAYER_WIN else GameResult.ONGOING, moves,
            moves + if (terminal) 2 else 0, 0)
    }

    private class MemoryStore : GoAiCheckpointStore {
        var active: GoAiCheckpointEntry? = null
        var handled = false
        var archives = linkedMapOf<String, GoAiCheckpointArchive>()
        var failAt: String? = null
        var failure: Exception? = null
        var failOnActiveWriteNumber = -1
        private var activeWrites = 0
        private val mutex = Mutex()
        override suspend fun <T> transaction(block: suspend GoAiCheckpointTransaction.() -> T): T = mutex.withLock {
            val old = Triple(active, handled, LinkedHashMap(archives))
            try { block(object : GoAiCheckpointTransaction {
                override suspend fun readActive() = active
                override suspend fun writeActive(entry: GoAiCheckpointEntry, updatedAtEpochMillis: Long) {
                    active = entry
                    activeWrites++
                    if (activeWrites == failOnActiveWriteNumber) throw IOException("active #$activeWrites")
                    fault("active")
                }
                override suspend fun clearActive() { active = null }
                override suspend fun legacyHandled() = handled
                override suspend fun markLegacyHandled() { handled = true; fault("marker") }
                override suspend fun findArchive(recordId: String) = archives[recordId]
                override suspend fun insertArchive(archive: GoAiCheckpointArchive) {
                    check(archives.putIfAbsent(archive.recordId, archive) == null)
                    fault("archive")
                }
            }) } catch (failure: Throwable) {
                active = old.first; handled = old.second; archives = old.third
                throw failure
            }
        }
        private fun fault(step: String) { if (step == failAt) throw failure ?: IOException(step) }
    }
}
