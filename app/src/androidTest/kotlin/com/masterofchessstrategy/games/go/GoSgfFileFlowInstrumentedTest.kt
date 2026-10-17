package com.masterofchessstrategy.games.go

import android.graphics.Bitmap
import android.accessibilityservice.AccessibilityService
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.DocumentsContract
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.core.app.ActivityOptionsCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.masterofchessstrategy.data.*
import com.masterofchessstrategy.endgame.ChineseChessEndgamePackParser
import com.masterofchessstrategy.engine.BoardPosition
import com.masterofchessstrategy.ui.GoLocalDataCheckActivity
import com.masterofchessstrategy.engine.ActionResult
import com.masterofchessstrategy.engine.GameResult
import com.masterofchessstrategy.engine.GoAction
import com.masterofchessstrategy.engine.GoPhase
import com.masterofchessstrategy.engine.GoPlayer
import com.masterofchessstrategy.engine.NativeGoEngine
import com.masterofchessstrategy.engine.RestoreResult
import com.masterofchessstrategy.games.go.data.*
import com.masterofchessstrategy.settings.awaitLocalDataWriters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

/** Real system SAF dialogs; the registry only records returned URIs for UUID-file cleanup. */
class GoSgfFileFlowInstrumentedTest {
    @get:Rule val composeRule = createAndroidComposeRule<GoLocalDataCheckActivity>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val runId = UUID.randomUUID().toString()
    private val filename = "mocs-go-qa-$runId.sgf"
    private val database by lazy { composeRule.activity.ownedDatabase }
    private val gate by lazy { GoStorageEpochGate.forDatabase(database) }
    private val repository by lazy { GatedGoSessionRepository(RoomGoSessionRepository(database), gate) }
    private val ownedWriters = linkedSetOf<ViewModel>()
    private val ownedReplays = linkedSetOf<GoReplayController>()
    private var storedReplayKey: String? = null
    private val createdDocuments = mutableListOf<Uri>()
    @Volatile private var cancelledCreates = 0
    @Volatile private var returnedCreates = 0

    @After fun cleanupOnlyOwnDocuments() {
        val ownedDatabaseName = composeRule.activity.ownedDatabaseName
        val ownedLegacyDirectory = composeRule.activity.ownedLegacyDirectory
        val ownedGate = gate
        var drained = false
        try {
            // SAF grants are temporary: delete only our returned UUID documents while the Activity lives.
            for (uri in createdDocuments.distinct()) {
                assertTrue(DocumentsContract.deleteDocument(context.contentResolver, uri))
            }
        } finally {
            try {
                try {
                    // Retire replay Native owners and page producers even if document cleanup failed.
                    composeRule.activityRule.scenario.close()
                } finally {
                    val cleanup = CoroutineScope(SupervisorJob() + Dispatchers.Default)
                    val finished = cleanup.async {
                        println("GO_SGF_CLEANUP=replay-drain")
                        ownedReplays.forEach { it.closeAndJoin() }
                        println("GO_SGF_CLEANUP=writer-drain")
                        if (ownedWriters.isNotEmpty()) {
                            withContext(Dispatchers.Main) { awaitLocalDataWriters(ownedWriters.toList()) { } }
                        }
                        println("GO_SGF_CLEANUP=gate-drain")
                        ownedGate.backup(replacing = false) { }
                        println("GO_SGF_CLEANUP=finished")
                    }
                    try {
                        // Unlike nested runBlocking, this pumps Compose's scheduler and has a real deadline.
                        composeRule.waitUntil(15_000) { finished.isCompleted }
                        runBlocking { finished.await() } // Already completed: only propagate its result.
                        drained = true
                    } finally {
                        cleanup.cancel()
                    }
                }
            } finally {
                if (drained) {
                    GoLocalDataCheckActivity.closeOwnedDatabase(ownedDatabaseName)
                    // These paths belong to this isolated test, never to the installation's saves.
                    assertTrue(context.deleteDatabase(ownedDatabaseName))
                    assertTrue("No legacy source should be created", ownedLegacyDirectory.listFiles()!!.isEmpty())
                    assertTrue(ownedLegacyDirectory.delete())
                } else {
                    println("GO_SGF_RETAINED_DB=$ownedDatabaseName; cleanup not confirmed, no close/delete")
                }
            }
        }
    }

