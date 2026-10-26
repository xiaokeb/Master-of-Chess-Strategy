package com.masterofchessstrategy.games.lanzhou_mahjong.data

import com.masterofchessstrategy.data.ActiveGameDao
import com.masterofchessstrategy.data.ActiveGameEntity
import com.masterofchessstrategy.engine.Difficulty
import com.masterofchessstrategy.games.lanzhou_mahjong.LanzhouMahjongAssistanceUsage
import com.masterofchessstrategy.games.lanzhou_mahjong.LanzhouMahjongDecisionPoint
import com.masterofchessstrategy.games.lanzhou_mahjong.LanzhouMahjongSessionRecord
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/** Explicit fake transaction and Native admission only. These checks do not execute SQLite/JNI. */
@OptIn(ExperimentalCoroutinesApi::class)
class RoomLanzhouMahjongAdmittedSessionStoreTest {
    private val pass = LanzhouMahjongSessionAdmission { it.nativeState }

    @Test fun onlyMissingRowIsNullAndSaveRoundTripsTheFullRecordWithoutTouchingOtherGames() = runTest {
        val db = TransactionModel()
        val unrelated = pass.createRow(record(), 4).copy(gameTypeCode = 0)
        db.rows[0] = unrelated
        val store = store(db, StandardTestDispatcher(testScheduler))
        assertNull(store.load())
        val snapshot = LanzhouMahjongStoredSession(record(actions = 1, usage = 2), 123)
        store.save(snapshot)
        val loaded = requireNotNull(store.load())
        assertEquals(123L, loaded.updatedAtEpochMillis)
        assertArrayEquals(snapshot.record.nativeState, loaded.record.nativeState)
        assertEquals(snapshot.record.decisions, loaded.record.decisions)
        assertEquals(2, loaded.record.assistance.undoUsed)
        assertEquals(2, loaded.record.assistance.hintUsed)
        assertEquals(0, db.rows.getValue(3).undoUseCount)
        assertEquals(0, db.rows.getValue(3).hintUseCount)
        assertEquals(unrelated, db.rows[0])
        assertEquals(1, db.transactions)
        assertEquals(0, db.deletes)
    }

    @Test fun corruptedOrOversizedRowsRemainErrorsAndAreRetained() = runTest {
        val original = pass.createRow(record(), 4)
        for (bad in listOf(original.copy(sessionId = "wrong"),
            original.copy(engineState = ByteArray(15_187)), original.copy(engineState = byteArrayOf(1)))) {
            val db = TransactionModel().apply { rows[3] = bad }
            expectFailure { store(db, StandardTestDispatcher(testScheduler)).load() }
            assertSame(bad, db.rows[3])
            assertEquals(0, db.transactions)
            assertEquals(0, db.deletes)
        }
    }

    @Test fun loadDatabaseFailuresNeverBecomeMissing() = runTest {
        val db = TransactionModel().apply { findFailure = IOException("query failed") }
        expectFailure { store(db, StandardTestDispatcher(testScheduler)).load() }
        assertEquals(0, db.transactions)
    }

    @Test fun saveNativeAdmissionRunsInsideTheTransactionAndRejectionDoesNotWrite() = runTest {
        val db = TransactionModel().apply { rows[3] = pass.createRow(record(), 4) }
        val before = db.rows.getValue(3).owned()
        val reject = LanzhouMahjongSessionAdmission {
            assertTrue(db.inTransaction)
            throw IOException("fake native history rejection")
        }
        expectFailure { store(db, StandardTestDispatcher(testScheduler), admission = reject)
            .save(LanzhouMahjongStoredSession(record(), 5)) }
        assertEquals(before, db.rows[3])
        assertEquals(0, db.attemptedWrites.size)
        assertEquals(1, db.rollbacks)
    }

    @Test fun readBackMismatchRollsBackEvenWhenTheUpsertReturned() = runTest {
        val db = TransactionModel().apply { rows[3] = pass.createRow(record(), 4) }
        val before = db.rows.getValue(3).owned()
        db.afterUpsert = { db.rows[3] = requireNotNull(db.rows[3]).copy(acceptedMoveCount = 999) }
        expectFailure { store(db, StandardTestDispatcher(testScheduler)).save(LanzhouMahjongStoredSession(record(), 5)) }
        assertEquals(before, db.rows[3])
        assertEquals(1, db.rollbacks)
    }

