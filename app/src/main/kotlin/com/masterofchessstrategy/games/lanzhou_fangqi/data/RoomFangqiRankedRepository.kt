package com.masterofchessstrategy.games.lanzhou_fangqi.data

import androidx.room.withTransaction
import com.masterofchessstrategy.data.*
import com.masterofchessstrategy.engine.GameResult
import com.masterofchessstrategy.games.lanzhou_fangqi.*
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Fixed-owner transactions. Requests supply their original ID/time; retries never allocate either. */
internal class RoomFangqiRankedRepository(private val database: MocsDatabase) {
    private val gate = FangqiStorageEpochGate.forDatabase(database)
    private val owner = gate.captureOwner()
    val ownerEpoch: Long get() = owner.epoch
    val epoch get() = gate.epoch
    private val replay = NativeFangqiRankedReplay()
    private val ledger = FangqiRankedLedger(replay)
    private val validator = FangqiRankedSnapshotValidator(ledger)
    private val ranked get() = database.fangqiRankedDao()

    suspend fun load(): FangqiRankedVerifiedSnapshot = transact { read() }

    suspend fun admitNew(creation: FangqiRankedCreation,
        expectedPrevious: FangqiStoredSession? = null,
    ): FangqiStoredSession = transact {
        val snapshot = read()
        val id = creation.initial.identity.sessionId
        val existing = snapshot.creations[id]
        val initial = creation.initial.toStoredSession()
        if (existing != null) {
            compatible(existing == creation && snapshot.settlements[id] == null && same(snapshot.active, initial),
                "方棋正式创建重试与已提交局面冲突")
            return@transact requireNotNull(snapshot.active)
        }
        validateFangqiRankedReplacement(snapshot.active, expectedPrevious, snapshot.settlements.keys)
        compatible(database.matchOutcomeDao().find(id) == null && database.gameRecordDao().find(id) == null,
            "方棋正式局ID与旧成果冲突")
        val learning = FangqiLearningSnapshot(database.fangqiLearningProgressDao().readAll())
        val eligibility = FangqiAiAdmission.eligibility(FangqiLearningRead.Ready(learning),
            FangqiQualifiedWinsRead.Verified(snapshot.wins))
        ledger.admitNewCreation(creation, eligibility)
        ranked.insertStart(FangqiRankedRowMapping.encodeStartUnvalidated(creation))
        database.activeGameDao().upsert(initial.toActiveRow())
        val after = read()
        compatible(after.creations[id] == creation && same(after.active, initial), "方棋正式创建读回不一致")
        requireNotNull(after.active)
    }

    suspend fun checkpoint(before: FangqiStoredSession, after: FangqiStoredSession): FangqiStoredSession = transact {
        val snapshot = read()
        val creation = snapshot.creations[after.sessionId] ?: invalid("方棋检查点缺正式创建来源")
        compatible(snapshot.settlements[after.sessionId] == null, "方棋首次终局已固定，不能覆盖检查点")
        validateFangqiCheckpointTransition(before, after)
        if (same(snapshot.active, after)) return@transact requireNotNull(snapshot.active)
        compatible(same(snapshot.active, before), "方棋活动局已变化，检查点冲突")
        compatible(replay.replay(FangqiRankedHistory(before.engineState)).result == GameResult.ONGOING,
            "方棋首次终局已出现，请先确认结算，不能继续修改原局")
        ledger.resume(creation, after.toRankedPosition(), null)
        database.activeGameDao().upsert(after.toActiveRow())
        val stored = read().active
        compatible(same(stored, after), "方棋检查点读回不一致")
        requireNotNull(stored)
    }

