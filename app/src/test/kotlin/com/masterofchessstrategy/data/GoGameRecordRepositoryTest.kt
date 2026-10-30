package com.masterofchessstrategy.data

import com.masterofchessstrategy.engine.GameResult
import com.masterofchessstrategy.engine.GameType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Admission callbacks are spies, not Native substitutes proving Go rules. */
class GoGameRecordRepositoryTest {
    @Test fun goLargerThan64KiBRoundTripsWithoutTruncationOrXiangqiVersionRewrite() = runBlocking {
        val dao = Rows()
        var admissions = 0
        val repository = RoomGameRecordRepository(dao) { admissions++ }
        val record = goRecord(GameRecordPayloadPolicyTest.frame(3, 4_000))
        assertTrue(repository.saveCompleted(record))
        val loaded = requireNotNull(repository.load(record.recordId))
        assertEquals(84_016, loaded.engineState.size)
        assertEquals(3, loaded.engineFormatVersion)
        assertArrayEquals(record.engineState, loaded.engineState)
        assertEquals(2, admissions)
    }

    @Test fun everyLegacyNonGoFamilyKeepsVersion2AndNeverCallsGoAdmission() = runBlocking {
        val dao = Rows()
        val repository = RoomGameRecordRepository(dao) { error("Non-Go row invoked Go admission") }
        for (game in GameType.entries.filter { it != GameType.GO }) {
            val record = GameRecord("legacy-${game.code}", game, StoredGameMode.LOCAL_TWO_PLAYER,
                null, GameResult.DRAW, byteArrayOf(1, 2, 3), 2, completedAtEpochMillis = 1,
                engineFormatVersion = 2)
            assertTrue(repository.saveCompleted(record))
            assertEquals(2, requireNotNull(repository.load(record.recordId)).engineFormatVersion)
        }
        assertEquals(5, (repository.list(GameRecordCategory.ALL) as LoadGameRecordsResult.Loaded).records.size)
    }

    @Test fun goMetadataConversionPreservesEachSupportedNativeVersionAndOriginalBytes() {
        for (version in 1..3) {
            val record = goRecord(GameRecordPayloadPolicyTest.frame(version, 2), version)
            val row = record.toGameRecordEntity()
            val restored = requireNotNull(row.toGameRecord())
            assertEquals(version, row.engineFormatVersion)
            assertEquals(version, restored.engineFormatVersion)
            assertArrayEquals(record.engineState, restored.engineState)
            row.engineState.fill(99)
            assertEquals('M'.code, restored.engineState[0].toInt())
        }
    }

    @Test fun failedAdmissionNeverInsertsOrClearsExistingRows() = runBlocking {
        val dao = Rows()
        val existing = goRecord().toGameRecordEntity()
        dao.rows[existing.recordId] = existing
        val repository = RoomGameRecordRepository(dao) { throw IllegalArgumentException("Rejected history") }
        assertThrows(IllegalArgumentException::class.java) { runBlocking {
            repository.saveCompleted(goRecord().copy(recordId = "rejected"))
        } }
        assertEquals(listOf(existing.recordId), dao.rows.keys.toList())
        assertEquals(0, dao.inserts)
        assertNull(repository.load(existing.recordId))
        assertSame(LoadGameRecordsResult.Incompatible, repository.list(GameRecordCategory.ALL))
        assertEquals(1, dao.rows.size)
    }

    @Test fun callerAndAdmissionCannotMutateTheOwnedPersistedPayload() = runBlocking {
        val dao = Rows()
        val record = goRecord()
        val original = record.engineState.copyOf()
        val repository = RoomGameRecordRepository(dao) { admitted ->
            assertArrayEquals(original, admitted.engineState)
            record.engineState.fill(99)
            admitted.engineState.fill(88)
        }
        assertTrue(repository.saveCompleted(record))
        assertArrayEquals(original, dao.rows.getValue(record.recordId).engineState)
    }

    @Test fun headerVersionMismatchFailsBeforeNativeAndKeepsTheStoredRow() = runBlocking {
        val dao = Rows()
        val record = goRecord()
        dao.rows[record.recordId] = record.toGameRecordEntity().copy(engineFormatVersion = 2)
        val repository = RoomGameRecordRepository(dao) { error("Invalid envelope reached Native") }
        assertNull(repository.load(record.recordId))
        assertSame(LoadGameRecordsResult.Incompatible, repository.list(GameRecordCategory.ALL))
        assertEquals(1, dao.rows.size)
    }

    @Test fun ongoingGoCannotBeDisguisedAsCompletedAndOldXiangqiLimitRemains() {
        assertThrows(IllegalArgumentException::class.java) { goRecord().copy(result = GameResult.ONGOING) }
        assertThrows(IllegalArgumentException::class.java) { GameRecord(
            "oversized-xiangqi", GameType.CHINESE_CHESS, StoredGameMode.LOCAL_TWO_PLAYER,
            null, GameResult.DRAW, ByteArray(65_537), 0, completedAtEpochMillis = 1,
        ) }
    }

    private fun goRecord(bytes: ByteArray = GameRecordPayloadPolicyTest.frame(3, 2), version: Int = 3) =
        GameRecord("go-1", GameType.GO, StoredGameMode.LOCAL_TWO_PLAYER,
            null, GameResult.DRAW, bytes, 2, completedAtEpochMillis = 1, engineFormatVersion = version)

    private class Rows : GameRecordDao {
        val rows = linkedMapOf<String, GameRecordEntity>()
        var inserts = 0
        override suspend fun insert(entity: GameRecordEntity): Long {
            inserts++
            if (entity.recordId in rows) return -1
            rows[entity.recordId] = entity.copy(engineState = entity.engineState.copyOf())
            return rows.size.toLong()
        }
        override suspend fun listAll() = rows.values.toList()
        override suspend fun find(recordId: String) = rows[recordId]
        override suspend fun setFavorite(recordId: String, favorite: Boolean): Int {
            val current = rows[recordId] ?: return 0
            rows[recordId] = current.copy(isFavorite = favorite)
            return 1
        }
        override suspend fun deleteAll() { rows.clear() }
    }
}
