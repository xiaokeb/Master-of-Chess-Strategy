package com.masterofchessstrategy.games.lanzhou_fangqi

import com.masterofchessstrategy.engine.Difficulty
import com.masterofchessstrategy.engine.GameResult
import com.masterofchessstrategy.engine.games.lanzhou_fangqi.FangqiAction
import com.masterofchessstrategy.engine.games.lanzhou_fangqi.FangqiPoint
import com.masterofchessstrategy.engine.games.lanzhou_fangqi.FangqiRejection
import com.masterofchessstrategy.engine.games.lanzhou_fangqi.FangqiSession
import com.masterofchessstrategy.engine.games.lanzhou_fangqi.FangqiSide
import com.masterofchessstrategy.engine.games.lanzhou_fangqi.FangqiSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

internal enum class FangqiFeedback {
    NONE, SELECT_OWN, SELECT_CAPTURE, GAME_OVER, AI_THINKING, ILLEGAL, REPEATED, HISTORY_LIMIT,
}

internal data class FangqiUiState(
    val snapshot: FangqiSnapshot,
    val selected: FangqiPoint? = null,
    val legalPoints: Set<FangqiPoint> = emptySet(),
    val feedback: FangqiFeedback = FangqiFeedback.NONE,
    val aiThinking: Boolean = false,
    val savePending: Boolean = false,
    val saveFailed: Boolean = false,
)

