package com.masterofchessstrategy.games.go

import com.masterofchessstrategy.engine.GoPlayer
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32

class GoPerMoveClockTest {
    @Test fun changingTurnsRefillsOnlyTheNewActorAndSameActorKeepsItsDeadline() {
        val initial = GoTurnClock.start(30_000, GoPlayer.BLACK, 1_000, GoClockMode.PER_MOVE)
        val white = initial.advance(11_000, GoPlayer.WHITE)
        assertEquals(20_000L, white.blackMillis)
        assertEquals(30_000L, white.whiteMillis)
        val same = white.advance(21_000, GoPlayer.WHITE)
        assertEquals(20_000L, same.whiteMillis)
        assertEquals(GoPlayer.WHITE, same.expired(41_000))
        val black = same.advance(31_000, GoPlayer.BLACK)
        assertEquals(30_000L, black.blackMillis)
        assertEquals(10_000L, black.whiteMillis)
    }

    @Test fun scoringPausesAndResumeAllocatesANewTurn() {
        val review = GoTurnClock.start(30_000, GoPlayer.WHITE, 1_000, GoClockMode.PER_MOVE).advance(11_000, null)
        assertEquals(20_000L, review.remaining(GoPlayer.WHITE, 999_000))
        assertNull(review.expired(999_000))
        val resumed = review.advance(999_000, GoPlayer.WHITE)
        assertEquals(30_000L, resumed.whiteMillis)
        assertEquals(GoPlayer.WHITE, resumed.expired(1_029_000))
    }

    @Test fun versionNineRoundTripsBothBlindModesAndAllConfiguredBounds() {
        for (blind in listOf(false, true)) for (seconds in listOf(1, 30, 60, 120, 600)) {
            val config = GoAiMatchConfig(9, blind = blind, perMoveLimitSeconds = seconds)
            val clock = GoTurnClock.start(seconds * 1_000L, GoPlayer.BLACK, 10, GoClockMode.PER_MOVE)
            val saved = GoAiSavedMatch("per-move", config, byteArrayOf(9, 0), clock = clock)
            val bytes = GoAiMatchFileCodec.encode(saved)
            assertEquals(9, bytes[4].toInt())
            val restored = checkNotNull(GoAiMatchFileCodec.decode(bytes))
            assertEquals(config, restored.config)
            assertEquals(clock, restored.clock)
            assertArrayEquals(bytes, GoAiMatchFileCodec.encode(restored))
            assertEquals(9, GoAiMatchFileCodec.canonicalVersion(bytes))
        }
    }

    @Test fun mainTimeVersionsSevenAndEightRetainTheirBytesAndMeaning() {
        for (blind in listOf(false, true)) {
            val saved = GoAiSavedMatch("main", GoAiMatchConfig(9, blind = blind), byteArrayOf(9, 0),
                clock = GoTurnClock.start(30_000, GoPlayer.BLACK, 10))
            val bytes = GoAiMatchFileCodec.encode(saved)
            assertEquals(if (blind) 8 else 7, bytes[4].toInt())
            val restored = checkNotNull(GoAiMatchFileCodec.decode(bytes))
            assertNull(restored.config.perMoveLimitSeconds)
            assertEquals(GoClockMode.MAIN, restored.clock!!.mode)
            assertArrayEquals(bytes, GoAiMatchFileCodec.encode(restored))
        }
    }

    @Test fun malformedVersionNineIsRejectedEvenWithARecomputedChecksum() {
        val config = GoAiMatchConfig(9, perMoveLimitSeconds = 30)
        val valid = GoAiMatchFileCodec.encode(GoAiSavedMatch("clock", config, byteArrayOf(9, 0),
            clock = GoTurnClock.start(30_000, GoPlayer.BLACK, 10, GoClockMode.PER_MOVE)))
        for (offset in valid.indices) {
            val bad = valid.copyOf().also { it[offset] = (it[offset].toInt() xor 128).toByte() }
            assertNull("Byte corruption at $offset", GoAiMatchFileCodec.decode(bad))
        }
        for (length in valid.indices) assertNull(GoAiMatchFileCodec.decode(valid.copyOf(length)))
        assertNull(GoAiMatchFileCodec.decode(valid + 0))
        fun changed(edit: (ByteArray) -> Unit) = valid.copyOf().also {
            edit(it)
            val crc = CRC32().apply { update(it, 0, 73); update(it, 77, it.size - 77) }.value.toInt()
            ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putInt(73, crc)
        }
        assertNull(GoAiMatchFileCodec.decode(changed { it[9] = 0 }))
        assertNull(GoAiMatchFileCodec.decode(changed { it[4] = 7 }))
        assertNull(GoAiMatchFileCodec.decode(changed {
            ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putLong(40, 30_001)
        }))
    }

    @Test fun contradictoryConfigurationCannotBeSaved() {
        val per = GoAiMatchConfig(9, perMoveLimitSeconds = 30)
        val clock = GoTurnClock.start(30_000, GoPlayer.BLACK, 10, GoClockMode.PER_MOVE)
        assertThrows(IllegalArgumentException::class.java) { GoAiSavedMatch("missing", per, byteArrayOf(1)) }
        assertThrows(IllegalArgumentException::class.java) {
            GoAiSavedMatch("wrong", per, byteArrayOf(1), clock = clock.copy(mode = GoClockMode.MAIN))
        }
        assertThrows(IllegalArgumentException::class.java) {
            GoAiSavedMatch("wrong", per.copy(perMoveLimitSeconds = 60), byteArrayOf(1), clock = clock)
        }
        assertThrows(IllegalArgumentException::class.java) {
            GoAiSavedMatch("wrong", per.copy(perMoveLimitSeconds = null), byteArrayOf(1), clock = clock)
        }
        for (seconds in listOf(0, 601)) assertThrows(IllegalArgumentException::class.java) {
            per.copy(perMoveLimitSeconds = seconds)
        }
    }
}
