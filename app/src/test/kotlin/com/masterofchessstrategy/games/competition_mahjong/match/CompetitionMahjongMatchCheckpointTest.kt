package com.masterofchessstrategy.games.competition_mahjong.match

import org.junit.Assert.*
import org.junit.Test
import com.masterofchessstrategy.engine.Difficulty
import com.masterofchessstrategy.engine.CompetitionMahjongAction
import com.masterofchessstrategy.engine.CompetitionMahjongRoundActionKind
import com.masterofchessstrategy.games.competition_mahjong.match.hint.CompetitionMahjongHintReceipt
import com.masterofchessstrategy.games.competition_mahjong.match.policy.CompetitionMahjongAssistanceUsage
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32

class CompetitionMahjongMatchCheckpointTest {
    @Test fun allViewerAssignmentsRoundTripBeforeAndAfterEveryHand() {
        repeat(4) { viewer ->
            val config = matchConfig(viewer)
            repeat(16) { index ->
                for (completed in index..index + 1) {
                    val record = matchRecord(config.handConfig(index).viewerSeat, completed > index)
                    val original = record.copyOf()
                    val checkpoint = CompetitionMahjongMatchCheckpoint(config, matchLedger(config, completed), index, record)
                    record.fill(0)
                    checkpoint.engineRecord.fill(0)
                    val decoded = CompetitionMahjongMatchCheckpointCodec.decode(CompetitionMahjongMatchCheckpointCodec.encode(checkpoint), viewer)!!
                    assertEquals(config, decoded.config)
                    assertEquals(index, decoded.activeHandIndex)
                    assertEquals(checkpoint.ledger.results, decoded.ledger.results)
                    assertArrayEquals(original, decoded.engineRecord)
                    decoded.validateRestoredFrame(matchFrame(config, index, terminal = completed > index))
                    assertFalse(decoded.toString().contains(config.seed.toString()))
                    assertFalse(decoded.toString().contains("["))
                }
            }
        }
    }

    @Test fun everyDamagedByteTruncationTrailingDataAndDifferentPlayerAreRejected() {
        val bytes = encoded()
        for (index in bytes.indices) {
            val damaged = bytes.copyOf()
            damaged[index] = (damaged[index].toInt() xor 1).toByte()
            assertNull("byte $index", CompetitionMahjongMatchCheckpointCodec.decode(damaged, 0))
        }
        for (length in bytes.indices) assertNull(CompetitionMahjongMatchCheckpointCodec.decode(bytes.copyOf(length), 0))
        assertNull(CompetitionMahjongMatchCheckpointCodec.decode(bytes + 0.toByte(), 0))
        assertNull(CompetitionMahjongMatchCheckpointCodec.decode(bytes, 1))
        assertNull(CompetitionMahjongMatchCheckpointCodec.decode(ByteArray(1_100_001), 0))
    }

    @Test fun validCrcCannotHideUnknownVersionBadMetadataOrNoncanonicalHistory() {
        val variants = listOf(4 to 2, 5 to 1, 6 to 1, 7 to 1, 12 to 35, 13 to 'g'.code,
            57 to 4, 58 to 4, 59 to 16, 60 to 17, 61 to 1, 62 to 0, 63 to 4, 64 to 0, 89 to 1)
        variants.forEach { (offset, replacement) ->
            val bytes = encoded()
            bytes[offset] = replacement.toByte()
            seal(bytes)
            assertNull("offset $offset", CompetitionMahjongMatchCheckpointCodec.decode(bytes, 0))
        }
        val negativeSeed = encoded()
        ByteBuffer.wrap(negativeSeed).order(ByteOrder.BIG_ENDIAN).putLong(49, -1)
        seal(negativeSeed)
        assertNull(CompetitionMahjongMatchCheckpointCodec.decode(negativeSeed, 0))
        val nonzeroSum = encoded()
        ByteBuffer.wrap(nonzeroSum).order(ByteOrder.BIG_ENDIAN).putInt(65, 999)
        seal(nonzeroSum)
        assertNull(CompetitionMahjongMatchCheckpointCodec.decode(nonzeroSum, 0))
    }

