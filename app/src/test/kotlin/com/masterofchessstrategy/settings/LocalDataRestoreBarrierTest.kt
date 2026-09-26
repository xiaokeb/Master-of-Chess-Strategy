package com.masterofchessstrategy.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LocalDataRestoreBarrierTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun prepare() = Dispatchers.setMain(dispatcher)
    @After fun cleanup() = Dispatchers.resetMain()

    @Test fun allOwnersAreCancelledBeforeWaitingAndFinalWritesPrecedeRestore() = runTest(dispatcher) {
        val owners = listOf(object : ViewModel() {}, object : ViewModel() {})
        val events = mutableListOf<String>()
        val jobs = owners.mapIndexed { index, owner ->
            owner.viewModelScope.launch {
                try { awaitCancellation() } finally {
                    // Models a database operation already past its cancellation point.
                    withContext(NonCancellable) { delay(100L); events += "write$index" }
                }
            }
        }
        runCurrent()
        var restored = false
        launch {
            awaitLocalDataWriters(owners) { events += "stop" }
            events += "restore"
            restored = true
        }
        runCurrent()
        assertTrue(jobs.all { it.isCancelled })
        assertFalse(restored)
        advanceTimeBy(99L)
        assertFalse(restored)
        advanceUntilIdle()
        assertEquals(listOf("stop", "write0", "write1", "restore"), events)
        // The owners remain usable for loading the new data after replacement.
        owners.first().viewModelScope.launch { events += "reload" }
        advanceUntilIdle()
        assertEquals("reload", events.last())
    }
}
