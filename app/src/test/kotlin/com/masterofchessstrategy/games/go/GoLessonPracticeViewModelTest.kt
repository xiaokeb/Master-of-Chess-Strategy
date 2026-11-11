package com.masterofchessstrategy.games.go

import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.SavedStateHandle
import com.masterofchessstrategy.engine.GoRecordEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.time.LocalDate

@OptIn(ExperimentalCoroutinesApi::class)
class GoLessonPracticeViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val owners = mutableListOf<ViewModelStore>()
    private var engineCalls = 0
    private val factory: (Int, Int, Int) -> GoRecordEngine = { size, _, _ ->
        engineCalls++; GoEndgameLibraryTest.GridRules(size)
    }
    @Before fun prepare() { Dispatchers.setMain(dispatcher) }
    @After fun cleanup() { owners.forEach { it.clear() }; Dispatchers.resetMain() }

    @Test fun anUnlockedRequestSelectsTheRealExerciseWithoutGrantingCompletion() = runTest(dispatcher) {
        val repository = Repository()
        val model = model(repository, "corner-capture")
        advanceUntilIdle()
        assertEquals("corner-capture", model.state.value.exercise!!.puzzle.id)
        assertEquals(GoEndgameChallengeMode.THEME, model.state.value.mode)
        assertNull(model.state.value.lockedRequestedPuzzleId)
        assertEquals(1, repository.selections)
        assertEquals(0, repository.completions)
        assertTrue(repository.progress.completedIds.isEmpty())
    }

    @Test fun aLockedRequestDoesNotSelectWriteOrCreateAnEngine() = runTest(dispatcher) {
        val repository = Repository()
        val before = repository.progress
        val model = model(repository, "escape-atari")
        advanceUntilIdle()
        assertSame(before, repository.progress)
        assertEquals("escape-atari", model.state.value.lockedRequestedPuzzleId)
        assertNull(model.state.value.exercise)
        assertFalse(model.state.value.canOperate)
        assertEquals(0, repository.selections)
        assertEquals(0, repository.completions)
        assertEquals(0, engineCalls)
        model.select("escape-atari"); model.answer(GoEndgameLibrary.puzzles[2].answer)
        advanceUntilIdle()
        assertSame(before, repository.progress)
    }

    @Test fun explicitMainlineChoiceOpensTheFirstPrerequisiteNotTheLockedTarget() = runTest(dispatcher) {
        val repository = Repository()
        val model = model(repository, "bridge-groups")
        advanceUntilIdle()
        model.openRequiredMainline()
        advanceUntilIdle()
        assertNull(model.state.value.lockedRequestedPuzzleId)
        assertEquals("corner-capture", model.state.value.exercise!!.puzzle.id)
        assertEquals(GoEndgameChallengeMode.MAINLINE, model.state.value.mode)
        assertTrue(repository.progress.completedIds.isEmpty())
        assertEquals(0, repository.completions)
    }

    @Test fun aPersistedPrefixAllowsItsCorrespondingPuzzleAndPreservesDailyMetadata() = runTest(dispatcher) {
        val prefix = GoEndgameLibrary.puzzles.take(2).map { it.id }.toSet()
        val date = LocalDate.of(2026, 10, 16)
        val initial = GoEndgameProgress(prefix).let { it.selectDaily(date, GoEndgameChallenges.daily(it, date).id) }
        val repository = Repository(initial)
        val model = model(repository, "escape-atari")
        advanceUntilIdle()
        assertEquals("escape-atari", model.state.value.exercise!!.puzzle.id)
        assertEquals(prefix, repository.progress.completedIds)
        assertEquals(initial.dailyDate, repository.progress.dailyDate)
        assertEquals(initial.dailyPuzzleId, repository.progress.dailyPuzzleId)
        assertEquals(GoEndgameChallengeMode.THEME, repository.progress.lastMode)
    }

    @Test fun failedInitialSelectionKeepsOldStorageAndRetryDoesNotInventACompletion() = runTest(dispatcher) {
        val repository = Repository().apply { failSelection = true }
        val before = repository.progress
        val model = model(repository, "corner-capture")
        advanceUntilIdle()
        assertSame(before, repository.progress)
        assertNull(model.state.value.exercise)
        assertNotNull(model.state.value.error)
        repository.failSelection = false
        model.retry()
        advanceUntilIdle()
        assertEquals("corner-capture", model.state.value.exercise!!.puzzle.id)
        assertNull(model.state.value.error)
        assertEquals(2, repository.selections)
        assertEquals(0, repository.completions)
    }

    @Test fun repositoryRecheckRejectsARequestAfterThePrefixWasReplaced() = runTest(dispatcher) {
        val repository = Repository(GoEndgameProgress(GoEndgameLibrary.puzzles.take(2).map { it.id }.toSet()))
            .apply { replaceBeforeSelect = true }
        val model = model(repository, "escape-atari")
        advanceUntilIdle()
        assertNotNull(model.state.value.error)
        assertNull(model.state.value.exercise)
        assertTrue(repository.progress.completedIds.isEmpty())
        assertEquals(0, repository.completions)
        model.retry()
        advanceUntilIdle()
        assertEquals("escape-atari", model.state.value.lockedRequestedPuzzleId)
        assertNull(model.state.value.error)
    }

    @Test fun unknownInitialPuzzleIsRejectedBeforeRepositoryRead() = runTest(dispatcher) {
        val repository = Repository()
        try { model(repository, "unknown"); fail("Unknown puzzle accepted") }
        catch (_: IllegalArgumentException) { }
        advanceUntilIdle()
        assertEquals(0, repository.loads)
        assertEquals(0, engineCalls)
    }

    @Test fun retryAfterEnteringThePrerequisiteKeepsItsCommittedSelection() = runTest(dispatcher) {
        val repository = Repository()
        val model = model(repository, "escape-atari")
        advanceUntilIdle()
        model.openRequiredMainline(); advanceUntilIdle()
        model.retry(); advanceUntilIdle()
        assertNull(model.state.value.lockedRequestedPuzzleId)
        assertEquals("corner-capture", model.state.value.exercise!!.puzzle.id)
        assertEquals(GoEndgameChallengeMode.MAINLINE, model.state.value.mode)
        assertEquals(1, repository.selections)
    }

    @Test fun reconstructedSavedStateKeepsTheCommittedMainlineInsteadOfReapplyingTheLink() = runTest(dispatcher) {
        val repository = Repository()
        val saved = SavedStateHandle()
        val first = model(repository, "bridge-groups", saved)
        advanceUntilIdle()
        first.openRequiredMainline(); advanceUntilIdle()
        first.answer(GoEndgameLibrary.puzzles[0].answer); advanceUntilIdle()
        first.select("side-capture"); advanceUntilIdle()
        val restored = SavedStateHandle(saved.keys().associateWith { saved.get<Any>(it) })
        owners.forEach { it.clear() }
        val second = model(repository, "bridge-groups", restored)
        advanceUntilIdle()
        assertNull(second.state.value.lockedRequestedPuzzleId)
        assertEquals("side-capture", second.state.value.exercise!!.puzzle.id)
        assertEquals(GoEndgameChallengeMode.MAINLINE, second.state.value.mode)
        assertEquals(setOf("corner-capture"), second.state.value.completedIds)
        assertEquals(2, repository.selections)
    }

    @Test fun reconstructedSavedStateAlsoPreservesTheUsersDailySelection() = runTest(dispatcher) {
        val repository = Repository()
        val saved = SavedStateHandle()
        val first = model(repository, "corner-capture", saved)
        advanceUntilIdle()
        first.openDaily(); advanceUntilIdle()
        val progress = repository.progress
        val restored = SavedStateHandle(saved.keys().associateWith { saved.get<Any>(it) })
        owners.forEach { it.clear() }
        val second = model(repository, "corner-capture", restored)
        advanceUntilIdle()
        assertSame(progress, repository.progress)
        assertEquals(GoEndgameChallengeMode.DAILY, second.state.value.mode)
        assertEquals(progress.dailyDate, second.state.value.dailyDate)
        assertEquals(progress.dailyPuzzleId, second.state.value.exercise!!.puzzle.id)
    }

    private fun model(repository: Repository, puzzle: String, saved: SavedStateHandle? = null): GoEndgameViewModel =
        GoEndgameViewModel(repository, dispatcher, factory, initialPuzzleId = puzzle, initialRequestState = saved).also {
            owners += ViewModelStore().apply { put("practice", it) }
        }

    private class Repository(var progress: GoEndgameProgress = GoEndgameProgress()) : GoEndgameProgressRepository {
        var loads = 0; var selections = 0; var completions = 0
        var failSelection = false; var replaceBeforeSelect = false
        override suspend fun load(): GoEndgameProgress { loads++; return progress }
        override suspend fun select(id: String, mode: GoEndgameChallengeMode): GoEndgameProgress {
            selections++
            if (failSelection) throw IOException("Selection failed")
            if (replaceBeforeSelect) { progress = GoEndgameProgress(); replaceBeforeSelect = false }
            return progress.select(id, mode).also { progress = it }
        }
        override suspend fun selectDaily(date: LocalDate, id: String): GoEndgameProgress =
            progress.selectDaily(date, id).also { progress = it }
        override suspend fun complete(id: String): GoEndgameProgress {
            completions++; return progress.complete(id).also { progress = it }
        }
    }
}
