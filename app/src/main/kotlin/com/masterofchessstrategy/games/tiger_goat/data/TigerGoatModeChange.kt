package com.masterofchessstrategy.games.tiger_goat.data

import androidx.room.withTransaction
import com.masterofchessstrategy.data.ActiveGameEntity
import com.masterofchessstrategy.data.LoadTutorialProgressResult
import com.masterofchessstrategy.data.MocsDatabase
import com.masterofchessstrategy.data.RoomTutorialProgressRepository
import com.masterofchessstrategy.data.StoredGameMode
import com.masterofchessstrategy.data.TigerGoatActiveArchive
import com.masterofchessstrategy.engine.GameType
import com.masterofchessstrategy.engine.games.tiger_goat.NativeTigerGoatSession
import com.masterofchessstrategy.games.tiger_goat.ai.TigerGoatFormalCheckpoint
import com.masterofchessstrategy.games.tiger_goat.ai.TigerGoatFormalConfig
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Both modes use the same row. The UI holds a proposal only until the player confirms it. */
internal class TigerGoatModeChange(private val database: MocsDatabase) {
    private val gate = TigerGoatStorageEpochGate.forDatabase(database)
    private val owner = gate.captureOwner()
    private val dao = database.activeGameDao()
    private val tutorials = RoomTutorialProgressRepository(database.tutorialProgressDao())

    internal class Proposal internal constructor(
        internal val owner: TigerGoatStorageEpochGate.Owner,
        private val previous: ActiveGameEntity?,
        internal val formal: TigerGoatFormalConfig?,
    ) {
        val replacesSavedGame: Boolean get() = previous != null
        internal fun matches(current: ActiveGameEntity?) = previous == current
    }

    suspend fun prepare(formal: TigerGoatFormalConfig?): Proposal = gate.access(owner) {
        val row = active()
        require(row == null || row.modeCode == oppositeMode(formal)) { "已有此模式棋局，请继续原局" }
        requireQualification(formal)
        Proposal(owner, row, formal)
    }

    suspend fun start(proposal: Proposal) {
        require(proposal.owner === owner) { "请重新确认虎羊棋模式切换" }
        val archive = withContext(Dispatchers.Default) {
            NativeTigerGoatSession().use { native ->
                TigerGoatActiveArchive(UUID.randomUUID().toString(), native.serialize(), 0, System.currentTimeMillis())
            }
        }
        val formal = proposal.formal
        val newRow = if (formal == null) archive.toEntity() else TigerGoatFormalActiveRow.to(
            TigerGoatFormalCheckpoint(archive.sessionId, 1L, formal, 0, archive.updatedAtEpochMillis, archive.engineState),
        )
        gate.commitReplacement(owner) {
            database.withTransaction {
                val row = active()
                check(proposal.matches(row) && (row == null || row.modeCode == oppositeMode(formal))) {
                    "虎羊棋存档已更新，请重新确认"
                }
                requireQualification(formal)
                dao.upsert(newRow)
            }
        }
    }

    private suspend fun active() = dao.find(GameType.TIGER_AND_GOAT.code)

    private fun oppositeMode(formal: TigerGoatFormalConfig?) =
        if (formal == null) StoredGameMode.HUMAN_VS_AI.code else StoredGameMode.LOCAL_TWO_PLAYER.code

    private suspend fun requireQualification(formal: TigerGoatFormalConfig?) {
        if (formal == null) return
        val result = tutorials.load(GameType.TIGER_AND_GOAT)
        check(result is LoadTutorialProgressResult.Loaded && result.progress.isCompleted) {
            "请先完成老虎吃羊棋教程"
        }
    }
}
