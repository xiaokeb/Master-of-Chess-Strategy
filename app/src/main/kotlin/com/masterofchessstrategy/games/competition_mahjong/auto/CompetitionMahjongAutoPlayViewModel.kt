package com.masterofchessstrategy.games.competition_mahjong.auto

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.masterofchessstrategy.engine.CompetitionMahjongAutoConfig
import com.masterofchessstrategy.engine.CompetitionMahjongAutoSession
import com.masterofchessstrategy.engine.CompetitionMahjongRoundPhase
import com.masterofchessstrategy.engine.NativeCompetitionMahjongAutoSession
import com.masterofchessstrategy.games.competition_mahjong.CompetitionMahjongResultUi
import com.masterofchessstrategy.games.competition_mahjong.CompetitionMahjongSeatUi
import com.masterofchessstrategy.games.competition_mahjong.CompetitionMahjongTableUiState
import com.masterofchessstrategy.games.competition_mahjong.competitionMahjongTableState
import com.masterofchessstrategy.games.competition_mahjong.auto.production.CompetitionMahjongAutoBackground
import com.masterofchessstrategy.games.competition_mahjong.match.CompetitionMahjongMatchSeating
import com.masterofchessstrategy.games.competition_mahjong.match.policy.CompetitionMahjongDifficultyOption
import com.masterofchessstrategy.games.competition_mahjong.match.policy.CompetitionMahjongPlayPolicy
import com.masterofchessstrategy.settings.LocalDataWriterRetirement
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Collections
import java.util.UUID

/** Library supplies compatible first-hand records; Native still admits the position when selected. */
class CompetitionMahjongAutoOpening(val id: String, val title: String,
    internal val config: CompetitionMahjongAutoRunConfig, record: ByteArray) {
    private val bytes = record.copyOf()
    internal val engineRecord: ByteArray get() = bytes.copyOf()
    init {
        require(id.isNotBlank() && title.isNotBlank() && title.length <= 80)
        CompetitionMahjongAutoRecord.eventCount(bytes, config.handConfig(0, 0, true))
    }
    override fun toString(): String = "CompetitionMahjongAutoOpening(id=$id)"
}

class CompetitionMahjongAutoDisplayConfig internal constructor(checkpoint: CompetitionMahjongAutoCheckpoint) {
    val runId = checkpoint.config.runId
    val viewerPlayerId = checkpoint.config.viewerPlayerId
    val playerBotLevels: List<Int> = Collections.unmodifiableList(checkpoint.config.playerBotLevels)
    val seriesIndex = checkpoint.seriesIndex
    val handIndex = checkpoint.activeHandIndex
    val standings = Collections.unmodifiableList(if (checkpoint.ledger.isComplete) checkpoint.ledger.finalStandings() else emptyList())
}

data class CompetitionMahjongAutoOpeningOption(val id: String, val title: String, val available: Boolean,
    val playerBotLevels: List<Int>)

data class CompetitionMahjongAutoPlayUiState(
    val playback: CompetitionMahjongAutoPlaybackState = CompetitionMahjongAutoPlaybackState(),
    val table: CompetitionMahjongTableUiState? = null,
    val configuration: CompetitionMahjongAutoDisplayConfig? = null,
    val difficultyOptions: List<CompetitionMahjongDifficultyOption> = emptyList(),
    val openings: List<CompetitionMahjongAutoOpeningOption> = emptyList(),
)

