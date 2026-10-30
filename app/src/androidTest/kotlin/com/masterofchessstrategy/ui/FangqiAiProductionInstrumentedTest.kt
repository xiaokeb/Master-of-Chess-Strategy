package com.masterofchessstrategy.ui

import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
import androidx.test.platform.app.InstrumentationRegistry
import com.masterofchessstrategy.data.*
import com.masterofchessstrategy.engine.*
import com.masterofchessstrategy.engine.games.lanzhou_fangqi.FangqiSide
import com.masterofchessstrategy.endgame.ChineseChessEndgamePackParser
import com.masterofchessstrategy.games.go.data.GoLocalStorage
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

/** Actual production NavHost, gestures, Native and Room; only the activity's UUID test DB is changed. */
class FangqiAiProductionInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<GoLocalDataCheckActivity>()
    private val pictureRun = UUID.randomUUID().toString()

    @Test fun realSixLessonGateCreatesFormalEasyAndKeepsActualAiTurnAcrossRecreationAndReentry() = withApp { f ->
        enterEntry()
        compose.onNodeWithTag("fangqi_ai_difficulty_0").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("fangqi_ai_create").performScrollTo().assertIsNotEnabled()
        assertTrue(runBlocking { f.db.fangqiRankedDao().readStarts().isEmpty() })
        capture("tutorial-locked")
        compose.onNodeWithTag("fangqi_ai_learning").performScrollTo().performClick()
        for (lesson in LanzhouFangqiLearningContent.lessons) {
            compose.onNodeWithTag("fangqi_learning_ack_${lesson.id}").performScrollTo().performClick()
            compose.waitUntil(15_000) { runBlocking { f.db.fangqiLearningProgressDao().readAll() }.any {
                it.contentKey == "lesson:${lesson.id}"
            } }
        }
        compose.onNodeWithTag("fangqi_learning_back").performScrollTo().performClick()
        compose.waitUntil(15_000) { f.entry().state.value.facts?.eligibility?.condition(Difficulty.EASY)?.isUnlocked == true }
        compose.onNodeWithTag("fangqi_ai_difficulty_1").performScrollTo().assertIsNotEnabled()
        chooseEasyAndCreate()
        awaitGame(f)
        val first = f.game()
        val initial = f.active()!!
        assertEquals(1, runBlocking { f.db.fangqiRankedDao().readStarts().size })
        assertEquals(1, initial.modeCode)
        compose.onNodeWithTag("lanzhou_fangqi_restart").performScrollTo().assertIsNotEnabled()
        point(0, 5); point(0, 4)
        awaitHumanTurn(f)
        val saved = f.active()!!
        assertEquals(initial.sessionId, saved.sessionId)
        assertTrue(saved.acceptedMoveCount >= 2)
        capture("formal-real-ai-turn-saved")
        compose.activityRule.scenario.recreate()
        awaitGame(f)
        assertSame(first, f.game())
        assertEquals(saved, f.active())
        capture("formal-owner-after-recreation")
        backFromGame()
        awaitEntry()
        compose.onNodeWithTag("fangqi_ai_create").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("fangqi_ai_resume").performScrollTo().performClick()
        awaitGame(f)
        assertNotSame(first, f.game())
        assertEquals(saved, f.active())
        capture("formal-reopened-from-room")
    }

    @Test fun realSourceLadderUnlocksAllTiersAndMasterUsesNoUndo() = withApp { f ->
        // Bounded QA: 45 distinct real creation/terminal receipts reuse the legal red-win history.
        // This proves qualification wiring, not human participation or AI strength.
        runBlocking {
            for (key in FangqiLearningCatalog.keys) f.db.fangqiLearningProgressDao()
                .insert(FangqiLearningProgressEntity(key, 1, 1))
            val repository = RoomFangqiRankedRepository(f.db)
            var previous: FangqiStoredSession? = null
            for ((difficulty, count) in listOf(Difficulty.EASY to 10, Difficulty.MEDIUM to 15, Difficulty.HARD to 20)) {
                repeat(count) { index ->
                    val initial = repository.create(FangqiRankedRoomFixtures.creation("qa-${difficulty.code}-$index", difficulty), previous)
                    val terminal = FangqiRankedRoomFixtures.terminal(initial)
                    repository.checkpoint(initial, terminal)
                    repository.firstTerminal(terminal, 30)
                    previous = terminal
                }
            }
        }
        enterEntry()
        for (difficulty in Difficulty.entries) compose.onNodeWithTag("fangqi_ai_difficulty_${difficulty.code}")
            .performScrollTo().assertIsEnabled()
        compose.onNodeWithTag("fangqi_ai_difficulty_3").performScrollTo().performClick()
        capture("all-tiers-unlocked-by-real-source-receipts")
        compose.onNodeWithTag("fangqi_ai_create").performScrollTo().assertIsEnabled().performClick()
        awaitGame(f)
        assertEquals(Difficulty.MASTER, f.game().state.value.difficulty)
        point(0, 5); point(0, 4)
        awaitHumanTurn(f)
        compose.onNodeWithTag("lanzhou_fangqi_undo").performScrollTo().assertIsNotEnabled()
        assertEquals(0, f.active()!!.undoUseCount)
        assertEquals(46, runBlocking { f.db.fangqiRankedDao().readStarts().size })
        assertEquals(45, runBlocking { f.db.fangqiRankedDao().readSettlements().size })
        capture("master-real-ai-turn-no-undo")
    }

    @Test fun recreationAndRefreshDuringFailedRealCreationKeepTheSameRetryRequestUsable() = withApp { f ->
        runBlocking {
            for (key in FangqiLearningCatalog.keys) f.db.fangqiLearningProgressDao()
                .insert(FangqiLearningProgressEntity(key, 1, 1))
        }
        enterEntry()
        val originalOwner = f.entry()
        f.db.openHelper.writableDatabase.execSQL("CREATE TRIGGER qa_fq_entry_create BEFORE INSERT ON fangqi_ranked_starts BEGIN SELECT RAISE(ABORT, 'owned entry UI fault'); END")
        val release = CompletableDeferred<Unit>(); val entered = CompletableDeferred<Unit>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val blocker = scope.launch { f.db.withTransaction { entered.complete(Unit); release.await() } }
        try {
            runBlocking { withTimeout(15_000) { entered.await() } }
            chooseEasyAndCreate()
            compose.waitUntil(15_000) { originalOwner.state.value.creating }
            compose.activityRule.scenario.recreate()
            assertSame(originalOwner, f.entry())
            compose.runOnIdle { originalOwner.reload() }
        } finally {
            release.complete(Unit)
            runBlocking { withTimeout(15_000) { blocker.join() } }
            scope.cancel()
        }
        try {
            compose.waitUntil(15_000) { originalOwner.state.value.let { !it.loading && !it.creating && it.pendingCreation && it.facts != null } }
            val failedAt = System.currentTimeMillis()
            assertTrue(runBlocking { f.db.fangqiRankedDao().readStarts().isEmpty() })
            compose.onNodeWithTag("fangqi_ai_retry_create").performScrollTo().assertIsEnabled()
            capture("creation-failed-after-recreation-can-retry")
            f.db.openHelper.writableDatabase.execSQL("DROP TRIGGER qa_fq_entry_create")
            compose.onNodeWithTag("fangqi_ai_retry_create").performScrollTo().performClick()
            awaitGame(f)
            val start = runBlocking { f.db.fangqiRankedDao().readStarts().single() }
            assertEquals(start.sessionId, f.active()!!.sessionId)
            assertTrue("Creation retains its first request time", start.startedAtEpochMillis <= failedAt)
            assertEquals(0, f.active()!!.acceptedMoveCount)
            capture("same-creation-request-confirmed-after-retry")
        } finally { f.db.openHelper.writableDatabase.execSQL("DROP TRIGGER IF EXISTS qa_fq_entry_create") }
    }

    @Test fun unfinishedOrdinaryMatchResumesWithoutFormalUpgradeOrReplacement() = withApp { f ->
        compose.onNodeWithTag(HOME_LANZHOU_TRADITIONAL_TAG).performScrollTo().performClick()
        compose.onNodeWithTag(LANZHOU_FANGQI_LOCAL_TAG).performScrollTo().performClick()
        awaitGame(f)
        point(0, 5); point(0, 4)
        compose.waitUntil(15_000) { f.active()?.acceptedMoveCount == 1 }
        val saved = f.active()!!
        backFromGame()
        compose.onNodeWithTag(LANZHOU_FANGQI_AI_TAG).performScrollTo().performClick()
        awaitEntry()
        compose.onNodeWithTag("fangqi_ai_create").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("fangqi_ai_resume").performScrollTo().performClick()
        awaitGame(f)
        assertEquals(FangqiStoredMode.LOCAL_TWO_PLAYER, f.game().state.value.mode)
        assertEquals(saved, f.active())
        assertTrue(runBlocking { f.db.fangqiRankedDao().readStarts().isEmpty() })
        capture("ordinary-resume-no-formal-upgrade")
    }

    @Test fun realFinalCaptureWaitsForFirstSettlementAndExplicitNextCreationKeepsOldProvenance() = withApp { f ->
        val initial = runBlocking { FangqiRankedRoomFixtures.start(f.db, "terminal-ui").second }
        val terminal = FangqiRankedRoomFixtures.terminal(initial)
        val parts = terminal.engineState.toString(Charsets.US_ASCII).split('|', limit = 6)
        val prefix = (parts.take(4).joinToString("|") + "|49|" + parts[5].split(';').take(49).joinToString(";"))
            .toByteArray(Charsets.US_ASCII)
        runBlocking { RoomFangqiRankedRepository(f.db).checkpoint(initial,
            FangqiStoredSession(initial.mode, initial.difficulty, prefix, initial.sessionId, 19, 49)) }
        enterEntry()
        compose.onNodeWithTag("fangqi_ai_resume").performScrollTo().performClick()
        awaitGame(f)
        assertEquals(1, f.game().state.value.interaction!!.state.value.snapshot.pendingCaptures)
        f.db.openHelper.writableDatabase.execSQL("CREATE TRIGGER qa_terminal_ui BEFORE INSERT ON fangqi_ranked_settlements BEGIN SELECT RAISE(ABORT, 'owned terminal UI fault'); END")
        try {
            point(5, 0)
            compose.waitUntil(15_000) { f.game().state.value.interaction!!.state.value.saveFailed }
            assertEquals(50, f.active()!!.acceptedMoveCount)
            assertArrayEquals(terminal.engineState, f.active()!!.engineState)
            assertNull(runBlocking { f.db.fangqiRankedDao().findSettlement(initial.sessionId) })
            assertNull(runBlocking { f.db.matchOutcomeDao().find(initial.sessionId) })
            assertNull(runBlocking { f.db.gameRecordDao().find(initial.sessionId) })
            capture("terminal-confirmation-failed")
        } finally { f.db.openHelper.writableDatabase.execSQL("DROP TRIGGER qa_terminal_ui") }
        compose.onNodeWithTag("lanzhou_fangqi_retry_save").performScrollTo().performClick()
        compose.waitUntil(15_000) { runBlocking { f.db.fangqiRankedDao().findSettlement(initial.sessionId) } != null &&
            !f.game().state.value.retrySave }
        val receipt = runBlocking { f.db.fangqiRankedDao().findSettlement(initial.sessionId) }!!
        val record = runBlocking { f.db.gameRecordDao().find(initial.sessionId) }!!
        assertArrayEquals(terminal.engineState, record.engineState)
        assertEquals(50, record.moveCount)
        compose.onNodeWithTag("lanzhou_fangqi_undo").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("lanzhou_fangqi_restart").performScrollTo().assertIsNotEnabled()
        capture("terminal-confirmed-once")
        backFromGame()
        compose.waitUntil(15_000) { f.entry().state.value.facts?.settledActive == true }
        assertEquals(1, f.entry().state.value.facts!!.eligibility.condition(Difficulty.MEDIUM).qualifyingWins)
        chooseEasyAndCreate()
        awaitGame(f)
        assertNotEquals(initial.sessionId, f.active()!!.sessionId)
        assertEquals(0, f.active()!!.acceptedMoveCount)
        assertEquals(2, runBlocking { f.db.fangqiRankedDao().readStarts().size })
        assertEquals(receipt, runBlocking { f.db.fangqiRankedDao().findSettlement(initial.sessionId) })
        assertEquals(record, runBlocking { f.db.gameRecordDao().find(initial.sessionId) })
        capture("next-formal-game-preserves-first-result")
    }

    @Test fun completeBackupReplacementRetiresFormalNativeAndFreshOwnerLoadsRestoredCheckpoint() = withApp { f ->
        runBlocking { FangqiRankedRoomFixtures.start(f.db, "restore-formal") }
        enterEntry()
        val backup = runBlocking { f.repository().export(100) }
        compose.onNodeWithTag("fangqi_ai_resume").performScrollTo().performClick()
        awaitGame(f)
        val old = f.game(); val oldNative = old.state.value.session!!
        point(0, 5); point(0, 4)
        awaitHumanTurn(f)
        assertTrue(f.active()!!.acceptedMoveCount >= 2)
        runBlocking { f.repository().restore(backup); withTimeout(15_000) { old.retirementCompletion.await() } }
        compose.waitUntil(15_000) { runCatching { f.game() !== old && !f.game().state.value.loading &&
            f.game().state.value.interaction != null }.getOrDefault(false) }
        assertTrue(runCatching { oldNative.snapshot() }.isFailure)
        assertEquals("restore-formal", f.active()!!.sessionId)
        assertEquals(0, f.active()!!.acceptedMoveCount)
        assertArrayEquals(FangqiRankedLedger.standardInitialHistory().bytes, f.active()!!.engineState)
        assertNull(runBlocking { f.db.fangqiRankedDao().findSettlement("restore-formal") })
        capture("backup-restored-fresh-formal-owner")
    }

    private fun enterEntry() {
        compose.onNodeWithTag(HOME_LANZHOU_TRADITIONAL_TAG).performScrollTo().performClick()
        compose.onNodeWithTag(LANZHOU_FANGQI_AI_TAG).performScrollTo().performClick()
        awaitEntry()
    }
    private fun awaitEntry() = compose.waitUntil(15_000) {
        compose.onAllNodesWithTag("fangqi_ai_difficulty_0").fetchSemanticsNodes().isNotEmpty() &&
            compose.onAllNodesWithTag("fangqi_ai_loading").fetchSemanticsNodes().isEmpty() &&
            compose.onAllNodesWithTag("fangqi_ai_opening").fetchSemanticsNodes().isEmpty()
    }
    private fun chooseEasyAndCreate() {
        compose.onNodeWithTag("fangqi_ai_difficulty_0").performScrollTo().assertIsEnabled().performClick()
        compose.onNodeWithTag("fangqi_ai_create").performScrollTo().assertIsEnabled().performClick()
    }
    private fun awaitGame(f: Fixture) = compose.waitUntil(15_000) {
        runCatching { !f.game().state.value.loading && f.game().state.value.interaction != null }.getOrDefault(false)
    }
    private fun awaitHumanTurn(f: Fixture) = compose.waitUntil(15_000) {
        val ui = f.game().state.value.interaction!!.state.value
        !ui.aiThinking && !ui.savePending && !ui.saveFailed && ui.snapshot.currentPlayer == FangqiSide.RED &&
            ui.snapshot.pendingCaptures == 0 && (f.active()?.acceptedMoveCount ?: 0) >= 2
    }
    private fun point(x: Int, y: Int) = compose.onNodeWithTag("lanzhou_fangqi_point_${x}_$y").assertIsDisplayed().performClick()
    private fun backFromGame() = compose.onNodeWithTag("lanzhou_fangqi_back").performScrollTo().performClick()
    private fun capture(label: String) {
        val directory = File(compose.activity.getExternalFilesDir(null), "fangqi-ai-production-qa/$pictureRun")
            .apply { check(isDirectory || mkdirs()) }
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        try {
            assertTrue(bitmap.width > bitmap.height)
            File(directory, "$label.png").outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { bitmap.recycle() }
    }

    private inner class Fixture {
        val db = compose.activity.ownedDatabase
        private val writers = LocalDataWriterRegistry.forDatabase(db)
        fun active() = runBlocking { db.activeGameDao().find(4) }
        fun game() = writers.snapshot().filterIsInstance<FangqiGameViewModel>()
            .single { !it.state.value.retired && !it.retirementCompletion.isCompleted }
        fun entry() = writers.snapshot().filterIsInstance<FangqiAiEntryViewModel>()
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
            var receipts = emptyList<Deferred<Unit>>(); var jobs = emptyList<Job>()
            val name = compose.activity.ownedDatabaseName; val legacy = compose.activity.ownedLegacyDirectory
            compose.activityRule.scenario.onActivity { activity ->
                activity.setContent { }
                val owners = writers.snapshot()
                jobs = owners.mapNotNull { it.viewModelScope.coroutineContext[Job] }
                receipts = owners.filterIsInstance<LocalDataWriterRetirement>().map { it.beginRetirement() }
                activity.viewModelStore.clear()
            }
            runBlocking { withTimeout(30_000) { receipts.awaitAll(); jobs.joinAll() } }
            compose.activityRule.scenario.close()
            GoLocalDataCheckActivity.closeOwnedDatabase(name)
            assertTrue(InstrumentationRegistry.getInstrumentation().targetContext.deleteDatabase(name))
            assertTrue(legacy.listFiles()!!.isEmpty()); assertTrue(legacy.delete())
        }
    }
    private fun withApp(block: (Fixture) -> Unit) {
        val f = Fixture()
        try { block(f) } finally { f.close() }
    }
}
