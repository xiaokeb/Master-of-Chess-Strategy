package com.masterofchessstrategy.games.lanzhou_fangqi.data

import com.masterofchessstrategy.data.*
import com.masterofchessstrategy.engine.Difficulty
import com.masterofchessstrategy.games.lanzhou_fangqi.*
import java.io.IOException
import java.util.Collections

/** Read all five families in one surrounding transaction; missing data is never synthesized. */
internal data class FangqiRankedStorageSnapshot(
    val starts: List<FangqiRankedStartEntity>,
    val settlements: List<FangqiRankedSettlementEntity>,
    val active: ActiveGameEntity?,
    val outcomes: List<MatchOutcomeEntity>,
    val records: List<GameRecordEntity>,
)

internal data class FangqiRankedVerifiedSnapshot(
    val creations: Map<String, FangqiRankedCreation>,
    val settlements: Map<String, FangqiRankedUnvalidatedSettlement>,
    val active: FangqiStoredSession?,
    val activeLink: FangqiRankedResume?,
    val wins: FangqiQualifiedWinFacts,
)

/** Must run off main with production Native admission; hashes and foreign keys alone grant nothing. */
internal class FangqiRankedSnapshotValidator(
    private val ledger: FangqiRankedLedger = FangqiRankedLedger(NativeFangqiRankedReplay()),
    private val activeAdmission: FangqiSessionAdmission = FangqiSessionAdmission(),
) {
    fun validate(snapshot: FangqiRankedStorageSnapshot): FangqiRankedVerifiedSnapshot {
        val starts = unique(snapshot.starts.toList()) { it.sessionId }
        val settlements = unique(snapshot.settlements.toList()) { it.sessionId }
        val outcomes = unique(snapshot.outcomes.toList()) { it.matchId }
        val records = unique(snapshot.records.map { it.copy(engineState = it.engineState.copyOf()) }) { it.recordId }
        val creations = starts.mapValues { (_, row) ->
            FangqiRankedRowMapping.decodeStartUnvalidated(row).also(ledger::validateCreation)
        }
        val verified = settlements.mapValues { (id, row) ->
            val value = FangqiRankedRowMapping.decodeSettlementUnvalidated(row, starts[id], outcomes[id])
            ledger.validateReceipt(creations[id] ?: invalid("方棋终局缺创建来源"), value.receipt)
            val record = records[id]
            if (value.requiresCompletedRecord) {
                matchRecord(record ?: invalid("方棋首次终局缺配套棋谱"), value.receipt)
            } else if (record != null) invalid("方棋已删除棋谱仍存在，原数据已保留")
            value
        }
        for (id in starts.keys) {
            if (id !in verified && (id in outcomes || id in records)) invalid("方棋成果缺首次终局收据")
        }
        // Generic legacy facts remain stored, but never enter the dedicated wins computation.
        val active = snapshot.active?.let { decodeActive(it.copy(engineState = it.engineState.copyOf())) }
        val link = active?.let { current ->
            val creation = creations[current.sessionId]
            if (creation == null) FangqiRankedResume.UnrankedLegacy
            else ledger.resume(creation, current.toRankedPosition(), verified[current.sessionId]?.receipt)
        }
        val wins = ledger.qualifiedWins(creations.values.toList(), verified.values.map { it.receipt })
        return FangqiRankedVerifiedSnapshot(Collections.unmodifiableMap(creations.toMap()),
            Collections.unmodifiableMap(verified.toMap()), active, link, wins)
    }

    private fun matchRecord(row: GameRecordEntity, receipt: FangqiRankedTerminalReceipt) {
        val terminal = receipt.terminal
        val id = terminal.identity
        val result = when (receipt.result) {
            com.masterofchessstrategy.engine.GameResult.FIRST_PLAYER_WIN -> 1
            com.masterofchessstrategy.engine.GameResult.SECOND_PLAYER_WIN -> 2
            else -> invalid("方棋正式棋谱结果无效")
        }
        if (row.recordId != id.sessionId || row.gameTypeCode != id.gameType.code || row.modeCode != id.mode.code ||
            row.difficultyCode != id.difficulty.code || row.playerIndex != id.humanPlayerIndex ||
            row.resultCode != result || row.engineFormatVersion != id.engineFormatVersion || row.isEndgame ||
            row.moveCount != terminal.acceptedActionCount || row.completedAtEpochMillis != receipt.settledAtEpochMillis ||
            !row.engineState.contentEquals(terminal.history.bytes)) invalid("方棋棋谱与首次终局来源不一致")
        // isFavorite is a mutable user preference, not immutable match provenance.
    }

    private fun decodeActive(row: ActiveGameEntity): FangqiStoredSession {
        if (row.gameTypeCode != 4 || row.envelopeVersion != RoomFangqiSessionStore.ENVELOPE_VERSION ||
            row.engineFormatVersion != 1 || row.resultOverrideCode != null || row.timeControlMinutes != null ||
            row.redRemainingMillis != null || row.blackRemainingMillis != null || row.turnStartedAtEpochMillis != null ||
            row.pendingDrawOfferSideCode != null || row.autoPlayPaused || row.autoPlaySpeedPermille != 1000 ||
            row.completedAutoGames != 0 || row.sessionVariantId.isNotEmpty() || row.playerIndex != 0) {
            invalid("方棋活动局字段不兼容")
        }
        val mode = FangqiStoredMode.entries.singleOrNull { it.code == row.modeCode } ?: invalid("方棋活动局模式无效")
        val difficulty = row.difficultyCode?.let { code ->
            Difficulty.entries.singleOrNull { it.code == code } ?: invalid("方棋活动局难度无效")
        }
        val stored = try { FangqiStoredSession(mode, difficulty, row.engineState, row.sessionId,
            row.updatedAtEpochMillis, row.acceptedMoveCount, row.undoUseCount, row.hintUseCount) }
        catch (failure: IllegalArgumentException) { throw IOException("方棋活动局元数据无效", failure) }
        if (FangqiSessionAdmission.inspectHistory(stored.engineState) != stored.acceptedActionCount) {
            invalid("方棋活动局完整历史计数不一致")
        }
        return activeAdmission.admit(stored)
    }

    private fun <T> unique(rows: List<T>, key: (T) -> String): Map<String, T> {
        val map = rows.associateBy(key)
        if (map.size != rows.size) invalid("方棋来源快照包含重复主键")
        return map
    }

    private fun invalid(message: String): Nothing = throw IOException(message)
}

internal fun FangqiStoredSession.toRankedPosition(): FangqiRankedPosition = FangqiRankedPosition(
    FangqiRankedIdentity(sessionId, com.masterofchessstrategy.engine.GameType.LANZHOU_SQUARE_CHESS,
        mode, difficulty ?: throw IOException("方棋正式活动局缺难度"), 0, FangqiRankedLedger.RULES_VERSION, 1),
    FangqiRankedHistory(engineState), updatedAtEpochMillis, acceptedActionCount, undoUseCount, hintUseCount,
)
