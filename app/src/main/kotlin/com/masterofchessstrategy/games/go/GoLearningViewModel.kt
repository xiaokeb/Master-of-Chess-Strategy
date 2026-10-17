package com.masterofchessstrategy.games.go

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.masterofchessstrategy.engine.GoRuleEngine
import com.masterofchessstrategy.engine.NativeGoEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class GoLearningUiState(
    val exercise: GoLessonState? = null,
    val progress: GoLearningProgress = GoLearningProgress(),
    val loading: Boolean = true,
    val saving: Boolean = false,
    val needsSave: Boolean = false,
    val error: String? = null,
) {
    val canOperate: Boolean get() = exercise != null && !loading && !saving && !needsSave
    val tutorialCompleted: Boolean get() = exercise != null && !loading && error == null &&
        progress.isCompleted && !needsSave && !saving
}

class GoLearningViewModel(
    private val repository: GoLearningProgressRepository,
    private val engineFactory: (Int, Int, Int) -> GoRuleEngine = { size, komi, handicap ->
        NativeGoEngine(size, komi, handicap)
    },
) : ViewModel() {
    private val mutex = Mutex()
    private var session: GoLessonSession? = null
    private var progress = GoLearningProgress()
    private val mutableState = MutableStateFlow(GoLearningUiState())
    val state: StateFlow<GoLearningUiState> = mutableState

    init { reload() }

    fun selectLesson(id: String) {
        if (!mutableState.value.canOperate) return
        viewModelScope.launch {
            mutex.withLock {
                if (!mutableState.value.canOperate) return@withLock
                try {
                    openLesson(checkNotNull(GoLearningLibrary.lesson(id)))
                    progress = progress.copy(lastLessonId = id)
                    persist()
                } catch (error: Exception) { report(error) }
            }
        }
    }

    fun answer(answer: GoLessonAnswer) {
        if (!mutableState.value.canOperate) return
        viewModelScope.launch {
            mutex.withLock {
                if (!mutableState.value.canOperate) return@withLock
                try {
                    val exercise = checkNotNull(session).answer(answer)
                    if (exercise.completed && exercise.lesson.id !in progress.completedLessonIds) {
                        progress = progress.copy(completedLessonIds = progress.completedLessonIds + exercise.lesson.id)
                        persist()
                    } else publish()
                } catch (error: Exception) { report(error) }
            }
        }
    }

    fun restart() = mutableState.value.exercise?.let { selectLesson(it.lesson.id) }

    fun retry() {
        if (mutableState.value.loading || mutableState.value.saving) return
        if (session == null) reload() else viewModelScope.launch { mutex.withLock { persist() } }
    }

    private fun reload() {
        viewModelScope.launch {
            mutex.withLock {
                mutableState.value = mutableState.value.copy(loading = true, error = null)
                try {
                    progress = repository.load()
                    openLesson(checkNotNull(GoLearningLibrary.lesson(progress.lastLessonId)))
                    publish()
                } catch (error: Exception) { report(error) }
            }
        }
    }

    private fun openLesson(lesson: GoLesson) {
        val next = GoLessonSession(lesson, engineFactory(
            lesson.boardSize, if (lesson.handicap > 0) 0 else 15, lesson.handicap,
        ))
        session?.close()
        session = next
    }

    private suspend fun persist() {
        publish(saving = true)
        try {
            repository.save(progress)
            val committed = repository.load()
            check(committed.completedLessonIds.containsAll(progress.completedLessonIds)) {
                "教程进度写入后核验失败"
            }
            // Keep this page's lesson selection, but publish the repository's merged completion set.
            progress = progress.copy(completedLessonIds = committed.completedLessonIds.toSet())
            publish()
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            publish(needsSave = true, error = error.message ?: "教程进度保存失败")
        }
    }

    private fun report(error: Exception) {
        if (error is CancellationException) throw error
        session?.close()
        session = null
        mutableState.value = mutableState.value.copy(
            exercise = null, loading = false, saving = false, error = error.message ?: "无法打开围棋教程",
        )
    }

    private fun publish(saving: Boolean = false, needsSave: Boolean = false, error: String? = null) {
        mutableState.value = GoLearningUiState(session?.state(), progress,
            loading = false, saving = saving, needsSave = needsSave, error = error)
    }

    override fun onCleared() {
        session?.close()
        session = null
        super.onCleared()
    }
}
