package com.masterofchessstrategy.games.lanzhou_fangqi.data

import com.masterofchessstrategy.games.lanzhou_fangqi.FangqiOwnerTestSession
import com.masterofchessstrategy.games.lanzhou_fangqi.FangqiOwnerTestStore
import com.masterofchessstrategy.games.lanzhou_fangqi.FangqiSessionStore
import com.masterofchessstrategy.games.lanzhou_fangqi.FangqiStoredSession
import java.io.IOException
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FangqiLocalStorageTest {
    @Test fun formalWriterSwitchWaitsForReleaseAndCannotReuseTheOrdinaryQueue() = runTest {
        val ordinary = FangqiOwnerTestStore()
        val formal = FangqiOwnerTestStore()
        val ids = mutableListOf<String>()
        val storage = FangqiLocalStorage(FangqiStorageEpochGate(), { ordinary }, StandardTestDispatcher(testScheduler),
            { _, id -> ids += id; formal })
        val first = storage.acquire()
        val opening = async { storage.acquireRanked("formal") }
        runCurrent()
        assertFalse(opening.isCompleted)
        first.release()
        val ranked = opening.await()
        assertTrue(first.autosave.progress.value.writerStopped)
        assertNotSame(first.autosave, ranked.autosave)
        assertEquals(listOf("formal"), ids)
        ranked.release()
        val back = storage.acquire()
        assertTrue(ranked.autosave.progress.value.writerStopped)
        assertNotSame(ranked.autosave, back.autosave)
        back.retireWriter(); back.release()
    }

    @Test fun formalReentryKeepsOnlyTheSameSessionAndEpochBinding() = runTest {
        val gate = FangqiStorageEpochGate()
        val bindings = mutableListOf<Pair<Long, String>>()
        val storage = FangqiLocalStorage(gate, { FangqiOwnerTestStore() }, StandardTestDispatcher(testScheduler),
            { owner, id -> bindings += owner.epoch to id; FangqiOwnerTestStore() })
        val first = storage.acquireRanked("first"); first.release()
        val same = storage.acquireRanked("first")
        assertSame(first.autosave, same.autosave)
        same.release()
        val second = storage.acquireRanked("second")
        assertTrue(first.autosave.progress.value.writerStopped)
        second.release()
        gate.exclusive(true) { }
        val fresh = storage.acquireRanked("second")
        assertTrue(second.autosave.progress.value.writerStopped)
        assertEquals(listOf(0L to "first", 0L to "second", 1L to "second"), bindings)
        fresh.retireWriter(); fresh.release()
    }

    @Test fun normalReentryKeepsTheSameIdleApplicationQueue() = runTest {
        val store = FangqiOwnerTestStore()
        val storage = FangqiLocalStorage(FangqiStorageEpochGate(), { store }, StandardTestDispatcher(testScheduler))
        val first = storage.acquire()
        val saved = FangqiOwnerTestSession.saved()
        assertTrue(first.autosave.awaitSaved(first.autosave.enqueue(saved)))
        first.release()
        val second = storage.acquire()
        assertSame(first.autosave, second.autosave)
        assertFalse(second.autosave.progress.value.closed)
        assertSame(saved, second.autosave.loadAfterPendingWrites())
        second.retireWriter(); second.release()
    }

    @Test fun newNativeOwnerWaitsForPreviousNativeReleaseNotJustWriterClosure() = runTest {
        val storage = FangqiLocalStorage(FangqiStorageEpochGate(), { FangqiOwnerTestStore() }, StandardTestDispatcher(testScheduler))
        val first = storage.acquire()
        first.retireWriter()
        val opening = async { storage.acquire() }
        runCurrent()
        assertFalse(opening.isCompleted)
        first.release()
        val second = opening.await()
        assertNotSame(first.autosave, second.autosave)
        assertTrue(first.autosave.progress.value.writerStopped)
        second.retireWriter(); second.release()
    }

    @Test fun epochChangeRetiresOldQueueAndStoreBeforeGrantingAFreshOwner() = runTest {
        val gate = FangqiStorageEpochGate()
        val store = FangqiOwnerTestStore()
        val captured = mutableListOf<FangqiStorageEpochGate.Owner>()
        val storage = FangqiLocalStorage(gate, { owner ->
            captured += owner
            object : FangqiSessionStore {
                override suspend fun load() = gate.access(owner) { store.load() }
                override suspend fun save(snapshot: FangqiStoredSession) = gate.access(owner) { store.save(snapshot) }
            }
        }, StandardTestDispatcher(testScheduler))
        val first = storage.acquire()
        assertTrue(first.autosave.awaitSaved(first.autosave.enqueue(FangqiOwnerTestSession.saved())))
        first.release()
        gate.exclusive(true) { store.saved = null }
        val second = storage.acquire()
        assertEquals(2, captured.size)
        assertEquals(0L, captured[0].epoch)
        assertEquals(1L, captured[1].epoch)
        assertTrue(first.autosave.progress.value.writerStopped)
        assertNotSame(first.autosave, second.autosave)
        assertNull(second.autosave.loadAfterPendingWrites())
        second.retireWriter(); second.release()
    }

    @Test fun cancelledAcquisitionDoesNotConsumeANewNativeLease() = runTest {
        val storage = FangqiLocalStorage(FangqiStorageEpochGate(), { FangqiOwnerTestStore() }, StandardTestDispatcher(testScheduler))
        val first = storage.acquire()
        val waiting = async { storage.acquire() }
        runCurrent()
        waiting.cancelAndJoin()
        first.release()
        val next = storage.acquire()
        assertSame(first.autosave, next.autosave)
        next.retireWriter(); next.release()
    }

    @Test fun failedNativeReleaseIsNotTreatedAsSafeForTheNextPage() = runTest {
        val storage = FangqiLocalStorage(FangqiStorageEpochGate(), { FangqiOwnerTestStore() }, StandardTestDispatcher(testScheduler))
        val first = storage.acquire()
        first.retireWriter()
        first.release(IOException("Native release failed"))
        try { storage.acquire(); fail() } catch (_: IOException) { }
    }
}