class CompetitionMahjongAutoPlayViewModel(
    store: CompetitionMahjongAutoStore,
    private val initialConfig: CompetitionMahjongAutoRunConfig,
    private val playPolicy: CompetitionMahjongPlayPolicy?,
    initialSettings: CompetitionMahjongAutoSettings = CompetitionMahjongAutoSettings(),
    openingRecord: ByteArray? = null,
    openings: List<CompetitionMahjongAutoOpening> = emptyList(),
    sessionFactory: (CompetitionMahjongAutoConfig) -> CompetitionMahjongAutoSession = ::NativeCompetitionMahjongAutoSession,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
    parentScope: CoroutineScope? = null,
    waitMillis: suspend (Long) -> Unit = { delay(it) },
    internal val navigationOwnerKey: String? = null,
) : ViewModel(), LocalDataWriterRetirement {
    private val scope = parentScope ?: viewModelScope
    private val configuration = MutableStateFlow<CompetitionMahjongAutoDisplayConfig?>(null)
    private val options = playPolicy?.options.orEmpty()
    private val openingRecords = openings.associateBy { it.id }.also { require(it.size == openings.size) }
    private val openingOptions = Collections.unmodifiableList(openings.map { source ->
        CompetitionMahjongAutoOpeningOption(source.id, source.title, source.config.viewerPlayerId == initialConfig.viewerPlayerId &&
            source.config.playerBotLevels.all { level -> options.any { it.botLevel == level && it.unlocked } },
            Collections.unmodifiableList(source.config.playerBotLevels))
    })
    private val trackingStore = object : CompetitionMahjongAutoStore {
        override suspend fun load(viewerPlayerId: Int): CompetitionMahjongAutoCheckpoint? =
            store.load(viewerPlayerId)?.also { configuration.value = CompetitionMahjongAutoDisplayConfig(it) }
        override suspend fun save(checkpoint: CompetitionMahjongAutoCheckpoint, archive: CompetitionMahjongAutoArchive?) {
            store.save(checkpoint, archive)
            configuration.value = CompetitionMahjongAutoDisplayConfig(checkpoint)
        }
    }
    private val controller = CompetitionMahjongAutoController(initialConfig, trackingStore, playPolicy, initialSettings,
        openingRecord, sessionFactory, dispatcher)
    private val playback = CompetitionMahjongAutoPlayback(controller, scope, waitMillis)
    private val backgroundForeground = MutableStateFlow(false)
    private var backgroundBinding: Job? = null
    private var visibleHost: Any? = null
    override val retirementCompletion get() = playback.retirementCompletion
    override fun beginRetirement(): Deferred<Unit> {
        navigationOwnerKey?.let(CompetitionMahjongAutoBackground::stopOwner)
        backgroundBinding?.cancel()
        return playback.beginRetirement()
    }
    val state = combine(playback.state, configuration) { current, metadata ->
        CompetitionMahjongAutoPlayUiState(current, autoTable(current),
            metadata?.takeIf { it.runId == current.activity.runId && it.seriesIndex == current.activity.seriesIndex &&
                it.handIndex == current.activity.handIndex }, options, openingOptions)
    }.stateIn(scope, SharingStarted.Eagerly, CompetitionMahjongAutoPlayUiState(difficultyOptions = options, openings = openingOptions))
    init { scope.launch { playback.open() } }

    /** The navigation owner keeps its lease while Android recreates the Activity. */
    internal fun attachBackgroundHost(host: Any) {
        val owner = checkNotNull(navigationOwnerKey)
        visibleHost = host
        if (backgroundBinding != null) return
        backgroundBinding = scope.launch {
            coroutineScope {
                launch {
                    combine(playback.state, backgroundForeground) { state, foreground -> state to foreground }
                        .collect { (state, foreground) -> CompetitionMahjongAutoBackground.update(owner, state, foreground) }
                }
                launch {
                    val permission = CompetitionMahjongAutoBackground.lease.map { it != null && it.owner == owner && it.ready }
                        .distinctUntilChanged()
                    combine(backgroundForeground, permission) { foreground, authorized -> foreground to authorized }
                        .collect { (foreground, authorized) -> playback.setHost(foreground, authorized) }
                }
            }
        }
    }

    internal fun updateBackgroundHost(host: Any, foreground: Boolean) {
        if (visibleHost === host) backgroundForeground.value = foreground
    }

    internal fun detachBackgroundHost(host: Any) {
        if (visibleHost !== host) return
        visibleHost = null
        backgroundForeground.value = false
    }

    fun setHost(foreground: Boolean, backgroundAuthorized: Boolean = false) =
        scope.launch { playback.setHost(foreground, backgroundAuthorized) }
    fun updateSettings(value: CompetitionMahjongAutoSettings) = scope.launch { playback.updateSettings(value) }
    internal fun pauseForBackgroundStop(runId: String) { scope.launch { playback.pauseForBackgroundStop(runId) } }
    fun togglePlayback() = scope.launch {
        val current = playback.state.value
        playback.updateSettings(current.activity.settings.copy(paused = !(current.activity.settings.paused || current.halted)))
    }
    fun step() = scope.launch { playback.step() }
    fun undo() = scope.launch { playback.undo() }
    fun retrySave() = scope.launch { playback.retrySave() }
    fun retryLoad() = scope.launch { playback.open() }
    fun close() = scope.launch { playback.close() }
    fun leave(onReady: () -> Unit) = scope.launch {
        if (playback.setHost(false, false) && !playback.state.value.activity.needsSave) {
            beginRetirement().await()
            onReady()
        }
    }

    override fun onCleared() { beginRetirement() }

    /** UI calls only after confirmation. Opening strategies remain those of its compatible record. */
    fun newRun(levels: List<Int>, settings: CompetitionMahjongAutoSettings, openingId: String? = null) =
        scope.launch {
            val source = openingId?.let(openingRecords::get)
            if (openingId != null && source == null) return@launch
            val selected = source?.config?.playerBotLevels ?: levels.toList()
            if (selected.size != 4 || selected.any { level -> options.none { it.botLevel == level && it.unlocked } } ||
                (source != null && source.config.viewerPlayerId != initialConfig.viewerPlayerId)) return@launch
            val seed = source?.config?.seed ?: (System.nanoTime() and Long.MAX_VALUE)
            val target = CompetitionMahjongAutoRunConfig(UUID.randomUUID().toString(), seed, initialConfig.viewerPlayerId,
                selected, source?.config?.decisionSeed ?: seed)
            playback.newRun(target, settings, source?.engineRecord)
        }
}

