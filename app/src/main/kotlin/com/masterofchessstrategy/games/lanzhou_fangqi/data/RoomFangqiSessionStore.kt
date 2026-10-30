package com.masterofchessstrategy.games.lanzhou_fangqi.data

import androidx.room.withTransaction
import com.masterofchessstrategy.data.ActiveGameDao
import com.masterofchessstrategy.data.ActiveGameEntity
import com.masterofchessstrategy.data.MocsDatabase
import com.masterofchessstrategy.engine.Difficulty
import com.masterofchessstrategy.engine.GameType
import com.masterofchessstrategy.games.lanzhou_fangqi.FangqiSessionStore
import com.masterofchessstrategy.games.lanzhou_fangqi.FangqiStoredMode
import com.masterofchessstrategy.games.lanzhou_fangqi.FangqiStoredSession
import java.io.IOException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Uses the existing per-game BLOB slot, without the shared Xiangqi format/64 KiB restrictions. */
internal class RoomFangqiSessionStore internal constructor(
    private val dao: ActiveGameDao,
    private val gate: FangqiStorageEpochGate,
    private val owner: FangqiStorageEpochGate.Owner,
    private val admission: FangqiSessionAdmission,
    private val dispatcher: CoroutineDispatcher,
    // Explicit test seam; production always uses the MocsDatabase constructor below.
    private val transaction: suspend (suspend () -> Unit) -> Unit,
) : FangqiSessionStore {
    private var rankedDao: FangqiRankedDao? = null
    constructor(
        database: MocsDatabase,
        gate: FangqiStorageEpochGate = FangqiStorageEpochGate.forDatabase(database),
        owner: FangqiStorageEpochGate.Owner = gate.captureOwner(),
    ) : this(database.activeGameDao(), gate, owner, FangqiSessionAdmission(), Dispatchers.IO,
        { operation -> database.withTransaction { operation() } }) {
        require(gate === FangqiStorageEpochGate.forDatabase(database)) { "方棋存储必须使用该数据库的统一门禁" }
        rankedDao = database.fangqiRankedDao()
    }

    override suspend fun load(): FangqiStoredSession? = gate.access(owner) {
        withContext(dispatcher) {
            currentCoroutineContext().ensureActive()
            val row = dao.find(GameType.LANZHOU_SQUARE_CHESS.code)?.copyOwned() ?: return@withContext null
            rejectFormalSource(row.sessionId)
            val snapshot = decode(row)
            admission.admit(snapshot).also { currentCoroutineContext().ensureActive() }
        }
    }

    override suspend fun save(snapshot: FangqiStoredSession) = gate.access(owner) {
        withContext(dispatcher) {
            currentCoroutineContext().ensureActive()
            val candidate = encode(admission.admit(snapshot))
            transaction {
                currentCoroutineContext().ensureActive()
                dao.find(GameType.LANZHOU_SQUARE_CHESS.code)?.let { rejectFormalSource(it.sessionId) }
                rejectFormalSource(candidate.sessionId)
                dao.upsert(candidate.copyOwned())
                currentCoroutineContext().ensureActive()
                val readBack = dao.find(GameType.LANZHOU_SQUARE_CHESS.code)?.copyOwned()
                if (readBack != candidate) throw IOException("方棋写入读回不一致，保存已回滚")
                currentCoroutineContext().ensureActive()
            }
            // Successful return is the only save acknowledgement; enqueueing is never an acknowledgement.
        }
    }

    private suspend fun rejectFormalSource(sessionId: String) {
        if (rankedDao?.findStart(sessionId) != null) {
            throw IOException("此局已有正式人机来源，请从正式对局入口继续；原存档已保留")
        }
    }

    private fun decode(row: ActiveGameEntity): FangqiStoredSession {
        if (row.gameTypeCode != GameType.LANZHOU_SQUARE_CHESS.code ||
            row.envelopeVersion != ENVELOPE_VERSION || row.engineFormatVersion != FangqiStoredSession.ENGINE_FORMAT_VERSION ||
            row.resultOverrideCode != null || row.timeControlMinutes != null || row.redRemainingMillis != null ||
            row.blackRemainingMillis != null || row.turnStartedAtEpochMillis != null || row.pendingDrawOfferSideCode != null ||
            row.autoPlayPaused || row.autoPlaySpeedPermille != 1_000 || row.completedAutoGames != 0 ||
            row.sessionVariantId.isNotEmpty() || row.playerIndex != 0
        ) throw IOException("方棋存档字段不兼容，原存档已保留")
        val mode = FangqiStoredMode.entries.singleOrNull { it.code == row.modeCode }
            ?: throw IOException("方棋存档模式无效，原存档已保留")
        val difficulty = row.difficultyCode?.let { code ->
            Difficulty.entries.singleOrNull { it.code == code }
                ?: throw IOException("方棋存档难度无效，原存档已保留")
        }
        return try {
            FangqiStoredSession(mode, difficulty, row.engineState, row.sessionId, row.updatedAtEpochMillis,
                row.acceptedMoveCount, row.undoUseCount, row.hintUseCount)
        } catch (invalid: IllegalArgumentException) {
            throw IOException("方棋存档元数据无效，原存档已保留", invalid)
        }
    }

    private fun encode(snapshot: FangqiStoredSession) = ActiveGameEntity(
        gameTypeCode = GameType.LANZHOU_SQUARE_CHESS.code,
        modeCode = snapshot.mode.code,
        difficultyCode = snapshot.difficulty?.code,
        envelopeVersion = ENVELOPE_VERSION,
        engineFormatVersion = FangqiStoredSession.ENGINE_FORMAT_VERSION,
        engineState = snapshot.engineState,
        updatedAtEpochMillis = snapshot.updatedAtEpochMillis,
        sessionId = snapshot.sessionId,
        acceptedMoveCount = snapshot.acceptedActionCount,
        undoUseCount = snapshot.undoUseCount,
        hintUseCount = snapshot.hintUseCount,
    )

    private fun ActiveGameEntity.copyOwned() = copy(engineState = engineState.copyOf())

    companion object {
        const val ENVELOPE_VERSION = 5
    }
}
