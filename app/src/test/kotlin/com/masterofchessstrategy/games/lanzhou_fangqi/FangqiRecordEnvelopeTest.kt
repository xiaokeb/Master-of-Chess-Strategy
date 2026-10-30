package com.masterofchessstrategy.games.lanzhou_fangqi

import com.masterofchessstrategy.data.GameRecord
import com.masterofchessstrategy.data.GameRecordCategory
import com.masterofchessstrategy.data.GameRecordDao
import com.masterofchessstrategy.data.GameRecordEntity
import com.masterofchessstrategy.data.GameRecordPayloadPolicy
import com.masterofchessstrategy.data.GameRecordPayloadPolicyTest
import com.masterofchessstrategy.data.LoadGameRecordsResult
import com.masterofchessstrategy.data.RoomGameRecordRepository
import com.masterofchessstrategy.data.StoredGameMode
import com.masterofchessstrategy.data.toGameRecord
import com.masterofchessstrategy.data.toGameRecordEntity
import com.masterofchessstrategy.engine.Difficulty
import com.masterofchessstrategy.engine.GameResult
import com.masterofchessstrategy.engine.GameType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/**
 * Synthetic envelope/transport fixtures, including deliberately repeated illegal moves.
 * Neither these bytes nor the supplied terminal result certify Native legality or ranked provenance.
 */
class FangqiRecordEnvelopeTest {
    @Test fun newFangqiDefaultsToItsVersionOneEnvelopeWhileLegacyLimitsStayExplicit() {
        val record = GameRecord("default-fq", GameType.LANZHOU_SQUARE_CHESS, StoredGameMode.HUMAN_VS_AI,
            Difficulty.EASY, GameResult.FIRST_PLAYER_WIN, history(1), 1, completedAtEpochMillis = 30)
        assertEquals(1, record.engineFormatVersion)
        assertEquals(1, GameRecordPayloadPolicy.currentVersion(GameType.LANZHOU_SQUARE_CHESS))
        assertEquals(1_048_576, GameRecordPayloadPolicy.maximumBytes(GameType.LANZHOU_SQUARE_CHESS))
        assertEquals(10_000, GameRecord.MAX_MOVE_COUNT)
        assertEquals(65_536, GameRecord.MAX_ENGINE_STATE_BYTES)
    }

    @Test fun all10001And16384ActionsRoundTripThroughActualModelAndEntityDecoderWithoutTruncation() {
        for (count in listOf(10_001, 16_384)) {
            val bytes = history(count)
            assertTrue(bytes.size > 65_536)
            val record = record(bytes, count)
            val row = record.toGameRecordEntity()
            val decoded = requireNotNull(row.toGameRecord())
            assertEquals(1, row.engineFormatVersion)
            assertEquals(1, decoded.engineFormatVersion)
            assertEquals(count, row.moveCount)
            assertEquals(count, decoded.moveCount)
            assertArrayEquals(bytes, row.engineState)
            assertArrayEquals(bytes, decoded.engineState)
            assertEquals(count, decoded.engineState.toString(Charsets.US_ASCII).substringAfterLast('|').split(';').size)
        }
    }

    @Test fun longestEnvelopeUsesActualRepositoryAndRetainsOwnedStoredAndReturnedCopies() = runBlocking {
        val rows = Rows()
        val repository = RoomGameRecordRepository(rows) { error("Fangqi must not use Go admission") }
        val bytes = history(16_384)
        val expected = bytes.copyOf()
        assertTrue(repository.saveCompleted(record(bytes, 16_384)))
        bytes.fill(0)
        val first = requireNotNull(repository.load("synthetic-fq"))
        assertArrayEquals(expected, first.engineState)
        first.engineState.fill(99)
        val second = requireNotNull(repository.load("synthetic-fq"))
        assertArrayEquals(expected, second.engineState)
        assertEquals(16_384, second.moveCount)
        assertFalse(repository.saveCompleted(record(expected, 16_384)))
        assertEquals(1, rows.rows.size)
    }

    @Test fun action16385IsRejectedByActualConstructionAndEntityDecoding() {
        rejectedEverywhere(history(16_385), 16_385)
    }