    @Test fun realFilePickerRoundTripCancelAndMalformedUtf8KeepDurableSessionSafe() {
        runBlocking {
            NativeGoEngine(9).use { engine ->
                engine.apply(GoAction.Place(BoardPosition(0, 0)))
                repository.save(GoSavedSession(9, engine.serialize(), GoScoreDisplayMode.STONES, 0))
            }
        }
        val registry = RecordingRegistry(composeRule.activity.activityResultRegistry)
        val owner = object : ActivityResultRegistryOwner { override val activityResultRegistry = registry }
        composeRule.activityRule.scenario.onActivity { activity ->
            activity.setContent {
                CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                    MaterialTheme {
                        GoGameRoute(onBack = {}, repository = repository, onWriterReady = { ownedWriters.add(it) })
                    }
                }
            }
        }
        awaitPhase("白方行棋")
        val original = runBlocking { repository.load()!! }
        composeRule.onNodeWithTag("go_import_sgf").performScrollTo().performClick()
        captureApp("import-confirmation")
        composeRule.onNodeWithText("取消").performClick()
        assertArrayEquals(original.engineState, runBlocking { repository.load()!! }.engineState)

        composeRule.onNodeWithTag("go_export_sgf").performScrollTo().performClick()
        val title = awaitSystemNode { it.className == "android.widget.EditText" }
        assertTrue(title.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, filename)
        }))
        captureSystem("create-document")
        clickSystem(awaitSystemNode {
            it.isEnabled && (it.viewIdResourceName?.endsWith(":id/action_menu_select") == true ||
                it.text?.toString() in setOf("SAVE", "Save", "保存"))
        })
        awaitMessage("SGF 棋谱已导出")
        assertEquals(1, createdDocuments.size)
        val text = context.contentResolver.openInputStream(createdDocuments.single())!!.use(GoSgfDocument::read)
        assertTrue(text.contains("SZ[9]"))
        assertTrue(text.contains(";B[aa]"))
        captureApp("exported")

        composeRule.onNodeWithText("新对局").performScrollTo().performClick()
        composeRule.onNodeWithText("13路").performClick()
        composeRule.onNodeWithText("开始").performClick()
        awaitPhase("黑方行棋")
        assertEquals(13, runBlocking { repository.load()!! }.boardSize)
        selectExportedDocument()
        awaitPhase("白方行棋")
        val imported = runBlocking { repository.load()!! }
        assertEquals(9, imported.boardSize)
        assertArrayEquals(original.engineState, imported.engineState)
        assertEquals(0, imported.lastMoveIndex)
        captureApp("imported")

        context.contentResolver.openOutputStream(createdDocuments.single(), "w")!!.use {
            it.write(byteArrayOf(0xc3.toByte(), 0x28))
        }
        selectExportedDocument()
        awaitMessage("SGF 文件必须使用有效的 UTF-8 编码")
        assertArrayEquals(imported.engineState, runBlocking { repository.load()!! }.engineState)
        captureApp("invalid-utf8")
    }

    @Test fun realOversizedSgfDocumentIsRejectedBeforeChangingTheRoomCheckpointOrArchives() {
        runBlocking {
            NativeGoEngine(9).use { engine ->
                assertEquals(ActionResult.Accepted, engine.apply(GoAction.Place(BoardPosition(0, 0))))
                repository.save(GoSavedSession(9, engine.serialize(), GoScoreDisplayMode.STONES, 0))
            }
        }
        val registry = RecordingRegistry(composeRule.activity.activityResultRegistry)
        val owner = object : ActivityResultRegistryOwner { override val activityResultRegistry = registry }
        composeRule.activityRule.scenario.onActivity { activity ->
            activity.setContent {
                CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                    MaterialTheme {
                        GoGameRoute(onBack = {}, repository = repository, onWriterReady = { ownedWriters.add(it) })
                    }
                }
            }
        }
        awaitPhase("白方行棋")
        val original = runBlocking { repository.load()!! }
        val checkpoint = runBlocking { database.goLocalSessionDao().find(1)!! }
        val sources = runBlocking { database.goRecordSourceDao().listAll() }
        val records = runBlocking { database.gameRecordDao().listAll() }
        assertTrue(sources.isEmpty() && records.isEmpty())

        composeRule.onNodeWithTag("go_export_sgf").performScrollTo().performClick()
        saveCreatedDocument(filename)
        awaitMessage("SGF 棋谱已导出")
        assertEquals(1, createdDocuments.size)
        val document = createdDocuments.single()
        // The provider owns a real oversized ASCII file; do not bypass SAF with a fake stream/size.
        context.contentResolver.openOutputStream(document, "w")!!.use {
            it.write(ByteArray(GoSgfDocument.MAX_BYTES + 1) { 'x'.code.toByte() })
        }
        assertEquals(GoSgfDocument.MAX_BYTES + 1,
            context.contentResolver.openInputStream(document)!!.use { it.readBytes().size })
        selectExportedDocument()
        awaitMessage("SGF 文件超过 1,000,000 字节")
        awaitPhase("白方行棋")
        composeRule.onNodeWithTag("go_import_sgf").assertIsEnabled()
        val retained = runBlocking { repository.load()!! }
        assertEquals(original.identity, retained.identity)
        assertEquals(original.boardSize, retained.boardSize)
        assertEquals(original.lastMoveIndex, retained.lastMoveIndex)
        assertArrayEquals(original.engineState, retained.engineState)
        runBlocking {
            val row = database.goLocalSessionDao().find(1)!!
            assertArrayEquals(checkpoint.checkpoint, row.checkpoint)
            assertEquals(checkpoint.envelopeVersion, row.envelopeVersion)
            assertEquals(checkpoint.updatedAtEpochMillis, row.updatedAtEpochMillis)
            assertEquals(sources, database.goRecordSourceDao().listAll())
            assertEquals(records, database.gameRecordDao().listAll())
            assertTrue(database.matchOutcomeDao().listAll().isEmpty())
            assertTrue(database.endgameProgressDao().listAll().isEmpty())
        }
        captureApp("oversized-sgf-rejected")
    }

    @Test fun archivedRecordExportsItsWholeMainlineFromCursorZeroAndPickerCancelIsReadOnly() {
        val recordId = seedCompletedArchive()
        val original = runBlocking { database.gameRecordDao().find(recordId)!! }
        val source = runBlocking { database.goRecordSourceDao().find(recordId)!! }
        val checkpoint = runBlocking { database.goLocalSessionDao().find(1)!!.checkpoint }
        showStoredRecord(recordId, RecordingRegistry(composeRule.activity.activityResultRegistry))
        awaitStoredExport()
        composeRule.onNodeWithTag("go_replay_cursor")
            .assertTextContains("行棋 0 / 3 着", substring = true)
        composeRule.onNodeWithText("来源：SGF 导入").performScrollTo().assertExists()

        composeRule.onNodeWithTag("go_stored_record_export").performScrollTo().performClick()
        cancelCreateDocument()
        awaitStoredExport()
        composeRule.onNodeWithText("SGF 棋谱已导出").assertDoesNotExist()
        assertTrue(createdDocuments.isEmpty())
        assertEquals(original, runBlocking { database.gameRecordDao().find(recordId) })
        assertEquals(source, runBlocking { database.goRecordSourceDao().find(recordId) })
        assertArrayEquals(checkpoint, runBlocking { database.goLocalSessionDao().find(1)!!.checkpoint })
        captureApp("archive-export-cancelled")

        composeRule.onNodeWithTag("go_stored_record_export").performScrollTo().performClick()
        saveCreatedDocument(filename)
        awaitMessage("SGF 棋谱已导出")
        assertEquals(1, createdDocuments.size)
        val text = context.contentResolver.openInputStream(createdDocuments.single())!!.use(GoSgfDocument::read)
        assertTrue(text.contains("SZ[9]"))
        assertTrue("The cursor is zero, but the exported record must include the placement and both passes",
            text.contains(";B[aa]") && text.contains(";W[]") && text.contains(";B[]"))
        NativeGoEngine(9).use { engine ->
            assertEquals(RestoreResult.Restored, engine.restoreSgf(text))
            assertEquals(3, engine.eventHistory().size)
            // SGF deliberately lacks the agreement events; it must not invent an agreed draw.
            assertEquals(GoPhase.SCORING, engine.phase)
            assertEquals(GameResult.ONGOING, engine.gameResult())
        }
        composeRule.onNodeWithTag("go_replay_cursor")
            .assertTextContains("行棋 0 / 3 着", substring = true)
        assertEquals(original, runBlocking { database.gameRecordDao().find(recordId) })
        assertEquals(source, runBlocking { database.goRecordSourceDao().find(recordId) })
        assertArrayEquals(checkpoint, runBlocking { database.goLocalSessionDao().find(1)!!.checkpoint })
        assertTrue(runBlocking { database.matchOutcomeDao().listAll().isEmpty() })
        captureApp("archive-export-full-mainline")
    }

    @Test fun documentPickerResultAfterActualRoomRestoreCannotExportTheRetiredArchive() {
        val recordId = seedCompletedArchive()
        showStoredRecord(recordId, RecordingRegistry(composeRule.activity.activityResultRegistry))
        awaitStoredExport()
        composeRule.onNodeWithTag("go_stored_record_export").performScrollTo().performClick()
        awaitSystemNode { it.className == "android.widget.EditText" }

        val originalEpoch = gate.epoch.value
        runBlocking {
            val registry = GameBackupRegistry(listOf(GoLocalSessionBackupAdapter(), GoLocalRecordBackupAdapter(),
                GoProgressBackupAdapter()))
            val backup = GatedGoBackupRepository(RoomLocalDataBackupRepository(database,
                ChineseChessEndgamePackParser.loadBundled(context.assets), { false }, registry), gate,
                GoLocalBackupPreparation(database, GoLocalLegacySource { error("must not migrate during restore") },
                    GoLearningLegacySource { error("must not migrate during restore") },
                    GoEndgameLegacySource { error("must not migrate during restore") }, GoUnsupportedBackupSourceGuard { }))
            backup.restore(LocalDataBackupCodec.encode(LocalDataSnapshot(5), registry))
        }
        assertEquals(originalEpoch + 1, gate.epoch.value)
        saveCreatedDocument("mocs-go-qa-$runId-stale.sgf")
        composeRule.waitUntil(15_000) { returnedCreates == 1 }
        awaitMessage("本地数据已替换，请返回后重新打开棋谱")
        composeRule.waitForIdle()
        composeRule.onNodeWithText("SGF 棋谱已导出").assertDoesNotExist()
        assertEquals(1, createdDocuments.size)
        val written = context.contentResolver.openInputStream(createdDocuments.single())!!.use { it.readBytes() }
        assertEquals("A stale result must not write the old SGF into the newly created document", 0, written.size)
        runBlocking {
            assertTrue(database.gameRecordDao().listAll().isEmpty())
            assertTrue(database.goRecordSourceDao().listAll().isEmpty())
            assertTrue(database.goLocalSessionDao().listAll().isEmpty())
            assertTrue(database.goProgressDao().listAll().isEmpty())
            for (scope in GoLegacyImportScopes.ALL) assertNotNull(database.localDataMigrationDao().find(scope))
        }
        captureApp("archive-export-retired-result")
    }

    private fun seedCompletedArchive(): String = runBlocking {
        val identity = GoSessionIdentity.create(GoSessionOrigin.SGF_IMPORT)
        NativeGoEngine(9).use { engine ->
            assertEquals(ActionResult.Accepted, engine.apply(GoAction.Place(BoardPosition(0, 0))))
            repeat(2) { assertEquals(ActionResult.Accepted, engine.apply(GoAction.Pass)) }
            val revision = engine.scoringState().proposalRevision
            assertEquals(ActionResult.Accepted, engine.apply(GoAction.ConfirmScore(GoPlayer.BLACK, revision)))
            assertEquals(ActionResult.Accepted, engine.apply(GoAction.ConfirmScore(GoPlayer.WHITE, revision)))
            assertEquals(GoPhase.FINISHED, engine.phase)
            repository.save(GoSavedSession(9, engine.serialize(), GoScoreDisplayMode.STONES, 0, identity))
        }
        identity.recordId
    }

    private fun showStoredRecord(recordId: String, registry: RecordingRegistry) {
        storedReplayKey = "go-replay-stored-${gate.epoch.value}-$recordId"
        val owner = object : ActivityResultRegistryOwner { override val activityResultRegistry = registry }
        composeRule.activityRule.scenario.onActivity { activity ->
            activity.setContent {
                CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                    MaterialTheme { GoStoredRecordRoute(recordId, database, gate, onBack = {}) }
                }
            }
        }
    }

    private fun awaitStoredExport() {
        composeRule.waitUntil(15_000) {
            composeRule.onAllNodes(hasTestTag("go_stored_record_export") and isEnabled())
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("go_stored_record_export").assertIsEnabled()
        composeRule.activityRule.scenario.onActivity { activity ->
            val key = checkNotNull(storedReplayKey)
            check(key in activity.viewModelStore.keys()) { "The actual stored replay owner must already exist" }
            ownedReplays.add(ViewModelProvider(activity)[key, GoReplayViewModel::class.java].controller)
        }
    }

    private fun saveCreatedDocument(name: String) {
        val title = awaitSystemNode { it.className == "android.widget.EditText" }
        assertTrue(title.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, name)
        }))
        captureSystem("create-$name")
        clickSystem(awaitSystemNode {
            it.isEnabled && (it.viewIdResourceName?.endsWith(":id/action_menu_select") == true ||
                it.text?.toString() in setOf("SAVE", "Save", "保存"))
        })
    }

    private fun cancelCreateDocument() {
        awaitSystemNode { it.className == "android.widget.EditText" }
        val expected = cancelledCreates + 1
        // The first real BACK may dismiss the filename keyboard; the next closes DocumentsUI.
        repeat(3) {
            assertTrue(instrumentation.uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK))
            val until = SystemClock.uptimeMillis() + 1_500
            while (SystemClock.uptimeMillis() < until && cancelledCreates < expected) SystemClock.sleep(100)
            if (cancelledCreates == expected) return
        }
        throw AssertionError("DocumentsUI did not return a cancelled CreateDocument result")
    }

    private fun selectExportedDocument() {
        composeRule.onNodeWithTag("go_import_sgf").performScrollTo().performClick()
        composeRule.onNodeWithText("选择文件").performClick()
        clickSystem(awaitSystemNode { it.text?.toString() == filename })
    }

    private fun awaitPhase(text: String) {
        composeRule.waitUntil(15_000) {
            composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() &&
                composeRule.onAllNodesWithText("正在保存对局…").fetchSemanticsNodes().isEmpty() &&
                composeRule.onAllNodesWithText("正在校验并保存导入棋谱…").fetchSemanticsNodes().isEmpty()
        }
    }

    private fun awaitMessage(text: String) {
        composeRule.waitUntil(15_000) { composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun awaitSystemNode(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo {
        val until = SystemClock.uptimeMillis() + 15_000
        while (SystemClock.uptimeMillis() < until) {
            if (Build.VERSION.SDK_INT >= 34) instrumentation.uiAutomation.clearCache()
            val root = instrumentation.uiAutomation.rootInActiveWindow
            if (root != null && root.packageName?.toString()?.contains("documentsui", ignoreCase = true) == true) {
                findNode(root, predicate)?.let { return it }
            }
            SystemClock.sleep(100)
        }
        throw AssertionError("Expected system document-picker node was not found")
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

    private fun captureApp(name: String) {
        composeRule.waitUntil(10_000) {
            composeRule.activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE &&
                composeRule.activity.hasWindowFocus()
        }
        composeRule.waitForIdle()
        // A returned URI can arrive before DocumentsUI gives our render surface focus again.
        composeRule.runOnUiThread { composeRule.activity.window.decorView.postInvalidateOnAnimation() }
        instrumentation.waitForIdleSync()
        // Window-manager fades continue outside Compose's test clock.
        SystemClock.sleep(350)
        // PixelCopy of the actual App root avoids UiAutomation's rotated display capture
        // after returning from DocumentsUI. No rotation/cropping of evidence pixels.
        saveBitmap(name, composeRule.onRoot().captureToImage().asAndroidBitmap(), requireLandscape = true)
    }

    private fun captureSystem(name: String) {
        SystemClock.sleep(350)
        saveBitmap(name, checkNotNull(instrumentation.uiAutomation.takeScreenshot()))
    }

    private fun saveBitmap(name: String, bitmap: Bitmap, requireLandscape: Boolean = false) {
        val directory = File(context.getExternalFilesDir(null), "go-sgf-qa/$runId")
        assertTrue(directory.isDirectory || directory.mkdirs())
        try {
            if (requireLandscape) assertTrue("Expected a landscape App screenshot: $name", bitmap.width > bitmap.height)
            File(directory, "$name.png").outputStream().use {
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally { bitmap.recycle() }
    }

    private inner class RecordingRegistry(private val base: ActivityResultRegistry) : ActivityResultRegistry() {
        override fun <I, O> onLaunch(
            requestCode: Int,
            contract: ActivityResultContract<I, O>,
            input: I,
            options: ActivityOptionsCompat?,
        ) {
            val launcher = base.register("go-sgf-qa-$runId-$requestCode", contract) { result ->
                if (contract is ActivityResultContracts.CreateDocument) {
                    if (result is Uri) createdDocuments.add(result) else cancelledCreates += 1
                    returnedCreates += 1
                }
                dispatchResult(requestCode, result)
            }
            launcher.launch(input, options)
        }
    }
}
