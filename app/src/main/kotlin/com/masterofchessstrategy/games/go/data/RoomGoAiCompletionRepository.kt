package com.masterofchessstrategy.games.go.data

import androidx.room.withTransaction
import com.masterofchessstrategy.data.GameRecord
import com.masterofchessstrategy.data.MocsDatabase
import com.masterofchessstrategy.data.toGameRecord
import com.masterofchessstrategy.data.toGameRecordEntity
import com.masterofchessstrategy.data.toMatchOutcomeEntity
import com.masterofchessstrategy.engine.GameType
import com.masterofchessstrategy.engine.GoPhase
import com.masterofchessstrategy.games.go.GoAiCompletedMatchSink
import com.masterofchessstrategy.games.go.GoAiMatchFileCodec
import com.masterofchessstrategy.games.go.GoAiSavedMatch
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Projects durable terminals; only an explicitly persisted formal start can settle growth. */
internal class RoomGoAiCompletionRepository(
    private val database: MocsDatabase,
    private val gate: GoStorageEpochGate = GoStorageEpochGate.forDatabase(database),
    private val ownerEpoch: Long = gate.epoch.value,
    private val worker: CoroutineDispatcher = Dispatchers.Default,
    private val beforeCommit: suspend () -> Unit = {},
) : GoAiCompletedMatchSink {
    override suspend fun storeCompleted(match: GoAiSavedMatch) = gate.access(ownerEpoch) {
        val admitted = withContext(worker) { admitGoAiCheckpoint(match) }
        require(admitted.phase == GoPhase.FINISHED && admitted.match().completionReserved) {
            "围棋人机结果尚未固定，不能提交完成棋谱"
        }
        database.withTransaction {
            val first = requireNotNull(database.goAiCheckpointDao().findArchive(admitted.recordId)) {
                "围棋人机完成缺少首次原始归档"
            }.let(GoAiCheckpointRows::archive)
            val projection = withContext(worker) { projectGoAiRecord(first) }
            requireTerminalMatch(first, admitted.match())
            requireArchiveChain(first)
            val existing = database.gameRecordDao().find(projection.recordId)
            val settled = database.goRankedDao().settlement(admitted.recordId)
            val start = database.goRankedDao().start(admitted.recordId)?.toStart()
            val outcome = start?.let { withContext(worker) { qualifiedGoOutcome(it, first) } }
            if (existing == null || (outcome != null && settled == null)) {
                // The caller's reservation flag is not proof: it must already be in the DB.
                val row = requireNotNull(database.goAiCheckpointDao().findActive(GameType.GO.code)) {
                    "围棋人机首次完成缺少已固定活动状态"
                }
                val active = GoAiCheckpointRows.entry(row)
                require(active.lineage == first.checkpoint.lineage &&
                    active.copyBytes().contentEquals(GoAiMatchFileCodec.encode(admitted.match()))) {
                    "围棋人机首次完成与已固定活动状态不符"
                }
            }
            insertOrVerify(projection)
            if (outcome != null) {
                val row = outcome.toMatchOutcomeEntity()
                database.matchOutcomeDao().insert(row)
                require(database.matchOutcomeDao().find(row.matchId) == row) {
                    "围棋正式成长与首次终局账本冲突"
                }
                database.goRankedDao().insertSettlement(GoRankedSettlementEntity(row.matchId))
                require(database.goRankedDao().settlement(row.matchId) == GoRankedSettlementEntity(row.matchId))
            } else require(settled == null) { "围棋已结算来源不再满足正式条件" }
            finishTransaction()
        }
    }

    /** Explicit, all-or-nothing backfill; ordinary list/replay never triggers it. */
    suspend fun projectExistingArchives(): Int = gate.access(ownerEpoch) {
        database.withTransaction {
            var inserted = 0
            val archives = database.goAiCheckpointDao().listArchives().map(GoAiCheckpointRows::archive)
            requireArchiveGraph(archives)
            for (first in archives) {
                val projection = withContext(worker) { projectGoAiRecord(first) }
                if (insertOrVerify(projection)) inserted++
            }
            finishTransaction()
            inserted
        }
    }

    private suspend fun insertOrVerify(projection: GameRecord): Boolean {
        require(database.goRecordSourceDao().find(projection.recordId) == null) {
            "围棋人机棋谱身份与本地来源冲突"
        }
        val previous = database.gameRecordDao().find(projection.recordId)
        if (previous != null) {
            requireSameGoAiRecord(requireNotNull(previous.toGameRecord()), projection)
            associate(projection.recordId)
            return false // Keep the original favorite flag and first completion time.
        }
        check(database.gameRecordDao().insert(projection.toGameRecordEntity()) != -1L)
        associate(projection.recordId)
        return true
    }

    private suspend fun associate(recordId: String) {
        database.goAiProjectionDao().insert(GoAiProjectionEntity(recordId))
        require(database.goAiProjectionDao().find(recordId) == GoAiProjectionEntity(recordId)) {
            "围棋人机棋谱投影来源版本冲突"
        }
    }

    private suspend fun finishTransaction() {
        beforeCommit() // Fault/cancellation seam; production performs no additional side effect.
        database.requireGoAiArchiveParents()
        database.requireGoAiProjectionParents()
        database.requireGoRankedParents()
        currentCoroutineContext().ensureActive()
    }

    private suspend fun requireArchiveChain(first: GoAiCheckpointArchive) {
        val visited = mutableSetOf(first.recordId)
        var cursor = first
        while (true) {
            val parentId = cursor.checkpoint.lineage.replacedTerminalRecordId ?: return
            require(visited.add(parentId)) { "围棋人机完成来源存在循环" }
            cursor = requireNotNull(database.goAiCheckpointDao().findArchive(parentId)) {
                "围棋人机完成来源缺少父终局"
            }.let(GoAiCheckpointRows::archive)
            withContext(worker) { projectGoAiRecord(cursor) } // Parent must also be a valid terminal source.
        }
    }

    private fun requireArchiveGraph(archives: List<GoAiCheckpointArchive>) {
        val byId = archives.associateBy { it.recordId }
        val checked = mutableSetOf<String>()
        for (first in archives) {
            val path = mutableSetOf<String>()
            var id: String? = first.recordId
            while (id != null && id !in checked) {
                require(path.add(id)) { "围棋人机完成来源存在循环" }
                val current = requireNotNull(byId[id]) { "围棋人机完成来源缺少父终局" }
                id = current.checkpoint.lineage.replacedTerminalRecordId
            }
            checked.addAll(path)
        }
    }

    private fun requireTerminalMatch(first: GoAiCheckpointArchive, next: GoAiSavedMatch) {
        val original = requireNotNull(GoAiMatchFileCodec.decode(first.checkpoint.copyBytes()))
        require(terminalContent(original).contentEquals(terminalContent(next))) {
            "围棋人机完成内容与首次原始归档冲突"
        }
        val unchanged = original.autoContinueEnabled == next.autoContinueEnabled &&
            original.autoContinueMaxGames == next.autoContinueMaxGames &&
            original.autoContinueGameNumber == next.autoContinueGameNumber
        val ended = original.autoContinueEnabled && !next.autoContinueEnabled &&
            next.autoContinueMaxGames == 1 && next.autoContinueGameNumber == 1
        require(unchanged || ended) { "围棋人机完成不能改写已归档系列" }
    }

    private fun terminalContent(match: GoAiSavedMatch): ByteArray = GoAiMatchFileCodec.encode(
        GoAiSavedMatch(match.matchId, match.config, match.copyBytes(), match.undoUsed, match.hintUsed,
            false, match.rememberedHuman, match.automaticUsed, match.copyOpeningBytes()))
}
