package com.masterofchessstrategy.games.lanzhou_fangqi

import com.masterofchessstrategy.engine.Difficulty
import com.masterofchessstrategy.engine.GameResult
import com.masterofchessstrategy.engine.GameType
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.IOException
import java.security.MessageDigest

/** Owned complete MOCS-LFQ bytes. A digest identifies bytes; it does not authenticate their origin. */
internal class FangqiRankedHistory(bytes: ByteArray) {
    private val owned = bytes.copyOf()
    val bytes: ByteArray get() = owned.copyOf()
    val sha256: String get() = sha256(owned)

    init { require(owned.size in 1..1_048_576) }

    override fun equals(other: Any?): Boolean = other is FangqiRankedHistory && owned.contentEquals(other.owned)
    override fun hashCode(): Int = owned.contentHashCode()
}

/** All identity fields must come from the same committed standard human game. */
internal data class FangqiRankedIdentity(
    val sessionId: String,
    val gameType: GameType,
    val mode: FangqiStoredMode,
    val difficulty: Difficulty,
    val humanPlayerIndex: Int,
    val rulesVersion: String,
    val engineFormatVersion: Int,
)

internal data class FangqiRankedPosition(
    val identity: FangqiRankedIdentity,
    val history: FangqiRankedHistory,
    val updatedAtEpochMillis: Long,
    val acceptedActionCount: Int,
    val undoUseCount: Int,
    val hintUseCount: Int,
)

/** Restore/import never uses the new-creation API, even if its board happens to be standard. */
internal enum class FangqiRankedStartOrigin {
    NEW_STANDARD_HUMAN_GAME, RESUME, BACKUP_IMPORT, LEGACY_RECORD, PRACTICE,
    LOCAL_TWO_PLAYER, AI_AUTO_PLAY, FREE_SETUP,
}

internal data class FangqiRankedCreation(
    val formatVersion: Int,
    val origin: FangqiRankedStartOrigin,
    val initial: FangqiRankedPosition,
) {
    /** Stable, length-prefixed binding of every creation field, independent of toString/JSON order. */
    val sha256: String get() {
        val buffer = ByteArrayOutputStream()
        DataOutputStream(buffer).use { out ->
            out.writeUTF("MOCS-FQ-RANKED-CREATION")
            out.writeInt(formatVersion)
            out.writeUTF(origin.name)
            with(initial) {
                with(identity) {
                    out.writeUTF(sessionId)
                    out.writeInt(gameType.code)
                    out.writeInt(mode.code)
                    out.writeInt(difficulty.code)
                    out.writeInt(humanPlayerIndex)
                    out.writeUTF(rulesVersion)
                    out.writeInt(engineFormatVersion)
                }
                out.writeUTF(history.sha256)
                out.writeLong(updatedAtEpochMillis)
                out.writeInt(acceptedActionCount)
                out.writeInt(undoUseCount)
                out.writeInt(hintUseCount)
            }
        }
        return sha256(buffer.toByteArray())
    }
}

/** Stored once per sessionId. Room must commit this, the record and growth in one transaction. */
internal data class FangqiRankedTerminalReceipt(
    val formatVersion: Int,
    val creationSha256: String,
    val terminal: FangqiRankedPosition,
    val result: GameResult,
    val settledAtEpochMillis: Long,
)

/**
 * Adapter contract: restore the supplied complete history in a fresh real Native session, reject
 * restore failure, then serialize and snapshot that SAME session. Never infer result from metadata.
 * No default implementation is supplied here; test doubles are not production source verification.
 */
internal fun interface FangqiRankedNativeReplay {
    fun replay(history: FangqiRankedHistory): FangqiRankedReplayResult
}

internal data class FangqiRankedReplayResult(
    val canonicalHistory: FangqiRankedHistory,
    val result: GameResult,
    val pendingCaptures: Int,
)

internal sealed interface FangqiRankedFirstTerminal {
    /** Proposal only. It becomes a receipt exclusively after the enclosing Room transaction commits. */
    data class Insert(val receipt: FangqiRankedTerminalReceipt) : FangqiRankedFirstTerminal
    data class AlreadySettled(val receipt: FangqiRankedTerminalReceipt) : FangqiRankedFirstTerminal
}

internal sealed interface FangqiRankedResume {
    data object UnrankedLegacy : FangqiRankedResume
    data class Linked(val creation: FangqiRankedCreation) : FangqiRankedResume
    data class Settled(val receipt: FangqiRankedTerminalReceipt) : FangqiRankedResume
}

