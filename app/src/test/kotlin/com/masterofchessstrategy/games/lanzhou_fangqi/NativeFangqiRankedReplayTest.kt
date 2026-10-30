package com.masterofchessstrategy.games.lanzhou_fangqi

import com.masterofchessstrategy.engine.Difficulty
import com.masterofchessstrategy.engine.GameResult
import com.masterofchessstrategy.engine.games.lanzhou_fangqi.FangqiAction
import com.masterofchessstrategy.engine.games.lanzhou_fangqi.FangqiMoveResult
import com.masterofchessstrategy.engine.games.lanzhou_fangqi.FangqiSession
import com.masterofchessstrategy.engine.games.lanzhou_fangqi.FangqiSide
import com.masterofchessstrategy.engine.games.lanzhou_fangqi.FangqiSnapshot
import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

/** Resource/ownership failures with an explicit fake factory; actual JNI is tested on Android. */
class NativeFangqiRankedReplayTest {
    private val history = FangqiRankedLedger.standardInitialHistory()

    @Test fun rejectsMainThreadBeforeCreatingAnyNativeResource() {
        var created = false
        val rejected = IllegalStateException("background required")
        val replay = NativeFangqiRankedReplay({ created = true; FakeSession() }, { throw rejected })
        assertSame(rejected, failure { replay.replay(history) })
        assertFalse(created)
    }

    @Test fun creationFailurePropagatesWithoutRetryOrFallback() {
        var calls = 0
        val error = UnsatisfiedLinkError("native unavailable")
        val replay = NativeFangqiRankedReplay({ calls++; throw error }, {})
        assertSame(error, failure { replay.replay(history) })
        assertEquals(1, calls)
    }

    @Test fun replayReadsCanonicalHistoryAndSnapshotFromOneFreshSessionThenCloses() {
        val session = FakeSession()
        session.snapshotValue = session.snapshotValue.copy(result = GameResult.SECOND_PLAYER_WIN, pendingCaptures = 0)
        val actual = replay(session).replay(history)
        assertEquals(history, actual.canonicalHistory)
        assertEquals(GameResult.SECOND_PLAYER_WIN, actual.result)
        assertEquals(0, actual.pendingCaptures)
        assertEquals(listOf("restore", "serialize", "snapshot", "close"), session.calls)
        assertArrayEquals(history.bytes, session.restoreBytes)
    }

    @Test fun pendingCapturesComeFromTheSameRestoredSnapshot() {
        val session = FakeSession()
        session.snapshotValue = session.snapshotValue.copy(pendingCaptures = 2)
        val actual = replay(session).replay(history)
        assertEquals(GameResult.ONGOING, actual.result)
        assertEquals(2, actual.pendingCaptures)
    }

    @Test fun everyCallCreatesItsOwnResourceAndClosesItExactlyOnce() {
        val sessions = mutableListOf<FakeSession>()
        val replay = NativeFangqiRankedReplay({ FakeSession().also(sessions::add) }, {})
        replay.replay(history)
        replay.replay(history)
        assertEquals(2, sessions.size)
        assertNotSame(sessions[0], sessions[1])
        assertTrue(sessions.all { it.calls.count { call -> call == "close" } == 1 })
    }

    @Test fun refusedRestoreClosesWithoutReadingResult() {
        val session = FakeSession().apply { restoreAccepted = false }
        assertTrue(failure { replay(session).replay(history) } is IOException)
        assertEquals(listOf("restore", "close"), session.calls)
    }

    @Test fun restoreSerializeAndSnapshotExceptionsKeepTheirIdentityAndAlwaysClose() {
        for (stage in listOf("restore", "serialize", "snapshot")) {
            val error = IllegalStateException(stage)
            val session = FakeSession().apply { failAt = stage; operationFailure = error }
            assertSame(error, failure { replay(session).replay(history) })
            assertEquals("close", session.calls.last())
            assertEquals(1, session.calls.count { it == "close" })
        }
    }

    @Test fun noncanonicalBytesCloseWithoutReturningSnapshot() {
        val session = FakeSession().apply { canonicalBytes = "different".toByteArray() }
        assertTrue(failure { replay(session).replay(history) } is IOException)
        assertEquals(listOf("restore", "serialize", "close"), session.calls)
    }

    @Test fun cleanupFailureAfterSuccessfulReplayPreventsAnySuccessResult() {
        val error = IllegalStateException("release failed")
        val session = FakeSession().apply { closeFailure = error }
        assertSame(error, failure { replay(session).replay(history) })
        assertEquals(1, session.calls.count { it == "close" })
    }

    @Test fun cleanupFailureIsSuppressedOnOriginalFailureRatherThanSwallowedOrReplacingIt() {
        val original = IOException("snapshot failed")
        val closing = IllegalStateException("release failed")
        val session = FakeSession().apply { failAt = "snapshot"; operationFailure = original; closeFailure = closing }
        val actual = failure { replay(session).replay(history) }
        assertSame(original, actual)
        assertEquals(listOf(closing), actual.suppressed.toList())
    }

    @Test fun cancellationAlsoClosesAndPropagatesTheOriginalCancellation() {
        val cancelled = kotlinx.coroutines.CancellationException("owner retired")
        val session = FakeSession().apply { failAt = "restore"; operationFailure = cancelled }
        assertSame(cancelled, failure { replay(session).replay(history) })
        assertEquals(listOf("restore", "close"), session.calls)
    }

    @Test fun restoreGetsACopyAndSerializedOutputIsOwnedBeforeSnapshotAndClose() {
        val session = FakeSession().apply { mutateRestoreInput = true; mutateSerializedOnClose = true }
        val before = history.bytes
        val actual = replay(session).replay(history)
        assertArrayEquals(before, history.bytes)
        assertArrayEquals(before, actual.canonicalHistory.bytes)
        assertTrue(session.canonicalBytes.all { it == 0.toByte() })
    }

    private fun replay(session: FakeSession) = NativeFangqiRankedReplay({ session }, {})

    private fun failure(action: () -> Unit): Throwable {
        try { action() } catch (error: Throwable) { return error }
        throw AssertionError("failure was not propagated")
    }

    private inner class FakeSession : FangqiSession {
        val calls = mutableListOf<String>()
        var restoreAccepted = true
        var restoreBytes: ByteArray? = null
        var canonicalBytes = history.bytes
        var snapshotValue = FangqiSnapshot(List(49) { 0 }, FangqiSide.RED, 0, GameResult.ONGOING, false)
        var failAt: String? = null
        var operationFailure: Throwable = IOException("operation failed")
        var closeFailure: Throwable? = null
        var mutateRestoreInput = false
        var mutateSerializedOnClose = false

        private fun enter(stage: String) {
            calls += stage
            if (failAt == stage) throw operationFailure
        }
        override fun restore(data: ByteArray): Boolean {
            enter("restore")
            restoreBytes = data.copyOf()
            if (mutateRestoreInput) data.fill(0)
            return restoreAccepted
        }
        override fun serialize(): ByteArray { enter("serialize"); return canonicalBytes }
        override fun snapshot(): FangqiSnapshot { enter("snapshot"); return snapshotValue }
        override fun close() {
            calls += "close"
            if (mutateSerializedOnClose) canonicalBytes.fill(0)
            closeFailure?.let { throw it }
        }
        override fun apply(action: FangqiAction): FangqiMoveResult = error("unused")
        override fun legalActions(): List<FangqiAction> = error("unused")
        override fun undo(): Boolean = error("unused")
        override fun reset(): Unit = error("unused")
        override fun chooseAction(difficulty: Difficulty): FangqiAction? = error("unused")
    }
}
