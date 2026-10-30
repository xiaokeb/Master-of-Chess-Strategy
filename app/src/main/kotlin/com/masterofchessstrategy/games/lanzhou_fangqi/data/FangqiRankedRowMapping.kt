package com.masterofchessstrategy.games.lanzhou_fangqi.data

import com.masterofchessstrategy.data.MatchOutcomeEntity
import com.masterofchessstrategy.engine.Difficulty
import com.masterofchessstrategy.engine.GameResult
import com.masterofchessstrategy.engine.GameType
import com.masterofchessstrategy.games.lanzhou_fangqi.FangqiRankedCreation
import com.masterofchessstrategy.games.lanzhou_fangqi.FangqiRankedHistory
import com.masterofchessstrategy.games.lanzhou_fangqi.FangqiRankedIdentity
import com.masterofchessstrategy.games.lanzhou_fangqi.FangqiRankedLedger
import com.masterofchessstrategy.games.lanzhou_fangqi.FangqiRankedPosition
import com.masterofchessstrategy.games.lanzhou_fangqi.FangqiRankedStartOrigin
import com.masterofchessstrategy.games.lanzhou_fangqi.FangqiRankedTerminalReceipt
import com.masterofchessstrategy.games.lanzhou_fangqi.FangqiStoredMode
import java.io.IOException

/** Orthogonal record lifecycle metadata. Receipt bytes/result/time are never rewritten on deletion. */
internal data class FangqiRankedUnvalidatedSettlement(
    val receipt: FangqiRankedTerminalReceipt,
    val recordDeletedAtEpochMillis: Long?,
) {
    /** Root must also verify the complete matching record, or its absence after explicit deletion. */
    val requiresCompletedRecord: Boolean get() = recordDeletedAtEpochMillis == null
}

/**
 * Strict structural mapping only. Every method is explicitly Unvalidated: Root must next use the
 * real-Native Ledger and verify trusted transactional provenance plus record/growth companions.
 */
internal object FangqiRankedRowMapping {
    fun encodeStartUnvalidated(creation: FangqiRankedCreation): FangqiRankedStartEntity {
        checkCreationShape(creation)
        val initial = creation.initial
        val id = initial.identity
        return FangqiRankedStartEntity(id.sessionId, creation.formatVersion, creation.origin.name,
            id.gameType.code, id.mode.code, id.difficulty.code, id.humanPlayerIndex, id.rulesVersion,
            id.engineFormatVersion, own(initial.history), initial.updatedAtEpochMillis,
            initial.acceptedActionCount, initial.undoUseCount, initial.hintUseCount, creation.sha256)
    }

    fun decodeStartUnvalidated(row: FangqiRankedStartEntity): FangqiRankedCreation {
        compatible(row.startOrigin == FangqiRankedStartOrigin.NEW_STANDARD_HUMAN_GAME.name,
            "方棋正式创建行来源不兼容")
        val position = FangqiRankedPosition(identity(row.sessionId, row.gameTypeCode, row.modeCode,
            row.difficultyCode, row.humanPlayerIndex, row.rulesVersion, row.engineFormatVersion),
            own(row.initialHistory), row.startedAtEpochMillis, row.acceptedActionCount, row.undoUseCount, row.hintUseCount)
        val creation = FangqiRankedCreation(row.formatVersion, FangqiRankedStartOrigin.NEW_STANDARD_HUMAN_GAME, position)
        checkCreationShape(creation)
        compatible(row.creationSha256 == creation.sha256, "方棋正式创建行摘要不一致")
        return creation
    }

    fun encodeSettlementUnvalidated(
        creation: FangqiRankedCreation,
        receipt: FangqiRankedTerminalReceipt,
        recordDeletedAtEpochMillis: Long?,
    ): FangqiRankedSettlementEntity {
        checkReceiptShape(creation, receipt, recordDeletedAtEpochMillis)
        val terminal = receipt.terminal
        val id = terminal.identity
        return FangqiRankedSettlementEntity(id.sessionId, receipt.formatVersion, receipt.creationSha256,
            id.gameType.code, id.mode.code, id.difficulty.code, id.humanPlayerIndex, id.rulesVersion,
            id.engineFormatVersion, own(terminal.history), terminal.updatedAtEpochMillis,
            terminal.acceptedActionCount, terminal.undoUseCount, terminal.hintUseCount,
            resultCode(receipt.result), receipt.settledAtEpochMillis, recordDeletedAtEpochMillis)
    }

    /** Both raw FK parents must be supplied; matching IDs alone do not validate outcome metadata. */
    fun decodeSettlementUnvalidated(
        row: FangqiRankedSettlementEntity,
        startRow: FangqiRankedStartEntity?,
        outcomeRow: MatchOutcomeEntity?,
    ): FangqiRankedUnvalidatedSettlement {
        val creation = decodeStartUnvalidated(startRow ?: throw IOException("方棋正式终局行缺创建父行"))
        val terminal = FangqiRankedPosition(identity(row.sessionId, row.gameTypeCode, row.modeCode,
            row.difficultyCode, row.humanPlayerIndex, row.rulesVersion, row.engineFormatVersion),
            own(row.terminalHistory), row.updatedAtEpochMillis, row.acceptedActionCount, row.undoUseCount, row.hintUseCount)
        val result = when (row.resultCode) {
            1 -> GameResult.FIRST_PLAYER_WIN
            2 -> GameResult.SECOND_PLAYER_WIN
            else -> throw IOException("方棋正式终局行不支持此结果")
        }
        val receipt = FangqiRankedTerminalReceipt(row.formatVersion, row.creationSha256, terminal, result, row.settledAtEpochMillis)
        checkReceiptShape(creation, receipt, row.recordDeletedAtEpochMillis)
        val outcome = outcomeRow ?: throw IOException("方棋正式终局行缺成长父行")
        with(terminal.identity) {
            compatible(outcome.matchId == sessionId && outcome.gameTypeCode == gameType.code &&
                outcome.modeCode == mode.code && outcome.difficultyCode == difficulty.code &&
                outcome.playerIndex == humanPlayerIndex && outcome.resultCode == row.resultCode &&
                outcome.settledAtEpochMillis == receipt.settledAtEpochMillis, "方棋正式终局行与成长父行不一致")
        }
        return FangqiRankedUnvalidatedSettlement(receipt, row.recordDeletedAtEpochMillis)
    }

