package com.masterofchessstrategy.data

import com.masterofchessstrategy.engine.Difficulty

internal data class AppSettings(
    val defaultDifficulty: Difficulty,
    val autoContinueEnabled: Boolean,
    val autoContinueGameLimit: Int = 10,
    val soundEnabled: Boolean,
    val gameDurationMinutes: Int?,
    val updatedAtEpochMillis: Long,
) {
    init {
        require(gameDurationMinutes == null || gameDurationMinutes in DURATION_RANGE) {
            "Game duration must be unlimited or between 5 and 180 minutes"
        }
        require(autoContinueGameLimit in AUTO_CONTINUE_LIMIT_RANGE) {
            "Auto-continue limit must be between 1 and 100 games"
        }
    }

    companion object {
        val DURATION_RANGE = 5..180
        val AUTO_CONTINUE_LIMIT_RANGE = 1..100

        val DEFAULT = AppSettings(
            defaultDifficulty = Difficulty.EASY,
            autoContinueEnabled = false,
            autoContinueGameLimit = 10,
            soundEnabled = true,
            gameDurationMinutes = null,
            updatedAtEpochMillis = 0L,
        )
    }
}

internal sealed interface LoadAppSettingsResult {
    data object NotFound : LoadAppSettingsResult

    data class Loaded(val settings: AppSettings) : LoadAppSettingsResult

    data object Incompatible : LoadAppSettingsResult
}

internal interface AppSettingsRepository {
    suspend fun load(): LoadAppSettingsResult

    suspend fun save(settings: AppSettings)

    suspend fun clear()
}

internal class RoomAppSettingsRepository(
    private val dao: AppSettingsDao,
) : AppSettingsRepository {
    override suspend fun load(): LoadAppSettingsResult {
        val entity = dao.find() ?: return LoadAppSettingsResult.NotFound
        if (entity.id != SINGLETON_ID) return LoadAppSettingsResult.Incompatible
        val difficulty = Difficulty.entries.firstOrNull {
            it.code == entity.defaultDifficultyCode
        } ?: return LoadAppSettingsResult.Incompatible
        val duration = entity.gameDurationMinutes
        if (duration != null && duration !in AppSettings.DURATION_RANGE) {
            return LoadAppSettingsResult.Incompatible
        }
        if (entity.autoContinueGameLimit !in AppSettings.AUTO_CONTINUE_LIMIT_RANGE) {
            return LoadAppSettingsResult.Incompatible
        }
        return LoadAppSettingsResult.Loaded(
            AppSettings(
                defaultDifficulty = difficulty,
                autoContinueEnabled = entity.autoContinueEnabled,
                autoContinueGameLimit = entity.autoContinueGameLimit,
                soundEnabled = entity.soundEnabled,
                gameDurationMinutes = duration,
                updatedAtEpochMillis = entity.updatedAtEpochMillis,
            ),
        )
    }

    override suspend fun save(settings: AppSettings) {
        dao.upsert(
            AppSettingsEntity(
                id = SINGLETON_ID,
                defaultDifficultyCode = settings.defaultDifficulty.code,
                autoContinueEnabled = settings.autoContinueEnabled,
                autoContinueGameLimit = settings.autoContinueGameLimit,
                soundEnabled = settings.soundEnabled,
                gameDurationMinutes = settings.gameDurationMinutes,
                updatedAtEpochMillis = settings.updatedAtEpochMillis,
            ),
        )
    }

    override suspend fun clear() {
        dao.delete()
    }

    private companion object {
        const val SINGLETON_ID = 0
    }
}
