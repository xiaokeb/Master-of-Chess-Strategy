package com.masterofchessstrategy.games.competition_mahjong.auto

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import kotlin.coroutines.coroutineContext

class CompetitionMahjongAutoPlaybackTest {
    private class TimerGate {
        data class Wait(val millis: Long, val signal: CompletableDeferred<Unit>)
        val issued = mutableListOf<Wait>()
        private val requests = Channel<Wait>(Channel.UNLIMITED)
        suspend fun wait(millis: Long) {
            val request = Wait(millis, CompletableDeferred(coroutineContext[Job]))
            issued += request
            requests.send(request)
            request.signal.await()
        }
        suspend fun next(): Wait = withTimeout(2000) {
            while (true) {
                val request = requests.receive()
                if (request.signal.isActive) return@withTimeout request
            }
            error("unreachable")
        }
        fun hasActive(): Boolean = issued.any { it.signal.isActive }
    }
    private class PlaybackHarness(settings: CompetitionMahjongAutoSettings = CompetitionMahjongAutoSettings()) {
        val native = AutoHarness(settings = settings)
        val timers = TimerGate()
        val parent = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val playback = CompetitionMahjongAutoPlayback(native.controller, parent, timers::wait)
        suspend fun until(predicate: (CompetitionMahjongAutoPlaybackState) -> Boolean) =
            withTimeout(2000) { playback.state.first(predicate) }
        suspend fun close() { playback.close(); parent.cancel() }
    }

    @Test fun pausedStartHasNoTimerAndManualStepSavesExactlyOneEvent() = runBlocking {
        val harness = PlaybackHarness()
        try {
            assertTrue(harness.playback.setHost(true))
            assertTrue(harness.playback.open())
            assertFalse(harness.timers.hasActive())
            assertTrue(harness.playback.step())
            assertEquals(1, harness.native.active.advanceCalls)
            assertEquals(1, harness.native.store.load(0)!!.eventsAfterOpening)
            assertFalse(harness.timers.hasActive())
        } finally { harness.close() }
    }

    @Test fun timerPerformsOneEventAndSchedulesOnlyAfterSave() = runBlocking {
        val harness = PlaybackHarness(CompetitionMahjongAutoSettings(paused = false, speed = 1.375f, stepDelayMillis = 550))
        try {
            harness.playback.setHost(true)
            assertTrue(harness.playback.open())
            val first = harness.timers.next()
            assertEquals(400L, first.millis)
            assertEquals(0, harness.native.active.advanceCalls)
            first.signal.complete(Unit)
            harness.until { it.activity.eventsAfterOpening == 1 && !it.busy }
            assertEquals(1, harness.native.store.load(0)!!.eventsAfterOpening)
            assertEquals(1, harness.native.active.advanceCalls)
            assertEquals(400L, harness.timers.next().millis)
        } finally { harness.close() }
    }

    @Test fun pauseInvalidatesTimerAndSpeedChangeReplacesDelayWithoutProgress() = runBlocking {
        val harness = PlaybackHarness(CompetitionMahjongAutoSettings(paused = false))
        try {
            harness.playback.setHost(true)
            harness.playback.open()
            val old = harness.timers.next()
            assertTrue(harness.playback.updateSettings(harness.playback.state.value.activity.settings.copy(paused = true)))
            assertTrue(old.signal.isCancelled)
            assertFalse(old.signal.complete(Unit))
            assertFalse(harness.timers.hasActive())
            assertTrue(harness.playback.updateSettings(harness.playback.state.value.activity.settings.copy(paused = false, speed = 3.125f)))
            assertEquals(128L, harness.timers.next().millis)
            assertEquals(0, harness.native.active.advanceCalls)
        } finally { harness.close() }
    }

    @Test fun backgroundRequiresBothStoredPreferenceAndHostAuthorization() = runBlocking {
        val harness = PlaybackHarness(CompetitionMahjongAutoSettings(paused = false))
        try {
            harness.playback.setHost(true)
            harness.playback.open()
            val foreground = harness.timers.next()
            assertTrue(harness.playback.setHost(false, true))
            assertTrue(foreground.signal.isCancelled)
            assertFalse(harness.timers.hasActive())
            assertFalse(harness.playback.state.value.activity.executionActive)
            assertTrue(harness.playback.updateSettings(harness.playback.state.value.activity.settings.copy(backgroundEnabled = true)))
            assertEquals(3000L, harness.timers.next().millis)
            assertTrue(harness.playback.state.value.activity.executionActive)
            assertTrue(harness.playback.setHost(false, false))
            assertFalse(harness.timers.hasActive())
            assertFalse(harness.playback.state.value.activity.executionActive)
        } finally { harness.close() }
    }

    @Test fun grantedBackgroundUsesSlowerDelayAndReturningForegroundReschedules() = runBlocking {
        val harness = PlaybackHarness(CompetitionMahjongAutoSettings(paused = false, speed = 4f, backgroundEnabled = true))
        try {
            harness.playback.setHost(true, true)
            harness.playback.open()
            val fast = harness.timers.next()
            assertEquals(100L, fast.millis)
            harness.playback.setHost(false, true)
            assertTrue(fast.signal.isCancelled)
            val slow = harness.timers.next()
            assertEquals(3000L, slow.millis)
            slow.signal.complete(Unit)
            harness.until { it.activity.eventsAfterOpening == 1 && !it.busy }
            harness.playback.setHost(true, true)
            assertEquals(100L, harness.timers.next().millis)
            assertEquals(1, harness.native.active.advanceCalls)
        } finally { harness.close() }
    }

