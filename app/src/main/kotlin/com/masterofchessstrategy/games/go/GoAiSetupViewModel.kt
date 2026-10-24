package com.masterofchessstrategy.games.go

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.masterofchessstrategy.engine.Difficulty
import com.masterofchessstrategy.engine.GoPhase
import com.masterofchessstrategy.settings.LocalDataWriterRetirement
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class GoAiSetupState(
    val loading: Boolean = true,
    val busy: Boolean = false,
    val snapshot: GoAiEntrySnapshot? = null,
    val pendingConfig: GoAiMatchConfig? = null,
    val readyMatch: GoAiSavedMatch? = null,
    val error: String? = null,
)

/** Persist first, navigate second. Old reads and retired writers cannot open a new page. */
internal class GoAiSetupViewModel(
    private val repository: GoAiEntryRepository,
    private val worker: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel(), LocalDataWriterRetirement {
    private val mutableState = MutableStateFlow(GoAiSetupState())
    val state = mutableState.asStateFlow()
    private val disposed = AtomicBoolean(false)
    private val tasks = mutableListOf<Job>() // Main-thread owned; includes cancelled reads still draining.
    private var reading: Job? = null
    private var generation = 0L
    private var hasResumed = false
    private val retirement = CompletableDeferred<Unit>()
    override val retirementCompletion: Deferred<Unit> = retirement

    init { reload() }

    fun resume() {
        if (hasResumed || !mutableState.value.loading) reload()
        hasResumed = true
    }

    fun reload() {
        if (disposed.get() || mutableState.value.busy) return
        val request = ++generation
        reading?.cancel()
        mutableState.value = GoAiSetupState()
        reading = owned {
            try {
                val snapshot = withContext(worker) { repository.load() }
                ensureActive()
                checkCurrent()
                if (!disposed.get() && request == generation) {
                    mutableState.value = GoAiSetupState(loading = false, snapshot = snapshot)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (!disposed.get() && request == generation) {
                    mutableState.value = GoAiSetupState(loading = false,
                        error = error.message ?: "围棋对局与解锁进度读取失败，请重试")
                }
            }
        }
    }

    fun continueMatch() {
        val current = mutableState.value
        if (disposed.get() || current.loading || current.busy || current.pendingConfig != null) return
        if (!repository.isCurrent) { invalidate(); return }
        val saved = current.snapshot?.active ?: return
        mutableState.value = current.copy(readyMatch = saved, error = null)
    }

    fun requestCreate(config: GoAiMatchConfig) {
        val current = mutableState.value
        if (disposed.get() || current.loading || current.busy || current.readyMatch != null) return
        if (!repository.isCurrent) { invalidate(); return }
        val snapshot = current.snapshot ?: return
        val difficulty = when (config.difficulty) {
            GoAiDifficulty.SIMPLE -> Difficulty.EASY
            GoAiDifficulty.MEDIUM -> Difficulty.MEDIUM
            GoAiDifficulty.HARD -> Difficulty.HARD
            GoAiDifficulty.MASTER -> Difficulty.MASTER
        }
        if (!snapshot.eligibility.condition(difficulty).isUnlocked) {
            mutableState.value = current.copy(error = "请先完成十课教程及对应难度的有效胜场")
        } else if (snapshot.phase == GoPhase.FINISHED && !snapshot.completionStored) {
            mutableState.value = current.copy(error = "请先查看并保存上一局结果，再创建新局")
        } else if (snapshot.active != null && snapshot.phase != GoPhase.FINISHED) {
            mutableState.value = current.copy(pendingConfig = config, error = null)
        } else create(config, abandonOngoing = false)
    }

    fun dismissAbandon() {
        if (!disposed.get() && !mutableState.value.busy) {
            mutableState.value = mutableState.value.copy(pendingConfig = null)
        }
    }

    fun confirmAbandon() {
        val config = mutableState.value.pendingConfig ?: return
        if (!disposed.get() && !mutableState.value.busy) create(config, abandonOngoing = true)
    }

    /** One Main-thread handoff. A retired owner or replaced epoch cannot dispatch stale navigation. */
    fun takeReadyMatch(): GoAiSavedMatch? {
        if (disposed.get()) return null
        if (!repository.isCurrent) { invalidate(); return null }
        val match = mutableState.value.readyMatch ?: return null
        mutableState.value = mutableState.value.copy(readyMatch = null)
        return match
    }

    private fun create(config: GoAiMatchConfig, abandonOngoing: Boolean) {
        val previous = mutableState.value.snapshot?.active
        mutableState.value = mutableState.value.copy(busy = true, pendingConfig = null,
            readyMatch = null, error = null)
        owned {
            try {
                // Once admitted, a transaction drains before retirement completes. Work still
                // queued on the worker is rejected when retirement has already sealed this VM.
                val saved = withContext(worker + NonCancellable) {
                    if (disposed.get()) null else repository.create(config, previous, abandonOngoing)
                }
                if (!disposed.get() && saved != null) {
                    checkCurrent()
                    mutableState.value = mutableState.value.copy(busy = false, readyMatch = saved)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                // A lost acknowledgement may already have committed a new UUID. Refresh the
                // authoritative active match; never blindly repeat creation or reuse old facts.
                var refreshed: GoAiEntrySnapshot? = null
                if (!disposed.get() && repository.isCurrent) {
                    try { refreshed = withContext(worker + NonCancellable) { repository.load() } }
                    catch (_: Exception) { /* No stale eligibility survives a failed refresh. */ }
                }
                if (!disposed.get()) {
                    mutableState.value = GoAiSetupState(loading = false,
                        snapshot = if (repository.isCurrent) refreshed else null,
                        error = failure.message ?: "新局未确认，请检查当前对局后重试")
                }
            }
        }
    }

    private fun checkCurrent() {
        check(repository.isCurrent) { "本地数据已替换，请重新打开围棋设置" }
    }

    private fun invalidate() {
        mutableState.value = GoAiSetupState(loading = false, error = "本地数据已替换，请重新打开围棋设置")
    }

    private fun owned(block: suspend CoroutineScope.() -> Unit): Job =
        viewModelScope.launch(start = CoroutineStart.LAZY, block = block).also {
            tasks.add(it)
            it.start()
        }

    override fun beginRetirement(): Deferred<Unit> {
        if (!disposed.compareAndSet(false, true)) return retirementCompletion
        generation++
        mutableState.value = mutableState.value.copy(readyMatch = null, pendingConfig = null)
        val draining = tasks.toList()
        draining.forEach { it.cancel() }
        val cleanup = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        cleanup.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                withContext(NonCancellable) { draining.joinAll() }
                retirement.complete(Unit)
            } catch (error: Throwable) {
                retirement.completeExceptionally(error)
            } finally { cleanup.cancel() }
        }
        return retirementCompletion
    }

    override fun onCleared() { beginRetirement(); super.onCleared() }
}