    @Test fun databaseFailureDuringReadBackRollsBackTheNewRow() = runTest {
        val db = TransactionModel().apply { rows[3] = pass.createRow(record(), 4) }
        val before = db.rows.getValue(3).owned()
        db.afterUpsert = { db.findFailure = IOException("read-back unavailable") }
        expectFailure { store(db, StandardTestDispatcher(testScheduler)).save(LanzhouMahjongStoredSession(record(), 5)) }
        assertEquals(before, db.rows[3])
        assertEquals(1, db.rollbacks)
    }

    @Test fun aWriteFailureAfterMutationStillRollsBack() = runTest {
        val db = TransactionModel().apply { rows[3] = pass.createRow(record(), 4) }
        val before = db.rows.getValue(3).owned()
        db.afterUpsert = { throw IOException("fake storage failed after mutation") }
        expectFailure { store(db, StandardTestDispatcher(testScheduler)).save(LanzhouMahjongStoredSession(record(), 5)) }
        assertEquals(before, db.rows[3])
    }

    @Test fun cancellationDuringUpsertRollsBackAndDoesNotAcknowledgeTheSnapshot() = runTest {
        val db = TransactionModel().apply { rows[3] = pass.createRow(record(), 4) }
        val before = db.rows.getValue(3).owned()
        val entered = CompletableDeferred<Unit>()
        db.afterUpsert = { entered.complete(Unit); awaitCancellation() }
        val store = store(db, StandardTestDispatcher(testScheduler))
        var acknowledged = false
        val write = launch { store.save(LanzhouMahjongStoredSession(record(), 5)); acknowledged = true }
        entered.await()
        write.cancelAndJoin()
        assertFalse(acknowledged)
        assertEquals(before, db.rows[3])
        assertEquals(1, db.rollbacks)
    }

    @Test fun cancellationDuringSynchronousAdmissionIsCheckedBeforeAnyUpsert() = runTest {
        val db = TransactionModel().apply { rows[3] = pass.createRow(record(), 4) }
        val before = db.rows.getValue(3).owned()
        var writeJob: Job? = null
        val cancel = LanzhouMahjongSessionAdmission { candidate ->
            assertTrue(db.inTransaction)
            requireNotNull(writeJob).cancel()
            candidate.nativeState
        }
        val store = store(db, StandardTestDispatcher(testScheduler), admission = cancel)
        var acknowledged = false
        val write = launch {
            writeJob = currentCoroutineContext()[Job]
            store.save(LanzhouMahjongStoredSession(record(), 5)); acknowledged = true
        }
        write.join()
        assertFalse(acknowledged)
        assertEquals(before, db.rows[3])
        assertEquals(0, db.attemptedWrites.size)
        assertEquals(1, db.rollbacks)
    }

    @Test fun failedSaveRetriesExactlyTheSameCallerTimeIdAndLzsrPayload() = runTest {
        val db = TransactionModel()
        val store = store(db, StandardTestDispatcher(testScheduler))
        val snapshot = LanzhouMahjongStoredSession(record(actions = 1, usage = 2), 123)
        db.afterUpsert = { throw IOException("first commit fails") }
        expectFailure { store.save(snapshot) }
        assertNull(db.rows[3])
        db.afterUpsert = null
        store.save(snapshot)
        assertEquals(2, db.attemptedWrites.size)
        assertEquals(db.attemptedWrites[0], db.attemptedWrites[1])
        assertEquals(123L, requireNotNull(store.load()).updatedAtEpochMillis)
        assertEquals(69, db.rows.getValue(3).sessionId.length)
    }

    @Test fun replacementRetiresTheSameStoreWithoutRefreshingItsOwner() = runTest {
        val db = TransactionModel()
        val gate = LanzhouMahjongStorageEpochGate()
        val store = store(db, StandardTestDispatcher(testScheduler), gate)
        gate.exclusive(true) { }
        expectFailure { store.load() }
        expectFailure { store.save(LanzhouMahjongStoredSession(record(), 4)) }
        assertEquals(0, db.queries)
        assertEquals(0, db.transactions)
        assertEquals(0, db.deletes)
    }

