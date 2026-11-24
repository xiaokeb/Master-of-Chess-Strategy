package com.masterofchessstrategy.ui

import android.graphics.Bitmap
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.masterofchessstrategy.engine.GoAction
import com.masterofchessstrategy.engine.GoPlayer
import com.masterofchessstrategy.engine.RestoreResult
import com.masterofchessstrategy.engine.NativeGoEngine
import com.masterofchessstrategy.games.go.GoAiMatchConfig
import com.masterofchessstrategy.games.go.GoLearningLibrary
import com.masterofchessstrategy.games.go.GoLearningProgress
import com.masterofchessstrategy.games.go.GoOpeningStudyLibrary
import com.masterofchessstrategy.games.go.data.GoLocalStorage
import com.masterofchessstrategy.games.go.data.GoStorageEpochGate
import com.masterofchessstrategy.games.go.data.RoomGoLearningProgressRepository
import com.masterofchessstrategy.settings.LocalDataWriterRegistry
import com.masterofchessstrategy.settings.awaitLocalDataWriters
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class GoOpeningStudyNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<GoLocalDataCheckActivity>()
    private val database get() = compose.activity.ownedDatabase
    private val line = checkNotNull(GoOpeningStudyLibrary.line("9-corners-center"))
    private val images by lazy {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        File(context.getExternalFilesDir(null), "go-opening-study-qa/${UUID.randomUUID()}").apply { check(mkdirs()) }
    }

    @After fun cleanup() {
        val db = database
        val name = compose.activity.ownedDatabaseName
        val legacy = compose.activity.ownedLegacyDirectory
        compose.activityRule.scenario.close()
        runBlocking {
            awaitLocalDataWriters(LocalDataWriterRegistry.forDatabase(db).snapshot()) {}
            GoStorageEpochGate.forDatabase(db).backup(false) {}
        }
        GoLocalDataCheckActivity.closeOwnedDatabase(name)
        assertTrue(InstrumentationRegistry.getInstrumentation().targetContext.deleteDatabase(name))
        assertTrue(legacy.listFiles()!!.isEmpty())
        assertTrue(legacy.delete())
    }

    @Test fun openingRemainsReadableBeforeTutorialAndCannotStartAi() {
        openStudy()
        compose.onNodeWithText("完成十课教程后开放简单难度的自动续弈。").performScrollTo().assertIsDisplayed()
        capture("catalog")
        openEndpoint()
        compose.onNodeWithTag("go_opening_start_auto").performScrollTo().assertIsNotEnabled()
        capture("locked-endpoint")
        assertNull(runBlocking { storage().aiEntryRepository().load().active })
    }

    @Test fun confirmedReplacementPreservesExactOpeningThenRealKataGoMakesAnUnrankedMove() {
        val storage = storage()
        val old = runBlocking {
            RoomGoLearningProgressRepository(database).save(GoLearningProgress(GoLearningLibrary.lessons.map { it.id }.toSet()))
            storage.aiEntryRepository().create(GoAiMatchConfig(9), expectedActive = null)
        }
        openStudy()
        openEndpoint()
        compose.onNodeWithTag("go_opening_start_auto").performScrollTo().assertIsEnabled().performClick()
        compose.onNodeWithText("取消").performClick()
        assertEquals(old.matchId, runBlocking { storage.aiEntryRepository().load().active?.matchId })
        compose.onNodeWithTag("go_opening_start_auto").performScrollTo().performClick()
        compose.onNodeWithTag("go_opening_confirm_replacement").assertIsEnabled().performClick()
        compose.waitUntil(60_000) { compose.onAllNodesWithTag("go_ai_pause").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("go_ai_pause").performScrollTo().assertIsEnabled().performClick()

        val expected = line.steps.map { GoAction.Place(it.point) }
        compose.waitUntil(90_000) {
            val active = runBlocking { storage.formalAiRepository().load() } ?: return@waitUntil false
            if (active.matchId == old.matchId) return@waitUntil false
            NativeGoEngine(9, 15, 0).use {
                it.restore(active.copyBytes())
                it.eventHistory().size > expected.size
            }
        }
        val active = checkNotNull(runBlocking { storage.formalAiRepository().load() })
        assertTrue(active.config.automatic)
        assertArrayEquals(GoOpeningStudyLibrary.open(line).opening.copyBytes(), active.copyOpeningBytes())
        val history = NativeGoEngine(9, 15, 0).use {
            it.restore(active.copyBytes())
            it.eventHistory()
        }
        assertEquals(expected, history.take(expected.size))
        assertEquals(1, runBlocking { database.goRankedDao().starts().size })
        assertTrue(runBlocking { database.matchOutcomeDao().listAll().isEmpty() })
        compose.onNodeWithText("指定开局训练局", substring = true).performScrollTo().assertIsDisplayed()
        capture("katago-training")
    }

    @Test fun switchingFromTheEndReturnsToTheSharedPositionThenPlaysTheSelectedBranch() {
        val start = "19-star-approach"
        val branch = "19-star-side-switch"
        val store = storage()
        openStudy()
        compose.onNodeWithTag("go_opening_line_$start").performScrollTo().performClick()
        awaitEvent("事件 0 / 8")
        compose.onNodeWithTag("go_opening_branch_$branch").assertDoesNotExist()
        compose.onNodeWithText("末步").performScrollTo().performClick()
        awaitEvent("事件 8 / 8")
        compose.onNodeWithTag("go_opening_branch_$branch").performScrollTo()
            .assertTextContains("第 4 手后改走", substring = true)
        capture("opening-branch-choice")
        compose.onNodeWithTag("go_opening_branch_$branch").performClick()
        awaitEvent("事件 4 / 6")
        compose.onNodeWithTag("go_intersection_3_3").assertContentDescriptionEquals("第4行第4列，黑子")
        compose.onNodeWithTag("go_intersection_15_15").assertContentDescriptionEquals("第16行第16列，白子")
        compose.onNodeWithTag("go_intersection_5_3").assertContentDescriptionEquals("第4行第6列，空点")
        capture("opening-shared-position")
        compose.activityRule.scenario.recreate()
        awaitEvent("事件 4 / 6")
        compose.onNodeWithText("下一步").performScrollTo().performClick()
        awaitEvent("事件 5 / 6")
        compose.onNodeWithTag("go_intersection_3_5").assertContentDescriptionEquals("第6行第4列，黑子，最后落点")
        compose.onNodeWithTag("go_opening_explanation").performScrollTo().assertTextContains("沿左边展开", substring = true)
        capture("opening-branch-move")
        assertNull(runBlocking { store.aiEntryRepository().load().active })
    }

    @Test fun thirteenLineBranchHandsTheWhiteTurnAndExactPrefixToRealKataGo() {
        val branch = checkNotNull(GoOpeningStudyLibrary.line("13-left-side-choice"))
        val endpoint = GoOpeningStudyLibrary.open(branch).opening
        assertEquals(GoPlayer.WHITE, endpoint.currentPlayer)
        val store = storage()
        runBlocking {
            RoomGoLearningProgressRepository(database).save(GoLearningProgress(GoLearningLibrary.lessons.map { it.id }.toSet()))
        }
        openStudy()
        compose.onNodeWithTag("go_opening_line_13-balanced-corners").performScrollTo().performClick()
        awaitEvent("事件 0 / 6")
        compose.onNodeWithText("末步").performScrollTo().performClick()
        awaitEvent("事件 6 / 6")
        compose.onNodeWithTag("go_opening_branch_${branch.id}").performScrollTo().performClick()
        awaitEvent("事件 4 / 5")
        compose.onNodeWithTag("go_opening_start_auto").performScrollTo().assertIsNotEnabled()
        capture("13-shared-position")
        compose.onNodeWithText("下一步").performScrollTo().performClick()
        awaitEvent("事件 5 / 5")
        compose.onNodeWithTag("go_intersection_3_6").assertContentDescriptionEquals("第7行第4列，黑子，最后落点")
        compose.onNodeWithTag("go_opening_explanation").performScrollTo().assertTextContains("轮到白", substring = true)
        capture("13-branch-endpoint")
        compose.onNodeWithTag("go_opening_start_auto").performScrollTo().assertIsEnabled().performClick()
        compose.waitUntil(60_000) { compose.onAllNodesWithTag("go_ai_pause").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("go_ai_pause").performScrollTo().assertIsEnabled().performClick()
        compose.waitUntil(90_000) {
            val saved = runBlocking { store.formalAiRepository().load() } ?: return@waitUntil false
            NativeGoEngine(13, 15, 0).use {
                it.restore(saved.copyBytes()) == RestoreResult.Restored && it.eventHistory().size > branch.steps.size
            }
        }
        val saved = checkNotNull(runBlocking { store.formalAiRepository().load() })
        assertTrue(saved.config.automatic)
        assertArrayEquals(endpoint.copyBytes(), saved.copyOpeningBytes())
        NativeGoEngine(13, 15, 0).use {
            assertEquals(RestoreResult.Restored, it.restore(saved.copyBytes()))
            assertEquals(branch.steps.map { step -> GoAction.Place(step.point) }, it.eventHistory().take(5))
        }
        assertTrue(runBlocking { database.goRankedDao().starts().isEmpty() })
        assertTrue(runBlocking { database.matchOutcomeDao().listAll().isEmpty() })
        capture("13-branch-katago")
    }

    private fun awaitEvent(label: String) = compose.waitUntil(15_000) {
        compose.onAllNodesWithText(label, substring = true).fetchSemanticsNodes().isNotEmpty()
    }

    private fun storage(): GoLocalStorage {
        val activity = compose.activity
        val directory = activity.ownedLegacyDirectory
        val isolated = object : ContextWrapper(activity.applicationContext) {
            override fun getApplicationContext(): Context = this
            override fun getDataDir(): File = directory
            override fun getFileStreamPath(name: String): File {
                require(name.matches(Regex("[a-z0-9-]+\\.bin")))
                return File(directory, name)
            }
        }
        return GoLocalStorage(database, isolated)
    }

    private fun openStudy() {
        compose.onNodeWithTag(HOME_GO_TAG).performScrollTo().performClick()
        compose.onNodeWithTag("go_navigation_opening_study").performScrollTo().performClick()
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("go_opening_line_${line.id}").fetchSemanticsNodes().isNotEmpty() }
    }

    private fun openEndpoint() {
        compose.onNodeWithTag("go_opening_line_${line.id}").performScrollTo().performClick()
        compose.waitUntil(15_000) { compose.onAllNodesWithText("末步").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("go_opening_start_auto").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("末步").performScrollTo().performClick()
        compose.waitUntil(15_000) {
            compose.onAllNodesWithText("事件 6 / 6", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File(images, "$name.png").outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
    }
}
