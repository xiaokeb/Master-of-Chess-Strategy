package com.masterofchessstrategy.games.go

import com.masterofchessstrategy.engine.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalCoroutinesApi::class)
class GoReplayControllerTest {
    @Test fun loadsInitialFrameAndStepsWithoutGameActions() = runTest {
        val reader = Reader()
        val controller = GoReplayController(this, { reader }, StandardTestDispatcher(testScheduler))
        runCurrent()
        assertTrue(controller.state.value.ready)
        controller.next(); runCurrent()
        assertEquals(1, controller.state.value.frame!!.eventIndex)
        controller.last(); runCurrent()
        assertEquals(3, controller.state.value.frame!!.eventIndex)
        controller.first(); runCurrent()
        assertEquals(0, controller.state.value.frame!!.eventIndex)
        controller.closeAndJoin()
    }

    @Test fun failedInitialFrameClosesCandidateAndReportsLoadFailure() = runTest {
        val reader = Reader().apply { brokenFrame = true }
        val controller = GoReplayController(this, { reader }, StandardTestDispatcher(testScheduler))
        runCurrent()
        assertFalse(controller.state.value.ready)
        assertNotNull(controller.state.value.error)
        assertEquals(1, reader.closed)
        controller.closeAndJoin()
        assertEquals(1, reader.closed)
    }

    @Test fun playbackHonorsSpeedAndStopsAtLastEvent() = runTest {
        val reader = Reader()
        val controller = GoReplayController(this, { reader }, StandardTestDispatcher(testScheduler))
        runCurrent()
        controller.setSpeed(4f); controller.play(); runCurrent()
        advanceTimeBy(249); runCurrent()
        assertEquals(0, controller.state.value.frame!!.eventIndex)
        advanceTimeBy(1); runCurrent()
        assertEquals(1, controller.state.value.frame!!.eventIndex)
        advanceTimeBy(500); runCurrent()
        assertEquals(3, controller.state.value.frame!!.eventIndex)
        assertFalse(controller.state.value.playing)
        val calls = reader.seeks
        advanceTimeBy(10_000); runCurrent()
        assertEquals(calls, reader.seeks)
        controller.closeAndJoin()
    }

    @Test fun pauseAndManualSeekCancelPendingPlayback() = runTest {
        val controller = GoReplayController(this, { Reader() }, StandardTestDispatcher(testScheduler))
        runCurrent()
        controller.play(); runCurrent()
        advanceTimeBy(500)
        controller.pause()
        advanceTimeBy(2_000); runCurrent()
        assertEquals(0, controller.state.value.frame!!.eventIndex)
        controller.play(); runCurrent()
        controller.seek(2); runCurrent()
        advanceTimeBy(2_000); runCurrent()
        assertEquals(2, controller.state.value.frame!!.eventIndex)
        assertFalse(controller.state.value.playing)
        controller.closeAndJoin()
    }

    @Test fun failedSeekKeepsFrameAndAllowsRetry() = runTest {
        val reader = Reader()
        val controller = GoReplayController(this, { reader }, StandardTestDispatcher(testScheduler))
        runCurrent()
        reader.brokenSeek = true
        controller.next(); runCurrent()
        assertEquals(0, controller.state.value.frame!!.eventIndex)
        assertFalse(controller.state.value.seeking)
        assertNotNull(controller.state.value.error)
        reader.brokenSeek = false
        controller.next(); runCurrent()
        assertEquals(1, controller.state.value.frame!!.eventIndex)
        assertNull(controller.state.value.error)
        controller.closeAndJoin()
    }

    @Test fun cancelledInFlightSeekCannotPublishStaleFrame() = runTest {
        val reader = Reader()
        val controller = GoReplayController(this, { reader }, StandardTestDispatcher(testScheduler))
        runCurrent()
        reader.hook = { controller.pause() }
        controller.next(); runCurrent()
        assertEquals(0, controller.state.value.frame!!.eventIndex)
        assertFalse(controller.state.value.seeking)
        reader.hook = null
        controller.seek(2); runCurrent()
        assertEquals(2, controller.state.value.frame!!.eventIndex)
        controller.closeAndJoin()
    }

    @Test fun closeIsIdempotentAndRejectsLaterCommands() = runTest {
        val reader = Reader()
        val controller = GoReplayController(this, { reader }, StandardTestDispatcher(testScheduler))
        runCurrent()
        controller.play(); runCurrent()
        controller.closeAndJoin(); controller.closeAndJoin()
        controller.next(); controller.play(); controller.setSpeed(2f)
        advanceTimeBy(2_000); runCurrent()
        assertEquals(1, reader.closed)
        assertEquals(0, reader.seeks)
        assertTrue(controller.state.value.closed)
        assertEquals(1f, controller.state.value.speed)
    }

    @Test fun rejectsInvalidSpeedAndCursorBeforeNativeWork() = runTest {
        val reader = Reader()
        val controller = GoReplayController(this, { reader }, StandardTestDispatcher(testScheduler))
        runCurrent()
        for (speed in listOf(Float.NaN, Float.POSITIVE_INFINITY, 0.49f, 4.01f)) {
            try { controller.setSpeed(speed); fail("Invalid speed accepted") } catch (_: IllegalArgumentException) { }
        }
        for (index in listOf(-1, 4)) {
            try { controller.seek(index); fail("Invalid cursor accepted") } catch (_: IllegalArgumentException) { }
        }
        assertEquals(0, reader.seeks)
        controller.closeAndJoin()
    }

    @Test fun disposalWaitsForBlockedWorkerBeforeClosingReader() = runBlocking {
        val executor = Executors.newSingleThreadExecutor()
        val worker = executor.asCoroutineDispatcher()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val reader = Reader()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val controller = GoReplayController(scope, { reader }, worker)
        try {
            withTimeout(5_000) { while (!controller.state.value.ready) delay(5) }
            reader.hook = { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
            controller.next()
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val closing = async { controller.closeAndJoin() }
            withTimeout(5_000) { while (!controller.state.value.closed) delay(5) }
            assertFalse(closing.isCompleted)
            assertEquals(0, reader.closed)
            release.countDown()
            withTimeout(5_000) { closing.await() }
            assertEquals(1, reader.closed)
            assertEquals(0, controller.state.value.frame!!.eventIndex)
        } finally {
            release.countDown()
            controller.closeAndJoin()
            scope.cancel(); worker.close(); executor.shutdown()
        }
    }

    private class Reader : GoReplayReader {
        var index = 0
        @Volatile var closed = 0
        var seeks = 0
        var brokenFrame = false
        var brokenSeek = false
        var hook: (() -> Unit)? = null
        override fun frame(): GoReplayFrame {
            check(!brokenFrame)
            return GoReplayFrame(9, 0, 15, List(81) { 0 }, GoPlayer.BLACK, GoPhase.PLAYING,
                GameResult.ONGOING, index, 3, index, 3, null, null, emptySet(), false, false, null, null)
        }
        override fun seek(eventIndex: Int, cancelled: () -> Boolean): GoReplayFrame {
            seeks++
            hook?.invoke()
            if (cancelled()) throw CancellationException()
            check(!brokenSeek)
            index = eventIndex
            return frame()
        }
        override fun exportSgf() = "whole-record"
        override fun close() { closed++ }
    }
}