    @Test fun oldOuterV1LoadsAndRewritesOuterV2WithoutChangingNativeV1OrTimestamp() = runTest {
        val db = TransactionModel()
        val original = pass.createRow(record(rule = 1), 44)
        val legacy = original.engineState.copyOf(174).also { it[4] = 1 }
        ByteBuffer.wrap(legacy).order(ByteOrder.LITTLE_ENDIAN).putInt(170,
            CRC32().apply { update(legacy, 0, 170) }.value.toInt())
        db.rows[3] = original.copy(engineFormatVersion = 1, engineState = legacy)
        val store = store(db, StandardTestDispatcher(testScheduler))
        val loaded = requireNotNull(store.load())
        assertArrayEquals(legacy, db.rows.getValue(3).engineState)
        store.save(loaded)
        val updated = db.rows.getValue(3)
        assertEquals(original.sessionId, updated.sessionId)
        assertEquals(44L, updated.updatedAtEpochMillis)
        assertEquals(2, updated.engineFormatVersion)
        assertEquals(1, requireNotNull(store.load()).record.nativeState[4].toInt())
    }

    private fun store(db: TransactionModel, dispatcher: CoroutineDispatcher,
        gate: LanzhouMahjongStorageEpochGate = LanzhouMahjongStorageEpochGate(),
        admission: LanzhouMahjongSessionAdmission = pass) =
        RoomLanzhouMahjongAdmittedSessionStore(db, gate, gate.captureOwner(), admission, dispatcher, db::transaction)

    private suspend fun expectFailure(block: suspend () -> Any?) {
        try { block(); fail("expected storage/admission failure") } catch (_: IOException) { }
    }

    private fun record(rule: Int = 2, actions: Int = 0, usage: Int = 0): LanzhouMahjongSessionRecord {
        // Structurally valid fake envelope only; pass canonicalizer does not prove legal native actions.
        val bytes = ByteArray(130 + actions * 3)
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put(byteArrayOf(76, 90, 77, 74, rule.toByte(), 0)); buffer.putLong(1)
        repeat(108) { buffer.put((it / 4).toByte()) }
        buffer.putInt(actions)
        repeat(actions) { buffer.put(byteArrayOf(1, 0, 0)) }
        var hash = 0x811c9dc5.toInt()
        for (index in 0 until bytes.size - 4) hash = (hash xor (bytes[index].toInt() and 255)) * 16777619
        buffer.putInt(hash)
        return LanzhouMahjongSessionRecord(Difficulty.EASY, 0, 7, 0, 1, 19, bytes,
            LanzhouMahjongAssistanceUsage(Difficulty.EASY, usage, usage),
            if (actions > 0) listOf(LanzhouMahjongDecisionPoint(0, 55)) else emptyList())
    }

    private fun ActiveGameEntity.owned() = copy(engineState = engineState.copyOf())

    /** Rollback model only. Real Room cancellation/SQLite semantics require Root's device tests. */
    private class TransactionModel : ActiveGameDao {
        val rows = mutableMapOf<Int, ActiveGameEntity>()
        val attemptedWrites = mutableListOf<ActiveGameEntity>()
        var transactions = 0
        var rollbacks = 0
        var queries = 0
        var deletes = 0
        var inTransaction = false
        var findFailure: IOException? = null
        var afterUpsert: (suspend () -> Unit)? = null

        override suspend fun find(gameTypeCode: Int): ActiveGameEntity? {
            queries++
            findFailure?.let { throw it }
            return rows[gameTypeCode]
        }
        override suspend fun upsert(entity: ActiveGameEntity) {
            check(inTransaction)
            attemptedWrites += entity.copy(engineState = entity.engineState.copyOf())
            rows[entity.gameTypeCode] = entity.copy(engineState = entity.engineState.copyOf())
            afterUpsert?.invoke()
        }
        override suspend fun delete(gameTypeCode: Int) { deletes++; rows.remove(gameTypeCode) }
        override suspend fun deleteAll() { deletes++; rows.clear() }

        suspend fun transaction(block: suspend () -> Unit) {
            check(!inTransaction)
            transactions++
            val before = rows.mapValues { (_, value) -> value.copy(engineState = value.engineState.copyOf()) }
            inTransaction = true
            try { block() } catch (failure: Throwable) {
                rows.clear(); rows.putAll(before); rollbacks++; throw failure
            } finally { inTransaction = false }
        }
    }
}
