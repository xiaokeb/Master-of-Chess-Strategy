package com.masterofchessstrategy.ui

import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.DocumentsContract
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.core.app.ActivityOptionsCompat
import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
import androidx.test.platform.app.InstrumentationRegistry
import com.masterofchessstrategy.R
import com.masterofchessstrategy.data.*
import com.masterofchessstrategy.engine.*
import com.masterofchessstrategy.games.go.*
import com.masterofchessstrategy.games.go.data.*
import com.masterofchessstrategy.settings.LocalDataWriterRegistry
import com.masterofchessstrategy.settings.awaitLocalDataWriters
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.io.IOException
import java.util.UUID

/** Actual production Settings/NavHost/backup VM and system SAF, with installation data isolated. */
class GoProductionBackupFlowInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<GoLocalDataCheckActivity>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val database by lazy { compose.activity.ownedDatabase }
    private val writers by lazy { LocalDataWriterRegistry.forDatabase(database) }
    private val gate by lazy { GoStorageEpochGate.forDatabase(database) }
    private val runId = UUID.randomUUID().toString()
    private val filename = "mocs-go-settings-$runId.mocs"
    private val createdDocuments = mutableListOf<Uri>()
    private lateinit var storage: GoLocalStorage

    @Before fun installRealProductionRootWithRecordingOnlySafProxy() {
        val db = database
        val legacyDirectory = compose.activity.ownedLegacyDirectory
        val isolated = object : ContextWrapper(context.applicationContext) {
            override fun getApplicationContext(): Context = this
            override fun getDataDir(): File = legacyDirectory
            override fun getFileStreamPath(name: String): File {
                require(name.matches(Regex("[a-z0-9-]+\\.bin")))
                return File(legacyDirectory, name)
            }
        }
        storage = GoLocalStorage(db, isolated)
        val registry = RecordingRegistry(compose.activity.activityResultRegistry)
        val owner = object : ActivityResultRegistryOwner { override val activityResultRegistry = registry }
        compose.activityRule.scenario.onActivity { activity ->
            activity.setContent {
                CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                    MasterOfChessStrategyApp(db, storage)
                }
            }
        }
        awaitTag(HOME_GO_TAG)
        seedOwnedGoData()
    }

    @After fun cleanupOnlyOwnedDocumentsDatabaseAndLegacyDirectory() {
        val name = compose.activity.ownedDatabaseName
        val legacy = compose.activity.ownedLegacyDirectory
        val owners = writers.snapshot()
        val ownedGate = gate
        try {
            // Temporary URI grants still belong to this live Activity.
            for (uri in createdDocuments.distinct()) {
                assertTrue(DocumentsContract.deleteDocument(context.contentResolver, uri))
            }
        } finally {
            try {
                try { compose.activityRule.scenario.close() } finally {
                    runBlocking {
                        awaitLocalDataWriters(owners) { }
                        ownedGate.backup(replacing = false) { }
                    }
                }
            } finally {
                GoLocalDataCheckActivity.closeOwnedDatabase(name)
                assertTrue(context.deleteDatabase(name))
                assertTrue("No installation legacy source may be created", legacy.listFiles()!!.isEmpty())
                assertTrue(legacy.delete())
            }
        }
    }

    @Test fun actualSettingsExportRestoreRetiresOldOwnersAndFreshGoPagePersistsTheRestoredGame() {
        val firstGame = openLocalFromHome()
        playAndAwaitSave(firstGame, 0, 0)
        returnFromGoToHome()
        val firstLesson = visitLearningAndReturnHome()
        val oldRepository = storage.sessionRepository()
        val exportEpoch = gate.epoch.value
        openSettingsFromHome()
        exportThroughActualSettings()
        assertEquals("Export is not a replacement", exportEpoch, gate.epoch.value)
        val original = snapshotGo()
        assertEquals(1, original.sessions.size)
        assertEquals(1, original.records.size)
        assertEquals(1, original.sources.size)
        assertEquals(2, original.progress.size)
        assertTrue(firstGame.viewModelScope.coroutineContext[Job]!!.isCompleted)
        assertTrue(firstLesson.viewModelScope.coroutineContext[Job]!!.isCompleted)
        captureApp("settings-exported")

        returnFromSettingsToHome()
        val modifiedGame = openLocalFromHome()
        assertNotSame(firstGame, modifiedGame)
        playAndAwaitSave(modifiedGame, 1, 0)
        val oldSaved = runBlocking { oldRepository.load()!! }
        returnFromGoToHome()
        runBlocking {
            storage.learningRepository().save(GoLearningProgress(
                GoLearningLibrary.lessons.take(3).map { it.id }.toSet(), GoLearningLibrary.lessons[2].id))
            storage.endgameRepository().complete(GoEndgameLibrary.puzzles[1].id)
            database.gameRecordDao().setFavorite(original.records.single().recordId, false)
        }
        assertFalse(original.sessions.single().checkpoint.contentEquals(snapshotGo().sessions.single().checkpoint))
        openSettingsFromHome()
        selectBackupThroughActualSettings()
        compose.onNodeWithText(context.getString(R.string.data_backup_restore_confirm_action)).performClick()
        awaitFeedback(R.string.data_backup_restored)
        assertEquals(exportEpoch + 1, gate.epoch.value)
        assertSnapshotEquals(original, snapshotGo())
        assertOldRepositoryRejected(oldRepository, oldSaved)
        instrumentation.runOnMainSync {
            modifiedGame.play(2, 0)
            firstLesson.selectLesson(GoLearningLibrary.lessons.last().id)
        }
        compose.waitForIdle()
        assertTrue(modifiedGame.viewModelScope.coroutineContext[Job]!!.isCompleted)
        assertSnapshotEquals(original, snapshotGo())
        assertNoSharedRewards()
        captureApp("settings-restored")

        returnFromSettingsToHome()
        val fresh = openLocalFromHome()
        assertNotSame(modifiedGame, fresh)
        assertEquals(GoStone.BLACK.code, fresh.state.value.stones[0])
        assertEquals(GoStone.EMPTY.code, fresh.state.value.stones[1])
        playAndAwaitSave(fresh, 1, 0)
        assertEquals(GoStone.WHITE.code, fresh.state.value.stones[1])
        captureApp("restored-go-reopened-and-saved")
        returnFromGoToHome()
        val lesson = visitLearningAndReturnHome()
        assertEquals(setOf(GoLearningLibrary.lessons.first().id), lesson.state.value.progress.completedLessonIds)
        assertEquals(GoLearningLibrary.lessons.first().id, lesson.state.value.progress.lastLessonId)
        assertEquals(original.records, snapshotGo().records)
        assertEquals(original.sources, snapshotGo().sources)
        assertNoSharedRewards()
    }

    @Test fun actualSettingsCancelAndInvalidRestorePreserveDataAndReopenedGoCanStillSave() {
        val oldGame = openLocalFromHome()
        playAndAwaitSave(oldGame, 0, 0)
        returnFromGoToHome()
        visitLearningAndReturnHome()
        val oldRepository = storage.sessionRepository()
        val oldSaved = runBlocking { oldRepository.load()!! }
        openSettingsFromHome()
        exportThroughActualSettings()
        val original = snapshotGo()
        val before = gate.epoch.value

        selectBackupThroughActualSettings()
        compose.onNodeWithText(context.getString(R.string.data_backup_restore_cancel)).performClick()
        assertSettingsActionsReady()
        assertEquals(before, gate.epoch.value)
        assertSnapshotEquals(original, snapshotGo())
        captureApp("settings-restore-confirm-cancelled")

        context.contentResolver.openOutputStream(createdDocuments.single(), "w")!!.use {
            it.write("not-a-valid-mocs-backup-$runId".toByteArray(Charsets.US_ASCII))
        }
        selectBackupThroughActualSettings()
        compose.onNodeWithText(context.getString(R.string.data_backup_restore_confirm_action)).performClick()
        awaitFeedback(R.string.data_backup_restore_failed)
        assertEquals("A failed attempted replacement still retires stale owners", before + 1, gate.epoch.value)
        assertSnapshotEquals(original, snapshotGo())
        assertOldRepositoryRejected(oldRepository, oldSaved)
        instrumentation.runOnMainSync { oldGame.play(2, 0) }
        compose.waitForIdle()
        assertTrue(oldGame.viewModelScope.coroutineContext[Job]!!.isCompleted)
        assertSnapshotEquals(original, snapshotGo())
        captureApp("settings-invalid-restore-rejected")

        returnFromSettingsToHome()
        val fresh = openLocalFromHome()
        assertNotSame(oldGame, fresh)
        assertEquals(GoStone.BLACK.code, fresh.state.value.stones[0])
        assertEquals(GoStone.EMPTY.code, fresh.state.value.stones[1])
        playAndAwaitSave(fresh, 1, 0)
        assertEquals(GoStone.WHITE.code, fresh.state.value.stones[1])
        assertEquals(original.records, snapshotGo().records)
        assertEquals(original.sources, snapshotGo().sources)
        assertNoSharedRewards()
        captureApp("failed-restore-go-reopened-and-saved")
    }

    private fun seedOwnedGoData() = runBlocking {
        val sessions = RoomGoSessionRepository(database)
        val archiveIdentity = GoSessionIdentity.create(GoSessionOrigin.SGF_IMPORT)
        NativeGoEngine(9).use { engine ->
            repeat(2) { assertEquals(ActionResult.Accepted, engine.apply(GoAction.Pass)) }
            val revision = engine.scoringState().proposalRevision
            assertEquals(ActionResult.Accepted, engine.apply(GoAction.ConfirmScore(GoPlayer.BLACK, revision)))
            assertEquals(ActionResult.Accepted, engine.apply(GoAction.ConfirmScore(GoPlayer.WHITE, revision)))
            assertEquals(GoPhase.FINISHED, engine.phase)
            sessions.save(GoSavedSession(9, engine.serialize(), GoScoreDisplayMode.STONES, identity = archiveIdentity))
        }
        assertEquals(1, database.gameRecordDao().setFavorite(archiveIdentity.recordId, true))
        NativeGoEngine(9).use { engine ->
            sessions.save(GoSavedSession(9, engine.serialize(), GoScoreDisplayMode.STONES,
                identity = GoSessionIdentity.create(GoSessionOrigin.LOCAL)))
        }
        RoomGoLearningProgressRepository(database).save(GoLearningProgress(setOf(GoLearningLibrary.lessons.first().id)))
        RoomGoEndgameProgressRepository(database).apply {
            complete(GoEndgameLibrary.puzzles.first().id)
            select(GoEndgameLibrary.puzzles[1].id, GoEndgameChallengeMode.THEME)
        }
    }

    private fun openLocalFromHome(): GoGameViewModel {
        compose.onNodeWithTag(HOME_GO_TAG).performScrollTo().performClick()
        compose.onNodeWithTag(GO_LOCAL_ENTRY_TAG).performScrollTo().performClick()
        var model: GoGameViewModel? = null
        compose.waitUntil(15_000) {
            model = writers.snapshot().filterIsInstance<GoGameViewModel>().lastOrNull()
            model?.state?.value?.let { it.canOperate && it.boardSize == 9 } == true
        }
        awaitTag("go_intersection_0_0")
        return checkNotNull(model)
    }

    private fun playAndAwaitSave(model: GoGameViewModel, x: Int, y: Int) {
        val before = snapshotGo().sessions.single().checkpoint
        compose.onNodeWithTag("go_intersection_${x}_$y").performClick()
        compose.waitUntil(15_000) {
            model.state.value.canOperate && runBlocking {
                database.goLocalSessionDao().find(GameType.GO.code)?.checkpoint?.contentEquals(before) == false
            }
        }
        assertFalse(model.state.value.needsSave)
    }

    private fun visitLearningAndReturnHome(): GoLearningViewModel {
        compose.onNodeWithTag(HOME_GO_TAG).performScrollTo().performClick()
        compose.onNodeWithTag(GO_LEARNING_ENTRY_TAG).performScrollTo().performClick()
        var model: GoLearningViewModel? = null
        compose.waitUntil(15_000) {
            model = writers.snapshot().filterIsInstance<GoLearningViewModel>().lastOrNull()
            model?.state?.value?.let { !it.loading && it.canOperate } == true
        }
        awaitTag("go_lesson_title")
        assertNotNull(runBlocking { database.goProgressDao().find(GameType.GO.code, GoProgressKind.LEARNING.code) })
        returnFromGoToHome()
        return checkNotNull(model)
    }

    private fun returnFromGoToHome() {
        compose.onNodeWithText("返回").performScrollTo().performClick()
        awaitTag(GO_MODES_BACK_TAG)
        compose.onNodeWithTag(GO_MODES_BACK_TAG).performScrollTo().performClick()
        awaitTag(HOME_GO_TAG)
    }

    private fun openSettingsFromHome() {
        compose.onNodeWithTag(HOME_SETTINGS_TAG).performScrollTo().performClick()
        awaitTag(SETTINGS_SCREEN_TAG)
        compose.onNodeWithTag(SETTINGS_EXPORT_DATA_TAG).performScrollTo().assertIsEnabled()
    }

    private fun returnFromSettingsToHome() {
        compose.onNodeWithText(context.getString(R.string.back)).performScrollTo().performClick()
        awaitTag(HOME_GO_TAG)
    }

    private fun exportThroughActualSettings() {
        compose.onNodeWithTag(SETTINGS_EXPORT_DATA_TAG).performScrollTo().performClick()
        val title = awaitSystemNode { it.className == "android.widget.EditText" }
        assertTrue(title.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, filename)
        }))
        clickSystem(awaitSystemNode {
            it.isEnabled && (it.viewIdResourceName?.endsWith(":id/action_menu_select") == true ||
                it.text?.toString() in setOf("SAVE", "Save", "保存"))
        })
        awaitFeedback(R.string.data_backup_exported)
        assertEquals(1, createdDocuments.size)
        assertTrue(context.contentResolver.openInputStream(createdDocuments.single())!!.use { it.read() } >= 0)
    }

    private fun selectBackupThroughActualSettings() {
        compose.onNodeWithTag(SETTINGS_RESTORE_DATA_TAG).performScrollTo().performClick()
        clickSystem(awaitSystemNode { it.text?.toString() == filename })
        awaitFeedbackText(R.string.data_backup_restore_confirm_title)
    }

    private fun awaitFeedback(resource: Int) {
        awaitFeedbackText(resource)
        compose.onNodeWithText(context.getString(resource)).performScrollTo().assertIsDisplayed()
        assertSettingsActionsReady()
    }

    private fun awaitFeedbackText(resource: Int) {
        val text = context.getString(resource)
        compose.waitUntil(15_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun assertSettingsActionsReady() {
        compose.onNodeWithTag(SETTINGS_EXPORT_DATA_TAG).assertIsEnabled()
        compose.onNodeWithTag(SETTINGS_RESTORE_DATA_TAG).assertIsEnabled()
        compose.onNodeWithText(context.getString(R.string.data_backup_working)).assertDoesNotExist()
    }

    private fun assertOldRepositoryRejected(repository: GoSessionRepository, saved: GoSavedSession) {
        try { runBlocking { repository.save(saved) }; fail("Retired Go repository must reject before writing") }
        catch (error: IOException) { assertTrue(error.message!!.contains("数据已替换")) }
    }

    private fun assertNoSharedRewards() = runBlocking {
        assertTrue(database.matchOutcomeDao().listAll().isEmpty())
        assertTrue(database.endgameProgressDao().listAll().isEmpty())
        assertNull(database.tutorialProgressDao().find(GameType.GO.code))
    }

    private data class GoSnapshot(
        val sessions: List<GoLocalSessionEntity>,
        val sources: List<GoRecordSourceEntity>,
        val records: List<GameRecordEntity>,
        val progress: List<GoProgressEntity>,
    )

    private fun snapshotGo(): GoSnapshot = runBlocking {
        database.withTransaction {
            GoSnapshot(database.goLocalSessionDao().listAll(), database.goRecordSourceDao().listAll(),
                database.gameRecordDao().listAll(), database.goProgressDao().listAll())
        }
    }

    private fun assertSnapshotEquals(expected: GoSnapshot, actual: GoSnapshot) {
        assertEquals(expected.sessions.map { Triple(it.gameTypeCode, it.envelopeVersion, it.updatedAtEpochMillis) },
            actual.sessions.map { Triple(it.gameTypeCode, it.envelopeVersion, it.updatedAtEpochMillis) })
        expected.sessions.zip(actual.sessions).forEach { (left, right) -> assertArrayEquals(left.checkpoint, right.checkpoint) }
        assertEquals(expected.sources, actual.sources)
        assertEquals(expected.records, actual.records)
        fun metadata(rows: List<GoProgressEntity>) = rows.map {
            listOf(it.gameTypeCode.toLong(), it.kindCode.toLong(), it.formatVersion.toLong(),
                it.contentVersion.toLong(), it.updatedAtEpochMillis)
        }
        assertEquals(metadata(expected.progress), metadata(actual.progress))
        expected.progress.zip(actual.progress).forEach { (left, right) -> assertArrayEquals(left.payload, right.payload) }
    }

    private fun awaitTag(tag: String) {
        compose.waitUntil(15_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun awaitSystemNode(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo {
        val until = SystemClock.uptimeMillis() + 15_000
        while (SystemClock.uptimeMillis() < until) {
            if (Build.VERSION.SDK_INT >= 34) instrumentation.uiAutomation.clearCache()
            val root = instrumentation.uiAutomation.rootInActiveWindow
            if (root?.packageName?.toString()?.contains("documentsui", ignoreCase = true) == true) {
                findNode(root, predicate)?.let { return it }
            }
            SystemClock.sleep(100)
        }
        throw AssertionError("Expected real DocumentsUI node was not found")
    }

    private fun findNode(node: AccessibilityNodeInfo, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (predicate(node)) return node
        for (index in 0 until node.childCount) {
            node.getChild(index)?.let { child -> findNode(child, predicate)?.let { return it } }
        }
        return null
    }

    private fun clickSystem(node: AccessibilityNodeInfo) {
        var clickable: AccessibilityNodeInfo? = node
        while (clickable != null && !clickable.isClickable) clickable = clickable.parent
        assertNotNull(clickable)
        assertTrue(clickable!!.performAction(AccessibilityNodeInfo.ACTION_CLICK))
    }

    private fun captureApp(label: String) {
        val activity = compose.activity
        compose.waitUntil(15_000) {
            activity.hasWindowFocus() && activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        }
        instrumentation.runOnMainSync { activity.window.decorView.postInvalidateOnAnimation() }
        compose.waitForIdle()
        SystemClock.sleep(350)
        val directory = File(context.getExternalFilesDir(null), "go-production-backup-qa/$runId")
        assertTrue(directory.isDirectory || directory.mkdirs())
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        try {
            assertTrue("The real Settings/Go NavHost must be landscape", bitmap.width > bitmap.height)
            File(directory, "$label.png").outputStream().use {
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally { bitmap.recycle() }
    }

    /** This proxy only observes actual results; every launch is delegated to the real system. */
    private inner class RecordingRegistry(private val base: ActivityResultRegistry) : ActivityResultRegistry() {
        override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I,
            options: ActivityOptionsCompat?,
        ) {
            val launcher = base.register("go-settings-$runId-$requestCode", contract) { result ->
                if (contract is ActivityResultContracts.CreateDocument && result is Uri) createdDocuments.add(result)
                dispatchResult(requestCode, result)
            }
            launcher.launch(input, options)
        }
    }
}
