package com.masterofchessstrategy.games.lanzhou_fangqi.data

import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/** Explicit fake DAO/transaction/verifier tests, not actual SQLite or JNI evidence. */
@OptIn(ExperimentalCoroutinesApi::class)
class FangqiLearningStoreTest {
    @Test fun emptyLoadDoesNotCreateRowsOrCompleteTutorial() = runTest {
        val db = Model()
        val loaded = store(db, StandardTestDispatcher(testScheduler)).load()
        assertTrue(loaded.rows.isEmpty())
        assertFalse(loaded.tutorialComplete)
        assertEquals(0, db.transactions)
        assertEquals(0, db.inserts)
    }

    @Test fun lessonAcknowledgementsMergeAndRepeatKeepsFirstTime() = runTest {
        val db = Model()
        var now = 10L
        val store = store(db, StandardTestDispatcher(testScheduler), clock = { now })
        assertEquals(10L, store.acknowledgeLesson("opening").rows.single().completedAtEpochMillis)
        now = 20L
        store.acknowledgeLesson("step")
        now = 30L
        val again = store.acknowledgeLesson("opening")
        assertEquals(10L, again.rows.single { it.contentKey == "lesson:opening" }.completedAtEpochMillis)
        assertEquals(setOf("opening", "step"), again.completedLessons)
        assertEquals(2, db.inserts)
        assertEquals(3, db.commits)
    }

    @Test fun sixExplicitLessonsCompleteTutorialAndPracticeDoesNotReplaceAnyLesson() = runTest {
        val db = Model()
        val store = store(db, StandardTestDispatcher(testScheduler))
        FangqiLearningCatalog.practiceIds.forEach { store.completePractice(it, byteArrayOf(7)) }
        assertFalse(store.load().tutorialComplete)
        FangqiLearningCatalog.lessonIds.forEachIndexed { index, id ->
            assertEquals(index == 5, store.acknowledgeLesson(id).tutorialComplete)
        }
        assertEquals(9, store.load().rows.size)
    }

    @Test fun badRowsAndFutureVersionsAreRetainedAndNeverOverwritten() = runTest {
        val invalid = listOf(
            row().copy(contentKey = "lesson:xiangqi-opening"),
            row().copy(contentKey = "practice:opening"),
            row().copy(contentVersion = 0), row().copy(contentVersion = 2),
            row().copy(completedAtEpochMillis = -1),
        ).map { listOf(it) } + listOf(listOf(row(), row()), List(10) { row() })
        invalid.forEach { rows ->
            val db = Model().apply { this.rows.addAll(rows) }
            val store = store(db, StandardTestDispatcher(testScheduler))
            failure { store.load() }
            failure { store.acknowledgeLesson("step") }
            assertEquals(rows, db.rows)
            assertEquals(0, db.inserts)
        }
    }

    @Test fun snapshotOwnsItsCollectionsAndRejectsMutation() {
        val input = mutableListOf(row())
        val snapshot = FangqiLearningSnapshot(input)
        input.clear()
        assertEquals(1, snapshot.rows.size)
        try { (snapshot.rows as MutableList).clear(); fail("mutable rows") } catch (_: UnsupportedOperationException) {}
        try { (snapshot.completedLessons as MutableSet).clear(); fail("mutable IDs") } catch (_: UnsupportedOperationException) {}
    }

    @Test fun invalidIdsAndNegativeClockFailWithoutInserting() = runTest {
        val db = Model()
        val store = store(db, StandardTestDispatcher(testScheduler), clock = { -1 })
        failure { store.acknowledgeLesson("opening") }
        try { store.acknowledgeLesson("not-a-lesson"); fail("unknown lesson") } catch (_: IllegalArgumentException) {}
        try { store.completePractice("opening", byteArrayOf(1)); fail("unknown practice") } catch (_: IllegalArgumentException) {}
        assertTrue(db.rows.isEmpty())
        assertEquals(0, db.inserts)
    }

    @Test fun failedNativeAdmissionCannotCreateOrReplaceProgress() = runTest {
        val db = Model().apply { rows.add(row()) }
        val verifier = FangqiLearningCompletionVerifier { _, _ -> throw IOException("fake Native rejection") }
        failure { store(db, StandardTestDispatcher(testScheduler), verifier = verifier)
            .completePractice(FangqiLearningCatalog.practiceIds.first(), byteArrayOf(9)) }
        assertEquals(listOf(row()), db.rows)
        assertEquals(0, db.transactions)
    }

    @Test fun oversizedHistoryIsRejectedBeforeVerifierOrStorageWork() = runTest {
        val db = Model()
        var validations = 0
        val store = store(db, StandardTestDispatcher(testScheduler),
            verifier = FangqiLearningCompletionVerifier { _, _ -> validations++ })
        failure { store.completePractice(FangqiLearningCatalog.practiceIds.first(), ByteArray(1_048_577)) }
        assertEquals(0, validations)
        assertEquals(0, db.transactions)
        assertTrue(db.rows.isEmpty())
    }

    @Test fun insertReadbackMismatchAndCommitFailuresAllRollBack() = runTest {
        for (fault in listOf("insert", "readback", "mismatch", "commit")) {
            val db = Model().apply { rows.add(row()); this.fault = fault }
            failure { store(db, StandardTestDispatcher(testScheduler)).acknowledgeLesson("step") }
            assertEquals(fault, listOf(row()), db.rows)
            assertEquals(fault, 0, db.commits)
        }
    }

