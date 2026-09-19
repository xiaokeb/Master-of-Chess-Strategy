package com.masterofchessstrategy.data

import com.masterofchessstrategy.engine.Difficulty
import com.masterofchessstrategy.engine.GameType

internal data class LastGameSelection(
    val gameType: GameType,
    val mode: StoredGameMode,
    val difficulty: Difficulty?,
    val updatedAtEpochMillis: Long,
)

internal sealed interface LoadLastSelectionResult {
    data object NotFound : LoadLastSelectionResult

    data class Loaded(val selection: LastGameSelection) : LoadLastSelectionResult

    data object Incompatible : LoadLastSelectionResult
}

internal interface LastSelectionRepository {
    suspend fun load(gameType: GameType): LoadLastSelectionResult

    suspend fun save(selection: LastGameSelection)

    suspend fun clear(gameType: GameType)
}

/**
 * Stores navigation metadata separately from the versioned engine-state BLOB.
 *
 * A difficulty belongs only to AI modes. Rejecting invalid combinations keeps
 * quick start from bypassing the mode and difficulty screens.
 */
internal class RoomLastSelectionRepository(
    private val dao: LastSelectionDao,
) : LastSelectionRepository {
    override suspend fun load(gameType: GameType): LoadLastSelectionResult {
        val entity = dao.find(gameType.code) ?: return LoadLastSelectionResult.NotFound
        if (entity.gameTypeCode != gameType.code) {
            return LoadLastSelectionResult.Incompatible
        }
        val mode = StoredGameMode.entries.firstOrNull { it.code == entity.modeCode }
            ?: return LoadLastSelectionResult.Incompatible
        val difficulty = entity.difficultyCode?.let { code ->
            Difficulty.entries.firstOrNull { it.code == code }
                ?: return LoadLastSelectionResult.Incompatible
        }
        if (!mode.acceptsDifficulty(difficulty)) {
            return LoadLastSelectionResult.Incompatible
        }
        return LoadLastSelectionResult.Loaded(
            LastGameSelection(
                gameType = gameType,
                mode = mode,
                difficulty = difficulty,
                updatedAtEpochMillis = entity.updatedAtEpochMillis,
            ),
        )
    }

    override suspend fun save(selection: LastGameSelection) {
        require(selection.mode.acceptsDifficulty(selection.difficulty)) {
            "Difficulty is incompatible with the selected game mode"
        }
        dao.upsert(
            LastSelectionEntity(
                gameTypeCode = selection.gameType.code,
                modeCode = selection.mode.code,
                difficultyCode = selection.difficulty?.code,
                updatedAtEpochMillis = selection.updatedAtEpochMillis,
            ),
        )
    }

    override suspend fun clear(gameType: GameType) {
        dao.delete(gameType.code)
    }

    private fun StoredGameMode.acceptsDifficulty(difficulty: Difficulty?): Boolean =
        when (this) {
            StoredGameMode.HUMAN_VS_AI,
            StoredGameMode.AI_AUTO_PLAY,
            StoredGameMode.TIMED_CHALLENGE,
            StoredGameMode.STREAK_CHALLENGE,
            StoredGameMode.BLIND_CHALLENGE,
            -> true

            StoredGameMode.CUSTOM_POSITION -> difficulty != null

            StoredGameMode.LOCAL_TWO_PLAYER,
            StoredGameMode.ENDGAME,
            StoredGameMode.TUTORIAL,
            -> difficulty == null
        }
}
