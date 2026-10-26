package com.masterofchessstrategy.games.lanzhou_mahjong

import com.masterofchessstrategy.engine.Difficulty
import com.masterofchessstrategy.engine.LanzhouMahjongAction
import com.masterofchessstrategy.engine.LanzhouMahjongActionKind
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Index of the local decision's action; the prefix ends immediately before that action. */
internal data class LanzhouMahjongDecisionPoint(val actionCount: Int, val nextChoiceSeed: Long) {
    init { require(actionCount in 0..999 && nextChoiceSeed >= 0) }
}

internal class LanzhouMahjongUndoPlan(
    nativeState: ByteArray,
    val nextChoiceSeed: Long,
    val usage: LanzhouMahjongAssistanceUsage,
    decisions: List<LanzhouMahjongDecisionPoint>,
) {
    private val ownedNative = nativeState.copyOf()
    private val ownedDecisions = decisions.toList()
    val nativeState: ByteArray get() = ownedNative.copyOf()
    val decisions: List<LanzhouMahjongDecisionPoint> get() = ownedDecisions.toList()
}

/** Used only inside the table controller's mutex. Native bytes never enter its public UI state. */
internal class LanzhouMahjongAssistanceManager(
    val difficulty: Difficulty,
    initialUsage: LanzhouMahjongAssistanceUsage = LanzhouMahjongAssistanceUsage(difficulty),
    initialDecisions: List<LanzhouMahjongDecisionPoint> = emptyList(),
    private val choiceSeed: () -> Long,
    private val restoreChoiceSeed: (Long) -> Unit,
) {
    var usage: LanzhouMahjongAssistanceUsage = initialUsage
        private set
    private var history = initialDecisions.toList()
    val decisions: List<LanzhouMahjongDecisionPoint> get() = history.toList()
    val canUndo: Boolean get() = history.isNotEmpty() &&
        usage.remaining(LanzhouMahjongAssistanceKind.UNDO) != 0

    init {
        require(initialUsage.difficulty == difficulty)
        require(history.size <= 1000)
        require(history.zipWithNext().all { (a, b) -> a.actionCount < b.actionCount })
        require(difficulty != Difficulty.MASTER || history.isEmpty())
    }

    fun beforeAction(
        nativeState: ByteArray,
        actions: List<LanzhouMahjongAction>,
        action: LanzhouMahjongAction,
    ): LanzhouMahjongDecisionPoint? {
        if (usage.remaining(LanzhouMahjongAssistanceKind.UNDO) == 0 ||
            actions.distinct().size <= 1 || action.kind == LanzhouMahjongActionKind.DRAW) return null
        val count = LanzhouMahjongNativeHistory.actionCount(nativeState) ?: error("Invalid native history")
        return LanzhouMahjongDecisionPoint(count, choiceSeed())
    }

    fun accepted(point: LanzhouMahjongDecisionPoint?) {
        if (point == null) return
        check(history.lastOrNull()?.actionCount?.let { it < point.actionCount } != false)
        history = history + point
    }

    fun useHint(): Boolean {
        val next = usage.consume(LanzhouMahjongAssistanceKind.HINT) ?: return false
        usage = next
        return true
    }

    fun undoPlan(nativeState: ByteArray): LanzhouMahjongUndoPlan? {
        if (!canUndo) return null
        val point = history.last()
        val prefix = LanzhouMahjongNativeHistory.prefix(nativeState, point.actionCount) ?: return null
        val next = usage.consume(LanzhouMahjongAssistanceKind.UNDO) ?: return null
        return LanzhouMahjongUndoPlan(prefix, point.nextChoiceSeed, next, history.dropLast(1))
    }

    fun acceptUndo(plan: LanzhouMahjongUndoPlan) {
        usage = plan.usage
        history = plan.decisions
        restoreChoiceSeed(plan.nextChoiceSeed)
    }
}

/** Structural prefix checks; session.restore still verifies every native action and its wall. */
internal object LanzhouMahjongNativeHistory {
    fun actionCount(bytes: ByteArray): Int? {
        if (bytes.size !in 130..3130) return null
        val input = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val dealer = bytes[5].toInt() and 255
        val base = input.getLong(6)
        if (dealer !in 0..3 || base !in 1..LanzhouMahjongSessionRecord.MAX_BASE_SCORE ||
            !validNativeEnvelope(bytes, dealer, base)) return null
        return input.getInt(122)
    }

    fun prefix(bytes: ByteArray, actionCount: Int): ByteArray? {
        val count = this.actionCount(bytes) ?: return null
        if (actionCount !in 0 until count) return null
        val prefix = bytes.copyOf(130 + actionCount * 3)
        val output = ByteBuffer.wrap(prefix).order(ByteOrder.LITTLE_ENDIAN)
        output.putInt(122, actionCount)
        var checksum = 0x811c9dc5.toInt()
        for (index in 0 until prefix.size - 4) {
            checksum = (checksum xor (prefix[index].toInt() and 255)) * 16777619
        }
        output.putInt(prefix.size - 4, checksum)
        return prefix
    }
}
