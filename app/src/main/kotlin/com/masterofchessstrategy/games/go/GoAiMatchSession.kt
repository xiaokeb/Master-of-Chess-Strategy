package com.masterofchessstrategy.games.go

import androidx.annotation.MainThread
import com.masterofchessstrategy.engine.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

data class GoAiHint(val highlightedPoints: Set<Int>, val passSuggested: Boolean, val allLegal: Boolean)

data class GoAiMatchUiState(
    val config: GoAiMatchConfig,
    val game: GoGameUiState = GoGameUiState(boardSize = config.boardSize),
    val paused: Boolean = true,
    val thinking: Boolean = false,
    val speed: Float = 1f,
    val undoUsed: Int = 0,
    val closed: Boolean = false,
    val hintUsed: Int = 0,
    val hintThinking: Boolean = false,
    val hint: GoAiHint? = null,
    val completionReserved: Boolean = false,
    val eventCount: Int = 0,
    val rememberedHuman: GoPlayer = config.humanPlayer ?: GoPlayer.BLACK,
    val automaticUsed: Boolean = config.automatic,
    val openingEventCount: Int = 0,
    val hasSpecifiedOpening: Boolean = false,
    val autoContinueEnabled: Boolean = false,
    val autoContinueMaxGames: Int = 1,
    val autoContinueGameNumber: Int = 1,
    val matchId: String = "",
    val completionStored: Boolean = false,
    val completionPending: Boolean = false,
    // Transient page policy, not a new GAI field or a claim about ranked participation.
    val immediateHumanCompletion: Boolean = false,
) {
    val humanCanPlay get() = !closed && game.canAct && game.phase == GoPhase.PLAYING && !config.isAi(game.currentPlayer)
    val canReviewScore get() = !closed && game.canAct && game.phase == GoPhase.SCORING
    val canRequestHint get() = humanCanPlay && !hintThinking && hintUsed < config.difficulty.hintLimit
    val canUndo get() = !closed && game.canOperate && !completionReserved && !completionPending && eventCount > openingEventCount && undoUsed < config.difficulty.undoLimit
    val canSwitchMode get() = !closed && game.canAct && game.phase == GoPhase.PLAYING && !completionReserved
    val canConfigureAutoContinue get() = !closed && config.automatic && game.canAct &&
        game.phase == GoPhase.PLAYING && !completionReserved
    val autoContinuePending get() = autoContinueEnabled && config.automatic && game.phase == GoPhase.FINISHED &&
        autoContinueGameNumber < autoContinueMaxGames
}

/** Control calls use the owner's dispatcher. Native candidates/save/disposal use one worker mutex.
 * Borrows the search engine: caller must await closeAndJoin before releasing the model runtime.
 * A successful checkpoint precedes publishing each move. No AI estimate adjudicates dead stones.
 */
