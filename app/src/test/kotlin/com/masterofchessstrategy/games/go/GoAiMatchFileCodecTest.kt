package com.masterofchessstrategy.games.go

import com.masterofchessstrategy.engine.GoPlayer
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32

class GoAiMatchFileCodecTest {
    @Test fun allPoliciesRoundTripIncludingPersistedUndoAllowance() {
        for (size in listOf(9, 13, 19)) for (handicap in listOf(0) + (2..9))
            for (difficulty in GoAiDifficulty.entries) for (human in listOf(null, GoPlayer.BLACK, GoPlayer.WHITE)) {
                val config = GoAiMatchConfig(size, handicap, human, difficulty)
                val used = minOf(1, difficulty.undoLimit)
                val original = GoAiSavedMatch("测试棋局-$size-$handicap", config, byteArrayOf(4, 5, 6), used, used)
                val restored = checkNotNull(GoAiMatchFileCodec.decode(GoAiMatchFileCodec.encode(original)))
                assertEquals(config, restored.config)
                assertEquals(original.matchId, restored.matchId)
                assertEquals(used, restored.undoUsed)
                assertEquals(used, restored.hintUsed)
                assertEquals(human ?: GoPlayer.BLACK, restored.rememberedHuman)
                assertEquals(human == null, restored.automaticUsed)
                assertArrayEquals(original.copyBytes(), restored.copyBytes())
            }
    }

    @Test fun supportsFullNativeEventCapacityAndCopiesInput() {
        val state = ByteArray(344_122) { (it % 251).toByte() }
        val original = GoAiSavedMatch("id", GoAiMatchConfig(19), state)
        state[0] = 1
        val encoded = GoAiMatchFileCodec.encode(original)
        val restored = checkNotNull(GoAiMatchFileCodec.decode(encoded))
        encoded[encoded.lastIndex] = 0
        assertEquals(344_122, restored.copyBytes().size)
        assertEquals(0, restored.copyBytes()[0].toInt())
        assertArrayEquals(original.copyBytes(), restored.copyBytes())
    }

    @Test fun rejectsEveryByteCorruptionTruncationAndTrailingData() {
        val valid = GoAiMatchFileCodec.encode(GoAiSavedMatch("id", GoAiMatchConfig(9), byteArrayOf(9, 0, 1)))
        for (index in valid.indices) {
            val bad = valid.copyOf(); bad[index] = (bad[index].toInt() xor 1).toByte()
            assertNull("Corruption at $index accepted", GoAiMatchFileCodec.decode(bad))
        }
        for (length in valid.indices) assertNull(GoAiMatchFileCodec.decode(valid.copyOf(length)))
        assertNull(GoAiMatchFileCodec.decode(valid + 0))
    }

    @Test fun checksumDoesNotOverridePolicyVersionOrUtf8Validation() {
        val valid = GoAiMatchFileCodec.encode(GoAiSavedMatch("id", GoAiMatchConfig(9), byteArrayOf(9, 0)))
        for ((index, code) in listOf(4 to 7, 5 to 10, 6 to 1, 7 to 2, 8 to 4, 9 to 8, 10 to 1, 10 to 2, 11 to 1, 44 to 0, 44 to 255)) {
            val bad = valid.copyOf(); bad[index] = code.toByte(); recheck(bad)
            assertNull("Invalid metadata at $index accepted", GoAiMatchFileCodec.decode(bad))
        }
        val invalidUndo = valid.copyOf()
        invalidUndo[8] = GoAiDifficulty.MASTER.ordinal.toByte()
        ByteBuffer.wrap(invalidUndo).order(ByteOrder.LITTLE_ENDIAN).putInt(12, 1)
        recheck(invalidUndo)
        assertNull(GoAiMatchFileCodec.decode(invalidUndo))
        val invalidHint = valid.copyOf()
        invalidHint[8] = GoAiDifficulty.HARD.ordinal.toByte()
        ByteBuffer.wrap(invalidHint).order(ByteOrder.LITTLE_ENDIAN).putInt(24, 2)
        recheck(invalidHint)
        assertNull(GoAiMatchFileCodec.decode(invalidHint))
    }

