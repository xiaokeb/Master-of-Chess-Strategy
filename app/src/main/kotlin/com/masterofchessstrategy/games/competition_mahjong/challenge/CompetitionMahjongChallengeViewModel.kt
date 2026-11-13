package com.masterofchessstrategy.games.competition_mahjong.challenge

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDate
import kotlin.random.Random

data class CompetitionMahjongChallengeUiState(
    val loading: Boolean = true,
    val exercise: CompetitionMahjongChallengeState? = null,
    val completedIds: Set<String> = emptySet(),
    val mode: CompetitionMahjongChallengeMode = CompetitionMahjongChallengeMode.MAINLINE,
    val dailyDate: LocalDate? = null,
    val needsSave: Boolean = false,
    val closed: Boolean = false,
    val error: String? = null,
) {
    val canOperate: Boolean get() = !loading && !needsSave && !closed && error == null && exercise != null
    fun isUnlocked(id: String): Boolean {
        val index = CompetitionMahjongChallengeLibrary.challenges.indexOfFirst { it.id == id }
        return index >= 0 && index <= CompetitionMahjongChallengeLibrary.challenges
            .takeWhile { it.id in completedIds }.size
    }
}

class CompetitionMahjongChallengeViewModel(
    private val repository: CompetitionMahjongChallengeProgressRepository,
    parentScope: CoroutineScope? = null,
    private val worker: CoroutineDispatcher = Dispatchers.Default,
    private val today: () -> LocalDate = { LocalDate.now() },
    private val random: Random = Random.Default,
) : ViewModel() {
    private val scope = parentScope ?: viewModelScope
    private val mutex = Mutex()
    private val mutableState = MutableStateFlow(CompetitionMahjongChallengeUiState())
    val state = mutableState.asStateFlow()
    private var session: CompetitionMahjongChallengeSession? = null
    private var pendingCompletion: String? = null
    private var closed = false
    private val lifetime = scope.launch {
        try { awaitCancellation() }
        finally { withContext(NonCancellable) { mutex.withLock { closed = true; publish(closed = true) } } }
    }

    init { reload() }

    fun reload(): Job = operation("牌型训练加载失败，原进度已保留") {
        val progress = repository.load()
        val candidate = createSession(progress.lastChallengeId)
        session = candidate
        pendingCompletion = null
        publish(progress = progress, exercise = candidate.state())
    }

    fun select(id: String, mode: CompetitionMahjongChallengeMode = CompetitionMahjongChallengeMode.MAINLINE): Job =
        operation("牌型训练切换未保存，原题已保留") {
            require(mode != CompetitionMahjongChallengeMode.DAILY)
            require(mutableState.value.isUnlocked(id)) { "牌型题尚未解锁" }
            val candidate = createSession(id)
            val progress = repository.select(id, mode)
            session = candidate
            pendingCompletion = null
            publish(progress = progress, exercise = candidate.state())
        }

    fun openDaily(): Job = operation("每日牌型未保存，原题已保留") {
        val date = today()
        val stored = repository.load()
        val selected = CompetitionMahjongChallenges.daily(stored, date)
        val candidate = createSession(selected.id)
        val progress = repository.selectDaily(date, selected.id)
        session = candidate
        pendingCompletion = null
        publish(progress = progress, exercise = candidate.state())
    }

    fun openRandom(theme: CompetitionMahjongChallengeTheme? = null,
                   level: CompetitionMahjongChallengeLevel? = null): Job =
        operation("随机牌型未保存，原题已保留") {
            val stored = repository.load()
            val selected = CompetitionMahjongChallenges.random(stored, random, theme, level)
                ?: error("当前筛选没有已解锁题目")
            val candidate = createSession(selected.id)
            val progress = repository.select(selected.id, CompetitionMahjongChallengeMode.RANDOM)
            session = candidate
            pendingCompletion = null
            publish(progress = progress, exercise = candidate.state())
        }

    fun answer(choiceId: String): Job = operation("牌型题操作失败，题面已保留") {
        val active = checkNotNull(session)
        val previous = active.state()
        require(!previous.completed && !mutableState.value.needsSave)
        val result = active.answer(choiceId)
        if (result.completed && result.challenge.id !in mutableState.value.completedIds) {
            pendingCompletion = result.challenge.id
            publish(exercise = result, needsSave = true)
            persistCompletion()
        } else publish(exercise = result)
    }

    fun reset(): Job = operation("牌型题重置失败") {
        require(pendingCompletion == null)
        publish(exercise = checkNotNull(session).reset())
    }

    fun retry(): Job = operation("牌型题进度仍未保存，请重试") {
        if (pendingCompletion != null) persistCompletion() else {
            val progress = repository.load()
            val candidate = createSession(progress.lastChallengeId)
            session = candidate
            publish(progress = progress, exercise = candidate.state())
        }
    }

    private suspend fun persistCompletion() {
        val id = checkNotNull(pendingCompletion)
        val progress = repository.complete(id)
        pendingCompletion = null
        publish(progress = progress, needsSave = false)
    }

    private fun createSession(id: String) = CompetitionMahjongChallengeSession(
        checkNotNull(CompetitionMahjongChallengeLibrary.challenge(id)) { "牌型题不存在" })

    private fun operation(message: String, block: suspend () -> Unit): Job = scope.launch {
        withContext(worker + NonCancellable) {
            mutex.withLock {
                if (closed) return@withLock
                mutableState.value = mutableState.value.copy(loading = true, error = null)
                try { block() }
                catch (failure: Exception) {
                    if (failure is CancellationException) throw failure
                    mutableState.value = mutableState.value.copy(loading = false,
                        needsSave = pendingCompletion != null, error = message)
                }
                if (mutableState.value.loading) mutableState.value = mutableState.value.copy(loading = false)
            }
        }
    }

    private fun publish(
        progress: CompetitionMahjongChallengeProgress? = null,
        exercise: CompetitionMahjongChallengeState? = mutableState.value.exercise,
        needsSave: Boolean = mutableState.value.needsSave,
        closed: Boolean = this.closed,
    ) {
        mutableState.value = CompetitionMahjongChallengeUiState(
            loading = mutableState.value.loading,
            exercise = exercise,
            completedIds = progress?.completedIds ?: mutableState.value.completedIds,
            mode = if (progress != null) progress.lastMode else mutableState.value.mode,
            dailyDate = if (progress != null) progress.dailyDate else mutableState.value.dailyDate,
            needsSave = needsSave,
            closed = closed,
            error = null,
        )
    }
}
