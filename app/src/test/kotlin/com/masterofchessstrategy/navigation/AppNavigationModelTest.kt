package com.masterofchessstrategy.navigation

import com.masterofchessstrategy.data.LastGameSelection
import com.masterofchessstrategy.data.StoredGameMode
import com.masterofchessstrategy.challenge.TimedChallengeConfig
import com.masterofchessstrategy.challenge.StreakChallengeState
import com.masterofchessstrategy.challenge.StreakChallengeStateCodec
import com.masterofchessstrategy.challenge.AssessmentChallengeState
import com.masterofchessstrategy.challenge.AssessmentChallengeStateCodec
import com.masterofchessstrategy.custom.CustomPositionStateCodec
import com.masterofchessstrategy.engine.Difficulty
import com.masterofchessstrategy.engine.GameType
import org.junit.Assert.assertArrayEquals
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
        assertEquals(
            ModeDestination.AUTO_PLAY_DIFFICULTY,
            ChineseChessMode.AI_AUTO_PLAY.destination,
        )
        assertEquals(
            ModeDestination.ENDGAME_CATALOG,
            ChineseChessMode.ENDGAME.destination,
        )
        assertEquals(
            ModeDestination.EXTENSIONS,
            ChineseChessMode.EXTENSIONS.destination,
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
    fun configuredAutoPlayHistoryQuickStartsItsOwnStableRoute() {
        val selection = LastGameSelection(
            gameType = GameType.CHINESE_CHESS,
            mode = StoredGameMode.AI_AUTO_PLAY,
            difficulty = Difficulty.HARD,
            updatedAtEpochMillis = 1L,
        )

        assertEquals(
            QuickStartDestination.AUTO_PLAY_GAME,
            selection.quickStartDestination(),
        )
        assertEquals(
            "chinese-chess/auto-play/2",
            AppDestination.chineseChessAutoPlayGame(Difficulty.HARD),
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

    @Test
    fun recordRouteKeepsStableLocalIdentifier() {
        assertEquals("records/match-123", AppDestination.chineseChessRecord("match-123"))
    }

    @Test
    fun endgameRouteKeepsStablePackIdentifier() {
        assertEquals(
            "chinese-chess/endgames/xq-easy-001",
            AppDestination.chineseChessEndgame("xq-easy-001"),
        )
    }

    @Test
    fun customPositionRouteRoundTripsCanonicalBytes() {
        val state = byteArrayOf(0, 1, 15, 16, 127, -1)

        val route = AppDestination.chineseChessCustomGame(Difficulty.HARD, state)
        val encoded = route.substringAfterLast('/')

        assertEquals("chinese-chess/extensions/custom/2/00010f107fff", route)
        assertArrayEquals(state, CustomPositionStateCodec.decode(encoded))
        assertEquals(
            "custom:00010f107fff",
            CustomPositionStateCodec.sessionVariant(state),
        )
    }

    @Test
    fun customPositionHistoryReturnsToItsSetupScreen() {
        val selection = LastGameSelection(
            gameType = GameType.CHINESE_CHESS,
            mode = StoredGameMode.CUSTOM_POSITION,
            difficulty = Difficulty.EASY,
            updatedAtEpochMillis = 1L,
        )

        assertEquals(
            QuickStartDestination.CUSTOM_SETUP,
            selection.quickStartDestination(),
        )
    }

    @Test
    fun timedChallengeRouteUsesCanonicalDifficultyAndClock() {
        val route = AppDestination.chineseChessTimedGame(Difficulty.MEDIUM, 30)

        assertEquals("chinese-chess/extensions/timed/1/30", route)
        assertEquals(
            "timed:30",
            TimedChallengeConfig.sessionVariant(route.substringAfterLast('/').toInt()),
        )
    }

    @Test
    fun timedChallengeHistoryReturnsToItsSetupScreen() {
        val selection = LastGameSelection(
            gameType = GameType.CHINESE_CHESS,
            mode = StoredGameMode.TIMED_CHALLENGE,
            difficulty = Difficulty.EASY,
            updatedAtEpochMillis = 1L,
        )

        assertEquals(
            QuickStartDestination.TIMED_SETUP,
            selection.quickStartDestination(),
        )
    }

    @Test
    fun streakRouteCarriesCanonicalSeriesState() {
        val state = StreakChallengeState(currentStreak = 2, bestStreak = 4)
        val route = AppDestination.chineseChessStreakGame(Difficulty.HARD, state)
        val encoded = route.substringAfterLast('/')

        assertEquals("chinese-chess/extensions/streak/2/streak:2:4:0:0:n", route)
        assertEquals(state, StreakChallengeStateCodec.decode(encoded))
    }

    @Test
    fun streakHistoryReturnsToItsSetupScreen() {
        val selection = LastGameSelection(
            gameType = GameType.CHINESE_CHESS,
            mode = StoredGameMode.STREAK_CHALLENGE,
            difficulty = Difficulty.EASY,
            updatedAtEpochMillis = 1L,
        )

        assertEquals(
            QuickStartDestination.STREAK_SETUP,
            selection.quickStartDestination(),
        )
    }

    @Test
    fun blindChallengeRouteUsesStableDifficulty() {
        assertEquals(
            "chinese-chess/extensions/blind/2",
            AppDestination.chineseChessBlindGame(Difficulty.HARD),
        )
    }

    @Test
    fun blindChallengeHistoryReturnsToItsSetupScreen() {
        val selection = LastGameSelection(
            gameType = GameType.CHINESE_CHESS,
            mode = StoredGameMode.BLIND_CHALLENGE,
            difficulty = Difficulty.EASY,
            updatedAtEpochMillis = 1L,
        )

        assertEquals(
            QuickStartDestination.BLIND_SETUP,
            selection.quickStartDestination(),
        )
    }

    @Test
    fun assessmentRouteCarriesCanonicalSeriesState() {
        val state = AssessmentChallengeState(
            completedGames = 1,
            rating = 1_400,
            wins = 1,
        )
        val route = AppDestination.chineseChessAssessmentGame(Difficulty.HARD, state)

        assertEquals(
            "chinese-chess/extensions/assessment/2/assessment:1:1400:1:0:0:n",
            route,
        )
        assertEquals(state, AssessmentChallengeStateCodec.decode(route.substringAfterLast('/')))
    }

    @Test
    fun assessmentHistoryReturnsToItsSetupScreen() {
        val selection = LastGameSelection(
            gameType = GameType.CHINESE_CHESS,
            mode = StoredGameMode.ASSESSMENT_CHALLENGE,
            difficulty = Difficulty.MEDIUM,
            updatedAtEpochMillis = 1L,
        )

        assertEquals(
            QuickStartDestination.ASSESSMENT_SETUP,
            selection.quickStartDestination(),
        )
    }

    @Test
    fun openingAutoPlayRouteCarriesDifficultyAndCanonicalPosition() {
        val route = AppDestination.chineseChessOpeningAutoPlay(
            Difficulty.MEDIUM,
            byteArrayOf(1, 2, 0x7f),
        )

        assertEquals("chinese-chess/extensions/openings/auto/1/01027f", route)
        assertEquals(
            byteArrayOf(1, 2, 0x7f).toList(),
            CustomPositionStateCodec.decode(route.substringAfterLast('/')).toList(),
        )
    }

    @Test
    fun openingAutoPlayHistoryReturnsToTrainingLibrary() {
        val selection = LastGameSelection(
            gameType = GameType.CHINESE_CHESS,
            mode = StoredGameMode.OPENING_AUTO_PLAY,
            difficulty = Difficulty.MEDIUM,
            updatedAtEpochMillis = 1L,
        )

        assertEquals(
            QuickStartDestination.OPENING_TRAINING,
            selection.quickStartDestination(),
        )
    }
}