/** Pure validation only: no database, Native instantiation, ID allocation, growth or navigation. */
internal class FangqiRankedLedger(private val nativeReplay: FangqiRankedNativeReplay) {
    /** Call only inside Root's gate/transaction after re-reading qualification and an empty active slot. */
    fun admitNewCreation(creation: FangqiRankedCreation, eligibility: FangqiAiEligibility): FangqiRankedCreation {
        validateCreation(creation)
        compatible(eligibility.select(creation.initial.identity.difficulty.code) is FangqiAiSelection.Eligible,
            "方棋正式新局资格不足")
        return creation
    }

    /** Validates persisted source shape and Native history; cannot prove who wrote arbitrary rows. */
    fun validateCreation(creation: FangqiRankedCreation) {
        compatible(creation.formatVersion == FORMAT_VERSION &&
            creation.origin == FangqiRankedStartOrigin.NEW_STANDARD_HUMAN_GAME, "方棋正式创建来源不兼容")
        val initial = creation.initial
        validateMetadata(initial)
        compatible(initial.history == standardInitialHistory() && initial.acceptedActionCount == 0 &&
            initial.undoUseCount == 0 && initial.hintUseCount == 0, "方棋正式创建必须是零操作标准开局")
        val replay = replay(initial)
        compatible(replay.result == GameResult.ONGOING && replay.pendingCaptures == 0,
            "方棋正式开局不是可继续的原始局面")
    }

    /** Existing save without its creation remains resumable by the normal store, but never ranked. */
    fun resume(creation: FangqiRankedCreation?, active: FangqiRankedPosition,
        receipt: FangqiRankedTerminalReceipt?,
    ): FangqiRankedResume {
        if (creation == null) {
            compatible(receipt == null, "方棋续局存在无正式创建来源的终局收据")
            return FangqiRankedResume.UnrankedLegacy
        }
        validateCreation(creation)
        validateContinuation(creation, active)
        if (receipt != null) {
            validateReceipt(creation, receipt)
            compatible(receipt.terminal == active, "方棋已结算活动局与首次终局来源冲突")
            return FangqiRankedResume.Settled(receipt)
        }
        return FangqiRankedResume.Linked(creation)
    }

    /**
     * Retry must carry the identical terminal snapshot, including assist counters. A later retry time
     * preserves the original receipt time. A changed outcome/history/identity is a conflict, not a win.
     */
    fun firstTerminal(
        creation: FangqiRankedCreation,
        terminal: FangqiRankedPosition,
        settledAtEpochMillis: Long,
        existing: FangqiRankedTerminalReceipt?,
    ): FangqiRankedFirstTerminal {
        validateCreation(creation)
        val result = validateTerminal(creation, terminal)
        compatible(settledAtEpochMillis >= terminal.updatedAtEpochMillis, "方棋结算早于终局快照")
        if (existing != null) {
            validateReceipt(creation, existing)
            compatible(existing.terminal == terminal && existing.result == result,
                "方棋首次终局已存在且来源冲突")
            return FangqiRankedFirstTerminal.AlreadySettled(existing)
        }
        return FangqiRankedFirstTerminal.Insert(FangqiRankedTerminalReceipt(
            FORMAT_VERSION, creation.sha256, terminal, result, settledAtEpochMillis,
        ))
    }

    fun validateReceipt(creation: FangqiRankedCreation, receipt: FangqiRankedTerminalReceipt) {
        validateCreation(creation)
        compatible(receipt.formatVersion == FORMAT_VERSION && receipt.creationSha256 == creation.sha256,
            "方棋终局收据版本或创建关联不兼容")
        val result = validateTerminal(creation, receipt.terminal)
        compatible(receipt.result == result && receipt.settledAtEpochMillis >= receipt.terminal.updatedAtEpochMillis,
            "方棋终局收据与真实重放或时间不一致")
    }

    /**
     * Read only the dedicated complete ledger. Missing tables/backup sections are NOT empty lists.
     * Legacy generic outcomes must never be converted into creation or receipt rows by the caller.
     */
    fun qualifiedWins(
        creations: List<FangqiRankedCreation>,
        receipts: List<FangqiRankedTerminalReceipt>,
    ): FangqiQualifiedWinFacts {
        val ownedCreations = creations.toList()
        val ownedReceipts = receipts.toList()
        val byId = ownedCreations.associateBy { it.initial.identity.sessionId }
        compatible(byId.size == ownedCreations.size, "方棋正式创建主键重复")
        compatible(ownedReceipts.map { it.terminal.identity.sessionId }.toSet().size == ownedReceipts.size,
            "方棋首次终局收据主键重复")
        ownedCreations.forEach(::validateCreation)
        val counts = Difficulty.entries.associateWith { 0 }.toMutableMap()
        for (receipt in ownedReceipts) {
            val creation = byId[receipt.terminal.identity.sessionId]
                ?: throw IOException("方棋终局收据缺少正式创建来源")
            validateReceipt(creation, receipt)
            if (receipt.result == GameResult.FIRST_PLAYER_WIN) {
                val difficulty = creation.initial.identity.difficulty
                counts[difficulty] = Math.addExact(counts.getValue(difficulty), 1)
            }
        }
        return FangqiQualifiedWinFacts.fromVerifiedLedgerCounts(GameType.LANZHOU_SQUARE_CHESS, counts)
    }