/** UI actions are routed through the native session; the board is never edited here. */
internal class LanzhouFangqiInteraction(
    private val session: FangqiSession,
    private val humanSide: FangqiSide? = null,
    private val aiDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val onPositionChanged: ((FangqiSnapshot, ByteArray, (Boolean) -> Unit) -> Unit)? = null,
    private val automatic: Boolean = false,
    private val undoAllowed: () -> Boolean = { true },
    private val onUndoAccepted: (() -> Unit)? = null,
    private val onRestartAccepted: (() -> Unit)? = null,
    private val restartAllowed: () -> Boolean = { true },
) {
    private var retired = false
    private var pendingCheckpoint: Pair<FangqiSnapshot, ByteArray>? = null
    private var checkpointAttempt = 0L
    private val mutableState = MutableStateFlow(FangqiUiState(session.snapshot()))
    val state = mutableState.asStateFlow()

    init {
        refresh()
    }

    fun tap(point: FangqiPoint) {
        if (retired || automatic) return
        val current = mutableState.value
        if (current.savePending || current.saveFailed) return
        if (current.aiThinking) return report(FangqiFeedback.AI_THINKING)
        if (current.snapshot.result != GameResult.ONGOING) return report(FangqiFeedback.GAME_OVER)
        if (humanSide != null && current.snapshot.currentPlayer != humanSide) {
            return report(FangqiFeedback.AI_THINKING)
        }
        if (current.snapshot.pendingCaptures > 0) {
            if (point !in current.legalPoints) return report(FangqiFeedback.SELECT_CAPTURE)
            apply(FangqiAction.Capture(point))
            return
        }
        val ownPiece = current.snapshot.board[point.index] == current.snapshot.currentPlayer.pieceCode
        if (ownPiece) {
            val selected = if (current.selected == point) null else point
            refresh(selected)
            return
        }
        val selected = current.selected ?: return report(FangqiFeedback.SELECT_OWN)
        apply(FangqiAction.Move(selected, point))
    }

    fun undo() {
        if (retired || !undoAllowed()) return
        if (mutableState.value.let { it.aiThinking || it.savePending || it.saveFailed } || !session.undo()) return
        val restored = session.snapshot()
        if (humanSide != null && restored.currentPlayer != humanSide && restored.canUndo) {
            session.undo()
        }
        beginCheckpoint()
        onUndoAccepted?.invoke()
        refresh()
        notifyPositionChanged()
    }

    fun reset() {
        if (retired || !restartAllowed()) return
        if (mutableState.value.let { it.aiThinking || it.savePending || it.saveFailed }) return
        session.reset()
        beginCheckpoint()
        onRestartAccepted?.invoke()
        refresh()
        notifyPositionChanged()
    }

    suspend fun playBlackAi(difficulty: Difficulty) = playAiTurn(difficulty, FangqiSide.BLACK)

    /** The owner launches and joins this job; a retired page must not apply late search results. */
    suspend fun playAiTurn(difficulty: Difficulty, side: FangqiSide) {
        val before = mutableState.value
        if (retired || before.aiThinking || before.savePending || before.saveFailed || before.snapshot.result != GameResult.ONGOING
            || before.snapshot.currentPlayer != side
        ) return
        mutableState.value = before.copy(aiThinking = true, selected = null, legalPoints = emptySet())
        try {
            while (!retired && session.snapshot().let {
                    it.currentPlayer == side && it.result == GameResult.ONGOING
                }
            ) {
                val action = withContext(aiDispatcher) { session.chooseAction(difficulty) }
                if (retired) return
                if (action == null) {
                    refresh(feedback = FangqiFeedback.ILLEGAL)
                    break
                }
                val result = session.apply(action)
                refresh(feedback = if (result.accepted) FangqiFeedback.NONE else result.rejection.toFeedback())
                if (!result.accepted) break
                notifyPositionChanged()
                val saved = state.first { !it.savePending }
                if (saved.saveFailed) break
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            refresh(feedback = FangqiFeedback.ILLEGAL)
        } finally {
            mutableState.value = mutableState.value.copy(aiThinking = false)
        }
    }

    private fun apply(action: FangqiAction) {
        val result = session.apply(action)
        if (result.accepted) beginCheckpoint()
        refresh(
            selected = if (result.accepted) null else mutableState.value.selected,
            feedback = if (result.accepted) FangqiFeedback.NONE else result.rejection.toFeedback(),
        )
        if (result.accepted) notifyPositionChanged()
    }

    /** Publish the write fence before exposing the next turn. */
    private fun beginCheckpoint() {
        // Main.immediate observers may run as soon as refresh publishes the new side.
        if (onPositionChanged != null) mutableState.value = mutableState.value.copy(savePending = true, saveFailed = false)
    }

    /** Synchronous export preserves every accepted capture state before another action can run. */
    private fun notifyPositionChanged() {
        if (onPositionChanged == null) return
        mutableState.value = mutableState.value.copy(savePending = true, saveFailed = false)
        try {
            pendingCheckpoint = session.snapshot() to session.serialize()
            submitCheckpoint()
        } catch (failure: Exception) {
            mutableState.value = mutableState.value.copy(savePending = false, saveFailed = true)
            if (failure is CancellationException) throw failure
        }
    }

    fun retrySave() {
        if (retired) return
        if (mutableState.value.saveFailed && !mutableState.value.savePending) {
            if (pendingCheckpoint == null) notifyPositionChanged() else submitCheckpoint()
        }
    }

    private fun submitCheckpoint() {
        val checkpoint = pendingCheckpoint ?: return
        val attempt = ++checkpointAttempt
        mutableState.value = mutableState.value.copy(savePending = true, saveFailed = false)
        try {
            onPositionChanged?.invoke(checkpoint.first, checkpoint.second.copyOf()) { saved ->
                // Ignore callbacks for a checkpoint superseded by a newer accepted action.
                if (!retired && pendingCheckpoint === checkpoint && checkpointAttempt == attempt) {
                    if (saved) pendingCheckpoint = null
                    mutableState.value = mutableState.value.copy(savePending = false, saveFailed = !saved)
                }
            }
        } catch (failure: Exception) {
            mutableState.value = mutableState.value.copy(savePending = false, saveFailed = true)
            if (failure is CancellationException) throw failure
        }
    }

    /** Main-thread fence; resource closing is deliberately left to the asynchronous owner. */
    fun seal() {
        if (retired) return
        retired = true
        checkpointAttempt++
        mutableState.value = mutableState.value.copy(selected = null, legalPoints = emptySet())
    }

    private fun refresh(
        selected: FangqiPoint? = null,
        feedback: FangqiFeedback = FangqiFeedback.NONE,
    ) {
        val snapshot = session.snapshot()
        val legal = session.legalActions()
        val legalPoints = if (snapshot.pendingCaptures > 0) {
            legal.filterIsInstance<FangqiAction.Capture>().map { it.point }.toSet()
        } else {
            legal.filterIsInstance<FangqiAction.Move>()
                .filter { it.from == selected }
                .map { it.to }
                .toSet()
        }
        mutableState.value = mutableState.value.copy(
            snapshot = snapshot, selected = selected, legalPoints = legalPoints, feedback = feedback,
        )
    }

    private fun report(feedback: FangqiFeedback) {
        mutableState.value = mutableState.value.copy(feedback = feedback)
    }

    private fun FangqiRejection.toFeedback(): FangqiFeedback = when (this) {
        FangqiRejection.RESOURCE_LIMIT -> FangqiFeedback.HISTORY_LIMIT
        FangqiRejection.REPEATED_POSITION -> FangqiFeedback.REPEATED
        FangqiRejection.GAME_OVER -> FangqiFeedback.GAME_OVER
        FangqiRejection.CAPTURE_REQUIRED -> FangqiFeedback.SELECT_CAPTURE
        else -> FangqiFeedback.ILLEGAL
    }
}
