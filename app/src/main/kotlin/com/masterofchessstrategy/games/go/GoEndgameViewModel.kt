package com.masterofchessstrategy.games.go

import androidx.lifecycle.ViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.masterofchessstrategy.engine.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.concurrent.atomic.AtomicBoolean
import java.time.LocalDate
import kotlin.random.Random

class GoEndgameViewModel(
    private val repository: GoEndgameProgressRepository,
    private val worker: CoroutineDispatcher = Dispatchers.Default,
    private val engineFactory: (Int, Int, Int) -> GoRecordEngine = { size, komi, handicap -> NativeGoEngine(size, komi, handicap) },
    private val today: () -> LocalDate = { LocalDate.now() },
    private val random: Random = Random.Default,
    private val initialPuzzleId: String? = null,
    private val initialRequestState: SavedStateHandle? = null,
) : ViewModel() {
    private val mutableState = MutableStateFlow(GoEndgameUiState())
    val state: StateFlow<GoEndgameUiState> = mutableState.asStateFlow()
    private val disposed = AtomicBoolean(false)
    private var session: GoEndgameSession? = null
    private var operation: Job? = null
    private var pendingCompletion: String? = null
    private var pendingSelection: Selection? = null
    private var pendingInitialPuzzleId = initialPuzzleId.takeUnless {
        initialRequestState?.get<Any>(INITIAL_REQUEST_CONSUMED) == true
    }
    private sealed interface Selection {
        data class Puzzle(val id: String, val mode: GoEndgameChallengeMode) : Selection
        data class Daily(val date: LocalDate) : Selection
    }

    init {
        require(initialPuzzleId == null || GoEndgameLibrary.puzzle(initialPuzzleId) != null)
        reload()
    }

    private data class Loaded(val progress: GoEndgameProgress, val session: GoEndgameSession?, val lockedId: String? = null)

    private fun reload() {
        if (disposed.get() || operation?.isActive == true) return
        mutableState.value = mutableState.value.copy(loading = true, error = null)
        operation = viewModelScope.launch {
            try {
                val loaded = withContext(worker) {
                    val progress = repository.load()
                    val requested = pendingInitialPuzzleId
                    if (requested != null && !progress.isUnlocked(requested)) Loaded(progress, null, requested)
                    else {
                        // Validate the real exercise before persisting a new selection. The repository
                        // rechecks the current prefix, so a concurrent replacement cannot bypass it.
                        val next = createSession(requested ?: progress.lastPuzzleId)
                        val committed = if (requested == null) progress
                            else repository.select(requested, GoEndgameChallengeMode.THEME)
                        Loaded(committed, next)
                    }
                }
                session = loaded.session
                val progress = loaded.progress
                if (loaded.lockedId == null) consumeInitialRequest()
                if (!disposed.get()) mutableState.value = GoEndgameUiState(loading = false,
                    exercise = loaded.session?.state(), completedIds = progress.completedIds, mode = progress.lastMode,
                    dailyDate = progress.dailyDate, lockedRequestedPuzzleId = loaded.lockedId)
            } catch (error: Exception) { report(error, "围棋残局加载失败，原进度已保留。") }
        }
    }

    private fun consumeInitialRequest() {
        pendingInitialPuzzleId = null
        if (initialPuzzleId != null) initialRequestState?.set(INITIAL_REQUEST_CONSUMED, true)
    }

    fun openRequiredMainline() {
        val previous = mutableState.value
        if (disposed.get() || operation?.isActive == true || previous.loading || previous.needsSave ||
            previous.error != null || previous.lockedRequestedPuzzleId == null) return
        val firstIncomplete = GoEndgameLibrary.puzzles.firstOrNull { it.id !in previous.completedIds } ?: return
        selectAndSave(Selection.Puzzle(firstIncomplete.id, GoEndgameChallengeMode.MAINLINE))
    }

    fun select(id: String, mode: GoEndgameChallengeMode = GoEndgameChallengeMode.MAINLINE) {
        val previous = mutableState.value
        if (disposed.get() || operation?.isActive == true || !previous.canOperate || !previous.isUnlocked(id)) return
        if (mode == GoEndgameChallengeMode.DAILY) return
        selectAndSave(Selection.Puzzle(id, mode))
    }

    fun openDaily() {
        if (disposed.get() || operation?.isActive == true || !mutableState.value.canOperate) return
        selectAndSave(Selection.Daily(today()))
    }

    fun openRandom(theme: GoEndgameTheme? = null, level: GoEndgameLevel? = null) {
        val previous = mutableState.value
        if (disposed.get() || operation?.isActive == true || !previous.canOperate) return
        val puzzle = GoEndgameChallenges.random(GoEndgameProgress(previous.completedIds), random, theme, level) ?: return
        selectAndSave(Selection.Puzzle(puzzle.id, GoEndgameChallengeMode.RANDOM))
    }

    private fun selectAndSave(request: Selection) {
        val previous = mutableState.value
        pendingSelection = request
        mutableState.value = previous.copy(loading = true, error = null)
        operation = viewModelScope.launch {
            try {
                val (progress, next) = withContext(worker + NonCancellable) {
                    val stored = repository.load()
                    val id = when (request) {
                        is Selection.Puzzle -> request.id
                        is Selection.Daily -> GoEndgameChallenges.daily(stored, request.date).id
                    }
                    val next = createSession(id)
                    val progress = when (request) {
                        is Selection.Puzzle -> repository.select(id, request.mode)
                        is Selection.Daily -> repository.selectDaily(request.date, id)
                    }
                    progress to next
                }
                session = next; pendingSelection = null
                consumeInitialRequest()
                if (!disposed.get()) mutableState.value = previous.copy(loading = false, exercise = next.state(),
                    completedIds = progress.completedIds, error = null, mode = progress.lastMode, dailyDate = progress.dailyDate,
                    lockedRequestedPuzzleId = null)
            } catch (error: Exception) { report(error, "围棋残局切换未保存，原关卡已保留。") }
        }
    }

    fun answer(point: BoardPosition) {
        val previous = mutableState.value
        val current = session ?: return
        if (disposed.get() || operation?.isActive == true || !previous.canOperate || previous.exercise?.completed == true) return
        mutableState.value = previous.copy(loading = true, error = null)
        operation = viewModelScope.launch {
            try {
                val result = withContext(worker) { current.answer(point) }
                if (result.completed && result.puzzle.id !in previous.completedIds) {
                    pendingCompletion = result.puzzle.id
                    mutableState.value = previous.copy(loading = true, exercise = result, needsSave = true)
                    persistCompletion()
                } else if (!disposed.get()) mutableState.value = previous.copy(loading = false, exercise = result)
            } catch (error: Exception) { report(error, "围棋残局操作失败，题面已保留。") }
        }
    }

    private suspend fun persistCompletion() {
        val id = checkNotNull(pendingCompletion)
        try {
            val progress = withContext(worker + NonCancellable) { repository.complete(id) }
            pendingCompletion = null
            if (!disposed.get()) mutableState.value = mutableState.value.copy(loading = false, needsSave = false,
                completedIds = progress.completedIds, error = null)
        } catch (error: Exception) { report(error, "本题已解，通关进度未保存，请重试。") }
    }

    fun reset() {
        val previous = mutableState.value
        val current = session ?: return
        if (disposed.get() || operation?.isActive == true || !previous.canOperate) return
        mutableState.value = previous.copy(loading = true, error = null)
        operation = viewModelScope.launch {
            try {
                val result = withContext(worker) { current.reset() }
                if (!disposed.get()) mutableState.value = previous.copy(loading = false, exercise = result)
            } catch (error: Exception) { report(error, "围棋残局重置失败，请重试。") }
        }
    }

    fun retry() {
        if (disposed.get() || operation?.isActive == true) return
        when {
            pendingCompletion != null -> {
                mutableState.value = mutableState.value.copy(loading = true, error = null)
                operation = viewModelScope.launch { persistCompletion() }
            }
            pendingSelection != null -> selectAndSave(checkNotNull(pendingSelection))
            else -> reload()
        }
    }

    private fun createSession(id: String) = GoEndgameSession(
        GoEndgameLibrary.open(checkNotNull(GoEndgameLibrary.puzzle(id)), engineFactory), engineFactory)

    private fun report(error: Exception, message: String) {
        if (error is CancellationException) throw error
        if (!disposed.get()) mutableState.value = mutableState.value.copy(loading = false,
            needsSave = pendingCompletion != null, error = message)
    }

    override fun onCleared() {
        disposed.set(true)
        super.onCleared()
    }

    private companion object { const val INITIAL_REQUEST_CONSUMED = "go.practice.initialRequestConsumed" }
}