class GoAiMatchSession(
    parentScope: CoroutineScope,
    private val repository: GoAiMatchRepository,
    initialConfig: GoAiMatchConfig,
    searchEngine: GoAiSearchEngine,
    private val worker: CoroutineDispatcher = Dispatchers.Default,
    private val initialOpening: GoAiOpening? = null,
    private val completedSink: GoAiCompletedMatchSink? = null,
    private val completeHumanImmediately: Boolean = false,
    private val engineFactory: (Int, Int, Int) -> GoRecordEngine = { size, komi, handicap -> NativeGoEngine(size, komi, handicap) },
) {
    init { require(!completeHumanImmediately || completedSink != null) { "Immediate completion requires durable storage" } }
    private val lifetime = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + lifetime)
    private val mutex = Mutex()
    private val disposed = AtomicBoolean(false)
    private val stamp = AtomicReference(GoAiPositionStamp(UUID.randomUUID().toString(), 0))
    private val mutableState = MutableStateFlow(GoAiMatchUiState(initialConfig,
        immediateHumanCompletion = completeHumanImmediately))
    val state: StateFlow<GoAiMatchUiState> = mutableState.asStateFlow()
    private val search = GoAiSearchCoordinator(scope, searchEngine, stamp::get, worker)
    private var engine: GoRecordEngine? = null // worker mutex only
    private var matchId = UUID.randomUUID().toString()
    private var checkpoint: ByteArray? = null // owner dispatcher only
    private var durableMatch: GoAiSavedMatch? = null // worker mutex only; exact last acknowledged envelope
    private var opening: GoAiOpening? = null // worker mutex only
    private var advanceJob: Job? = null
    private var hintSearchStamp: GoAiPositionStamp? = null
    private var automaticResumeAllowed = false // Owner-only; explicit/lifecycle pause revokes continuation search.
    private var retirementStarted = false // Owner dispatcher only; sealing never waits for a worker.

    init {
        scope.launch {
            search.state.collect { value ->
                if (disposed.get()) return@collect
                when (value) {
                    is GoAiSearchState.Ready -> {
                        val suggestion = search.consume(stamp.get()) ?: return@collect
                        val current = mutableState.value
                        if (hintSearchStamp == value.stamp && current.humanCanPlay && current.hintThinking) {
                            hintSearchStamp = null
                            completeHint(value.stamp, suggestion.action)
                        } else if (!current.paused && current.game.canAct && current.config.isAi(current.game.currentPlayer)) {
                            commit(suggestion.action, false)
                        }
                    }
                    is GoAiSearchState.Failed -> {
                        hintSearchStamp = null
                        mutableState.value = mutableState.value.copy(thinking = false, hintThinking = false,
                            game = mutableState.value.game.copy(error = value.message))
                    }
                    is GoAiSearchState.Thinking -> mutableState.value = mutableState.value.copy(
                        thinking = hintSearchStamp != value.stamp)
                    GoAiSearchState.Idle -> {
                        if (hintSearchStamp != null) {
                            hintSearchStamp = null
                            mutableState.value = mutableState.value.copy(hintThinking = false)
                        }
                        mutableState.value = mutableState.value.copy(thinking = false)
                    }
                }
            }
        }
        scope.launch {
            var loadedBytes: ByteArray? = null
            var loadedState: GoAiMatchUiState? = null
            withContext(worker + NonCancellable) {
                mutex.withLock {
                    var candidate: GoRecordEngine? = null
                    try {
                        if (disposed.get() || !lifetime.isActive) return@withLock
                        val saved = repository.load()
                        // A read already in flight may drain, but it must not create a new
                        // match or native owner after the page has retired while awaiting it.
                        if (disposed.get() || !lifetime.isActive) return@withLock
                        durableMatch = saved
                        val config = saved?.config ?: initialConfig
                        val selectedOpening = if (saved != null) saved.copyOpeningBytes()?.let {
                            GoAiOpening.fromSnapshot(it, engineFactory)
                        } else initialOpening
                        if (selectedOpening != null) check(selectedOpening.config(config.humanPlayer, config.difficulty) == config)
                        candidate = if (saved == null && selectedOpening != null) selectedOpening.createEngine(engineFactory)
                            else engineFactory(config.boardSize, if (config.handicapStones == 0) 15 else 0, config.handicapStones)
                        if (saved != null) check(candidate.restore(saved.copyBytes()) == RestoreResult.Restored) { "Invalid AI checkpoint" }
                        check(candidate.boardSize == config.boardSize && candidate.handicapStones == config.handicapStones &&
                            candidate.blackKomiQuarters == if (config.handicapStones == 0) 15 else 0)
                        selectedOpening?.checkContinuation(candidate)
                        var game = snapshot(candidate)
                        val eventCount = candidate.eventHistory().size
                        val openingEventCount = selectedOpening?.eventCount ?: 0
                        check(saved?.completionReserved != true || (game.phase == GoPhase.FINISHED && game.result != GameResult.ONGOING))
                        val bytes = candidate.serialize()
                        val id = saved?.matchId ?: matchId
                        val undoUsed = saved?.undoUsed ?: 0
                        if (disposed.get() || !lifetime.isActive) return@withLock
                        if (saved == null) persistCheckpoint(GoAiSavedMatch(id, config, bytes, undoUsed,
                            openingBytes = selectedOpening?.copyBytes()), freshStandard =
                            config.humanPlayer != null && config.handicapStones == 0 && selectedOpening == null)
                        var reserved = saved?.completionReserved ?: false
                        var stored = false
                        if (completeHumanImmediately && !config.automatic && game.phase == GoPhase.FINISHED &&
                            !disposed.get() && lifetime.isActive) {
                            val finished = GoAiSavedMatch(id, config, bytes, undoUsed, saved?.hintUsed ?: 0,
                                true, saved?.rememberedHuman ?: checkNotNull(config.humanPlayer),
                                saved?.automaticUsed ?: false, selectedOpening?.copyBytes(),
                                saved?.autoContinueEnabled ?: false, saved?.autoContinueMaxGames ?: 1,
                                saved?.autoContinueGameNumber ?: 1)
                            try {
                                if (!reserved) { persistCheckpoint(finished); reserved = true }
                                if (!disposed.get() && lifetime.isActive) {
                                    checkNotNull(completedSink).storeCompleted(finished)
                                    stored = true
                                }
                            } catch (_: Exception) { game = game.copy(error = HUMAN_COMPLETION_ERROR) }
                        }
                        if (!disposed.get() && lifetime.isActive) {
                            engine = candidate; candidate = null
                            opening = selectedOpening
                            matchId = id
                            loadedBytes = bytes.copyOf()
                            loadedState = GoAiMatchUiState(config, game, undoUsed = undoUsed, hintUsed = saved?.hintUsed ?: 0,
                                immediateHumanCompletion = completeHumanImmediately,
                                completionReserved = reserved, eventCount = eventCount, completionStored = stored,
                                completionPending = completeHumanImmediately && !config.automatic &&
                                    game.phase == GoPhase.FINISHED && !stored,
                                rememberedHuman = saved?.rememberedHuman ?: config.humanPlayer ?: GoPlayer.BLACK,
                                automaticUsed = saved?.automaticUsed ?: config.automatic,
                                openingEventCount = openingEventCount, hasSpecifiedOpening = selectedOpening != null,
                                autoContinueEnabled = saved?.autoContinueEnabled ?: false,
                                autoContinueMaxGames = saved?.autoContinueMaxGames ?: 1,
                                autoContinueGameNumber = saved?.autoContinueGameNumber ?: 1, matchId = id)
                        }
                    } catch (_: Exception) {
                        if (!disposed.get() && lifetime.isActive) mutableState.value = mutableState.value.copy(
                            game = mutableState.value.game.copy(loading = false, error = "围棋人机对局加载或保存失败，请返回后重试。"))
                    } finally { candidate?.close() }
                }
            }
            checkpoint = loadedBytes
            loadedState?.let { if (!disposed.get()) { mutableState.value = it; maybeAutoContinue() } }
            // Every restored or new match starts paused until the foreground owner resumes it.
        }
    }

    fun humanAction(action: GoAction): Boolean {
        if (disposed.get() || !lifetime.isActive) return false
        val current = mutableState.value
        val allowed = when (action) {
            is GoAction.Place, GoAction.Pass -> current.humanCanPlay
            is GoAction.ToggleDeadGroup, is GoAction.ConfirmScore, is GoAction.ResumePlay -> current.canReviewScore
        }
        if (!allowed || !lifetime.isActive) return false
        invalidateSearch()
        commit(action, false)
        return true
    }

    fun undo(): Boolean {
        val current = mutableState.value
        if (disposed.get() || !lifetime.isActive || !current.canUndo) return false
        invalidateSearch()
        commit(null, true)
        return true
    }

    fun restart(completedSink: GoAiCompletedMatchSink? = this.completedSink): Boolean =
        restartInternal(completedSink, automaticContinuation = false)

    fun continueAutomatically(): Boolean {
        if (disposed.get() || !lifetime.isActive) return false
        val current = mutableState.value
        if (!current.autoContinuePending || !current.game.canOperate) return false
        val sink = completedSink ?: run {
            mutableState.value = current.copy(game = current.game.copy(
                error = "完成棋谱保存功能尚未准备好，自动续局已停止。"))
            return false
        }
        return restartInternal(sink, automaticContinuation = true)
    }

    fun retryAutoContinuation(): Boolean {
        if (disposed.get() || !lifetime.isActive) return false
        val current = mutableState.value
        return when {
            current.autoContinuePending -> continueAutomatically()
            current.autoContinueEnabled && current.config.automatic && current.game.phase == GoPhase.FINISHED &&
                current.autoContinueGameNumber >= current.autoContinueMaxGames -> archiveAutoSeriesEnd()
            else -> false
        }
    }

    /** Retries only this fixed result; never creates a replacement match. */
    fun retryCompletion(): Boolean {
        val previous = mutableState.value
        if (!completeHumanImmediately || disposed.get() || !lifetime.isActive || previous.config.automatic ||
            previous.game.phase != GoPhase.FINISHED || !previous.game.canOperate || previous.completionStored) return false
        val sink = completedSink ?: return false
        mutableState.value = previous.copy(paused = true, thinking = false,
            game = previous.game.copy(saving = true, error = null))
        scope.launch {
            var reserved = previous.completionReserved
            var stored = false
            withContext(worker + NonCancellable) {
                mutex.withLock {
                    try {
                        if (disposed.get() || !lifetime.isActive) return@withLock
                        val active = checkNotNull(engine)
                        check(active.phase == GoPhase.FINISHED && active.gameResult() != GameResult.ONGOING)
                        val finished = GoAiSavedMatch(matchId, previous.config, active.serialize(), previous.undoUsed,
                            previous.hintUsed, true, previous.rememberedHuman, previous.automaticUsed,
                            opening?.copyBytes(), previous.autoContinueEnabled, previous.autoContinueMaxGames,
                            previous.autoContinueGameNumber)
                        if (!reserved) { persistCheckpoint(finished); reserved = true }
                        if (disposed.get() || !lifetime.isActive) return@withLock
                        sink.storeCompleted(finished)
                        stored = true
                    } catch (_: Exception) { }
                }
            }
            if (disposed.get()) return@launch
            mutableState.value = mutableState.value.copy(completionReserved = reserved, completionStored = stored,
                completionPending = !stored,
                game = previous.game.copy(error = if (stored) null else HUMAN_COMPLETION_ERROR))
        }
        return true
    }

    private fun restartInternal(completedSink: GoAiCompletedMatchSink?, automaticContinuation: Boolean): Boolean {
        val previous = mutableState.value
        if (disposed.get() || !lifetime.isActive || !previous.game.canOperate) return false
        if (automaticContinuation && !previous.autoContinuePending) return false
        if (previous.game.phase == GoPhase.FINISHED && completedSink == null) {
            mutableState.value = previous.copy(game = previous.game.copy(error = "本局已结束；完成棋谱保存功能准备好后才能重开。"))
            return false
        }
        if (!automaticContinuation) automaticResumeAllowed = false
        invalidateSearch()
        mutableState.value = mutableState.value.copy(paused = true, thinking = false,
            game = previous.game.copy(saving = true, error = null))
        val nextId = UUID.randomUUID().toString()
        val nextAutoEnabled = previous.autoContinueEnabled && previous.config.automatic
        val nextAutoMax = if (nextAutoEnabled) previous.autoContinueMaxGames else 1
        val nextAutoNumber = if (automaticContinuation) previous.autoContinueGameNumber + 1 else 1
        scope.launch {
            // Old synchronous NN work must leave the runtime before a new position becomes ready.
            search.cancelAndJoin()
            var bytes: ByteArray? = null
            var game: GoGameUiState? = null
            var reserved = previous.completionReserved
            var failed = false
            withContext(worker + NonCancellable) {
                mutex.withLock {
                    var candidate: GoRecordEngine? = null
                    try {
                        if (disposed.get() || !lifetime.isActive) return@withLock
                        val active = checkNotNull(engine)
                        val config = previous.config
                        candidate = opening?.createEngine(engineFactory)
                            ?: engineFactory(config.boardSize, if (config.handicapStones == 0) 15 else 0, config.handicapStones)
                        val nextGame = snapshot(candidate)
                        check(candidate.boardSize == config.boardSize && candidate.handicapStones == config.handicapStones &&
                            candidate.blackKomiQuarters == if (config.handicapStones == 0) 15 else 0)
                        check(nextGame.phase == GoPhase.PLAYING && nextGame.result == GameResult.ONGOING &&
                            candidate.eventHistory().size == previous.openingEventCount)
                        if (previous.game.phase == GoPhase.FINISHED) {
                            check(active.phase == GoPhase.FINISHED && active.gameResult() != GameResult.ONGOING)
                            val old = GoAiSavedMatch(matchId, config, active.serialize(), previous.undoUsed, previous.hintUsed, true,
                                previous.rememberedHuman, previous.automaticUsed, opening?.copyBytes(),
                                previous.autoContinueEnabled, previous.autoContinueMaxGames, previous.autoContinueGameNumber)
                            // Seal before touching Room: a failed return or process death cannot allow
                            // undoing an already archived UUID into a different result.
                            if (!reserved) { persistCheckpoint(old); reserved = true }
                            if (disposed.get() || !lifetime.isActive) return@withLock
                            checkNotNull(completedSink).storeCompleted(old)
                        }
                        if (disposed.get() || !lifetime.isActive) return@withLock
                        val nextBytes = candidate.serialize()
                        persistCheckpoint(GoAiSavedMatch(nextId, config, nextBytes, rememberedHuman = previous.rememberedHuman,
                            openingBytes = opening?.copyBytes(), autoContinueEnabled = nextAutoEnabled,
                            autoContinueMaxGames = nextAutoMax, autoContinueGameNumber = nextAutoNumber),
                            freshStandard = config.humanPlayer != null && config.handicapStones == 0 && opening == null)
                        engine?.close(); engine = null
                        if (!disposed.get() && lifetime.isActive) {
                            engine = candidate; candidate = null
                            matchId = nextId
                            bytes = nextBytes.copyOf(); game = nextGame
                        }
                    } catch (_: Exception) { failed = true }
                    finally { candidate?.close() }
                }
            }
            if (disposed.get()) return@launch
            if (bytes != null) {
                checkpoint = bytes
                stamp.set(stamp.get().let { it.copy(revision = it.revision + 1) })
                mutableState.value = GoAiMatchUiState(previous.config, checkNotNull(game),
                    immediateHumanCompletion = completeHumanImmediately,
                    speed = mutableState.value.speed, rememberedHuman = previous.rememberedHuman,
                    eventCount = previous.openingEventCount,
                    openingEventCount = previous.openingEventCount,
                    hasSpecifiedOpening = previous.hasSpecifiedOpening,
                    autoContinueEnabled = nextAutoEnabled, autoContinueMaxGames = nextAutoMax,
                    autoContinueGameNumber = nextAutoNumber, matchId = nextId,
                    paused = !automaticContinuation || !automaticResumeAllowed)
                if (!mutableState.value.paused) scheduleSearch()
            } else {
                mutableState.value = mutableState.value.copy(completionReserved = reserved,
                    game = previous.game.copy(error = if (failed) {
                        if (reserved) "本局结果已固定；棋谱保存或重开未完成，请重试。"
                        else "重开未提交；当前棋盘和辅助次数已保留，请重试。"
                    } else previous.game.error))
            }
        }
        return true
    }

    /** Replace this activity with a new training opening only after the caller confirms its preview. */
    fun replaceOpening(replacement: GoAiOpening, completedSink: GoAiCompletedMatchSink? = null): Boolean {
        val previous = mutableState.value
        if (disposed.get() || !lifetime.isActive || !previous.game.canOperate) return false
        if (previous.game.phase == GoPhase.FINISHED && completedSink == null) {
            mutableState.value = previous.copy(game = previous.game.copy(
                error = "本局已结束；完成棋谱保存功能准备好后才能导入指定开局。"))
            return false
        }
        val nextConfig = replacement.config(previous.config.humanPlayer, previous.config.difficulty)
        automaticResumeAllowed = false
        invalidateSearch()
        mutableState.value = mutableState.value.copy(paused = true, thinking = false,
            game = previous.game.copy(saving = true, error = null))
        val nextId = UUID.randomUUID().toString()
        scope.launch {
            search.cancelAndJoin()
            var bytes: ByteArray? = null
            var game: GoGameUiState? = null
            var reserved = previous.completionReserved
            var failed = false
            withContext(worker + NonCancellable) {
                mutex.withLock {
                    var candidate: GoRecordEngine? = null
                    try {
                        if (disposed.get() || !lifetime.isActive) return@withLock
                        val active = checkNotNull(engine)
                        candidate = replacement.createEngine(engineFactory)
                        val nextGame = snapshot(candidate)
                        check(replacement.config(nextConfig.humanPlayer, nextConfig.difficulty) == nextConfig)
                        check(nextGame.phase == GoPhase.PLAYING && nextGame.result == GameResult.ONGOING &&
                            candidate.eventHistory().size == replacement.eventCount)
                        if (previous.game.phase == GoPhase.FINISHED) {
                            check(active.phase == GoPhase.FINISHED && active.gameResult() != GameResult.ONGOING)
                            val old = GoAiSavedMatch(matchId, previous.config, active.serialize(), previous.undoUsed,
                                previous.hintUsed, true, previous.rememberedHuman, previous.automaticUsed,
                                opening?.copyBytes(), previous.autoContinueEnabled,
                                previous.autoContinueMaxGames, previous.autoContinueGameNumber)
                            if (!reserved) { persistCheckpoint(old); reserved = true }
                            if (disposed.get() || !lifetime.isActive) return@withLock
                            checkNotNull(completedSink).storeCompleted(old)
                        }
                        if (disposed.get() || !lifetime.isActive) return@withLock
                        val nextBytes = candidate.serialize()
                        persistCheckpoint(GoAiSavedMatch(nextId, nextConfig, nextBytes,
                            rememberedHuman = previous.rememberedHuman, openingBytes = replacement.copyBytes(),
                            autoContinueEnabled = previous.autoContinueEnabled && nextConfig.automatic,
                            autoContinueMaxGames = if (previous.autoContinueEnabled && nextConfig.automatic)
                                previous.autoContinueMaxGames else 1))
                        engine?.close(); engine = null
                        if (!disposed.get() && lifetime.isActive) {
                            engine = candidate; candidate = null
                            opening = replacement
                            matchId = nextId
                            bytes = nextBytes.copyOf(); game = nextGame
                        }
                    } catch (_: Exception) { failed = true }
                    finally { candidate?.close() }
                }
            }
            if (disposed.get()) return@launch
            if (bytes != null) {
                checkpoint = bytes
                stamp.set(stamp.get().let { it.copy(revision = it.revision + 1) })
                mutableState.value = GoAiMatchUiState(nextConfig, checkNotNull(game),
                    immediateHumanCompletion = completeHumanImmediately,
                    speed = mutableState.value.speed, rememberedHuman = previous.rememberedHuman,
                    eventCount = replacement.eventCount, openingEventCount = replacement.eventCount,
                    hasSpecifiedOpening = true, autoContinueEnabled = previous.autoContinueEnabled && nextConfig.automatic,
                    autoContinueMaxGames = if (previous.autoContinueEnabled && nextConfig.automatic)
                        previous.autoContinueMaxGames else 1, matchId = nextId)
            } else {
                mutableState.value = mutableState.value.copy(completionReserved = reserved,
                    game = previous.game.copy(error = if (failed) {
                        if (reserved) "本局结果已固定；棋谱保存或指定开局导入未完成，请重试。"
                        else "指定开局未提交；当前棋盘和辅助次数已保留，请重试。"
                    } else previous.game.error))
            }
        }
        return true
    }

    fun setAutomatic(automatic: Boolean): Boolean {
        val previous = mutableState.value
        if (disposed.get() || !lifetime.isActive || !previous.canSwitchMode || previous.config.automatic == automatic) return false
        val config = previous.config.copy(humanPlayer = if (automatic) null else previous.rememberedHuman)
        automaticResumeAllowed = false
        invalidateSearch()
        mutableState.value = mutableState.value.copy(paused = true, thinking = false,
            game = previous.game.copy(saving = true, error = null))
        scope.launch {
            search.cancelAndJoin()
            var committedBytes: ByteArray? = null
            var failed = false
            withContext(worker + NonCancellable) {
                mutex.withLock {
                    try {
                        if (disposed.get() || !lifetime.isActive) return@withLock
                        val active = checkNotNull(engine)
                        check(active.phase == GoPhase.PLAYING)
                        val bytes = active.serialize()
                        persistCheckpoint(GoAiSavedMatch(matchId, config, bytes, previous.undoUsed, previous.hintUsed,
                            previous.completionReserved, previous.rememberedHuman, previous.automaticUsed || automatic,
                            opening?.copyBytes(), previous.autoContinueEnabled && automatic,
                            if (previous.autoContinueEnabled && automatic) previous.autoContinueMaxGames else 1,
                            if (previous.autoContinueEnabled && automatic) previous.autoContinueGameNumber else 1))
                        committedBytes = bytes.copyOf()
                    } catch (_: Exception) { failed = true }
                }
            }
            if (disposed.get()) return@launch
            if (committedBytes != null) {
                checkpoint = committedBytes
                stamp.set(stamp.get().let { it.copy(revision = it.revision + 1) })
                mutableState.value = mutableState.value.copy(config = config, game = previous.game,
                    automaticUsed = previous.automaticUsed || automatic, paused = true, thinking = false,
                    autoContinueEnabled = previous.autoContinueEnabled && automatic,
                    autoContinueMaxGames = if (previous.autoContinueEnabled && automatic)
                        previous.autoContinueMaxGames else 1,
                    autoContinueGameNumber = if (previous.autoContinueEnabled && automatic)
                        previous.autoContinueGameNumber else 1)
            } else {
                mutableState.value = mutableState.value.copy(game = previous.game.copy(
                    error = if (failed) "模式切换未提交；棋盘、执色和辅助次数已保留，请重试。" else previous.game.error))
            }
        }
        return true
    }

    fun configureAutoContinuation(enabled: Boolean, maxGames: Int = 1): Boolean {
        if (disposed.get() || !lifetime.isActive) return false
        val previous = mutableState.value
        val targetMax = if (enabled) maxGames else 1
        val targetNumber = if (enabled && previous.autoContinueEnabled) previous.autoContinueGameNumber else 1
        require(!enabled || maxGames in 2..100)
        if (disposed.get() || !lifetime.isActive || !previous.canConfigureAutoContinue ||
            enabled && (completedSink == null || targetMax < targetNumber) ||
            previous.autoContinueEnabled == enabled && previous.autoContinueMaxGames == targetMax) return false
        automaticResumeAllowed = false
        invalidateSearch()
        mutableState.value = mutableState.value.copy(paused = true, thinking = false,
            game = previous.game.copy(saving = true, error = null))
        scope.launch {
            search.cancelAndJoin()
            var committedBytes: ByteArray? = null
            var failed = false
            withContext(worker + NonCancellable) {
                mutex.withLock {
                    try {
                        if (disposed.get() || !lifetime.isActive) return@withLock
                        val active = checkNotNull(engine)
                        check(active.phase == GoPhase.PLAYING)
                        val bytes = active.serialize()
                        persistCheckpoint(GoAiSavedMatch(matchId, previous.config, bytes, previous.undoUsed,
                            previous.hintUsed, previous.completionReserved, previous.rememberedHuman,
                            previous.automaticUsed, opening?.copyBytes(), enabled, targetMax, targetNumber))
                        committedBytes = bytes.copyOf()
                    } catch (_: Exception) { failed = true }
                }
            }
            if (disposed.get()) return@launch
            if (committedBytes != null) {
                checkpoint = committedBytes
                stamp.set(stamp.get().let { it.copy(revision = it.revision + 1) })
                mutableState.value = mutableState.value.copy(game = previous.game, paused = true, thinking = false,
                    autoContinueEnabled = enabled, autoContinueMaxGames = targetMax,
                    autoContinueGameNumber = targetNumber)
            } else mutableState.value = mutableState.value.copy(game = previous.game.copy(
                error = if (failed) "自动续局设置未提交；当前棋盘和原设置已保留，请重试。" else previous.game.error))
        }
        return true
    }

    private fun archiveAutoSeriesEnd(): Boolean {
        val previous = mutableState.value
        if (disposed.get() || !lifetime.isActive || !previous.game.canOperate || !previous.autoContinueEnabled ||
            !previous.config.automatic || previous.game.phase != GoPhase.FINISHED ||
            previous.autoContinueGameNumber < previous.autoContinueMaxGames) return false
        val sink = completedSink ?: run {
            mutableState.value = previous.copy(game = previous.game.copy(
                error = "完成棋谱保存功能尚未准备好，连续对局结果已保留。"))
            return false
        }
        mutableState.value = previous.copy(paused = true, thinking = false,
            game = previous.game.copy(saving = true, error = null))
        scope.launch {
            var reserved = previous.completionReserved
            var stored = false
            withContext(worker + NonCancellable) {
                mutex.withLock {
                    try {
                        if (disposed.get() || !lifetime.isActive) return@withLock
                        val active = checkNotNull(engine)
                        check(active.phase == GoPhase.FINISHED && active.gameResult() != GameResult.ONGOING)
                        val finished = GoAiSavedMatch(matchId, previous.config, active.serialize(), previous.undoUsed,
                            previous.hintUsed, true, previous.rememberedHuman, previous.automaticUsed,
                            opening?.copyBytes(), previous.autoContinueEnabled, previous.autoContinueMaxGames,
                            previous.autoContinueGameNumber)
                        if (!reserved) { persistCheckpoint(finished); reserved = true }
                        if (disposed.get() || !lifetime.isActive) return@withLock
                        sink.storeCompleted(finished)
                        stored = true
                    } catch (_: Exception) { }
                }
            }
            if (disposed.get()) return@launch
            mutableState.value = mutableState.value.copy(completionReserved = reserved,
                game = previous.game.copy(error = if (stored) null
                    else "连续对局已结束；完成棋谱保存未完成，请重试。"))
        }
        return true
    }

    fun requestHint(): Boolean {
        val current = mutableState.value
        if (disposed.get() || !lifetime.isActive || !current.canRequestHint) return false
        invalidateSearch()
        val version = stamp.get()
        mutableState.value = mutableState.value.copy(hintThinking = true, game = current.game.copy(error = null))
        if (current.config.difficulty == GoAiDifficulty.SIMPLE) {
            completeHint(version, null)
        } else {
            val bytes = checkpoint ?: return false
            hintSearchStamp = version
            if (!search.start(GoAiPositionSnapshot(version, bytes), current.config.difficulty.budget)) {
                hintSearchStamp = null
                mutableState.value = mutableState.value.copy(hintThinking = false)
                return false
            }
        }
        return true
    }

    private fun completeHint(version: GoAiPositionStamp, action: GoAction?) {
        val previous = mutableState.value
        if (disposed.get() || !previous.humanCanPlay || version != stamp.get()) return
        mutableState.value = previous.copy(hintThinking = false, game = previous.game.copy(saving = true))
        scope.launch {
            var committed = false
            var result: GoAiHint? = null
            withContext(worker + NonCancellable) {
                mutex.withLock {
                    var candidate: GoRecordEngine? = null
                    try {
                        if (disposed.get() || !lifetime.isActive || version != stamp.get()) return@withLock
                        val active = checkNotNull(engine)
                        val bytes = active.serialize()
                        candidate = engineFactory(active.boardSize, 15, 0)
                        check(candidate.restore(bytes) == RestoreResult.Restored)
                        val choices = if (action == null) candidate.legalActions() else {
                            check(action is GoAction.Place || action == GoAction.Pass)
                            check(candidate.apply(action) == ActionResult.Accepted) { "Illegal hint" }
                            listOf(action)
                        }
                        check(choices.isNotEmpty() && choices.all { it is GoAction.Place || it == GoAction.Pass })
                        val points = choices.filterIsInstance<GoAction.Place>().map {
                            check(it.point.x in 0 until active.boardSize && it.point.y in 0 until active.boardSize)
                            it.point.y * active.boardSize + it.point.x
                        }.toSet()
                        val hint = GoAiHint(points, GoAction.Pass in choices, action == null)
                        // Keep the original board bytes: applying a candidate only validates the hint.
                        persistCheckpoint(GoAiSavedMatch(matchId, previous.config, bytes, previous.undoUsed, previous.hintUsed + 1,
                            rememberedHuman = previous.rememberedHuman, automaticUsed = previous.automaticUsed,
                            openingBytes = opening?.copyBytes(), autoContinueEnabled = previous.autoContinueEnabled,
                            autoContinueMaxGames = previous.autoContinueMaxGames,
                            autoContinueGameNumber = previous.autoContinueGameNumber))
                        committed = true
                        result = hint
                    } catch (_: Exception) {
                        if (!disposed.get()) mutableState.value = mutableState.value.copy(
                            game = previous.game.copy(error = "提示未提交；棋盘和提示次数已保留，请重试。"), hintThinking = false)
                    } finally { candidate?.close() }
                }
            }
            if (disposed.get()) return@launch
            if (committed) {
                val show = version == stamp.get() && lifetime.isActive
                stamp.set(stamp.get().let { it.copy(revision = it.revision + 1) })
                mutableState.value = mutableState.value.copy(game = previous.game, hintUsed = previous.hintUsed + 1,
                    hint = if (show) result else null, hintThinking = false)
            } else if (mutableState.value.game.saving) {
                mutableState.value = mutableState.value.copy(game = previous.game, hintThinking = false)
            }
        }
    }

    fun pause() {
        if (disposed.get()) return
        automaticResumeAllowed = false
        mutableState.value = mutableState.value.copy(paused = true, thinking = false)
        invalidateSearch()
    }

    fun resume() {
        if (disposed.get() || !lifetime.isActive || !mutableState.value.game.canOperate || !mutableState.value.paused) return
        automaticResumeAllowed = true
        mutableState.value = mutableState.value.copy(paused = false)
        scheduleSearch()
    }

    fun retrySearch() {
        if (disposed.get() || !lifetime.isActive || mutableState.value.paused || !mutableState.value.game.canAct) return
        invalidateSearch()
        mutableState.value = mutableState.value.copy(game = mutableState.value.game.copy(error = null))
        scheduleSearch()
    }

    fun setSpeed(speed: Float) {
        if (disposed.get() || !lifetime.isActive) return
        require(speed.isFinite() && speed in 0.5f..4f)
        mutableState.value = mutableState.value.copy(speed = speed)
    }

    private fun commit(action: GoAction?, undo: Boolean) {
        val previous = mutableState.value
        if (disposed.get() || !lifetime.isActive || !previous.game.canOperate) return
        mutableState.value = previous.copy(thinking = false, game = previous.game.copy(saving = true, error = null))
        scope.launch {
            var committedBytes: ByteArray? = null
            var committedGame: GoGameUiState? = null
            var committedUndoUsed = previous.undoUsed
            var committedEventCount = previous.eventCount
            var committedMatchId = previous.matchId
            var committedReserved = false
            var completionStored = false
            withContext(worker + NonCancellable) {
                mutex.withLock {
                    var candidate: GoRecordEngine? = null
                    try {
                        if (disposed.get() || !lifetime.isActive) return@withLock
                        val active = checkNotNull(engine)
                        candidate = engineFactory(active.boardSize, 15, 0)
                        check(candidate.restore(active.serialize()) == RestoreResult.Restored)
                        if (undo) {
                            check(candidate.undo()) { "No previous event" }
                            // Human undo restores their turn; review events and automatic mode step once.
                            if (!previous.config.automatic && previous.game.phase == GoPhase.PLAYING) {
                                while (candidate.phase == GoPhase.PLAYING && previous.config.isAi(player(candidate)) &&
                                    candidate.eventHistory().size > previous.openingEventCount) {
                                    check(candidate.undo())
                                }
                            }
                        } else check(candidate.apply(checkNotNull(action)) == ActionResult.Accepted) { "Illegal AI or human action" }
                        val next = snapshot(candidate)
                        val nextEventCount = candidate.eventHistory().size
                        val bytes = candidate.serialize()
                        val undoUsed = previous.undoUsed + if (undo) 1 else 0
                        // A finished record must never become a different game under the same archive key.
                        // Durable integration must retain the parent archive and classify this noninitial
                        // child as a continuation, not a fresh standard-ranked game.
                        val nextMatchId = if (undo && previous.game.phase == GoPhase.FINISHED)
                            UUID.randomUUID().toString() else matchId
                        val reserve = completeHumanImmediately && !previous.config.automatic && next.phase == GoPhase.FINISHED
                        if (disposed.get() || !lifetime.isActive) return@withLock
                        persistCheckpoint(GoAiSavedMatch(nextMatchId, previous.config, bytes, undoUsed, previous.hintUsed,
                            completionReserved = reserve,
                            rememberedHuman = previous.rememberedHuman, automaticUsed = previous.automaticUsed,
                            openingBytes = opening?.copyBytes(), autoContinueEnabled = previous.autoContinueEnabled,
                            autoContinueMaxGames = previous.autoContinueMaxGames,
                            autoContinueGameNumber = previous.autoContinueGameNumber))
                        matchId = nextMatchId
                        engine?.close(); engine = null
                        if (!disposed.get() && lifetime.isActive) {
                            engine = candidate; candidate = null
                            committedBytes = bytes.copyOf()
                            committedGame = next
                            committedUndoUsed = undoUsed
                            committedEventCount = nextEventCount
                            committedMatchId = nextMatchId
                            committedReserved = reserve
                            if (reserve && !disposed.get() && lifetime.isActive) {
                                try {
                                    checkNotNull(completedSink).storeCompleted(checkNotNull(durableMatch))
                                    completionStored = true
                                } catch (_: Exception) { committedGame = next.copy(error = HUMAN_COMPLETION_ERROR) }
                            }
                        }
                    } catch (_: Exception) {
                        if (!disposed.get() && lifetime.isActive) mutableState.value = mutableState.value.copy(
                            game = previous.game.copy(error = "该操作未提交；当前棋盘已保留，请重试。"), thinking = false)
                    } finally { candidate?.close() }
                }
            }
            if (committedBytes != null) {
                checkpoint = committedBytes
                stamp.set(stamp.get().let { it.copy(revision = it.revision + 1) })
                search.positionChanged()
                // Publish readiness only after bytes/version change on the owner dispatcher.
                // Preserve a pause/speed change made while the durable write was in flight.
                if (disposed.get()) return@launch
                mutableState.value = mutableState.value.copy(game = checkNotNull(committedGame), undoUsed = committedUndoUsed,
                    eventCount = committedEventCount, thinking = false, matchId = committedMatchId,
                    completionReserved = committedReserved, completionStored = completionStored,
                    completionPending = committedReserved && !completionStored)
                if (mutableState.value.game.phase != GoPhase.PLAYING) {
                    mutableState.value = mutableState.value.copy(paused = true)
                    if (mutableState.value.game.phase == GoPhase.FINISHED) maybeAutoContinue()
                } else scheduleSearch()
            }
        }
    }

    private suspend fun persistCheckpoint(next: GoAiSavedMatch, freshStandard: Boolean = false) {
        try {
            saveGoAiCheckpoint(repository, durableMatch, next, freshStandard)
            durableMatch = next
        } catch (failure: GoAiCheckpointWriteUncertainException) {
            // Existing operation catches must never resurrect an owner with an unknown disk state.
            withContext(scope.coroutineContext + NonCancellable) {
                beginRetirement()
                mutableState.value = mutableState.value.copy(game = mutableState.value.game.copy(
                    loading = false, saving = false, error = failure.message))
            }
            throw failure
        }
    }

    private companion object {
        const val HUMAN_COMPLETION_ERROR = "本局结果已固定；完成棋谱保存未完成，请重试保存。"
    }

    private fun maybeAutoContinue() {
        if (disposed.get() || !lifetime.isActive) return
        val current = mutableState.value
        if (current.autoContinuePending) continueAutomatically()
        else if (current.autoContinueEnabled && current.config.automatic && current.game.phase == GoPhase.FINISHED &&
            current.autoContinueGameNumber >= current.autoContinueMaxGames) archiveAutoSeriesEnd()
    }

    private fun scheduleSearch() {
        advanceJob?.cancel()
        val version = stamp.get()
        advanceJob = scope.launch {
            val current = mutableState.value
            if (current.config.automatic) delay((1_000 / current.speed).toLong())
            val ready = mutableState.value
            if (disposed.get() || ready.paused || !ready.game.canAct || ready.game.phase != GoPhase.PLAYING ||
                !ready.config.isAi(ready.game.currentPlayer) || version != stamp.get()) return@launch
            val bytes = checkpoint ?: return@launch
            search.start(GoAiPositionSnapshot(version, bytes), ready.config.difficulty.budget)
        }
    }

    private fun invalidateSearch() {
        hintSearchStamp = null
        mutableState.value = mutableState.value.copy(hint = null, hintThinking = false)
        advanceJob?.cancel(); advanceJob = null
        stamp.set(stamp.get().let { it.copy(revision = it.revision + 1) })
        search.cancel()
    }

    /** Seal internal producers immediately; already-entered durable writes still drain on join. */
    @MainThread
    fun beginRetirement() {
        if (retirementStarted) return
        retirementStarted = true
        disposed.set(true)
        automaticResumeAllowed = false
        try {
            invalidateSearch()
        } finally {
            mutableState.value = mutableState.value.copy(closed = true, paused = true, thinking = false,
                hintThinking = false, hint = null, game = mutableState.value.game.copy(sessionReady = false))
            lifetime.cancel()
        }
    }

    @MainThread
    suspend fun closeAndJoin() {
        beginRetirement()
        withContext(NonCancellable) {
            lifetime.join()
            search.closeAndJoin()
            withContext(worker) { mutex.withLock { engine?.close(); engine = null } }
        }
    }

    private fun player(source: GoRecordEngine) = GoPlayer.entries.first { it.code == source.currentPlayer.value }
    private fun snapshot(source: GoRecordEngine): GoGameUiState {
        val scoring = source.scoringState()
        val last = source.eventHistory().filterIsInstance<GoAction.Place>().lastOrNull()?.point
        return GoGameUiState(boardSize = source.boardSize, handicapStones = source.handicapStones,
            stones = List(source.boardSize * source.boardSize) { source.stoneAt(BoardPosition(it % source.boardSize, it / source.boardSize)).code },
            currentPlayer = player(source), phase = scoring.phase, result = source.gameResult(),
            deadStoneIndices = scoring.deadStones.map { it.y * source.boardSize + it.x }.toSet(),
            lastMoveIndex = last?.let { it.y * source.boardSize + it.x }, proposalRevision = scoring.proposalRevision,
            blackConfirmed = scoring.blackConfirmed, whiteConfirmed = scoring.whiteConfirmed, previewScore = source.previewScore(),
            sessionReady = true, loading = false, recordAvailable = true)
    }
}
