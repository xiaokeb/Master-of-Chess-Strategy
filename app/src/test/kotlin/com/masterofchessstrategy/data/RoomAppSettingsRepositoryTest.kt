package com.masterofchessstrategy.data

import com.masterofchessstrategy.engine.Difficulty
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class RoomAppSettingsRepositoryTest {
    @Test
    fun missingRowUsesExplicitNotFoundResult() = runBlocking {
        val result = RoomAppSettingsRepository(FakeAppSettingsDao()).load()

        assertSame(LoadAppSettingsResult.NotFound, result)
    }

    @Test
    fun saveAndLoadPreserveAllSettings() = runBlocking {
        val dao = FakeAppSettingsDao()
        val repository = RoomAppSettingsRepository(dao)
        val expected = AppSettings(
            defaultDifficulty = Difficulty.HARD,
            autoContinueEnabled = true,
            soundEnabled = false,
            gameDurationMinutes = 45,
            updatedAtEpochMillis = 99L,
        )

        repository.save(expected)

        assertEquals(expected, (repository.load() as LoadAppSettingsResult.Loaded).settings)
    }

    @Test
    fun invalidDifficultyOrDurationIsIncompatible() = runBlocking {
        val dao = FakeAppSettingsDao(
            AppSettingsEntity(
                id = 0,
                defaultDifficultyCode = 99,
                autoContinueEnabled = false,
                soundEnabled = true,
                gameDurationMinutes = 181,
                updatedAtEpochMillis = 0L,
            ),
        )

        assertSame(
            LoadAppSettingsResult.Incompatible,
            RoomAppSettingsRepository(dao).load(),
        )
    }

    private class FakeAppSettingsDao(
        var entity: AppSettingsEntity? = null,
    ) : AppSettingsDao {
        override suspend fun find(): AppSettingsEntity? = entity

        override suspend fun upsert(entity: AppSettingsEntity) {
            this.entity = entity
        }

        override suspend fun delete() {
            entity = null
        }
    }
}
