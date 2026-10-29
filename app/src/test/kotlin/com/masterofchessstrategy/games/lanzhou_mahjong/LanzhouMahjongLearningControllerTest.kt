package com.masterofchessstrategy.games.lanzhou_mahjong

import com.masterofchessstrategy.games.lanzhou_mahjong.data.LanzhouMahjongLearningAdvance
import com.masterofchessstrategy.games.lanzhou_mahjong.data.LanzhouMahjongLearningConflictException
import com.masterofchessstrategy.games.lanzhou_mahjong.data.LanzhouMahjongLearningRepository
import com.masterofchessstrategy.games.lanzhou_mahjong.data.LanzhouMahjongLearningSnapshot
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class LanzhouMahjongLearningControllerTest {
    @Test
    fun courseHasStableIdsAndLegalExampleInventory() {
        val lessons = LanzhouMahjongLearningLibrary.lessons
        assertEquals(10, lessons.size)
        assertEquals(2, LanzhouMahjongLearningLibrary.CONTENT_VERSION)
        assertTrue(lessons.last().explanation.contains("各赔24倍"))
        assertTrue(lessons.last().explanation.contains("合计72倍"))
        assertTrue(lessons.last().explanation.contains("旧v1存档仍按每家8倍"))
        assertEquals(lessons.size, lessons.map { it.id }.toSet().size)
        for (lesson in lessons) {
            assertTrue(lesson.title.isNotBlank() && lesson.explanation.isNotBlank())
            assertTrue(lesson.answerIndex in lesson.options.indices)
            assertEquals(lesson.options.size, lesson.options.toSet().size)
            assertTrue(lesson.exampleHand.size <= 14 && lesson.exampleHand.all { it in 0..26 })
            assertTrue(lesson.exampleHand.groupingBy { it }.eachCount().values.all { it <= 4 })
        }
    }

    @Test
    fun wrongAnswerAndLockedLessonDoNotUnlockProgress() = runTest {
        val store = MemoryStore()
        val controller = LanzhouMahjongLearningController(store, StandardTestDispatcher(testScheduler))
        controller.load()
        controller.selectLesson(5)
        assertEquals(0, controller.state.value.lessonIndex)
        controller.submit(0)
        assertEquals(0, controller.state.value.completedCount)
        assertEquals(0, store.saves)
    }

    @Test
    fun failedSaveKeepsLessonLockedAndRetryCompletesOnce() = runTest {
        val store = MemoryStore().apply { failSave = true }
        val controller = LanzhouMahjongLearningController(store, StandardTestDispatcher(testScheduler))
        controller.load()
        controller.submit(controller.state.value.lesson.answerIndex)
        assertEquals(0, controller.state.value.completedCount)
        assertEquals("教程进度保存失败，请重试。当前进度尚未确认", controller.state.value.feedback)
        store.failSave = false
        controller.retrySave()
        assertEquals(1, controller.state.value.completedCount)
        controller.submit(controller.state.value.lesson.answerIndex)
        assertEquals(1, store.saves)
        controller.selectLesson(1)
        assertEquals(1, controller.state.value.lessonIndex)
    }

    @Test
    fun completeCourseCanBeRestoredWithoutRepeatingAwards() = runTest {
        val store = MemoryStore()
        val controller = LanzhouMahjongLearningController(store, StandardTestDispatcher(testScheduler))
        controller.load()
        for (index in LanzhouMahjongLearningLibrary.lessons.indices) {
            controller.selectLesson(index)
            controller.submit(controller.state.value.lesson.answerIndex)
        }
        assertTrue(controller.state.value.isCompleted)
        assertEquals(10, store.saves)
        val restored = LanzhouMahjongLearningController(store, StandardTestDispatcher(testScheduler))
        restored.load()
        assertTrue(restored.state.value.isCompleted)
        restored.submit(restored.state.value.lesson.answerIndex)
        assertEquals(10, store.saves)
    }

    @Test
    fun retryReusesExactRequestAndTimestampAndFreezesQuestion() = runTest {
        val store = MemoryStore().apply { failSave = true }
        var clockCalls = 0
        val controller = LanzhouMahjongLearningController(store, StandardTestDispatcher(testScheduler)) { ++clockCalls; 123L }
        controller.load()
        controller.submit(1)
        val original = store.requests.single()
        controller.selectLesson(1)
        controller.submit(0)
        controller.load()
        assertEquals(0, controller.state.value.lessonIndex)
        assertTrue(controller.state.value.hasPendingSave)
        store.failSave = false
        controller.retrySave()
        assertSame(original, store.requests.last())
        assertEquals(1, clockCalls)
        assertEquals(123L, store.progress!!.updatedAtEpochMillis)
    }

    @Test
    fun lostAcknowledgementCanRetryWithoutWritingTwice() = runTest {
        val store = MemoryStore().apply { loseAcknowledgement = true }
        val controller = LanzhouMahjongLearningController(store, StandardTestDispatcher(testScheduler)) { 42L }
        controller.load()
        controller.submit(1)
        assertEquals(0, controller.state.value.completedCount)
        assertEquals(1, store.saves)
        store.loseAcknowledgement = false
        controller.retrySave()
        assertEquals(1, controller.state.value.completedCount)
        assertEquals(1, store.saves)
    }

    @Test
    fun failedReadPreservesOriginalFailureAndNeverWrites() = runTest {
        val failure = java.io.IOException("private-path-token")
        val store = MemoryStore().apply { loadFailure = failure }
        val controller = LanzhouMahjongLearningController(store, StandardTestDispatcher(testScheduler))
        controller.load()
        controller.submit(1)
        assertFalse(controller.state.value.loaded)
        assertSame(failure, controller.lastFailure)
        assertFalse(controller.state.value.feedback!!.contains("private-path-token"))
        assertEquals(0, store.saves)
        store.loadFailure = null
        store.progress = LanzhouMahjongLearningSnapshot(2, 3, 21L)
        controller.load()
        assertEquals(3, controller.state.value.completedCount)
    }

    @Test
    fun conflictRequiresExplicitReadAndDoesNotOverwriteNewerProgress() = runTest {
        val store = MemoryStore()
        val controller = LanzhouMahjongLearningController(store, StandardTestDispatcher(testScheduler)) { 100L }
        controller.load()
        store.progress = LanzhouMahjongLearningSnapshot(2, 3, 99L)
        controller.submit(1)
        assertTrue(controller.state.value.requiresReload)
        assertEquals(0, controller.state.value.completedCount)
        assertTrue(controller.lastFailure is LanzhouMahjongLearningConflictException)
        controller.retrySave()
        assertEquals(1, store.requests.size)
        controller.load()
        assertEquals(3, controller.state.value.completedCount)
        assertFalse(controller.state.value.hasPendingSave)
        assertFalse(controller.state.value.requiresReload)
        assertEquals(0, store.saves)
    }

    @Test
    fun retirementWaitsAcceptedSaveAndRefusesLateUiAndNewWork() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val store = MemoryStore().apply { beforeSave = { entered.complete(Unit); release.await() } }
        val controller = LanzhouMahjongLearningController(store, StandardTestDispatcher(testScheduler))
        controller.load()
        val accepted = launch { controller.submit(1) }
        entered.await()
        val retirement = controller.beginRetirement()
        assertSame(retirement, controller.beginRetirement())
        assertFalse(retirement.isCompleted)
        accepted.cancel()
        controller.retrySave()
        release.complete(Unit)
        accepted.join()
        retirement.await()
        assertEquals(1, store.saves)
        assertEquals(0, controller.state.value.completedCount)
        assertTrue(controller.state.value.retired)
        assertFalse(controller.state.value.isBusy)
        controller.submit(1)
        assertEquals(1, store.saves)
    }

    @Test
    fun changedEpochBeforeSubmissionCannotWrite() = runTest {
        val store = MemoryStore()
        val controller = LanzhouMahjongLearningController(store, StandardTestDispatcher(testScheduler))
        controller.load()
        store.epoch.value++
        controller.submit(1)
        assertTrue(controller.state.value.retired)
        assertEquals(0, store.saves)
        assertTrue(controller.retirementCompletion.isCompleted)
    }

    private class MemoryStore : LanzhouMahjongLearningRepository {
        override val ownerEpoch = 0L
        override val epoch = MutableStateFlow(0L)
        var progress: LanzhouMahjongLearningSnapshot? = null
        var failSave = false
        var loseAcknowledgement = false
        var loadFailure: Exception? = null
        var beforeSave: suspend () -> Unit = {}
        val requests = mutableListOf<LanzhouMahjongLearningAdvance>()
        var saves = 0
        override suspend fun load(): LanzhouMahjongLearningSnapshot? {
            loadFailure?.let { throw it }
            return progress
        }
        override suspend fun advance(request: LanzhouMahjongLearningAdvance): LanzhouMahjongLearningSnapshot {
            requests += request
            beforeSave()
            if (failSave) error("write failed")
            if (progress != request.after) {
                if (progress != request.before) throw LanzhouMahjongLearningConflictException()
                progress = request.after
                saves++
            }
            if (loseAcknowledgement) error("ack lost")
            return request.after
        }
    }
}