    @Test fun rejectsUnboundedStateIdentityAndInvalidUndoUse() {
        for (id in listOf("", " ", "a\u0000b", "围".repeat(43))) {
            try { GoAiSavedMatch(id, GoAiMatchConfig(), byteArrayOf(1)); fail("Invalid id accepted") }
            catch (_: IllegalArgumentException) { }
        }
        for (bytes in listOf(byteArrayOf(), ByteArray(1_000_001))) {
            try { GoAiSavedMatch("id", GoAiMatchConfig(), bytes); fail("Invalid size accepted") }
            catch (_: IllegalArgumentException) { }
        }
        try { GoAiSavedMatch("id", GoAiMatchConfig(difficulty = GoAiDifficulty.MASTER), byteArrayOf(1), 1); fail("Master undo use accepted") }
        catch (_: IllegalArgumentException) { }
        try { GoAiSavedMatch("id", GoAiMatchConfig(difficulty = GoAiDifficulty.MASTER), byteArrayOf(1), hintUsed = 1); fail("Master hint use accepted") }
        catch (_: IllegalArgumentException) { }
        try { GoAiMatchFileCodec.encode(GoAiSavedMatch("\uD800", GoAiMatchConfig(), byteArrayOf(1))); fail("Invalid UTF-16 accepted") }
        catch (_: java.nio.charset.CharacterCodingException) { }
    }

    @Test fun versionOneRestoresZeroHintsAndMigratesWithoutChangingIdentityOrBoard() {
        // Frozen v1 layout; migration must preserve its original identity and board.
        val id = "old-id".toByteArray()
        val state = byteArrayOf(9, 0, 4, 5)
        val buffer = ByteBuffer.allocate(28 + id.size + state.size).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(0x31494147).put(1).put(9).put(0).put(0).put(1).put(byteArrayOf(0, 0, 0))
            .putInt(2).putInt(id.size).putInt(state.size).putInt(0).put(id).put(state)
        val bytes = buffer.array()
        val crc = CRC32().apply { update(bytes, 0, 24); update(bytes, 28, bytes.size - 28) }.value.toInt()
        buffer.putInt(24, crc)
        val restored = checkNotNull(GoAiMatchFileCodec.decode(bytes))
        assertEquals("old-id", restored.matchId)
        assertEquals(2, restored.undoUsed)
        assertEquals(0, restored.hintUsed)
        assertArrayEquals(state, restored.copyBytes())
        val migrated = GoAiMatchFileCodec.encode(restored)
        assertEquals(6, migrated[4].toInt())
        assertEquals(0, checkNotNull(GoAiMatchFileCodec.decode(migrated)).hintUsed)
        assertArrayEquals(state, checkNotNull(GoAiMatchFileCodec.decode(migrated)).copyBytes())
    }

    @Test fun versionTwoPreservesHintAllowanceAndMigratesWithoutSealing() {
        val id = "v2-id".toByteArray()
        val state = byteArrayOf(9, 0, 0, 1)
        val buffer = ByteBuffer.allocate(32 + id.size + state.size).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(0x31494147).put(2).put(9).put(0).put(0).put(1).put(byteArrayOf(0, 0, 0))
            .putInt(2).putInt(id.size).putInt(state.size).putInt(3).putInt(0).put(id).put(state)
        val bytes = buffer.array(); recheck(bytes)
        val restored = checkNotNull(GoAiMatchFileCodec.decode(bytes))
        assertEquals(3, restored.hintUsed)
        assertEquals(2, restored.undoUsed)
        assertFalse(restored.completionReserved)
        assertArrayEquals(state, restored.copyBytes())
        assertEquals(6, GoAiMatchFileCodec.encode(restored)[4].toInt())
        bytes[9] = 1; recheck(bytes)
        assertNull(GoAiMatchFileCodec.decode(bytes)) // v2 cannot declare a v3 seal flag.
    }

    @Test fun completionReservationRoundTripsAndUnknownFlagsAreRejected() {
        val match = GoAiSavedMatch("complete", GoAiMatchConfig(9), byteArrayOf(9, 0), completionReserved = true)
        val bytes = GoAiMatchFileCodec.encode(match)
        assertTrue(checkNotNull(GoAiMatchFileCodec.decode(bytes)).completionReserved)
        for (flag in listOf(4, 5, 128, 255)) {
            val bad = bytes.copyOf(); bad[9] = flag.toByte(); recheck(bad)
            assertNull(GoAiMatchFileCodec.decode(bad))
        }
    }

