package com.masterofchessstrategy.games.competition_mahjong.match.play

import androidx.lifecycle.ViewModel
import com.masterofchessstrategy.settings.LocalDataWriterRetirement
import com.masterofchessstrategy.engine.CompetitionMahjongAction
import com.masterofchessstrategy.engine.CompetitionMahjongPublicView
import com.masterofchessstrategy.engine.CompetitionMahjongRoundActionKind
import com.masterofchessstrategy.engine.CompetitionMahjongRoundPhase
import com.masterofchessstrategy.engine.CompetitionMahjongRuleSession
import com.masterofchessstrategy.engine.NativeCompetitionMahjongSession
import com.masterofchessstrategy.games.competition_mahjong.COMPETITION_MAHJONG_AUTO_SPEEDS
import com.masterofchessstrategy.games.competition_mahjong.CompetitionMahjongAutoPlayUi
import com.masterofchessstrategy.games.competition_mahjong.CompetitionMahjongMatchConfig
import com.masterofchessstrategy.games.competition_mahjong.CompetitionMahjongTableUiState
import com.masterofchessstrategy.games.competition_mahjong.competitionMahjongActionId
import com.masterofchessstrategy.games.competition_mahjong.competitionMahjongApplyFeedback
import com.masterofchessstrategy.games.competition_mahjong.competitionMahjongDeclarationTiles
import com.masterofchessstrategy.games.competition_mahjong.competitionMahjongPublicBotSeed
import com.masterofchessstrategy.games.competition_mahjong.competitionMahjongTableState
import com.masterofchessstrategy.games.competition_mahjong.withControls
import com.masterofchessstrategy.games.competition_mahjong.match.CompetitionMahjongCompetitionConfig
import com.masterofchessstrategy.games.competition_mahjong.match.CompetitionMahjongCompetitionState
import com.masterofchessstrategy.games.competition_mahjong.match.CompetitionMahjongMatchCheckpoint
import com.masterofchessstrategy.games.competition_mahjong.match.CompetitionMahjongMatchController
import com.masterofchessstrategy.games.competition_mahjong.match.CompetitionMahjongMatchStore
import com.masterofchessstrategy.games.competition_mahjong.match.CompetitionMahjongMatchPlayback
import com.masterofchessstrategy.games.competition_mahjong.match.hint.*
import com.masterofchessstrategy.games.competition_mahjong.match.policy.CompetitionMahjongAssistanceUsage
import com.masterofchessstrategy.games.competition_mahjong.match.policy.CompetitionMahjongDifficultyOption
import com.masterofchessstrategy.games.competition_mahjong.match.policy.CompetitionMahjongPlayPolicy
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

internal data class CompetitionMahjongMatchPlayState(
    val series: CompetitionMahjongCompetitionState = CompetitionMahjongCompetitionState(),
    val table: CompetitionMahjongTableUiState? = null,
    val loading: Boolean = true,
    val busy: Boolean = true,
    val paused: Boolean = false,
    val speed: Float = 1f,
    val botLevel: Int = 1,
    val error: String? = null,
    val declarationTiles: List<Int> = emptyList(),
    val difficultyOptions: List<CompetitionMahjongDifficultyOption> = emptyList(),
    val assistance: CompetitionMahjongAssistanceUsage? = null,
    val hint: CompetitionMahjongHintReceipt? = null,
    val hintAdvisorAvailable: Boolean = false,
    val retired: Boolean = false,
) {
    val canOperate: Boolean get() = !retired && table != null && !loading && !busy && !series.needsSave
    val canDeclareWin: Boolean get() = canOperate && declarationTiles.isNotEmpty()
    val canHint: Boolean get() = canOperate && table?.result == null && table?.availableActions?.isNotEmpty() == true &&
        assistance?.undoInProgress != true && (hint != null || (hintAdvisorAvailable && assistance?.canHint == true))
}

