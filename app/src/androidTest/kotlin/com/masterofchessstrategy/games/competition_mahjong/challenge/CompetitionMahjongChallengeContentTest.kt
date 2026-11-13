package com.masterofchessstrategy.games.competition_mahjong.challenge

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class CompetitionMahjongChallengeContentTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private var scope: CoroutineScope? = null

    @After fun closeScope() {
        scope?.cancel()
        scope = null
    }

    @Test fun wrongThenCorrectAnswerPersistsAndUnlocksNextAt150Percent() {
        val repository = UiRepository()
        val game = createGame(repository)
        setContent(game, 1.5f)
        awaitReady(game)

        compose.onNodeWithText("国标麻将 · 牌型训练").assertIsDisplayed()
        compose.onNodeWithTag("competition_mahjong_challenge_prompt").assertTextContains("基本番为7番")
        compose.onNodeWithTag("competition_mahjong_challenge_choice_a").performScrollTo().performClick()
        compose.waitUntil(5_000) { game.state.value.exercise?.attempts == 1 }
        compose.onNodeWithTag("competition_mahjong_challenge_feedback").assertTextContains("不符合题面条件")

        compose.onNodeWithTag("competition_mahjong_challenge_choice_b").performScrollTo().performClick()
        compose.waitUntil(5_000) { game.state.value.completedIds.size == 1 && !game.state.value.loading }
        compose.onNodeWithTag("competition_mahjong_challenge_completed").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("competition_mahjong_challenge_next").performScrollTo().assertIsEnabled()
        assertEquals(1, repository.completeCalls)
    }

    @Test fun catalogShowsLockedMasterContentAndDailySelectionStaysUnlocked() {
        val repository = UiRepository()
        val game = createGame(repository)
        setContent(game)
        awaitReady(game)

        compose.onNodeWithTag("competition_mahjong_challenge_catalog").performClick()
        compose.onNodeWithTag("competition_mahjong_challenge_catalog_dialog").assertIsDisplayed()
        compose.onNodeWithTag("competition_mahjong_challenge_level_MASTER").performScrollTo().performClick()
        compose.onNodeWithTag("competition_mahjong_challenge_filtered_random").assertIsNotEnabled()
        compose.onNodeWithTag("competition_mahjong_challenge_item_four-wind-pungs")
            .performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("关闭").performClick()

        compose.onNodeWithTag("competition_mahjong_challenge_daily").performClick()
        compose.waitUntil(5_000) { game.state.value.mode == CompetitionMahjongChallengeMode.DAILY }
        assertEquals("seven-plus-flowers", game.state.value.exercise!!.challenge.id)
        compose.onNodeWithTag("competition_mahjong_challenge_mode").assertTextContains("2026-10-15")
    }

    private fun createGame(repository: UiRepository): CompetitionMahjongChallengeViewModel {
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        scope = owner
        return CompetitionMahjongChallengeViewModel(repository, owner, today = { LocalDate.of(2026, 10, 15) })
    }

    private fun setContent(game: CompetitionMahjongChallengeViewModel, fontScale: Float = 1f) {
        compose.setContent {
            val density = LocalDensity.current
            val state by game.state.collectAsState()
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                MaterialTheme {
                    CompetitionMahjongChallengeScreen(state, { game.answer(it) }, { game.select(it) },
                        { game.select(it, CompetitionMahjongChallengeMode.THEME) }, { game.openDaily() },
                        { theme, level -> game.openRandom(theme, level) }, { game.reset() }, { game.retry() }, {})
                }
            }
        }
    }

    private fun awaitReady(game: CompetitionMahjongChallengeViewModel) {
        compose.waitUntil(5_000) { game.state.value.canOperate }
    }

    private class UiRepository : CompetitionMahjongChallengeProgressRepository {
        var progress = CompetitionMahjongChallengeProgress()
        var completeCalls = 0
        override suspend fun load() = progress
        override suspend fun select(id: String, mode: CompetitionMahjongChallengeMode) =
            progress.select(id, mode).also { progress = it }
        override suspend fun selectDaily(date: LocalDate, id: String) =
            progress.selectDaily(date, id).also { progress = it }
        override suspend fun complete(id: String) = progress.complete(id).also {
            completeCalls++
            progress = it
        }
    }
}
