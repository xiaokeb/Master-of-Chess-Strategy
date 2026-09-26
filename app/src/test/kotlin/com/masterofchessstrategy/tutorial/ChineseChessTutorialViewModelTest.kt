package com.masterofchessstrategy.tutorial

import com.masterofchessstrategy.data.LoadTutorialProgressResult
import com.masterofchessstrategy.data.TutorialProgress
import com.masterofchessstrategy.data.TutorialProgressRepository
import com.masterofchessstrategy.engine.BoardPosition
import com.masterofchessstrategy.engine.GameType
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

@OptIn(ExperimentalCoroutinesApi::class)
class ChineseChessTutorialViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun savedCheckpointResumesAtPractice() = runTest(dispatcher) {
        val progress = progressAt(2)
        val viewModel = ChineseChessTutorialViewModel(
            FakeTutorialRepository(LoadTutorialProgressResult.Loaded(progress)),
        )

        advanceUntilIdle()

        assertEquals(TutorialStage.PRACTICE, viewModel.uiState.stage)
        assertFalse(viewModel.uiState.isLoading)
    }

    @Test
    fun practiceAcceptsOnlyTaughtSoldierMoveBeforeCheckpoint() = runTest(dispatcher) {
        val repository = FakeTutorialRepository(
            LoadTutorialProgressResult.Loaded(progressAt(2)),
        )
        val viewModel = ChineseChessTutorialViewModel(repository, nowEpochMillis = { 50L })
        advanceUntilIdle()

        viewModel.onPracticeSquareTap(BoardPosition(1, 6))
        assertEquals(TutorialFeedback.SELECT_SOLDIER, viewModel.uiState.feedback)

        viewModel.onPracticeSquareTap(ChineseChessTutorialViewModel.PRACTICE_SOLDIER_START)
        viewModel.onPracticeSquareTap(ChineseChessTutorialViewModel.PRACTICE_SOLDIER_TARGET)
        assertTrue(viewModel.uiState.isPracticeSolved)

        viewModel.completePractice()
        advanceUntilIdle()

        assertEquals(TutorialStage.QUIZ, viewModel.uiState.stage)
        assertEquals(3, repository.saved?.completedStepCount)
        assertEquals(50L, repository.saved?.updatedAtEpochMillis)
    }

    @Test
    fun onlyCorrectStalemateAnswerCompletesTutorial() = runTest(dispatcher) {
        val repository = FakeTutorialRepository(
            LoadTutorialProgressResult.Loaded(progressAt(3)),
        )
        val viewModel = ChineseChessTutorialViewModel(repository)
        advanceUntilIdle()

        viewModel.answerQuiz(StalemateAnswer.DRAW)
        viewModel.completeQuiz()
        assertFalse(viewModel.uiState.progress.isCompleted)

        viewModel.answerQuiz(StalemateAnswer.SIDE_TO_MOVE_LOSES)
        viewModel.completeQuiz()
        advanceUntilIdle()

        assertTrue(viewModel.uiState.progress.isCompleted)
        assertEquals(TutorialStage.COMPLETE, viewModel.uiState.stage)
    }

    @Test
    fun failedCheckpointSaveReturnsToPreviousStep() = runTest(dispatcher) {
        val repository = FakeTutorialRepository(
            loadResult = LoadTutorialProgressResult.NotFound,
            failSave = true,
        )
        val viewModel = ChineseChessTutorialViewModel(repository)
        advanceUntilIdle()

        viewModel.advanceReadingStep()
        advanceUntilIdle()

        assertEquals(TutorialStage.RULES, viewModel.uiState.stage)
        assertEquals(TutorialFeedback.SAVE_FAILED, viewModel.uiState.feedback)
    }

    @Test
    fun failedFinalSaveRollsBackCompletionAndCanBeRetried() = runTest(dispatcher) {
        val repository = FakeTutorialRepository(
            LoadTutorialProgressResult.Loaded(progressAt(3)), failSave = true,
        )
        val viewModel = ChineseChessTutorialViewModel(repository)
        advanceUntilIdle()
        viewModel.answerQuiz(StalemateAnswer.SIDE_TO_MOVE_LOSES)
        viewModel.completeQuiz()
        assertEquals(TutorialStage.COMPLETE, viewModel.uiState.stage)
        assertFalse(viewModel.uiState.isInteractionEnabled)
        advanceUntilIdle()
        assertEquals(TutorialStage.QUIZ, viewModel.uiState.stage)
        assertFalse(viewModel.uiState.progress.isCompleted)
        assertEquals(TutorialFeedback.SAVE_FAILED, viewModel.uiState.feedback)

        repository.failSave = false
        viewModel.completeQuiz()
        advanceUntilIdle()
        assertTrue(viewModel.uiState.progress.isCompleted)
        assertTrue(viewModel.uiState.isInteractionEnabled)
        assertEquals(true, repository.saved?.isCompleted)
    }

    private fun progressAt(completedStepCount: Int) = TutorialProgress(
        gameType = GameType.CHINESE_CHESS,
        contentVersion = TutorialProgress.CURRENT_CONTENT_VERSION,
        completedStepCount = completedStepCount,
        isCompleted = completedStepCount == TutorialProgress.TOTAL_STEP_COUNT,
        updatedAtEpochMillis = 1L,
    )

    private class FakeTutorialRepository(
        private val loadResult: LoadTutorialProgressResult,
        var failSave: Boolean = false,
    ) : TutorialProgressRepository {
        var saved: TutorialProgress? = null

        override suspend fun load(gameType: GameType): LoadTutorialProgressResult = loadResult

        override suspend fun save(progress: TutorialProgress) {
            if (failSave) error("storage unavailable")
            saved = progress
        }

        override suspend fun clear(gameType: GameType) = Unit
    }
}
