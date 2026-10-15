package com.masterofchessstrategy.games.tiger_goat

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class TigerGoatActionKind(val code: Int) {
    REMOVE_GOAT(0),
    PLACE_GOAT(1),
    STEP(2),
    CAPTURE(3),
    END_CHAIN(4),
    RESIGN(5),
    PASS_GOAT(6),
}

data class TigerGoatAction(
    val kind: TigerGoatActionKind,
    val from: Int = -1,
    val to: Int = -1,
)

data class TigerGoatSnapshot(
    val pieces: List<Int>,
    val phase: TigerGoatPhase,
    val toMove: Int,
    val reserve: Int,
    val captured: Int,
    val winner: TigerGoatWinner,
    val chainTiger: Int = -1,
    val chainVisited: Set<Int> = emptySet(),
)

interface TigerGoatRulesSession : AutoCloseable {
    val spec: TigerGoatBoardSpec
    val actionCount: Int
    fun snapshot(): TigerGoatSnapshot
    fun legalActions(): List<TigerGoatAction>
    /** Returns zero on success; other values are stable rule error codes. */
    fun apply(action: TigerGoatAction): Int
    fun undo(): Boolean
    fun reset()
    fun serialize(): ByteArray
    fun restore(bytes: ByteArray): Boolean
}

class TigerGoatGameController(private val session: TigerGoatRulesSession) : AutoCloseable {
    private var selected: Int? = null
    private val mutableUiState = MutableStateFlow(displayState())
    val uiState: StateFlow<TigerGoatDisplayState> = mutableUiState
    val boardSpec: TigerGoatBoardSpec get() = session.spec

    fun onPointTap(point: Int) {
        val snapshot = session.snapshot()
        if (point !in session.spec.points.indices || snapshot.winner != TigerGoatWinner.NONE) {
            return
        }
        val legal = session.legalActions()
        val directAction = when {
            snapshot.phase == TigerGoatPhase.OPENING_REMOVAL ->
                legal.firstOrNull { it.kind == TigerGoatActionKind.REMOVE_GOAT && it.from == point }
            snapshot.toMove == 2 && snapshot.reserve > 0 ->
                legal.firstOrNull { it.kind == TigerGoatActionKind.PLACE_GOAT && it.to == point }
            snapshot.phase == TigerGoatPhase.CAPTURE_CHAIN ->
                legal.firstOrNull { it.kind == TigerGoatActionKind.CAPTURE && it.to == point }
            else -> null
        }
        if (directAction != null) {
            apply(directAction)
            return
        }
        if (snapshot.phase == TigerGoatPhase.OPENING_REMOVAL ||
            snapshot.phase == TigerGoatPhase.CAPTURE_CHAIN ||
            (snapshot.toMove == 2 && snapshot.reserve > 0)
        ) {
            publish("此处当前不能操作")
            return
        }
        if (snapshot.pieces[point] == snapshot.toMove) {
            selected = point
            publish()
            return
        }
        val from = selected
        val move = legal.firstOrNull {
            it.from == from && it.to == point &&
                (it.kind == TigerGoatActionKind.STEP || it.kind == TigerGoatActionKind.CAPTURE)
        }
        if (move == null) {
            publish("请选择已标记的合法落点")
            return
        }
        apply(move)
    }

    fun endChain() {
        session.legalActions().firstOrNull { it.kind == TigerGoatActionKind.END_CHAIN }
            ?.let(::apply)
    }

    fun passGoat() {
        val snapshot = session.snapshot()
        val legal = session.legalActions()
        if (canPassGoat(snapshot, legal)) apply(legal.single())
    }

    fun undo() {
        if (session.undo()) {
            selected = null
            publish()
        }
    }

    fun restart() {
        session.reset()
        selected = null
        publish()
    }

    fun resign() = apply(TigerGoatAction(TigerGoatActionKind.RESIGN))

    fun restore(bytes: ByteArray): Boolean {
        if (!session.restore(bytes.copyOf())) {
            publish("存档损坏或版本不兼容")
            return false
        }
        selected = null
        publish()
        return true
    }

    fun serialize(): ByteArray = session.serialize().copyOf()

    override fun close() = session.close()

    private fun apply(action: TigerGoatAction) {
        val error = session.apply(action)
        if (error == 0) {
            selected = null
            publish()
        } else {
            publish(when (error) {
                1 -> "对局已经结束"
                2 -> "请按当前阶段操作"
                3 -> "请选择棋盘上的落点"
                4 -> "请选择当前一方的棋子"
                5 -> "该落点已有棋子"
                6 -> "请沿棋盘连线走步或跳吃"
                7 -> "老虎不能斜向移动"
                8 -> "跳吃须越过一只羊"
                9 -> "本次连跳不能返回已到过的落点"
                10 -> "本局已达到可保存的步数上限"
                else -> "操作失败，请重试"
            })
        }
    }

    private fun publish(feedback: String? = null) {
        mutableUiState.value = displayState(feedback)
    }

    private fun displayState(feedback: String? = null): TigerGoatDisplayState {
        val snapshot = session.snapshot()
        val legal = session.legalActions()
        val targets = when {
            snapshot.phase == TigerGoatPhase.OPENING_REMOVAL ->
                legal.filter { it.kind == TigerGoatActionKind.REMOVE_GOAT }.map { it.from }.toSet()
            snapshot.phase == TigerGoatPhase.CAPTURE_CHAIN ->
                legal.filter { it.kind == TigerGoatActionKind.CAPTURE }.map { it.to }.toSet()
            snapshot.toMove == 2 && snapshot.reserve > 0 ->
                legal.filter { it.kind == TigerGoatActionKind.PLACE_GOAT }.map { it.to }.toSet()
            selected != null ->
                legal.filter { it.from == selected }.map { it.to }.toSet()
            else -> emptySet()
        }
        return TigerGoatDisplayState(
            pieces = snapshot.pieces,
            phase = snapshot.phase,
            toMove = snapshot.toMove,
            reserve = snapshot.reserve,
            captured = snapshot.captured,
            winner = snapshot.winner,
            selectedPoint = selected ?: snapshot.chainTiger.takeIf { it >= 0 },
            legalDestinations = targets,
            chainVisited = snapshot.chainVisited,
            canUndo = session.actionCount > 0,
            canPassGoat = canPassGoat(snapshot, legal),
            feedback = feedback,
        )
    }

    private fun canPassGoat(snapshot: TigerGoatSnapshot, legal: List<TigerGoatAction>) =
        snapshot.phase == TigerGoatPhase.PLAY && snapshot.toMove == 2 &&
            snapshot.reserve == 0 && snapshot.winner == TigerGoatWinner.NONE &&
            legal.singleOrNull() == TigerGoatAction(TigerGoatActionKind.PASS_GOAT)
}
