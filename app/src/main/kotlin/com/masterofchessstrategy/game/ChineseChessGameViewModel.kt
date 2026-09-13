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
import com.masterofchessstrategy.data.MatchOutcome
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
import java.util.UUID

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
    private val matchIdFactory: () -> String = { UUID.randomUUID().toString() },
    private val onMatchFinished: (MatchOutcome) -> Unit = {},
    private val engineFactory: () -> ChineseChessRuleEngine,
) : ViewModel() {
    private var engine: ChineseChessRuleEngine? = null
    private var acceptedMoveCount = 0
    private var undoUseCount = 0
    private var hintUseCount = 0
    private var resultOverride: GameResult? = null
    private var matchId = createMatchId()
    private var settlementRequested = false

    private val isAiGame = mode == StoredGameMode.HUMAN_VS_AI
    private val assistancePolicy = ChineseChessAssistancePolicy.resolve(mode, difficulty)

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
                    difficulty == Difficulty.EASY ||
                    difficulty == Difficulty.MEDIUM ||
                    difficulty == Difficulty.HARD ||
                    difficulty == Difficulty.MASTER
            ) {
                "Human versus AI requires a supported difficulty"
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
        if (uiState.hintedOrigins.isNotEmpty() || uiState.hintedDestinations.isNotEmpty()) {
            uiState = uiState.copy(
                hintedOrigins = emptySet(),
                hintedDestinations = emptySet(),
            )
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
            uiState = uiState.copy(
                feedback = if (uiState.undoRemaining == 0) {
                    ChineseChessFeedback.UNDO_LIMIT_REACHED
                } else {
                    ChineseChessFeedback.NOTHING_TO_UNDO
                },
            )
            return
        }
        runEngineOperation {
            if (undoTurn(activeEngine)) {
                undoUseCount++
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
            matchId = createMatchId()
            settlementRequested = false
            activeEngine.reset()
            acceptedMoveCount = 0
            undoUseCount = 0
            hintUseCount = 0
            resultOverride = null
            refresh(ChineseChessFeedback.GAME_RESTARTED)
            persistCurrentSession()
        }
    }

    fun requestHint() {
        val activeEngine = engine ?: return
        if (!uiState.isInteractionEnabled || uiState.result != GameResult.ONGOING) return
        if (!uiState.canRequestHint) {
            uiState = uiState.copy(
                feedback = if (
                    assistancePolicy.hintMode != ChineseChessHintMode.NONE &&
                    uiState.hintRemaining == 0
                ) {
                    ChineseChessFeedback.HINT_LIMIT_REACHED
                } else {
                    ChineseChessFeedback.HINT_UNAVAILABLE
                },
            )
            return
        }
        when (assistancePolicy.hintMode) {
            ChineseChessHintMode.ALL_LEGAL_MOVES -> revealAllLegalMoves(activeEngine)
            ChineseChessHintMode.BEST_MOVE -> calculateBestMoveHint(activeEngine)
            ChineseChessHintMode.NONE -> {
                uiState = uiState.copy(feedback = ChineseChessFeedback.HINT_UNAVAILABLE)
            }
        }
    }

    fun resign() {
        if (!uiState.isInteractionEnabled || uiState.result != GameResult.ONGOING) return
        resultOverride = if (uiState.currentSide == ChineseChessSide.RED) {
            GameResult.SECOND_PLAYER_WIN
        } else {
            GameResult.FIRST_PLAYER_WIN
        }
        refresh(ChineseChessFeedback.PLAYER_RESIGNED)
        persistCurrentSession()
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
                        restoreEngineState(repository, result.snapshot)
                    }
                }
            }
        }
    }

    private suspend fun restoreEngineState(
        repository: GameSessionRepository,
        snapshot: GameSessionSnapshot,
    ) {
        val activeEngine = engine ?: return
        try {
            when (activeEngine.restore(snapshot.engineState)) {
                RestoreResult.Restored -> {
                    matchId = snapshot.sessionId
                        .takeIf(::isValidMatchId)
                        ?: createMatchId()
                    settlementRequested = false
                    acceptedMoveCount = snapshot.acceptedMoveCount
                    undoUseCount = snapshot.undoUseCount
                    hintUseCount = snapshot.hintUseCount
                    resultOverride = snapshot.resultOverride
                    refresh(ChineseChessFeedback.GAME_RESTORED)
                    if (
                        isAiGame &&
                        resultOverride == null &&
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
            matchId = createMatchId()
            settlementRequested = false
            activeEngine.reset()
            acceptedMoveCount = 0
            undoUseCount = 0
            hintUseCount = 0
            resultOverride = null
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
        val snapshot = createSnapshot(stateBytes)
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
            val result = resultOverride ?: activeEngine.gameResult()
            val undoRemaining = assistancePolicy.undoRemaining(undoUseCount)
            val hintRemaining = assistancePolicy.hintRemaining(hintUseCount)
            uiState = ChineseChessGameUiState(
                board = board,
                currentSide = when (activeEngine.currentPlayer.value) {
                    ChineseChessSide.RED.code -> ChineseChessSide.RED
                    ChineseChessSide.BLACK.code -> ChineseChessSide.BLACK
                    else -> error("Engine returned an unsupported player")
                },
                result = result,
                canUndo =
                    acceptedMoveCount > 0 &&
                        result == GameResult.ONGOING &&
                        (undoRemaining == null || undoRemaining > 0),
                undoRemaining = undoRemaining,
                canRequestHint =
                    result == GameResult.ONGOING &&
                        assistancePolicy.hintMode != ChineseChessHintMode.NONE &&
                        (hintRemaining == null || hintRemaining > 0),
                hintRemaining = hintRemaining,
                isAiGame = isAiGame,
                difficulty = difficulty,
                feedback = feedback,
            )
            requestSettlement(result)
        }
    }

    private fun clearSelection() {
        uiState = uiState.copy(
            selectedPosition = null,
            legalDestinations = emptySet(),
            hintedOrigins = emptySet(),
            hintedDestinations = emptySet(),
            feedback = null,
        )
    }

    private fun revealAllLegalMoves(activeEngine: ChineseChessRuleEngine) {
        runEngineOperation {
            val moves = activeEngine.legalActions()
            if (moves.isEmpty()) {
                uiState = uiState.copy(feedback = ChineseChessFeedback.HINT_UNAVAILABLE)
                return@runEngineOperation
            }
            uiState = uiState.copy(
                selectedPosition = null,
                legalDestinations = emptySet(),
                hintedOrigins = moves.mapTo(mutableSetOf(), BoardMove::from),
                hintedDestinations = moves.mapTo(mutableSetOf(), BoardMove::to),
                feedback = ChineseChessFeedback.HINT_READY,
            )
            persistCurrentSession()
        }
    }

    private fun calculateBestMoveHint(activeEngine: ChineseChessRuleEngine) {
        val aiEngine = activeEngine as? ChineseChessAiEngine
        val selectedDifficulty = difficulty
        if (aiEngine == null || selectedDifficulty == null) {
            uiState = uiState.copy(
                feedback = ChineseChessFeedback.HINT_UNAVAILABLE,
            )
            return
        }
        uiState = uiState.copy(
            selectedPosition = null,
            legalDestinations = emptySet(),
            hintedOrigins = emptySet(),
            hintedDestinations = emptySet(),
            isHintThinking = true,
            feedback = null,
        )
        viewModelScope.launch {
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
            val hintedMove = try {
                move?.takeIf { it in activeEngine.legalActions() }
            } catch (_: RuntimeException) {
                disableEngine()
                return@launch
            } catch (_: LinkageError) {
                disableEngine()
                return@launch
            }
            if (hintedMove == null) {
                uiState = uiState.copy(
                    isHintThinking = false,
                    feedback = ChineseChessFeedback.HINT_UNAVAILABLE,
                )
                return@launch
            }
            hintUseCount++
            uiState = uiState.copy(
                hintedOrigins = setOf(hintedMove.from),
                hintedDestinations = setOf(hintedMove.to),
                isHintThinking = false,
                canRequestHint =
                    assistancePolicy.hintRemaining(hintUseCount)?.let { it > 0 } ?: true,
                hintRemaining = assistancePolicy.hintRemaining(hintUseCount),
                feedback = ChineseChessFeedback.HINT_READY,
            )
            persistCurrentSession()
        }
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
        val selectedDifficulty = difficulty?.takeIf {
            it != Difficulty.MASTER
        }
        if (aiEngine == null || selectedDifficulty == null) {
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
            hintedOrigins = emptySet(),
            hintedDestinations = emptySet(),
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
        createSnapshot(activeEngine.serialize())

    private fun createSnapshot(
        engineState: ByteArray,
    ): GameSessionSnapshot =
        GameSessionSnapshot(
            gameType = GameType.CHINESE_CHESS,
            mode = mode,
            difficulty = difficulty,
            engineState = engineState,
            updatedAtEpochMillis = nowEpochMillis(),
            sessionId = matchId,
            acceptedMoveCount = acceptedMoveCount,
            undoUseCount = undoUseCount,
            hintUseCount = hintUseCount,
            resultOverride = resultOverride,
        )

    private fun requestSettlement(result: GameResult) {
        if (
            !isAiGame ||
            result == GameResult.ONGOING ||
            settlementRequested
        ) {
            return
        }
        val selectedDifficulty = difficulty ?: return
        settlementRequested = true
        onMatchFinished(
            MatchOutcome(
                matchId = matchId,
                gameType = GameType.CHINESE_CHESS,
                mode = StoredGameMode.HUMAN_VS_AI,
                difficulty = selectedDifficulty,
                playerIndex = ChineseChessSide.RED.code,
                result = result,
                settledAtEpochMillis = nowEpochMillis(),
            ),
        )
    }

    private fun createMatchId(): String =
        matchIdFactory().also {
            require(isValidMatchId(it)) {
                "Generated match id must contain 1 to 64 characters"
            }
        }

    private fun isValidMatchId(value: String): Boolean =
        value.isNotBlank() && value.length <= MatchOutcome.MAX_MATCH_ID_LENGTH

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
            hintedOrigins = emptySet(),
            hintedDestinations = emptySet(),
            canUndo = false,
            canRequestHint = false,
            isEngineAvailable = false,
            isRestoring = false,
            isPersisting = false,
            isAiThinking = false,
            isHintThinking = false,
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
            onMatchFinished: (MatchOutcome) -> Unit = {},
            engineFactory: () -> ChineseChessRuleEngine = {
                NativeChineseChessEngine()
            },
        ): ViewModelProvider.Factory =
            viewModelFactory {
                initializer {
                    ChineseChessGameViewModel(
                        sessionRepository = repository,
                        mode = mode,
                        difficulty = difficulty,
                        onMatchFinished = onMatchFinished,
                        engineFactory = engineFactory,
                    )
                }
            }
    }
}
