package com.masterofchessstrategy.games.tiger_goat.data

import androidx.room.withTransaction
import com.masterofchessstrategy.data.ActiveGameEntity
import com.masterofchessstrategy.data.MocsDatabase
import com.masterofchessstrategy.data.StoredGameMode
import com.masterofchessstrategy.data.TigerGoatActiveArchive
import com.masterofchessstrategy.data.validateTigerGoatActiveArchive
import com.masterofchessstrategy.engine.Difficulty
import com.masterofchessstrategy.engine.GameType
import com.masterofchessstrategy.games.tiger_goat.ai.GatedTigerGoatFormalSessionStore
import com.masterofchessstrategy.games.tiger_goat.ai.TigerGoatFormalCheckpoint
import com.masterofchessstrategy.games.tiger_goat.ai.TigerGoatFormalConfig
import com.masterofchessstrategy.games.tiger_goat.ai.TigerGoatFormalLoadResult
import com.masterofchessstrategy.games.tiger_goat.ai.TigerGoatFormalSessionStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The active row contains raw MOCT plus versioned formal metadata. */
internal object TigerGoatFormalActiveRow {
    private const val VARIANT = "tiger-goat-ai-v1:"

    fun from(row: ActiveGameEntity): TigerGoatFormalCheckpoint {
        require(row.modeCode == StoredGameMode.HUMAN_VS_AI.code && row.difficultyCode == Difficulty.EASY.code)
        require(row.sessionVariantId.startsWith(VARIANT))
        val revision = row.sessionVariantId.removePrefix(VARIANT).toLong()
        require(row.sessionVariantId == "$VARIANT$revision")
        val config = TigerGoatFormalConfig(row.playerIndex + 1)
        val archive = TigerGoatActiveArchive.fromEntity(row.copy(modeCode = StoredGameMode.LOCAL_TWO_PLAYER.code,
            difficultyCode = null, sessionVariantId = "", playerIndex = 0))
        return TigerGoatFormalCheckpoint(archive.sessionId, revision, config, archive.actionCount,
            archive.updatedAtEpochMillis, archive.engineState)
    }

    fun archive(checkpoint: TigerGoatFormalCheckpoint) = TigerGoatActiveArchive(checkpoint.sessionId,
        checkpoint.copyBytes(), checkpoint.actionCount, checkpoint.updatedAt)

    fun to(checkpoint: TigerGoatFormalCheckpoint) = archive(checkpoint).toEntity().copy(
        modeCode = StoredGameMode.HUMAN_VS_AI.code, difficultyCode = checkpoint.config.difficulty.code,
        sessionVariantId = "$VARIANT${checkpoint.revision}", playerIndex = checkpoint.config.humanSide - 1,
    )
}

/** Compare against the last observed row so a second owner cannot silently replace a newer save. */
private class RoomTigerGoatFormalSessionStore(private val database: MocsDatabase) : TigerGoatFormalSessionStore {
    private val dao = database.activeGameDao()
    private var observed: ActiveGameEntity? = null
    private var loaded = false
    override val isCurrent = true

    override suspend fun load(): TigerGoatFormalLoadResult {
        val row = dao.find(GameType.TIGER_AND_GOAT.code)
        observed = row
        loaded = true
        if (row == null) return TigerGoatFormalLoadResult.Missing
        if (row.modeCode == StoredGameMode.LOCAL_TWO_PLAYER.code) return TigerGoatFormalLoadResult.LocalGame
        val checkpoint = try { TigerGoatFormalActiveRow.from(row) }
        catch (_: IllegalArgumentException) { return TigerGoatFormalLoadResult.Incompatible }
        return if (admitted(checkpoint)) {
            TigerGoatFormalLoadResult.Loaded(checkpoint)
        } else TigerGoatFormalLoadResult.Incompatible
    }

    override suspend fun save(checkpoint: TigerGoatFormalCheckpoint) {
        check(loaded) { "请先读取虎羊棋存档" }
        val row = TigerGoatFormalActiveRow.to(checkpoint)
        require(admitted(checkpoint))
        database.withTransaction {
            val current = dao.find(GameType.TIGER_AND_GOAT.code)
            check(current == observed || current == row) { "虎羊棋存档已更新，请重新打开" }
            check(current == null || current.modeCode == StoredGameMode.HUMAN_VS_AI.code) { "已有其他模式的虎羊棋局" }
            if (current != row) dao.upsert(row)
        }
        observed = row
    }

    private suspend fun admitted(checkpoint: TigerGoatFormalCheckpoint) = withContext(Dispatchers.Default) {
        validateTigerGoatActiveArchive(TigerGoatFormalActiveRow.archive(checkpoint))
    }
}

internal fun roomTigerGoatFormalStore(database: MocsDatabase): TigerGoatFormalSessionStore =
    GatedTigerGoatFormalSessionStore(RoomTigerGoatFormalSessionStore(database), TigerGoatStorageEpochGate.forDatabase(database))
