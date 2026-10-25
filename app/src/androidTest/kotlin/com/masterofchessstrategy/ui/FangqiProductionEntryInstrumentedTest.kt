package com.masterofchessstrategy.ui

import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
import androidx.test.platform.app.InstrumentationRegistry
import com.masterofchessstrategy.data.ActiveGameEntity
import com.masterofchessstrategy.engine.games.lanzhou_fangqi.FangqiAction
import com.masterofchessstrategy.engine.games.lanzhou_fangqi.FangqiPoint
import com.masterofchessstrategy.engine.games.lanzhou_fangqi.FangqiSide
import com.masterofchessstrategy.engine.games.lanzhou_fangqi.NativeLanzhouFangqiSession
import com.masterofchessstrategy.games.lanzhou_fangqi.FangqiGameViewModel
import com.masterofchessstrategy.games.lanzhou_fangqi.FangqiStoredMode
import com.masterofchessstrategy.games.lanzhou_fangqi.FangqiStoredSession
import com.masterofchessstrategy.games.lanzhou_fangqi.data.RoomFangqiSessionStore
import com.masterofchessstrategy.settings.LocalDataWriterRegistry
import com.masterofchessstrategy.settings.LocalDataWriterRetirement
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Real production navigation, Native rules and an Activity-owned isolated Room database. */
class FangqiProductionEntryInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<GoLocalDataCheckActivity>()

    @Test fun localMoveResumeAndRecreationKeepDurableHistoryAndRetainedOwner() = withOwnedApp { f ->
        enterGame()
        val initial = checkNotNull(f.row())
        assertEquals(0, initial.modeCode)
        capture("opening")
        point(0, 5); point(0, 4)
        awaitActions(f, 1)
        val saved = checkNotNull(f.row())
        assertEquals(initial.sessionId, saved.sessionId)
        val owner = f.owners().single()
        capture("first-move-saved")
        compose.activityRule.scenario.recreate()
        awaitBoard()
        assertSame(owner, f.owners().single())
        assertFalse(owner.retirementCompletion.isCompleted)
        assertEquals(saved, f.row())
        capture("same-owner-after-recreation")
        compose.onNodeWithText("返回").performScrollTo().performClick()
        compose.waitUntil(15_000) { compose.onAllNodesWithTag(LANZHOU_FANGQI_LOCAL_TAG).fetchSemanticsNodes().isNotEmpty() }
        runBlocking { withTimeout(15_000) { owner.retirementCompletion.await() } }
        compose.onNodeWithTag(LANZHOU_FANGQI_LOCAL_TAG).performScrollTo().performClick()
        awaitBoard()
        assertNotSame(owner, f.owners().single())
        assertEquals(saved, f.row())
        point(0, 1); point(0, 2)
        awaitActions(f, 2)
        assertEquals(saved.sessionId, f.row()!!.sessionId)
        capture("resumed-second-move")
    }

    @Test fun partialCaptureSurvivesRecreationAndUndoRestoresTheWholeTurn() = withOwnedApp { f ->
        val board = MutableList(49) { 0 }
        listOf(0, 2, 14, 8, 9, 15, 23).forEach { board[it] = 1 }
        listOf(40, 41, 48).forEach { board[it] = 2 }
        val bytes = NativeLanzhouFangqiSession.fromPosition(board, FangqiSide.RED).use { game ->
            assertTrue(game.apply(FangqiAction.Move(FangqiPoint(2, 3), FangqiPoint(2, 2))).accepted)
            assertEquals(2, game.snapshot().pendingCaptures)
            game.serialize()
        }
        runBlocking { RoomFangqiSessionStore(f.db).save(FangqiStoredSession(
            FangqiStoredMode.LOCAL_TWO_PLAYER, null, bytes, UUID.randomUUID().toString(), 123L, 1)) }
        enterGame()
        capture("two-captures-due")
        val boardBounds = compose.onNodeWithTag("lanzhou_fangqi_board").fetchSemanticsNode().boundsInRoot
        point(6, 6)
        awaitActions(f, 2)
        val partial = f.row()!!
        NativeLanzhouFangqiSession().use { game ->
            assertTrue(game.restore(partial.engineState))
            assertEquals(1, game.snapshot().pendingCaptures)
            assertEquals(0, game.snapshot().board[48])
        }
        val owner = f.owners().single()
        compose.activityRule.scenario.recreate()
        awaitBoard()
        assertSame(owner, f.owners().single())
        assertEquals(partial, f.row())
        capture("partial-capture-restored")
        point(5, 5)
        awaitActions(f, 3)
        assertEquals("Changing turn instructions must not move or resize the board", boardBounds,
            compose.onNodeWithTag("lanzhou_fangqi_board").fetchSemanticsNode().boundsInRoot)
        compose.onNodeWithText("悔棋").performScrollTo().assertIsEnabled().performClick()
        awaitActions(f, 0)
        NativeLanzhouFangqiSession().use { game ->
            assertTrue(game.restore(f.row()!!.engineState))
            assertEquals(board, game.snapshot().board)
            assertEquals(FangqiSide.RED, game.snapshot().currentPlayer)
            assertEquals(0, game.snapshot().pendingCaptures)
        }
        assertEquals(1, f.row()!!.undoUseCount)
        capture("whole-turn-undo-saved")
    }

    @Test fun recreationWhileExitWaitsForARealTransactionReattachesAndReturnsOnce() = withOwnedApp { f ->
        enterGame()
        val owner = f.owners().single()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val blocker = scope.launch { f.db.withTransaction { entered.complete(Unit); release.await() } }
        try {
            runBlocking { withTimeout(15_000) { entered.await() } }
            point(0, 5); point(0, 4)
            compose.waitUntil(15_000) { compose.onAllNodesWithText("正在保存对局").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("返回").performScrollTo().performClick()
            compose.waitUntil(15_000) { owner.state.value.leaving }
            capture("exit-awaits-real-room-transaction")
            compose.activityRule.scenario.recreate()
            compose.waitUntil(15_000) { compose.onAllNodesWithTag("fangqi_owner_loading").fetchSemanticsNodes().isNotEmpty() }
            assertSame(owner, f.owners().single())
            assertFalse(owner.retirementCompletion.isCompleted)
            capture("exit-intent-after-recreation")
            release.complete(Unit)
            runBlocking { withTimeout(15_000) { blocker.join() } }
            compose.waitUntil(15_000) { compose.onAllNodesWithTag(LANZHOU_FANGQI_LOCAL_TAG).fetchSemanticsNodes().isNotEmpty() }
            runBlocking { withTimeout(15_000) { owner.retirementCompletion.await() } }
            assertEquals("1", f.row()!!.engineState.toString(Charsets.US_ASCII).split('|', limit = 6)[4])
            compose.onNodeWithTag(HOME_LANZHOU_TRADITIONAL_TAG).assertDoesNotExist()
        } finally {
            release.complete(Unit)
            runBlocking { withTimeout(15_000) { blocker.join() } }
            scope.cancel()
        }
    }

    @Test fun corruptExistingHistoryShowsRecoveryErrorWithoutWritingANewGame() = withOwnedApp { f ->
        val broken = ActiveGameEntity(4, 0, null, 5, 1, "broken-history".toByteArray(),
            123L, UUID.randomUUID().toString())
        runBlocking { f.db.activeGameDao().upsert(broken) }
        compose.onNodeWithTag(HOME_LANZHOU_TRADITIONAL_TAG).performScrollTo().performClick()
        compose.onNodeWithTag(LANZHOU_FANGQI_LOCAL_TAG).performScrollTo().performClick()
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("fangqi_owner_error").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("lanzhou_fangqi_board").assertDoesNotExist()
        assertEquals(broken, f.row())
        capture("corrupt-history-preserved")
    }

    private fun enterGame() {
        compose.onNodeWithTag(HOME_LANZHOU_TRADITIONAL_TAG).performScrollTo().performClick()
        compose.onNodeWithTag(LANZHOU_FANGQI_LOCAL_TAG).performScrollTo().performClick()
        awaitBoard()
    }
    private fun awaitBoard() = compose.waitUntil(15_000) {
        compose.onAllNodesWithTag("lanzhou_fangqi_board").fetchSemanticsNodes().isNotEmpty() &&
            compose.onAllNodesWithTag("fangqi_owner_loading").fetchSemanticsNodes().isEmpty()
    }
    private fun point(x: Int, y: Int) = compose.onNodeWithTag("lanzhou_fangqi_point_${x}_$y")
        .assertIsDisplayed().performClick()
    private fun awaitActions(f: Fixture, count: Int) = compose.waitUntil(15_000) {
        f.row()?.engineState?.toString(Charsets.US_ASCII)?.split('|', limit = 6)?.getOrNull(4) == count.toString() &&
            compose.onAllNodesWithText("正在保存对局").fetchSemanticsNodes().isEmpty()
    }
    private fun capture(label: String) {
        compose.waitForIdle()
        val directory = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null),
            "fangqi-production-qa/$pictureRun").apply { check(isDirectory || mkdirs()) }
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        try {
            assertTrue(bitmap.width > bitmap.height)
            File(directory, "$label.png").outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { bitmap.recycle() }
    }
    private val pictureRun = UUID.randomUUID().toString()

    private inner class Fixture {
        val db = compose.activity.ownedDatabase
        private val writers = LocalDataWriterRegistry.forDatabase(db)
        fun row(): ActiveGameEntity? = runBlocking { db.activeGameDao().find(4) }
        fun owners() = writers.snapshot().filterIsInstance<FangqiGameViewModel>()
        fun close() {
            var receipts = emptyList<Deferred<Unit>>()
            var jobs = emptyList<Job>()
            val name = compose.activity.ownedDatabaseName
            val legacy = compose.activity.ownedLegacyDirectory
            compose.activityRule.scenario.onActivity { activity ->
                activity.setContent {}
                val owners = writers.snapshot()
                jobs = owners.mapNotNull { it.viewModelScope.coroutineContext[Job] }
                receipts = owners.filterIsInstance<LocalDataWriterRetirement>().map { it.beginRetirement() }
                activity.viewModelStore.clear()
            }
            runBlocking { withTimeout(30_000) { receipts.awaitAll(); jobs.joinAll() } }
            assertTrue(writers.snapshot().isEmpty())
            compose.activityRule.scenario.close()
            GoLocalDataCheckActivity.closeOwnedDatabase(name)
            assertTrue(InstrumentationRegistry.getInstrumentation().targetContext.deleteDatabase(name))
            assertTrue(legacy.listFiles()!!.isEmpty())
            assertTrue(legacy.delete())
        }
    }
    private fun withOwnedApp(block: (Fixture) -> Unit) {
        val fixture = Fixture()
        try { block(fixture) } finally { fixture.close() }
    }
}
