package com.masterofchessstrategy.game

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.masterofchessstrategy.data.GameSessionRepository
import com.masterofchessstrategy.data.GameSessionSnapshot
import com.masterofchessstrategy.data.LoadGameSessionResult
import com.masterofchessstrategy.data.StoredGameMode
import com.masterofchessstrategy.engine.ActionResult
import com.masterofchessstrategy.engine.BoardMove
import com.masterofchessstrategy.engine.BoardPosition
import com.masterofchessstrategy.engine.ChineseChessBoard
import com.masterofchessstrategy.engine.ChineseChessAiEngine
import com.masterofchessstrategy.engine.ChineseChessRuleEngine
import com.masterofchessstrategy.engine.ChineseChessSide
import com.masterofchessstrategy.engine.EngineError
import com.masterofchessstrategy.engine.Difficulty
import com.masterofchessstrategy.engine.GameResult
import com.masterofchessstrategy.engine.GameType
import com.masterofchessstrategy.engine.NativeChineseChessEngine
import com.masterofchessstrategy.engine.RestoreResult
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Owns one local or human-versus-AI Chinese chess session.
 *
 * Room access is asynchronous. While restoring or committing a snapshot, game
 * interactions are disabled so a newer position cannot be overtaken by an
 * older database write.
 */
class ChineseChessGameViewModel internal constructor(
    private val sessionRepository: GameSessionRepository? = null,
    private val nowEpochMillis: () -> Long = { System.currentTimeMillis() },
    private val mode: StoredGameMode = StoredGameMode.LOCAL_TWO_PLAYER,
    private val difficulty: Difficulty? = null,
    private val aiDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val engineFactory: () -> ChineseChessRuleEngine,
) : ViewModel() {
    private var engine: ChineseChessRuleEngine? = null
    private var acceptedMoveCount = 0

    private val isAiGame = mode == StoredGameMode.HUMAN_VS_AI

    var uiState by mutableStateOf(
        ChineseChessGameUiState(
            isAiGame = isAiGame,
            difficulty = difficulty,
        ),
    )
        private set

    constructor() : this(engineFactory = { NativeChineseChessEngine() })

    init {
        try {
            require(
                !isAiGame ||
                    difficulty == Difficulty.EASY
            ) {
                "Human versus AI currently requires easy difficulty"
            }
            engine = engineFactory().also { created ->
                require(!isAiGame || created is ChineseChessAiEngine) {
                    "Human versus AI requires an AI-capable engine"
                }
            }
            refresh()
            restoreSavedSession()
        } catch (_: RuntimeException) {
            disableEngine()
        } catch (_: LinkageError) {
            disableEngine()
        }
    }

    fun onSquareTap(position: BoardPosition) {
        ChineseChessBoard.requireInside(position)
        val activeEngine = engine ?: return
        if (!uiState.isInteractionEnabled) return
        if (uiState.result != GameResult.ONGOING) {
            uiState = uiState.copy(feedback = ChineseChessFeedback.GAME_FINISHED)
            return
        }

        val piece = uiState.pieceAt(position)
        val selected = uiState.selectedPosition
        if (selected == null) {
            selectPiece(activeEngine, position, piece?.side)
            return
        }

        when {
            position == selected -> clearSelection()
            piece?.side == uiState.currentSide -> selectPiece(activeEngine, position, piece.side)
            position in uiState.legalDestinations -> applyMove(activeEngine, BoardMove(selected, position))
            else -> uiState = uiState.copy(feedback = ChineseChessFeedback.ILLEGAL_MOVE)
        }
    }

    fun undo() {
        val activeEngine = engine ?: return
        if (!uiState.isInteractionEnabled) return
        if (!uiState.canUndo) {
            uiState = uiState.copy(feedback = ChineseChessFeedback.NOTHING_TO_UNDO)
            return
        }
        runEngineOperation {
            if (undoTurn(activeEngine)) {
                refresh(ChineseChessFeedback.MOVE_UNDONE)
                persistCurrentSession()
            } else {
                acceptedMoveCount = 0
                refresh(ChineseChessFeedback.NOTHING_TO_UNDO)
            }
        }
    }

    fun restart() {
        val activeEngine = engine ?: return
        if (!uiState.isInteractionEnabled) return
        runEngineOperation {
            activeEngine.reset()
            acceptedMoveCount = 0
            refresh(ChineseChessFeedback.GAME_RESTARTED)
            persistCurrentSession()
        }
    }

    fun dismissFeedback() {
        uiState = uiState.copy(feedback = null)
    }

    override fun onCleared() {
        closeEngine()
    }

    private fun restoreSavedSession() {
        val repository = sessionRepository ?: return
        uiState = uiState.copy(isRestoring = true)
        viewModelScope.launch {
            val result = try {
                repository.load(GameType.CHINESE_CHESS)
            } catch (_: RuntimeException) {
                uiState = uiState.copy(
                    isRestoring = false,
                    feedback = ChineseChessFeedback.RESTORE_REJECTED,
                )
                return@launch
            }
            when (result) {
                LoadGameSessionResult.NotFound -> {
                    uiState = uiState.copy(isRestoring = false)
                }

                LoadGameSessionResult.Incompatible -> rejectStoredSession(repository)

                is LoadGameSessionResult.Loaded -> {
                    if (
                        result.snapshot.mode != mode ||
                        result.snapshot.difficulty != difficulty
                    ) {
                        rejectStoredSession(repository)
                    } else {
                        restoreEngineState(repository, result.snapshot.engineState)
                    }
                }
            }
        }
    }

    private suspend fun restoreEngineState(
        repository: GameSessionRepository,
        data: ByteArray,
    ) {
        val activeEngine = engine ?: return
        try {
            when (activeEngine.restore(data)) {
                RestoreResult.Restored -> {
                    acceptedMoveCount = 0
                    refresh(ChineseChessFeedback.GAME_RESTORED)
                    if (
                        isAiGame &&
                        activeEngine.currentPlayer.value == ChineseChessSide.BLACK.code &&
                        activeEngine.gameResult() == GameResult.ONGOING
                    ) {
                        startAiTurn(activeEngine, saveHumanPosition = false)
                    }
                }

                is RestoreResult.Rejected -> rejectStoredSession(repository)
            }
        } catch (_: RuntimeException) {
            disableEngine()
        } catch (_: LinkageError) {
            disableEngine()
        }
    }

    private suspend fun rejectStoredSession(repository: GameSessionRepository) {
        try {
            repository.clear(GameType.CHINESE_CHESS)
        } catch (_: RuntimeException) {
            // A failed cleanup must not prevent a fresh in-memory game.
        }
        val activeEngine = engine ?: return
        runEngineOperation {
            activeEngine.reset()
            acceptedMoveCount = 0
            refresh(ChineseChessFeedback.RESTORE_REJECTED)
        }
    }

    private fun persistCurrentSession() {
        val repository = sessionRepository ?: return
        val activeEngine = engine ?: return
        val stateBytes = try {
            activeEngine.serialize()
        } catch (_: RuntimeException) {
            disableEngine()
            return
        } catch (_: LinkageError) {
            disableEngine()
            return
        }
        uiState = uiState.copy(isPersisting = true)
        val snapshot = GameSessionSnapshot(
            gameType = GameType.CHINESE_CHESS,
            mode = mode,
            difficulty = difficulty,
            engineState = stateBytes,
            updatedAtEpochMillis = nowEpochMillis(),
        )
        viewModelScope.launch {
            try {
                repository.save(snapshot)
                uiState = uiState.copy(isPersisting = false)
            } catch (_: RuntimeException) {
                uiState = uiState.copy(
                    isPersisting = false,
                    feedback = ChineseChessFeedback.SAVE_FAILED,
                )
            }
        }
    }

    private fun selectPiece(
        activeEngine: ChineseChessRuleEngine,
        position: BoardPosition,
        side: ChineseChessSide?,
    ) {
        if (side == null) {
            uiState = uiState.copy(feedback = ChineseChessFeedback.SELECT_OWN_PIECE)
            return
        }
        if (side != uiState.currentSide) {
            uiState = uiState.copy(feedback = ChineseChessFeedback.WRONG_SIDE)
            return
        }
        runEngineOperation {
            val destinations = activeEngine.legalActions()
                .asSequence()
                .filter { it.from == position }
                .map { it.to }
                .toSet()
            uiState = uiState.copy(
                selectedPosition = position,
                legalDestinations = destinations,
                feedback = if (destinations.isEmpty()) {
                    ChineseChessFeedback.ILLEGAL_MOVE
                } else {
                    null
                },
            )
        }
    }

    private fun applyMove(
        activeEngine: ChineseChessRuleEngine,
        move: BoardMove,
    ) {
        runEngineOperation {
            when (val result = activeEngine.apply(move)) {
                ActionResult.Accepted -> {
                    acceptedMoveCount++
                    refresh()
                    if (
                        isAiGame &&
                        activeEngine.currentPlayer.value == ChineseChessSide.BLACK.code &&
                        activeEngine.gameResult() == GameResult.ONGOING
                    ) {
                        startAiTurn(activeEngine)
                    } else {
                        persistCurrentSession()
                    }
                }

                is ActionResult.Rejected -> {
                    uiState = uiState.copy(feedback = result.error.toFeedback())
                }
            }
        }
    }

    private fun refresh(feedback: ChineseChessFeedback? = null) {
        val activeEngine = engine ?: return
        runEngineOperation {
            val board = buildList(ChineseChessBoard.WIDTH * ChineseChessBoard.HEIGHT) {
                repeat(ChineseChessBoard.HEIGHT) { y ->
                    repeat(ChineseChessBoard.WIDTH) { x ->
                        add(activeEngine.pieceAt(BoardPosition(x, y)))
                    }
                }
            }
            uiState = ChineseChessGameUiState(
                board = board,
                currentSide = when (activeEngine.currentPlayer.value) {
                    ChineseChessSide.RED.code -> ChineseChessSide.RED
                    ChineseChessSide.BLACK.code -> ChineseChessSide.BLACK
                    else -> error("Engine returned an unsupported player")
                },
                result = activeEngine.gameResult(),
                canUndo = acceptedMoveCount > 0,
                isAiGame = isAiGame,
                difficulty = difficulty,
                feedback = feedback,
            )
        }
    }

    private fun clearSelection() {
        uiState = uiState.copy(
            selectedPosition = null,
            legalDestinations = emptySet(),
            feedback = null,
        )
    }

    private fun undoTurn(activeEngine: ChineseChessRuleEngine): Boolean {
        if (!activeEngine.undo()) return false
        acceptedMoveCount = (acceptedMoveCount - 1).coerceAtLeast(0)
        if (
            isAiGame &&
            activeEngine.currentPlayer.value == ChineseChessSide.BLACK.code &&
            acceptedMoveCount > 0
        ) {
            if (!activeEngine.undo()) return false
            acceptedMoveCount--
        }
        return true
    }

    private fun startAiTurn(
        activeEngine: ChineseChessRuleEngine,
        saveHumanPosition: Boolean = true,
    ) {
        val aiEngine = activeEngine as? ChineseChessAiEngine
        val selectedDifficulty = difficulty
        if (aiEngine == null || selectedDifficulty != Difficulty.EASY) {
            disableEngine()
            return
        }
        val humanSnapshot = if (saveHumanPosition) {
            createSnapshot(activeEngine)
        } else {
            null
        }
        uiState = uiState.copy(
            selectedPosition = null,
            legalDestinations = emptySet(),
            isAiThinking = true,
            isPersisting = humanSnapshot != null && sessionRepository != null,
        )
        viewModelScope.launch {
            humanSnapshot?.let { snapshot ->
                try {
                    sessionRepository?.save(snapshot)
                } catch (_: RuntimeException) {
                    // The post-AI snapshot retries persistence for the full turn.
                }
            }
            if (engine !== activeEngine) return@launch
            uiState = uiState.copy(isPersisting = false)
            val move = try {
                withContext(aiDispatcher) {
                    aiEngine.chooseMove(selectedDifficulty)
                }
            } catch (_: RuntimeException) {
                disableEngine()
                return@launch
            } catch (_: LinkageError) {
                disableEngine()
                return@launch
            }
            if (engine !== activeEngine) return@launch
            if (move == null) {
                val feedback = if (activeEngine.gameResult() == GameResult.ONGOING) {
                    ChineseChessFeedback.AI_MOVE_FAILED
                } else {
                    null
                }
                refresh(feedback)
                return@launch
            }
            when (activeEngine.apply(move)) {
                ActionResult.Accepted -> {
                    acceptedMoveCount++
                    refresh(ChineseChessFeedback.AI_MOVED)
                    persistCurrentSession()
                }

                is ActionResult.Rejected -> {
                    refresh(ChineseChessFeedback.AI_MOVE_FAILED)
                }
            }
        }
    }

    private fun createSnapshot(
        activeEngine: ChineseChessRuleEngine,
    ): GameSessionSnapshot =
        GameSessionSnapshot(
            gameType = GameType.CHINESE_CHESS,
            mode = mode,
            difficulty = difficulty,
            engineState = activeEngine.serialize(),
            updatedAtEpochMillis = nowEpochMillis(),
        )

    private inline fun runEngineOperation(operation: () -> Unit) {
        try {
            operation()
        } catch (_: RuntimeException) {
            disableEngine()
        } catch (_: LinkageError) {
            disableEngine()
        }
    }

    private fun disableEngine() {
        closeEngine()
        uiState = uiState.copy(
            selectedPosition = null,
            legalDestinations = emptySet(),
            canUndo = false,
            isEngineAvailable = false,
            isRestoring = false,
            isPersisting = false,
            feedback = ChineseChessFeedback.ENGINE_UNAVAILABLE,
        )
    }

    private fun closeEngine() {
        val activeEngine = engine
        engine = null
        try {
            activeEngine?.close()
        } catch (_: RuntimeException) {
            // Cleanup must not replace the stable user-facing failure state.
        } catch (_: LinkageError) {
            // The native library may already be unavailable during teardown.
        }
    }

    private fun EngineError.toFeedback(): ChineseChessFeedback =
        when (this) {
            EngineError.ILLEGAL_ACTION -> ChineseChessFeedback.ILLEGAL_MOVE
            EngineError.INVALID_STATE,
            EngineError.CORRUPTED_DATA,
            EngineError.UNSUPPORTED,
            -> ChineseChessFeedback.MOVE_REJECTED
        }

    companion object {
        internal fun factory(
            repository: GameSessionRepository,
            mode: StoredGameMode = StoredGameMode.LOCAL_TWO_PLAYER,
            difficulty: Difficulty? = null,
        ): ViewModelProvider.Factory =
            viewModelFactory {
                initializer {
                    ChineseChessGameViewModel(
                        sessionRepository = repository,
                        mode = mode,
                        difficulty = difficulty,
                        engineFactory = { NativeChineseChessEngine() },
                    )
                }
            }
    }
}
