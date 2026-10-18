package com.masterofchessstrategy.engine

import java.util.zip.CRC32
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChineseChessRuleRevisionDecoderTest {
    @Test
    fun supportedLayoutsExposeOnlyThePinnedRuleRevision() {
        for (version in 2..4) {
            assertEquals(ChineseChessRuleRevision.LEGACY_COMPATIBILITY,
                ChineseChessRuleRevisionDecoder.decode(revisionSnapshot(version, 0)))
        }
        assertEquals(ChineseChessRuleRevision.CONTINUOUS_CHECKING_MATE,
            ChineseChessRuleRevisionDecoder.decode(revisionSnapshot(4, 1)))
    }

    @Test
    fun unknownMarkerAndMalformedLayoutNeverFallBackToLegacy() {
        for (revision in listOf(2, 255)) {
            assertNull(ChineseChessRuleRevisionDecoder.decode(revisionSnapshot(4, revision)))
        }
        val valid = revisionSnapshot(4, 1)
        for (data in listOf(valid.copyOf(valid.size - 1), valid.copyOf(valid.size + 1))) {
            resealRevisionSnapshot(data)
            assertNull(ChineseChessRuleRevisionDecoder.decode(data))
        }
        val damaged = valid.copyOf().apply { this[size - 5] = 0 }
        assertNull(ChineseChessRuleRevisionDecoder.decode(damaged))
        val otherGame = valid.copyOf().apply { this[5] = 1 }
        resealRevisionSnapshot(otherGame)
        assertNull(ChineseChessRuleRevisionDecoder.decode(otherGame))
        val future = valid.copyOf().apply { this[4] = 5 }
        resealRevisionSnapshot(future)
        assertNull(ChineseChessRuleRevisionDecoder.decode(future))
        assertNull(ChineseChessRuleRevisionDecoder.decode(byteArrayOf(1)))
    }

    @Test
    fun claimEventsRemainBeforeTheMarkerAndPartOfTheChecksum() {
        val initial = revisionSnapshot(4, 1)
        val claims = initial.copyOf(initial.size + 3)
        claims[102] = 1 // One u16 claim count, then ply 0 and red claimant.
        claims[104] = 0
        claims[105] = 0
        claims[106] = 0
        claims[107] = 1
        resealRevisionSnapshot(claims)
        assertEquals(ChineseChessRuleRevision.CONTINUOUS_CHECKING_MATE,
            ChineseChessRuleRevisionDecoder.decode(claims))
        claims[106] = 1
        assertNull(ChineseChessRuleRevisionDecoder.decode(claims))
    }
}

internal fun revisionSnapshot(version: Int, revision: Int = 0): ByteArray {
    val bytes = ByteArray(102 + (if (version >= 3) 2 else 0) + (if (version == 4) 1 else 0) + 4)
    "MOCX".encodeToByteArray().copyInto(bytes)
    bytes[4] = version.toByte()
    bytes[12 + 4] = 0x81.toByte()
    bytes[12 + 85] = 1
    bytes[12 + 49] = 7
    if (version == 4) bytes[bytes.size - 5] = revision.toByte()
    resealRevisionSnapshot(bytes)
    return bytes
}

internal fun resealRevisionSnapshot(bytes: ByteArray) {
    val crc = CRC32().apply { update(bytes, 0, bytes.size - 4) }.value
    repeat(4) { byte -> bytes[bytes.size - 4 + byte] = (crc ushr (8 * byte)).toByte() }
}
