package com.masterofchessstrategy.games.lanzhou_mahjong.data

import com.masterofchessstrategy.data.LocalDataBackupRepository
import com.masterofchessstrategy.data.LocalDataRestoreSummary
import com.masterofchessstrategy.games.go.data.GoStorageEpochGate
import com.masterofchessstrategy.games.lanzhou_fangqi.data.FangqiCoordinatedBackupRepository
import com.masterofchessstrategy.games.lanzhou_fangqi.data.FangqiStorageEpochGate
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

/** Real gate/wrapper composition around an explicit delegate fake; no SQLite, JNI or device. */
@OptIn(ExperimentalCoroutinesApi::class)
class LanzhouMahjongCoordinatedBackupRepositoryTest {
    @Test fun anEnteredLanzhouWriterBlocksEveryInnerRestoreStage() = runTest {
        val gates = Gates()
        val release = CompletableDeferred<Unit>()
        val writer = launch { gates.lz.access(gates.lz.captureOwner()) { release.await() } }
        runCurrent()
        val delegate = RecordingDelegate()
        val restore = async { combined(gates, delegate).restore(byteArrayOf(1)) }
        runCurrent()
        assertEquals(listOf(0L, 0L, 0L), gates.epochs())
        assertEquals(0, delegate.restores)
        release.complete(Unit); writer.join(); restore.await()
        assertEquals(listOf(1L, 1L, 1L), gates.epochs())
        assertEquals(1, delegate.restores)
    }

    @Test fun exportHoldsAllThreeGatesWithoutRetiringTheirWriters() = runTest {
        val gates = Gates()
        val release = CompletableDeferred<Unit>()
        val delegate = RecordingDelegate().apply { beforeExport = { release.await() } }
        val export = async { combined(gates, delegate).export(42) }
        runCurrent()
        val entered = mutableListOf<String>()
        val lzWriter = launch { gates.lz.access(gates.lz.captureOwner()) { entered += "lz" } }
        val fqWriter = launch { gates.fq.access(gates.fq.captureOwner()) { entered += "fq" } }
        val goWriter = launch { gates.go.access(0) { entered += "go" } }
        runCurrent()
        assertTrue(entered.isEmpty())
        assertEquals(listOf(0L, 0L, 0L), gates.epochs())
        release.complete(Unit)
        assertArrayEquals(byteArrayOf(4, 2), export.await())
        lzWriter.join(); fqWriter.join(); goWriter.join()
        assertEquals(setOf("lz", "fq", "go"), entered.toSet())
        assertEquals(listOf(0L, 0L, 0L), gates.epochs())
        assertEquals(42L, delegate.exportTime)
    }

    @Test fun completedReplacementRetiresAllOldOwnersAndReturnsTheDelegateSummary() = runTest {
        val gates = Gates()
        val lzOwner = gates.lz.captureOwner()
        val fqOwner = gates.fq.captureOwner()
        val delegate = RecordingDelegate()
        assertSame(delegate.summary, combined(gates, delegate).restore(byteArrayOf(1)))
        stale { gates.lz.access(lzOwner) { fail() } }
        stale { gates.fq.access(fqOwner) { fail() } }
        stale { gates.go.access(0) { fail() } }
        gates.lz.access(gates.lz.captureOwner()) { }
        gates.fq.access(gates.fq.captureOwner()) { }
        gates.go.access(gates.go.epoch.value) { }
    }

    @Test fun failedReplacementPropagatesOnceAndKeepsReachedOwnersRetired() = runTest {
        val gates = Gates()
        val lzOwner = gates.lz.captureOwner()
        val fqOwner = gates.fq.captureOwner()
        val failure = IOException("admission failed")
        val delegate = RecordingDelegate().apply { beforeRestore = { throw failure } }
        assertSame(failure, runCatching { combined(gates, delegate).restore(byteArrayOf(1)) }.exceptionOrNull())
        assertEquals(1, delegate.restores)
        assertEquals(listOf(1L, 1L, 1L), gates.epochs())
        stale { gates.lz.access(lzOwner) { fail() } }
        stale { gates.fq.access(fqOwner) { fail() } }
        stale { gates.go.access(0) { fail() } }
    }

    @Test fun cancellationBeforeTheLanzhouGateRetiresNothingAndNeverStartsDelegate() = runTest {
        val gates = Gates()
        val release = CompletableDeferred<Unit>()
        val owner = gates.lz.captureOwner()
        val writer = launch { gates.lz.access(owner) { release.await() } }
        runCurrent()
        val delegate = RecordingDelegate()
        val restore = launch { combined(gates, delegate).restore(byteArrayOf(1)) }
        runCurrent(); restore.cancelAndJoin()
        assertEquals(listOf(0L, 0L, 0L), gates.epochs())
        assertEquals(0, delegate.restores)
        release.complete(Unit); writer.join()
        gates.lz.access(owner) { }
    }

