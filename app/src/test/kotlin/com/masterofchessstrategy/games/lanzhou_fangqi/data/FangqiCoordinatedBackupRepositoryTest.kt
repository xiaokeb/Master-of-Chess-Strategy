package com.masterofchessstrategy.games.lanzhou_fangqi.data

import com.masterofchessstrategy.data.LocalDataBackupRepository
import com.masterofchessstrategy.data.LocalDataRestoreSummary
import com.masterofchessstrategy.games.go.data.GatedGoBackupRepository
import com.masterofchessstrategy.games.go.data.GoBackupPreparation
import com.masterofchessstrategy.games.go.data.GoStorageEpochGate
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/** Real gate/wrapper concurrency, with an explicit delegate fake; not a SQLite/JNI test. */
@OptIn(ExperimentalCoroutinesApi::class)
class FangqiCoordinatedBackupRepositoryTest {
    @Test fun waitsForAnEnteredFangqiWriterBeforeGoPreparationOrReplacement() = runTest {
        val fq = FangqiStorageEpochGate()
        val go = GoStorageEpochGate()
        val release = CompletableDeferred<Unit>()
        val oldOwner = fq.captureOwner()
        val writer = launch { fq.access(oldOwner) { release.await() } }
        runCurrent()
        val delegate = RecordingDelegate()
        val repository = combined(fq, go, delegate)
        val restore = async { repository.restore(byteArrayOf(1)) }
        runCurrent()
        assertEquals(0L, fq.epoch.value)
        assertEquals(0L, go.epoch.value)
        assertEquals(0, delegate.restores)
        release.complete(Unit)
        writer.join()
        restore.await()
        assertEquals(1L, fq.epoch.value)
        assertEquals(1L, go.epoch.value)
        assertEquals(1, delegate.restores)
    }

    @Test fun exportKeepsBothWritersOutWithoutRetiringEitherOwner() = runTest {
        val fq = FangqiStorageEpochGate()
        val go = GoStorageEpochGate()
        val owner = fq.captureOwner()
        val release = CompletableDeferred<Unit>()
        val delegate = RecordingDelegate().apply { beforeExport = { release.await() } }
        val export = async { combined(fq, go, delegate).export(42L) }
        runCurrent()
        assertEquals(1, delegate.exports)
        var fqWrote = false
        var goWrote = false
        val fqWriter = launch { fq.access(owner) { fqWrote = true } }
        val goWriter = launch { go.access(0L) { goWrote = true } }
        runCurrent()
        assertFalse(fqWrote)
        assertFalse(goWrote)
        release.complete(Unit)
        assertArrayEquals(byteArrayOf(4, 2), export.await())
        fqWriter.join()
        goWriter.join()
        assertTrue(fqWrote)
        assertTrue(goWrote)
        assertEquals(0L, fq.epoch.value)
        assertEquals(0L, go.epoch.value)
    }

    @Test fun restoreRetiresBothOldOwnersAndAllowsFreshReadersAfterCompletion() = runTest {
        val fq = FangqiStorageEpochGate()
        val go = GoStorageEpochGate()
        val oldOwner = fq.captureOwner()
        combined(fq, go, RecordingDelegate()).restore(byteArrayOf(1))
        assertTrue(runCatching { fq.access(oldOwner) { error("stale Fangqi write") } }.exceptionOrNull() is IOException)
        assertTrue(runCatching { go.access(0L) { error("stale Go write") } }.exceptionOrNull() is IOException)
        assertEquals("fresh-fq", fq.access(fq.captureOwner()) { "fresh-fq" })
        assertEquals("fresh-go", go.access(go.epoch.value) { "fresh-go" })
    }

