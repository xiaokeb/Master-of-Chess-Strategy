package com.masterofchessstrategy.games.competition_mahjong.challenge

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.time.LocalDate
import kotlin.random.Random

class CompetitionMahjongChallengeViewModelTest {
    private class MemoryRepository(
        var progress: CompetitionMahjongChallengeProgress = CompetitionMahjongChallengeProgress(),
    ) : CompetitionMahjongChallengeProgressRepository {
        var failSelect = false
        var failComplete = false
        override suspend fun load() = progress
        override suspend fun select(id: String, mode: CompetitionMahjongChallengeMode): CompetitionMahjongChallengeProgress {
            if (failSelect) { failSelect = false; throw IOException("selection failure") }
            return progress.select(id, mode).also { progress = it }
        }
        override suspend fun selectDaily(date: LocalDate, id: String): CompetitionMahjongChallengeProgress {
            if (failSelect) { failSelect = false; throw IOException("selection failure") }
            return progress.selectDaily(date, id).also { progress = it }
        }
        override suspend fun complete(id: String): CompetitionMahjongChallengeProgress {
            if (failComplete) { failComplete = false; throw IOException("completion failure") }
            return progress.complete(id).also { progress = it }
        }
    }

    private class Harness(initial: CompetitionMahjongChallengeProgress = CompetitionMahjongChallengeProgress()) {
        val repository = MemoryRepository(initial)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val game = CompetitionMahjongChallengeViewModel(repository, scope, Dispatchers.Unconfined,
            today = { LocalDate.of(2026, 10, 15) }, random = Random(7))
        suspend fun ready() = withTimeout(2_000) { game.state.first { !it.loading && it.exercise != null } }
        fun close() = scope.cancel()
    }

    @Test fun loadWrongAnswerAndCorrectCompletionUnlockExactlyOneQuestion() = runBlocking {
        val harness = Harness()
        try {
            var state = harness.ready()
            assertEquals("seven-plus-flowers", state.exercise!!.challenge.id)
            harness.game.answer("a").join()
            state = harness.game.state.value
            assertFalse(state.exercise!!.completed)
            assertTrue(state.completedIds.isEmpty())

            harness.game.answer("b").join()
            state = harness.game.state.value
            assertTrue(state.exercise!!.completed)
            assertEquals(setOf("seven-plus-flowers"), state.completedIds)
            assertTrue(state.isUnlocked("last-wall-self-draw"))
            assertFalse(state.needsSave)
        } finally { harness.close() }
    }

    @Test fun completionSaveFailureKeepsSolvedQuestionForExplicitRetry() = runBlocking {
        val harness = Harness()
        try {
            harness.ready()
            harness.repository.failComplete = true
            harness.game.answer("b").join()
            var state = harness.game.state.value
            assertTrue(state.exercise!!.completed)
            assertTrue(state.needsSave)
            assertNotNull(state.error)
            assertTrue(state.completedIds.isEmpty())

            harness.game.retry().join()
            state = harness.game.state.value
            assertFalse(state.needsSave)
            assertNull(state.error)
            assertTrue("seven-plus-flowers" in state.completedIds)
        } finally { harness.close() }
    }

    @Test fun failedSelectionPreservesCurrentQuestionAndStoredPosition() = runBlocking {
        val first = CompetitionMahjongChallengeLibrary.challenges.first()
        val second = CompetitionMahjongChallengeLibrary.challenges[1]
        val harness = Harness(CompetitionMahjongChallengeProgress().complete(first.id))
        try {
            harness.ready()
            harness.repository.failSelect = true
            harness.game.select(second.id).join()
            val state = harness.game.state.value
            assertEquals(first.id, state.exercise!!.challenge.id)
            assertEquals(first.id, harness.repository.progress.lastChallengeId)
            assertNotNull(state.error)
        } finally { harness.close() }
    }

    @Test fun dailyAndFilteredRandomUseOnlyUnlockedContentAndPersistMode() = runBlocking {
        val values = CompetitionMahjongChallengeLibrary.challenges
        var initial = CompetitionMahjongChallengeProgress()
        repeat(6) { initial = initial.complete(values[it].id) }
        val harness = Harness(initial)
        try {
            harness.ready()
            harness.game.openDaily().join()
            var state = harness.game.state.value
            var exercise = checkNotNull(state.exercise)
            assertEquals(CompetitionMahjongChallengeMode.DAILY, state.mode)
            assertEquals(LocalDate.of(2026, 10, 15), state.dailyDate)
            assertTrue(exercise.challenge.id in initial.completedIds || initial.isUnlocked(exercise.challenge.id))

            harness.game.openRandom(level = CompetitionMahjongChallengeLevel.MEDIUM).join()
            state = harness.game.state.value
            exercise = checkNotNull(state.exercise)
            assertEquals(CompetitionMahjongChallengeMode.RANDOM, state.mode)
            assertEquals(CompetitionMahjongChallengeLevel.MEDIUM, exercise.challenge.level)
            assertTrue(initial.isUnlocked(exercise.challenge.id))
        } finally { harness.close() }
    }

    @Test fun parentCancellationClosesStateAndRejectsFurtherChanges() = runBlocking {
        val harness = Harness()
        harness.ready()
        harness.close()
        withTimeout(2_000) { harness.game.state.first { it.closed } }
        val before = harness.game.state.value
        harness.game.answer("b").join()
        assertEquals(before, harness.game.state.value)
    }
}
