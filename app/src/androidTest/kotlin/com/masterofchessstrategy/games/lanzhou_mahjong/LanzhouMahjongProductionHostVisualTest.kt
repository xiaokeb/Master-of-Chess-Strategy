package com.masterofchessstrategy.games.lanzhou_mahjong

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.masterofchessstrategy.engine.Difficulty
import com.masterofchessstrategy.engine.LanzhouMahjongActionKind
import com.masterofchessstrategy.games.lanzhou_mahjong.data.LanzhouMahjongNativeFixtures
import com.masterofchessstrategy.games.lanzhou_mahjong.data.LanzhouMahjongOwnedDatabaseFixture
import com.masterofchessstrategy.games.lanzhou_mahjong.data.createLanzhouMahjongHostStorage
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real VM/Route, same-DB strict production store and Native; only a UUID-owned database is touched. */
@RunWith(AndroidJUnit4::class)
class LanzhouMahjongProductionHostVisualTest {
    @get:Rule val compose = createComposeRule()

    @Test fun realTableDiscardSavesNativeOpponentHistoryAndReturnsAfterRelease() {
        val output = visualDirectory()
        withPage { fixture, storage, page ->
            compose.waitUntil(20_000) {
                page.state.value.table?.let { !it.isBusy && !it.needsSave && !it.needsAdvance } == true
            }
            val initialTable = requireNotNull(page.state.value.table)
            assertFalse(initialTable.blocked)
            val initial = io { requireNotNull(storage.store.load()) }
            val initialCount = requireNotNull(LanzhouMahjongNativeHistory.actionCount(initial.record.nativeState))
            val discard = initialTable.actions.first { it.kind == LanzhouMahjongActionKind.DISCARD }
            val handIndex = initialTable.view.seats[initialTable.viewerSeat].concealedTiles.sorted().indexOf(discard.tile)
            assertTrue(handIndex >= 0)

            val hand = compose.onNodeWithTag("lanzhou_mahjong_hand_$handIndex")
            val button = compose.onNodeWithTag("lanzhou_mahjong_discard")
            button.performScrollTo().assertIsDisplayed().assertIsNotEnabled()
            hand.performScrollTo().assertIsDisplayed().assertIsEnabled()
            button.assertIsDisplayed()
            capture(output, "table-01-initial")

            hand.performClick().assertIsSelected()
            button.performScrollTo().assertIsDisplayed().assertIsEnabled().performClick()
            compose.waitUntil(20_000) {
                page.state.value.table?.let {
                    !it.isBusy && !it.needsSave && !it.needsAdvance && !it.blocked && it.view != initialTable.view
                } == true
            }
            val saved = io { requireNotNull(storage.store.load()) }
            val savedBytes = saved.record.nativeState
            val savedCount = requireNotNull(LanzhouMahjongNativeHistory.actionCount(savedBytes))
            assertTrue("The actual discard and at least one opponent action must be saved", savedCount > initialCount + 1)
            assertTrue((initialCount until savedCount).any { index ->
                (savedBytes[127 + index * 3].toInt() and 255) != initialTable.viewerSeat
            })
            assertEquals(discard.kind.code, savedBytes[126 + initialCount * 3].toInt() and 255)
            assertEquals(initialTable.viewerSeat, savedBytes[127 + initialCount * 3].toInt() and 255)
            assertEquals(discard.tile, savedBytes[128 + initialCount * 3].toInt() and 255)
            assertEquals((initial.record.nextChoiceSeed + savedCount - initialCount - 1) and Long.MAX_VALUE,
                saved.record.nextChoiceSeed)
            val savedRow = io { requireNotNull(fixture.row()) }
            assertEquals(savedCount, savedRow.acceptedMoveCount)
            compose.onNodeWithTag("lanzhou_mahjong_hand_0").performScrollTo().assertIsDisplayed()
            // The hand and lower action area remain reachable after Native/AI changes the real table.
            val nextAction = page.state.value.table!!.actions.firstOrNull()
            val lowerTag = when {
                nextAction == null -> "lanzhou_mahjong_result"
                nextAction.kind == LanzhouMahjongActionKind.DISCARD ||
                    nextAction.kind == LanzhouMahjongActionKind.READY_DISCARD -> "lanzhou_mahjong_discard"
                else -> "lanzhou_mahjong_action_${nextAction.kind.code}_${nextAction.tile}"
            }
            compose.onNodeWithTag(lowerTag).performScrollTo().assertIsDisplayed()
            capture(output, "table-02-saved")

            compose.onNodeWithText("返回").performScrollTo().assertIsEnabled().performClick()
            awaitReturned(page)
            compose.onNodeWithTag(RETURNED_TAG).assertIsDisplayed()
            assertTrue(page.retirementCompletion.isCompleted)
            assertFalse(page.retirementCompletion.isCancelled)
            assertEquals(savedRow, io { fixture.row() })
            capture(output, "table-03-returned")
            metadata(output, "table", initialCount, savedCount, page)
        }
    }