    suspend fun firstTerminal(terminal: FangqiStoredSession, settledAtEpochMillis: Long): FangqiRankedTerminalReceipt = transact {
        val snapshot = read()
        val id = terminal.sessionId
        val creation = snapshot.creations[id] ?: invalid("方棋首次终局缺正式创建来源")
        val existing = snapshot.settlements[id]?.receipt
        val proposal = ledger.firstTerminal(creation, terminal.toRankedPosition(), settledAtEpochMillis, existing)
        if (proposal is FangqiRankedFirstTerminal.AlreadySettled) return@transact proposal.receipt
        // The terminal must already be the exact acknowledged activity checkpoint.
        compatible(same(snapshot.active, terminal), "方棋首次终局不是当前已确认检查点")
        val receipt = (proposal as FangqiRankedFirstTerminal.Insert).receipt
        val identity = receipt.terminal.identity
        val result = when (receipt.result) {
            GameResult.FIRST_PLAYER_WIN -> 1
            GameResult.SECOND_PLAYER_WIN -> 2
            else -> invalid("方棋正式终局结果不兼容")
        }
        compatible(database.matchOutcomeDao().find(id) == null && database.gameRecordDao().find(id) == null,
            "方棋首次成果ID已存在，原数据已保留")
        val outcome = MatchOutcomeEntity(id, 4, identity.mode.code, identity.difficulty.code, 0,
            result, receipt.settledAtEpochMillis)
        compatible(database.matchOutcomeDao().insert(outcome) != -1L, "方棋首次成果写入冲突")
        val record = GameRecordEntity(id, 4, identity.mode.code, identity.difficulty.code, result,
            identity.engineFormatVersion, receipt.terminal.history.bytes, receipt.terminal.acceptedActionCount,
            false, false, receipt.settledAtEpochMillis, 0)
        compatible(database.gameRecordDao().insert(record) != -1L, "方棋首次棋谱写入冲突")
        ranked.insertSettlement(FangqiRankedRowMapping.encodeSettlementUnvalidated(creation, receipt, null))
        val stored = read().settlements[id] ?: invalid("方棋首次终局读回缺失")
        compatible(stored.receipt == receipt && stored.recordDeletedAtEpochMillis == null,
            "方棋首次终局读回不一致")
        stored.receipt
    }

    suspend fun deleteRecord(sessionId: String, deletedAtEpochMillis: Long): Long = transact {
        val snapshot = read()
        val existing = snapshot.settlements[sessionId] ?: invalid("方棋棋谱删除缺首次终局来源")
        compatible(deletedAtEpochMillis >= existing.receipt.settledAtEpochMillis, "方棋棋谱删除早于首次结算")
        existing.recordDeletedAtEpochMillis?.let {
            compatible(it == deletedAtEpochMillis, "方棋首次删除时间不可改写")
            return@transact it
        }
        compatible(ranked.markRecordDeleted(sessionId, deletedAtEpochMillis) == 1, "方棋棋谱删除标记冲突")
        compatible(ranked.deleteCompletedRecord(sessionId) == 1, "方棋棋谱删除缺原始记录")
        val after = read().settlements[sessionId] ?: invalid("方棋删除后收据缺失")
        compatible(after.receipt == existing.receipt && after.recordDeletedAtEpochMillis == deletedAtEpochMillis,
            "方棋显式删除读回不一致")
        deletedAtEpochMillis
    }

    private suspend fun read(): FangqiRankedVerifiedSnapshot {
        currentCoroutineContext().ensureActive()
        return validator.validate(FangqiRankedStorageSnapshot(ranked.readStarts(), ranked.readSettlements(),
            database.activeGameDao().find(4), database.matchOutcomeDao().listAll(), database.gameRecordDao().listAll()))
            .also { currentCoroutineContext().ensureActive() }
    }

    private suspend fun <T> transact(operation: suspend () -> T): T = gate.access(owner) {
        withContext(Dispatchers.IO) {
            database.withTransaction {
                currentCoroutineContext().ensureActive()
                operation().also { currentCoroutineContext().ensureActive() }
            }
        }
    }
}

