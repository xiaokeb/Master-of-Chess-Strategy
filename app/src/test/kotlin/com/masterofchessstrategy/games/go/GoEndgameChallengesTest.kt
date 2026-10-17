package com.masterofchessstrategy.games.go

import java.time.LocalDate
import kotlin.random.Random
import org.junit.Assert.*
import org.junit.Test

class GoEndgameChallengesTest {
    private val ids = GoEndgameLibrary.puzzles.map { it.id }
    private val date = LocalDate.of(2026, 10, 14)

    @Test fun themeAndLevelFiltersPreserveMainlineOrderWithoutChangingUnlocks() {
        assertEquals(listOf(ids[0], ids[1], ids[4]), GoEndgameChallenges.catalog(GoEndgameTheme.CAPTURE).map { it.id })
        assertEquals(listOf(ids[4]), GoEndgameChallenges.catalog(GoEndgameTheme.CAPTURE, GoEndgameLevel.MEDIUM).map { it.id })
        assertTrue(GoEndgameChallenges.available(GoEndgameProgress(), level = GoEndgameLevel.MEDIUM).isEmpty())
        assertEquals(listOf(ids[0]), GoEndgameChallenges.available(GoEndgameProgress(), GoEndgameTheme.CAPTURE).map { it.id })
    }

    @Test fun firstDailyChoiceIsDeterministicAndAlwaysUnlockedIncludingDatesBeforeEpoch() {
        for (count in 0..ids.size) for (day in listOf(date, date.minusDays(1), LocalDate.of(1969, 12, 31))) {
            val progress = GoEndgameProgress(ids.take(count).toSet())
            val selected = GoEndgameChallenges.daily(progress, day)
            assertTrue(progress.isUnlocked(selected.id))
            assertEquals(selected.id, GoEndgameChallenges.daily(progress, day).id)
        }
    }

    @Test fun reservedDailyDoesNotChangeWhenSameDayUnlockPoolGrows() {
        val original = GoEndgameProgress().selectDaily(date, ids.first())
        val advanced = original.complete(ids[0]).complete(ids[1]).complete(ids[2])
        assertEquals(ids.first(), GoEndgameChallenges.daily(advanced, date).id)
        assertEquals(date, advanced.dailyDate); assertEquals(GoEndgameChallengeMode.DAILY, advanced.lastMode)
        val nextDate = date.plusDays(1)
        val next = advanced.selectDaily(nextDate, GoEndgameChallenges.daily(advanced, nextDate).id)
        assertEquals(nextDate, next.dailyDate)
    }

    @Test fun randomSelectionStaysWithinUnlockedFilteredPoolAndReturnsNullForEmptyPool() {
        val progress = GoEndgameProgress(ids.take(3).toSet())
        val source = Random(12345)
        val pool = GoEndgameChallenges.available(progress, GoEndgameTheme.CONNECTION)
        repeat(50) { assertTrue(GoEndgameChallenges.random(progress, source, GoEndgameTheme.CONNECTION) in pool) }
        assertNull(GoEndgameChallenges.random(GoEndgameProgress(), source, level = GoEndgameLevel.MEDIUM))
    }

    @Test fun randomSelectionCanReachEveryCandidateWithFixedSeed() {
        val progress = GoEndgameProgress(ids.toSet())
        val source = Random(98765)
        assertEquals(ids.toSet(), (0 until 200).map { GoEndgameChallenges.random(progress, source)!!.id }.toSet())
    }

    @Test fun cachedDailyMustBeUnlockedAndMatchActiveDailyPuzzle() {
        assertInvalid { GoEndgameProgress(dailyDate = date) }
        assertInvalid { GoEndgameProgress(dailyDate = date, dailyPuzzleId = ids[1]) }
        assertInvalid { GoEndgameProgress(lastMode = GoEndgameChallengeMode.DAILY) }
        assertInvalid { GoEndgameProgress(setOf(ids[0]), ids[1], GoEndgameChallengeMode.DAILY, date, ids[0]) }
    }

    @Test fun completingAnOldPuzzlePreservesCurrentChallengeAndDailyReservation() {
        val progress = GoEndgameProgress(ids.take(2).toSet(), ids[2]).selectDaily(date,
            GoEndgameChallenges.daily(GoEndgameProgress(ids.take(2).toSet()), date).id)
            .select(ids[2], GoEndgameChallengeMode.RANDOM)
        val repeated = progress.complete(ids[0])
        assertEquals(ids[2], repeated.lastPuzzleId); assertEquals(GoEndgameChallengeMode.RANDOM, repeated.lastMode)
        assertEquals(progress.dailyPuzzleId, repeated.dailyPuzzleId); assertEquals(date, repeated.dailyDate)
    }

    private fun assertInvalid(block: () -> Unit) {
        try { block(); fail("Invalid challenge metadata accepted") } catch (_: IllegalArgumentException) { }
    }
}
