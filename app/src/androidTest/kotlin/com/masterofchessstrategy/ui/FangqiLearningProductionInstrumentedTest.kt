package com.masterofchessstrategy.ui

import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.masterofchessstrategy.data.ActiveGameEntity
import com.masterofchessstrategy.data.createProductionGameBackupRepository
import com.masterofchessstrategy.endgame.ChineseChessEndgamePackParser
import com.masterofchessstrategy.engine.NativeChineseChessEngine
import com.masterofchessstrategy.engine.RestoreResult
import com.masterofchessstrategy.games.go.data.GoLocalStorage
import com.masterofchessstrategy.games.lanzhou_fangqi.FangqiLearningViewModel
import com.masterofchessstrategy.games.lanzhou_fangqi.LanzhouFangqiLearningContent
import com.masterofchessstrategy.games.lanzhou_fangqi.data.FangqiLearningProgressEntity
import com.masterofchessstrategy.games.lanzhou_fangqi.data.FangqiLocalStorage
import com.masterofchessstrategy.settings.LocalDataWriterRegistry
import com.masterofchessstrategy.settings.LocalDataWriterRetirement
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Production navigation and real Room/Native; every test owns its database and screenshot UUID. */
class FangqiLearningProductionInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<GoLocalDataCheckActivity>()
    private val pictureRun = UUID.randomUUID().toString()

    @Test fun sixExplicitLessonAcknowledgementsPersistAcrossRecreationAndReentry() = withApp { f ->
        enterLearning()
        assertTrue(f.rows().isEmpty())
        capture("lessons-uncompleted")
        for (lesson in LanzhouFangqiLearningContent.lessons) {
            compose.onNodeWithTag("fangqi_learning_ack_${lesson.id}").performScrollTo().performClick()
            compose.waitUntil(15_000) {
                f.rows().any { it.contentKey == "lesson:${lesson.id}" } &&
                    lesson.id in f.currentOwner().state.value.progress!!.completedLessons
            }
            compose.onNodeWithTag("fangqi_learning_ack_${lesson.id}").assertIsNotEnabled()
        }
        val saved = f.rows()
        assertEquals(6, saved.size)
        val owner = f.currentOwner()
        capture("six-lessons-saved")
        compose.activityRule.scenario.recreate()
        awaitLearning()
        assertSame(owner, f.currentOwner())
        assertEquals(saved, f.rows())
        capture("same-learning-owner-after-recreation")
        compose.onNodeWithTag("fangqi_learning_back").performScrollTo().performClick()
        awaitMenu()
        runBlocking { withTimeout(15_000) { owner.retirementCompletion.await() } }
        compose.onNodeWithTag(LANZHOU_FANGQI_LEARNING_TAG).performScrollTo().performClick()
        awaitLearning()
        assertNotSame(owner, f.currentOwner())
        assertEquals(saved, f.rows())
        compose.onNodeWithTag("fangqi_learning_ack_opening").performScrollTo().assertIsNotEnabled()
        capture("lessons-reentered-from-disk")
    }

    @Test fun allThreePracticesUseActualTouchAndRetainNativeThroughRecreation() = withApp { f ->
        enterLearning()
        val owner = f.currentOwner()
        for ((index, practice) in LanzhouFangqiLearningContent.practices.withIndex()) {
            openPractice(practice.id)
            val initial = owner.state.value.session!!.serialize()
            if (index == 0) {
                point(1, 2); point(1, 1)
                compose.waitUntil(15_000) { owner.state.value.session!!.snapshot().pendingCaptures == 1 }
                val pending = owner.state.value.session!!.serialize()
                capture("practice-one-pending-capture")
                compose.activityRule.scenario.recreate()
                awaitBoard()
                assertSame(owner, f.currentOwner())
                assertArrayEquals(pending, owner.state.value.session!!.serialize())
                assertTrue(f.rows().none { it.contentKey == "practice:${practice.id}" })
                capture("practice-one-recreated")
                compose.onNodeWithText("重开").performScrollTo().performClick()
                compose.waitUntil(15_000) { owner.state.value.session!!.serialize().contentEquals(initial) }
                capture("practice-one-original-seed-restarted")
            }
            val bounds = compose.onNodeWithTag("lanzhou_fangqi_board").fetchSemanticsNode().boundsInRoot
            when (index) {
                0 -> { point(1, 2); point(1, 1); point(5, 5) }
                1 -> { point(2, 3); point(2, 2); point(6, 6); point(5, 5) }
                2 -> { point(0, 2); point(0, 1) }
            }
            compose.waitUntil(15_000) { f.rows().any { it.contentKey == "practice:${practice.id}" } }
            compose.waitUntil(15_000) { compose.onAllNodesWithTag("fangqi_learning_practice_saved").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("fangqi_learning_practice_saved").assertIsDisplayed()
            assertEquals("Completion must keep the board stable", bounds,
                compose.onNodeWithTag("lanzhou_fangqi_board").fetchSemanticsNode().boundsInRoot)
            capture("practice-${index + 1}-won-and-saved")
            compose.onNodeWithText("返回").performScrollTo().performClick()
            awaitLearning()
            assertSame(owner, f.currentOwner())
        }
        assertEquals(3, f.rows().size)
        assertTrue(f.currentOwner().state.value.progress!!.completedLessons.isEmpty())
    }

    @Test fun realPracticeSaveFailureFreezesSameHistoryAndExplicitRetryCommitsOnce() = withApp { f ->
        enterLearning()
        val id = LanzhouFangqiLearningContent.practices.first().id
        f.db.openHelper.writableDatabase.execSQL("CREATE TRIGGER fangqi_learning_ui_fault BEFORE INSERT ON " +
            "fangqi_learning_progress BEGIN SELECT RAISE(ABORT, 'owned learning UI fault'); END")
        try {
            openPractice(id)
            point(1, 2); point(1, 1); point(5, 5)
            compose.waitUntil(15_000) {
                compose.onAllNodesWithText("保存失败，请重试后继续").fetchSemanticsNodes().isNotEmpty()
            }
            val owner = f.currentOwner()
            val history = owner.state.value.session!!.serialize()
            assertTrue(f.rows().isEmpty())
            compose.onNodeWithText("学习进度保存失败，请重试。当前进度尚未确认").assertIsDisplayed()
            compose.onNodeWithTag("fangqi_learning_practice_saved").assertDoesNotExist()
            compose.onNodeWithText("重开").assertIsNotEnabled()
            capture("practice-save-failed-no-completion")
            f.db.openHelper.writableDatabase.execSQL("DROP TRIGGER fangqi_learning_ui_fault")
            compose.onNodeWithText("重试保存").performScrollTo().performClick()
            compose.waitUntil(15_000) { f.rows().size == 1 }
            compose.waitUntil(15_000) { compose.onAllNodesWithTag("fangqi_learning_practice_saved").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("fangqi_learning_practice_saved").assertIsDisplayed()
            assertArrayEquals(history, owner.state.value.session!!.serialize())
            capture("same-practice-history-retried-and-saved")
        } finally { f.db.openHelper.writableDatabase.execSQL("DROP TRIGGER IF EXISTS fangqi_learning_ui_fault") }
    }

    @Test fun recreationDuringRealLessonTransactionWaitReattachesExitAndReturnsOnce() = withApp { f ->
        enterLearning()
        val owner = f.currentOwner()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val blocker = scope.launch { f.db.withTransaction { entered.complete(Unit); release.await() } }
        try {
            runBlocking { withTimeout(15_000) { entered.await() } }
            compose.onNodeWithTag("fangqi_learning_ack_opening").performScrollTo().performClick()
            compose.waitUntil(15_000) { owner.state.value.saving }
            compose.onNodeWithTag("fangqi_learning_back").performScrollTo().performClick()
            compose.waitUntil(15_000) { owner.state.value.leaving }
            capture("lesson-exit-awaiting-real-transaction")
            compose.activityRule.scenario.recreate()
            compose.waitUntil(15_000) { compose.onAllNodesWithTag("fangqi_learning_leaving").fetchSemanticsNodes().isNotEmpty() }
            assertSame(owner, f.currentOwner())
            assertFalse(owner.retirementCompletion.isCompleted)
            capture("lesson-exit-intent-recreated")
            release.complete(Unit)
            runBlocking { withTimeout(15_000) { blocker.join() } }
            awaitMenu()
            runBlocking { withTimeout(15_000) { owner.retirementCompletion.await() } }
            assertEquals("lesson:opening", f.rows().single().contentKey)
            compose.onNodeWithTag(HOME_LANZHOU_TRADITIONAL_TAG).assertDoesNotExist()
        } finally {
            release.complete(Unit)
            runBlocking { withTimeout(15_000) { blocker.join() } }
            scope.cancel()
        }
    }

    @Test fun incompatibleLearningRowsShowRetryWithoutInventingEmptyProgress() = withApp { f ->
        runBlocking { f.db.fangqiLearningProgressDao().insert(FangqiLearningProgressEntity("lesson:opening", 99, 17)) }
        compose.onNodeWithTag(HOME_LANZHOU_TRADITIONAL_TAG).performScrollTo().performClick()
        compose.onNodeWithTag(LANZHOU_FANGQI_LEARNING_TAG).performScrollTo().performClick()
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("fangqi_learning_error").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("fangqi_learning_ready").assertDoesNotExist()
        compose.onNodeWithText("学习进度读取失败，请重试。原记录已保留").assertIsDisplayed()
        assertEquals(99, f.rows().single().contentVersion)
        compose.onNodeWithText("重试读取").performClick()
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("fangqi_learning_error").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(99, f.rows().single().contentVersion)
        capture("incompatible-learning-row-preserved")
    }

    @Test fun restoredEpochRetiresPracticeAndNextOwnerCanOpenAndMove() = withApp { f ->
        enterLearning()
        val owner = f.currentOwner()
        val repository = f.repository()
        val backup = runBlocking { repository.export(811) }
        openPractice(LanzhouFangqiLearningContent.practices.first().id)
        val oldSession = owner.state.value.session!!
        point(1, 2); point(1, 1)
        runBlocking { repository.restore(backup); withTimeout(15_000) { owner.retirementCompletion.await() } }
        awaitLearning()
        assertNotSame(owner, f.currentOwner())
        assertTrue(runCatching { oldSession.snapshot() }.isFailure)
        assertTrue(f.rows().isEmpty())
        capture("restored-learning-new-owner")
        openPractice(LanzhouFangqiLearningContent.practices.first().id)
        point(1, 2); point(1, 1)
        assertEquals(1, f.currentOwner().state.value.session!!.snapshot().pendingCaptures)
        capture("restored-owner-practice-continues")
    }

    @Test fun practiceNativeLeaseNeverOverwritesExistingOrdinaryLocalMatch() = withApp { f ->
        compose.onNodeWithTag(HOME_LANZHOU_TRADITIONAL_TAG).performScrollTo().performClick()
        compose.onNodeWithTag(LANZHOU_FANGQI_LOCAL_TAG).performScrollTo().performClick()
        awaitBoard()
        point(0, 5); point(0, 4)
        compose.waitUntil(15_000) { f.activeRow()?.acceptedMoveCount == 1 }
        val saved = f.activeRow()!!
        compose.onNodeWithText("返回").performScrollTo().performClick()
        awaitMenu()
        compose.onNodeWithTag(LANZHOU_FANGQI_LEARNING_TAG).performScrollTo().performClick()
        awaitLearning()
        openPractice(LanzhouFangqiLearningContent.practices.first().id)
        point(1, 2); point(1, 1)
        compose.onNodeWithText("返回").performScrollTo().performClick()
        awaitLearning()
        compose.onNodeWithTag("fangqi_learning_back").performScrollTo().performClick()
        awaitMenu()
        assertEquals(saved, f.activeRow())
        compose.onNodeWithTag(LANZHOU_FANGQI_LOCAL_TAG).performScrollTo().performClick()
        awaitBoard()
        assertEquals(saved, f.activeRow())
        capture("ordinary-match-preserved-after-learning")
    }

    private fun enterLearning() {
        compose.onNodeWithTag(HOME_LANZHOU_TRADITIONAL_TAG).performScrollTo().performClick()
        compose.onNodeWithTag(LANZHOU_FANGQI_LEARNING_TAG).performScrollTo().performClick()
        awaitLearning()
    }
    private fun awaitLearning() = compose.waitUntil(15_000) {
        compose.onAllNodesWithTag("fangqi_learning_ready").fetchSemanticsNodes().isNotEmpty()
    }
    private fun awaitMenu() = compose.waitUntil(15_000) {
        compose.onAllNodesWithTag(LANZHOU_FANGQI_LEARNING_TAG).fetchSemanticsNodes().isNotEmpty()
    }
    private fun openPractice(id: String) {
        compose.onNodeWithTag("fangqi_learning_practice_$id").performScrollTo().performClick()
        awaitBoard()
    }
    private fun awaitBoard() = compose.waitUntil(15_000) {
        compose.onAllNodesWithTag("lanzhou_fangqi_board").fetchSemanticsNodes().isNotEmpty()
    }
    private fun point(x: Int, y: Int) = compose.onNodeWithTag("lanzhou_fangqi_point_${x}_$y").assertIsDisplayed().performClick()
    private fun capture(label: String) {
        compose.waitForIdle()
        val directory = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null),
            "fangqi-learning-production-qa/$pictureRun").apply { check(isDirectory || mkdirs()) }
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        try {
            assertTrue(bitmap.width > bitmap.height)
            File(directory, "$label.png").outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { bitmap.recycle() }
    }

    private inner class Fixture {
        val db = compose.activity.ownedDatabase
        private val writers = LocalDataWriterRegistry.forDatabase(db)
        fun rows() = runBlocking { db.fangqiLearningProgressDao().readAll() }
        fun activeRow(): ActiveGameEntity? = runBlocking { db.activeGameDao().find(4) }
        fun currentOwner() = writers.snapshot().filterIsInstance<FangqiLearningViewModel>()
            .single { !it.state.value.retired && !it.retirementCompletion.isCompleted }
        fun repository() = createProductionGameBackupRepository(db,
            ChineseChessEndgamePackParser.loadBundled(compose.activity.assets),
            GoLocalStorage(db, object : android.content.ContextWrapper(compose.activity.applicationContext) {
                override fun getApplicationContext(): android.content.Context = this
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
            runBlocking { withTimeout(30_000) { receipts.awaitAll(); jobs.joinAll() } }
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
}
