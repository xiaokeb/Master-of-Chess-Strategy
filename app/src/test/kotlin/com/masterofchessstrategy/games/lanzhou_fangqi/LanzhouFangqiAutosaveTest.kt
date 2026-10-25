package com.masterofchessstrategy.games.lanzhou_fangqi

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LanzhouFangqiAutosaveTest {
    @Test fun closeAndJoinWaitsForAnAlreadyCommittingWriter() = runTest {
        val release = CompletableDeferred<Unit>()
        var committed = false
        val store = object : FangqiSessionStore {
            override suspend fun load(): FangqiStoredSession? = null
            override suspend fun save(snapshot: FangqiStoredSession) = withContext(NonCancellable) {
                release.await()
                committed = true
            }
        }
        val queue = LanzhouFangqiAutosave(store, backgroundScope)
        queue.enqueue(snapshot(1))
        runCurrent()
        val close = launch { queue.closeAndJoin() }
        runCurrent()
        assertFalse(close.isCompleted)
        assertFalse(queue.progress.value.writerStopped)
        release.complete(Unit)
        close.join()
        assertTrue(committed)
        assertTrue(queue.progress.value.writerStopped)
    }

    @Test fun cancelledOwnerReleasesAwaitersAndRejectsNewWrites() = runTest {
        val owner = CoroutineScope(Job() + StandardTestDispatcher(testScheduler))
        val store = object : FangqiSessionStore {
            override suspend fun load(): FangqiStoredSession? = null
            override suspend fun save(snapshot: FangqiStoredSession) { CompletableDeferred<Unit>().await() }
        }
        val queue = LanzhouFangqiAutosave(store, owner)
        val revision = queue.enqueue(snapshot(1))
        runCurrent()
        owner.cancel()
        runCurrent()
        assertFalse(queue.awaitSaved(revision))
        assertTrue(queue.progress.value.writerStopped)
        val rejected = runCatching { queue.enqueue(snapshot(2)) }.exceptionOrNull()
        assertTrue(rejected is IllegalStateException)
        queue.closeAndJoin()
    }

    @Test fun slowFirstWriteCannotOverwriteLatestAndPendingSnapshotsCoalesce() = runTest {
        val releaseFirst = CompletableDeferred<Unit>()
        val written = mutableListOf<FangqiStoredSession>()
        val store = object : FangqiSessionStore {
            override suspend fun load() = written.lastOrNull()
            override suspend fun save(snapshot: FangqiStoredSession) {
                if (snapshot.acceptedActionCount == 1) releaseFirst.await()
                written += snapshot
            }
        }
        val queue = LanzhouFangqiAutosave(store, backgroundScope)
        queue.enqueue(snapshot(1))
        runCurrent()
        queue.enqueue(snapshot(2))
        val newest = queue.enqueue(snapshot(3))
        releaseFirst.complete(Unit)
        assertTrue(queue.awaitSaved(newest))
        assertEquals(listOf(1, 3), written.map { it.acceptedActionCount })
        assertEquals(3, queue.loadAfterPendingWrites()?.acceptedActionCount)
        queue.close()
    }

    @Test fun failedWriteIsReportedAndRetryKeepsTheLatestSnapshot() = runTest {
        var fail = true
        var stored: FangqiStoredSession? = null
        val store = object : FangqiSessionStore {
            override suspend fun load() = stored
            override suspend fun save(snapshot: FangqiStoredSession) {
                if (fail) error("fixture write failure")
                stored = snapshot
            }
        }
        val queue = LanzhouFangqiAutosave(store, backgroundScope)
        val revision = queue.enqueue(snapshot(4))
        assertFalse(queue.awaitSaved(revision))
        assertEquals(null, stored)
        fail = false
        assertEquals(revision, queue.retryLatest())
        assertTrue(queue.awaitSaved(revision))
        assertEquals(4, stored?.acceptedActionCount)
        queue.close()
    }

    @Test fun storedNativeBytesAreCopiedOnInputAndRead() {
        val bytes = byteArrayOf(1, 2, 3)
        val stored = FangqiStoredSession(FangqiStoredMode.LOCAL_TWO_PLAYER, null, bytes, "test-game", 1)
        bytes[0] = 8
        val read = stored.engineState
        read[1] = 9
        assertEquals(listOf<Byte>(1, 2, 3), stored.engineState.toList())
    }

    @Test fun closingBeforeAWriteReleasesPendingAwaiterWithoutClaimingSaved() = runTest {
        var writes = 0
        val store = object : FangqiSessionStore {
            override suspend fun load(): FangqiStoredSession? = null
            override suspend fun save(snapshot: FangqiStoredSession) { writes++ }
        }
        val queue = LanzhouFangqiAutosave(store, backgroundScope)
        val pending = queue.enqueue(snapshot(1))
        queue.close()
        assertFalse(queue.awaitSaved(pending))
        runCurrent()
        assertEquals(0, writes)
    }

    private fun snapshot(actions: Int) = FangqiStoredSession(
        FangqiStoredMode.LOCAL_TWO_PLAYER, null, byteArrayOf(actions.toByte()),
        "test-game", actions.toLong(), acceptedActionCount = actions,
    )
}
