package com.masterofchessstrategy.games.competition_mahjong.learning

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Production screen/controller with an injected store; these tests do not claim real Room coverage. */
@RunWith(AndroidJUnit4::class)
class CompetitionMahjongLearningScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun unreadStepAndFutureChaptersAreDisabled() = withLearning { learning, _, _, _ ->
        compose.onNodeWithTag("competition_mahjong_learning_next").assertIsNotEnabled()
        compose.onNodeWithTag("competition_mahjong_learning_chapter_1").assertIsNotEnabled()
        compose.onNodeWithTag("competition_mahjong_learning_read").performScrollTo().performClick()
        compose.waitUntil(5_000) { learning.state.value.solved }
        compose.onNodeWithTag("competition_mahjong_learning_next").performScrollTo().performClick()
        compose.waitUntil(5_000) { learning.state.value.cardIndex == 1 }
        compose.onNodeWithTag("competition_mahjong_learning_next").assertIsNotEnabled()
    }

    @Test fun tilePracticeRequiresTheTwoMatchingCopiesBeforeAdvancing() = withLearning { learning, _, _, _ ->
        solveAndAdvance(learning)
        clickTile(learning, 0)
        clickTile(learning, 1)
        compose.onNodeWithTag("competition_mahjong_learning_submit").performScrollTo().performClick()
        compose.waitUntil(5_000) { learning.state.value.feedback != null }
        assertFalse(learning.state.value.solved)
        compose.onNodeWithTag("competition_mahjong_learning_next").assertIsNotEnabled()
        clickTile(learning, 1)
        clickTile(learning, 2)
        compose.onNodeWithTag("competition_mahjong_learning_submit").performScrollTo().performClick()
        compose.waitUntil(5_000) { learning.state.value.solved }
        compose.onNodeWithTag("competition_mahjong_learning_next").performScrollTo().performClick()
        compose.waitUntil(5_000) { learning.state.value.cardIndex == 2 }
    }

    @Test fun failedSaveKeepsCompletionHiddenAndBackOffersExplicitChoices() = withLearning(3) { learning, store, back, completed ->
        store.failSave = true
        finishCurrentChapter(learning)
        compose.waitUntil(5_000) { learning.state.value.needsSave && !learning.state.value.busy }
        compose.onNodeWithTag("competition_mahjong_learning_complete").assertDoesNotExist()
        compose.onNodeWithText("返回", useUnmergedTree = true).performClick()
        compose.onNodeWithText("本章进度尚未保存").assertExists()
        assertEquals(0, back[0])
        assertEquals(0, completed[0])
        compose.onNodeWithText("留在教程").performClick()
        store.failSave = false
        compose.onNodeWithTag("competition_mahjong_learning_retry").performScrollTo().performClick()
        compose.waitUntil(5_000) { learning.state.value.tutorialCompleted }
        compose.onNodeWithTag("competition_mahjong_learning_complete").assertExists()
        assertTrue(store.saved!!.isCompleted)
    }

    @Test fun completionCallbackIsAvailableOnlyAfterTheFinalChapterIsCommitted() = withLearning(3, largeText = true) { learning, store, _, completed ->
        compose.onNodeWithTag("competition_mahjong_learning_complete").assertDoesNotExist()
        finishCurrentChapter(learning)
        compose.waitUntil(5_000) { learning.state.value.tutorialCompleted }
        assertEquals(4, store.saved!!.completedChapterCount)
        assertEquals(0, completed[0])
        compose.waitUntil(5_000) {
            val chip = compose.onNodeWithTag("competition_mahjong_learning_chapter_3").getUnclippedBoundsInRoot()
            val row = compose.onNodeWithTag("competition_mahjong_learning_chapters").getUnclippedBoundsInRoot()
            chip.left >= row.left && chip.right <= row.right
        }
        compose.onNodeWithTag("competition_mahjong_learning_complete").performClick()
        assertEquals(1, completed[0])
    }

    @Test fun restoredAndChangedChapterIsVisibleAt150PercentInANarrowViewport() =
        withLearning(4, largeText = true) { learning, store, _, _ ->
            assertEquals(3, learning.state.value.chapterIndex)
            compose.onNodeWithTag("competition_mahjong_learning_chapter_3").assertIsDisplayed()
            runBlocking { learning.selectChapter(0) }
            compose.onNodeWithTag("competition_mahjong_learning_chapter_0").assertIsDisplayed()
            runBlocking { learning.selectChapter(3) }
            compose.onNodeWithTag("competition_mahjong_learning_chapter_3").assertIsDisplayed()
            assertEquals(4, store.saved!!.completedChapterCount)
        }

    private fun withLearning(count: Int = 0, largeText: Boolean = false, block: (CompetitionMahjongLearningController,
        MemoryStore, IntArray, IntArray) -> Unit) {
        val store = MemoryStore(proof(count))
        val learning = CompetitionMahjongLearningController(store, Dispatchers.IO)
        runBlocking { learning.load() }
        val back = intArrayOf(0)
        val completed = intArrayOf(0)
        compose.setContent {
            val state by learning.state.collectAsState()
            val scope = rememberCoroutineScope()
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides if (largeText) Density(density.density, 1.5f) else density) {
              MaterialTheme {
                CompetitionMahjongLearningScreen(state, { back[0]++ }, { completed[0]++ },
                    { scope.launch { learning.selectChapter(it) } },
                    { scope.launch { learning.acknowledgeReading() } },
                    { scope.launch { learning.selectOption(it) } },
                    { scope.launch { learning.toggleTile(it) } },
                    { scope.launch { learning.submit() } },
                    { scope.launch { learning.advance() } },
                    { scope.launch { learning.load() } },
                    { scope.launch { learning.retrySave() } },
                    modifier = if (largeText) Modifier.width(360.dp) else Modifier)
              }
            }
        }
        try { block(learning, store, back, completed) } finally {
            runBlocking { learning.beginRetirement().await() }
            compose.runOnUiThread { compose.activity.setContent { } }
        }
    }

    private fun finishCurrentChapter(learning: CompetitionMahjongLearningController) {
        val chapter = learning.state.value.chapterIndex
        val count = learning.state.value.chapter.cards.size
        repeat(count) { solveAndAdvance(learning) }
        compose.waitUntil(5_000) {
            val state = learning.state.value
            !state.busy && (state.needsSave || state.chapterIndex != chapter || state.tutorialCompleted)
        }
    }

    private fun solveAndAdvance(learning: CompetitionMahjongLearningController) {
        val before = learning.state.value
        when (val task = before.card.task) {
            MahjongLessonTask.Reading ->
                compose.onNodeWithTag("competition_mahjong_learning_read").performScrollTo().performClick()
            is MahjongLessonTask.Choice -> {
                compose.onNodeWithTag("competition_mahjong_learning_option_${task.correctIndex}")
                    .performScrollTo().performClick()
                compose.waitUntil(5_000) { learning.state.value.selectedOption == task.correctIndex }
                compose.onNodeWithTag("competition_mahjong_learning_submit").performScrollTo().performClick()
            }
            is MahjongLessonTask.Tiles -> {
                task.acceptedSelections.first().forEach { clickTile(learning, it) }
                compose.onNodeWithTag("competition_mahjong_learning_submit").performScrollTo().performClick()
            }
        }
        compose.waitUntil(5_000) { learning.state.value.solved && !learning.state.value.busy }
        compose.onNodeWithTag("competition_mahjong_learning_next").performScrollTo().performClick()
        compose.waitUntil(5_000) {
            val state = learning.state.value
            state.card.id != before.card.id || state.needsSave || state.tutorialCompleted
        }
    }

    private fun clickTile(learning: CompetitionMahjongLearningController, index: Int) {
        val previouslySelected = index in learning.state.value.selectedTiles
        compose.onNodeWithTag("competition_mahjong_learning_tile_$index").performScrollTo().performClick()
        compose.waitUntil(5_000) { (index in learning.state.value.selectedTiles) != previouslySelected }
    }

    private class MemoryStore(@Volatile var saved: CompetitionMahjongLearningProof?) : CompetitionMahjongLearningProofStore {
        @Volatile var failSave = false
        override suspend fun load() = CompetitionMahjongLearningProofRead.Ready(saved ?: CompetitionMahjongLearningProof())
        override suspend fun confirm(request: CompetitionMahjongLearningProofRequest): CompetitionMahjongLearningProof {
            if (failSave) throw IOException("injected test save failure")
            return (saved ?: CompetitionMahjongLearningProof()).confirm(request).also { saved = it }
        }
    }

    private fun proof(count: Int): CompetitionMahjongLearningProof =
        (0 until count).fold(CompetitionMahjongLearningProof()) { saved, index ->
            val chapter = CompetitionMahjongLearningLibrary.chapters[index]
            saved.confirm(CompetitionMahjongLearningProofRequest(
                "00000000-0000-0000-0000-${(index + 1).toString().padStart(12, '0')}",
                chapter.id, chapter.cards.map { it.id }, index.toLong()))
        }
}
