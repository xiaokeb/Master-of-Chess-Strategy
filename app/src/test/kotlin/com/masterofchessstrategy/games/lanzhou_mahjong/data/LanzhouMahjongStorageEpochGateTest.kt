package com.masterofchessstrategy.games.lanzhou_mahjong.data

import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LanzhouMahjongStorageEpochGateTest {
    @Test fun sameDatabaseUsesIdentityEvenWhenDistinctDatabaseObjectsCompareEqual() {
        data class Database(val value: Int)
        val first = Database(1)
        val second = Database(1)
        assertSame(LanzhouMahjongStorageEpochGate.forDatabase(first), LanzhouMahjongStorageEpochGate.forDatabase(first))
        assertNotSame(LanzhouMahjongStorageEpochGate.forDatabase(first), LanzhouMahjongStorageEpochGate.forDatabase(second))
    }

    @Test fun foreignOwnerIsRejectedWithoutRunningItsBlock() = runTest {
        val gate = LanzhouMahjongStorageEpochGate()
        expectStale { gate.access(LanzhouMahjongStorageEpochGate().captureOwner()) { fail("foreign writer entered") } }
        assertEquals(0L, gate.epoch.value)
    }

    @Test fun failedReplacementStillRetiresOldWritersAndNextOwnerCanEnter() = runTest {
        val gate = LanzhouMahjongStorageEpochGate()
        val owner = gate.captureOwner()
        try { gate.exclusive(true) { throw IOException("restore rollback") }; fail() } catch (_: IOException) { }
        assertEquals(1L, gate.epoch.value)
        expectStale { gate.access(owner) { fail("old writer entered") } }
        assertEquals("new", gate.access(gate.captureOwner()) { "new" })
        gate.exclusive(true) { }
        assertEquals(2L, gate.epoch.value)
    }

    @Test fun exportDoesNotRetireOwnerOrAdvanceEpoch() = runTest {
        val gate = LanzhouMahjongStorageEpochGate()
        val owner = gate.captureOwner()
        assertEquals("snapshot", gate.exclusive(false) { "snapshot" })
        assertEquals(0L, gate.epoch.value)
        gate.access(owner) { }
    }

    @Test fun replacementWaitsForAnAdmittedWriteThenRejectsThatWriter() = runTest {
        val gate = LanzhouMahjongStorageEpochGate()
        val owner = gate.captureOwner()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val order = mutableListOf<String>()
        val write = launch { gate.access(owner) { entered.complete(Unit); release.await(); order += "write" } }
        entered.await()
        val replacement = launch { gate.exclusive(true) { order += "replace" } }
        runCurrent()
        assertTrue(order.isEmpty())
        release.complete(Unit)
        write.join(); replacement.join()
        assertEquals(listOf("write", "replace"), order)
        expectStale { gate.access(owner) { fail() } }
    }

    @Test fun cancellingAReplacementBeforeLockAcquisitionDoesNotRetireOwners() = runTest {
        val gate = LanzhouMahjongStorageEpochGate()
        val owner = gate.captureOwner()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val write = launch { gate.access(owner) { entered.complete(Unit); release.await() } }
        entered.await()
        val replacement = launch { gate.exclusive(true) { fail("cancelled replacement entered") } }
        runCurrent()
        replacement.cancel(); replacement.join()
        release.complete(Unit); write.join()
        assertEquals(0L, gate.epoch.value)
        gate.access(owner) { }
    }

    private suspend fun expectStale(block: suspend () -> Unit) {
        try { block(); fail("expected retired/foreign writer rejection") } catch (_: IOException) { }
    }
}