    @Test fun versionThreeInfersOriginalSideAndParticipationWithoutRewritingHistory() {
        for (human in listOf(GoPlayer.WHITE, null)) {
            val id = "v3-id".toByteArray(); val state = byteArrayOf(13, 5, 0, 1)
            val buffer = ByteBuffer.allocate(32 + id.size + state.size).order(ByteOrder.LITTLE_ENDIAN)
            buffer.putInt(0x31494147).put(3).put(13).put(5).put((human?.code ?: 255).toByte()).put(1)
                .put(byteArrayOf(1, 0, 0)).putInt(2).putInt(id.size).putInt(state.size).putInt(3).putInt(0).put(id).put(state)
            val bytes = buffer.array(); recheck(bytes)
            val old = checkNotNull(GoAiMatchFileCodec.decode(bytes))
            assertEquals(human ?: GoPlayer.BLACK, old.rememberedHuman)
            assertEquals(human == null, old.automaticUsed)
            assertTrue(old.completionReserved)
            val migrated = checkNotNull(GoAiMatchFileCodec.decode(GoAiMatchFileCodec.encode(old)))
            assertEquals(old.matchId, migrated.matchId); assertEquals(2, migrated.undoUsed); assertEquals(3, migrated.hintUsed)
            assertArrayEquals(state, migrated.copyBytes())
            bytes[9] = 2; recheck(bytes); assertNull(GoAiMatchFileCodec.decode(bytes))
            bytes[9] = 1; bytes[10] = 1; recheck(bytes); assertNull(GoAiMatchFileCodec.decode(bytes))
        }
    }

    @Test fun currentFormatCarriesWhitePreferenceAndMixedParticipationAndRejectsContradictions() {
        for (human in listOf(null, GoPlayer.WHITE)) for (sealed in listOf(false, true)) {
            val match = GoAiSavedMatch("mixed", GoAiMatchConfig(13, 5, human), byteArrayOf(13, 5),
                completionReserved = sealed, rememberedHuman = GoPlayer.WHITE, automaticUsed = true)
            val bytes = GoAiMatchFileCodec.encode(match)
            val restored = checkNotNull(GoAiMatchFileCodec.decode(bytes))
            assertEquals(GoPlayer.WHITE, restored.rememberedHuman); assertTrue(restored.automaticUsed)
            assertEquals(sealed, restored.completionReserved); assertEquals(human, restored.config.humanPlayer)
            if (human == null) {
                bytes[9] = (if (sealed) 1 else 0).toByte(); recheck(bytes)
                assertNull(GoAiMatchFileCodec.decode(bytes))
            } else {
                bytes[10] = 0; recheck(bytes); assertNull(GoAiMatchFileCodec.decode(bytes))
            }
        }
        try {
            GoAiSavedMatch("bad", GoAiMatchConfig(humanPlayer = null), byteArrayOf(1), automaticUsed = false)
            fail("Automatic mode without participation accepted")
        } catch (_: IllegalArgumentException) { }
        try {
            GoAiSavedMatch("bad", GoAiMatchConfig(), byteArrayOf(1), rememberedHuman = GoPlayer.WHITE)
            fail("Contradictory manual side accepted")
        } catch (_: IllegalArgumentException) { }
    }

    @Test fun versionFourMigrationKeepsSourceMetadataAndAddsNoOpening() {
        val id = "v4-id".toByteArray(); val state = byteArrayOf(13, 5, 0, 1)
        val buffer = ByteBuffer.allocate(32 + id.size + state.size).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(0x31494147).put(4).put(13).put(5).put(1).put(2)
            .put(byteArrayOf(3, 1, 0)).putInt(1).putInt(id.size).putInt(state.size).putInt(1).putInt(0).put(id).put(state)
        val bytes = buffer.array(); recheck(bytes)
        val old = checkNotNull(GoAiMatchFileCodec.decode(bytes))
        assertEquals(GoPlayer.WHITE, old.rememberedHuman); assertTrue(old.automaticUsed)
        assertTrue(old.completionReserved); assertFalse(old.hasSpecifiedOpening); assertNull(old.copyOpeningBytes())
        val migratedBytes = GoAiMatchFileCodec.encode(old)
        assertEquals(6, migratedBytes[4].toInt())
        val migrated = checkNotNull(GoAiMatchFileCodec.decode(migratedBytes))
        assertArrayEquals(state, migrated.copyBytes()); assertNull(migrated.copyOpeningBytes())
    }

    @Test fun v6RoundTripsIndependentCurrentAndOpeningRecordsAtFullCapacity() {
        val current = ByteArray(1_000_000) { (it % 251).toByte() }
        val opening = ByteArray(1_000_000) { ((it + 7) % 251).toByte() }
        val match = GoAiSavedMatch("opening", GoAiMatchConfig(19, humanPlayer = null), current,
            rememberedHuman = GoPlayer.WHITE, openingBytes = opening)
        current[0] = 99; opening[0] = 98
        val encoded = GoAiMatchFileCodec.encode(match)
        assertEquals(2_000_051, encoded.size)
        val restored = checkNotNull(GoAiMatchFileCodec.decode(encoded))
        assertTrue(restored.hasSpecifiedOpening); assertEquals(0, restored.copyBytes()[0].toInt())
        assertEquals(7, checkNotNull(restored.copyOpeningBytes())[0].toInt())
        val detached = checkNotNull(restored.copyOpeningBytes()); detached[0] = 1
        assertEquals(7, checkNotNull(restored.copyOpeningBytes())[0].toInt())
    }