    private fun checkCreationShape(creation: FangqiRankedCreation) {
        compatible(creation.formatVersion == FangqiRankedLedger.FORMAT_VERSION &&
            creation.origin == FangqiRankedStartOrigin.NEW_STANDARD_HUMAN_GAME, "方棋正式创建行版本或来源不兼容")
        val initial = creation.initial
        checkPositionShape(initial)
        compatible(initial.history == FangqiRankedLedger.standardInitialHistory() &&
            initial.acceptedActionCount == 0 && initial.undoUseCount == 0 && initial.hintUseCount == 0,
            "方棋正式创建行不是零操作标准开局")
    }

    private fun checkReceiptShape(creation: FangqiRankedCreation, receipt: FangqiRankedTerminalReceipt, deletedAt: Long?) {
        checkCreationShape(creation)
        val terminal = receipt.terminal
        checkPositionShape(terminal)
        compatible(receipt.formatVersion == FangqiRankedLedger.FORMAT_VERSION && receipt.creationSha256 == creation.sha256 &&
            terminal.identity == creation.initial.identity && terminal.acceptedActionCount > 0 &&
            terminal.updatedAtEpochMillis >= creation.initial.updatedAtEpochMillis &&
            receipt.settledAtEpochMillis >= terminal.updatedAtEpochMillis, "方棋正式终局行创建关联或时钟不一致")
        resultCode(receipt.result)
        compatible(deletedAt == null || deletedAt >= receipt.settledAtEpochMillis,
            "方棋棋谱显式删除时间早于首次结算")
    }

    private fun identity(sessionId: String, game: Int, mode: Int, difficulty: Int, human: Int,
        rules: String, engineFormat: Int,
    ): FangqiRankedIdentity {
        compatible(game == GameType.LANZHOU_SQUARE_CHESS.code && mode == FangqiStoredMode.HUMAN_VS_AI.code,
            "方棋正式行棋种或模式不兼容")
        val tier = Difficulty.entries.singleOrNull { it.code == difficulty }
            ?: throw IOException("方棋正式行难度不兼容")
        return FangqiRankedIdentity(sessionId, GameType.LANZHOU_SQUARE_CHESS, FangqiStoredMode.HUMAN_VS_AI,
            tier, human, rules, engineFormat)
    }

    private fun checkPositionShape(position: FangqiRankedPosition) {
        with(position.identity) {
            compatible(sessionId.isNotBlank() && sessionId.length <= 64 && sessionId.all { it.code in 33..126 } &&
                gameType == GameType.LANZHOU_SQUARE_CHESS && mode == FangqiStoredMode.HUMAN_VS_AI &&
                humanPlayerIndex == 0 && rulesVersion == FangqiRankedLedger.RULES_VERSION &&
                engineFormatVersion == FangqiRankedLedger.ENGINE_FORMAT_VERSION, "方棋正式行身份不兼容")
        }
        val limit = when (position.identity.difficulty) {
            Difficulty.EASY -> Int.MAX_VALUE
            Difficulty.MEDIUM -> 3
            Difficulty.HARD -> 1
            Difficulty.MASTER -> 0
        }
        compatible(position.updatedAtEpochMillis >= 0 && position.acceptedActionCount in 0..16_384 &&
            position.undoUseCount in 0..limit && position.hintUseCount in 0..limit, "方棋正式行时钟或辅助次数不兼容")
        val bytes = position.history.bytes
        compatible(bytes.all { it.toInt() in 32..126 }, "方棋正式行历史编码无效")
        val parts = bytes.toString(Charsets.US_ASCII).split('|', limit = 6)
        val expectedRoot = FangqiRankedLedger.standardInitialHistory().bytes.toString(Charsets.US_ASCII).split('|').take(4)
        compatible(parts.size == 6 && parts.take(4) == expectedRoot, "方棋正式行历史不是标准开局来源")
        val count = parts[4].toIntOrNull()
        compatible(count == position.acceptedActionCount && count.toString() == parts[4], "方棋正式行历史动作数不一致")
        val actions = parts[5]
        compatible((if (actions.isEmpty()) 0 else actions.count { it == ';' } + 1) == count &&
            !actions.startsWith(';') && !actions.endsWith(';') && ";;" !in actions, "方棋正式行历史动作段不一致")
        // Action grammar, legality, pending captures and actual result still require real Native Ledger replay.
    }

    private fun resultCode(result: GameResult): Int = when (result) {
        GameResult.FIRST_PLAYER_WIN -> 1
        GameResult.SECOND_PLAYER_WIN -> 2
        else -> throw IOException("方棋正式终局行只支持真实红胜或黑胜")
    }

    private fun own(history: FangqiRankedHistory) = FangqiRankedHistory(history.bytes)
    private fun compatible(condition: Boolean, message: String) {
        if (!condition) throw IOException(message)
    }
}
