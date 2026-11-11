package com.masterofchessstrategy.games.competition_mahjong.auto

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class CompetitionMahjongAutoCheckpointTest {
    @Test fun strategiesStayWithOriginalPlayersThroughAllSixteenHands() {
        val input = mutableListOf(0, 1, 2, 3)
        val config = autoConfig(2, input)
        input[2] = 0
        val rotations = listOf(listOf(0, 1, 2, 3), listOf(1, 0, 3, 2), listOf(2, 3, 1, 0), listOf(3, 2, 0, 1))
        repeat(16) { hand ->
            val native = config.handConfig(0, hand, false)
            assertEquals(rotations[hand / 4], native.botLevels)
            assertEquals(rotations[hand / 4].indexOf(2), native.viewerSeat)
            assertEquals(hand % 4, native.dealerSeat)
            assertEquals(hand / 4, native.roundWind)
        }
        assertEquals(listOf(0, 1, 2, 3), config.playerBotLevels)
        assertNotEquals(config.handConfig(0, 0, false).seed, config.handConfig(1, 0, false).seed)
        assertEquals(config.handConfig(0, 0, true).decisionSeed, config.handConfig(1, 0, true).decisionSeed)
    }

    @Test fun continuousSpeedAndBackgroundFloorHaveStrictLimits() {
        assertEquals(200L, CompetitionMahjongAutoSettings(speed = 2.125f, stepDelayMillis = 425).delayMillis(false, true))
        assertEquals(800L, CompetitionMahjongAutoSettings(speed = .5f).delayMillis(false, true))
        assertEquals(16L, CompetitionMahjongAutoSettings(speed = 4f, stepDelayMillis = 0).delayMillis(false, true))
        assertEquals(3000L, CompetitionMahjongAutoSettings(speed = 4f).delayMillis(false, false))
        assertEquals(6000L, CompetitionMahjongAutoSettings(speed = .5f, handBreakMillis = 3000).delayMillis(true, false))
        listOf(Float.NaN, Float.POSITIVE_INFINITY, .49f, 4.01f).forEach { value ->
            assertThrows(IllegalArgumentException::class.java) { CompetitionMahjongAutoSettings(speed = value) }
        }
        assertThrows(IllegalArgumentException::class.java) { CompetitionMahjongAutoSettings(maxSeries = 0) }
        assertThrows(IllegalArgumentException::class.java) { CompetitionMahjongAutoSettings(backgroundDelayMillis = 1999) }
    }

    @Test fun codecKeepsPrivateHistorySettingsAndCopiesRecords() {
        val config = autoConfig(3, listOf(0, 1, 2, 3))
        val opening = autoRecord(config.handConfig(0, 0, true), 1)
        val settings = CompetitionMahjongAutoSettings(false, 1.375f, 250, 1250, true, 3, true, 4000, false, 8, 3, true, true)
        val checkpoint = autoCheckpoint(config, settings, hand = 15, completed = true, opening = opening, elapsed = 9876)
        opening.fill(0)
        checkpoint.engineRecord.fill(0)
        checkpoint.completedHands[0].baseline.fill(0)
        val encoded = CompetitionMahjongAutoCheckpointCodec.encode(checkpoint)
        val restored = checkNotNull(CompetitionMahjongAutoCheckpointCodec.decode(encoded, 3))
        assertEquals(config, restored.config)
        assertEquals(settings, restored.settings)
        assertEquals(9876L, restored.elapsedMillis)
        assertEquals(16, restored.ledger.completedHandCount)
        assertArrayEquals(checkpoint.openingRecord, restored.openingRecord)
        assertArrayEquals(checkpoint.engineRecord, restored.engineRecord)
        assertTrue(CompetitionMahjongAutoArchive(checkpoint).sameContent(CompetitionMahjongAutoArchive(restored)))
        assertFalse(checkpoint.toString().contains("109"))
    }

    @Test fun corruptTruncatedOversizeWrongOwnerAndUnknownVersionsAreRejected() {
        val bytes = CompetitionMahjongAutoCheckpointCodec.encode(autoCheckpoint())
        assertNull(CompetitionMahjongAutoCheckpointCodec.decode(bytes, 1))
        for (size in listOf(0, 15, 60, bytes.size - 1)) assertNull(CompetitionMahjongAutoCheckpointCodec.decode(bytes.copyOf(size), 0))
        assertNull(CompetitionMahjongAutoCheckpointCodec.decode(ByteArray(CompetitionMahjongAutoCheckpointCodec.MAX_BYTES + 1), 0))
        for (offset in listOf(0, 4, 5, 6, 12, 80, bytes.lastIndex)) {
            val modified = bytes.copyOf().also { it[offset] = (it[offset].toInt() xor 64).toByte() }
            if (offset < 8) fixAutoCheckpointCrc(modified)
            assertNull("offset=$offset", CompetitionMahjongAutoCheckpointCodec.decode(modified, 0))
        }
    }

    @Test fun recomputedCrcCannotAuthorizeInvalidSettingsOrNonBinaryFlags() {
        val bytes = CompetitionMahjongAutoCheckpointCodec.encode(autoCheckpoint())
        // Fixed MCAU prefix 12 + UUID36 + seeds16 + viewer1 + levels4 + series2 + hand1 + elapsed8 = 80.
        for (offset in listOf(80, 101, 104, 113, 122, 123)) {
            val malformed = bytes.copyOf().also { it[offset] = 2 }
            fixAutoCheckpointCrc(malformed)
            assertNull("boolean offset=$offset", CompetitionMahjongAutoCheckpointCodec.decode(malformed, 0))
        }
        val badSpeed = bytes.copyOf().also { ByteBuffer.wrap(it).order(ByteOrder.BIG_ENDIAN).putFloat(81, Float.NaN) }
        fixAutoCheckpointCrc(badSpeed)
        assertNull(CompetitionMahjongAutoCheckpointCodec.decode(badSpeed, 0))
        val negativeElapsed = bytes.copyOf().also { ByteBuffer.wrap(it).order(ByteOrder.BIG_ENDIAN).putLong(72, -1) }
        fixAutoCheckpointCrc(negativeElapsed)
        assertNull(CompetitionMahjongAutoCheckpointCodec.decode(negativeElapsed, 0))
    }

    @Test fun immutableOpeningRejectsDifferentOriginPrefixAndEarlierHistory() {
        val native = autoConfig().handConfig(0, 0, true)
        val initial = autoRecord(native, 1)
        assertEquals(2, CompetitionMahjongAutoRecord.eventsAfter(initial, autoRecord(native, 3), native))
        listOf(autoRecord(native, 3, origin = 999), autoRecord(native, 3, eventByte = 9), autoRecord(native, 0)).forEach {
            assertThrows(IllegalArgumentException::class.java) { CompetitionMahjongAutoRecord.eventsAfter(initial, it, native) }
        }
        val differentOpening = autoRecord(native, 1, origin = 999)
        assertThrows(IllegalArgumentException::class.java) {
            CompetitionMahjongAutoCheckpoint(autoConfig(), CompetitionMahjongAutoSettings(), 0, 0, emptyList(), initial, initial, differentOpening)
        }
    }

    @Test fun innerAndOuterChecksumsOwnershipAndBoundedCountAreIndependent() {
        val native = autoConfig().handConfig(0, 0, false)
        val record = autoRecord(native, 3)
        for (offset in listOf(4, 5, 6, 10, 12, 20, 28, 29, 30, 175)) {
            val modified = record.copyOf().also { it[offset] = (it[offset].toInt() xor 1).toByte() }
            fixAutoRecordChecksums(modified)
            assertThrows("offset=$offset", IllegalArgumentException::class.java) { CompetitionMahjongAutoRecord.eventCount(modified, native) }
        }
        val corruptInner = record.copyOf().also { it[40] = (it[40].toInt() xor 1).toByte() }
        ByteBuffer.wrap(corruptInner).order(ByteOrder.LITTLE_ENDIAN).putInt(corruptInner.size - 4,
            java.util.zip.CRC32().apply { update(corruptInner, 0, corruptInner.size - 4) }.value.toInt())
        assertThrows(IllegalArgumentException::class.java) { CompetitionMahjongAutoRecord.eventCount(corruptInner, native) }
        assertEquals(4096, CompetitionMahjongAutoRecord.eventCount(autoRecord(native, 4096), native))
        assertThrows(IllegalArgumentException::class.java) { CompetitionMahjongAutoRecord.eventCount(autoRecord(native, 4097), native) }
    }

    @Test fun terminalFrameAndLedgerMustAgreeAndCannotSkipHand() {
        val checkpoint = autoCheckpoint(hand = 4, completed = true)
        checkpoint.validateFrame(autoFrame(checkpoint.config.handConfig(0, 4, false), true))
        assertThrows(IllegalArgumentException::class.java) {
            checkpoint.validateFrame(autoFrame(checkpoint.config.handConfig(0, 4, false)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            CompetitionMahjongAutoCheckpoint(checkpoint.config, checkpoint.settings, 0, 6, checkpoint.completedHands,
                autoRecord(checkpoint.config.handConfig(0, 6, false)), autoRecord(checkpoint.config.handConfig(0, 6, false)))
        }
    }

    @Test fun archiveSelectionUsesCompleteSeriesAndConfiguredConditionsOnly() {
        val noRules = CompetitionMahjongAutoSettings(archiveAll = false)
        fun archive(settings: CompetitionMahjongAutoSettings, winner: (Int) -> Int? = { null }) =
            CompetitionMahjongAutoArchive(autoCheckpoint(settings = settings, hand = 15, completed = true, winnerPlayer = winner))
        assertFalse(archive(noRules).selected(autoPolicy()))
        assertTrue(archive(noRules.copy(archiveLongHandEvents = 3)).selected(autoPolicy()))
        assertFalse(archive(noRules.copy(archiveLongHandEvents = 4)).selected(autoPolicy()))
        assertTrue(archive(noRules.copy(archiveFanThreshold = 8)) { 0 }.selected(autoPolicy()))
        assertFalse(archive(noRules.copy(archiveFanThreshold = 9)) { 0 }.selected(autoPolicy()))
        assertTrue(archive(noRules.copy(archiveComeback = true)) { if (it < 3) 1 else 0 }.selected(autoPolicy()))
        assertFalse(archive(noRules.copy(archiveComeback = true)) { 0 }.selected(autoPolicy()))
        assertFalse(archive(noRules.copy(archiveMaster = true)).selected(autoPolicy()))
        val master = autoCheckpoint(autoConfig(levels = listOf(0, 3, 0, 0)), noRules.copy(archiveMaster = true), hand = 15, completed = true)
        assertTrue(CompetitionMahjongAutoArchive(master).selected(autoPolicy()))
        assertThrows(IllegalArgumentException::class.java) { CompetitionMahjongAutoArchive(autoCheckpoint()) }
    }
}
