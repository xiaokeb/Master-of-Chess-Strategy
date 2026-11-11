package com.masterofchessstrategy.games.competition_mahjong.auto.replay

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.masterofchessstrategy.engine.CompetitionMahjongAutoConfig
import com.masterofchessstrategy.engine.CompetitionMahjongAutoSession
import com.masterofchessstrategy.engine.NativeCompetitionMahjongAutoSession
import com.masterofchessstrategy.games.competition_mahjong.CompetitionMahjongTableUiState
import com.masterofchessstrategy.games.competition_mahjong.auto.CompetitionMahjongAutoPlaybackState
import com.masterofchessstrategy.games.competition_mahjong.auto.CompetitionMahjongAutoSettings
import com.masterofchessstrategy.games.competition_mahjong.auto.CompetitionMahjongAutoState
import com.masterofchessstrategy.games.competition_mahjong.auto.autoTable
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Collections

class CompetitionMahjongAutoReplayUiState internal constructor(
    entries: List<CompetitionMahjongAutoArchiveSummary> = emptyList(),
    val favoritesOnly: Boolean = false,
    val selectedKey: String? = null,
    val replay: CompetitionMahjongAutoReplayState = CompetitionMahjongAutoReplayState(),
    val table: CompetitionMahjongTableUiState? = null,
    val playing: Boolean = false,
    val speed: Float = 1f,
    val busy: Boolean = false,
    val closed: Boolean = false,
    val notice: String? = null,
    val error: String? = null,
) {
    val entries: List<CompetitionMahjongAutoArchiveSummary> =
        Collections.unmodifiableList(entries.toList())
    val visibleEntries: List<CompetitionMahjongAutoArchiveSummary> get() =
        if (favoritesOnly) entries.filter { it.favorite } else entries
    val selected: CompetitionMahjongAutoArchiveSummary? get() =
        entries.firstOrNull { it.key == selectedKey }
    init { require(speed.isFinite() && speed in 0.5f..4f) }
}

