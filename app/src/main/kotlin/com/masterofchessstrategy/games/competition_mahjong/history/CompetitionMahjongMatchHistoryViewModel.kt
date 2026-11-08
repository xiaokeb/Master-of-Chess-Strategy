package com.masterofchessstrategy.games.competition_mahjong.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class CompetitionMahjongMatchHistoryUiState(
    val history: CompetitionMahjongMatchHistory = CompetitionMahjongMatchHistory.empty(),
    val selectedMatchId: String? = null,
    val loading: Boolean = true,
    val busy: Boolean = false,
    val closed: Boolean = false,
    val notice: String? = null,
    val error: String? = null,
) {
    val selected: CompetitionMahjongMatchHistoryEntry?
        get() = selectedMatchId?.let { id -> history.entries.firstOrNull { it.matchId == id } }
}

class CompetitionMahjongMatchHistoryViewModel(
    private val store: CompetitionMahjongMatchHistoryStore,
    private val viewerPlayerId: Int,
    parentScope: CoroutineScope? = null,
    private val worker: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private val scope = parentScope ?: viewModelScope
    private val mutex = Mutex()
    private val mutableState = MutableStateFlow(CompetitionMahjongMatchHistoryUiState())
    val state = mutableState.asStateFlow()
    private var closed = false
    private val lifetime = scope.launch {
        try { awaitCancellation() }
        finally { withContext(NonCancellable) { mutex.withLock {
            closed = true
            mutableState.value = mutableState.value.copy(loading = false, busy = false, closed = true)
        } } }
    }

    init {
        require(viewerPlayerId in 0..3)
        refresh()
    }

    fun refresh(): Job = transaction(loading = true) {
        val history = store.load(viewerPlayerId)
        require(history.entries.all { it.viewerPlayerId == viewerPlayerId })
        mutableState.value.copy(history = history,
            selectedMatchId = mutableState.value.selectedMatchId?.takeIf { id ->
                history.entries.any { it.matchId == id }
            }, loading = false, busy = false, notice = null, error = null)
    }

    fun select(matchId: String?) {
        if (closed) return
        val current = mutableState.value
        if (matchId == null || current.history.entries.any { it.matchId == matchId })
            mutableState.value = current.copy(selectedMatchId = matchId, notice = null, error = null)
    }

    fun setFavorite(matchId: String, favorite: Boolean): Job = transaction {
        val history = store.setFavorite(viewerPlayerId, matchId, favorite)
        mutableState.value.copy(history = history, busy = false, notice = if (favorite) "已收藏成绩单" else "已取消收藏")
    }

    fun delete(matchId: String): Job = transaction {
        val history = store.delete(viewerPlayerId, matchId)
        mutableState.value.copy(history = history,
            selectedMatchId = mutableState.value.selectedMatchId.takeUnless { it == matchId },
            busy = false, notice = "成绩单已删除")
    }

    private fun transaction(loading: Boolean = false,
                            block: suspend () -> CompetitionMahjongMatchHistoryUiState): Job = scope.launch {
        withContext(worker + NonCancellable) {
            mutex.withLock {
                if (closed) return@withLock
                mutableState.value = mutableState.value.copy(
                    loading = loading || mutableState.value.loading, busy = !loading,
                    notice = null, error = null)
                try {
                    mutableState.value = block()
                } catch (failure: Exception) {
                    if (failure is CancellationException) throw failure
                    mutableState.value = mutableState.value.copy(loading = false, busy = false,
                        error = "国标麻将成绩历史操作失败，原数据已保留")
                }
            }
        }
    }
}