/** UI scheduler for a full series; persistence and Native ownership stay in MatchController. */
internal class CompetitionMahjongMatchPlayViewModel(
    private val store: CompetitionMahjongMatchStore,
    private val initialConfig: CompetitionMahjongCompetitionConfig,
    private val sessionFactory: (CompetitionMahjongMatchConfig) -> CompetitionMahjongRuleSession = {
        NativeCompetitionMahjongSession(it.seed, it.dealerSeat, it.roundWind, it.viewerSeat)
    },
    private val workerDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val seedSource: () -> Long = { System.nanoTime() and Long.MAX_VALUE },
    private val idSource: () -> String = { UUID.randomUUID().toString() },
    private val playPolicy: CompetitionMahjongPlayPolicy? = null,
    private val hintAdvisor: CompetitionMahjongHintAdvisor? = null,
    private val ownerIsCurrent: (() -> Boolean)? = null,
    private val policyProvider: (() -> CompetitionMahjongPlayPolicy)? = null,
) : ViewModel(), LocalDataWriterRetirement {
    private val mutableState = MutableStateFlow(CompetitionMahjongMatchPlayState(botLevel = initialConfig.botLevel))
    val state = mutableState.asStateFlow()
    private val transaction = Mutex()
    private val pendingTransactions = AtomicInteger(0)
    private val disposed = AtomicBoolean(false)
    private val admission = Any()
    private val workScope = CoroutineScope(SupervisorJob() + workerDispatcher)
    private val cleanupStarted = AtomicBoolean(false)
    private val retired = CompletableDeferred<Unit>()
    override val retirementCompletion: Deferred<Unit> get() = retired
    private val botPaused = AtomicBoolean(false)
    private val foreground = AtomicBoolean(true)
    private var effectiveConfig = initialConfig
    private var loadedSavedSession = false
    private var assistance: CompetitionMahjongAssistanceUsage? = null
    private var hintReceipt: CompetitionMahjongHintReceipt? = null
    private val trackedStore = object : CompetitionMahjongMatchStore {
        override suspend fun load(viewerPlayerId: Int): CompetitionMahjongMatchCheckpoint? {
            requireCurrentOwner()
            val policy = requirePolicy()
            val saved = store.load(viewerPlayerId)
            requireCurrentOwner()
            val candidateConfig = saved?.config ?: initialConfig
            val usage = if (saved == null) policy.freshUsage(candidateConfig.botLevel) else saved.assistance
            policy.requireAuthorized(candidateConfig.botLevel, usage)
            assistance = usage
            hintReceipt = saved?.hintReceipt
            val playback = saved?.playback ?: CompetitionMahjongMatchPlayback()
            botPaused.set(playback.paused)
            mutableState.update { it.copy(speed = playback.speed) }
            loadedSavedSession = saved != null
            if (saved != null) effectiveConfig = saved.config
            return saved
        }
        override suspend fun save(checkpoint: CompetitionMahjongMatchCheckpoint) {
            requireCurrentOwner()
            requirePolicy().requireAuthorized(checkpoint.config.botLevel, assistance)
            store.save(checkpoint.withAssistance(checkNotNull(assistance)).withPlayback(
                CompetitionMahjongMatchPlayback(botPaused.get(), mutableState.value.speed)).withHintReceipt(hintReceipt))
            requireCurrentOwner()
            effectiveConfig = checkpoint.config
        }
    }
    private var controller = createController(initialConfig, trackedStore)
    private var feedback: String? = null
    private var operationError: String? = null
    private var hasUndo = false

    init { launchTransaction { load() } }

    fun retryLoad() {
        if (state.value.table != null || !begin(requireTable = false)) return
        launchTransaction { load() }
    }

    fun performAction(id: String, handIndex: Int) {
        if (!begin(handIndex = handIndex)) return
        launchTransaction {
            val action = controller.state.value.legalActions.firstOrNull { competitionMahjongActionId(it) == id }
            if (action == null) {
                feedback = "牌局已变化，请使用当前可选操作。"
                return@launchTransaction
            }
            apply(action, handIndex)
        }
    }

    fun declareWin(tile: Int, handIndex: Int) {
        if (tile !in 0..33 || !begin(handIndex = handIndex)) return
        launchTransaction {
            val series = controller.state.value
            val frame = series.table ?: return@launchTransaction
            if (tile !in declarationTiles(frame, series.legalActions.isNotEmpty())) {
                feedback = "当前不能以这张牌报和，请重新核对牌桌。"
                return@launchTransaction
            }
            apply(CompetitionMahjongAction(CompetitionMahjongRoundActionKind.WIN, tile), handIndex)
        }
    }

    fun requestHint(handIndex: Int) {
        if (!foreground.get() || !state.value.canHint || !begin(handIndex = handIndex)) return
        launchTransaction {
            val series = controller.state.value
            val frame = series.table ?: return@launchTransaction
            val actions = series.legalActions
            val usage = checkNotNull(assistance)
            requirePolicy().requireAuthorized(effectiveConfig.botLevel, usage)
            if (usage.undoInProgress || actions.isEmpty() || frame.publicFrameVersion != 2) return@launchTransaction
            hintReceipt?.takeIf { it.matches(handIndex, frame, actions) }?.let {
                feedback = "显示已保存提示，不再扣除次数。"
                return@launchTransaction
            }
            val advisor = hintAdvisor ?: return@launchTransaction
            if (!usage.canHint) return@launchTransaction
            val position = competitionMahjongHintPosition(handIndex, frame)
            val identity = advisor.identity
            val recommendation = try {
                require(identity.matches(Regex("[A-Za-z0-9_.-]{1,64}")))
                withTimeout(5_000) { advisor.recommend(CompetitionMahjongHintInput(frame, actions),
                    position.take(16).toULong(16).toLong() and Long.MAX_VALUE) }
            } catch (_: Exception) {
                operationError = "提示暂不可用，未扣除提示次数。"
                return@launchTransaction
            }
            if (!available() || !foreground.get()) return@launchTransaction
            if (recommendation == null) {
                feedback = "当前没有可用建议，未扣除提示次数。"
                return@launchTransaction
            }
            if (recommendation !in actions) {
                operationError = "提示返回了非当前合法操作，未扣除提示次数。"
                return@launchTransaction
            }
            assistance = usage.consumeHint()
            hintReceipt = CompetitionMahjongHintReceipt(handIndex, position, identity, recommendation)
            feedback = if (controller.saveCurrent()) "提示已保存。" else
                "提示尚未保存，请重试保存后查看，不会重复扣除次数。"
        }
    }

    fun nextHand(handIndex: Int) {
        if (!state.value.series.canStartNextHand || !begin(handIndex = handIndex)) return
        launchTransaction {
            hintReceipt = null
            if (controller.nextHand(handIndex)) {
                hasUndo = false
                feedback = null
                advanceOtherSeats()
            }
        }
    }

    /** Only call after the UI explicitly confirms replacing the current series. */
    fun newMatch(botLevel: Int) {
        if (botLevel !in 0..3 || !begin(requireTable = false)) return
        launchTransaction {
            val nextUsage = requirePolicy().freshUsage(botLevel)
            val config = effectiveConfig.copy(matchId = idSource(), seed = seedSource(), botLevel = botLevel,
                botStrategy = initialConfig.botStrategy)
            val oldUsage = assistance
            val oldHint = hintReceipt
            assistance = nextUsage
            hintReceipt = null
            val freshStore = object : CompetitionMahjongMatchStore {
                override suspend fun load(viewerPlayerId: Int): CompetitionMahjongMatchCheckpoint? = null
                override suspend fun save(checkpoint: CompetitionMahjongMatchCheckpoint) = trackedStore.save(checkpoint)
            }
            val candidate = createController(config, freshStore)
            var installed = false
            try {
                if (!candidate.open()) {
                    operationError = candidate.state.value.error ?: "新赛事未保存，原赛事已保留。"
                    return@launchTransaction
                }
                val old = controller
                controller = candidate
                installed = true
                hasUndo = false
                feedback = null
                old.close()
                advanceOtherSeats()
            } finally {
                if (!installed) {
                    assistance = oldUsage
                    hintReceipt = oldHint
                    candidate.close()
                }
            }
        }
    }

    fun undo(handIndex: Int) {
        if (!begin(handIndex = handIndex)) return
        launchTransaction {
            val oldUsage = checkNotNull(assistance)
            requirePolicy().requireAuthorized(effectiveConfig.botLevel, oldUsage)
            if (oldUsage.undoInProgress) {
                completeUndo(handIndex)
                return@launchTransaction
            }
            if (!oldUsage.canUndo) {
                feedback = "本赛事悔棋次数已用完。"
                return@launchTransaction
            }
            val oldHint = hintReceipt
            hintReceipt = null
            assistance = oldUsage.consumeUndo().copy(undoInProgress = true)
            completeUndo(handIndex, oldUsage)
            if (assistance == oldUsage && !controller.state.value.needsSave) {
                val current = controller.state.value
                if (oldHint != null && current.table?.let { oldHint.matches(handIndex, it, current.legalActions) } == true)
                    hintReceipt = oldHint
            }
        }
    }

    fun retrySave() {
        if (!state.value.series.needsSave || !begin(allowUnsaved = true)) return
        launchTransaction {
            if (controller.retrySave()) {
                if (assistance?.undoInProgress == true) completeUndo(controller.state.value.activeHandIndex)
                else advanceOtherSeats()
            }
        }
    }

    fun toggleBots() {
        if (!available() || state.value.table == null || state.value.loading || state.value.series.needsSave) return
        botPaused.set(!botPaused.get())
        savePlaybackPreferences()
    }

    fun setSpeed(speed: Float) {
        if (speed !in COMPETITION_MAHJONG_AUTO_SPEEDS || !available() || state.value.table == null ||
            state.value.loading || state.value.series.needsSave || speed == state.value.speed) return
        mutableState.update { current -> current.copy(speed = speed, table = current.table?.withControls(
            autoPlay = current.table.autoPlay.copy(speed = speed),
        )) }
        savePlaybackPreferences()
    }

    private fun savePlaybackPreferences() {
        mutableState.update { it.copy(busy = true, table = it.table?.withControls(isBusy = true)) }
        updateControls()
        // A pause can interrupt an active bounded AI loop; the metadata save joins the same serial queue.
        launchTransaction {
            if (!controller.state.value.needsSave && controller.saveCurrent()) advanceOtherSeats()
        }
    }

    /** Stop bounded AI scheduling off-screen; finish an already accepted write before closing. */
    fun setForeground(active: Boolean) {
        if (!available()) return
        foreground.set(active)
        updateControls()
        if (active && !botPaused.get() && begin()) launchTransaction { advanceOtherSeats() }
    }

    private suspend fun load() {
        if (controller.open()) {
            hasUndo = loadedSavedSession
            if (assistance?.undoInProgress == true) completeUndo(controller.state.value.activeHandIndex)
            else advanceOtherSeats()
        }
    }

    private suspend fun apply(action: CompetitionMahjongAction, index: Int) {
        val before = controller.state.value.table ?: return
        val oldHint = hintReceipt
        hintReceipt = null
        val result = controller.apply(action, index) ?: return
        feedback = competitionMahjongApplyFeedback(result)
        if (!result.accepted) { hintReceipt = oldHint; return }
        hasUndo = true
        if (result.falseWin) {
            val after = controller.state.value.table ?: return
            val penalty = before.netScores[before.viewerSeat].toLong() - after.netScores[after.viewerSeat].toLong()
            feedback = "错和：向其他三家各付${penalty / 3}分，本盘停和。可继续摸打和吃碰杠。"
        }
        if (!controller.state.value.needsSave) advanceOtherSeats()
    }

    /** Continue the same charged request after a failed write or a restored intermediate checkpoint. */
    private suspend fun completeUndo(index: Int, unusedReservation: CompetitionMahjongAssistanceUsage? = null) {
        var undone = 0
        // Fresh requests undo at least one event; recovered requests may already be at their target.
        if (unusedReservation != null || controller.state.value.legalActions.isEmpty()) {
            while (undone < MAX_UNDO_EVENTS && controller.undo(index)) {
                undone++
                if (controller.state.value.legalActions.isNotEmpty()) break
            }
        }
        if (controller.state.value.needsSave) {
            feedback = "撤回尚未保存，请重试保存以完成本次撤回，不会重复扣次。"
            return
        }
        if (undone == 0 && unusedReservation != null) {
            assistance = unusedReservation
            hasUndo = false
            feedback = "没有可撤销的操作。"
            return
        }
        hasUndo = undone > 0 || hasUndo
        if (undone == MAX_UNDO_EVENTS && controller.state.value.legalActions.isEmpty()) {
            botPaused.set(true)
            feedback = "已达到本次撤回上限，可继续完成此次撤回，不再扣次数。"
            return
        }
        assistance = checkNotNull(assistance).copy(undoInProgress = false)
        if (!controller.saveCurrent()) {
            feedback = "撤回完成状态尚未保存，请重试保存。"
            return
        }
        feedback = if (controller.state.value.legalActions.isNotEmpty()) "已撤回至上一处本席选择。" else "已撤回到本盘开局。"
        if (controller.state.value.legalActions.isEmpty()) {
            botPaused.set(true)
            controller.saveCurrent()
        }
    }

    private suspend fun advanceOtherSeats() {
        render(busy = true)
        for (batch in 0 until MAX_BOT_BATCHES) {
            val current = controller.state.value
            val frame = current.table ?: return
            if (!available() || botPaused.get() || !foreground.get() || current.needsSave || assistance?.undoInProgress == true ||
                frame.isTerminal() || current.legalActions.isNotEmpty()) return
            hintReceipt = null
            val moved = controller.advanceOtherSeats(current.activeHandIndex, competitionMahjongPublicBotSeed(frame), BOT_BATCH_SIZE)
            if (controller.state.value.needsSave) return
            if (moved == 0) {
                botPaused.set(true)
                operationError = "其他席暂无可推进操作，可撤回或重开。"
                controller.saveCurrent()
                return
            }
            hasUndo = true
            render(busy = true)
            val next = controller.state.value
            if (next.table?.isTerminal() == true || next.legalActions.isNotEmpty()) return
            delay((400 / state.value.speed).toLong())
        }
        botPaused.set(true)
        feedback = "AI已达到本次推进上限并暂停，可继续推进。"
        controller.saveCurrent()
    }

    private fun begin(requireTable: Boolean = true, allowUnsaved: Boolean = false, handIndex: Int? = null): Boolean {
        if (!available()) return false
        while (true) {
            val current = mutableState.value
            if (current.loading || current.busy || (!allowUnsaved && current.series.needsSave) ||
                (requireTable && current.table == null) || (handIndex != null && current.series.activeHandIndex != handIndex)) return false
            if (mutableState.compareAndSet(current, current.copy(busy = true, table = current.table?.withControls(isBusy = true)))) return true
        }
    }

    private fun launchTransaction(block: suspend () -> Unit) {
        synchronized(admission) {
            if (!available()) return
            pendingTransactions.incrementAndGet()
        }
        mutableState.update { it.copy(busy = true, table = it.table?.withControls(isBusy = true)) }
        // Admission owns this work, so clearing the ViewModel cannot cancel it before cleanup sees it.
        workScope.launch(start = CoroutineStart.UNDISPATCHED) {
            withContext(NonCancellable + workerDispatcher) {
                transaction.withLock {
                    operationError = null
                    try {
                        requireCurrentOwner()
                        render(busy = true)
                        block()
                    } catch (failure: Exception) {
                        operationError = failure.message ?: "赛事操作失败"
                    } finally {
                        pendingTransactions.decrementAndGet()
                        render(busy = false)
                        finishRetirementIfDrained()
                    }
                }
            }
        }
    }

    private fun updateControls() {
        val paused = botPaused.get() || !foreground.get()
        mutableState.update { current -> current.copy(paused = paused, table = current.table?.withControls(
            autoPlay = current.table.autoPlay.copy(running = !paused),
        )) }
    }

    private fun render(busy: Boolean) = synchronized(admission) {
        if (!available()) return@synchronized
        val series = controller.state.value
        val frame = series.table
        val paused = botPaused.get() || !foreground.get()
        mutableState.update { previous ->
            val effectiveBusy = busy || pendingTransactions.get() > 0
            val table = frame?.let {
                competitionMahjongTableState(it, series.legalActions, feedback,
                    isBusy = effectiveBusy || series.needsSave, canRestart = !series.needsSave,
                    canUndo = hasUndo && !series.needsSave && assistance?.let { usage -> usage.canUndo || usage.undoInProgress } == true,
                    autoPlay = CompetitionMahjongAutoPlayUi(available = !it.isTerminal(), running = !paused,
                        speed = previous.speed, controlsEnabled = !series.needsSave))
            }
            CompetitionMahjongMatchPlayState(series, table, false, effectiveBusy, paused, previous.speed, effectiveConfig.botLevel,
                operationError ?: series.error, frame?.let { declarationTiles(it, series.legalActions.isNotEmpty()) }.orEmpty(),
                currentPolicy()?.options.orEmpty(), assistance,
                hintReceipt?.takeIf { !effectiveBusy && !series.needsSave && frame != null &&
                    it.matches(series.activeHandIndex, frame, series.legalActions) },
                hintAdvisor != null && frame?.publicFrameVersion == 2)
        }
    }

    private fun createController(config: CompetitionMahjongCompetitionConfig, store: CompetitionMahjongMatchStore) =
        CompetitionMahjongMatchController(config, store, sessionFactory, workerDispatcher)

    private fun currentPolicy(): CompetitionMahjongPlayPolicy? = policyProvider?.invoke() ?: playPolicy

    private fun requirePolicy(): CompetitionMahjongPlayPolicy = checkNotNull(currentPolicy()) {
        "国标麻将难度权限尚未接入，未创建或替换赛事"
    }

    private fun available(): Boolean {
        if (disposed.get()) return false
        if (ownerIsCurrent?.invoke() == true) return true
        beginRetirement()
        return false
    }

    private fun requireCurrentOwner() {
        if (ownerIsCurrent?.invoke() != true) {
            beginRetirement()
            throw java.io.IOException("赛事归属已失效，请返回后重新进入")
        }
    }

    override fun beginRetirement(): Deferred<Unit> {
        synchronized(admission) {
            if (disposed.compareAndSet(false, true)) {
                foreground.set(false)
                mutableState.update { it.copy(retired = true, busy = false,
                    error = "赛事页面已关闭或归属已变化，请返回后重新进入",
                    table = it.table?.withControls(isBusy = true)) }
            }
            finishRetirementIfDrained()
        }
        return retired
    }

    private fun finishRetirementIfDrained() {
        if (!disposed.get() || pendingTransactions.get() != 0 || !cleanupStarted.compareAndSet(false, true)) return
        workScope.launch {
            try {
                transaction.withLock { controller.close() }
                retired.complete(Unit)
            } catch (failure: Throwable) {
                mutableState.update { it.copy(error = "赛事资源释放失败，请重试返回；本地数据未重新取得归属") }
                retired.completeExceptionally(failure)
            } finally { workScope.cancel() }
        }
    }

    override fun onCleared() {
        beginRetirement()
    }

    private companion object {
        const val BOT_BATCH_SIZE = 16
        const val MAX_BOT_BATCHES = 64
        const val MAX_UNDO_EVENTS = 1024
        fun declarationTiles(view: CompetitionMahjongPublicView, hasChoice: Boolean): List<Int> {
            return competitionMahjongDeclarationTiles(view, hasChoice)
        }
    }
}

private fun CompetitionMahjongPublicView.isTerminal() =
    phase == CompetitionMahjongRoundPhase.WON || phase == CompetitionMahjongRoundPhase.EXHAUSTIVE_DRAW