    @Test fun metadataAndDeclaredHistoryCountsMustBothMatchTheWholePayload() {
        val bytes = history(10_001)
        for (count in listOf(-1, 0, 10_000, 10_002, 16_384)) rejectedEverywhere(bytes, count)
        val one = history(1).toString(Charsets.US_ASCII)
        for (bad in listOf(one.replace("|1|m", "|2|m"), one.replace("|1|m", "|01|m"),
            "$one;", one.replace("|1|m", "|1|;m"))) {
            rejectedEverywhere(bad.toByteArray(Charsets.US_ASCII), 1)
        }
    }

    @Test fun payloadAboveOneMiBIsRejectedEvenWithAnAsciiHeaderAndMatchingSingleActionSegment() {
        val prefix = history(0).toString(Charsets.US_ASCII).removeSuffix("|0|") + "|1|"
        val bytes = (prefix + "m".repeat(1_048_577 - prefix.length)).toByteArray(Charsets.US_ASCII)
        assertEquals(1_048_577, bytes.size)
        assertTrue(bytes.all { it.toInt() in 32..126 })
        rejectedEverywhere(bytes, 1)
    }

    @Test fun malformedHeadersOtherFormatsAndNonAsciiAreRejectedBeforeAnyNativeClaim() {
        val one = history(1).toString(Charsets.US_ASCII)
        for (bad in listOf("broken", one.replace("MOCS-LFQ", "MOCS-GO"),
            one.replace("MOCS-LFQ|1|", "MOCS-LFQ|2|"), one.replace("|1|1|", "|1|0|"),
            one.replaceFirst("222", "322"))) {
            rejectedEverywhere(bad.toByteArray(Charsets.US_ASCII), 1)
        }
        for (badByte in listOf(0, 10, 127, 128, 255)) {
            val bytes = history(1).also { it[it.lastIndex] = badByte.toByte() }
            rejectedEverywhere(bytes, 1)
        }
    }

    @Test fun unknownOuterVersionsAreRejectedRatherThanRewrittenAsCurrent() {
        for (version in listOf(-1, 0, 3, 4, 99)) rejectedEverywhere(history(1), 1, version)
    }

    @Test fun legacyVersionTwoKeepsItsOriginalArbitraryBytesAnd10000MoveBoundary() {
        for (size in listOf(1, 65_536)) {
            // Old generic v2 never claimed the MOCS-LFQ history contract or ranked creation source.
            val bytes = ByteArray(size) { 0xff.toByte() }
            val old = record(bytes, 10_000, 2)
            val restored = requireNotNull(old.toGameRecordEntity().toGameRecord())
            assertEquals(2, restored.engineFormatVersion)
            assertEquals(10_000, restored.moveCount)
            assertArrayEquals(bytes, restored.engineState)
        }
    }

    @Test fun legacyVersionTwoCannotAcquireVersionOneByteOrMoveCapacity() {
        rejectedEverywhere(ByteArray(65_537), 10_000, 2)
        rejectedEverywhere(byteArrayOf(1), 10_001, 2)
        rejectedEverywhere(byteArrayOf(1), -1, 2)
        rejectedEverywhere(byteArrayOf(), 0, 2)
    }

    @Test fun xiangqiActualModelAndDecoderKeepVersionTwo64KiBAnd10000Moves() {
        val accepted = record(ByteArray(65_536), 10_000, 2, GameType.CHINESE_CHESS)
        assertEquals(2, requireNotNull(accepted.toGameRecordEntity().toGameRecord()).engineFormatVersion)
        rejectedEverywhere(ByteArray(65_537), 10_000, 2, GameType.CHINESE_CHESS)
        rejectedEverywhere(byteArrayOf(1), 10_001, 2, GameType.CHINESE_CHESS)
        rejectedEverywhere(history(1), 1, 1, GameType.CHINESE_CHESS)
        rejectedEverywhere(history(16_384), 16_384, 2, GameType.CHINESE_CHESS)
    }