    @Test fun oversizedInnerLengthAndWrongDerivedSingleHandConfigAreRejectedWithRecomputedCrc() {
        val bytes = encoded()
        val innerLengthOffset = 61 + 36
        val badLength = bytes.copyOf()
        ByteBuffer.wrap(badLength).order(ByteOrder.BIG_ENDIAN).putInt(innerLengthOffset, Int.MAX_VALUE)
        seal(badLength)
        assertNull(CompetitionMahjongMatchCheckpointCodec.decode(badLength, 0))
        val changedSeed = bytes.copyOf()
        val start = innerLengthOffset + 4
        ByteBuffer.wrap(changedSeed).order(ByteOrder.LITTLE_ENDIAN).putLong(start + 8, 123)
        val innerEnd = changedSeed.size - 4
        val innerCrc = CRC32().apply { update(changedSeed, start, innerEnd - start - 4) }.value.toInt()
        ByteBuffer.wrap(changedSeed).order(ByteOrder.LITTLE_ENDIAN).putInt(innerEnd - 4, innerCrc)
        seal(changedSeed)
        assertNull(CompetitionMahjongMatchCheckpointCodec.decode(changedSeed, 0))
    }

    @Test fun restoredNativeFrameMustMatchLedgerTerminalStatusAndRecordedScores() {
        val config = matchConfig()
        val terminal = CompetitionMahjongMatchCheckpoint(config, matchLedger(config, 1), 0, matchRecord(0, true))
        assertThrows(IllegalArgumentException::class.java) { terminal.validateRestoredFrame(matchFrame(config, 0, terminal = false)) }
        assertThrows(IllegalArgumentException::class.java) { terminal.validateRestoredFrame(matchFrame(config, 0, winnerPlayer = 1)) }
        val ongoing = CompetitionMahjongMatchCheckpoint(config, CompetitionMahjongMatchLedger.empty(), 0, matchRecord(0))
        assertThrows(IllegalArgumentException::class.java) { ongoing.validateRestoredFrame(matchFrame(config, 0)) }
    }

    @Test fun checkpointCannotJoinSkippedHandOrDifferentPhysicalViewerRecord() {
        val config = matchConfig()
        assertThrows(IllegalArgumentException::class.java) {
            CompetitionMahjongMatchCheckpoint(config, CompetitionMahjongMatchLedger.empty(), 2, matchRecord(0))
        }
        assertThrows(IllegalArgumentException::class.java) {
            CompetitionMahjongMatchCheckpoint(config, matchLedger(config, 4), 4, matchRecord(0))
        }
    }

    @Test fun versionTwoKeepsDifficultyAndSeriesUsageForEveryHandAndViewer() {
        repeat(4) { viewer ->
            val config = matchConfig(viewer)
            repeat(16) { hand ->
                val usage = CompetitionMahjongAssistanceUsage(Difficulty.MEDIUM, 2, 1)
                val checkpoint = CompetitionMahjongMatchCheckpoint(config, matchLedger(config, hand), hand,
                    matchRecord(config.handConfig(hand).viewerSeat), usage)
                val bytes = CompetitionMahjongMatchCheckpointCodec.encode(checkpoint)
                assertEquals(2, bytes[4].toInt())
                val restored = CompetitionMahjongMatchCheckpointCodec.decode(bytes, viewer)!!
                assertEquals(usage, restored.assistance)
                assertEquals(checkpoint.config, restored.config)
                assertEquals(hand, restored.activeHandIndex)
                assertEquals(checkpoint.ledger.results, restored.ledger.results)
                assertArrayEquals(checkpoint.engineRecord, restored.engineRecord)
            }
        }
    }

