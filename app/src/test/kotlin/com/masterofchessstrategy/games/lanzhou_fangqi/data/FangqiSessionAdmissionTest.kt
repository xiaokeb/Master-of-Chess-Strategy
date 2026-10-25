package com.masterofchessstrategy.games.lanzhou_fangqi.data

import com.masterofchessstrategy.engine.Difficulty
import com.masterofchessstrategy.games.lanzhou_fangqi.FangqiStoredMode
import com.masterofchessstrategy.games.lanzhou_fangqi.FangqiStoredSession
import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

/** The canonicalizer is a test double here. Real Native replay is covered by the instrumented suite. */
class FangqiSessionAdmissionTest {
    @Test fun retainsBytesAndEveryMetadataFieldWithoutTheXiangqi64KiBLimit() {
        val bytes = history(FangqiSessionAdmission.MAX_ACTIONS)
        assertTrue(bytes.size > 65_536)
        val input = stored(bytes, FangqiStoredMode.AI_AUTO_PLAY, Difficulty.MASTER)
        val output = FangqiSessionAdmission { it }.admit(input)
        assertEquals(FangqiSessionAdmission.MAX_ACTIONS, FangqiSessionAdmission.inspectHistory(output.engineState))
        assertArrayEquals(bytes, output.engineState)
        assertEquals(input.sessionId, output.sessionId)
        assertEquals(input.mode, output.mode)
        assertEquals(input.difficulty, output.difficulty)
        assertEquals(input.updatedAtEpochMillis, output.updatedAtEpochMillis)
        assertEquals(input.acceptedActionCount, output.acceptedActionCount)
        assertEquals(Int.MAX_VALUE, output.undoUseCount)
        assertEquals(Int.MAX_VALUE, output.hintUseCount)
        bytes.fill(0)
        output.engineState.fill(0)
        assertEquals('M'.code.toByte(), output.engineState[0])
        assertEquals('M'.code.toByte(), input.engineState[0])
    }

    @Test fun rejectsUnsupportedTruncatedAndOversizedHistoriesBeforeCallingNative() {
        var calls = 0
        val admission = FangqiSessionAdmission { calls++; it }
        val invalid = listOf(
            history().toString(Charsets.US_ASCII).replace("|1|1|", "|2|1|").toByteArray(),
            history().toString(Charsets.US_ASCII).replace("|0|", "|1|").toByteArray(),
            history().toString(Charsets.US_ASCII).replace("|0|", "|00|").toByteArray(),
            history(1).toString(Charsets.US_ASCII).plus(';').toByteArray(),
            history(16_385),
            byteArrayOf(0, 1, 2),
        )
        invalid.forEach { bytes -> expectInvalid { admission.admit(stored(bytes)) } }
        expectInvalid { FangqiSessionAdmission.inspectHistory(ByteArray(1_048_577) { 32 }) }
        assertEquals(0, calls)
    }

    @Test fun rejectsNativeRejectionAndNoncanonicalReadBackWithoutChangingSource() {
        val input = stored(history(1))
        val source = input.engineState
        expectInvalid { FangqiSessionAdmission { throw IOException("illegal history") }.admit(input) }
        expectInvalid { FangqiSessionAdmission { history() }.admit(input) }
        assertArrayEquals(source, input.engineState)
    }

    companion object {
        internal fun history(count: Int = 0): ByteArray =
            ("MOCS-LFQ|1|1|" + "0".repeat(49) + "|$count|" +
                List(count) { "m,1,0,1" }.joinToString(";")).toByteArray(Charsets.US_ASCII)

        internal fun stored(bytes: ByteArray = history(), mode: FangqiStoredMode = FangqiStoredMode.LOCAL_TWO_PLAYER,
            difficulty: Difficulty? = null) = FangqiStoredSession(mode, difficulty, bytes,
            "方棋-完整历史", 1_792_814_400_123L, 16_384, Int.MAX_VALUE, Int.MAX_VALUE)

        internal fun expectInvalid(block: () -> Unit) {
            try { block(); fail("Expected IOException") } catch (_: IOException) { }
        }
    }
}