    @Test fun cancellationWaitingForFangqiRetiresOnlyTheReachedLanzhouOwner() = runTest {
        val gates = Gates()
        val release = CompletableDeferred<Unit>()
        val lzOwner = gates.lz.captureOwner()
        val fqOwner = gates.fq.captureOwner()
        val writer = launch { gates.fq.access(fqOwner) { release.await() } }
        runCurrent()
        val delegate = RecordingDelegate()
        val restore = launch { combined(gates, delegate).restore(byteArrayOf(1)) }
        runCurrent()
        assertEquals(listOf(1L, 0L, 0L), gates.epochs())
        restore.cancelAndJoin()
        assertEquals(0, delegate.restores)
        stale { gates.lz.access(lzOwner) { fail() } }
        release.complete(Unit); writer.join()
        gates.fq.access(fqOwner) { }
        gates.go.access(0) { }
    }

    @Test fun cancellationWaitingForGoDoesNotPretendTheGoOwnerWasRetired() = runTest {
        val gates = Gates()
        val release = CompletableDeferred<Unit>()
        val lzOwner = gates.lz.captureOwner()
        val fqOwner = gates.fq.captureOwner()
        val writer = launch { gates.go.access(0) { release.await() } }
        runCurrent()
        val delegate = RecordingDelegate()
        val restore = launch { combined(gates, delegate).restore(byteArrayOf(1)) }
        runCurrent()
        assertEquals(listOf(1L, 1L, 0L), gates.epochs())
        restore.cancelAndJoin()
        stale { gates.lz.access(lzOwner) { fail() } }
        stale { gates.fq.access(fqOwner) { fail() } }
        release.complete(Unit); writer.join()
        gates.go.access(0) { }
        assertEquals(0, delegate.restores)
    }

    @Test fun restoreOwnsTheInputBeforeWaitingForAnyGate() = runTest {
        val gates = Gates()
        val release = CompletableDeferred<Unit>()
        val writer = launch { gates.lz.access(gates.lz.captureOwner()) { release.await() } }
        runCurrent()
        val delegate = RecordingDelegate()
        val input = byteArrayOf(1, 2, 3)
        val restore = async { combined(gates, delegate).restore(input) }
        runCurrent()
        input.fill(9)
        release.complete(Unit); writer.join(); restore.await()
        assertArrayEquals(byteArrayOf(1, 2, 3), delegate.restored)
    }

    private class Gates {
        val lz = LanzhouMahjongStorageEpochGate()
        val fq = FangqiStorageEpochGate()
        val go = GoStorageEpochGate()
        fun epochs() = listOf(lz.epoch.value, fq.epoch.value, go.epoch.value)
    }

    private fun combined(gates: Gates, delegate: RecordingDelegate): LocalDataBackupRepository {
        val go = object : LocalDataBackupRepository {
            override suspend fun export(createdAtEpochMillis: Long): ByteArray = gates.go.backup(false) {
                delegate.export(createdAtEpochMillis)
            }
            override suspend fun restore(bytes: ByteArray): LocalDataRestoreSummary = gates.go.backup(true) {
                delegate.restore(bytes)
            }
        }
        return LanzhouMahjongCoordinatedBackupRepository(FangqiCoordinatedBackupRepository(go, gates.fq), gates.lz)
    }

    private suspend fun stale(block: suspend () -> Unit) {
        try { block(); fail("old writer must be rejected") } catch (_: IOException) { }
    }

    private class RecordingDelegate : LocalDataBackupRepository {
        val summary = LocalDataRestoreSummary(1, 2, 3, 4)
        var exports = 0
        var restores = 0
        var exportTime: Long? = null
        var restored: ByteArray? = null
        var beforeExport: (suspend () -> Unit)? = null
        var beforeRestore: (suspend () -> Unit)? = null
        override suspend fun export(createdAtEpochMillis: Long): ByteArray {
            exports++; exportTime = createdAtEpochMillis; beforeExport?.invoke()
            return byteArrayOf(4, 2)
        }
        override suspend fun restore(bytes: ByteArray): LocalDataRestoreSummary {
            restores++; restored = bytes.copyOf(); beforeRestore?.invoke()
            return summary
        }
    }
}