    private fun validateTerminal(creation: FangqiRankedCreation, terminal: FangqiRankedPosition): GameResult {
        val replay = validateContinuation(creation, terminal)
        compatible(terminal.acceptedActionCount > 0 && replay.pendingCaptures == 0 &&
            replay.result in listOf(GameResult.FIRST_PLAYER_WIN, GameResult.SECOND_PLAYER_WIN),
            "方棋正式终局必须来自完整原生胜负，不接受未结束、提和或覆盖结果")
        return replay.result
    }

    private fun validateContinuation(creation: FangqiRankedCreation, position: FangqiRankedPosition): FangqiRankedReplayResult {
        validateMetadata(position)
        compatible(position.identity == creation.initial.identity &&
            position.updatedAtEpochMillis >= creation.initial.updatedAtEpochMillis, "方棋来源身份或时间不一致")
        return replay(position)
    }

    private fun validateMetadata(position: FangqiRankedPosition) {
        with(position.identity) {
            compatible(sessionId.isNotBlank() && sessionId.length <= 64 &&
                sessionId.all { it.code in 33..126 } && gameType == GameType.LANZHOU_SQUARE_CHESS &&
                mode == FangqiStoredMode.HUMAN_VS_AI && humanPlayerIndex == 0 &&
                rulesVersion == RULES_VERSION && engineFormatVersion == ENGINE_FORMAT_VERSION,
                "方棋正式局身份不兼容")
        }
        compatible(position.updatedAtEpochMillis >= 0 && position.acceptedActionCount in 0..16_384 &&
            position.undoUseCount >= 0 && position.hintUseCount >= 0, "方棋正式局计数或时间无效")
        val limit = when (position.identity.difficulty) {
            Difficulty.EASY -> Int.MAX_VALUE
            Difficulty.MEDIUM -> 3
            Difficulty.HARD -> 1
            Difficulty.MASTER -> 0
        }
        compatible(position.undoUseCount <= limit && position.hintUseCount <= limit, "方棋辅助次数超出本档规则")
    }

    private fun replay(position: FangqiRankedPosition): FangqiRankedReplayResult {
        // Envelope checks bound input and anchor the original board; only Native validates legal history.
        val bytes = position.history.bytes
        compatible(bytes.all { it.toInt() in 32..126 }, "方棋历史编码无效")
        val parts = bytes.toString(Charsets.US_ASCII).split('|', limit = 6)
        compatible(parts.size == 6 && parts.take(4) == standardParts, "方棋历史不是正式标准开局来源")
        val count = parts[4].toIntOrNull()
        compatible(count != null && count in 0..16_384 && count.toString() == parts[4] &&
            count == position.acceptedActionCount, "方棋历史动作计数不一致")
        val actions = parts[5]
        compatible((if (actions.isEmpty()) 0 else actions.count { it == ';' } + 1) == count &&
            !actions.startsWith(';') && !actions.endsWith(';') && ";;" !in actions,
            "方棋历史动作段不一致")
        val result = nativeReplay.replay(position.history)
        compatible(result.canonicalHistory == position.history && result.pendingCaptures in 0..14,
            "方棋真实重放未返回相同规范历史")
        return result
    }

    companion object {
        const val FORMAT_VERSION = 1
        const val ENGINE_FORMAT_VERSION = 1
        const val RULES_VERSION = "lfq-2026-09-30-v1"
        private val standardParts = listOf("MOCS-LFQ", "1", "1", "2".repeat(14) + "0".repeat(21) + "1".repeat(14))
        fun standardInitialHistory() = FangqiRankedHistory((standardParts.joinToString("|") + "|0|").toByteArray(Charsets.US_ASCII))
    }
}

private fun compatible(condition: Boolean, message: String) {
    if (!condition) throw IOException(message)
}

private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
    .joinToString("") { "%02x".format(it.toInt() and 0xff) }