/** Prefix checks validate a transition proposal; the repository also replays Native and checks CAS. */
internal fun validateFangqiCheckpointTransition(before: FangqiStoredSession, after: FangqiStoredSession) {
    compatible(before.sessionId == after.sessionId && before.mode == FangqiStoredMode.HUMAN_VS_AI &&
        after.mode == before.mode && before.difficulty == after.difficulty && before.difficulty != null,
        "方棋检查点身份或难度改变")
    compatible(after.updatedAtEpochMillis >= before.updatedAtEpochMillis &&
        after.undoUseCount >= before.undoUseCount && after.hintUseCount >= before.hintUseCount,
        "方棋检查点时间或辅助计数回退")
    val prior = historyParts(before)
    val next = historyParts(after)
    compatible(prior.take(4) == next.take(4), "方棋检查点初始根改变")
    val oldActions = actions(prior[5])
    val newActions = actions(next[5])
    when {
        oldActions == newActions -> compatible(after.undoUseCount == before.undoUseCount &&
            (after.hintUseCount == before.hintUseCount ||
                after.hintUseCount.toLong() == before.hintUseCount.toLong() + 1), "方棋静态检查点辅助变更不兼容")
        newActions.size > oldActions.size -> compatible(newActions.take(oldActions.size) == oldActions &&
            after.undoUseCount == before.undoUseCount && after.hintUseCount == before.hintUseCount,
            "方棋向前检查点不是原历史扩展")
        else -> compatible(oldActions.take(newActions.size) == newActions &&
            after.undoUseCount.toLong() == before.undoUseCount.toLong() + 1 &&
            after.hintUseCount == before.hintUseCount, "方棋回退必须是一次有凭据的前缀悔棋")
    }
    val limit = when (after.difficulty) {
        com.masterofchessstrategy.engine.Difficulty.EASY -> Int.MAX_VALUE
        com.masterofchessstrategy.engine.Difficulty.MEDIUM -> 3
        com.masterofchessstrategy.engine.Difficulty.HARD -> 1
        com.masterofchessstrategy.engine.Difficulty.MASTER -> 0
        null -> invalid("方棋检查点缺难度")
    }
    compatible(after.undoUseCount <= limit && after.hintUseCount <= limit, "方棋检查点辅助超额")
}

private fun historyParts(saved: FangqiStoredSession): List<String> {
    compatible(FangqiSessionAdmission.inspectHistory(saved.engineState) == saved.acceptedActionCount,
        "方棋检查点完整历史计数不一致")
    return saved.engineState.toString(Charsets.US_ASCII).split('|', limit = 6)
}
private fun actions(value: String) = if (value.isEmpty()) emptyList() else value.split(';')
/** Starting another game may replace only an explicitly named, already settled activity. */
internal fun validateFangqiRankedReplacement(current: FangqiStoredSession?, expected: FangqiStoredSession?,
    settledIds: Set<String>,
) {
    compatible(if (expected == null) current == null else same(current, expected) && expected.sessionId in settledIds,
        "方棋新局只能从空槽或明确确认的已结算原局创建")
}
private fun same(a: FangqiStoredSession?, b: FangqiStoredSession): Boolean = a != null &&
    a.sessionId == b.sessionId && a.mode == b.mode && a.difficulty == b.difficulty &&
    a.updatedAtEpochMillis == b.updatedAtEpochMillis && a.acceptedActionCount == b.acceptedActionCount &&
    a.undoUseCount == b.undoUseCount && a.hintUseCount == b.hintUseCount && a.engineState.contentEquals(b.engineState)
private fun FangqiRankedPosition.toStoredSession() = FangqiStoredSession(identity.mode, identity.difficulty,
    history.bytes, identity.sessionId, updatedAtEpochMillis, acceptedActionCount, undoUseCount, hintUseCount)
private fun FangqiStoredSession.toActiveRow() = ActiveGameEntity(4, mode.code, difficulty?.code,
    RoomFangqiSessionStore.ENVELOPE_VERSION, 1, engineState, updatedAtEpochMillis,
    sessionId, acceptedActionCount, undoUseCount, hintUseCount)
private fun compatible(condition: Boolean, message: String) { if (!condition) invalid(message) }
private fun invalid(message: String): Nothing = throw IOException(message)
