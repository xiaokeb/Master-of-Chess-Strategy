package com.masterofchessstrategy.games.lanzhou_fangqi.data

import com.masterofchessstrategy.data.ActiveGameDao
import com.masterofchessstrategy.data.ActiveGameEntity
import com.masterofchessstrategy.engine.Difficulty
import com.masterofchessstrategy.games.lanzhou_fangqi.FangqiStoredMode
import com.masterofchessstrategy.games.lanzhou_fangqi.LanzhouFangqiAutosave
import com.masterofchessstrategy.games.lanzhou_fangqi.data.FangqiSessionAdmissionTest.Companion.history
import com.masterofchessstrategy.games.lanzhou_fangqi.data.FangqiSessionAdmissionTest.Companion.stored
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/** Fault-injection transaction model only; real Room/Native tests live in androidTest. */
@OptIn(ExperimentalCoroutinesApi::class)
class RoomFangqiSessionStoreTest {
    @Test fun roundTripsAllModesAndDifficultyCodesWithoutMutatingAnotherGame() = runTest {
        val db = TransactionModel()
        db.rows[0] = row().copy(gameTypeCode = 0, engineFormatVersion = 2)
        val other = db.rows.getValue(0).owned()
        val store = store(db, StandardTestDispatcher(testScheduler))
        assertNull(store.load())
        val variants = listOf(FangqiStoredMode.LOCAL_TWO_PLAYER to null) +
            listOf(FangqiStoredMode.HUMAN_VS_AI, FangqiStoredMode.AI_AUTO_PLAY).flatMap { mode ->
                Difficulty.entries.map { mode to it }
            }
        variants.forEach { (mode, difficulty) ->
            val snapshot = stored(history(16_384), mode, difficulty)
            store.save(snapshot)
            val loaded = requireNotNull(store.load())
            assertEquals(mode, loaded.mode)
            assertEquals(difficulty, loaded.difficulty)
            assertEquals(snapshot.sessionId, loaded.sessionId)
            assertEquals(snapshot.updatedAtEpochMillis, loaded.updatedAtEpochMillis)
            assertEquals(16_384, loaded.acceptedActionCount)
            assertEquals(Int.MAX_VALUE, loaded.hintUseCount)
            assertEquals(Int.MAX_VALUE, loaded.undoUseCount)
            assertArrayEquals(snapshot.engineState, loaded.engineState)
            loaded.engineState.fill(0)
            assertArrayEquals(snapshot.engineState, requireNotNull(store.load()).engineState)
        }
        assertEquals(other, db.rows[0])
    }

    @Test fun badRowsAreErrorsAndRetainedRatherThanMissingOrSilentlyOverwritten() = runTest {
        val original = row()
        val invalid = listOf(
            original.copy(gameTypeCode = 5), original.copy(modeCode = 9), original.copy(difficultyCode = 0),
            original.copy(modeCode = 1, difficultyCode = null), original.copy(modeCode = 1, difficultyCode = 9),
            original.copy(envelopeVersion = 4), original.copy(engineFormatVersion = 2),
            original.copy(sessionId = ""), original.copy(sessionId = "x".repeat(65)),
            original.copy(updatedAtEpochMillis = -1), original.copy(acceptedMoveCount = -1),
            original.copy(acceptedMoveCount = 16_385), original.copy(undoUseCount = -1), original.copy(hintUseCount = -1),
            original.copy(resultOverrideCode = 0), original.copy(timeControlMinutes = 5),
            original.copy(redRemainingMillis = 0), original.copy(blackRemainingMillis = 0),
            original.copy(turnStartedAtEpochMillis = 0), original.copy(pendingDrawOfferSideCode = 0),
            original.copy(autoPlayPaused = true), original.copy(autoPlaySpeedPermille = 999),
            original.copy(completedAutoGames = 1), original.copy(sessionVariantId = "custom"), original.copy(playerIndex = 1),
            original.copy(engineState = byteArrayOf(1)),
        )
        invalid.forEach { corrupt ->
            val db = TransactionModel().apply { rows[4] = corrupt.owned() }
            expectFailure { store(db, StandardTestDispatcher(testScheduler)).load() }
            assertEquals(corrupt, db.rows[4])
            assertEquals(0, db.transactions)
        }
    }

    @Test fun nativeRejectionBeforeWriteRetainsThePriorRow() = runTest {
        val db = TransactionModel().apply { rows[4] = row() }
        val before = db.rows[4]!!.owned()
        val store = store(db, StandardTestDispatcher(testScheduler), admission = FangqiSessionAdmission {
            throw IOException("Native rejected")
        })
        expectFailure { store.save(stored(history(1))) }
        expectFailure { store.load() }
        assertEquals(before, db.rows[4])
        assertEquals(0, db.transactions)
    }

    @Test fun upsertReadbackAndCommitFailuresRollBackAndPropagate() = runTest {
        for (failure in listOf("upsert", "readback", "mismatch", "commit")) {
            val db = TransactionModel().apply { rows[4] = row(); fault = failure }
            val before = db.rows[4]!!.owned()
            expectFailure { store(db, StandardTestDispatcher(testScheduler)).save(stored(history(1))) }
            assertEquals(failure, before, db.rows[4])
            assertEquals(failure, 1, db.transactions)
        }
    }

