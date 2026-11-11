package com.masterofchessstrategy.games.competition_mahjong.auto.replay

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.masterofchessstrategy.games.competition_mahjong.auto.CompetitionMahjongAutoArchive
import com.masterofchessstrategy.games.competition_mahjong.auto.captureCompetitionMahjongAuto
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CompetitionMahjongAutoReplayContentTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private var scope: CoroutineScope? = null
    private var game: CompetitionMahjongAutoReplayViewModel? = null

    @After fun closeReplay() {
        scope?.cancel()
        scope = null
        game = null
    }

    @Test fun privateExportRequiresExplicitWarningAndCancelPreservesArchiveAt150Percent() {
        val archive = CompetitionMahjongAutoReplayInstrumentedTest.nativeArchive()
        val library = UiLibrary(archive)
        val active = createGame(library)
        var exported: CompetitionMahjongAutoExport? = null
        setContent(active, fontScale = 1.5f) { value -> exported = value; true }
        awaitEntries(active)

        compose.onNodeWithText("国标麻将 · 自动赛事棋谱").assertIsDisplayed()
        compose.onNodeWithTag("competition_mahjong_auto_archive_export_${archive.key}")
            .performScrollTo().performClick()
        compose.onNodeWithText("导出完整私有棋谱？").assertIsDisplayed()
        compose.onNodeWithText("各席暗牌和牌墙", substring = true).assertIsDisplayed()
        captureCompetitionMahjongAuto("export-warning")
        compose.onNodeWithTag("competition_mahjong_auto_export_cancel").performClick()
        compose.onNodeWithTag("competition_mahjong_auto_export_dialog").assertDoesNotExist()
        assertNull(exported)
        assertFalse(library.deleted)

        compose.onNodeWithTag("competition_mahjong_auto_archive_export_${archive.key}")
            .performScrollTo().performClick()
        compose.onNodeWithTag("competition_mahjong_auto_export_confirm").performClick()
        compose.waitUntil(10_000) { exported != null && active.state.value.notice == "棋谱已导出" }
        val output = requireNotNull(exported)
        assertEquals(CompetitionMahjongAutoExport.MIME_TYPE, output.mimeType)
        assertNotNull(CompetitionMahjongAutoExportCodec.decode(output.bytes, VIEWER))
        assertFalse(library.deleted)
    }

    @Test fun realNativeReplayExposesOnlyObservationControlsAndSelectsAnyHand() {
        val archive = CompetitionMahjongAutoReplayInstrumentedTest.nativeArchive()
        val library = UiLibrary(archive)
        val active = createGame(library)
        setContent(active) { false }
        awaitEntries(active)

        compose.onNodeWithTag("competition_mahjong_auto_archive_replay_${archive.key}")
            .performScrollTo().performClick()
        compose.waitUntil(30_000) {
            active.state.value.selectedKey == archive.key && active.state.value.table != null &&
                !active.state.value.busy
        }
        compose.onNodeWithTag("competition_mahjong_auto_replay_position")
            .assertTextContains("第1 / 16盘", substring = true)
        compose.onNodeWithTag("competition_mahjong_auto_replay_next").performScrollTo().assertIsEnabled().performClick()
        compose.waitUntil(10_000) { active.state.value.replay.eventIndex == 1 }
        compose.onNodeWithTag("competition_mahjong_auto_replay_previous").performScrollTo().assertIsEnabled().performClick()
        compose.waitUntil(10_000) { active.state.value.replay.eventIndex == 0 }
        compose.onNodeWithTag("competition_mahjong_auto_replay_hand_15")
            .performScrollTo().performClick()
        compose.waitUntil(10_000) {
            active.state.value.replay.handIndex == 15 && active.state.value.replay.eventIndex == 0
        }
        compose.onNodeWithTag("competition_mahjong_auto_replay_speed").performScrollTo().assertIsDisplayed()
        captureCompetitionMahjongAuto("replay")
        compose.onAllNodesWithText("摸牌").assertCountEquals(0)
        compose.onAllNodesWithText("出牌").assertCountEquals(0)
    }

    private fun createGame(library: UiLibrary): CompetitionMahjongAutoReplayViewModel {
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        scope = owner
        return CompetitionMahjongAutoReplayViewModel(library, VIEWER, parentScope = owner)
            .also { game = it }
    }

    private fun setContent(active: CompetitionMahjongAutoReplayViewModel, fontScale: Float = 1f,
                           onExport: suspend (CompetitionMahjongAutoExport) -> Boolean) {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                MaterialTheme {
                    CompetitionMahjongAutoReplayContent(active, null, onBack = {}, onExport = onExport,
                        modifier = Modifier.safeDrawingPadding())
                }
            }
        }
    }

    private fun awaitEntries(active: CompetitionMahjongAutoReplayViewModel) {
        compose.waitUntil(10_000) { active.state.value.entries.size == 1 && !active.state.value.busy }
    }

    private class UiLibrary(private val archive: CompetitionMahjongAutoArchive) :
        CompetitionMahjongAutoArchiveLibrary {
        @Volatile var favorite = false
        @Volatile var deleted = false
        override suspend fun list(viewerPlayerId: Int): List<CompetitionMahjongAutoArchiveSummary> {
            require(viewerPlayerId == VIEWER)
            return if (deleted) emptyList() else listOf(archive.summary(1_760_000_000_000, favorite))
        }
        override suspend fun load(key: String, viewerPlayerId: Int): CompetitionMahjongAutoArchive? {
            require(viewerPlayerId == VIEWER)
            return archive.takeIf { !deleted && it.key == key }
        }
        override suspend fun setFavorite(key: String, viewerPlayerId: Int, favorite: Boolean) {
            require(viewerPlayerId == VIEWER && key == archive.key && !deleted)
            this.favorite = favorite
        }
        override suspend fun delete(key: String, viewerPlayerId: Int) {
            require(viewerPlayerId == VIEWER && key == archive.key && !deleted)
            deleted = true
        }
    }

    companion object { private const val VIEWER = 2 }
}
