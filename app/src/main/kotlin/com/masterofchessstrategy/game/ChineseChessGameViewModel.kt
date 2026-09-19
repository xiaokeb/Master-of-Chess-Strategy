package com.masterofchessstrategy.game

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.masterofchessstrategy.data.AppSettings
import com.masterofchessstrategy.data.GameSessionRepository
import com.masterofchessstrategy.data.GameSessionSnapshot
import com.masterofchessstrategy.data.GameRecord
import com.masterofchessstrategy.data.LoadGameSessionResult
import com.masterofchessstrategy.data.MatchOutcome
import com.masterofchessstrategy.data.StoredGameMode
import com.masterofchessstrategy.challenge.TimedChallengeConfig
import com.masterofchessstrategy.challenge.StreakChallengeState
import com.masterofchessstrategy.challenge.StreakChallengeStateCodec
import com.masterofchessstrategy.custom.CustomPositionStateCodec
import com.masterofchessstrategy.engine.ActionResult
import com.masterofchessstrategy.engine.BoardMove
import com.masterofchessstrategy.engine.BoardPosition
import com.masterofchessstrategy.engine.ChineseChessAiEngine
import com.masterofchessstrategy.engine.ChineseChessBoard
import com.masterofchessstrategy.engine.ChineseChessRuleEngine
import com.masterofchessstrategy.engine.ChineseChessSide
import com.masterofchessstrategy.engine.Difficulty
import com.masterofchessstrategy.engine.EngineError
import com.masterofchessstrategy.engine.GameResult
import com.masterofchessstrategy.engine.GameType
import com.masterofchessstrategy.engine.NativeChineseChessEngine
import com.masterofchessstrategy.engine.RestoreResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import kotlin.math.roundToInt

/**
 * Owns one local, human-versus-AI, or AI auto-play Chinese chess session.
 *
 * Room access is asynchronous. While restoring or committing a snapshot, game
 * interactions are disabled so a newer position cannot be overtaken by an
 * older database write.
 */