    @Test fun corruptStoredRowShowsOnlyStableRecoveryErrorAndCanSafelyReturn() {
        val output = visualDirectory()
        withPage(prepare = { fixture ->
            val snapshot = io {
                LanzhouMahjongNativeFixtures.snapshot(discard = false, usage = 0, timestamp = 50, decisions = false)
            }
            io { fixture.store().save(snapshot) }
            val valid = io { requireNotNull(fixture.row()) }
            val bad = valid.copy(engineState = valid.engineState.copyOf().also { it[0] = 0 })
            io { fixture.db.activeGameDao().upsert(bad) }
        }) { fixture, _, page ->
            compose.waitUntil(20_000) { !page.state.value.isLoading }
            assertTrue(page.state.value.restoreFailed)
            assertNull(page.state.value.table)
            val row = io { requireNotNull(fixture.row()) }
            val message = "对局恢复失败，原存档已保留，请返回后重试"
            compose.onNodeWithText(message).assertIsDisplayed()
            compose.onNodeWithText("开启新局").assertDoesNotExist()
            compose.onNodeWithText("重开").assertDoesNotExist()
            assertTrue(page.failures.isNotEmpty())
            assertFalse(Regex("SQLite|IOException|Native|LZMJ|active_games").containsMatchIn(requireNotNull(page.state.value.error)))
            capture(output, "corrupt-01-error")

            compose.runOnUiThread { page.restart() }
            compose.waitForIdle()
            assertEquals(row, io { fixture.row() })
            compose.onNodeWithText(message).assertIsDisplayed()
            compose.onNodeWithText("返回").assertIsEnabled()
            capture(output, "corrupt-02-preserved")

            compose.onNodeWithText("返回").performClick()
            awaitReturned(page)
            compose.onNodeWithTag(RETURNED_TAG).assertIsDisplayed()
            assertTrue(page.retirementCompletion.isCompleted)
            assertFalse(page.retirementCompletion.isCancelled)
            assertEquals(row, io { fixture.row() })
            capture(output, "corrupt-03-returned")
            metadata(output, "corrupt", null, null, page)
        }
    }

    private fun withPage(
        prepare: (LanzhouMahjongOwnedDatabaseFixture) -> Unit = {},
        block: (LanzhouMahjongOwnedDatabaseFixture, LanzhouMahjongHostStorage,
            LanzhouMahjongGameViewModel) -> Unit,
    ) {
        val fixture = LanzhouMahjongOwnedDatabaseFixture()
        val models = ViewModelStore()
        var page: LanzhouMahjongGameViewModel? = null
        var returned by mutableStateOf(false)
        try {
            prepare(fixture)
            io { fixture.db.lanzhouMahjongLearningProgressDao().upsert(
                com.masterofchessstrategy.games.lanzhou_mahjong.data.LanzhouMahjongLearningProgressEntity(0, 2, 10, 1)) }
            val mode = if (io { fixture.row() } == null) LanzhouMahjongOpenMode.NEW else LanzhouMahjongOpenMode.RESUME
            val storage = fixture.db.createLanzhouMahjongHostStorage()
            compose.runOnUiThread {
                page = LanzhouMahjongGameViewModel(storage, LanzhouMahjongLaunchConfig(Difficulty.EASY, 321), openMode = mode)
                models.put("lanzhou-mahjong-visual", requireNotNull(page))
            }
            val current = requireNotNull(page)
            compose.setContent {
                MaterialTheme {
                    if (returned) {
                        Column(Modifier.fillMaxSize().testTag(RETURNED_TAG),
                            verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("已安全返回兰州麻将")
                        }
                    } else LanzhouMahjongGameRoute(current, onBack = {
                        check(current.retirementCompletion.isCompleted && !current.retirementCompletion.isCancelled)
                        returned = true
                    })
                }
            }
            block(fixture, storage, current)
        } finally {
            // Never close/delete the owned database while this VM may still use Native or Room.
            val current = page
            if (current == null) fixture.close()
            else {
                var retirement: Deferred<Unit>? = null
                compose.runOnUiThread { retirement = current.beginRetirement(); models.clear() }
                io { requireNotNull(retirement).await() }
                fixture.close()
            }
        }
    }

    private fun awaitReturned(page: LanzhouMahjongGameViewModel) = compose.waitUntil(20_000) {
        page.retirementCompletion.isCompleted &&
            compose.onAllNodesWithTag(RETURNED_TAG).fetchSemanticsNodes().isNotEmpty()
    }

    private fun visualDirectory(): File {
        val token = requireNotNull(InstrumentationRegistry.getArguments().getString("mocsVisualToken"))
        require(token.matches(Regex("qa-[a-f0-9]{32}")))
        val base = requireNotNull(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null))
        return File(base, "mocs-visual/$token").apply { check(isDirectory || mkdirs()) }
    }

    private fun capture(directory: File, name: String) {
        require(name.matches(Regex("(?:table|corrupt)-0[123]-[a-z]+")))
        compose.waitForIdle()
        val target = File(directory, "$name.png")
        check(!target.exists()) { "Visual evidence must use a fresh token" }
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        try {
            assertTrue(bitmap.width > 0 && bitmap.height > 0)
            target.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { bitmap.recycle() }
    }

    private fun metadata(directory: File, prefix: String, before: Int?, after: Int?, page: LanzhouMahjongGameViewModel) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        File(directory, "$prefix-metadata.json").writeText(JSONObject()
            .put("fontScale", context.resources.configuration.fontScale)
            .put("beforeActions", before ?: JSONObject.NULL).put("afterActions", after ?: JSONObject.NULL)
            .put("retirementAcknowledged", page.retirementCompletion.isCompleted && !page.retirementCompletion.isCancelled)
            .put("defaultDatabaseTouched", false).toString(2))
    }

    private fun <T> io(operation: suspend () -> T): T = runBlocking {
        withTimeout(20_000) { withContext(Dispatchers.Default) { operation() } }
    }

    companion object { private const val RETURNED_TAG = "lanzhou_mahjong_visual_returned" }
}
