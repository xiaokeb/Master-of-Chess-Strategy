package com.masterofchessstrategy.ui

import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.viewModelScope
import androidx.test.platform.app.InstrumentationRegistry
import com.masterofchessstrategy.R
import com.masterofchessstrategy.data.MatchOutcomeEntity
import com.masterofchessstrategy.games.lanzhou_fangqi.*
import com.masterofchessstrategy.games.lanzhou_fangqi.data.*
import com.masterofchessstrategy.settings.LocalDataWriterRegistry
import com.masterofchessstrategy.settings.LocalDataWriterRetirement
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Actual NavHost/Native final capture followed by Home/Profile read, never default app data. */
class FangqiGrowthProductionInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<GoLocalDataCheckActivity>()
    private val pictureRun = UUID.randomUUID().toString()

    @Test fun actualFirstTerminalRefreshesHomeAndProfileWithoutCountingOldUnqualifiedWins() = withApp {
        val db = compose.activity.ownedDatabase
        val initial = runBlocking { FangqiRankedRoomFixtures.start(db, "growth-production").second }
        val terminal = FangqiRankedRoomFixtures.terminal(initial)
        val parts = terminal.engineState.toString(Charsets.US_ASCII).split('|', limit = 6)
        val beforeCapture = (parts.take(4).joinToString("|") + "|49|" + parts[5].split(';').take(49).joinToString(";"))
            .toByteArray(Charsets.US_ASCII)
        runBlocking {
            repeat(20) { db.matchOutcomeDao().insert(MatchOutcomeEntity("old-ui-$it", 4, 1, 0, 0, 1, 1)) }
            RoomFangqiRankedRepository(db).checkpoint(initial,
                FangqiStoredSession(initial.mode, initial.difficulty, beforeCapture, initial.sessionId, 19, 49))
        }
        enterEntry()
        compose.onNodeWithTag("fangqi_ai_resume").performScrollTo().performClick()
        compose.waitUntil(15_000) { currentGame()?.state?.value?.interaction != null }
        compose.onNodeWithTag("lanzhou_fangqi_point_5_0").assertIsDisplayed().performClick()
        compose.waitUntil(15_000) {
            runBlocking { db.fangqiRankedDao().findSettlement(initial.sessionId) } != null &&
                currentGame()?.state?.value?.retrySave == false
        }
        compose.onNodeWithTag("lanzhou_fangqi_back").performScrollTo().performClick()
        awaitEntry()
        returnHome()
        awaitText(compose.activity.getString(R.string.profile_wins, 1))
        compose.onNodeWithText(compose.activity.getString(R.string.profile_score, 10)).assertExists()
        capture("home-trusted-one-win")
        compose.onNodeWithTag(HOME_PROFILE_TAG).performScrollTo().performClick()
        compose.onNodeWithTag(PROFILE_SCREEN_TAG).assertExists()
        compose.onNodeWithText(compose.activity.getString(R.string.profile_wins, 1)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(compose.activity.getString(R.string.profile_total_rewards, 1, 10)).assertExists()
        capture("profile-trusted-one-win")
        assertEquals(21, runBlocking { db.matchOutcomeDao().listAll().size })
        assertEquals(1, runBlocking { db.fangqiRankedDao().readSettlements().size })
        compose.activityRule.scenario.recreate()
        awaitText(compose.activity.getString(R.string.profile_wins, 1))
        assertEquals(1, runBlocking { db.fangqiRankedDao().readSettlements().size })
        capture("profile-after-recreation-no-double-growth")
    }

    @Test fun legacyOnlyWinsStayStoredAndShowNoFormalGrowthAfterEntryReturns() = withApp {
        val db = compose.activity.ownedDatabase
        runBlocking { repeat(20) { db.matchOutcomeDao().insert(MatchOutcomeEntity("legacy-ui-$it", 4, 1, 0, 0, 1, 1)) } }
        enterEntry()
        returnHome()
        awaitText(compose.activity.getString(R.string.profile_wins, 0))
        compose.onNodeWithText(compose.activity.getString(R.string.profile_score, 0)).assertExists()
        assertEquals(20, runBlocking { db.matchOutcomeDao().listAll().size })
        assertTrue(runBlocking { db.fangqiRankedDao().readStarts().isEmpty() })
        capture("home-legacy-retained-no-growth")
    }

    private fun enterEntry() {
        compose.onNodeWithTag(HOME_LANZHOU_TRADITIONAL_TAG).performScrollTo().performClick()
        compose.onNodeWithTag(LANZHOU_FANGQI_AI_TAG).performScrollTo().performClick()
        awaitEntry()
    }
    private fun awaitEntry() = compose.waitUntil(15_000) {
        compose.onAllNodesWithTag("fangqi_ai_difficulty_0").fetchSemanticsNodes().isNotEmpty() &&
            compose.onAllNodesWithTag("fangqi_ai_loading").fetchSemanticsNodes().isEmpty()
    }
    private fun returnHome() {
        compose.onNodeWithTag("fangqi_ai_back").performScrollTo().performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.back)).performScrollTo().performClick()
        compose.onNodeWithTag(HOME_PROFILE_TAG).assertExists()
    }
    private fun awaitText(text: String) = compose.waitUntil(15_000) {
        compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
    }
    private fun currentGame() = LocalDataWriterRegistry.forDatabase(compose.activity.ownedDatabase).snapshot()
        .filterIsInstance<FangqiGameViewModel>().singleOrNull { !it.state.value.retired && !it.retirementCompletion.isCompleted }
    private fun capture(label: String) {
        val directory = File(compose.activity.getExternalFilesDir(null), "fangqi-growth-production-qa/$pictureRun")
            .apply { check(isDirectory || mkdirs()) }
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        try { File(directory, "$label.png").outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
        finally { bitmap.recycle() }
    }
    private fun withApp(block: () -> Unit) {
        val activity = compose.activity
        val name = activity.ownedDatabaseName; val legacy = activity.ownedLegacyDirectory
        val writers = LocalDataWriterRegistry.forDatabase(activity.ownedDatabase)
        try { block() } finally {
            var receipts = emptyList<Deferred<Unit>>(); var jobs = emptyList<Job>()
            compose.activityRule.scenario.onActivity { current ->
                current.setContent { }
                val owners = writers.snapshot()
                jobs = owners.mapNotNull { it.viewModelScope.coroutineContext[Job] }
                receipts = owners.filterIsInstance<LocalDataWriterRetirement>().map { it.beginRetirement() }
                current.viewModelStore.clear()
            }
            runBlocking { withTimeout(30_000) { receipts.awaitAll(); jobs.joinAll() } }
            compose.activityRule.scenario.close()
            GoLocalDataCheckActivity.closeOwnedDatabase(name)
            assertTrue(InstrumentationRegistry.getInstrumentation().targetContext.deleteDatabase(name))
            assertTrue(legacy.listFiles()!!.isEmpty()); assertTrue(legacy.delete())
        }
    }
}
