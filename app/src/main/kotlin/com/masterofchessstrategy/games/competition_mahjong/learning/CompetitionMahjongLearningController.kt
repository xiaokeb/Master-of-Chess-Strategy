package com.masterofchessstrategy.games.competition_mahjong.learning

import com.masterofchessstrategy.settings.LocalDataWriterRetirement
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

internal data class CompetitionMahjongLearningState(
    val loaded: Boolean = false,
    val busy: Boolean = false,
    val chapterIndex: Int = 0,
    val cardIndex: Int = 0,
    val completedChapterCount: Int = 0,
    val selectedTiles: Set<Int> = emptySet(),
    val selectedOption: Int? = null,
    val solved: Boolean = false,
    val feedback: String? = null,
    val needsSave: Boolean = false,
    val error: String? = null,
    val retired: Boolean = false,
) {
    val chapter: MahjongLessonChapter get() = CompetitionMahjongLearningLibrary.chapters[chapterIndex]
    val card: MahjongLessonCard get() = chapter.cards[cardIndex]
    val canOperate: Boolean get() = loaded && !busy && !needsSave && !retired
    val tutorialCompleted: Boolean get() = canOperate && error == null &&
        completedChapterCount == CompetitionMahjongLearningLibrary.CHAPTER_COUNT
    val canAdvance: Boolean get() = canOperate && solved &&
        !(chapterIndex == CompetitionMahjongLearningLibrary.chapters.lastIndex &&
            cardIndex == chapter.cards.lastIndex && tutorialCompleted)
}