    @Test fun cancellationDuringReadbackRollsBackInsertedRow() = runTest {
        val db = Model().apply { rows.add(row()); fault = "cancel-readback" }
        val store = store(db, StandardTestDispatcher(testScheduler))
        val job = launch { store.acknowledgeLesson("step") }
        db.readbackEntered.await()
        job.cancelAndJoin()
        assertEquals(listOf(row()), db.rows)
        assertEquals(0, db.commits)
    }

    @Test fun successfulAcknowledgementWaitsForActualTransactionCommit() = runTest {
        val db = Model().apply { commitRelease = CompletableDeferred() }
        val store = store(db, StandardTestDispatcher(testScheduler))
        var acknowledged = false
        val job = launch { store.acknowledgeLesson("opening"); acknowledged = true }
        db.commitEntered.await()
        assertFalse(acknowledged)
        db.commitRelease!!.complete(Unit)
        job.join()
        assertTrue(acknowledged)
        assertEquals(1, db.commits)
    }

    @Test fun replacedAndForeignOwnersCannotReadVerifyOrWrite() = runTest {
        val db = Model()
        val gate = FangqiStorageEpochGate()
        var validations = 0
        val store = store(db, StandardTestDispatcher(testScheduler), gate = gate,
            verifier = FangqiLearningCompletionVerifier { _, _ -> validations++ })
        gate.exclusive(replacing = true) {}
        failure { store.load() }
        failure { store.acknowledgeLesson("opening") }
        failure { store.completePractice(FangqiLearningCatalog.practiceIds.first(), byteArrayOf(1)) }
        val foreign = store(db, StandardTestDispatcher(testScheduler), gate = gate,
            owner = FangqiStorageEpochGate().captureOwner())
        failure { foreign.load() }
        assertEquals(0, validations)
        assertEquals(0, db.transactions)
    }

    @Test fun evidenceIsCopiedBeforeWaitingForStorageGate() = runTest {
        val db = Model()
        val gate = FangqiStorageEpochGate()
        val release = CompletableDeferred<Unit>()
        val entered = CompletableDeferred<Unit>()
        val locker = launch { gate.exclusive(replacing = false) { entered.complete(Unit); release.await() } }
        entered.await()
        val evidence = byteArrayOf(7, 8)
        var received: ByteArray? = null
        val store = store(db, StandardTestDispatcher(testScheduler), gate = gate,
            verifier = FangqiLearningCompletionVerifier { _, bytes -> received = bytes.copyOf() })
        val writer = launch(start = CoroutineStart.UNDISPATCHED) {
            store.completePractice(FangqiLearningCatalog.practiceIds.first(), evidence)
        }
        evidence.fill(0)
        release.complete(Unit)
        locker.join(); writer.join()
        assertArrayEquals(byteArrayOf(7, 8), received)
    }

    @Test fun concurrentAcknowledgementsSerializeAndRetainBothKeys() = runTest {
        val db = Model()
        val gate = FangqiStorageEpochGate()
        val first = store(db, StandardTestDispatcher(testScheduler), gate = gate)
        val second = store(db, StandardTestDispatcher(testScheduler), gate = gate)
        val one = launch { first.acknowledgeLesson("opening") }
        val two = launch { second.acknowledgeLesson("step") }
        runCurrent(); one.join(); two.join()
        assertEquals(setOf("opening", "step"), first.load().completedLessons)
        assertEquals(2, db.commits)
    }

    private fun store(
        db: Model,
        dispatcher: CoroutineDispatcher,
        gate: FangqiStorageEpochGate = FangqiStorageEpochGate(),
        owner: FangqiStorageEpochGate.Owner = gate.captureOwner(),
        verifier: FangqiLearningCompletionVerifier = FangqiLearningCompletionVerifier { _, _ -> },
        clock: () -> Long = { 42L },
    ) = RoomFangqiLearningStore(db.dao, gate, owner, verifier, dispatcher, clock, db::transaction)

    private suspend fun failure(block: suspend () -> Any?) {
        try { block(); fail("expected persistence rejection") } catch (_: IOException) {}
    }

    private class Model {
        val rows = mutableListOf<FangqiLearningProgressEntity>()
        var fault: String? = null
        var inserts = 0
        var transactions = 0
        var commits = 0
        private var readsInTransaction = 0
        private var inTransaction = false
        val readbackEntered = CompletableDeferred<Unit>()
        val commitEntered = CompletableDeferred<Unit>()
        var commitRelease: CompletableDeferred<Unit>? = null
        val dao = object : FangqiLearningProgressDao {
            override suspend fun readAll(): List<FangqiLearningProgressEntity> {
                if (inTransaction && ++readsInTransaction == 2) {
                    if (fault == "readback") throw IOException("readback")
                    if (fault == "mismatch") return rows.map { it.copy(completedAtEpochMillis = 999L) }
                    if (fault == "cancel-readback") { readbackEntered.complete(Unit); awaitCancellation() }
                }
                return rows.toList()
            }
            override suspend fun insert(row: FangqiLearningProgressEntity) {
                check(rows.none { it.contentKey == row.contentKey })
                rows.add(row); inserts++
                if (fault == "insert") throw IOException("insert")
            }
            override suspend fun clearAll() { rows.clear() }
        }
        suspend fun transaction(block: suspend () -> Unit) {
            check(!inTransaction)
            val before = rows.toList()
            inTransaction = true; readsInTransaction = 0; transactions++
            try {
                block()
                commitEntered.complete(Unit)
                commitRelease?.await()
                if (fault == "commit") throw IOException("commit")
                commits++
            } catch (error: Throwable) {
                rows.clear(); rows.addAll(before)
                throw error
            } finally { inTransaction = false }
        }
    }

    companion object {
        private fun row() = FangqiLearningProgressEntity("lesson:opening", 1, 10L)
    }
}
