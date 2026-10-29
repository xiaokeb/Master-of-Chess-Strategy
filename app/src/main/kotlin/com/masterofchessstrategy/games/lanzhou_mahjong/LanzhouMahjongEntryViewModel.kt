package com.masterofchessstrategy.games.lanzhou_mahjong

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.masterofchessstrategy.games.lanzhou_mahjong.data.LanzhouMahjongEntryFacts
import com.masterofchessstrategy.games.lanzhou_mahjong.data.LanzhouMahjongEntryRepository
import com.masterofchessstrategy.settings.LocalDataWriterRetirement
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class LanzhouMahjongEntryState(
    val loading: Boolean = true,
    val facts: LanzhouMahjongEntryFacts? = null,
    val error: String? = null,
    val retired: Boolean = false,
)

/** Read-only menu facts. The host separately reauthorizes every new game and restart. */
internal class LanzhouMahjongEntryViewModel(
    private val repository: LanzhouMahjongEntryRepository,
    worker: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel(), LocalDataWriterRetirement {
    private val admission = Any()
    private val readerScope = CoroutineScope(SupervisorJob() + worker)
    private val ownerEpoch = repository.ownerEpoch
    private val mutableState = MutableStateFlow(LanzhouMahjongEntryState())
    val state = mutableState.asStateFlow()
    private var acceptedRead = false
    private var refreshPending = false
    private var hasResumed = false
    private var retired = false
    private var epochWatcher: Job? = null
    private val retirement = CompletableDeferred<Unit>()
    override val retirementCompletion: Deferred<Unit> get() = retirement
    @Volatile internal var lastFailure: Throwable? = null
        private set

    init {
        reload()
        epochWatcher = viewModelScope.launch {
            repository.epoch.first { it != ownerEpoch }
            beginRetirement()
        }
    }

    /** The initial lifecycle resume reuses construction; later returns always request fresh facts. */
    fun resume() {
        val refresh = synchronized(admission) {
            val hadResumed = hasResumed
            hasResumed = true
            hadResumed
        }
        if (refresh) reload()
    }

    fun reload() {
        synchronized(admission) {
            if (!currentOwner()) return
            if (acceptedRead) { refreshPending = true; return }
            acceptedRead = true
            mutableState.value = LanzhouMahjongEntryState()
        }
        readerScope.launch {
            try {
                if (!currentOwner()) return@launch
                val facts = withContext(NonCancellable) { repository.loadFacts() }
                synchronized(admission) {
                    if (currentOwner() && !refreshPending) {
                        lastFailure = null
                        mutableState.value = LanzhouMahjongEntryState(loading = false, facts = facts)
                    }
                }
            } catch (failure: Exception) {
                recordFailure(failure)
            } catch (failure: LinkageError) {
                recordFailure(failure)
            } finally {
                val refresh = synchronized(admission) {
                    acceptedRead = false
                    if (retired) finishRetirement()
                    val repeat = refreshPending && !retired
                    refreshPending = false
                    repeat
                }
                if (refresh) reload()
            }
        }
    }

    private fun recordFailure(failure: Throwable) = synchronized(admission) {
        lastFailure = failure
        if (currentOwner() && !refreshPending) {
            mutableState.value = LanzhouMahjongEntryState(loading = false,
                error = "菜单进度读取失败，请重试。原记录已保留")
        }
    }

    private fun currentOwner(): Boolean = synchronized(admission) {
        if (repository.epoch.value != ownerEpoch) beginRetirement()
        !retired
    }

    override fun beginRetirement(): Deferred<Unit> = synchronized(admission) {
        retired = true
        refreshPending = false
        epochWatcher?.cancel()
        mutableState.value = LanzhouMahjongEntryState(loading = false, retired = true,
            error = "本地数据已更换或页面已关闭，请返回后重新进入")
        if (!acceptedRead) finishRetirement()
        retirementCompletion
    }

    private fun finishRetirement() {
        readerScope.cancel()
        retirement.complete(Unit)
    }

    override fun onCleared() { beginRetirement(); super.onCleared() }
}
