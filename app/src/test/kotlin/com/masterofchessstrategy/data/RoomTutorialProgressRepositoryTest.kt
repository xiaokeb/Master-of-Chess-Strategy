package com.masterofchessstrategy.data

import com.masterofchessstrategy.engine.GameType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class RoomTutorialProgressRepositoryTest {
    @Test
    fun saveAndLoadPreserveVersionedProgress() = runBlocking {
        val dao = FakeTutorialProgressDao()
        val repository = RoomTutorialProgressRepository(dao)
        val expected = TutorialProgress(
            gameType = GameType.CHINESE_CHESS,
            contentVersion = TutorialProgress.CURRENT_CONTENT_VERSION,
            completedStepCount = 3,
            isCompleted = false,
            updatedAtEpochMillis = 99L,
        )

        repository.save(expected)

        assertEquals(
            expected,
            (repository.load(GameType.CHINESE_CHESS) as
                LoadTutorialProgressResult.Loaded).progress,
        )
    }

    @Test
    fun contradictoryOrOutdatedRowsAreIncompatible() = runBlocking {
        val outdated = FakeTutorialProgressDao(
            TutorialProgressEntity(
                gameTypeCode = GameType.CHINESE_CHESS.code,
                contentVersion = TutorialProgress.CURRENT_CONTENT_VERSION + 1,
                completedStepCount = 4,
                isCompleted = true,
                updatedAtEpochMillis = 0L,
            ),
        )
        val contradictory = FakeTutorialProgressDao(
            TutorialProgressEntity(
                gameTypeCode = GameType.CHINESE_CHESS.code,
                contentVersion = TutorialProgress.CURRENT_CONTENT_VERSION,
                completedStepCount = 2,
                isCompleted = true,
                updatedAtEpochMillis = 0L,
            ),
        )

        assertSame(
            LoadTutorialProgressResult.Incompatible,
            RoomTutorialProgressRepository(outdated).load(GameType.CHINESE_CHESS),
        )
        assertSame(
            LoadTutorialProgressResult.Incompatible,
            RoomTutorialProgressRepository(contradictory).load(GameType.CHINESE_CHESS),
        )
    }

    private class FakeTutorialProgressDao(
        var entity: TutorialProgressEntity? = null,
    ) : TutorialProgressDao {
        override suspend fun find(gameTypeCode: Int): TutorialProgressEntity? = entity

        override suspend fun upsert(entity: TutorialProgressEntity) {
            this.entity = entity
        }

        override suspend fun delete(gameTypeCode: Int) {
            entity = null
        }
    }
}
