package com.masterofchessstrategy.games.lanzhou_mahjong

import com.masterofchessstrategy.games.lanzhou_mahjong.data.LanzhouMahjongLearningAdvance
import com.masterofchessstrategy.games.lanzhou_mahjong.data.LanzhouMahjongLearningConflictException
import com.masterofchessstrategy.games.lanzhou_mahjong.data.LanzhouMahjongLearningRepository
import com.masterofchessstrategy.games.lanzhou_mahjong.data.LanzhouMahjongLearningSnapshot
import com.masterofchessstrategy.settings.LocalDataWriterRetirement
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

// Legacy draft types remain for the unconnected generic four-step Store.
internal data class LanzhouMahjongLearningProgress(val completedCount: Int) {
    init { require(completedCount in 0..LanzhouMahjongLearningLibrary.lessons.size) }
    val isCompleted: Boolean get() = completedCount == LanzhouMahjongLearningLibrary.lessons.size
}

internal interface LanzhouMahjongLearningStore {
    suspend fun load(): LanzhouMahjongLearningProgress?
    suspend fun save(progress: LanzhouMahjongLearningProgress)
}

internal data class LanzhouMahjongLearningState(
    val loaded: Boolean = false,
    val isBusy: Boolean = false,
    val lessonIndex: Int = 0,
    val completedCount: Int = 0,
    val feedback: String? = null,
    val hasPendingSave: Boolean = false,
    val retired: Boolean = false,
    val requiresReload: Boolean = false,
) {
    val lesson: LanzhouMahjongLesson get() = LanzhouMahjongLearningLibrary.lessons[lessonIndex]
    val isCompleted: Boolean get() = completedCount == LanzhouMahjongLearningLibrary.lessons.size
}

/** Only a confirmed exact snapshot unlocks a lesson. Accepted work survives caller cancellation. */
internal class LanzhouMahjongLearningController(
    private val repository: LanzhouMahjongLearningRepository,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val now: () -> Long = System::currentTimeMillis,
) : LocalDataWriterRetirement {
    private val admission = Any()
    private var accepted = false
    private var retired = false
    private val retirement = CompletableDeferred<Unit>()
    override val retirementCompletion: Deferred<Unit> get() = retirement
    private val ownerEpoch = repository.ownerEpoch
    private var snapshot: LanzhouMahjongLearningSnapshot? = null
    private var pending: LanzhouMahjongLearningAdvance? = null
    private val mutableState = MutableStateFlow(LanzhouMahjongLearningState())
    val state: StateFlow<LanzhouMahjongLearningState> = mutableState.asStateFlow()
    @Volatile internal var lastFailure: Throwable? = null
        private set

    suspend fun load() = operate {
        if (state.value.loaded && !state.value.requiresReload) return@operate
        if (pending != null && !state.value.requiresReload) return@operate
        publish(state.value.copy(loaded = false))
        try {
            val loaded = repository.load()
            if (!currentOwner()) return@operate
            snapshot = loaded
            pending = null
            val count = loaded?.completedCount ?: 0
            publish(LanzhouMahjongLearningState(loaded = true, isBusy = true,
                lessonIndex = count.coerceAtMost(LanzhouMahjongLearningLibrary.lessons.lastIndex),
                completedCount = count))
            lastFailure = null
        } catch (error: Exception) {
            lastFailure = error
            publish(state.value.copy(feedback = "教程进度读取失败，请重试。原记录已保留"))
        }
    }

    suspend fun selectLesson(index: Int) = operate {
        val state = state.value
        if (state.loaded && pending == null && index in 0..state.completedCount &&
            index in LanzhouMahjongLearningLibrary.lessons.indices) {
            publish(state.copy(lessonIndex = index, feedback = null))
        }
    }

    suspend fun submit(answerIndex: Int) = operate {
        val state = state.value
        if (!state.loaded || pending != null) return@operate
        val lesson = state.lesson
        if (answerIndex !in lesson.options.indices) return@operate
        if (answerIndex != lesson.answerIndex) {
            publish(state.copy(feedback = "再想一想：${lesson.explanation}"))
        } else if (state.lessonIndex < state.completedCount) {
            publish(state.copy(feedback = "答对了：${lesson.answerExplanation}"))
        } else {
            try {
                pending = LanzhouMahjongLearningAdvance(snapshot,
                    LanzhouMahjongLearningSnapshot(LanzhouMahjongLearningLibrary.CONTENT_VERSION,
                        state.completedCount + 1, maxOf(now(), snapshot?.updatedAtEpochMillis ?: 0)))
                publish(state.copy(hasPendingSave = true))
                savePending()
            } catch (error: Exception) {
                lastFailure = error
                publish(state.copy(hasPendingSave = pending != null,
                    feedback = "教程进度保存失败，请重试。当前进度尚未确认"))
            }
        }
    }

    suspend fun retrySave() = operate {
        if (pending != null && !state.value.requiresReload) savePending()
    }

    private suspend fun savePending() {
        val request = checkNotNull(pending)
        try {
            if (!currentOwner()) return
            val saved = repository.advance(request)
            check(saved == request.after) { "Learning repository returned an unexpected snapshot" }
            if (!currentOwner()) return
            snapshot = saved
            pending = null
            lastFailure = null
            publish(state.value.copy(completedCount = saved.completedCount, hasPendingSave = false,
                feedback = "答对了：${state.value.lesson.answerExplanation}"))
        } catch (error: Exception) {
            lastFailure = error
            publish(state.value.copy(requiresReload = error is LanzhouMahjongLearningConflictException,
                feedback = if (error is LanzhouMahjongLearningConflictException)
                    "教程进度已变化，请重新读取。原记录已保留"
                else "教程进度保存失败，请重试。当前进度尚未确认"))
        }
    }

    private fun currentOwner(): Boolean = synchronized(admission) {
        if (repository.epoch.value != ownerEpoch) beginRetirement()
        !retired
    }

    private fun publish(next: LanzhouMahjongLearningState) = synchronized(admission) {
        if (!retired) mutableState.value = next
    }

    override fun beginRetirement(): Deferred<Unit> = synchronized(admission) {
        retired = true
        mutableState.value = state.value.copy(retired = true,
            feedback = "本地数据已更换或页面已关闭，请返回后重新进入")
        if (!accepted) retirement.complete(Unit)
        retirementCompletion
    }

    private suspend fun operate(operation: suspend () -> Unit) {
        synchronized(admission) {
            if (!currentOwner() || accepted) return
            accepted = true
            mutableState.value = state.value.copy(isBusy = true)
        }
        try {
            withContext(dispatcher + NonCancellable) {
                if (currentOwner()) operation()
            }
        } finally {
            synchronized(admission) {
                accepted = false
                mutableState.value = state.value.copy(isBusy = false)
                if (retired) retirement.complete(Unit)
            }
        }
    }
}
