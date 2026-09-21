package com.masterofchessstrategy.settings

import com.masterofchessstrategy.data.AppSettings
import com.masterofchessstrategy.data.AppSettingsRepository
import com.masterofchessstrategy.data.LoadAppSettingsResult
import com.masterofchessstrategy.engine.Difficulty
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AppSettingsViewModelTest {
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
    fun missingSettingsLoadDocumentedDefaults() = runTest(dispatcher) {
        val viewModel = AppSettingsViewModel(
            FakeSettingsRepository(LoadAppSettingsResult.NotFound),
        )

        advanceUntilIdle()

        assertFalse(viewModel.uiState.isLoading)
        assertEquals(Difficulty.EASY, viewModel.uiState.settings.defaultDifficulty)
        assertFalse(viewModel.uiState.settings.autoContinueEnabled)
        assertEquals(10, viewModel.uiState.settings.autoContinueGameLimit)
        assertTrue(viewModel.uiState.settings.soundEnabled)
        assertNull(viewModel.uiState.settings.gameDurationMinutes)
    }

    @Test
    fun eachEditCommitsWholeValidatedSettingsRow() = runTest(dispatcher) {
        val repository = FakeSettingsRepository(LoadAppSettingsResult.NotFound)
        val viewModel = AppSettingsViewModel(repository, nowEpochMillis = { 123L })
        advanceUntilIdle()

        viewModel.setAutoContinue(true)
        assertTrue(viewModel.uiState.isSaving)
        advanceUntilIdle()

        assertTrue(requireNotNull(repository.saved).autoContinueEnabled)
        assertEquals(123L, repository.saved?.updatedAtEpochMillis)
        assertEquals(SettingsFeedback.SAVED, viewModel.uiState.feedback)
    }

    @Test
    fun timeLimitUsesSafeDefaultAndClampedSteps() = runTest(dispatcher) {
        val repository = FakeSettingsRepository(LoadAppSettingsResult.NotFound)
        val viewModel = AppSettingsViewModel(repository)
        advanceUntilIdle()

        viewModel.setTimeLimitEnabled(true)
        advanceUntilIdle()
        assertEquals(30, viewModel.uiState.settings.gameDurationMinutes)

        repeat(40) {
            viewModel.adjustDuration(AppSettingsViewModel.DURATION_STEP_MINUTES)
            advanceUntilIdle()
        }
        assertEquals(180, viewModel.uiState.settings.gameDurationMinutes)
    }

    @Test
    fun autoContinueLimitIsClampedAndSaved() = runTest(dispatcher) {
        val repository = FakeSettingsRepository(LoadAppSettingsResult.NotFound)
        val viewModel = AppSettingsViewModel(repository)
        advanceUntilIdle()

        repeat(120) {
            viewModel.adjustAutoContinueLimit(1)
            advanceUntilIdle()
        }

        assertEquals(100, viewModel.uiState.settings.autoContinueGameLimit)
        assertEquals(100, repository.saved?.autoContinueGameLimit)
    }

    @Test
    fun appearanceSelectionIsValidatedAndSaved() = runTest(dispatcher) {
        val repository = FakeSettingsRepository(LoadAppSettingsResult.NotFound)
        val viewModel = AppSettingsViewModel(repository)
        advanceUntilIdle()

        viewModel.setSelectedAppearance(3)
        advanceUntilIdle()
        viewModel.setSelectedAppearance(8)
        advanceUntilIdle()

        assertEquals(3, viewModel.uiState.settings.selectedAppearanceCode)
        assertEquals(3, repository.saved?.selectedAppearanceCode)
    }

    @Test
    fun failedSaveRestoresPreviousSettings() = runTest(dispatcher) {
        val repository = FakeSettingsRepository(
            loadResult = LoadAppSettingsResult.NotFound,
            failSave = true,
        )
        val viewModel = AppSettingsViewModel(repository)
        advanceUntilIdle()

        viewModel.setSoundEnabled(false)
        advanceUntilIdle()

        assertTrue(viewModel.uiState.settings.soundEnabled)
        assertEquals(SettingsFeedback.SAVE_FAILED, viewModel.uiState.feedback)
    }

    @Test
    fun incompatibleRowIsClearedAndReplacedBySafeDefaults() = runTest(dispatcher) {
        val repository = FakeSettingsRepository(LoadAppSettingsResult.Incompatible)
        val viewModel = AppSettingsViewModel(repository)

        advanceUntilIdle()

        assertEquals(1, repository.clearCalls)
        assertEquals(AppSettings.DEFAULT, viewModel.uiState.settings)
        assertEquals(SettingsFeedback.LOAD_RECOVERED, viewModel.uiState.feedback)
    }

    private class FakeSettingsRepository(
        private val loadResult: LoadAppSettingsResult,
        private val failSave: Boolean = false,
    ) : AppSettingsRepository {
        var saved: AppSettings? = null
        var clearCalls = 0

        override suspend fun load(): LoadAppSettingsResult = loadResult

        override suspend fun save(settings: AppSettings) {
            if (failSave) error("storage unavailable")
            saved = settings
        }

        override suspend fun clear() {
            clearCalls++
        }
    }
}