    @Test fun v6RejectsOpeningFlagLengthAndCapacityContradictions() {
        val valid = GoAiMatchFileCodec.encode(GoAiSavedMatch("id", GoAiMatchConfig(9), byteArrayOf(9, 0),
            openingBytes = byteArrayOf(9, 0, 0)))
        for (change in listOf<Pair<Int, Int>>(9 to 0, 9 to 1, 28 to 0, 28 to -1, 28 to 1_000_001)) {
            val bad = valid.copyOf()
            if (change.first == 28) ByteBuffer.wrap(bad).order(ByteOrder.LITTLE_ENDIAN).putInt(28, change.second)
            else bad[change.first] = change.second.toByte()
            recheck(bad); assertNull(GoAiMatchFileCodec.decode(bad))
        }
        for (opening in listOf(byteArrayOf(), ByteArray(1_000_001))) {
            try { GoAiSavedMatch("id", GoAiMatchConfig(), byteArrayOf(1), openingBytes = opening); fail("Invalid opening accepted") }
            catch (_: IllegalArgumentException) { }
        }
    }

    @Test fun versionFiveMigrationKeepsOpeningAndAddsNoAutomaticContinuation() {
        val id = "v5-id".toByteArray(); val state = byteArrayOf(9, 0, 0, 1); val opening = byteArrayOf(9, 0, 0)
        val buffer = ByteBuffer.allocate(36 + id.size + state.size + opening.size).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(0x31494147).put(5).put(9).put(0).put(255.toByte()).put(1)
            .put(byteArrayOf(6, 0, 0)).putInt(0).putInt(id.size).putInt(state.size).putInt(0)
            .putInt(opening.size).putInt(0).put(id).put(state).put(opening)
        val bytes = buffer.array(); recheck(bytes)
        val old = checkNotNull(GoAiMatchFileCodec.decode(bytes))
        assertTrue(old.hasSpecifiedOpening); assertFalse(old.autoContinueEnabled)
        assertEquals(1, old.autoContinueMaxGames); assertEquals(1, old.autoContinueGameNumber)
        assertArrayEquals(opening, old.copyOpeningBytes())
        val migrated = GoAiMatchFileCodec.encode(old)
        assertEquals(6, migrated[4].toInt())
        assertFalse(checkNotNull(GoAiMatchFileCodec.decode(migrated)).autoContinueEnabled)
    }

    @Test fun v6RoundTripsBoundedAutomaticContinuationAndRejectsContradictions() {
        val match = GoAiSavedMatch("series", GoAiMatchConfig(9, humanPlayer = null), byteArrayOf(9, 0),
            autoContinueEnabled = true, autoContinueMaxGames = 10, autoContinueGameNumber = 4)
        val encoded = GoAiMatchFileCodec.encode(match)
        val restored = checkNotNull(GoAiMatchFileCodec.decode(encoded))
        assertTrue(restored.autoContinueEnabled); assertEquals(10, restored.autoContinueMaxGames)
        assertEquals(4, restored.autoContinueGameNumber)
        for ((offset, value) in listOf(9 to 7, 32 to 1, 32 to 101, 36 to 0, 36 to 11)) {
            val bad = encoded.copyOf()
            if (offset == 9) bad[offset] = value.toByte()
            else ByteBuffer.wrap(bad).order(ByteOrder.LITTLE_ENDIAN).putInt(offset, value)
            recheck(bad); assertNull("Invalid continuation metadata accepted", GoAiMatchFileCodec.decode(bad))
        }
        for (invalid in listOf(
            { GoAiSavedMatch("bad", GoAiMatchConfig(), byteArrayOf(1), autoContinueEnabled = true,
                autoContinueMaxGames = 3) },
            { GoAiSavedMatch("bad", GoAiMatchConfig(humanPlayer = null), byteArrayOf(1),
                autoContinueEnabled = true, autoContinueMaxGames = 1) },
            { GoAiSavedMatch("bad", GoAiMatchConfig(humanPlayer = null), byteArrayOf(1),
                autoContinueEnabled = true, autoContinueMaxGames = 3, autoContinueGameNumber = 4) }
        )) try { invalid(); fail("Invalid continuation accepted") } catch (_: IllegalArgumentException) { }
    }

    private fun recheck(bytes: ByteArray) {
        val version = bytes[4].toInt()
        val header = when (version) { 1 -> 28; in 2..4 -> 32; 5 -> 36; else -> 44 }
        val crcOffset = header - 4
        val crc = CRC32().apply { update(bytes, 0, crcOffset); update(bytes, header, bytes.size - header) }.value.toInt()
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(crcOffset, crc)
    }
}