    @Test fun cancellationAfterUpsertBeforeReadbackCompletionRollsBack() = runTest {
        val db = TransactionModel().apply { rows[4] = row(); fault = "cancel-readback" }
        val before = db.rows[4]!!.owned()
        val store = store(db, StandardTestDispatcher(testScheduler))
        val save = launch { store.save(stored(history(1))) }
        db.readbackEntered.await()
        save.cancelAndJoin()
        assertEquals(before, db.rows[4])
    }

    @Test fun acknowledgementWaitsForTransactionCommit() = runTest {
        val db = TransactionModel().apply { commitRelease = CompletableDeferred() }
        val store = store(db, StandardTestDispatcher(testScheduler))
        val queue = LanzhouFangqiAutosave(store, this)
        val snapshot = stored(history(1))
        val revision = queue.enqueue(snapshot)
        db.commitEntered.await()
        assertEquals(0L, queue.progress.value.savedRevision)
        db.commitRelease!!.complete(Unit)
        assertTrue(queue.awaitSaved(revision))
        assertArrayEquals(snapshot.engineState, requireNotNull(store.load()).engineState)
        queue.closeAndJoin()
    }

    @Test fun failedSaveRetriesTheSameRevisionAndImmutableSnapshotAfterRollback() = runTest {
        val db = TransactionModel().apply { rows[4] = row(); fault = "readback" }
        val before = db.rows[4]!!.owned()
        val store = store(db, StandardTestDispatcher(testScheduler))
        val queue = LanzhouFangqiAutosave(store, this)
        val snapshot = stored(history(3))
        val revision = queue.enqueue(snapshot)
        assertFalse(queue.awaitSaved(revision))
        assertEquals(before, db.rows[4])
        assertEquals(0L, queue.progress.value.savedRevision)
        db.fault = null
        assertEquals(revision, queue.retryLatest())
        assertTrue(queue.awaitSaved(revision))
        val restored = requireNotNull(store.load())
        assertEquals(snapshot.sessionId, restored.sessionId)
        assertEquals(snapshot.updatedAtEpochMillis, restored.updatedAtEpochMillis)
        assertArrayEquals(snapshot.engineState, restored.engineState)
        queue.closeAndJoin()
    }

    @Test fun staleOwnersCannotReadOrWriteAfterRestoreAndNewOwnerCanContinue() = runTest {
        val db = TransactionModel()
        val gate = FangqiStorageEpochGate()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val old = store(db, dispatcher, gate)
        old.save(stored())
        gate.exclusive(true) { db.rows.clear() }
        expectFailure { old.load() }
        expectFailure { old.save(stored(history(1))) }
        assertNull(db.rows[4])
        val fresh = store(db, dispatcher, gate)
        fresh.save(stored(history(2)))
        assertArrayEquals(history(2), requireNotNull(fresh.load()).engineState)
    }

    private fun store(db: TransactionModel, dispatcher: kotlinx.coroutines.CoroutineDispatcher,
        gate: FangqiStorageEpochGate = FangqiStorageEpochGate(),
        admission: FangqiSessionAdmission = FangqiSessionAdmission { it }) = RoomFangqiSessionStore(
        db, gate, gate.captureOwner(), admission, dispatcher, { db.transaction(it) })

    private suspend fun expectFailure(block: suspend () -> Unit) {
        try { block(); fail("Expected IOException") } catch (_: IOException) { }
    }

    private class TransactionModel : ActiveGameDao {
        val rows = mutableMapOf<Int, ActiveGameEntity>()
        var fault: String? = null
        var transactions = 0
        private var writing = false
        val readbackEntered = CompletableDeferred<Unit>()
        val commitEntered = CompletableDeferred<Unit>()
        var commitRelease: CompletableDeferred<Unit>? = null
        override suspend fun find(gameTypeCode: Int): ActiveGameEntity? {
            if (writing) when (fault) {
                "readback" -> throw IOException("readback failure")
                "mismatch" -> return rows[gameTypeCode]?.copy(sessionId = "mismatch")
                "cancel-readback" -> { readbackEntered.complete(Unit); awaitCancellation() }
            }
            return rows[gameTypeCode]?.owned()
        }
        override suspend fun upsert(entity: ActiveGameEntity) {
            rows[entity.gameTypeCode] = entity.owned()
            writing = true
            if (fault == "upsert") throw IOException("upsert failure")
        }
        override suspend fun delete(gameTypeCode: Int) { rows.remove(gameTypeCode) }
        override suspend fun deleteAll() { rows.clear() }
        suspend fun transaction(block: suspend () -> Unit) {
            transactions++
            val before = rows.mapValues { it.value.owned() }
            try {
                block()
                commitEntered.complete(Unit)
                commitRelease?.await()
                if (fault == "commit") throw IOException("commit failure")
            } catch (failure: Throwable) {
                rows.clear(); rows.putAll(before)
                throw failure
            } finally { writing = false }
        }
    }

    companion object {
        private fun ActiveGameEntity.owned() = copy(engineState = engineState.copyOf())
        private fun row() = ActiveGameEntity(4, 0, null, 5, 1, history(), 123L, "retained", 0)
    }
}
