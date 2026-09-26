package com.masterofchessstrategy.custom

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.masterofchessstrategy.data.GameSessionRepository
import com.masterofchessstrategy.data.LoadGameSessionResult
import com.masterofchessstrategy.data.StoredGameMode
import com.masterofchessstrategy.engine.BoardPosition
import com.masterofchessstrategy.engine.ChineseChessBoard
import com.masterofchessstrategy.engine.ChineseChessPiece
import com.masterofchessstrategy.engine.ChineseChessPieceType
import com.masterofchessstrategy.engine.ChineseChessRuleEngine
import com.masterofchessstrategy.engine.ChineseChessSide
import com.masterofchessstrategy.engine.Difficulty
import com.masterofchessstrategy.engine.GameResult
import com.masterofchessstrategy.engine.GameType
import com.masterofchessstrategy.engine.NativeChineseChessEngine
import com.masterofchessstrategy.engine.RestoreResult
import kotlinx.coroutines.launch
import java.util.UUID

internal enum class HandicapFeedback { GENERAL_PROTECTED, INVALID_POSITION, ENGINE_UNAVAILABLE, DIFFICULTY_LOCKED }

internal data class PreparedHandicapGame(val variant: String, val difficulty: Difficulty)

internal data class ChineseChessHandicapUiState(
    val removed: Set<Int> = emptySet(),
    val presetSide: ChineseChessSide = ChineseChessSide.BLACK,
    val difficulty: Difficulty = Difficulty.EASY,
    val unlockedDifficulties: Set<Difficulty> = emptySet(),
    val savedGame: PreparedHandicapGame? = null,
    val isLaunching: Boolean = false,
    val feedback: HandicapFeedback? = null,
) {
    val board: List<ChineseChessPiece?> get() = ChineseChessHandicapConfig.board(removed)
    val removedPositions: Set<BoardPosition> get() = removed.mapTo(linkedSetOf()) { BoardPosition(it % 9, it / 9) }
}

/** Selects pieces to remove; all game logic remains in the shared native-backed game VM. */
internal class ChineseChessHandicapViewModel(
    private val repository: GameSessionRepository,
    unlockedDifficulties: Set<Difficulty>,
    private val engineFactory: () -> ChineseChessRuleEngine = { NativeChineseChessEngine() },
    private val idFactory: () -> String = { UUID.randomUUID().toString().replace("-", "") },
) : ViewModel() {
    private val standard = standardBoard()
    var uiState by mutableStateOf(ChineseChessHandicapUiState(
        unlockedDifficulties = unlockedDifficulties.toSet(),
        difficulty = unlockedDifficulties.firstOrNull() ?: Difficulty.EASY,
    ))
        private set

    /** Called whenever the setup destination becomes visible again. */
    fun refreshSavedGame() {
        uiState = uiState.copy(isLaunching = false)
        viewModelScope.launch {
            val snapshot = try {
                (repository.load(GameType.CHINESE_CHESS) as? LoadGameSessionResult.Loaded)?.snapshot
            } catch (_: RuntimeException) { null }
            val saved = snapshot?.takeIf {
                it.mode == StoredGameMode.HANDICAP && it.difficulty in uiState.unlockedDifficulties
            }?.let {
                val initial = try { ChineseChessHandicapConfig.initialState(it.sessionVariantId) }
                    catch (_: IllegalArgumentException) { null }
                if (initial != null && isPlayable(initial, reportFailure = false)) {
                    PreparedHandicapGame(it.sessionVariantId, requireNotNull(it.difficulty))
                } else null
            }
            uiState = uiState.copy(savedGame = saved)
        }
    }

    fun toggleSquare(position: BoardPosition) {
        if (uiState.isLaunching) return
        ChineseChessBoard.requireInside(position)
        val index = position.y * 9 + position.x
        if (standard[index]?.type == ChineseChessPieceType.GENERAL) {
            uiState = uiState.copy(feedback = HandicapFeedback.GENERAL_PROTECTED)
            return
        }
        if (index !in ChineseChessHandicapConfig.removableSquares) return
        uiState = uiState.copy(
            removed = if (index in uiState.removed) uiState.removed - index else uiState.removed + index,
            feedback = null,
        )
    }

    fun selectPresetSide(side: ChineseChessSide) {
        if (!uiState.isLaunching) uiState = uiState.copy(presetSide = side)
    }

    fun addPreset(type: ChineseChessPieceType) {
        if (uiState.isLaunching) return
        require(type == ChineseChessPieceType.HORSE || type == ChineseChessPieceType.CHARIOT)
        val square = standard.indices.firstOrNull {
            val piece = standard[it]
            piece?.side == uiState.presetSide && piece.type == type && it !in uiState.removed
        } ?: return
        uiState = uiState.copy(removed = uiState.removed + square, feedback = null)
    }

    fun reset() {
        if (!uiState.isLaunching) uiState = uiState.copy(removed = emptySet(), feedback = null)
    }

    fun selectDifficulty(difficulty: Difficulty) {
        if (uiState.isLaunching) return
        if (difficulty in uiState.unlockedDifficulties) uiState = uiState.copy(difficulty = difficulty, feedback = null)
    }

    fun prepareNewGame(): PreparedHandicapGame? {
        if (uiState.isLaunching) return null
        if (uiState.difficulty !in uiState.unlockedDifficulties) {
            uiState = uiState.copy(feedback = HandicapFeedback.DIFFICULTY_LOCKED)
            return null
        }
        val variant: String
        val initial: ByteArray
        try {
            variant = ChineseChessHandicapConfig.sessionVariant(uiState.removed, idFactory())
            initial = ChineseChessHandicapConfig.initialState(variant)
        } catch (_: IllegalArgumentException) {
            uiState = uiState.copy(feedback = HandicapFeedback.INVALID_POSITION)
            return null
        }
        if (!isPlayable(initial, reportFailure = true)) return null
        uiState = uiState.copy(feedback = null, isLaunching = true)
        return PreparedHandicapGame(variant, uiState.difficulty)
    }

    fun continueSavedGame(): PreparedHandicapGame? {
        if (uiState.isLaunching) return null
        return uiState.savedGame?.also { uiState = uiState.copy(isLaunching = true) }
    }

    private fun isPlayable(state: ByteArray, reportFailure: Boolean): Boolean {
        val playable = try {
            engineFactory().use {
                it.restore(state) is RestoreResult.Restored && it.gameResult() == GameResult.ONGOING &&
                    it.legalActions().isNotEmpty()
            }
        } catch (_: RuntimeException) {
            if (reportFailure) uiState = uiState.copy(feedback = HandicapFeedback.ENGINE_UNAVAILABLE)
            return false
        } catch (_: LinkageError) {
            if (reportFailure) uiState = uiState.copy(feedback = HandicapFeedback.ENGINE_UNAVAILABLE)
            return false
        }
        if (!playable && reportFailure) uiState = uiState.copy(feedback = HandicapFeedback.INVALID_POSITION)
        return playable
    }

    companion object {
        fun factory(repository: GameSessionRepository, unlocked: Set<Difficulty>): ViewModelProvider.Factory =
            viewModelFactory { initializer { ChineseChessHandicapViewModel(repository, unlocked) } }
    }
}
