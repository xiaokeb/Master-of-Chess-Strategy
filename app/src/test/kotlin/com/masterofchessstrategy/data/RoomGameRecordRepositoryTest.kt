package com.masterofchessstrategy.data

import com.masterofchessstrategy.engine.Difficulty
import com.masterofchessstrategy.engine.GameResult
import com.masterofchessstrategy.engine.GameType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class RoomGameRecordRepositoryTest {
    @Test
    fun completedGameIsIdempotentAndFavoriteFilterUsesStoredFlag() = runBlocking {
        val dao = FakeGameRecordDao()
        val repository = RoomGameRecordRepository(dao)
        val record = record("match-1")

        assertTrue(repository.saveCompleted(record))
        assertFalse(repository.saveCompleted(record))
        assertTrue(repository.setFavorite(record.recordId, true))

        val favorites = repository.list(GameRecordCategory.FAVORITES)
            as LoadGameRecordsResult.Loaded
        assertEquals(listOf("match-1"), favorites.records.map(GameRecord::recordId))
        assertTrue(favorites.records.single().isFavorite)
    }

    @Test
    fun returnedStateIsDefensivelyCopied() = runBlocking {
        val repository = RoomGameRecordRepository(FakeGameRecordDao())
        repository.saveCompleted(record("copy"))

        val first = requireNotNull(repository.load("copy"))
        first.engineState[0] = 99
        val second = requireNotNull(repository.load("copy"))

        assertEquals(1, second.engineState[0].toInt())
    }

    @Test
    fun incompatibleEngineVersionRejectsWholeList() = runBlocking {
        val dao = FakeGameRecordDao().apply {
            entities["broken"] = record("broken").toEntity().copy(engineFormatVersion = 99)
        }

        assertSame(
            LoadGameRecordsResult.Incompatible,
            RoomGameRecordRepository(dao).list(GameRecordCategory.ALL),
        )
    }

    private fun record(id: String) =
        GameRecord(
            recordId = id,
            gameType = GameType.CHINESE_CHESS,
            mode = StoredGameMode.HUMAN_VS_AI,
            difficulty = Difficulty.EASY,
            result = GameResult.FIRST_PLAYER_WIN,
            engineState = byteArrayOf(1, 2, 3),
            moveCount = 18,
            completedAtEpochMillis = 1_000L,
        )

    private fun GameRecord.toEntity() =
        GameRecordEntity(
            recordId = recordId,
            gameTypeCode = gameType.code,
            modeCode = mode.code,
            difficultyCode = difficulty?.code,
            resultCode = 1,
            engineFormatVersion = 2,
            engineState = engineState.copyOf(),
            moveCount = moveCount,
            isFavorite = isFavorite,
            isEndgame = isEndgame,
            completedAtEpochMillis = completedAtEpochMillis,
        )

    private class FakeGameRecordDao : GameRecordDao {
        val entities = linkedMapOf<String, GameRecordEntity>()

        override suspend fun insert(entity: GameRecordEntity): Long {
            if (entities.containsKey(entity.recordId)) return -1L
            entities[entity.recordId] = entity.copy(engineState = entity.engineState.copyOf())
            return entities.size.toLong()
        }

        override suspend fun listAll(): List<GameRecordEntity> =
            entities.values.toList()

        override suspend fun find(recordId: String): GameRecordEntity? =
            entities[recordId]?.copy(engineState = entities.getValue(recordId).engineState.copyOf())

        override suspend fun setFavorite(recordId: String, favorite: Boolean): Int {
            val current = entities[recordId] ?: return 0
            entities[recordId] = current.copy(isFavorite = favorite)
            return 1
        }
    }
}