    @Test fun versionOneDecodesWithoutInventingAssistanceHistoryAndRetainsItsBytes() {
        val bytes = encoded()
        val decoded = CompetitionMahjongMatchCheckpointCodec.decode(bytes, 0)!!
        assertNull(decoded.assistance)
        assertEquals(1, bytes[4].toInt())
        assertArrayEquals(bytes, CompetitionMahjongMatchCheckpointCodec.encode(decoded))
    }

    @Test fun validVersionTwoCrcCannotHideUnknownPolicyOrInvalidCounters() {
        val config = matchConfig()
        val checkpoint = CompetitionMahjongMatchCheckpoint(config, matchLedger(config, 1), 0,
            matchRecord(0, true), CompetitionMahjongAssistanceUsage(Difficulty.MEDIUM, 2, 1))
        val original = CompetitionMahjongMatchCheckpointCodec.encode(checkpoint)
        val start = 61 + 36
        for ((offset, value) in listOf(start to 2, start + 1 to 9, start + 2 to 2, start + 3 to 1)) {
            val bytes = original.copyOf()
            bytes[offset] = value.toByte()
            seal(bytes)
            assertNull(CompetitionMahjongMatchCheckpointCodec.decode(bytes, 0))
        }
        for (offset in listOf(start + 4, start + 8)) for (value in listOf(-1, 4, Int.MAX_VALUE)) {
            val bytes = original.copyOf()
            ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN).putInt(offset, value)
            seal(bytes)
            assertNull(CompetitionMahjongMatchCheckpointCodec.decode(bytes, 0))
        }
        for (length in original.indices) assertNull(CompetitionMahjongMatchCheckpointCodec.decode(original.copyOf(length), 0))
    }

    @Test fun versionThreePreservesPlaybackAndAssistanceAcrossAllSeatsAndHands() {
        repeat(4) { viewer ->
            val config = matchConfig(viewer)
            repeat(16) { index ->
                for (speed in listOf(0.5f, 1f, 2f, 4f)) for (paused in listOf(false, true)) {
                    val usage = CompetitionMahjongAssistanceUsage(Difficulty.MEDIUM, 2, 1)
                    val playback = CompetitionMahjongMatchPlayback(paused, speed)
                    val checkpoint = CompetitionMahjongMatchCheckpoint(config, matchLedger(config, index), index,
                        matchRecord(config.handConfig(index).viewerSeat), usage, playback)
                    val bytes = CompetitionMahjongMatchCheckpointCodec.encode(checkpoint)
                    assertEquals(3, bytes[4].toInt())
                    val restored = CompetitionMahjongMatchCheckpointCodec.decode(bytes, viewer)!!
                    assertEquals(playback, restored.playback)
                    assertEquals(usage, restored.assistance)
                    assertArrayEquals(bytes, CompetitionMahjongMatchCheckpointCodec.encode(restored))
                    assertEquals(playback, restored.withAssistance(usage.copy(hintUsed = 2)).playback)
                }
            }
        }
    }

    @Test fun oldVersionsDoNotInventSavedPlaybackAndRemainByteExact() {
        val config = matchConfig()
        for (usage in listOf(null, CompetitionMahjongAssistanceUsage(Difficulty.MEDIUM))) {
            val bytes = CompetitionMahjongMatchCheckpointCodec.encode(CompetitionMahjongMatchCheckpoint(
                config, matchLedger(config, 1), 0, matchRecord(0, true), usage))
            val decoded = CompetitionMahjongMatchCheckpointCodec.decode(bytes, 0)!!
            assertEquals(CompetitionMahjongBotStrategy.LEGACY_BATCH, decoded.config.botStrategy)
            assertNull(decoded.playback)
            assertArrayEquals(bytes, CompetitionMahjongMatchCheckpointCodec.encode(decoded))
        }
    }

    @Test fun versionFivePersistsTheStrategyWithAllExistingMetadataShapes() {
        val config = matchConfig().copy(botStrategy = CompetitionMahjongBotStrategy.POSITION_SEEDED_V1)
        val plain = CompetitionMahjongMatchCheckpoint(config, CompetitionMahjongMatchLedger.empty(), 0, matchRecord(0))
        val assisted = plain.withAssistance(CompetitionMahjongAssistanceUsage(Difficulty.MEDIUM, hintUsed = 1))
        val playback = assisted.withPlayback(CompetitionMahjongMatchPlayback(true, 2f))
        val hint = playback.withHintReceipt(CompetitionMahjongHintReceipt(0, "a".repeat(64), "qa",
            CompetitionMahjongAction(CompetitionMahjongRoundActionKind.PASS)))
        listOf(plain, assisted, playback, hint).forEach { checkpoint ->
            val codec = CompetitionMahjongMatchCheckpointCodec
            val bytes = codec.encode(checkpoint)
            assertEquals(5, bytes[4].toInt())
            val restored = checkNotNull(codec.decode(bytes, 0))
            assertEquals(config, restored.config)
            assertEquals(checkpoint.assistance, restored.assistance)
            assertEquals(checkpoint.playback, restored.playback)
            assertEquals(checkpoint.hintReceipt, restored.hintReceipt)
            assertArrayEquals(bytes, codec.encode(restored))
            val invalidMetadata = bytes.copyOf().also { it[61] = 0 }
            seal(invalidMetadata)
            assertNull(codec.decode(invalidMetadata, 0))
        }
    }

    @Test fun validVersionThreeCrcCannotHideBadPlaybackRevisionFlagsReservedOrSpeed() {
        val config = matchConfig()
        val bytes = CompetitionMahjongMatchCheckpointCodec.encode(CompetitionMahjongMatchCheckpoint(
            config, matchLedger(config, 1), 0, matchRecord(0, true),
            CompetitionMahjongAssistanceUsage(Difficulty.MEDIUM), CompetitionMahjongMatchPlayback(true, 2f)))
        val start = 61 + 36 + 12
        for ((offset, value) in listOf(start to 2, start + 1 to 2, start + 2 to 1, start + 3 to 1)) {
            val changed = bytes.copyOf()
            changed[offset] = value.toByte()
            seal(changed)
            assertNull(CompetitionMahjongMatchCheckpointCodec.decode(changed, 0))
        }
        for (speed in listOf(-1, 0, 501, 3000, Int.MAX_VALUE)) {
            val changed = bytes.copyOf()
            ByteBuffer.wrap(changed).order(ByteOrder.BIG_ENDIAN).putInt(start + 4, speed)
            seal(changed)
            assertNull(CompetitionMahjongMatchCheckpointCodec.decode(changed, 0))
        }
        for (size in bytes.indices) assertNull(CompetitionMahjongMatchCheckpointCodec.decode(bytes.copyOf(size), 0))
    }

    @Test fun playbackRejectsInvalidSpeedAndMissingFormalAssistance() {
        for (speed in listOf(Float.NaN, Float.POSITIVE_INFINITY, 0f, 3f))
            assertThrows(IllegalArgumentException::class.java) { CompetitionMahjongMatchPlayback(speed = speed) }
        val config = matchConfig()
        assertThrows(IllegalArgumentException::class.java) {
            CompetitionMahjongMatchCheckpoint(config, matchLedger(config, 1), 0, matchRecord(0, true),
                playback = CompetitionMahjongMatchPlayback())
        }
    }

    private fun encoded(): ByteArray {
        val config = matchConfig()
        return CompetitionMahjongMatchCheckpointCodec.encode(CompetitionMahjongMatchCheckpoint(config, matchLedger(config, 1), 0, matchRecord(0, true)))
    }

    private fun seal(bytes: ByteArray) {
        val crc = CRC32().apply { update(bytes, 0, bytes.size - 4) }.value.toInt()
        ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN).putInt(bytes.size - 4, crc)
    }
}