class CompetitionMahjongAutoReplayViewModel(
    private val library: CompetitionMahjongAutoArchiveLibrary,
    private val viewerPlayerId: Int,
    private val sessionFactory: (CompetitionMahjongAutoConfig) -> CompetitionMahjongAutoSession =
        ::NativeCompetitionMahjongAutoSession,
    parentScope: CoroutineScope? = null,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val waitMillis: suspend (Long) -> Unit = { delay(it) },
) : ViewModel() {
    private val scope = parentScope ?: viewModelScope
    private val mutex = Mutex()
    private val mutableState = kotlinx.coroutines.flow.MutableStateFlow(CompetitionMahjongAutoReplayUiState())
    val state = mutableState.asStateFlow()
    private var entries = emptyList<CompetitionMahjongAutoArchiveSummary>()
    private var favoritesOnly = false
    private var selectedKey: String? = null
    private var controller: CompetitionMahjongAutoReplayController? = null
    private var playing = false
    private var speed = 1f
    private var busy = false
    private var closed = false
    private var notice: String? = null
    private var error: String? = null
    private var playbackJob: Job? = null
    private val lifetime = scope.launch {
        try {
            awaitCancellation()
        } finally {
            withContext(NonCancellable) {
                mutex.withLock {
                    playbackJob?.cancel()
                    playbackJob = null
                    playing = false
                    controller?.close()
                    controller = null
                    closed = true
                    publish()
                }
            }
        }
    }

    init {
        require(viewerPlayerId in 0..3)
        refresh()
    }

    fun refresh(): Job = operation {
        entries = checkedEntries(library.list(viewerPlayerId))
        if (selectedKey != null && entries.none { it.key == selectedKey }) closeReplayLocked()
        true
    }

    fun showFavorites(value: Boolean) {
        if (closed) return
        favoritesOnly = value
        publish()
    }

    fun select(key: String): Job = operation {
        val summary = entries.firstOrNull { it.key == key } ?: return@operation false
        val archive = library.load(key, viewerPlayerId) ?: return@operation false
        require(archive.key == summary.key && archive.checkpoint.config.viewerPlayerId == viewerPlayerId)
        val candidate = CompetitionMahjongAutoReplayController(archive, sessionFactory, dispatcher)
        if (!candidate.open()) {
            val message = candidate.state.value.error
            candidate.close()
            error = message
            return@operation false
        }
        stopPlayback()
        val old = controller
        controller = candidate
        selectedKey = key
        error = null
        old?.close()
        true
    }

    fun closeReplay(): Job = operation { closeReplayLocked(); true }
    fun forward(): Job = replayOperation { active, current ->
        active.forward(current.handIndex, current.eventIndex)
    }
    fun backward(): Job = replayOperation { active, current ->
        active.backward(current.handIndex, current.eventIndex)
    }
    fun selectHand(index: Int): Job = replayOperation { active, _ -> active.selectHand(index) }

    fun togglePlayback() {
        if (closed || busy || controller == null) return
        if (playing) {
            stopPlayback()
            publish()
        } else {
            startPlayback()
            publish()
        }
    }

    fun setSpeed(value: Float) {
        if (!value.isFinite() || value !in 0.5f..4f || closed) return
        val restart = playing
        stopPlayback()
        speed = value
        if (restart) startPlayback()
        publish()
    }

    fun setFavorite(key: String, favorite: Boolean): Job = operation {
        require(entries.any { it.key == key })
        library.setFavorite(key, viewerPlayerId, favorite)
        entries = checkedEntries(library.list(viewerPlayerId))
        notice = if (favorite) "已收藏棋谱" else "已取消收藏"
        true
    }

    fun delete(key: String): Job = operation {
        require(entries.any { it.key == key })
        library.delete(key, viewerPlayerId)
        if (selectedKey == key) closeReplayLocked()
        entries = checkedEntries(library.list(viewerPlayerId))
        notice = "已删除棋谱"
        true
    }

    fun export(key: String, sink: suspend (CompetitionMahjongAutoExport) -> Boolean): Job = scope.launch {
        mutex.withLock {
            if (closed) return@withLock
            busy = true
            notice = null
            error = null
            publish()
            try {
                val summary = entries.firstOrNull { it.key == key } ?: error("棋谱不存在")
                val archive = library.load(key, viewerPlayerId) ?: error("棋谱不存在")
                require(archive.key == summary.key && archive.checkpoint.config.viewerPlayerId == viewerPlayerId)
                if (sink(archive.export())) notice = "棋谱已导出"
                else error = "未写出棋谱，原归档保持不变"
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                error = failure.message ?: "棋谱导出失败"
            } finally {
                busy = false
                publish()
            }
        }
    }

    fun leave(onReady: () -> Unit): Job = scope.launch {
        mutex.withLock {
            stopPlayback()
            controller?.close()
            controller = null
            selectedKey = null
            publish()
        }
        onReady()
    }

    private fun replayOperation(block: suspend (CompetitionMahjongAutoReplayController,
                                                CompetitionMahjongAutoReplayState) -> Boolean): Job = operation {
        stopPlayback()
        val active = controller ?: return@operation false
        val accepted = block(active, active.state.value)
        error = active.state.value.error
        accepted
    }

    private fun operation(block: suspend () -> Boolean): Job = scope.launch {
        mutex.withLock {
            if (closed) return@withLock
            busy = true
            notice = null
            error = null
            publish()
            try {
                if (!block() && error == null) error = "棋谱操作未完成"
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                error = failure.message ?: "棋谱操作失败"
            } finally {
                busy = false
                publish()
            }
        }
    }

    private fun startPlayback() {
        val active = controller ?: return
        if (active.state.value.atEnd) return
        playing = true
        playbackJob = scope.launch {
            while (isActive) {
                waitMillis((400 / speed).toLong().coerceAtLeast(16))
                val keepGoing = mutex.withLock {
                    if (!playing || closed || controller !== active) return@withLock false
                    busy = true
                    publish()
                    val current = active.state.value
                    val accepted = active.forward(current.handIndex, current.eventIndex)
                    error = active.state.value.error
                    busy = false
                    if (!accepted || active.state.value.atEnd) {
                        playing = false
                        playbackJob = null
                    }
                    publish()
                    accepted && !active.state.value.atEnd
                }
                if (!keepGoing) break
            }
        }
    }

    private fun stopPlayback() {
        playing = false
        playbackJob?.cancel()
        playbackJob = null
    }

    private suspend fun closeReplayLocked() {
        stopPlayback()
        controller?.close()
        controller = null
        selectedKey = null
    }

    private fun checkedEntries(values: List<CompetitionMahjongAutoArchiveSummary>):
        List<CompetitionMahjongAutoArchiveSummary> {
        require(values.all { it.viewerPlayerId == viewerPlayerId })
        require(values.map { it.key }.toSet().size == values.size)
        require(values.zipWithNext().all { (first, second) ->
            first.savedAtEpochMillis >= second.savedAtEpochMillis
        })
        return values.toList()
    }

    private fun publish() {
        val replay = controller?.state?.value ?: CompetitionMahjongAutoReplayState()
        mutableState.value = CompetitionMahjongAutoReplayUiState(entries, favoritesOnly, selectedKey, replay,
            replayTable(replay, busy, error), playing, speed, busy, closed, notice, error ?: replay.error)
    }
}

internal fun replayTable(state: CompetitionMahjongAutoReplayState, busy: Boolean,
                         error: String?): CompetitionMahjongTableUiState? {
    val frame = state.frame ?: return null
    val projected = autoTable(CompetitionMahjongAutoPlaybackState(
        CompetitionMahjongAutoState(runId = state.archiveKey, handIndex = state.handIndex,
            settings = CompetitionMahjongAutoSettings(), frame = frame),
        busy = busy, foreground = true, error = error,
    )) ?: return null
    return CompetitionMahjongTableUiState(projected.seats, projected.localSeat, projected.dealerSeat,
        projected.currentSeat, projected.roundLabel, projected.wallRemaining,
        "棋谱回放 · 事件 " + state.eventIndex + " / " + state.eventCount,
        emptyList(), projected.claim, projected.result, error, busy, canRestart = false,
        canUndo = false, drawnTile = projected.drawnTile)
}