    @Test fun goExistingThreeNativeVersionsAndMaximumEnvelopeRemainDistinctFromFangqi() {
        for (version in 1..3) {
            val bytes = GameRecordPayloadPolicyTest.frame(version, 2)
            val go = record(bytes, 2, version, GameType.GO)
            val decoded = requireNotNull(go.toGameRecordEntity().toGameRecord())
            assertEquals(version, decoded.engineFormatVersion)
            assertArrayEquals(bytes, decoded.engineState)
        }
        val maximum = GameRecordPayloadPolicyTest.frame(3, 16_386)
        assertEquals(344_122, maximum.size)
        assertEquals(344_122, requireNotNull(record(maximum, 10_000, 3, GameType.GO).toGameRecordEntity().toGameRecord()).engineState.size)
        rejectedEverywhere(GameRecordPayloadPolicyTest.frame(3, 16_387), 10_000, 3, GameType.GO)
        rejectedEverywhere(GameRecordPayloadPolicyTest.frame(3, 2), 10_001, 3, GameType.GO)
        rejectedEverywhere(maximum, 2, 1)
    }

    @Test fun aCorruptFangqiRowMakesRepositoryReadIncompatibleWithoutDiscardingEitherRow() = runBlocking {
        val rows = Rows()
        rows.rows["good"] = raw(history(10_001), 10_001).copy(recordId = "good")
        rows.rows["bad"] = raw(history(16_384), 16_383).copy(recordId = "bad")
        val repository = RoomGameRecordRepository(rows) { error("Fangqi invoked Go admission") }
        assertNotNull(repository.load("good"))
        assertNull(repository.load("bad"))
        assertSame(LoadGameRecordsResult.Incompatible, repository.list(GameRecordCategory.ALL))
        assertEquals(setOf("good", "bad"), rows.rows.keys)
    }

    @Test fun entityEncodingAndDecodingEachOwnTheEntireLongHistory() {
        val bytes = history(10_001)
        val original = bytes.copyOf()
        val encoded = record(bytes, 10_001).toGameRecordEntity()
        bytes.fill(0)
        assertArrayEquals(original, encoded.engineState)
        val decoded = requireNotNull(encoded.toGameRecord())
        encoded.engineState.fill(0)
        assertArrayEquals(original, decoded.engineState)
    }

    private fun record(bytes: ByteArray, count: Int, version: Int = 1,
        game: GameType = GameType.LANZHOU_SQUARE_CHESS,
    ) = GameRecord("synthetic-fq", game, StoredGameMode.HUMAN_VS_AI, Difficulty.EASY,
        GameResult.FIRST_PLAYER_WIN, bytes, count, completedAtEpochMillis = 30, engineFormatVersion = version)

    private fun raw(bytes: ByteArray, count: Int, version: Int = 1,
        game: GameType = GameType.LANZHOU_SQUARE_CHESS,
    ) = GameRecordEntity("synthetic-fq", game.code, StoredGameMode.HUMAN_VS_AI.code, Difficulty.EASY.code,
        1, version, bytes.copyOf(), count, completedAtEpochMillis = 30)

    private fun rejectedEverywhere(bytes: ByteArray, count: Int, version: Int = 1,
        game: GameType = GameType.LANZHOU_SQUARE_CHESS,
    ) {
        assertThrows(IllegalArgumentException::class.java) { record(bytes, count, version, game) }
        assertNull(raw(bytes, count, version, game).toGameRecord())
    }

    private fun history(count: Int): ByteArray {
        val root = "2".repeat(14) + "0".repeat(21) + "1".repeat(14)
        return ("MOCS-LFQ|1|1|$root|$count|" + List(count) { "m,1,35,28" }.joinToString(";")).toByteArray(Charsets.US_ASCII)
    }

    private class Rows : GameRecordDao {
        val rows = linkedMapOf<String, GameRecordEntity>()
        override suspend fun insert(entity: GameRecordEntity): Long {
            if (entity.recordId in rows) return -1
            rows[entity.recordId] = entity.copy(engineState = entity.engineState.copyOf())
            return rows.size.toLong()
        }
        override suspend fun listAll(): List<GameRecordEntity> = rows.values.toList()
        override suspend fun find(recordId: String): GameRecordEntity? = rows[recordId]?.let { it.copy(engineState = it.engineState.copyOf()) }
        override suspend fun setFavorite(recordId: String, favorite: Boolean): Int = error("unused")
        override suspend fun deleteAll(): Unit = error("must not clear rows")
    }
}
