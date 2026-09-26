package com.masterofchessstrategy.tutorial

import com.masterofchessstrategy.data.TutorialProgress
import com.masterofchessstrategy.endgame.ChineseChessEndgamePackParser
import com.masterofchessstrategy.endgame.ChineseChessEndgameUiState
import com.masterofchessstrategy.endgame.EndgameLevelEntry
import com.masterofchessstrategy.engine.GameType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

class ChineseChessTutorialPracticeTest {
    private val pack = ChineseChessEndgamePackParser.parse(
        File("src/main/assets/endgames/chinese_chess/endgames-v5.txt").readText(),
    )
    private val first = pack.levels.single { it.id == "xq-easy-001" }
    private val completed = ChineseChessTutorialUiState(
        progress = TutorialProgress(GameType.CHINESE_CHESS, 1, 4, true, 10L),
        isLoading = false,
    )
    private val catalog = ChineseChessEndgameUiState(
        entries = listOf(EndgameLevelEntry(first, true, true, null)),
        isLoading = false,
    )

    @Test
    fun completedTutorialLinksTheActualIntroductoryAsset() {
        assertEquals(first, availableTutorialEndgame(completed, catalog))
        assertEquals(1, first.maxPlayerMoves)
        assertEquals("多解胜局", first.theme)
    }

    @Test
    fun unsavedOrUnfinishedTutorialCannotOpenThePractice() {
        assertNull(availableTutorialEndgame(completed.copy(isSaving = true), catalog))
        assertNull(availableTutorialEndgame(completed.copy(isLoading = true), catalog))
        assertNull(availableTutorialEndgame(
            completed.copy(progress = TutorialProgress(GameType.CHINESE_CHESS, 1, 3, false, 10L)),
            catalog,
        ))
    }

    @Test
    fun unavailableOrLockedCatalogNeverFallsBackToAnotherLevel() {
        assertNull(availableTutorialEndgame(completed, catalog.copy(isLoading = true)))
        assertNull(availableTutorialEndgame(completed, catalog.copy(entries = emptyList())))
        assertNull(availableTutorialEndgame(completed, catalog.copy(entries = listOf(
            catalog.entries.single().copy(isUnlocked = false),
        ))))
        assertNull(availableTutorialEndgame(completed, catalog.copy(entries = listOf(
            catalog.entries.single().copy(isChapterUnlocked = false),
        ))))
        assertNull(availableTutorialEndgame(completed, catalog.copy(entries = pack.levels
            .filter { it.id != first.id }.map { EndgameLevelEntry(it, true, true, null) },
        )))
    }
}
