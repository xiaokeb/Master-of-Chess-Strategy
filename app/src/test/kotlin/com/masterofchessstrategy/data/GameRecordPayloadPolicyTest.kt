package com.masterofchessstrategy.data

import com.masterofchessstrategy.engine.GameType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GameRecordPayloadPolicyTest {
    @Test fun xiangqiKeepsItsHistoricalMarkerAnd64KiBBoundary() {
        assertEquals(2, GameRecordPayloadPolicy.currentVersion(GameType.CHINESE_CHESS))
        assertTrue(GameRecordPayloadPolicy.accepts(GameType.CHINESE_CHESS, 2, ByteArray(65_536)))
        assertFalse(GameRecordPayloadPolicy.accepts(GameType.CHINESE_CHESS, 2, ByteArray(65_537)))
        assertFalse(GameRecordPayloadPolicy.accepts(GameType.CHINESE_CHESS, 3, byteArrayOf(1)))
        // This envelope gate must not reinterpret an old Xiangqi payload as MOCG.
        assertTrue(GameRecordPayloadPolicy.accepts(GameType.CHINESE_CHESS, 2, byteArrayOf(1, 2, 3)))
    }

    @Test fun currentGoMaximumDoesNotWidenOtherGames() {
        assertEquals(3, GameRecordPayloadPolicy.currentVersion(GameType.GO))
        assertEquals(344_484, GameRecordPayloadPolicy.maximumBytes(GameType.GO))
        assertTrue(GameRecordPayloadPolicy.accepts(GameType.GO, 3, frame(3, 16_386)))
        assertFalse(GameRecordPayloadPolicy.accepts(GameType.GO, 3, frame(3, 16_387)))
        assertFalse(GameRecordPayloadPolicy.accepts(GameType.CHINESE_CHESS, 2, frame(3, 16_386)))
    }

    @Test fun customGoHeaderIncludesOriginalActorAndBoardBeforeEvents() {
        for (size in listOf(9, 13, 19)) {
            val bytes = frame(6, 2, size)
            assertTrue(GameRecordPayloadPolicy.accepts(GameType.GO, 6, bytes))
            assertFalse(GameRecordPayloadPolicy.accepts(GameType.GO, 6, bytes + 0))
            for ((offset, invalid) in listOf(7 to 2, 16 to 2, 17 to 3)) {
                assertFalse(GameRecordPayloadPolicy.accepts(GameType.GO, 6,
                    bytes.copyOf().apply { this[offset] = invalid.toByte() }))
            }
        }
        assertTrue(GameRecordPayloadPolicy.accepts(GameType.GO, 6, frame(6, 16_386, 19)))
        assertFalse(GameRecordPayloadPolicy.accepts(GameType.GO, 6, frame(6, 16_387, 19)))
    }

    @Test fun allGoVersionsKeepTheirExactHeaderAndEventWidths() {
        for (version in 1..5) {
            val bytes = frame(version, 2)
            assertTrue(GameRecordPayloadPolicy.accepts(GameType.GO, version, bytes))
            assertFalse(GameRecordPayloadPolicy.accepts(GameType.GO, version, bytes + 0))
            assertFalse(GameRecordPayloadPolicy.accepts(GameType.GO, version, bytes.copyOf(bytes.size - 1)))
            assertFalse(GameRecordPayloadPolicy.accepts(GameType.GO, version % 5 + 1, bytes))
        }
        assertTrue(GameRecordPayloadPolicy.accepts(GameType.GO, 1, frame(1, 4_096)))
        assertFalse(GameRecordPayloadPolicy.accepts(GameType.GO, 1, frame(1, 4_097)))
    }

    @Test fun damagedUnknownAndUnsignedOverflowHeadersAreRejected() {
        assertFalse(GameRecordPayloadPolicy.accepts(GameType.GO, 3, byteArrayOf()))
        assertFalse(GameRecordPayloadPolicy.accepts(GameType.GO, 3, frame(3, 0).copyOf(12)))
        for (offset in 0..4) {
            val damaged = frame(3, 0).apply { this[offset] = 99 }
            assertFalse(GameRecordPayloadPolicy.accepts(GameType.GO, 3, damaged))
        }
        val overflow = frame(3, 0).apply { for (offset in 12..15) this[offset] = 0xff.toByte() }
        assertFalse(GameRecordPayloadPolicy.accepts(GameType.GO, 3, overflow))
    }

    @Test fun everyNonGoFamilyPreservesItsLegacyEnvelopeWithoutAcquiringGoCapacity() {
        for (game in GameType.entries.filter { it != GameType.GO && it != GameType.LANZHOU_SQUARE_CHESS }) {
            assertEquals(2, GameRecordPayloadPolicy.currentVersion(game))
            assertTrue(GameRecordPayloadPolicy.accepts(game, 2, byteArrayOf(1)))
            assertFalse(GameRecordPayloadPolicy.accepts(game, 3, byteArrayOf(1)))
            assertFalse(GameRecordPayloadPolicy.accepts(game, 2, frame(3, 16_386)))
        }
    }

    @Test fun boardKomiHandicapAndProposalClockMatchNativeHeaderRestrictions() {
        for (size in listOf(9, 13, 19)) {
            assertTrue(GameRecordPayloadPolicy.accepts(GameType.GO, 3, frame(3, 0).apply { this[5] = size.toByte() }))
        }
        assertFalse(GameRecordPayloadPolicy.accepts(GameType.GO, 3, frame(3, 0).apply { this[5] = 11 }))
        assertFalse(GameRecordPayloadPolicy.accepts(GameType.GO, 2, frame(2, 0).apply { this[6] = 7 }))
        assertFalse(GameRecordPayloadPolicy.accepts(GameType.GO, 3, frame(3, 0).apply { this[7] = 5 }))
        assertTrue(GameRecordPayloadPolicy.accepts(GameType.GO, 3, frame(3, 0).apply { this[6] = 0; this[7] = 5 }))
        assertFalse(GameRecordPayloadPolicy.accepts(GameType.GO, 3, frame(3, 0).apply { this[11] = 0x80.toByte() }))
    }

    companion object {
        /** Structural fixtures only. Zero events are not proof of a legal, completed game. */
        fun frame(version: Int, events: Int, size: Int = 9): ByteArray {
            val baseHeader = when (version) { 1 -> 10; 2 -> 11; else -> 16 }
            val header = baseHeader + if (version == 6) 1 + size * size else 0
            val width = when (version) { 1 -> 3; 2 -> 17; else -> 21 }
            return ByteArray(header + events * width).apply {
                "MOCG".toByteArray(Charsets.US_ASCII).copyInto(this)
                this[4] = version.toByte(); this[5] = size.toByte()
                if (version != 1) this[6] = 15
                repeat(4) { this[baseHeader - 4 + it] = (events ushr (8 * it)).toByte() }
            }
        }
    }
}
