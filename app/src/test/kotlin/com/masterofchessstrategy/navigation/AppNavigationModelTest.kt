package com.masterofchessstrategy.navigation

import com.masterofchessstrategy.data.LastGameSelection
import com.masterofchessstrategy.data.StoredGameMode
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
        assertTrue(
            ChineseChessMode.entries
                .filterNot {
                    it == ChineseChessMode.LOCAL_TWO_PLAYER ||
                        it == ChineseChessMode.HUMAN_VS_AI
                }
                .all { it.destination == ModeDestination.LOCKED },
        )
    }

    @Test
    fun allAiDifficultiesStayLockedBeforeTutorialExists() {
        assertEquals(4, ChineseChessDifficulties.size)
        assertTrue(ChineseChessDifficulties.none { it.isUnlocked })
    }

    @Test
    fun lockedHistoricalModeReturnsToModeSelection() {
        val selection = LastGameSelection(
            gameType = GameType.CHINESE_CHESS,
            mode = StoredGameMode.TUTORIAL,
            difficulty = null,
            updatedAtEpochMillis = 1L,
        )

        assertEquals(
            QuickStartDestination.MODE_SELECTION,
            selection.quickStartDestination(),
        )
    }
}
