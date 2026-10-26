package com.masterofchessstrategy.games.lanzhou_mahjong.data

import com.masterofchessstrategy.engine.Difficulty
import com.masterofchessstrategy.games.lanzhou_mahjong.LanzhouMahjongAssistanceUsage
import com.masterofchessstrategy.games.lanzhou_mahjong.LanzhouMahjongDecisionPoint
import com.masterofchessstrategy.games.lanzhou_mahjong.LanzhouMahjongSessionRecord
import com.masterofchessstrategy.games.lanzhou_mahjong.LanzhouMahjongSessionRecordCodec
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import java.util.zip.CRC32
import org.junit.Assert.*
import org.junit.Test

/** Explicit fake Native canonicalizers exercise admission boundaries, never JNI or legal replay. */
class LanzhouMahjongSessionAdmissionTest {
    private val passthrough = LanzhouMahjongSessionAdmission { it.nativeState }

    @Test fun preservesBothNativeRulesAndEveryFixedViewerWithoutChangingOldIdAlgorithm() {
        for (rule in 1..2) for (viewer in 0..3) {
            val source = record(rule = rule, viewer = viewer)
            val row = passthrough.createRow(source, 42)
            assertEquals(69, row.sessionId.length)
            assertTrue(row.sessionId.matches(Regex("lzmj-[0-9a-f]{64}")))
            val digest = MessageDigest.getInstance("SHA-256")
            digest.update(source.nativeState, 0, 122)
            digest.update(viewer.toByte())
            digest.update(source.difficulty.code.toByte())
            assertEquals("lzmj-" + digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }, row.sessionId)
            assertArrayEquals(source.nativeState, passthrough.admitRow(row).nativeState)
            assertEquals(rule, passthrough.admitRow(row).nativeState[4].toInt())
        }
    }

    @Test fun legacyOuterV1IsAdmittedAndNewWriteUpgradesOnlyTheOuterEnvelope() {
        for (nativeRule in 1..2) {
            val record = record(rule = nativeRule)
            val v2 = passthrough.createRow(record, 42)
            val v1 = LanzhouMahjongSessionRecordCodec.encode(record).copyOf(174)
            v1[4] = 1
            crc(v1)
            val legacyRow = v2.copy(engineFormatVersion = 1, engineState = v1)
            val restored = passthrough.admitRow(legacyRow)
            val newRow = passthrough.createRow(restored, 43)
            assertEquals(v2.sessionId, newRow.sessionId)
            assertEquals(2, newRow.engineFormatVersion)
            assertEquals(2, newRow.engineState[4].toInt())
            assertEquals(nativeRule, restored.nativeState[4].toInt())
            assertArrayEquals(record.nativeState, restored.nativeState)
            assertArrayEquals(v1, legacyRow.engineState)
        }
    }

    @Test fun usageAndDecisionSeedRemainInsideLzsrDespiteZeroLegacyColumns() {
        val source = record(actions = 1, undoUsed = 2, hintUsed = 3,
            decisions = listOf(LanzhouMahjongDecisionPoint(0, 55)))
        val row = passthrough.createRow(source, 123)
        assertEquals(0, row.undoUseCount)
        assertEquals(0, row.hintUseCount)
        assertEquals(1, row.acceptedMoveCount)
        val restored = passthrough.admitRow(row)
        assertEquals(2, restored.assistance.undoUsed)
        assertEquals(3, restored.assistance.hintUsed)
        assertEquals(source.decisions, restored.decisions)
        assertEquals(19L, restored.nextChoiceSeed)
    }

    @Test fun metadataAndOtherGameFieldsRejectBeforeCallingNative() {
        var calls = 0
        val admission = LanzhouMahjongSessionAdmission { calls++; it.nativeState }
        val row = passthrough.createRow(record(), 42)
        val invalid = listOf(row.copy(gameTypeCode = 0), row.copy(modeCode = 0),
            row.copy(envelopeVersion = 2), row.copy(engineFormatVersion = 1),
            row.copy(difficultyCode = 3), row.copy(difficultyCode = null), row.copy(playerIndex = 1),
            row.copy(sessionVariantId = ""), row.copy(sessionId = "new-id"),
            row.copy(sessionId = row.sessionId.uppercase()), row.copy(acceptedMoveCount = 1),
            row.copy(updatedAtEpochMillis = -1), row.copy(undoUseCount = 1), row.copy(hintUseCount = 1),
            row.copy(resultOverrideCode = 0), row.copy(timeControlMinutes = 0),
            row.copy(redRemainingMillis = 0), row.copy(blackRemainingMillis = 0),
            row.copy(turnStartedAtEpochMillis = 0), row.copy(pendingDrawOfferSideCode = 0),
            row.copy(autoPlayPaused = true), row.copy(autoPlaySpeedPermille = 999),
            row.copy(completedAutoGames = 1))
        for (bad in invalid) assertThrows(IOException::class.java) { admission.admitRow(bad) }
        assertEquals(0, calls)
        assertArrayEquals(row.engineState, passthrough.createRow(record(), 42).engineState)
    }

    @Test fun damagedAndFutureEnvelopesRejectBeforeCallingNative() {
        var calls = 0
        val admission = LanzhouMahjongSessionAdmission { calls++; it.nativeState }
        val row = passthrough.createRow(record(), 42)
        val future = row.engineState.copyOf().also { it[4] = 3; crc(it) }
        val damaged = row.engineState.copyOf().also { it[50] = 99 }
        for (bytes in listOf(byteArrayOf(), byteArrayOf(1), future, damaged,
            ByteArray(LanzhouMahjongSessionRecordCodec.MAX_BYTES + 1))) {
            assertThrows(IOException::class.java) { admission.admitRow(row.copy(engineState = bytes)) }
        }
        assertEquals(0, calls)
    }

    @Test fun structurallyValidButRejectedHistoryDoesNotMutateTheSuppliedRow() {
        val row = passthrough.createRow(record(actions = 1), 42)
        val before = row.engineState.copyOf()
        val admission = LanzhouMahjongSessionAdmission { throw IllegalStateException("fake native rejects history") }
        assertThrows(IOException::class.java) { admission.admitRow(row) }
        assertArrayEquals(before, row.engineState)
    }

    @Test fun nativeNormalizationOrRuleUpgradeIsRejectedInsteadOfSilentlyReplacingHistory() {
        val source = record(rule = 1)
        val row = passthrough.createRow(source, 42)
        val upgraded = record(rule = 2).nativeState
        val admission = LanzhouMahjongSessionAdmission { upgraded }
        assertThrows(IOException::class.java) { admission.admitRow(row) }
        assertEquals(1, source.nativeState[4].toInt())
        assertArrayEquals(source.nativeState, LanzhouMahjongSessionRecordCodec.decode(row.engineState)!!.nativeState)
    }

    @Test fun admittedRecordOwnsNativeAndEnvelopeBytesIndependentlyOfCallerAndVerifier() {
        val source = record()
        val row = passthrough.createRow(source, 42)
        val expected = source.nativeState
        var returned: ByteArray? = null
        val admission = LanzhouMahjongSessionAdmission { candidate ->
            candidate.nativeState.also { returned = it }
        }
        val admitted = admission.admitRow(row)
        row.engineState.fill(0)
        returned!!.fill(0)
        admitted.nativeState.fill(0)
        assertArrayEquals(expected, admitted.nativeState)
        assertArrayEquals(expected, source.nativeState)
    }

    @Test fun cancellationIsNotWrappedAsCorruptionAndNegativeTimeDoesNotCallNative() {
        val cancelled = CancellationException("cancel verifier")
        val admission = LanzhouMahjongSessionAdmission { throw cancelled }
        assertSame(cancelled, assertThrows(CancellationException::class.java) { admission.admit(record()) })
        var calls = 0
        val counting = LanzhouMahjongSessionAdmission { calls++; it.nativeState }
        assertThrows(IOException::class.java) { counting.createRow(record(), -1) }
        assertEquals(0, calls)
    }

    private fun record(rule: Int = 2, viewer: Int = 0, actions: Int = 0,
        undoUsed: Int = 0, hintUsed: Int = 0,
        decisions: List<LanzhouMahjongDecisionPoint> = emptyList()): LanzhouMahjongSessionRecord {
        // Envelope-only fixture. The injected fake verifier deliberately makes no legality claim.
        val bytes = ByteArray(130 + actions * 3)
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put(byteArrayOf(76, 90, 77, 74, rule.toByte(), 0))
        buffer.putLong(1)
        repeat(108) { buffer.put((it / 4).toByte()) }
        buffer.putInt(actions)
        repeat(actions) { buffer.put(byteArrayOf(1, viewer.toByte(), 0)) }
        var hash = 0x811c9dc5.toInt()
        for (index in 0 until bytes.size - 4) hash = (hash xor (bytes[index].toInt() and 255)) * 16777619
        buffer.putInt(hash)
        return LanzhouMahjongSessionRecord(Difficulty.EASY, viewer, 7, 0, 1, 19, bytes,
            LanzhouMahjongAssistanceUsage(Difficulty.EASY, undoUsed, hintUsed), decisions)
    }

    private fun crc(bytes: ByteArray) {
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(bytes.size - 4,
            CRC32().apply { update(bytes, 0, bytes.size - 4) }.value.toInt())
    }
}
