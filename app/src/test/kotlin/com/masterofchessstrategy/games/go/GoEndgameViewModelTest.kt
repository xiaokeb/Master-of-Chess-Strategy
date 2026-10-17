package com.masterofchessstrategy.games.go

import androidx.lifecycle.ViewModelStore
import com.masterofchessstrategy.engine.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class GoEndgameViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun teardown() { Dispatchers.resetMain() }
    private val first = GoEndgameLibrary.puzzles[0]
    private val second = GoEndgameLibrary.puzzles[1]
    private val factory: (Int, Int, Int) -> GoRecordEngine = { size, _, _ -> GoEndgameLibraryTest.GridRules(size) }

    @Test fun initialLoadRestoresOnlyPersistedProgressAndLastUnlockedPuzzle() = runTest(dispatcher) {
        val repository = Repository(GoEndgameProgress(setOf(first.id), second.id))
        val model = GoEndgameViewModel(repository, dispatcher, factory)
        val store = ViewModelStore().apply { put("qa", model) }
        try {
            runCurrent(); assertTrue(model.state.value.canOperate)
            assertEquals(second.id, model.state.value.exercise!!.puzzle.id)
            assertEquals(setOf(first.id), model.state.value.completedIds)
            model.select(GoEndgameLibrary.puzzles[2].id); runCurrent()
            assertEquals(second.id, model.state.value.exercise!!.puzzle.id); assertEquals(0, repository.selections)
        } finally { store.clear(); runCurrent() }
    }

    @Test fun failedCompletionCannotUnlockNextAndRetryPreservesSolvedPuzzle() = runTest(dispatcher) {
        val repository = Repository().apply { failCompletion = true }
        val model = GoEndgameViewModel(repository, dispatcher, factory)
        val store = ViewModelStore().apply { put("qa", model) }
        try {
            runCurrent(); model.answer(first.answer); runCurrent()
            val failed = model.state.value
            assertTrue(failed.exercise!!.completed); assertTrue(failed.needsSave); assertFalse(failed.canOperate)
            assertTrue(failed.completedIds.isEmpty()); assertFalse(failed.isUnlocked(second.id)); assertNotNull(failed.error)
            model.reset(); model.select(second.id); runCurrent()
            assertTrue(model.state.value.exercise!!.completed); assertEquals(0, repository.selections)
            repository.failCompletion = false; model.retry(); runCurrent()
            assertFalse(model.state.value.needsSave); assertTrue(model.state.value.canOperate)
            assertEquals(setOf(first.id), model.state.value.completedIds); assertEquals(2, repository.completions)
            model.select(second.id); runCurrent(); assertEquals(second.id, repository.progress.lastPuzzleId)
        } finally { store.clear(); runCurrent() }
    }

    @Test fun wrongAnswerNeverWritesProgressAndRepeatChallengeNeverCompletesAgain() = runTest(dispatcher) {
        val repository = Repository()
        val model = GoEndgameViewModel(repository, dispatcher, factory)
        val store = ViewModelStore().apply { put("qa", model) }
        try {
            runCurrent(); model.answer(BoardPosition(4, 4)); runCurrent()
            assertEquals(0, repository.completions); assertFalse(model.state.value.exercise!!.completed)
            model.answer(first.answer); runCurrent(); assertEquals(1, repository.completions)
            model.reset(); runCurrent(); model.answer(first.answer); runCurrent()
            assertEquals(1, repository.completions); assertEquals(setOf(first.id), model.state.value.completedIds)
        } finally { store.clear(); runCurrent() }
    }

    @Test fun selectionSaveFailurePreservesOldPuzzleAndRetrySelectsRequestedPuzzle() = runTest(dispatcher) {
        val repository = Repository(GoEndgameProgress(setOf(first.id))).apply { failSelection = true }
        val model = GoEndgameViewModel(repository, dispatcher, factory)
        val store = ViewModelStore().apply { put("qa", model) }
        try {
            runCurrent(); model.select(second.id); runCurrent()
            assertEquals(first.id, model.state.value.exercise!!.puzzle.id); assertNotNull(model.state.value.error)
            assertEquals(first.id, repository.progress.lastPuzzleId)
            repository.failSelection = false; model.retry(); runCurrent()
            assertEquals(second.id, model.state.value.exercise!!.puzzle.id); assertNull(model.state.value.error)
        } finally { store.clear(); runCurrent() }
    }

    @Test fun failedLoadNeverCreatesBlankPlayablePuzzleAndCanRetry() = runTest(dispatcher) {
        val repository = Repository().apply { failLoad = true }
        val model = GoEndgameViewModel(repository, dispatcher, factory)
        val store = ViewModelStore().apply { put("qa", model) }
        try {
            runCurrent(); assertNull(model.state.value.exercise); assertFalse(model.state.value.canOperate)
            assertNotNull(model.state.value.error)
            repository.failLoad = false; model.retry(); runCurrent()
            assertTrue(model.state.value.canOperate); assertEquals(first.id, model.state.value.exercise!!.puzzle.id)
        } finally { store.clear(); runCurrent() }
    }

    @Test fun closingDuringAcceptedSaveStillCommitsForNextViewModel() = runTest(dispatcher) {
        val repository = Repository().apply { completionGate = CompletableDeferred() }
        val model = GoEndgameViewModel(repository, dispatcher, factory)
        val store = ViewModelStore().apply { put("qa", model) }
        runCurrent(); model.answer(first.answer); runCurrent()
        assertTrue(model.state.value.loading); assertFalse(model.state.value.isUnlocked(second.id))
        store.clear(); runCurrent(); repository.completionGate!!.complete(Unit); runCurrent()
        assertEquals(setOf(first.id), repository.progress.completedIds)
        val restored = GoEndgameViewModel(repository, dispatcher, factory)
        val restoredStore = ViewModelStore().apply { put("qa", restored) }
        try { runCurrent(); assertTrue(restored.state.value.isUnlocked(second.id)) }
        finally { restoredStore.clear(); runCurrent() }
    }

    @Test fun repeatedAnswersSelectionsAndRetriesAreRejectedWhileCompletionWriteIsInFlight() = runTest(dispatcher) {
        val repository = Repository().apply { completionGate = CompletableDeferred() }
        val model = GoEndgameViewModel(repository, dispatcher, factory)
        val store = ViewModelStore().apply { put("qa", model) }
        try {
            runCurrent(); model.answer(first.answer); runCurrent()
            model.answer(first.answer); model.select(second.id); model.reset(); model.retry(); runCurrent()
            assertEquals(1, repository.completions); assertEquals(0, repository.selections)
            assertFalse(model.state.value.isUnlocked(second.id))
            repository.completionGate!!.complete(Unit); runCurrent()
            assertTrue(model.state.value.isUnlocked(second.id)); assertEquals(1, repository.completions)
        } finally { repository.completionGate!!.complete(Unit); store.clear(); runCurrent() }
    }

    @Test fun dailyReservationSurvivesCompletionAndViewModelRecreationOnSameDate() = runTest(dispatcher) {
        val date = java.time.LocalDate.of(2026, 10, 14)
        val repository = Repository()
        val model = GoEndgameViewModel(repository, dispatcher, factory, today = { date })
        val store = ViewModelStore().apply { put("qa", model) }
        try {
            runCurrent(); model.openDaily(); runCurrent()
            assertEquals(GoEndgameChallengeMode.DAILY, model.state.value.mode)
            assertEquals(first.id, repository.progress.dailyPuzzleId)
            model.answer(first.answer); runCurrent(); model.openDaily(); runCurrent()
            assertEquals(first.id, model.state.value.exercise!!.puzzle.id)
            assertEquals(date, model.state.value.dailyDate)
        } finally { store.clear(); runCurrent() }
        val restored = GoEndgameViewModel(repository, dispatcher, factory, today = { date })
        val nextStore = ViewModelStore().apply { put("qa", restored) }
        try {
            runCurrent(); assertEquals(GoEndgameChallengeMode.DAILY, restored.state.value.mode)
            assertEquals(first.id, restored.state.value.exercise!!.puzzle.id)
            assertEquals(setOf(first.id), restored.state.value.completedIds)
        } finally { nextStore.clear(); runCurrent() }
    }

    @Test fun failedDailySaveKeepsOldModeAndRetriesOriginalCapturedDate() = runTest(dispatcher) {
        var date = java.time.LocalDate.of(2026, 10, 14)
        val repository = Repository().apply { failSelection = true }
        val model = GoEndgameViewModel(repository, dispatcher, factory, today = { date })
        val store = ViewModelStore().apply { put("qa", model) }
        try {
            runCurrent(); model.openDaily(); runCurrent()
            assertEquals(GoEndgameChallengeMode.MAINLINE, model.state.value.mode)
            assertNull(repository.progress.dailyDate); assertNotNull(model.state.value.error)
            date = date.plusDays(1); repository.failSelection = false; model.retry(); runCurrent()
            assertEquals(date.minusDays(1), repository.progress.dailyDate)
            assertEquals(GoEndgameChallengeMode.DAILY, model.state.value.mode)
            model.openDaily(); runCurrent(); assertEquals(date, repository.progress.dailyDate)
        } finally { store.clear(); runCurrent() }
    }

    @Test fun randomSelectionHonorsUnlocksFiltersAndRestoresMode() = runTest(dispatcher) {
        val repository = Repository()
        val model = GoEndgameViewModel(repository, dispatcher, factory, random = kotlin.random.Random(5))
        val store = ViewModelStore().apply { put("qa", model) }
        try {
            runCurrent(); model.openRandom(level = GoEndgameLevel.MEDIUM); runCurrent()
            assertEquals(0, repository.selections); assertEquals(GoEndgameChallengeMode.MAINLINE, model.state.value.mode)
            model.openRandom(); runCurrent()
            assertEquals(first.id, model.state.value.exercise!!.puzzle.id)
            assertEquals(GoEndgameChallengeMode.RANDOM, repository.progress.lastMode)
            model.select(second.id, GoEndgameChallengeMode.THEME); runCurrent()
            assertEquals(first.id, model.state.value.exercise!!.puzzle.id)
        } finally { store.clear(); runCurrent() }
        val restored = GoEndgameViewModel(repository, dispatcher, factory)
        val nextStore = ViewModelStore().apply { put("qa", restored) }
        try { runCurrent(); assertEquals(GoEndgameChallengeMode.RANDOM, restored.state.value.mode) }
        finally { nextStore.clear(); runCurrent() }
    }

    @Test fun themedSelectionPersistsWithoutFabricatingDailyAndReturningMainlineClearsMode() = runTest(dispatcher) {
        val repository = Repository(GoEndgameProgress(setOf(first.id)))
        val model = GoEndgameViewModel(repository, dispatcher, factory)
        val store = ViewModelStore().apply { put("qa", model) }
        try {
            runCurrent(); model.select(second.id, GoEndgameChallengeMode.DAILY); runCurrent()
            assertEquals(0, repository.selections); assertNull(repository.progress.dailyDate)
            model.select(second.id, GoEndgameChallengeMode.THEME); runCurrent()
            assertEquals(GoEndgameChallengeMode.THEME, model.state.value.mode)
            model.select(first.id); runCurrent()
            assertEquals(GoEndgameChallengeMode.MAINLINE, model.state.value.mode)
        } finally { store.clear(); runCurrent() }
    }

    private class Repository(var progress: GoEndgameProgress = GoEndgameProgress()) : GoEndgameProgressRepository {
        var failLoad = false; var failSelection = false; var failCompletion = false
        var selections = 0; var completions = 0
        var completionGate: CompletableDeferred<Unit>? = null
        override suspend fun load(): GoEndgameProgress { if (failLoad) throw IOException(); return progress }
        override suspend fun select(id: String, mode: GoEndgameChallengeMode): GoEndgameProgress {
            selections++; if (failSelection) throw IOException()
            progress = progress.select(id, mode); return progress
        }
        override suspend fun selectDaily(date: java.time.LocalDate, id: String): GoEndgameProgress {
            selections++; if (failSelection) throw IOException()
            progress = progress.selectDaily(date, id); return progress
        }
        override suspend fun complete(id: String): GoEndgameProgress {
            completions++; completionGate?.await(); if (failCompletion) throw IOException()
            progress = progress.complete(id); return progress
        }
    }
}
