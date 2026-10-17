package com.masterofchessstrategy.games.go

import com.masterofchessstrategy.engine.GoPlayer
import org.junit.Assert.*
import org.junit.Test

/** Pure storage checks: no Native engine, search coordinator or running session is required. */
class GoAiMatchStorageContractTest {
    @Test fun originalDifficultyBudgetsAndAssistanceLimitsAreUnchanged() {
        assertEquals(listOf(1 to 1_000L, 8 to 3_000L, 32 to 8_000L, 128 to 20_000L),
            GoAiDifficulty.entries.map { it.budget.maxVisits to it.budget.maxTimeMillis })
        assertEquals(listOf(Int.MAX_VALUE, 3, 1, 0), GoAiDifficulty.entries.map { it.undoLimit })
        assertEquals(GoAiDifficulty.entries.map { it.undoLimit }, GoAiDifficulty.entries.map { it.hintLimit })
    }

    @Test fun searchBudgetStillRejectsOutOfRangeVisitsAndTime() {
        assertEquals(512, GoAiSearchBudget(512, 20_000).maxVisits)
        for ((visits, time) in listOf(0 to 1L, 513 to 1L, 1 to 0L, 1 to 20_001L)) {
            assertThrows(IllegalArgumentException::class.java) { GoAiSearchBudget(visits, time) }
        }
    }

    @Test fun savedCurrentAndOpeningBytesNeverAliasTheCallerOrEachOther() {
        val bytes = byteArrayOf(1, 2)
        val opening = byteArrayOf(3, 4)
        val saved = GoAiSavedMatch("owned", GoAiMatchConfig(), bytes, openingBytes = opening)
        bytes[0] = 9
        opening[0] = 9
        saved.copyBytes()[1] = 9
        saved.copyOpeningBytes()!![1] = 9
        assertArrayEquals(byteArrayOf(1, 2), saved.copyBytes())
        assertArrayEquals(byteArrayOf(3, 4), saved.copyOpeningBytes())
        assertTrue(saved.hasSpecifiedOpening)
    }

    @Test fun matchConfigurationRetainsBoardHandicapAndPlayerValidation() {
        val human = GoAiMatchConfig(13, 5, GoPlayer.WHITE)
        assertFalse(human.automatic)
        assertTrue(human.isAi(GoPlayer.BLACK))
        assertFalse(human.isAi(GoPlayer.WHITE))
        assertTrue(GoAiMatchConfig(humanPlayer = null).automatic)
        for ((size, handicap) in listOf(10 to 0, 9 to 1, 19 to 10)) {
            assertThrows(IllegalArgumentException::class.java) { GoAiMatchConfig(size, handicap) }
        }
    }

    @Test fun invalidIdentityPayloadAndAssistanceStillFailBeforeStorage() {
        val config = GoAiMatchConfig(difficulty = GoAiDifficulty.MASTER)
        assertThrows(IllegalArgumentException::class.java) { GoAiSavedMatch("", config, byteArrayOf(1)) }
        assertThrows(IllegalArgumentException::class.java) { GoAiSavedMatch("valid", config, byteArrayOf()) }
        assertThrows(IllegalArgumentException::class.java) {
            GoAiSavedMatch("valid", config, byteArrayOf(1), hintUsed = 1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            GoAiSavedMatch("valid", config, byteArrayOf(1), openingBytes = byteArrayOf())
        }
    }

    @Test fun automaticContinuationRetainsItsBoundedAutomaticOnlyContract() {
        val automatic = GoAiMatchConfig(humanPlayer = null)
        val saved = GoAiSavedMatch("automatic", automatic, byteArrayOf(1), autoContinueEnabled = true,
            autoContinueMaxGames = 100, autoContinueGameNumber = 100)
        assertEquals(100, saved.autoContinueGameNumber)
        assertThrows(IllegalArgumentException::class.java) {
            GoAiSavedMatch("human", GoAiMatchConfig(), byteArrayOf(1), autoContinueEnabled = true,
                autoContinueMaxGames = 2)
        }
        assertThrows(IllegalArgumentException::class.java) {
            GoAiSavedMatch("automatic", automatic, byteArrayOf(1), autoContinueEnabled = true,
                autoContinueMaxGames = 2, autoContinueGameNumber = 3)
        }
        assertThrows(IllegalArgumentException::class.java) {
            GoAiSavedMatch("automatic", automatic, byteArrayOf(1), autoContinueMaxGames = 2)
        }
    }
}