    @Test fun saveFailureStopsAllTimersAndRetryDoesNotRepeatEvent() = runBlocking {
        val harness = PlaybackHarness(CompetitionMahjongAutoSettings(paused = false))
        try {
            harness.playback.setHost(true)
            harness.playback.open()
            val timer = harness.timers.next()
            harness.native.store.failNext = true
            timer.signal.complete(Unit)
            harness.until { it.halted && it.activity.needsSave && !it.busy }
            assertFalse(harness.timers.hasActive())
            assertEquals(1, harness.native.active.advanceCalls)
            assertTrue(harness.playback.retrySave())
            assertEquals(1, harness.native.active.advanceCalls)
            assertEquals(1, harness.native.store.load(0)!!.eventsAfterOpening)
            assertFalse(harness.playback.state.value.halted)
            assertEquals(400L, harness.timers.next().millis)
        } finally { harness.close() }
    }

    @Test fun nextHandSaveFailureDoesNotCreateAutomaticRetryLoop() = runBlocking {
        val harness = PlaybackHarness(CompetitionMahjongAutoSettings(paused = false))
        try {
            harness.playback.setHost(true)
            harness.playback.open()
            repeat(3) {
                harness.timers.next().signal.complete(Unit)
                harness.until { state -> state.activity.eventsAfterOpening == it + 1 && !state.busy }
            }
            val terminal = harness.native.active
            val timer = harness.timers.next()
            assertEquals(1500L, timer.millis)
            harness.native.store.failNext = true
            timer.signal.complete(Unit)
            harness.until { it.halted && !it.busy }
            assertFalse(harness.timers.hasActive())
            assertFalse(terminal.closed)
            assertEquals(2, harness.native.sessions.size)
            assertTrue(harness.playback.step())
            assertTrue(terminal.closed)
            assertEquals(1, harness.playback.state.value.activity.handIndex)
            assertTrue(harness.playback.state.value.activity.settings.paused)
        } finally { harness.close() }
    }

    @Test fun manualStepDuringPlaybackPausesBeforeNativeEventAndUndoIsSaved() = runBlocking {
        val harness = PlaybackHarness(CompetitionMahjongAutoSettings(paused = false))
        try {
            harness.playback.setHost(true)
            harness.playback.open()
            val old = harness.timers.next()
            assertTrue(harness.playback.step())
            assertTrue(old.signal.isCancelled)
            assertTrue(harness.native.store.load(0)!!.settings.paused)
            assertFalse(harness.timers.hasActive())
            assertTrue(harness.playback.undo())
            assertEquals(0, harness.native.store.load(0)!!.eventsAfterOpening)
            assertEquals(1, harness.native.active.advanceCalls)
        } finally { harness.close() }
    }

    @Test fun completeSeriesStopsTimersUntilExplicitContinuationIsConfigured() = runBlocking {
        val harness = PlaybackHarness(CompetitionMahjongAutoSettings(paused = false, stepDelayMillis = 0, handBreakMillis = 0))
        try {
            harness.playback.setHost(true)
            harness.playback.open()
            repeat(63) { index ->
                val timer = harness.timers.next()
                assertEquals(16L, timer.millis)
                timer.signal.complete(Unit)
                val accepted = harness.native.sessions.sumOf { it.advanceCalls }
                harness.until { !it.busy && (it.activity.finished || harness.timers.hasActive()) }
                assertTrue("transaction $index", accepted > 0)
            }
            harness.until { it.activity.finished && !it.busy }
            assertFalse(harness.timers.hasActive())
            assertEquals(1, harness.native.store.archives.size)
            assertTrue(harness.playback.updateSettings(harness.playback.state.value.activity.settings.copy(autoContinue = true, maxSeries = 2)))
            harness.timers.next().signal.complete(Unit)
            harness.until { it.activity.seriesIndex == 1 && !it.busy }
            assertEquals(0, harness.playback.state.value.activity.completedHands)
            assertEquals(0, harness.native.active.advanceCalls)
        } finally { harness.close() }
    }

    @Test fun closeCancelsTimersClosesNativeAndRejectsFurtherCommands() = runBlocking {
        val harness = PlaybackHarness(CompetitionMahjongAutoSettings(paused = false))
        harness.playback.setHost(true)
        harness.playback.open()
        val timer = harness.timers.next()
        assertTrue(harness.playback.close())
        assertTrue(timer.signal.isCancelled)
        assertTrue(harness.native.sessions.all { it.closed })
        assertTrue(harness.playback.state.value.activity.closed)
        assertFalse(harness.playback.step())
        assertFalse(harness.playback.setHost(true))
        harness.parent.cancel()
    }

    @Test fun parentCancellationClosesNativeAndNoTimerCanAdvanceAfterward() = runBlocking {
        val harness = PlaybackHarness(CompetitionMahjongAutoSettings(paused = false))
        harness.playback.setHost(true)
        harness.playback.open()
        val timer = harness.timers.next()
        harness.parent.cancel()
        harness.until { it.activity.closed }
        assertTrue(timer.signal.isCancelled)
        assertTrue(harness.native.sessions.all { it.closed })
    }
}
