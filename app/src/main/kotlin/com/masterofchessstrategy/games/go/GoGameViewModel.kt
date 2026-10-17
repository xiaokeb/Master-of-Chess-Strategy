package com.masterofchessstrategy.games.go

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.masterofchessstrategy.engine.ActionResult
import com.masterofchessstrategy.engine.BoardPosition
import com.masterofchessstrategy.engine.GoAction
import com.masterofchessstrategy.engine.GoPhase
import com.masterofchessstrategy.engine.GoPlayer
import com.masterofchessstrategy.engine.GoRuleEngine
import com.masterofchessstrategy.engine.GoRecordEngine
import com.masterofchessstrategy.engine.NativeGoEngine
import com.masterofchessstrategy.engine.RestoreResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Serializes native actions and their durable save before accepting another touch. */
class GoGameViewModel(
    private val repository: GoSessionRepository,
    private val initialSize: Int = 19,
    private val initialHandicap: Int = 0,
    private val engineFactory: (Int, Int, Int) -> GoRuleEngine = { size, komi, handicap ->
        NativeGoEngine(size, komi, handicap)
    },
    private val recordDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {
    private val mutex = Mutex()
    private var engine: GoRuleEngine? = null
    private var identity = GoSessionIdentity.create()
    private var disposed = false
    private val mutableState = MutableStateFlow(GoGameUiState(boardSize = initialSize))
    val state: StateFlow<GoGameUiState> = mutableState

    init {
        require(initialSize in setOf(9, 13, 19))
        require(initialHandicap == 0 || initialHandicap in 2..9)
        viewModelScope.launch {
            mutex.withLock {
                var candidate: GoRuleEngine? = null
                try {
                    val saved = repository.load()
                    candidate = engineFactory(saved?.boardSize ?: initialSize,
                        if (saved == null && initialHandicap > 0) 0 else 15,
                        if (saved == null) initialHandicap else 0)
                    if (saved != null) {
                        check(candidate.restore(saved.engineState) == RestoreResult.Restored) {
                            "围棋存档中的规则状态无效"
                        }
                        identity = saved.identity
                    }
                    engine = candidate
                    candidate = null
                    if (saved == null) {
                        persist(checkNotNull(engine), mode = GoScoreDisplayMode.STONES)
                    } else {
                        mutableState.value = snapshot(
                            engine = checkNotNull(engine),
                            mode = saved.displayMode,
                            lastMove = saved.lastMoveIndex,
                        )
                    }
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    mutableState.value = mutableState.value.copy(
                        loading = false,
                        error = error.message ?: "无法打开围棋对局",
                    )
                } finally {
                    candidate?.close()
                }
            }
        }
    }

    fun play(x: Int, y: Int) {
        val current = mutableState.value
        val action = if (current.phase == GoPhase.SCORING) {
            GoAction.ToggleDeadGroup(BoardPosition(x, y), current.scoringActor)
        } else {
            GoAction.Place(BoardPosition(x, y))
        }
        perform(action, if (current.phase == GoPhase.PLAYING) y * current.boardSize + x
            else current.lastMoveIndex)
    }

    fun pass() = perform(GoAction.Pass)

    fun confirm(actor: GoPlayer) = perform(
        GoAction.ConfirmScore(actor, mutableState.value.proposalRevision),
    )

    fun resume(actor: GoPlayer) = perform(GoAction.ResumePlay(actor))

    fun undo() {
        if (!mutableState.value.canOperate) return
        viewModelScope.launch {
            mutex.withLock {
                val current = engine ?: return@withLock
                if (!mutableState.value.canOperate) return@withLock
                val nextIdentity = if (current.phase == GoPhase.FINISHED) {
                    try { identity.afterTerminalUndo() } catch (error: IllegalStateException) {
                        mutableState.value = mutableState.value.copy(error = error.message)
                        return@withLock
                    }
                } else identity
                if (!current.undo()) return@withLock
                identity = nextIdentity
                persist(current, lastMove = null)
            }
        }
    }

    fun selectScoringActor(actor: GoPlayer) {
        if (!mutableState.value.canOperate) return
        mutableState.value = mutableState.value.copy(scoringActor = actor)
    }

    fun toggleLegalPoints() {
        if (!mutableState.value.canOperate) return
        mutableState.value = mutableState.value.copy(showLegalPoints = !mutableState.value.showLegalPoints)
    }

    fun toggleScoreMode() {
        viewModelScope.launch {
            mutex.withLock {
                val current = engine ?: return@withLock
                if (!mutableState.value.canOperate) return@withLock
                val mode = if (mutableState.value.displayMode == GoScoreDisplayMode.STONES) {
                    GoScoreDisplayMode.POINTS
                } else GoScoreDisplayMode.STONES
                persist(current, mode = mode)
            }
        }
    }

    fun retrySave() {
        if (mutableState.value.importing) return
        viewModelScope.launch {
            mutex.withLock { engine?.let { persist(it) } }
        }
    }

    fun newGame(size: Int, handicap: Int) {
        if (mutableState.value.loading || mutableState.value.saving ||
            mutableState.value.importing || mutableState.value.needsSave) return
        viewModelScope.launch {
            mutex.withLock {
                if (mutableState.value.loading || mutableState.value.saving ||
                    mutableState.value.importing || mutableState.value.needsSave) return@withLock
                val previous = mutableState.value
                mutableState.value = previous.copy(saving = true, error = null)
                var candidate: GoRuleEngine? = null
                try {
                    candidate = engineFactory(size, if (handicap == 0) 15 else 0, handicap)
                    val next = snapshot(candidate, GoScoreDisplayMode.STONES).copy(
                        scoringActor = GoPlayer.BLACK, showLegalPoints = false,
                    )
                    val nextIdentity = GoSessionIdentity.create(GoSessionOrigin.LOCAL)
                    repository.save(GoSavedSession(size, candidate.serialize(), GoScoreDisplayMode.STONES,
                        identity = nextIdentity))
                    identity = nextIdentity
                    engine?.close()
                    engine = candidate
                    candidate = null
                    mutableState.value = next
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    mutableState.value = previous.copy(
                        saving = false, error = error.message ?: "无法开始新对局",
                    )
                } finally {
                    candidate?.close()
                }
            }
        }
    }

    suspend fun exportSgf(): String {
        check(mutableState.value.canOperate) { "请等待围棋对局保存完成" }
        return mutex.withLock {
            check(mutableState.value.canOperate) { "请等待围棋对局保存完成" }
            val records = engine as? GoRecordEngine ?: error("当前围棋引擎不支持 SGF")
            records.exportSgf().also { GoSgfDocument.encode(it) }
        }
    }

    fun importSgf(text: String) {
        val previous = mutableState.value
        if (!previous.canOperate || !previous.recordAvailable) return
        mutableState.value = previous.copy(importing = true, error = null)
        viewModelScope.launch {
            mutex.withLock {
                var candidate: GoRecordEngine? = null
                try {
                    GoSgfDocument.encode(text)
                    withContext(recordDispatcher) {
                        for (size in listOf(9, 13, 19)) {
                            val attempt = engineFactory(size, 15, 0)
                            if (attempt !is GoRecordEngine) {
                                attempt.close()
                                error("当前围棋引擎不支持 SGF")
                            }
                            candidate = attempt
                            if (attempt.restoreSgf(text) == RestoreResult.Restored) break
                            attempt.close()
                            candidate = null
                        }
                    }
                    val imported = checkNotNull(candidate) { "SGF 棋谱无效或不支持该主线" }
                    val next = snapshot(imported, previous.displayMode).copy(showLegalPoints = false)
                    val nextIdentity = GoSessionIdentity.create(GoSessionOrigin.SGF_IMPORT)
                    // Finish the save/swap even if the page closes during the AtomicFile write.
                    withContext(NonCancellable) {
                        repository.save(GoSavedSession(imported.boardSize, imported.serialize(),
                            previous.displayMode, next.lastMoveIndex, nextIdentity))
                        identity = nextIdentity
                        engine?.close()
                        if (disposed) imported.close() else {
                            engine = imported
                            mutableState.value = next
                        }
                        candidate = null
                    }
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    if (!disposed) mutableState.value = previous.copy(
                        error = error.message ?: "无法导入 SGF 棋谱",
                    )
                } finally {
                    candidate?.close()
                }
            }
        }
    }

    private fun perform(action: GoAction, lastMove: Int? = mutableState.value.lastMoveIndex) {
        if (!mutableState.value.canAct) return
        viewModelScope.launch {
            mutex.withLock {
                val current = engine ?: return@withLock
                if (!mutableState.value.canAct) return@withLock
                when (current.apply(action)) {
                    ActionResult.Accepted -> persist(current, lastMove = lastMove)
                    is ActionResult.Rejected -> {
                        mutableState.value = mutableState.value.copy(error = "该操作不符合当前围棋规则")
                    }
                }
            }
        }
    }

    private suspend fun persist(
        current: GoRuleEngine,
        mode: GoScoreDisplayMode = mutableState.value.displayMode,
        lastMove: Int? = mutableState.value.lastMoveIndex,
    ) {
        val proposed = snapshot(current, mode, lastMove).copy(saving = true)
        mutableState.value = proposed
        try {
            repository.save(GoSavedSession(current.boardSize, current.serialize(), mode, proposed.lastMoveIndex, identity))
            mutableState.value = proposed.copy(saving = false)
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            mutableState.value = proposed.copy(
                saving = false, needsSave = true,
                error = error.message ?: "围棋对局保存失败，请重试",
            )
        }
    }

    private fun snapshot(
        engine: GoRuleEngine,
        mode: GoScoreDisplayMode,
        lastMove: Int? = null,
    ): GoGameUiState {
        val size = engine.boardSize
        val scoring = engine.scoringState()
        return GoGameUiState(
            boardSize = size,
            handicapStones = engine.handicapStones,
            stones = List(size * size) { index ->
                engine.stoneAt(BoardPosition(index % size, index / size)).code
            },
            currentPlayer = GoPlayer.entries[engine.currentPlayer.value],
            phase = scoring.phase,
            result = engine.gameResult(),
            legalPoints = engine.legalActions().filterIsInstance<GoAction.Place>()
                .map { it.point.y * size + it.point.x }.toSet(),
            showLegalPoints = mutableState.value.showLegalPoints,
            deadStoneIndices = scoring.deadStones.map { it.y * size + it.x }.toSet(),
            lastMoveIndex = if (engine is GoRecordEngine) {
                engine.eventHistory().filterIsInstance<GoAction.Place>().lastOrNull()?.point
                    ?.let { it.y * size + it.x }
            } else lastMove,
            proposalRevision = scoring.proposalRevision,
            blackConfirmed = scoring.blackConfirmed,
            whiteConfirmed = scoring.whiteConfirmed,
            previewScore = if (scoring.phase == GoPhase.FINISHED) engine.finalScore()
                else engine.previewScore(),
            displayMode = mode,
            scoringActor = mutableState.value.scoringActor,
            sessionReady = true,
            recordAvailable = engine is GoRecordEngine,
            loading = false,
        )
    }

    override fun onCleared() {
        disposed = true
        engine?.close()
        engine = null
        super.onCleared()
    }
}
