package com.masterofchessstrategy.games.lanzhou_mahjong.settlement

import com.masterofchessstrategy.engine.LanzhouMahjongPhase
import com.masterofchessstrategy.games.lanzhou_mahjong.LanzhouMahjongSessionRecord
import java.io.IOException
import java.math.BigInteger

/** Pure evidence validation. The repository must separately prove creation and commit first-settle. */
internal class LanzhouMahjongSettlementPolicy(private val native: LanzhouMahjongSettlementReplay) {
    /** Call inside authorized new-game creation; a returned candidate is not persisted proof. */
    fun validateCreationCandidate(creation: LanzhouMahjongFormalCreation) {
        valid(creation.version == 1 && creation.createdAt >= 0, "创建凭据版本或时间无效")
        valid(creation.origin == LanzhouMahjongCreationOrigin.AUTHORIZED_STANDARD_HUMAN, "缺少正式新局来源")
        val initial = creation.initial
        val bytes = initial.nativeState
        valid(creation.sessionId.matches(Regex("lzmj-[0-9a-f]{64}")) &&
            creation.sessionId == native.sessionId(initial), "创建身份不一致")
        valid(bytes[4] == 2.toByte() && actionCount(initial) == 0, "只接受新规则的标准初态")
        valid(initial.nextChoiceSeed == initial.seed && initial.assistance.undoUsed == 0 &&
            initial.assistance.hintUsed == 0 && initial.decisions.isEmpty(), "创建初态包含历史辅助操作")
        val replayed = native.replay(initial)
        valid(bytes.contentEquals(replayed.copyInitialState()) &&
            bytes.contentEquals(replayed.copyCanonicalState()), "种子生成初态或规范回放不一致")
        valid(replayed.view.phase == LanzhouMahjongPhase.DISCARD &&
            replayed.view.dealerSeat == initial.dealerSeat &&
            replayed.view.netScores == listOf(0L, 0L, 0L, 0L), "创建不是标准未行棋状态")
    }

    /** Existing receipt must be read in the same transaction; concurrent inserts are a DB concern. */
    fun firstTerminal(
        creation: LanzhouMahjongFormalCreation?,
        terminal: LanzhouMahjongTerminalCandidate,
        existing: LanzhouMahjongTerminalReceipt? = null,
    ): LanzhouMahjongFirstSettlement {
        valid(creation != null, "缺少可信创建凭据，不能计入资格")
        val admittedCreation = checkNotNull(creation)
        validateCreationCandidate(admittedCreation)
        valid(terminal.capturedAt >= 0 && terminal.sessionId == admittedCreation.sessionId, "终局身份或时间无效")
        val initial = admittedCreation.initial
        val record = terminal.record
        valid(record.viewerSeat == initial.viewerSeat && record.difficulty == initial.difficulty &&
            record.dealerSeat == initial.dealerSeat && record.seed == initial.seed &&
            record.baseScore == initial.baseScore, "终局创建元数据不一致")
        valid(terminal.sessionId == native.sessionId(record) && actionCount(record) > 0, "终局身份或历史不一致")
        valid(initial.nativeState.copyOfRange(0, 122).contentEquals(record.nativeState.copyOfRange(0, 122)),
            "终局初始牌墙或规则身份不一致")
        val replayed = native.replay(record)
        valid(initial.nativeState.contentEquals(replayed.copyInitialState()) &&
            record.nativeState.contentEquals(replayed.copyCanonicalState()), "终局规范回放不一致")
        val view = replayed.view
        valid(view.dealerSeat == initial.dealerSeat && view.netScores.size == 4 &&
            view.netScores.fold(BigInteger.ZERO) { sum, score -> sum + BigInteger.valueOf(score) } == BigInteger.ZERO,
            "四席实际结算无效")
        val outcome = when (view.phase) {
            LanzhouMahjongPhase.EXHAUSTIVE_DRAW -> {
                valid(view.outcome.winnerSeat == null && !view.outcome.falseWin && view.netScores.all { it == 0L },
                    "黄庄结果不一致")
                LanzhouMahjongQualifiedOutcome.EXHAUSTIVE_DRAW
            }
            LanzhouMahjongPhase.WON -> {
                if (view.outcome.falseWin) {
                    valid(view.outcome.winnerSeat == null && view.outcome.payerSeat?.let { it in 0..3 } == true,
                        "炸胡结果不一致")
                    LanzhouMahjongQualifiedOutcome.FALSE_DECLARATION
                } else {
                    valid(view.outcome.winnerSeat?.let { it in 0..3 } == true, "合法和牌缺少赢家")
                    if (view.outcome.winnerSeat == record.viewerSeat) LanzhouMahjongQualifiedOutcome.WIN
                    else LanzhouMahjongQualifiedOutcome.LOSS
                }
            }
            else -> throw IOException("兰州麻将尚未终局")
        }
        val proposed = LanzhouMahjongTerminalReceipt(
            admittedCreation.sha256, terminal, record.difficulty, record.viewerSeat,
            record.nativeState[4].toInt() and 255, record.baseScore, outcome,
            view.outcome.winnerSeat, view.outcome.payerSeat, view.netScores,
        )
        if (existing == null) return LanzhouMahjongFirstSettlement.Insert(proposed)
        valid(existing.version == proposed.version && existing.creationSha256 == proposed.creationSha256 &&
            existing.terminal.sessionId == terminal.sessionId &&
            existing.terminal.copyRecordBytes().contentEquals(terminal.copyRecordBytes()) &&
            existing.difficulty == proposed.difficulty && existing.viewerSeat == proposed.viewerSeat &&
            existing.nativeRuleVersion == proposed.nativeRuleVersion && existing.baseScore == proposed.baseScore &&
            existing.outcome == proposed.outcome && existing.winnerSeat == proposed.winnerSeat &&
            existing.payerSeat == proposed.payerSeat && existing.netScores == proposed.netScores &&
            existing.terminal.capturedAt >= 0, "同一身份已有不同终局或结算凭据")
        return LanzhouMahjongFirstSettlement.AlreadySettled(existing)
    }

    private fun actionCount(record: LanzhouMahjongSessionRecord) = (record.nativeState.size - 130) / 3
    private fun valid(condition: Boolean, message: String) {
        if (!condition) throw IOException(message)
    }
}
