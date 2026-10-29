package com.masterofchessstrategy.ui

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.lifecycle.viewModelScope
import androidx.test.platform.app.InstrumentationRegistry
import com.masterofchessstrategy.data.createProductionGameBackupRepository
import com.masterofchessstrategy.endgame.ChineseChessEndgamePackParser
import com.masterofchessstrategy.engine.LanzhouMahjongActionKind
import com.masterofchessstrategy.engine.NativeChineseChessEngine
import com.masterofchessstrategy.engine.RestoreResult
import com.masterofchessstrategy.games.go.data.GoLocalStorage
import com.masterofchessstrategy.games.lanzhou_fangqi.data.FangqiLocalStorage
import com.masterofchessstrategy.games.lanzhou_mahjong.*
import com.masterofchessstrategy.games.lanzhou_mahjong.data.LanzhouMahjongLearningProgressEntity
import com.masterofchessstrategy.settings.LocalDataWriterRegistry
import com.masterofchessstrategy.settings.LocalDataWriterRetirement
import java.io.File
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Actual app NavHost, Room and Native. The debug activity owns a UUID DB and isolated legacy paths. */
class LanzhouMahjongProductionEntryInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<GoLocalDataCheckActivity>()

    @Test fun homeTenLessonsUnlockEasyAndActualGameCanSaveReturnResumeAndRecreate() = withApp { f ->
        enterMenu()
        compose.onNodeWithTag("lanzhou_mahjong_entry_new_easy").assertDoesNotExist()
        compose.onNodeWithTag("lanzhou_mahjong_entry_progress").assertTextEquals("已掌握 0/10 课")
        capture("flow-01-locked")
        compose.onNodeWithTag("lanzhou_mahjong_entry_learning").performScrollTo().performClick()
        compose.waitUntil(20_000) { f.learningOwnerOrNull()?.state?.value?.loaded == true }
        for ((index, lesson) in LanzhouMahjongLearningLibrary.lessons.withIndex()) {
            val owner = requireNotNull(f.learningOwnerOrNull())
            compose.waitUntil(20_000) { owner.state.value.lessonIndex == index && !owner.state.value.isBusy }
            compose.onNodeWithTag("lanzhou_mahjong_answer_${lesson.answerIndex}").performScrollTo().performClick()
            compose.waitUntil(20_000) { owner.state.value.let { !it.isBusy && !it.hasPendingSave && it.completedCount == index + 1 } }
            assertEquals(index + 1, io { f.db.lanzhouMahjongLearningProgressDao().readBoundedRows().single().completedCount })
            if (index != 9) compose.onNodeWithText("下一课").performScrollTo().performClick()
        }
        compose.onNodeWithTag("lanzhou_mahjong_learning_complete").performScrollTo().assertIsDisplayed()
        capture("flow-02-complete")
        val learning = requireNotNull(f.learningOwnerOrNull())
        compose.onNodeWithText("返回").performScrollTo().performClick()
        awaitMenu()
        io { learning.retirementCompletion.await() }
        compose.onNodeWithTag("lanzhou_mahjong_entry_progress").assertTextEquals("已掌握 10/10 课")
        capture("flow-03-unlocked")
        compose.onNodeWithTag("lanzhou_mahjong_entry_new_easy").performScrollTo().performClick()
        awaitTable(f)
        capture("flow-04-initial")
        val game = f.gameOwner()
        val table = requireNotNull(game.state.value.table)
        val discard = table.actions.first { it.kind == LanzhouMahjongActionKind.DISCARD }
        val index = table.view.seats[table.viewerSeat].concealedTiles.sorted().indexOf(discard.tile)
        assertTrue(index >= 0)
        val before = io { requireNotNull(f.db.activeGameDao().find(3)) }
        // Reach the outer page's action area before scrolling the nested horizontal hand row.
        compose.onNodeWithTag("lanzhou_mahjong_discard").performScrollTo().assertIsDisplayed().assertIsNotEnabled()
        compose.onNodeWithTag("lanzhou_mahjong_hand_$index").performScrollTo().assertIsDisplayed().assertIsEnabled().performClick().assertIsSelected()
        compose.onNodeWithTag("lanzhou_mahjong_discard").performScrollTo().assertIsDisplayed().assertIsEnabled().performClick()
        try {
            compose.waitUntil(20_000) { game.state.value.table?.let { !it.isBusy && !it.needsSave && it.view != table.view } == true }
        } catch (failure: Exception) {
            throw AssertionError("QA discard did not finish: state=${game.state.value}; " +
                "beforeActions=${before.acceptedMoveCount}; afterActions=${io { f.db.activeGameDao().find(3)?.acceptedMoveCount }}; " +
                "failures=${game.failures.map { it.toString() }}", failure)
        }
        val saved = io { requireNotNull(f.db.activeGameDao().find(3)) }
        assertTrue(saved.acceptedMoveCount > before.acceptedMoveCount)
        capture("flow-05-saved")
        compose.onNodeWithText("返回").performScrollTo().performClick()
        awaitMenu()
        io { game.retirementCompletion.await() }
        assertEquals(saved, io { f.db.activeGameDao().find(3) })
        compose.onNodeWithTag("lanzhou_mahjong_entry_new_easy").assertDoesNotExist()
        compose.onNodeWithTag("lanzhou_mahjong_entry_resume").performScrollTo().assertTextEquals("继续简单对局")
        capture("flow-06-resume")
        compose.onNodeWithTag("lanzhou_mahjong_entry_resume").performClick()
        awaitTable(f)
        val resumed = f.gameOwner()
        assertNotSame(game, resumed)
        compose.activityRule.scenario.recreate()
        awaitTable(f)
        assertSame(resumed, f.gameOwner())
        assertEquals(saved, io { f.db.activeGameDao().find(3) })
        capture("flow-07-recreated")
        compose.onNodeWithText("返回").performScrollTo().performClick()
        awaitMenu()
        capture("flow-08-returned")
        metadata("flow", mapOf("lessonsAnswered" to 10, "nativeDiscardSaved" to true, "sameVmAfterActivityRecreate" to true))
    }

    @Test fun invalidStoredLearningShowsRetryAndPreservesTheExactRow() = withApp { f ->
        val bad = LanzhouMahjongLearningProgressEntity(0, 99, 10, 17)
        io { f.db.lanzhouMahjongLearningProgressDao().upsert(bad) }
        enterMenu(expectError = true)
        compose.onNodeWithTag("lanzhou_mahjong_entry_new_easy").assertDoesNotExist()
        compose.onNodeWithTag("lanzhou_mahjong_entry_resume").assertDoesNotExist()
        compose.onNodeWithTag("lanzhou_mahjong_entry_error").assertTextEquals("菜单进度读取失败，请重试。原记录已保留")
        capture("invalid-01-error")
        compose.onNodeWithTag("lanzhou_mahjong_entry_refresh").performScrollTo().performClick()
        compose.waitUntil(20_000) { compose.onAllNodesWithTag("lanzhou_mahjong_entry_error").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(listOf(bad), io { f.db.lanzhouMahjongLearningProgressDao().readBoundedRows() })
        compose.onNodeWithTag("lanzhou_mahjong_entry_back").performScrollTo().performClick()
        compose.onNodeWithTag(HOME_SETTINGS_TAG).assertExists()
        capture("invalid-02-home")
        metadata("invalid", mapOf("invalidRowPreserved" to true))
    }

    @Test fun productionBackupRestoreReplacesMenuOwnerAndNewGameUsesTheNewEpoch() = withApp { f ->
        io { f.db.lanzhouMahjongLearningProgressDao().upsert(LanzhouMahjongLearningProgressEntity(0, 2, 10, 11)) }
        enterMenu()
        val old = f.entryOwner()
        val backup = f.repository()
        val bytes = io { backup.export(900) }
        capture("restore-01-before")
        io { backup.restore(bytes); old.retirementCompletion.await() }
        compose.waitUntil(20_000) { f.entryOwnerOrNull()?.let { it !== old && it.state.value.facts?.tutorialCompleted == true } == true }
        assertNotSame(old, f.entryOwner())
        capture("restore-02-menu")
        compose.onNodeWithTag("lanzhou_mahjong_entry_new_easy").performScrollTo().performClick()
        awaitTable(f)
        capture("restore-03-game")
        metadata("restore", mapOf("oldMenuRetired" to true, "newEpochGameCreated" to true))
    }

    private fun enterMenu(expectError: Boolean = false) {
        compose.onNodeWithText("兰州麻将").performScrollTo().assertIsEnabled().performClick()
        if (expectError) compose.waitUntil(20_000) { compose.onAllNodesWithTag("lanzhou_mahjong_entry_error").fetchSemanticsNodes().isNotEmpty() }
        else awaitMenu()
    }
    private fun awaitMenu() = compose.waitUntil(20_000) {
        compose.onAllNodesWithTag("lanzhou_mahjong_entry_progress").fetchSemanticsNodes().isNotEmpty()
    }
    private fun awaitTable(f: Fixture) = compose.waitUntil(20_000) {
        f.gameOwnerOrNull()?.state?.value?.table?.let { !it.isBusy && !it.needsSave && !it.needsAdvance && !it.blocked } == true
    }

    private fun directory(): File {
        val token = requireNotNull(InstrumentationRegistry.getArguments().getString("mocsVisualToken"))
        require(token.matches(Regex("qa-[a-f0-9]{32}")))
        return File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null),
            "mocs-visual/$token").apply { check(isDirectory || mkdirs()) }
    }
    private fun capture(name: String) {
        require(name.matches(Regex("(?:flow|invalid|restore)-0[1-8]-[a-z]+")))
        compose.waitForIdle()
        val file = File(directory(), "$name.png")
        check(!file.exists())
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        try { file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
        finally { bitmap.recycle() }
    }
    private fun metadata(prefix: String, details: Map<String, Any>) {
        val file = File(directory(), "$prefix-metadata.json")
        check(!file.exists())
        val value = JSONObject(details).put("fontScale", compose.activity.resources.configuration.fontScale)
            .put("defaultDatabaseTouched", false).put("actualAppNavigation", true)
        file.writeText(value.toString(2))
    }

    private inner class Fixture {
        val db = compose.activity.ownedDatabase
        val writers = LocalDataWriterRegistry.forDatabase(db)
        fun entryOwnerOrNull() = writers.snapshot().filterIsInstance<LanzhouMahjongEntryViewModel>().singleOrNull { !it.state.value.retired }
        fun entryOwner() = requireNotNull(entryOwnerOrNull())
        fun learningOwnerOrNull() = writers.snapshot().filterIsInstance<LanzhouMahjongLearningViewModel>().singleOrNull { !it.state.value.retired }
        fun gameOwnerOrNull() = writers.snapshot().filterIsInstance<LanzhouMahjongGameViewModel>().singleOrNull { !it.state.value.retired && !it.retirementCompletion.isCompleted }
        fun gameOwner() = requireNotNull(gameOwnerOrNull())
        fun repository() = createProductionGameBackupRepository(db,
            ChineseChessEndgamePackParser.loadBundled(compose.activity.assets),
            GoLocalStorage(db, object : ContextWrapper(compose.activity.applicationContext) {
                override fun getApplicationContext(): Context = this
                override fun getDataDir(): File = compose.activity.ownedLegacyDirectory
                override fun getFileStreamPath(name: String): File {
                    require(name.matches(Regex("[a-z0-9-]+\\.bin")))
                    return File(compose.activity.ownedLegacyDirectory, name)
                }
            }), FangqiLocalStorage.forDatabase(db)) { bytes ->
                NativeChineseChessEngine().use { it.restore(bytes) == RestoreResult.Restored }
            }
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
            io { receipts.awaitAll(); jobs.joinAll() }
            assertTrue(writers.snapshot().isEmpty())
            compose.activityRule.scenario.close()
            GoLocalDataCheckActivity.closeOwnedDatabase(name)
            assertTrue(InstrumentationRegistry.getInstrumentation().targetContext.deleteDatabase(name))
            assertTrue(legacy.listFiles()!!.isEmpty())
            assertTrue(legacy.delete())
        }
    }
    private fun withApp(block: (Fixture) -> Unit) {
        val fixture = Fixture()
        try { block(fixture) } finally { fixture.close() }
    }
    private fun <T> io(operation: suspend () -> T): T = runBlocking {
        withTimeout(30_000) { withContext(Dispatchers.IO) { operation() } }
    }
}
