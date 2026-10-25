package com.masterofchessstrategy.games.lanzhou_fangqi.data

import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FangqiStorageEpochGateTest {
    @Test fun databaseLookupUsesIdentityEvenForObjectsWithEqualValues() {
        data class DatabaseKey(val value: Int)
        val first = DatabaseKey(1)
        val second = DatabaseKey(1)
        assertSame(FangqiStorageEpochGate.forDatabase(first), FangqiStorageEpochGate.forDatabase(first))
        assertNotSame(FangqiStorageEpochGate.forDatabase(first), FangqiStorageEpochGate.forDatabase(second))
    }

    @Test fun foreignOwnerIsRejectedEvenWhenBothEpochNumbersAreZero() = runTest {
        val gate = FangqiStorageEpochGate()
        try { gate.access(FangqiStorageEpochGate().captureOwner()) { fail("foreign owner entered") }; fail() }
        catch (_: IOException) { }
        assertEquals(0L, gate.epoch.value)
    }

    @Test fun failedReplacementRetiresOwnersAndDoesNotReuseEpochs() = runTest {
        val gate = FangqiStorageEpochGate()
        val first = gate.captureOwner()
        try { gate.exclusive(true) { throw IOException("restore failed") } } catch (_: IOException) { }
        assertEquals(1L, gate.epoch.value)
        try { gate.access(first) { fail("stale writer entered") }; fail() } catch (_: IOException) { }
        gate.access(gate.captureOwner()) { }
        gate.exclusive(false) { }
        assertEquals(1L, gate.epoch.value)
        gate.exclusive(true) { }
        assertEquals(2L, gate.epoch.value)
    }

    @Test fun restoreWaitsForInFlightAccessBeforeRetiringIt() = runTest {
        val gate = FangqiStorageEpochGate()
        val owner = gate.captureOwner()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val order = mutableListOf<String>()
        val write = launch { gate.access(owner) { entered.complete(Unit); release.await(); order += "commit" } }
        entered.await()
        val restore = launch { gate.exclusive(true) { order += "restore" } }
        runCurrent()
        assertTrue(order.isEmpty())
        release.complete(Unit)
        write.join(); restore.join()
        assertEquals(listOf("commit", "restore"), order)
        try { gate.access(owner) { fail() }; fail() } catch (_: IOException) { }
    }
}