    @Test fun failurePropagatesAndStillRetiresBothOwnersWithoutRetryingDelegate() = runTest {
        val fq = FangqiStorageEpochGate()
        val go = GoStorageEpochGate()
        val oldOwner = fq.captureOwner()
        val failure = IOException("admission rejected")
        val delegate = RecordingDelegate().apply { beforeRestore = { throw failure } }
        assertSame(failure, runCatching { combined(fq, go, delegate).restore(byteArrayOf(1)) }.exceptionOrNull())
        assertEquals(1, delegate.restores)
        assertEquals(1L, fq.epoch.value)
        assertEquals(1L, go.epoch.value)
        assertTrue(runCatching { fq.access(oldOwner) { Unit } }.exceptionOrNull() is IOException)
        assertTrue(runCatching { go.access(0L) { Unit } }.exceptionOrNull() is IOException)
    }

    @Test fun cancellationWhileWaitingForOuterGateDoesNotStartOrRetireAnything() = runTest {
        val fq = FangqiStorageEpochGate()
        val go = GoStorageEpochGate()
        val release = CompletableDeferred<Unit>()
        val owner = fq.captureOwner()
        val writer = launch { fq.access(owner) { release.await() } }
        runCurrent()
        val delegate = RecordingDelegate()
        val restore = launch { combined(fq, go, delegate).restore(byteArrayOf(1)) }
        runCurrent()
        restore.cancelAndJoin()
        assertEquals(0, delegate.restores)
        assertEquals(0L, fq.epoch.value)
        assertEquals(0L, go.epoch.value)
        release.complete(Unit)
        writer.join()
        fq.access(owner) { Unit }
        go.access(0L) { Unit }
    }

    @Test fun cancellationWhileWaitingForGoDoesNotPretendThatGoWasRetired() = runTest {
        val fq = FangqiStorageEpochGate()
        val go = GoStorageEpochGate()
        val release = CompletableDeferred<Unit>()
        val goWriter = launch { go.access(0L) { release.await() } }
        runCurrent()
        val oldFqOwner = fq.captureOwner()
        val delegate = RecordingDelegate()
        val restore = launch { combined(fq, go, delegate).restore(byteArrayOf(1)) }
        runCurrent()
        assertEquals(1L, fq.epoch.value)
        assertEquals(0L, go.epoch.value)
        assertEquals(0, delegate.restores)
        restore.cancelAndJoin()
        assertTrue(runCatching { fq.access(oldFqOwner) { Unit } }.exceptionOrNull() is IOException)
        fq.access(fq.captureOwner()) { Unit }
        release.complete(Unit)
        goWriter.join()
        go.access(0L) { Unit }
        assertEquals(0, delegate.restores)
    }

    @Test fun ownsRestoreBytesBeforeWaitingForAnEnteredWriter() = runTest {
        val fq = FangqiStorageEpochGate()
        val go = GoStorageEpochGate()
        val release = CompletableDeferred<Unit>()
        val writer = launch { fq.access(fq.captureOwner()) { release.await() } }
        runCurrent()
        val delegate = RecordingDelegate()
        val bytes = byteArrayOf(1, 2, 3)
        val restore = async { combined(fq, go, delegate).restore(bytes) }
        runCurrent()
        bytes.fill(9)
        release.complete(Unit)
        writer.join()
        restore.await()
        assertArrayEquals(byteArrayOf(1, 2, 3), delegate.restored)
    }

    private fun combined(fq: FangqiStorageEpochGate, go: GoStorageEpochGate, delegate: RecordingDelegate) =
        FangqiCoordinatedBackupRepository(GatedGoBackupRepository(delegate, go, object : GoBackupPreparation {
            override suspend fun beforeExport() = Unit
            override suspend fun beforeRestore() = Unit
        }), fq)

    private class RecordingDelegate : LocalDataBackupRepository {
        var exports = 0
        var restores = 0
        var restored: ByteArray? = null
        var beforeExport: suspend () -> Unit = {}
        var beforeRestore: suspend () -> Unit = {}
        override suspend fun export(createdAtEpochMillis: Long): ByteArray {
            exports++
            beforeExport()
            return byteArrayOf(4, 2)
        }
        override suspend fun restore(bytes: ByteArray): LocalDataRestoreSummary {
            restores++
            restored = bytes.copyOf()
            beforeRestore()
            return LocalDataRestoreSummary(0, 0, 0, 0)
        }
    }
}
