package com.masterofchessstrategy.games.go

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32

class GoSessionFileCodecTest {
    @Test fun sessionRoundTripPreservesEngineBytesAndDisplayMode() {
        val original = GoSavedSession(13, byteArrayOf(0, 1, 2, -1), GoScoreDisplayMode.POINTS, 42)
        val decoded = GoSessionFileCodec.decode(GoSessionFileCodec.encode(original))!!
        assertEquals(13, decoded.boardSize)
        assertEquals(GoScoreDisplayMode.POINTS, decoded.displayMode)
        assertArrayEquals(original.engineState, decoded.engineState)
        assertEquals(42, decoded.lastMoveIndex)
        assertEquals(original.identity, decoded.identity)
    }

    @Test fun truncatedOrModifiedSessionIsRejected() {
        val encoded = GoSessionFileCodec.encode(
            GoSavedSession(9, byteArrayOf(1, 2, 3), GoScoreDisplayMode.STONES),
        )
        assertNull(GoSessionFileCodec.decode(encoded.copyOfRange(0, encoded.size - 1)))
        encoded[encoded.lastIndex] = (encoded.last().toInt() xor 1).toByte()
        assertNull(GoSessionFileCodec.decode(encoded))
    }

    @Test fun invalidBoardAndDisplayModeAreRejected() {
        val encoded = GoSessionFileCodec.encode(
            GoSavedSession(19, byteArrayOf(1), GoScoreDisplayMode.STONES),
        )
        encoded[5] = 10
        assertNull(GoSessionFileCodec.decode(encoded))
        encoded[5] = 19
        encoded[6] = 2
        assertNull(GoSessionFileCodec.decode(encoded))
    }

    @Test fun legacyEnvelopeStillLoadsWithoutAMoveMarker() {
        // Independent historical layout, not a slice of the current encoder's output.
        val legacy = legacyEnvelope(1)
        val decoded = GoSessionFileCodec.decode(legacy)!!
        assertNull(decoded.lastMoveIndex)
        assertEquals(GoScoreDisplayMode.POINTS, decoded.displayMode)
        assertArrayEquals(byteArrayOf(1, 2, 3), decoded.engineState)
        assertEquals(GoSessionOrigin.LEGACY_UNKNOWN, decoded.identity.origin)
        assertEquals(decoded.identity, GoSessionFileCodec.decode(legacy.copyOf())!!.identity)
    }

    @Test fun outOfBoardMoveMarkerIsRejected() {
        val current = GoSessionFileCodec.encode(
            GoSavedSession(9, byteArrayOf(1), GoScoreDisplayMode.STONES, 40),
        )
        current[16] = 81
        assertNull(GoSessionFileCodec.decode(current))
    }

    @Test fun historicalV2KeepsMoveMarkerAndIdentityThroughUpgrade() {
        val legacy = legacyEnvelope(2)
        val originalBytes = legacy.copyOf()
        val decoded = GoSessionFileCodec.decode(legacy)!!
        assertEquals(40, decoded.lastMoveIndex)
        val upgraded = GoSessionFileCodec.encode(decoded)
        assertEquals(3, upgraded[4].toInt())
        assertEquals(decoded.identity, GoSessionFileCodec.decode(upgraded)!!.identity)
        assertArrayEquals(originalBytes, legacy)
    }

    @Test fun checksumCoversEveryMetadataAndPayloadByte() {
        val encoded = GoSessionFileCodec.encode(GoSavedSession(9, byteArrayOf(1, 2, 3),
            GoScoreDisplayMode.POINTS, 40,
            GoSessionIdentity("stable-go-session", GoSessionOrigin.SGF_IMPORT, 7)))
        for (index in encoded.indices) {
            val changed = encoded.copyOf()
            changed[index] = (changed[index].toInt() xor 1).toByte()
            assertNull("modified byte $index", GoSessionFileCodec.decode(changed))
        }
        for (length in 0 until encoded.size) {
            assertNull("truncated at $length", GoSessionFileCodec.decode(encoded.copyOf(length)))
        }
        assertNull(GoSessionFileCodec.decode(encoded + 0.toByte()))
    }

    @Test fun semanticMetadataIsValidatedEvenWithCorrectChecksum() {
        val encoded = GoSessionFileCodec.encode(GoSavedSession(9, byteArrayOf(1),
            GoScoreDisplayMode.STONES, identity = GoSessionIdentity("stable", GoSessionOrigin.LOCAL)))
        for ((index, value) in listOf(4 to 4, 7 to 1, 20 to 99, 21 to 1, 22 to 1,
            23 to 1, 27 to 128, 28 to 0, 29 to ':'.code)) {
            val changed = encoded.copyOf()
            changed[index] = value.toByte()
            checksumV3(changed)
            assertNull("invalid metadata at $index", GoSessionFileCodec.decode(changed))
        }
    }

    @Test fun everySourceAndLargestIdentityRevisionAndPayloadRoundTrip() {
        for (source in GoSessionOrigin.entries) {
            val session = GoSavedSession(19, ByteArray(1_048_576) { it.toByte() },
                GoScoreDisplayMode.STONES, 360,
                GoSessionIdentity("x".repeat(80), source, Int.MAX_VALUE))
            val decoded = GoSessionFileCodec.decode(GoSessionFileCodec.encode(session))!!
            assertEquals(session.identity, decoded.identity)
            assertArrayEquals(session.engineState, decoded.engineState)
            assertTrue(decoded.identity.recordId.length <= 64)
        }
    }

    private fun legacyEnvelope(version: Int): ByteArray {
        val payload = byteArrayOf(1, 2, 3)
        return ByteBuffer.allocate((if (version == 1) 16 else 20) + payload.size)
            .order(ByteOrder.LITTLE_ENDIAN).apply {
                putInt(0x474F5331); put(version.toByte()); put(9); put(1); put(0)
                putInt(payload.size); putInt(CRC32().apply { update(payload) }.value.toInt())
                if (version == 2) putInt(40)
                put(payload)
            }.array()
    }

    private fun checksumV3(bytes: ByteArray) {
        val crc = CRC32().apply { update(bytes, 0, 12); update(bytes, 16, bytes.size - 16) }
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(12, crc.value.toInt())
    }
}
