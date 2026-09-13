package com.masterofchessstrategy.navigation

import com.masterofchessstrategy.data.LastGameSelection
import com.masterofchessstrategy.data.StoredGameMode
import com.masterofchessstrategy.engine.Difficulty
import com.masterofchessstrategy.engine.GameType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppNavigationModelTest {
    @Test
    fun homeCatalogHasFiveCardsAndCoversEveryGameType() {
        assertEquals(5, HomeGameEntry.entries.size)
        assertEquals(
            GameType.entries.toSet(),
            HomeGameEntry.entries.flatMap { it.gameTypes }.toSet(),
        )
    }

    @Test
    fun onlyImplementedChineseChessEntryIsAvailable() {
        assertTrue(HomeGameEntry.CHINESE_CHESS.isAvailable)
        HomeGameEntry.entries
            .filterNot { it == HomeGameEntry.CHINESE_CHESS }
            .forEach { assertFalse(it.isAvailable) }
    }

    @Test
    fun modesRouteOnlyToImplementedOrExplicitSetupDestinations() {
        assertEquals(
            ModeDestination.GAME,
            ChineseChessMode.LOCAL_TWO_PLAYER.destination,
        )
        assertEquals(
            ModeDestination.DIFFICULTY,
            ChineseChessMode.HUMAN_VS_AI.destination,
        )
        assertEquals(
            ModeDestination.TUTORIAL,
            ChineseChessMode.TUTORIAL.destination,
        )
        assertTrue(
            listOf(ChineseChessMode.AI_AUTO_PLAY, ChineseChessMode.ENDGAME)
                .all { it.destination == ModeDestination.LOCKED },
        )
    }

    @Test
    fun tutorialUnlocksOnlyEasyDifficulty() {
        assertTrue(chineseChessDifficulties(tutorialCompleted = false).none { it.isUnlocked })
        val afterTutorial = chineseChessDifficulties(tutorialCompleted = true)

        assertEquals(4, afterTutorial.size)
        assertTrue(afterTutorial.single { it.difficulty == Difficulty.EASY }.isUnlocked)
        assertTrue(afterTutorial.single { it.difficulty == Difficulty.EASY }.isPlayable)
        assertTrue(afterTutorial.drop(1).none { it.isUnlocked })
    }

    @Test
    fun verifiedWinsUnlockMediumDifficultyAndItsImplementedEngine() {
        val entries = chineseChessDifficulties(
            tutorialCompleted = true,
            winsByDifficulty = mapOf(Difficulty.EASY to 10),
        )
        val medium = entries.single { it.difficulty == Difficulty.MEDIUM }

        assertTrue(medium.isUnlocked)
        assertTrue(medium.isPlayable)
    }

    @Test
    fun fifteenMediumWinsUnlockImplementedHardDifficulty() {
        val entries = chineseChessDifficulties(
            tutorialCompleted = true,
            winsByDifficulty = mapOf(Difficulty.MEDIUM to 15),
        )
        val hard = entries.single { it.difficulty == Difficulty.HARD }

        assertTrue(hard.isUnlocked)
        assertTrue(hard.isPlayable)
    }

    @Test
    fun tutorialHistoryReturnsDirectlyToTutorial() {
        val selection = LastGameSelection(
            gameType = GameType.CHINESE_CHESS,
            mode = StoredGameMode.TUTORIAL,
            difficulty = null,
            updatedAtEpochMillis = 1L,
        )

        assertEquals(
            QuickStartDestination.TUTORIAL,
            selection.quickStartDestination(),
        )
    }

    @Test
    fun configuredAiHistoryQuickStartsAiGame() {
        val selection = LastGameSelection(
            gameType = GameType.CHINESE_CHESS,
            mode = StoredGameMode.HUMAN_VS_AI,
            difficulty = Difficulty.EASY,
            updatedAtEpochMillis = 1L,
        )

        assertEquals(
            QuickStartDestination.AI_GAME,
            selection.quickStartDestination(),
        )
    }

    @Test
    fun aiRouteCarriesStableMediumDifficultyCode() {
        assertEquals(
            "chinese-chess/ai-game/1",
            AppDestination.chineseChessAiGame(Difficulty.MEDIUM),
        )
    }

    @Test
    fun masterBecomesPlayableAfterTwentyHardWins() {
        val beforeThreshold = chineseChessDifficulties(
            tutorialCompleted = true,
            winsByDifficulty = mapOf(Difficulty.HARD to 19),
        ).single { it.difficulty == Difficulty.MASTER }
        val atThreshold = chineseChessDifficulties(
            tutorialCompleted = true,
            winsByDifficulty = mapOf(Difficulty.HARD to 20),
        ).single { it.difficulty == Difficulty.MASTER }

        assertFalse(beforeThreshold.isUnlocked)
        assertFalse(beforeThreshold.isPlayable)
        assertTrue(atThreshold.isUnlocked)
        assertTrue(atThreshold.isPlayable)
    }
}
