package com.masterofchessstrategy.data

import com.masterofchessstrategy.engine.Difficulty
import com.masterofchessstrategy.engine.GameType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class RoomLastSelectionRepositoryTest {
    @Test
    fun saveAndLoadMapStableCodes() = runBlocking {
        val dao = FakeLastSelectionDao()
        val repository = RoomLastSelectionRepository(dao)
        val selection = LastGameSelection(
            gameType = GameType.CHINESE_CHESS,
            mode = StoredGameMode.HUMAN_VS_AI,
            difficulty = Difficulty.MEDIUM,
            updatedAtEpochMillis = 42L,
        )

        repository.save(selection)

        assertEquals(GameType.CHINESE_CHESS.code, dao.entity?.gameTypeCode)
        assertEquals(StoredGameMode.HUMAN_VS_AI.code, dao.entity?.modeCode)
        assertEquals(Difficulty.MEDIUM.code, dao.entity?.difficultyCode)
        assertEquals(
            selection,
            (repository.load(GameType.CHINESE_CHESS) as LoadLastSelectionResult.Loaded)
                .selection,
        )
    }

    @Test
    fun unknownModeIsRejectedAsIncompatible() = runBlocking {
        val dao = FakeLastSelectionDao(
            LastSelectionEntity(
                gameTypeCode = GameType.CHINESE_CHESS.code,
                modeCode = 99,
                difficultyCode = null,
                updatedAtEpochMillis = 0L,
            ),
        )

        val result = RoomLastSelectionRepository(dao).load(GameType.CHINESE_CHESS)

        assertSame(LoadLastSelectionResult.Incompatible, result)
    }

    @Test
    fun localModeRejectsDifficulty() {
        val repository = RoomLastSelectionRepository(FakeLastSelectionDao())

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                repository.save(
                    LastGameSelection(
                        gameType = GameType.CHINESE_CHESS,
                        mode = StoredGameMode.LOCAL_TWO_PLAYER,
                        difficulty = Difficulty.EASY,
                        updatedAtEpochMillis = 0L,
                    ),
                )
            }
        }
    }

    private class FakeLastSelectionDao(
        var entity: LastSelectionEntity? = null,
    ) : LastSelectionDao {
        override suspend fun find(gameTypeCode: Int): LastSelectionEntity? =
            entity?.takeIf { it.gameTypeCode == gameTypeCode }

        override suspend fun upsert(entity: LastSelectionEntity) {
            this.entity = entity
        }

        override suspend fun delete(gameTypeCode: Int) {
            if (entity?.gameTypeCode == gameTypeCode) entity = null
        }
    }
}