class ChineseChessGameViewModel internal constructor(
    private val sessionRepository: GameSessionRepository? = null,
    private val nowEpochMillis: () -> Long = { System.currentTimeMillis() },
    private val mode: StoredGameMode = StoredGameMode.LOCAL_TWO_PLAYER,
    private var difficulty: Difficulty? = null,
    private val aiDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val matchIdFactory: () -> String = { UUID.randomUUID().toString() },
    private val onMatchFinished: (MatchOutcome) -> Unit = {},
    private val onGameRecorded: (GameRecord) -> Unit = {},
    private val initialTimeControlMinutes: Int? = null,
    private val perMoveTimeLimitSeconds: Int? = null,
    private val autoContinueEnabled: Boolean = false,
    private val autoContinueGameLimit: Int = 10,
    private val clockTickIntervalMillis: Long? = CLOCK_TICK_MILLIS,
    initialPositionState: ByteArray? = null,
    private var sessionVariantId: String = "",
    initialStreakState: StreakChallengeState? = null,
    private val endgameTitle: String? = null,
    private val endgameMaxPlayerMoves: Int? = null,
    private val engineFactory: () -> ChineseChessRuleEngine,
) : ViewModel() {
    private val initialPositionState = initialPositionState?.copyOf()
    private var streakState = initialStreakState
    private var engine: ChineseChessRuleEngine? = null
    private var acceptedMoveCount = 0
    private var undoUseCount = 0
    private var hintUseCount = 0
    private var resultOverride: GameResult? = null
    private var matchId = createMatchId()
    private var settlementRequested = false
    private var terminalHandled = false
    private var recordRequested = false
    private val perMoveTimeLimitMillis = perMoveTimeLimitSeconds?.times(1_000L)
    private val initialClockMillis =
        perMoveTimeLimitMillis ?: initialTimeControlMinutes?.toClockMillis()
    private var timeControlMinutes =
        if (perMoveTimeLimitSeconds == null) {
            initialTimeControlMinutes
        } else {
            TimedChallengeConfig.BACKING_CLOCK_MINUTES
        }
    private var redRemainingMillis = initialClockMillis
    private var blackRemainingMillis = initialClockMillis
    private var turnStartedAtEpochMillis = initialClockMillis?.let {
        nowEpochMillis()
    }
    private var pendingDrawOfferSide: ChineseChessSide? = null
    private var autoPlayPaused = false
    private var autoPlaySpeedPermille = DEFAULT_AUTO_PLAY_SPEED
    private var completedAutoGames = 0
    private var clockJob: Job? = null
    private var automationJob: Job? = null
    private val mutableSoundEvents = MutableSharedFlow<ChineseChessSoundCue>(
        extraBufferCapacity = SOUND_EVENT_BUFFER_CAPACITY,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    private val isHumanControlledAiGame =
        mode == StoredGameMode.HUMAN_VS_AI ||
            mode == StoredGameMode.ENDGAME ||
            mode == StoredGameMode.CUSTOM_POSITION ||
            mode == StoredGameMode.TIMED_CHALLENGE ||
            mode == StoredGameMode.STREAK_CHALLENGE
    private val isAiGame =
        isHumanControlledAiGame || mode == StoredGameMode.AI_AUTO_PLAY
    private val isAutoPlay = mode == StoredGameMode.AI_AUTO_PLAY
    private val assistancePolicy: ChineseChessAssistancePolicy
        get() = ChineseChessAssistancePolicy.resolve(mode, difficulty)

    var uiState by mutableStateOf(
        ChineseChessGameUiState(
            isAiGame = isAiGame,
            isAutoPlay = isAutoPlay,
            difficulty = difficulty,
            isEndgame = mode == StoredGameMode.ENDGAME,
            isCustomPosition = mode == StoredGameMode.CUSTOM_POSITION,
            isTimedChallenge = mode == StoredGameMode.TIMED_CHALLENGE,
            perMoveTimeLimitSeconds = perMoveTimeLimitSeconds,
            isStreakChallenge = mode == StoredGameMode.STREAK_CHALLENGE,
            currentStreak = initialStreakState?.currentStreak ?: 0,
            bestStreak = initialStreakState?.bestStreak ?: 0,
            streakNextDifficulty = initialStreakState?.nextDifficulty,
            endgameTitle = endgameTitle,
            endgameMaxPlayerMoves = endgameMaxPlayerMoves,
        ),
    )
        private set

    /**
     * Live-only sound events. replay remains zero so restore and recomposition
     * never repeat an action that happened before the current collector.
     */
    val soundEvents = mutableSoundEvents.asSharedFlow()

    constructor() : this(engineFactory = { NativeChineseChessEngine() })

    init {
        try {
            require(
                initialTimeControlMinutes == null ||
                    initialTimeControlMinutes in AppSettings.DURATION_RANGE
            ) {
                "Time control is outside the supported range"
            }
            require(autoContinueGameLimit in AppSettings.AUTO_CONTINUE_LIMIT_RANGE) {
                "Auto-continue limit is outside the supported range"
            }
            require(
                !isAiGame ||
                    difficulty == Difficulty.EASY ||
                    difficulty == Difficulty.MEDIUM ||
                    difficulty == Difficulty.HARD ||
                    difficulty == Difficulty.MASTER
            ) {
                "AI modes require a supported difficulty"
            }
            require(sessionVariantId.length <= MAX_SESSION_VARIANT_ID_LENGTH)
            require(
                mode != StoredGameMode.ENDGAME ||
                    (
                        sessionVariantId.isNotBlank() &&
                            this.initialPositionState != null &&
                            !endgameTitle.isNullOrBlank() &&
                            endgameMaxPlayerMoves != null &&
                            endgameMaxPlayerMoves in 1..MAX_ENDGAME_PLAYER_MOVES
                        )
            ) {
                "Endgame mode requires level metadata and an initial position"
            }
            require(
                mode == StoredGameMode.ENDGAME ||
                    (endgameTitle == null && endgameMaxPlayerMoves == null)
            ) { "Endgame metadata is only valid in endgame mode" }
            require(
                mode != StoredGameMode.CUSTOM_POSITION ||
                    (
                        this.initialPositionState != null &&
                            sessionVariantId ==
                            CustomPositionStateCodec.sessionVariant(
                                this.initialPositionState,
                            )
                        )
            ) { "Custom-position mode requires a versioned initial position" }
            require(
                if (mode == StoredGameMode.TIMED_CHALLENGE) {
                    initialTimeControlMinutes == null &&
                        perMoveTimeLimitSeconds != null &&
                        perMoveTimeLimitSeconds in TimedChallengeConfig.ALLOWED_SECONDS &&
                        sessionVariantId == TimedChallengeConfig.sessionVariant(
                            requireNotNull(perMoveTimeLimitSeconds),
                        )
                } else {
                    perMoveTimeLimitSeconds == null
                },
            ) { "Timed challenge requires a canonical per-move clock" }
            require(
                if (mode == StoredGameMode.STREAK_CHALLENGE) {
                    difficulty != null &&
                        initialStreakState != null &&
                        sessionVariantId == StreakChallengeStateCodec.encode(
                            initialStreakState,
                        )
                } else {
                    initialStreakState == null
                },
            ) { "Streak challenge requires a canonical series state" }
            engine = engineFactory().also { created ->
                require(!isAiGame || created is ChineseChessAiEngine) {
                    "AI modes require an AI-capable engine"
                }
                this.initialPositionState?.let { initial ->
                    require(created.restore(initial) is RestoreResult.Restored) {
                        "Initial Chinese chess position is incompatible"
                    }
                }
            }
            refresh()
            restoreSavedSession()
            if (sessionRepository == null) {
                resumeRuntimeWork()
            }
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
            val now = nowEpochMillis()
            if (!commitActiveClock(now)) return@runEngineOperation
            if (undoTurn(activeEngine)) {
                undoUseCount++
                pendingDrawOfferSide = null
                startClockForCurrentTurn(activeEngine, now)
                refresh(ChineseChessFeedback.MOVE_UNDONE)
                persistCurrentSession()
            } else {
                acceptedMoveCount = 0
                refresh(ChineseChessFeedback.NOTHING_TO_UNDO)
            }
        }
    }

    fun restart() {
        if (mode == StoredGameMode.STREAK_CHALLENGE) return
        val activeEngine = engine ?: return
        if (!canRunControl()) return
        runEngineOperation {
            resetPosition(activeEngine)
            resetSessionState(resetAutoGameCount = true)
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
        if (!commitActiveClock(nowEpochMillis())) return
        resultOverride = if (uiState.currentSide == ChineseChessSide.RED) {
            GameResult.SECOND_PLAYER_WIN
        } else {
            GameResult.FIRST_PLAYER_WIN
        }
        turnStartedAtEpochMillis = null
        pendingDrawOfferSide = null
        refresh(ChineseChessFeedback.PLAYER_RESIGNED)
        emitTerminalSound(requireNotNull(resultOverride))
        persistCurrentSession()
    }

    fun offerOrAcceptDraw() {
        if (
            !uiState.isInteractionEnabled ||
            uiState.result != GameResult.ONGOING ||
            isAutoPlay
        ) {
            return
        }
        val currentSide = uiState.currentSide
        when (pendingDrawOfferSide) {
            null -> {
                pendingDrawOfferSide = currentSide
                refresh(ChineseChessFeedback.DRAW_OFFERED)
                persistCurrentSession()
            }

            currentSide -> {
                uiState = uiState.copy(feedback = ChineseChessFeedback.DRAW_WAITING)
            }

            else -> {
                if (!commitActiveClock(nowEpochMillis())) return
                pendingDrawOfferSide = null
                resultOverride = GameResult.DRAW
                turnStartedAtEpochMillis = null
                refresh(ChineseChessFeedback.DRAW_ACCEPTED)
                emitSound(ChineseChessSoundCue.DRAW)
                persistCurrentSession()
            }
        }
    }

    fun toggleAutoPlayPaused() {
        val activeEngine = engine ?: return
        if (!isAutoPlay || !uiState.isEngineAvailable) return
        if (autoPlayPaused && uiState.result != GameResult.ONGOING) {
            runEngineOperation {
                activeEngine.reset()
                autoPlayPaused = false
                resetSessionState(resetAutoGameCount = false)
                refresh(ChineseChessFeedback.AUTO_PLAY_RESUMED)
                persistCurrentSession()
            }
            return
        }
        val now = nowEpochMillis()
        autoPlayPaused = !autoPlayPaused
        if (autoPlayPaused && uiState.result == GameResult.ONGOING) {
            if (!commitActiveClock(now)) return
            turnStartedAtEpochMillis = null
        } else if (autoPlayPaused) {
            automationJob?.cancel()
            automationJob = null
        } else if (uiState.result == GameResult.ONGOING) {
            turnStartedAtEpochMillis = timeControlMinutes?.let { now }
        }
        uiState = uiState.copy(
            isAutoPlayPaused = autoPlayPaused,
            redRemainingMillis = redRemainingMillis,
            blackRemainingMillis = blackRemainingMillis,
            feedback = if (autoPlayPaused) {
                ChineseChessFeedback.AUTO_PLAY_PAUSED
            } else {
                ChineseChessFeedback.AUTO_PLAY_RESUMED
            },
        )
        if (!uiState.isAiThinking) {
            persistCurrentSession()
        }
    }

    fun setAutoPlaySpeed(speed: Float) {
        if (!isAutoPlay || !uiState.isEngineAvailable || !speed.isFinite()) return
        autoPlaySpeedPermille = (
            speed.coerceIn(MIN_AUTO_PLAY_SPEED, MAX_AUTO_PLAY_SPEED) * 1_000f
        ).roundToInt()
        uiState = uiState.copy(autoPlaySpeed = autoPlaySpeedPermille / 1_000f)
    }

    fun persistAutoPlaySpeed() {
        if (!isAutoPlay || !uiState.isEngineAvailable) return
        if (!uiState.isAiThinking) {
            persistCurrentSession()
        }
    }

    internal fun synchronizeClock() {
        if (
            timeControlMinutes == null ||
            uiState.isRestoring ||
            uiState.result != GameResult.ONGOING
        ) {
            return
        }
        val now = nowEpochMillis()
        val remaining = remainingFor(uiState.currentSide, now) ?: return
        if (remaining <= 0L) {
            expireActiveClock(now)
            return
        }
        uiState = uiState.copy(
            redRemainingMillis = remainingFor(ChineseChessSide.RED, now),
            blackRemainingMillis = remainingFor(ChineseChessSide.BLACK, now),
        )
    }

    fun dismissFeedback() {
        uiState = uiState.copy(feedback = null)
    }

    fun continueStreakChallenge() {
        if (
            mode != StoredGameMode.STREAK_CHALLENGE ||
            uiState.result == GameResult.ONGOING ||
            !canRunControl()
        ) {
            return
        }
        val activeEngine = engine ?: return
        val activeStreak = streakState ?: return
        if (activeStreak.nextDifficulty == null) return
        runEngineOperation {
            val (nextDifficulty, nextState) = activeStreak.startNextGame()
            difficulty = nextDifficulty
            streakState = nextState
            sessionVariantId = StreakChallengeStateCodec.encode(nextState)
            resetPosition(activeEngine)
            resetSessionState(resetAutoGameCount = true)
            refresh(ChineseChessFeedback.STREAK_NEXT_GAME)
            persistCurrentSession()
        }
    }

    override fun onCleared() {
        clockJob?.cancel()
        automationJob?.cancel()
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
                resumeRuntimeWork()
                return@launch
            }
            when (result) {
                LoadGameSessionResult.NotFound -> {
                    if (resetStreakSeriesState()) {
                        refresh()
                    }
                    turnStartedAtEpochMillis = timeControlMinutes?.let {
                        nowEpochMillis()
                    }
                    uiState = uiState.copy(isRestoring = false)
                    persistCurrentSession()
                }

                LoadGameSessionResult.Incompatible -> rejectStoredSession(repository)

                is LoadGameSessionResult.Loaded -> {
                    if (
                        result.snapshot.mode != mode ||
                        (
                            mode != StoredGameMode.STREAK_CHALLENGE &&
                                (
                                    result.snapshot.difficulty != difficulty ||
                                        result.snapshot.sessionVariantId != sessionVariantId
                                    )
                            )
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
        val restoredStreakState = if (mode == StoredGameMode.STREAK_CHALLENGE) {
            try {
                StreakChallengeStateCodec.decode(snapshot.sessionVariantId)
            } catch (_: IllegalArgumentException) {
                rejectStoredSession(repository)
                return
            }
        } else {
            null
        }
        try {
            when (activeEngine.restore(snapshot.engineState)) {
                RestoreResult.Restored -> {
                    val restoredResult =
                        snapshot.resultOverride ?: activeEngine.gameResult()
                    if (
                        mode == StoredGameMode.STREAK_CHALLENGE &&
                        restoredResult == GameResult.ONGOING &&
                        restoredStreakState?.nextDifficulty != null
                    ) {
                        rejectStoredSession(repository)
                        return
                    }
                    if (mode == StoredGameMode.STREAK_CHALLENGE) {
                        difficulty = requireNotNull(snapshot.difficulty)
                        streakState = requireNotNull(restoredStreakState)
                        sessionVariantId = snapshot.sessionVariantId
                    }
                    matchId = snapshot.sessionId
                        .takeIf(::isValidMatchId)
                        ?: createMatchId()
                    settlementRequested = false
                    terminalHandled =
                        isAutoPlay &&
                            (
                                snapshot.resultOverride != null ||
                                    activeEngine.gameResult() != GameResult.ONGOING
                                )
                    acceptedMoveCount = snapshot.acceptedMoveCount
                    undoUseCount = snapshot.undoUseCount
                    hintUseCount = snapshot.hintUseCount
                    resultOverride = snapshot.resultOverride
                    timeControlMinutes = snapshot.timeControlMinutes
                    redRemainingMillis = snapshot.redRemainingMillis
                    blackRemainingMillis = snapshot.blackRemainingMillis
                    turnStartedAtEpochMillis = snapshot.turnStartedAtEpochMillis
                    pendingDrawOfferSide = snapshot.pendingDrawOfferSide
                    autoPlayPaused = snapshot.autoPlayPaused
                    autoPlaySpeedPermille = snapshot.autoPlaySpeedPermille
                    completedAutoGames = snapshot.completedAutoGames
                    refresh(ChineseChessFeedback.GAME_RESTORED)
                    synchronizeClock()
                    resumeRuntimeWork()
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
            resetStreakSeriesState()
            resetPosition(activeEngine)
            resetSessionState(resetAutoGameCount = true)
            refresh(ChineseChessFeedback.RESTORE_REJECTED)
            persistCurrentSession()
        }
    }

    private fun persistCurrentSession() {
        val repository = sessionRepository
        if (repository == null) {
            resumeRuntimeWork()
            return
        }
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
                resumeRuntimeWork()
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
            val now = nowEpochMillis()
            val movingSide = uiState.currentSide
            val isCapture = uiState.pieceAt(move.to) != null
            if (!commitActiveClock(now)) return@runEngineOperation
            when (val result = activeEngine.apply(move)) {
                ActionResult.Accepted -> {
                    acceptedMoveCount++
                    val engineResult = activeEngine.gameResult()
                    if (
                        mode == StoredGameMode.ENDGAME &&
                        movingSide == ChineseChessSide.RED &&
                        engineResult == GameResult.ONGOING &&
                        endgamePlayerMoveCount() >= requireNotNull(endgameMaxPlayerMoves)
                    ) {
                        resultOverride = GameResult.SECOND_PLAYER_WIN
                    }
                    startClockForCurrentTurn(activeEngine, now)
                    if (
                        pendingDrawOfferSide != null &&
                        pendingDrawOfferSide != movingSide
                    ) {
                        pendingDrawOfferSide = null
                    }
                    refresh()
                    val resolvedResult = resultOverride ?: engineResult
                    emitMoveSound(resolvedResult, isCapture)
                    if (
                        isHumanControlledAiGame &&
                        activeEngine.currentPlayer.value == ChineseChessSide.BLACK.code &&
                        resolvedResult == GameResult.ONGOING
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
            if (result != GameResult.ONGOING) {
                turnStartedAtEpochMillis = null
                pendingDrawOfferSide = null
            }
            val now = nowEpochMillis()
            val currentSide = when (activeEngine.currentPlayer.value) {
                ChineseChessSide.RED.code -> ChineseChessSide.RED
                ChineseChessSide.BLACK.code -> ChineseChessSide.BLACK
                else -> error("Engine returned an unsupported player")
            }
            handleAutoPlayTerminal(result)
            requestSettlement(result)
            requestCompletedRecord(result, activeEngine)
            handleStreakTerminal(result)
            val undoRemaining = assistancePolicy.undoRemaining(undoUseCount)
            val hintRemaining = assistancePolicy.hintRemaining(hintUseCount)
            uiState = ChineseChessGameUiState(
                board = board,
                currentSide = currentSide,
                result = result,
                canUndo =
                    !isAutoPlay &&
                    acceptedMoveCount > 0 &&
                        result == GameResult.ONGOING &&
                        (undoRemaining == null || undoRemaining > 0),
                undoRemaining = undoRemaining,
                canRequestHint =
                    !isAutoPlay &&
                    result == GameResult.ONGOING &&
                        assistancePolicy.hintMode != ChineseChessHintMode.NONE &&
                        (hintRemaining == null || hintRemaining > 0),
                hintRemaining = hintRemaining,
                isAiGame = isAiGame,
                isAutoPlay = isAutoPlay,
                isAutoPlayPaused = autoPlayPaused,
                autoPlaySpeed = autoPlaySpeedPermille / 1_000f,
                completedAutoGames = completedAutoGames,
                autoContinueGameLimit = autoContinueGameLimit,
                difficulty = difficulty,
                isEndgame = mode == StoredGameMode.ENDGAME,
                isCustomPosition = mode == StoredGameMode.CUSTOM_POSITION,
                isTimedChallenge = mode == StoredGameMode.TIMED_CHALLENGE,
                perMoveTimeLimitSeconds = perMoveTimeLimitSeconds,
                isStreakChallenge = mode == StoredGameMode.STREAK_CHALLENGE,
                currentStreak = streakState?.currentStreak ?: 0,
                bestStreak = streakState?.bestStreak ?: 0,
                streakNextDifficulty = streakState?.nextDifficulty,
                endgameTitle = endgameTitle,
                endgamePlayerMovesUsed = endgamePlayerMoveCount(),
                endgameMaxPlayerMoves = endgameMaxPlayerMoves,
                timeControlMinutes = timeControlMinutes,
                redRemainingMillis = remainingFor(
                    ChineseChessSide.RED,
                    now,
                    currentSide,
                ),
                blackRemainingMillis = remainingFor(
                    ChineseChessSide.BLACK,
                    now,
                    currentSide,
                ),
                pendingDrawOfferSide = pendingDrawOfferSide,
                canOfferOrAcceptDraw =
                    !isAutoPlay &&
                        mode != StoredGameMode.ENDGAME &&
                        result == GameResult.ONGOING,
                feedback = feedback,
            )
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
            } catch (_: CancellationException) {
                return@launch
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
        if (
            isAutoPlay &&
            (autoPlayPaused || automationJob?.isActive == true)
        ) {
            return
        }
        val aiEngine = activeEngine as? ChineseChessAiEngine
        val selectedDifficulty = difficulty
        if (aiEngine == null || selectedDifficulty == null) {
            disableEngine()
            return
        }
        val humanSnapshot = if (
            saveHumanPosition && isHumanControlledAiGame
        ) {
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
        val job = viewModelScope.launch {
            humanSnapshot?.let { snapshot ->
                try {
                    sessionRepository?.save(snapshot)
                } catch (_: RuntimeException) {
                    // The post-AI snapshot retries persistence for the full turn.
                }
            }
            if (engine !== activeEngine) return@launch
            uiState = uiState.copy(isPersisting = false)
            if (isAutoPlay) {
                delay(autoPlayDelayMillis())
                if (autoPlayPaused || engine !== activeEngine) {
                    automationJob = null
                    uiState = uiState.copy(isAiThinking = false)
                    persistCurrentSession()
                    return@launch
                }
            }
            val aiDeclinedDraw =
                isHumanControlledAiGame &&
                    mode != StoredGameMode.ENDGAME &&
                    pendingDrawOfferSide == ChineseChessSide.RED &&
                    activeEngine.currentPlayer.value == ChineseChessSide.BLACK.code
            if (aiDeclinedDraw) {
                pendingDrawOfferSide = null
            }
            val move = try {
                withContext(aiDispatcher) {
                    aiEngine.chooseMove(selectedDifficulty)
                }
            } catch (_: CancellationException) {
                return@launch
            } catch (_: RuntimeException) {
                disableEngine()
                return@launch
            } catch (_: LinkageError) {
                disableEngine()
                return@launch
            }
            if (engine !== activeEngine) return@launch
            if (isAutoPlay && autoPlayPaused) {
                automationJob = null
                uiState = uiState.copy(isAiThinking = false)
                persistCurrentSession()
                return@launch
            }
            val now = nowEpochMillis()
            if (!commitActiveClock(now)) return@launch
            if (move == null) {
                val feedback = if (activeEngine.gameResult() == GameResult.ONGOING) {
                    ChineseChessFeedback.AI_MOVE_FAILED
                } else {
                    null
                }
                if (isAutoPlay && feedback != null) {
                    autoPlayPaused = true
                    automationJob = null
                }
                refresh(feedback)
                persistCurrentSession()
                return@launch
            }
            when (activeEngine.apply(move)) {
                ActionResult.Accepted -> {
                    val isCapture = uiState.pieceAt(move.to) != null
                    acceptedMoveCount++
                    startClockForCurrentTurn(activeEngine, now)
                    refresh(
                        if (aiDeclinedDraw) {
                            ChineseChessFeedback.DRAW_DECLINED
                        } else {
                            ChineseChessFeedback.AI_MOVED
                        },
                    )
                    emitMoveSound(activeEngine.gameResult(), isCapture)
                    if (isAutoPlay) {
                        automationJob = null
                    }
                    persistCurrentSession()
                }

                is ActionResult.Rejected -> {
                    if (isAutoPlay) {
                        autoPlayPaused = true
                        automationJob = null
                    }
                    refresh(ChineseChessFeedback.AI_MOVE_FAILED)
                    persistCurrentSession()
                }
            }
        }
        if (isAutoPlay) {
            automationJob = job
        }
    }

    private fun resumeRuntimeWork() {
        val activeEngine = engine ?: return
        if (
            uiState.isRestoring ||
            uiState.isPersisting ||
            !uiState.isEngineAvailable
        ) {
            return
        }
        ensureClockTicker()
        if (uiState.result != GameResult.ONGOING) {
            scheduleAutoContinue(activeEngine)
            return
        }
        when {
            isAutoPlay && !autoPlayPaused -> {
                startAiTurn(activeEngine, saveHumanPosition = false)
            }

            isHumanControlledAiGame &&
                activeEngine.currentPlayer.value == ChineseChessSide.BLACK.code -> {
                startAiTurn(activeEngine, saveHumanPosition = false)
            }
        }
    }

    private fun ensureClockTicker() {
        val interval = clockTickIntervalMillis ?: return
        if (
            timeControlMinutes == null ||
            interval <= 0L ||
            clockJob?.isActive == true
        ) {
            return
        }
        clockJob = viewModelScope.launch {
            while (true) {
                delay(interval)
                synchronizeClock()
            }
        }
    }

    private fun commitActiveClock(now: Long): Boolean {
        if (timeControlMinutes == null || uiState.result != GameResult.ONGOING) {
            return uiState.result == GameResult.ONGOING
        }
        val side = uiState.currentSide
        val remaining = remainingFor(side, now, side) ?: return true
        setRemaining(side, remaining)
        if (remaining <= 0L) {
            expireActiveClock(now)
            return false
        }
        turnStartedAtEpochMillis = now
        return true
    }

    private fun expireActiveClock(now: Long) {
        if (uiState.result != GameResult.ONGOING) return
        val expiredSide = uiState.currentSide
        setRemaining(expiredSide, 0L)
        turnStartedAtEpochMillis = null
        pendingDrawOfferSide = null
        resultOverride = if (expiredSide == ChineseChessSide.RED) {
            GameResult.SECOND_PLAYER_WIN
        } else {
            GameResult.FIRST_PLAYER_WIN
        }
        refresh(ChineseChessFeedback.TIME_EXPIRED)
        emitTerminalSound(requireNotNull(resultOverride))
        if (isAutoPlay) {
            automationJob = null
        }
        persistCurrentSession()
    }

    private fun remainingFor(
        side: ChineseChessSide,
        now: Long,
        activeSide: ChineseChessSide = uiState.currentSide,
    ): Long? {
        val stored = when (side) {
            ChineseChessSide.RED -> redRemainingMillis
            ChineseChessSide.BLACK -> blackRemainingMillis
        } ?: return null
        val startedAt = turnStartedAtEpochMillis
        if (
            side != activeSide ||
            startedAt == null ||
            resultOverride != null
        ) {
            return stored
        }
        val elapsed = (now - startedAt).coerceAtLeast(0L)
        return (stored - elapsed).coerceAtLeast(0L)
    }

    private fun setRemaining(side: ChineseChessSide, millis: Long) {
        if (side == ChineseChessSide.RED) {
            redRemainingMillis = millis
        } else {
            blackRemainingMillis = millis
        }
    }

    private fun startClockForCurrentTurn(
        activeEngine: ChineseChessRuleEngine,
        now: Long,
    ) {
        if (timeControlMinutes == null) {
            turnStartedAtEpochMillis = null
            return
        }
        perMoveTimeLimitMillis?.let { limit ->
            val side = when (activeEngine.currentPlayer.value) {
                ChineseChessSide.RED.code -> ChineseChessSide.RED
                ChineseChessSide.BLACK.code -> ChineseChessSide.BLACK
                else -> error("Engine returned an unsupported player")
            }
            setRemaining(side, limit)
        }
        turnStartedAtEpochMillis = now
    }

    private fun emitMoveSound(result: GameResult, isCapture: Boolean) {
        if (result == GameResult.ONGOING) {
            emitSound(
                if (isCapture) {
                    ChineseChessSoundCue.CAPTURE
                } else {
                    ChineseChessSoundCue.MOVE
                },
            )
        } else {
            emitTerminalSound(result)
        }
    }

    private fun emitTerminalSound(result: GameResult) {
        val cue = when (result) {
            GameResult.ONGOING -> return
            GameResult.DRAW -> ChineseChessSoundCue.DRAW
            GameResult.FIRST_PLAYER_WIN -> ChineseChessSoundCue.VICTORY
            GameResult.SECOND_PLAYER_WIN -> {
                if (isHumanControlledAiGame) {
                    ChineseChessSoundCue.DEFEAT
                } else {
                    ChineseChessSoundCue.VICTORY
                }
            }
        }
        emitSound(cue)
    }

    private fun emitSound(cue: ChineseChessSoundCue) {
        mutableSoundEvents.tryEmit(cue)
    }

    private fun handleAutoPlayTerminal(result: GameResult) {
        if (!isAutoPlay || result == GameResult.ONGOING || terminalHandled) return
        terminalHandled = true
        completedAutoGames++
        if (
            !autoContinueEnabled ||
            completedAutoGames >= autoContinueGameLimit
        ) {
            autoPlayPaused = true
        }
    }

    private fun handleStreakTerminal(result: GameResult) {
        if (
            mode != StoredGameMode.STREAK_CHALLENGE ||
            result == GameResult.ONGOING
        ) {
            return
        }
        val active = streakState ?: return
        if (active.nextDifficulty != null) return
        val completed = active.complete(result, requireNotNull(difficulty))
        streakState = completed
        sessionVariantId = StreakChallengeStateCodec.encode(completed)
    }

    private fun resetStreakSeriesState(): Boolean {
        if (mode != StoredGameMode.STREAK_CHALLENGE) return false
        val initial = StreakChallengeState()
        if (streakState == initial) return false
        streakState = initial
        sessionVariantId = StreakChallengeStateCodec.encode(initial)
        return true
    }

    private fun scheduleAutoContinue(activeEngine: ChineseChessRuleEngine) {
        if (
            !isAutoPlay ||
            autoPlayPaused ||
            !autoContinueEnabled ||
            completedAutoGames >= autoContinueGameLimit ||
            automationJob?.isActive == true
        ) {
            return
        }
        val job = viewModelScope.launch {
            delay(autoPlayDelayMillis())
            if (
                engine !== activeEngine ||
                autoPlayPaused ||
                uiState.result == GameResult.ONGOING
            ) {
                return@launch
            }
            automationJob = null
            activeEngine.reset()
            resetSessionState(resetAutoGameCount = false)
            refresh(ChineseChessFeedback.GAME_RESTARTED)
            persistCurrentSession()
        }
        automationJob = job
    }

    private fun resetSessionState(resetAutoGameCount: Boolean) {
        automationJob?.cancel()
        automationJob = null
        matchId = createMatchId()
        settlementRequested = false
        terminalHandled = false
        recordRequested = false
        acceptedMoveCount = 0
        undoUseCount = 0
        hintUseCount = 0
        resultOverride = null
        pendingDrawOfferSide = null
        if (resetAutoGameCount) {
            completedAutoGames = 0
            autoPlayPaused = false
        }
        redRemainingMillis = initialClockMillis
        blackRemainingMillis = initialClockMillis
        turnStartedAtEpochMillis = initialClockMillis?.let { nowEpochMillis() }
    }

    private fun canRunControl(): Boolean =
        uiState.isEngineAvailable &&
            !uiState.isRestoring &&
            !uiState.isPersisting &&
            !uiState.isAiThinking &&
            !uiState.isHintThinking

    private fun autoPlayDelayMillis(): Long =
        (BASE_AUTO_PLAY_DELAY_MILLIS * 1_000L / autoPlaySpeedPermille)
            .coerceAtLeast(MIN_AUTO_PLAY_DELAY_MILLIS)

    private fun Int.toClockMillis(): Long = this * 60_000L

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
            timeControlMinutes = timeControlMinutes,
            redRemainingMillis = redRemainingMillis,
            blackRemainingMillis = blackRemainingMillis,
            turnStartedAtEpochMillis = turnStartedAtEpochMillis,
            pendingDrawOfferSide = pendingDrawOfferSide,
            autoPlayPaused = autoPlayPaused,
            autoPlaySpeedPermille = autoPlaySpeedPermille,
            completedAutoGames = completedAutoGames,
            sessionVariantId = sessionVariantId,
        )

    private fun requestSettlement(result: GameResult) {
        if (
            mode != StoredGameMode.HUMAN_VS_AI ||
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

    private fun resetPosition(activeEngine: ChineseChessRuleEngine) {
        val initial = initialPositionState
        if (initial == null) {
            activeEngine.reset()
        } else {
            require(activeEngine.restore(initial) is RestoreResult.Restored) {
                "Initial Chinese chess position became incompatible"
            }
        }
    }

    private fun endgamePlayerMoveCount(): Int = (acceptedMoveCount + 1) / 2

    private fun requestCompletedRecord(
        result: GameResult,
        activeEngine: ChineseChessRuleEngine,
    ) {
        if (result == GameResult.ONGOING || recordRequested) return
        val state = try {
            activeEngine.serialize()
        } catch (_: RuntimeException) {
            return
        } catch (_: LinkageError) {
            return
        }
        val record = try {
            GameRecord(
                recordId = matchId,
                gameType = GameType.CHINESE_CHESS,
                mode = mode,
                difficulty = difficulty,
                result = result,
                engineState = state,
                moveCount = acceptedMoveCount,
                isEndgame = mode == StoredGameMode.ENDGAME,
                completedAtEpochMillis = nowEpochMillis(),
            )
        } catch (_: IllegalArgumentException) {
            return
        }
        try {
            onGameRecorded(record)
            recordRequested = true
        } catch (_: RuntimeException) {
            // A record storage failure must not invalidate the completed game.
        }
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
        private const val CLOCK_TICK_MILLIS = 250L
        private const val BASE_AUTO_PLAY_DELAY_MILLIS = 1_000L
        private const val MIN_AUTO_PLAY_DELAY_MILLIS = 125L
        private const val DEFAULT_AUTO_PLAY_SPEED = 1_000
        private const val MIN_AUTO_PLAY_SPEED = 0.5f
        private const val MAX_AUTO_PLAY_SPEED = 4f
        private const val SOUND_EVENT_BUFFER_CAPACITY = 8
        private const val MAX_SESSION_VARIANT_ID_LENGTH = 320
        private const val MAX_ENDGAME_PLAYER_MOVES = 100

        internal fun factory(
            repository: GameSessionRepository,
            mode: StoredGameMode = StoredGameMode.LOCAL_TWO_PLAYER,
            difficulty: Difficulty? = null,
            onMatchFinished: (MatchOutcome) -> Unit = {},
            onGameRecorded: (GameRecord) -> Unit = {},
            timeControlMinutes: Int? = null,
            perMoveTimeLimitSeconds: Int? = null,
            autoContinueEnabled: Boolean = false,
            autoContinueGameLimit: Int = 10,
            initialPositionState: ByteArray? = null,
            sessionVariantId: String = "",
            streakState: StreakChallengeState? = null,
            endgameTitle: String? = null,
            endgameMaxPlayerMoves: Int? = null,
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
                        onGameRecorded = onGameRecorded,
                        initialTimeControlMinutes = timeControlMinutes,
                        perMoveTimeLimitSeconds = perMoveTimeLimitSeconds,
                        autoContinueEnabled = autoContinueEnabled,
                        autoContinueGameLimit = autoContinueGameLimit,
                        initialPositionState = initialPositionState,
                        sessionVariantId = sessionVariantId,
                        initialStreakState = streakState,
                        endgameTitle = endgameTitle,
                        endgameMaxPlayerMoves = endgameMaxPlayerMoves,
                        engineFactory = engineFactory,
                    )
                }
            }
    }
}
