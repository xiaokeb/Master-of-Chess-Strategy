package com.masterofchessstrategy.games.tiger_goat.data

import com.masterofchessstrategy.data.LocalDataBackupRepository
import com.masterofchessstrategy.data.LocalDataRestoreSummary
import com.masterofchessstrategy.games.tiger_goat.TigerGoatLoadResult
import com.masterofchessstrategy.games.tiger_goat.TigerGoatSavedSession
import com.masterofchessstrategy.games.tiger_goat.TigerGoatSessionStore
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TigerGoatStorageOwnershipTest {
    private fun saved(bytes: ByteArray = byteArrayOf(1, 2)) = TigerGoatSavedSession("session", bytes, 0, 10)

    private class Store : TigerGoatSessionStore {
        var loaded: TigerGoatLoadResult = TigerGoatLoadResult.Missing
        val writes = mutableListOf<TigerGoatSavedSession>()
        var failure: Throwable? = null
        override suspend fun load(): TigerGoatLoadResult = loaded
        override suspend fun save(saved: TigerGoatSavedSession) {
            failure?.let { throw it }
            writes += saved
        }
    }

    private class Backup : LocalDataBackupRepository {
        val summary = LocalDataRestoreSummary(1, 2, 3, 4)
        var restored: ByteArray? = null
        var exportedAt: Long? = null
        var failure: Throwable? = null
        override suspend fun export(createdAtEpochMillis: Long): ByteArray {
            exportedAt = createdAtEpochMillis
            return byteArrayOf(7)
        }
        override suspend fun restore(bytes: ByteArray): LocalDataRestoreSummary {
            failure?.let { throw it }
            restored = bytes
            return summary
        }
    }

    @Test fun gateRegistryUsesDatabaseIdentity() {
        data class DatabaseKey(val value: Int)
        val first = DatabaseKey(1)
        val second = DatabaseKey(1)
        assertSame(TigerGoatStorageEpochGate.forDatabase(first), TigerGoatStorageEpochGate.forDatabase(first))
        assertNotSame(TigerGoatStorageEpochGate.forDatabase(first), TigerGoatStorageEpochGate.forDatabase(second))
    }

    @Test fun foreignOwnerCannotEnterEvenWithSameEpoch() = runTest {
        val gate = TigerGoatStorageEpochGate()
        val error = runCatching { gate.access(TigerGoatStorageEpochGate().captureOwner()) { fail() } }.exceptionOrNull()
        assertTrue(error is IOException)
        assertEquals(0L, gate.epoch.value)
    }

    @Test fun failedReplacementRetiresOldOwnerAndPreservesFailure() = runTest {
        val gate = TigerGoatStorageEpochGate()
        val old = gate.captureOwner()
        val failure = IOException("invalid backup")
        assertSame(failure, originalFailure(runCatching { gate.exclusive(true) { throw failure } }.exceptionOrNull()))
        assertEquals(1L, gate.epoch.value)
        assertTrue(runCatching { gate.access(old) { fail() } }.exceptionOrNull() is IOException)
        gate.access(gate.captureOwner()) { }
        gate.exclusive(true) { }
        assertEquals(2L, gate.epoch.value)
    }

    @Test fun replacementWaitsForStartedWriteAndRejectsLaterOldWriter() = runTest {
        val gate = TigerGoatStorageEpochGate()
        val owner = gate.captureOwner()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val order = mutableListOf<String>()
        val first = launch { gate.access(owner) { entered.complete(Unit); release.await(); order += "save" } }
        entered.await()
        val restore = launch { gate.exclusive(true) { order += "restore" } }
        runCurrent()
        val late = async { runCatching { gate.access(owner) { order += "stale" } }.exceptionOrNull() }
        runCurrent()
        assertEquals(0L, gate.epoch.value)
        release.complete(Unit)
        first.join(); restore.join()
        assertTrue(late.await() is IOException)
        assertEquals(listOf("save", "restore"), order)
    }

    @Test fun cancelledWaitingReplacementDoesNotRetireOwner() = runTest {
        val gate = TigerGoatStorageEpochGate()
        val owner = gate.captureOwner()
        val release = CompletableDeferred<Unit>()
        val writer = launch { gate.access(owner) { release.await() } }
        runCurrent()
        val restore = launch { gate.exclusive(true) { fail("cancelled restore entered") } }
        runCurrent()
        restore.cancel(); restore.join()
        release.complete(Unit); writer.join()
        assertEquals(0L, gate.epoch.value)
        gate.access(owner) { }
    }

    @Test fun sameGateReentryFailsWithoutDeadlockOrEpochChange() = runTest {
        val gate = TigerGoatStorageEpochGate()
        gate.access(gate.captureOwner()) {
            assertTrue(runCatching { gate.exclusive(true) { fail() } }.exceptionOrNull() is IllegalStateException)
        }
        assertEquals(0L, gate.epoch.value)
        gate.exclusive(false) { }
    }

    @Test fun nestedDifferentGatesKeepAncestorReentryGuard() = runTest {
        val first = TigerGoatStorageEpochGate()
        val second = TigerGoatStorageEpochGate()
        first.exclusive(false) {
            second.exclusive(false) {
                assertTrue(runCatching { first.exclusive(false) { fail() } }.exceptionOrNull() is IllegalStateException)
            }
        }
    }

    @Test fun structuredChildCannotWaitForParentHeldGate() = runTest {
        val gate = TigerGoatStorageEpochGate()
        gate.exclusive(false) {
            coroutineScope {
                val child = async { runCatching { gate.access(gate.captureOwner()) { fail() } }.exceptionOrNull() }
                assertTrue(child.await() is IllegalStateException)
            }
        }
    }

    @Test fun loadReturnsOwnedBytesAndPreservesMissingAndIncompatible() = runTest {
        val delegate = Store()
        val wrapper = GatedTigerGoatSessionStore(delegate, TigerGoatStorageEpochGate())
        assertSame(TigerGoatLoadResult.Missing, wrapper.load())
        delegate.loaded = TigerGoatLoadResult.Incompatible
        assertSame(TigerGoatLoadResult.Incompatible, wrapper.load())
        val original = saved()
        delegate.loaded = TigerGoatLoadResult.Loaded(original)
        val loaded = (wrapper.load() as TigerGoatLoadResult.Loaded).saved
        original.bytes[0] = 9
        assertArrayEquals(byteArrayOf(1, 2), loaded.bytes)
        loaded.bytes[1] = 8
        assertArrayEquals(byteArrayOf(9, 2), original.bytes)
    }

    @Test fun queuedSaveOwnsBytesBeforeWaitingForGate() = runTest {
        val gate = TigerGoatStorageEpochGate()
        val delegate = Store()
        val wrapper = GatedTigerGoatSessionStore(delegate, gate)
        val release = CompletableDeferred<Unit>()
        val holder = launch { gate.exclusive(false) { release.await() } }
        runCurrent()
        val input = saved()
        val save = launch { wrapper.save(input) }
        runCurrent()
        input.bytes[0] = 9
        release.complete(Unit); holder.join(); save.join()
        assertArrayEquals(byteArrayOf(1, 2), delegate.writes.single().bytes)
    }

    @Test fun staleStoreCannotLoadOrSaveAndDoesNotRenewItsOwner() = runTest {
        val gate = TigerGoatStorageEpochGate()
        val delegate = Store()
        val old = GatedTigerGoatSessionStore(delegate, gate)
        gate.exclusive(true) { }
        repeat(2) {
            assertTrue(runCatching { old.load() }.exceptionOrNull() is IOException)
            assertTrue(runCatching { old.save(saved()) }.exceptionOrNull() is IOException)
        }
        assertTrue(delegate.writes.isEmpty())
        assertEquals(0L, old.ownerEpoch)
        assertEquals(1L, old.epoch.value)
        GatedTigerGoatSessionStore(delegate, gate).save(saved())
        assertEquals(1, delegate.writes.size)
    }

    @Test fun storeFailureIsPropagatedWithoutAcknowledgingWrite() = runTest {
        val delegate = Store()
        val failure = IOException("disk full")
        delegate.failure = failure
        val wrapper = GatedTigerGoatSessionStore(delegate, TigerGoatStorageEpochGate())
        assertSame(failure, originalFailure(runCatching { wrapper.save(saved()) }.exceptionOrNull()))
        assertTrue(delegate.writes.isEmpty())
    }

    @Test fun exportPreservesOwnerAndReturnsDelegateResult() = runTest {
        val gate = TigerGoatStorageEpochGate()
        val owner = gate.captureOwner()
        val delegate = Backup()
        val wrapper = TigerGoatCoordinatedBackupRepository(delegate, gate)
        assertArrayEquals(byteArrayOf(7), wrapper.export(123))
        assertEquals(123L, delegate.exportedAt)
        assertEquals(0L, gate.epoch.value)
        gate.access(owner) { }
    }

    @Test fun restoreOwnsInputBeforeWaitingAndReturnsOriginalSummary() = runTest {
        val gate = TigerGoatStorageEpochGate()
        val delegate = Backup()
        val wrapper = TigerGoatCoordinatedBackupRepository(delegate, gate)
        val release = CompletableDeferred<Unit>()
        val holder = launch { gate.exclusive(false) { release.await() } }
        runCurrent()
        val input = byteArrayOf(1, 2)
        val restore = async { wrapper.restore(input) }
        runCurrent()
        input[0] = 9
        assertNull(delegate.restored)
        release.complete(Unit); holder.join()
        assertSame(delegate.summary, restore.await())
        assertArrayEquals(byteArrayOf(1, 2), delegate.restored)
        assertEquals(1L, gate.epoch.value)
    }

    @Test fun failedBackupRestoreInvalidatesStoreWithoutChangingDelegateData() = runTest {
        val gate = TigerGoatStorageEpochGate()
        val store = GatedTigerGoatSessionStore(Store(), gate)
        val delegate = Backup()
        val failure = IOException("rejected backup")
        delegate.failure = failure
        val wrapper = TigerGoatCoordinatedBackupRepository(delegate, gate)
        assertSame(failure, originalFailure(runCatching { wrapper.restore(byteArrayOf(3)) }.exceptionOrNull()))
        assertNull(delegate.restored)
        assertTrue(runCatching { store.save(saved()) }.exceptionOrNull() is IOException)
    }
    // Coroutine debug recovery may copy an exception while preserving its original cause.
    private fun originalFailure(error: Throwable?): Throwable? = generateSequence(error) { it.cause }.lastOrNull()
}