/** Accepted work drains independently of the page scope; only saved, current-owner facts advance UI. */
internal class CompetitionMahjongLearningController(
    private val store: CompetitionMahjongLearningProofStore,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val now: () -> Long = System::currentTimeMillis,
    private val newRequestId: () -> String = { UUID.randomUUID().toString() },
    private val ownerIsCurrent: () -> Boolean = { true },
) : LocalDataWriterRetirement {
    private val admission = Any()
    private var accepted = false
    private var retired = false
    private val retirement = CompletableDeferred<Unit>()
    override val retirementCompletion: Deferred<Unit> get() = retirement
    private var proof = CompetitionMahjongLearningProof()
    private var pending: CompetitionMahjongLearningProofRequest? = null
    private val mutableState = MutableStateFlow(CompetitionMahjongLearningState())
    val state: StateFlow<CompetitionMahjongLearningState> = mutableState.asStateFlow()
    @Volatile internal var lastFailure: Throwable? = null
        private set

    suspend fun load() = operate {
        if (state.value.loaded || pending != null) return@operate
        try {
            val read = store.load()
            if (!currentOwner()) return@operate
            proof = when (read) {
                is CompetitionMahjongLearningProofRead.Ready -> read.proof
                CompetitionMahjongLearningProofRead.Incompatible -> throw IOException("教材确认记录不兼容")
            }
            val count = proof.completedChapterCount
            publish(CompetitionMahjongLearningState(loaded = true, busy = true,
                chapterIndex = count.coerceAtMost(CompetitionMahjongLearningLibrary.chapters.lastIndex),
                completedChapterCount = count))
            lastFailure = null
        } catch (error: Exception) {
            lastFailure = error
            publish(state.value.copy(error = "教程进度读取失败，请重试；原进度未覆盖。"))
        }
    }

    suspend fun selectChapter(index: Int) = operate {
        val current = state.value
        if (!current.loaded || pending != null || index !in CompetitionMahjongLearningLibrary.chapters.indices ||
            index > current.completedChapterCount) return@operate
        publish(CompetitionMahjongLearningState(loaded = true, busy = true,
            chapterIndex = index, completedChapterCount = current.completedChapterCount))
    }

    suspend fun acknowledgeReading() = operate {
        val current = state.value
        if (!current.loaded || pending != null || current.solved || current.card.task != MahjongLessonTask.Reading) return@operate
        publish(current.copy(solved = true, feedback = current.card.answerExplanation))
    }

    suspend fun selectOption(index: Int) = operate {
        val current = state.value
        val task = current.card.task as? MahjongLessonTask.Choice ?: return@operate
        if (!current.loaded || pending != null || current.solved || index !in task.options.indices) return@operate
        publish(current.copy(selectedOption = index, feedback = null))
    }

    suspend fun toggleTile(index: Int) = operate {
        val current = state.value
        val task = current.card.task as? MahjongLessonTask.Tiles ?: return@operate
        if (!current.loaded || pending != null || current.solved || index !in task.tiles.indices) return@operate
        publish(current.copy(selectedTiles = if (index in current.selectedTiles)
            current.selectedTiles - index else current.selectedTiles + index, feedback = null))
    }

    suspend fun submit() = operate {
        val current = state.value
        if (!current.loaded || pending != null || current.solved) return@operate
        val correct = when (val task = current.card.task) {
            MahjongLessonTask.Reading -> return@operate
            is MahjongLessonTask.Choice -> {
                if (current.selectedOption == null) return@operate
                current.selectedOption == task.correctIndex
            }
            is MahjongLessonTask.Tiles -> {
                if (current.selectedTiles.isEmpty()) return@operate
                current.selectedTiles in task.acceptedSelections
            }
        }
        publish(current.copy(solved = correct, feedback = if (correct)
            "答对了：${current.card.answerExplanation}" else "还没有答对。请重新检查题目与本步讲解。"))
    }

    suspend fun advance() = operate {
        val current = state.value
        if (!current.loaded || pending != null || !current.solved) return@operate
        if (current.cardIndex < current.chapter.cards.lastIndex) {
            publish(current.copy(cardIndex = current.cardIndex + 1,
                selectedTiles = emptySet(), selectedOption = null, solved = false, feedback = null))
        } else if (current.chapterIndex < current.completedChapterCount) {
            moveAfterChapter(current, current.completedChapterCount)
        } else {
            try {
                // The final explicit advance creates this once; retries retain all original values.
                pending = CompetitionMahjongLearningProofRequest(newRequestId(), current.chapter.id,
                    current.chapter.cards.map { it.id }, now())
                publish(current.copy(needsSave = true))
                savePending()
            } catch (error: Exception) {
                lastFailure = error
                publish(current.copy(needsSave = pending != null,
                    error = "本章确认未保存，请重试；原进度未覆盖。"))
            }
        }
    }

    suspend fun retrySave() = operate { if (pending != null) savePending() }

    private suspend fun savePending() {
        val request = checkNotNull(pending)
        val current = state.value
        try {
            if (!currentOwner()) return
            val expected = proof.confirm(request)
            val saved = store.confirm(request)
            if (!currentOwner()) return
            check(saved.receipts.take(expected.completedChapterCount) == expected.receipts) {
                "Learning store returned different chapter confirmations"
            }
            proof = saved
            pending = null
            lastFailure = null
            moveAfterChapter(current, saved.completedChapterCount)
        } catch (error: Exception) {
            lastFailure = error
            publish(current.copy(needsSave = true,
                error = "本章已答完，进度保存失败。请重试保存后继续。"))
        }
    }

    private fun moveAfterChapter(current: CompetitionMahjongLearningState, count: Int) {
        publish(if (current.chapterIndex < CompetitionMahjongLearningLibrary.chapters.lastIndex) {
            CompetitionMahjongLearningState(loaded = true, busy = true,
                chapterIndex = current.chapterIndex + 1, completedChapterCount = count)
        } else current.copy(completedChapterCount = count, needsSave = false, error = null,
            feedback = "四章练习与知识测试已完成，进度已保存。"))
    }

    private fun currentOwner(): Boolean = synchronized(admission) { currentOwnerLocked() }

    private fun currentOwnerLocked(): Boolean {
        if (!ownerIsCurrent()) retireLocked()
        return !retired
    }

    private fun publish(next: CompetitionMahjongLearningState) = synchronized(admission) {
        if (currentOwnerLocked()) mutableState.value = next
    }

    override fun beginRetirement(): Deferred<Unit> = synchronized(admission) {
        retireLocked()
        retirementCompletion
    }

    private fun retireLocked() {
        retired = true
        mutableState.value = state.value.copy(retired = true,
            error = "页面已关闭或本地数据已更换，请返回后重新进入教程。")
        if (!accepted) retirement.complete(Unit)
    }

    private suspend fun operate(operation: suspend () -> Unit) {
        synchronized(admission) {
            if (!currentOwnerLocked() || accepted) return
            accepted = true
            mutableState.value = state.value.copy(busy = true)
        }
        try {
            withContext(dispatcher + NonCancellable) { if (currentOwner()) operation() }
        } finally {
            synchronized(admission) {
                accepted = false
                currentOwnerLocked()
                mutableState.value = state.value.copy(busy = false)
                if (retired) retirement.complete(Unit)
            }
        }
    }
}
