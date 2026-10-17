package com.masterofchessstrategy.games.go

import com.masterofchessstrategy.engine.ActionResult
import com.masterofchessstrategy.engine.BoardPosition
import com.masterofchessstrategy.engine.GameResult
import com.masterofchessstrategy.engine.GameType
import com.masterofchessstrategy.engine.GoAction
import com.masterofchessstrategy.engine.GoAreaScore
import com.masterofchessstrategy.engine.GoPhase
import com.masterofchessstrategy.engine.GoRuleEngine
import com.masterofchessstrategy.engine.GoScoringState
import com.masterofchessstrategy.engine.GoStone
import com.masterofchessstrategy.engine.PlayerId
import com.masterofchessstrategy.engine.RestoreResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class GoLearningViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    @Test fun failedFinalProgressSaveCannotUnlockTutorialUntilRetry() = runTest(dispatcher) {
        val first = GoLearningLibrary.lessons.first()
        val repository = ProgressDouble().apply {
            saved = GoLearningProgress(GoLearningLibrary.lessons.drop(1).map { it.id }.toSet(), first.id)
        }
        val model = GoLearningViewModel(repository) { size, _, _ -> BoardDouble(size) }
        advanceUntilIdle()
        repository.failSave = true
        (first.goal as GoLessonGoal.FindLiberties).points.forEach {
            model.answer(GoLessonAnswer.Point(it))
            advanceUntilIdle()
        }
        assertTrue(model.state.value.needsSave)
        assertFalse(model.state.value.canOperate)
        assertFalse(model.state.value.tutorialCompleted)
        assertFalse(repository.saved.isCompleted)
        repository.failSave = false
        model.retry()
        advanceUntilIdle()
        assertTrue(model.state.value.tutorialCompleted)
        assertTrue(repository.saved.isCompleted)
    }

    @Test fun wrongPointDoesNotRecordACompletedLesson() = runTest(dispatcher) {
        val repository = ProgressDouble()
        val model = GoLearningViewModel(repository) { size, _, _ -> BoardDouble(size) }
        advanceUntilIdle()
        val before = model.state.value.exercise!!.stones
        model.answer(GoLessonAnswer.Point(BoardPosition(0, 0)))
        advanceUntilIdle()
        assertEquals(before, model.state.value.exercise!!.stones)
        assertFalse(model.state.value.exercise!!.completed)
        assertTrue(repository.saved.completedLessonIds.isEmpty())
        assertEquals(0, repository.saveCalls)
    }

    @Test fun selectingAKnowledgePointSavesReturnPositionAndClosesPreviousSession() = runTest(dispatcher) {
        val repository = ProgressDouble()
        val engines = mutableListOf<BoardDouble>()
        val model = GoLearningViewModel(repository) { size, _, _ -> BoardDouble(size).also(engines::add) }
        advanceUntilIdle()
        model.selectLesson("connection")
        advanceUntilIdle()
        assertEquals("connection", repository.saved.lastLessonId)
        assertEquals("connection", model.state.value.exercise!!.lesson.id)
        assertTrue(engines.first().closed)
        assertFalse(engines.last().closed)
        assertTrue(repository.saved.completedLessonIds.isEmpty())
    }

    @Test fun unreadableLessonCannotExposeACompletedTutorialCondition() = runTest(dispatcher) {
        val repository = ProgressDouble().apply {
            saved = GoLearningProgress(GoLearningLibrary.lessons.map { it.id }.toSet())
        }
        val model = GoLearningViewModel(repository) { _, _, _ -> throw IOException("题面无法打开") }
        advanceUntilIdle()
        assertFalse(model.state.value.tutorialCompleted)
        assertFalse(model.state.value.canOperate)
        assertEquals("题面无法打开", model.state.value.error)
    }

    @Test fun committedMergeIsPublishedImmediatelyWithoutLosingThisPagesLesson() = runTest(dispatcher) {
        val first = GoLearningLibrary.lessons.first()
        val repository = ProgressDouble().apply {
            mergeOnSave = GoLearningLibrary.lessons.drop(1).map { it.id }.toSet()
        }
        val model = GoLearningViewModel(repository) { size, _, _ -> BoardDouble(size) }
        advanceUntilIdle()
        (first.goal as GoLessonGoal.FindLiberties).points.forEach {
            model.answer(GoLessonAnswer.Point(it)); advanceUntilIdle()
        }
        assertTrue(model.state.value.tutorialCompleted)
        assertEquals(10, model.state.value.progress.completedLessonIds.size)
        assertEquals(first.id, model.state.value.progress.lastLessonId)
        assertEquals(first.id, model.state.value.exercise!!.lesson.id)
    }

    @Test fun unverifiedReadBackCannotUnlockEvenIfTheWriteSucceeded() = runTest(dispatcher) {
        val first = GoLearningLibrary.lessons.first()
        val repository = ProgressDouble().apply {
            saved = GoLearningProgress(GoLearningLibrary.lessons.drop(1).map { it.id }.toSet())
            failReadAfterWrite = true
        }
        val model = GoLearningViewModel(repository) { size, _, _ -> BoardDouble(size) }
        advanceUntilIdle()
        (first.goal as GoLessonGoal.FindLiberties).points.forEach {
            model.answer(GoLessonAnswer.Point(it)); advanceUntilIdle()
        }
        assertTrue(repository.saved.isCompleted)
        assertFalse(model.state.value.tutorialCompleted)
        assertTrue(model.state.value.needsSave)
        repository.failReadAfterWrite = false
        model.retry(); advanceUntilIdle()
        assertTrue(model.state.value.tutorialCompleted)
    }

    @Test fun successfulReadBackMissingTheSavedLessonRequiresRetryBeforeUnlocking() = runTest(dispatcher) {
        val first = GoLearningLibrary.lessons.first()
        val repository = ProgressDouble().apply {
            saved = GoLearningProgress(GoLearningLibrary.lessons.drop(1).map { it.id }.toSet())
            omitReadBackIds = setOf(first.id)
        }
        val model = GoLearningViewModel(repository) { size, _, _ -> BoardDouble(size) }
        advanceUntilIdle()
        (first.goal as GoLessonGoal.FindLiberties).points.forEach {
            model.answer(GoLessonAnswer.Point(it)); advanceUntilIdle()
        }
        assertTrue(repository.saved.isCompleted)
        assertFalse(model.state.value.tutorialCompleted)
        assertFalse(model.state.value.canOperate)
        assertTrue(model.state.value.needsSave)
        assertEquals("教程进度写入后核验失败", model.state.value.error)
        repository.omitReadBackIds = emptySet()
        model.retry(); advanceUntilIdle()
        assertEquals(2, repository.saveCalls)
        assertTrue(model.state.value.tutorialCompleted)
        assertTrue(model.state.value.canOperate)
        assertFalse(model.state.value.needsSave)
    }

    private class ProgressDouble : GoLearningProgressRepository {
        var saved = GoLearningProgress()
        var failSave = false
        var saveCalls = 0
        var mergeOnSave: Set<String> = emptySet()
        var failReadAfterWrite = false
        var omitReadBackIds: Set<String> = emptySet()
        override suspend fun load(): GoLearningProgress {
            if (failReadAfterWrite && saveCalls > 0) throw IOException("模拟写后重读失败")
            return if (saveCalls > 0) saved.copy(completedLessonIds = saved.completedLessonIds - omitReadBackIds)
                else saved
        }
        override suspend fun save(progress: GoLearningProgress) {
            if (failSave) throw IOException("模拟写入失败")
            saveCalls++
            saved = progress.copy(completedLessonIds = progress.completedLessonIds + mergeOnSave)
        }
    }

    /** Only a board for progress tests; native instrumentation verifies all lesson rules. */
    private class BoardDouble(override val boardSize: Int) : GoRuleEngine {
        override val gameType = GameType.GO
        override var currentPlayer = PlayerId(0)
        override val blackKomiQuarters = 15
        override val handicapStones = 0
        override val phase = GoPhase.PLAYING
        var closed = false
        private val stones = MutableList(boardSize * boardSize) { GoStone.EMPTY }
        override fun apply(action: GoAction): ActionResult {
            if (action is GoAction.Place) stones[action.point.y * boardSize + action.point.x] =
                if (currentPlayer.value == 0) GoStone.BLACK else GoStone.WHITE
            currentPlayer = PlayerId(1 - currentPlayer.value)
            return ActionResult.Accepted
        }
        override fun stoneAt(point: BoardPosition) = stones[point.y * boardSize + point.x]
        override fun scoringState() = GoScoringState(phase, 0, false, false, emptyList())
        override fun serialize() = stones.map { it.code.toByte() }.toByteArray()
        override fun restore(data: ByteArray) = RestoreResult.Restored
        override fun legalActions(): List<GoAction> = emptyList()
        override fun gameResult() = GameResult.ONGOING
        override fun previewScore(): GoAreaScore? = null
        override fun finalScore(): GoAreaScore? = null
        override fun reset() = Unit
        override fun undo() = false
        override fun close() { closed = true }
    }
}