/** Read-only observation projection; no manual action or inferred opponent concealed tile. */
internal fun autoTable(playback: CompetitionMahjongAutoPlaybackState): CompetitionMahjongTableUiState? {
    val frame = playback.activity.frame ?: return null
    val raw = competitionMahjongTableState(frame, emptyList(), playback.error, playback.busy, canRestart = !frame.isAutoTerminal(),
        canUndo = playback.canUndo)
    val seating = CompetitionMahjongMatchSeating.forHand(playback.activity.handIndex)
    fun name(seat: Int) = "玩家${seating.playerAtSeat(seat) + 1}AI"
    val status = when {
        playback.activity.needsSave -> "演局尚未保存，请重试保存"
        playback.halted -> "自动演局已停止，请检查后继续"
        playback.activity.finished -> "本次自动演局已结束"
        playback.activity.settings.paused -> "演局已暂停，可单步前进或回退"
        !playback.hostPermitsExecution -> "演局等待返回前台"
        frame.phase == CompetitionMahjongRoundPhase.DISCARD -> "${name(frame.currentSeat)}正在出牌"
        frame.phase == CompetitionMahjongRoundPhase.DRAW -> "${name(frame.currentSeat)}将摸牌"
        frame.phase == CompetitionMahjongRoundPhase.CLAIM -> "等待各席AI回应认领"
        frame.phase == CompetitionMahjongRoundPhase.ROB_KONG -> "等待各席AI确认抢杠和"
        else -> "本盘已结算，等待下一盘"
    }
    val seats = raw.seats.map { seat -> CompetitionMahjongSeatUi(seat.seat, name(seat.seat), seat.windLabel, seat.concealedCount,
        seat.visibleHand, seat.flowers, seat.melds, seat.discards, seat.netScore, seat.canWin) }
    val result = raw.result?.let { value ->
        val title = if (frame.phase == CompetitionMahjongRoundPhase.WON)
            "${name(checkNotNull(frame.winnerSeat))}${if (frame.payerSeat == null) "自摸" else "和牌"}" else value.title
        val detail = if (frame.phase == CompetitionMahjongRoundPhase.WON) {
            val payment = frame.payerSeat?.let { "${name(it)}点和，三家付底分" } ?: "三家各付底分与番分"
            "$payment · ${value.detail.substringAfter(" · ")}"
        } else value.detail
        CompetitionMahjongResultUi(title, detail, value.netScores, value.fans, value.totalFan, value.seatLabels,
            value.winningTile, value.winningTiles)
    }
    return CompetitionMahjongTableUiState(seats, raw.localSeat, raw.dealerSeat, raw.currentSeat, raw.roundLabel, raw.wallRemaining,
        status, emptyList(), raw.claim, result, raw.feedback, raw.isBusy, !playback.activity.needsSave,
        canUndo = playback.canUndo, drawnTile = raw.drawnTile)
}
