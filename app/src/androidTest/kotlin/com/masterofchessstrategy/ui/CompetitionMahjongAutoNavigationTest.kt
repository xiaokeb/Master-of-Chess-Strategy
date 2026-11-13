package com.masterofchessstrategy.ui

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.app.Notification
import android.app.NotificationManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.os.SystemClock
import android.provider.DocumentsContract
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.core.app.ActivityOptionsCompat
import androidx.test.platform.app.InstrumentationRegistry
import com.masterofchessstrategy.engine.NativeCompetitionMahjongAutoSession
import com.masterofchessstrategy.games.competition_mahjong.auto.CompetitionMahjongAutoCheckpoint
import com.masterofchessstrategy.games.competition_mahjong.auto.CompetitionMahjongAutoOpeningLibrary
import com.masterofchessstrategy.games.competition_mahjong.auto.CompetitionMahjongAutoRunConfig
import com.masterofchessstrategy.games.competition_mahjong.auto.CompetitionMahjongAutoSettings
import com.masterofchessstrategy.games.competition_mahjong.auto.production.CompetitionMahjongAutoBackground
import com.masterofchessstrategy.games.competition_mahjong.auto.production.CompetitionMahjongAutoBackgroundService
import com.masterofchessstrategy.games.competition_mahjong.auto.production.CompetitionMahjongAutoProductionRoute
import com.masterofchessstrategy.games.competition_mahjong.auto.replay.CompetitionMahjongAutoExportCodec
import com.masterofchessstrategy.games.competition_mahjong.auto.replay.CompetitionMahjongAutoReplayInstrumentedTest.Companion.nativeArchive
import com.masterofchessstrategy.games.competition_mahjong.auto.storage.RoomCompetitionMahjongAutoStore
import com.masterofchessstrategy.games.competition_mahjong.learning.CompetitionMahjongLearningProofCatalog
import com.masterofchessstrategy.games.competition_mahjong.learning.CompetitionMahjongLearningProofRequest
import com.masterofchessstrategy.games.competition_mahjong.learning.RoomCompetitionMahjongLearningProofStore
import com.masterofchessstrategy.settings.LocalDataWriterRegistry
import com.masterofchessstrategy.settings.awaitLocalDataWriters
import com.masterofchessstrategy.ui.theme.MocsTheme
import java.io.File
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Production NavHost and isolated Room data, with actual Native sessions and system file picker. */
class CompetitionMahjongAutoNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<GoLocalDataCheckActivity>()
    private val database get() = compose.activity.ownedDatabase
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    @Volatile private var createdDocument: Uri? = null
    private val images by lazy {
        File(instrumentation.targetContext.getExternalFilesDir(null), "competition-auto-navigation-qa/${UUID.randomUUID()}")
            .apply { check(mkdirs()) }
    }

    @After fun cleanup() {
        val db = database
        val name = compose.activity.ownedDatabaseName
        val legacy = compose.activity.ownedLegacyDirectory
        try {
            createdDocument?.let { assertTrue(DocumentsContract.deleteDocument(instrumentation.targetContext.contentResolver, it)) }
        } finally {
            compose.activityRule.scenario.close()
            runBlocking { awaitLocalDataWriters(LocalDataWriterRegistry.forDatabase(db).snapshot()) {} }
            GoLocalDataCheckActivity.closeOwnedDatabase(name)
            assertTrue(instrumentation.targetContext.deleteDatabase(name))
            assertTrue(legacy.listFiles()!!.isEmpty())
            assertTrue(legacy.delete())
        }
    }

    @Test fun lockedViewerCanReachTheTutorialWithoutCreatingActivity() {
        openModes()
        compose.onNodeWithTag("competition_auto_entry").performScrollTo().performClick()
        awaitTag("competition_mahjong_auto_learning")
        compose.onNodeWithText("完成四章国标麻将教程后开放自动演局。").performScrollTo().assertIsDisplayed()
        assertNull(runBlocking { RoomCompetitionMahjongAutoStore(database, 0).load(0) })
        capture("locked")
        compose.onNodeWithTag("competition_mahjong_auto_learning").performScrollTo().performClick()
        awaitTag("competition_mahjong_learning_chapters")
    }

    @Test fun learnedViewerStepsReturnsAndResumesExactlyTheSameStoredEvent() {
        val db = database
        val viewer = 2
        completeTutorial()
        openModes(viewer)
        openAutomatic()
        val reader = RoomCompetitionMahjongAutoStore(db, viewer)
        val initial = checkNotNull(runBlocking { reader.load(viewer) })
        compose.onNodeWithTag("competition_mahjong_auto_step").performScrollTo().assertIsEnabled().performClick()
        compose.waitUntil(15_000) { runBlocking { reader.load(viewer)?.eventsAfterOpening == 1 } }
        val saved = checkNotNull(runBlocking { reader.load(viewer) })
        assertEquals(initial.config.runId, saved.config.runId)
        assertEquals(viewer, saved.config.viewerPlayerId)
        assertTrue(saved.settings.paused)
        assertTrue(runBlocking { db.matchOutcomeDao().listAll().isEmpty() })
        capture("played")
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        awaitTag("competition_auto_entry")
        openAutomatic()
        val resumed = checkNotNull(runBlocking { reader.load(viewer) })
        assertEquals(saved.config.runId, resumed.config.runId)
        assertArrayEquals(saved.engineRecord, resumed.engineRecord)
        assertNull(runBlocking { RoomCompetitionMahjongAutoStore(db, 0).load(0) })
        capture("resumed")
    }

    @Test fun selectedOpeningBecomesTheImmutableBaselineAndSurvivesReentry() {
        val db = database
        val viewer = 2
        completeTutorial()
        val store = RoomCompetitionMahjongAutoStore(db, viewer)
        val opening = CompetitionMahjongAutoOpeningLibrary.load(viewer).single { it.id == "early-exchanges" }
        openModes(viewer)
        openAutomatic()
        val initial = checkNotNull(runBlocking { store.load(viewer) })
        assertNull(initial.openingRecord)

        val newRun = "competition_mahjong_auto_new"
        val openingTag = "competition_mahjong_auto_opening_${opening.id}"
        compose.onNodeWithTag(newRun).performScrollTo().performClick()
        compose.onNodeWithTag("competition_mahjong_auto_opening_first-discard").performScrollTo().assertIsEnabled()
        compose.onNodeWithTag(openingTag).performScrollTo().assertIsEnabled().performClick()
        capture("opening-selected")
        compose.onNodeWithTag("competition_mahjong_auto_new_cancel").performClick()
        assertEquals(initial.config.runId, runBlocking { store.load(viewer)?.config?.runId })

        compose.onNodeWithTag(newRun).performScrollTo().performClick()
        compose.onNodeWithTag(openingTag).performScrollTo().performClick()
        compose.onNodeWithTag("competition_mahjong_auto_new_confirm").assertIsEnabled().performClick()
        compose.waitUntil(15_000) { runBlocking { store.load(viewer)?.openingRecord != null } }
        val selected = checkNotNull(runBlocking { store.load(viewer) })
        assertNotEquals(initial.config.runId, selected.config.runId)
        assertArrayEquals(opening.engineRecord, selected.openingRecord)
        assertArrayEquals(selected.baseline, selected.engineRecord)
        assertEquals(0, selected.eventsAfterOpening)
        assertTrue(selected.settings.paused)
        capture("opening-baseline")

        compose.onNodeWithTag("competition_mahjong_auto_step").performScrollTo().performClick()
        compose.waitUntil(15_000) { runBlocking { store.load(viewer)?.eventsAfterOpening == 1 } }
        val saved = checkNotNull(runBlocking { store.load(viewer) })
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        awaitTag("competition_auto_entry")
        openAutomatic()
        val restored = checkNotNull(runBlocking { store.load(viewer) })
        assertEquals(saved.config.runId, restored.config.runId)
        assertArrayEquals(saved.engineRecord, restored.engineRecord)
        assertArrayEquals(opening.engineRecord, restored.openingRecord)
        assertTrue(runBlocking { db.matchOutcomeDao().listAll().isEmpty() })
        capture("opening-resumed")
    }

    @Test fun notificationControlsScreenOffPlaybackAndCommitsThePause() {
        check(Build.HARDWARE in setOf("ranchu", "goldfish"))
        val context = instrumentation.targetContext
        val notifications = context.getSystemService(NotificationManager::class.java)
        val power = context.getSystemService(PowerManager::class.java)
        val store = RoomCompetitionMahjongAutoStore(database, 0)
        val run = CompetitionMahjongAutoRunConfig(UUID.randomUUID().toString(), 20261112)
        val start = NativeCompetitionMahjongAutoSession(run.handConfig(0, 0, false)).use { it.serialize() }
        val settings = CompetitionMahjongAutoSettings(stepDelayMillis = 700, backgroundEnabled = true,
            backgroundDelayMillis = 2500)
        completeTutorial()
        runBlocking {
            assertNull(store.load(0))
            store.save(CompetitionMahjongAutoCheckpoint(run, settings, 0, 0, emptyList(), start, start))
        }
        fun notification() = notifications.activeNotifications
            .singleOrNull { it.id == CompetitionMahjongAutoBackgroundService.NOTIFICATION_ID }?.notification
        fun saved() = checkNotNull(runBlocking { store.load(0) })
        fun await(stage: String, predicate: () -> Boolean) = runBlocking {
            try {
                withTimeout(20_000) { while (!predicate()) delay(60) }
            } catch (failure: TimeoutCancellationException) {
                val lease = CompetitionMahjongAutoBackground.lease.value
                throw AssertionError("$stage: interactive=${power.isInteractive}, notification=${notification() != null}, " +
                    "leaseReady=${lease?.ready}, foreground=${lease?.foreground}, events=${saved().eventsAfterOpening}, " +
                    "paused=${saved().settings.paused}", failure)
            }
        }
        fun wakeHeld() = backgroundShell("dumpsys power").substringAfter("Wake Locks:").substringBefore("Suspend Blockers:")
            .contains("MasterofChessStrategy:CompetitionMahjongAuto")
        fun wakeScreen() {
            backgroundShell("input keyevent 224")
            backgroundShell("wm dismiss-keyguard")
        }
        val button = "competition_mahjong_auto_background_toggle"
        try {
            wakeScreen()
            assertFalse(notifications.areNotificationsEnabled())
            openModes()
            openAutomatic()
            compose.onNodeWithTag(button).performScrollTo().assertIsNotEnabled()
            compose.onNodeWithTag("competition_mahjong_auto_toggle").performScrollTo().performClick()
            compose.waitUntil(15_000) { compose.onAllNodes(hasTestTag(button) and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag(button).performScrollTo().performClick()
            val deadline = SystemClock.elapsedRealtime() + 4_000
            while (SystemClock.elapsedRealtime() < deadline) {
                val root = instrumentation.uiAutomation.rootInActiveWindow
                val deny = listOf("com.google.android.permissioncontroller", "com.android.permissioncontroller")
                    .firstNotNullOfOrNull { pkg -> root?.findAccessibilityNodeInfosByViewId("$pkg:id/permission_deny_button")?.firstOrNull() }
                if (deny?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) break
                SystemClock.sleep(80)
            }
            compose.onNodeWithText("通知设置").performScrollTo().assertIsDisplayed()
            assertNull(CompetitionMahjongAutoBackground.lease.value)
            assertNull(notification())

            instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
            backgroundShell("cmd appops set --uid ${context.packageName} POST_NOTIFICATION allow")
            await("runtime notification grant") { notifications.areNotificationsEnabled() }
            compose.onNodeWithTag(button).performScrollTo().performClick()
            await("foreground service started") { CompetitionMahjongAutoBackground.lease.value?.ready == true && notification() != null }
            compose.onNodeWithTag("competition_mahjong_auto_background_status").performScrollTo().assertTextEquals("后台演局已启用")
            capture("background-enabled")
            val notice = checkNotNull(notification())
            assertTrue(notice.flags and Notification.FLAG_ONGOING_EVENT != 0)
            assertEquals(CompetitionMahjongAutoBackground.CHANNEL, notice.channelId)
            assertEquals("停止后台演局", notice.actions.single().title.toString())
            backgroundShell("cmd statusbar expand-notifications")
            try {
                SystemClock.sleep(800)
                captureFailure("background-notification")
            } finally { backgroundShell("cmd statusbar collapse") }

            backgroundShell("input keyevent 223")
            await("screen-off lease and wake lock") {
                !power.isInteractive && CompetitionMahjongAutoBackground.lease.value?.foreground == false && wakeHeld()
            }
            val atSleep = saved().eventsAfterOpening
            await("first screen-off event") { saved().eventsAfterOpening > atSleep }
            val first = saved().eventsAfterOpening
            val firstAt = SystemClock.elapsedRealtime()
            await("second screen-off event") { saved().eventsAfterOpening > first }
            val interval = SystemClock.elapsedRealtime() - firstAt
            assertTrue("Measured background interval $interval ms", interval >= 2200)
            val background = saved()
            assertEquals(run.runId, background.config.runId)
            println("MAHJONG_BACKGROUND events=$atSleep/$first/${background.eventsAfterOpening} interval_ms=$interval")

            wakeScreen()
            notice.contentIntent.send()
            await("notification returns to the visible owner") {
                power.isInteractive && CompetitionMahjongAutoBackground.lease.value?.foreground == true && !wakeHeld()
            }
            assertEquals(run.runId, saved().config.runId)
            capture("background-returned")

            backgroundShell("input keyevent 223")
            await("second screen-off lease") {
                !power.isInteractive && CompetitionMahjongAutoBackground.lease.value?.foreground == false && wakeHeld()
            }
            notice.actions.single().actionIntent.send()
            await("notification stop commits pause") {
                CompetitionMahjongAutoBackground.lease.value == null && notification() == null && !wakeHeld() && saved().settings.paused
            }
            val stopped = saved()
            SystemClock.sleep(settings.backgroundDelayMillis + 400)
            assertArrayEquals(stopped.engineRecord, saved().engineRecord)
            assertEquals(run.runId, saved().config.runId)
            assertTrue(runBlocking { database.matchOutcomeDao().listAll().isEmpty() })
            wakeScreen()
            compose.onNodeWithTag("competition_mahjong_auto_toggle").performScrollTo().assertTextEquals("继续演局")
            capture("background-stopped")
        } finally {
            instrumentation.runOnMainSync { CompetitionMahjongAutoBackground.lease.value?.let {
                CompetitionMahjongAutoBackground.release(it.token)
            } }
            wakeScreen()
            backgroundShell("cmd appops set --uid ${context.packageName} POST_NOTIFICATION allow")
        }
    }

    private fun backgroundShell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(
        instrumentation.uiAutomation.executeShellCommand(command),
    ).use { it.readBytes().toString(Charsets.UTF_8) }

    @Test fun productionArchiveReplaysAndSystemFilePickerCancellationPreservesPrivateArchive() {
        val archive = nativeArchive()
        val viewer = archive.checkpoint.config.viewerPlayerId
        val store = RoomCompetitionMahjongAutoStore(database, viewer)
        runBlocking { store.load(viewer); store.save(archive.checkpoint, archive) }
        openModes(viewer)
        compose.onNodeWithTag("competition_auto_archive_entry").performScrollTo().performClick()
        val export = "competition_mahjong_auto_archive_export_${archive.key}"
        try {
            compose.waitUntil(15_000) { compose.onAllNodes(hasTestTag(export) and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag(export).performScrollTo().assertIsDisplayed().performClick()
            awaitTag("competition_mahjong_auto_export_confirm")
        } catch (failure: Throwable) {
            runCatching { captureFailure("archive-controls-failure") }
            throw failure
        }
        compose.onNodeWithText("各席暗牌和牌墙", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("competition_mahjong_auto_export_confirm").performClick()
        val automation = instrumentation.uiAutomation
        val deadline = SystemClock.elapsedRealtime() + 15_000
        while (SystemClock.elapsedRealtime() < deadline &&
            automation.rootInActiveWindow?.packageName?.toString()?.contains("documentsui", ignoreCase = true) != true) {
            SystemClock.sleep(100)
        }
        assertTrue(automation.rootInActiveWindow?.packageName?.toString()?.contains("documentsui", ignoreCase = true) == true)
        repeat(3) {
            if (automation.rootInActiveWindow?.packageName?.toString()?.contains("documentsui", ignoreCase = true) == true) {
                assertTrue(automation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK))
                SystemClock.sleep(750)
            }
        }
        compose.waitUntil(15_000) {
            compose.onAllNodesWithText("未写出棋谱，原归档保持不变", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue(archive.sameContent(checkNotNull(runBlocking { store.load(archive.key, viewer) })))
        val replay = "competition_mahjong_auto_archive_replay_${archive.key}"
        compose.onNodeWithTag(replay).performScrollTo().assertIsDisplayed().assertIsEnabled().performClick()
        awaitTag("competition_mahjong_auto_replay_position")
        compose.onNodeWithTag("competition_mahjong_auto_replay_position").performScrollTo()
            .assertTextContains("第1 / 16盘", substring = true)
        val next = "competition_mahjong_auto_replay_next"
        compose.onNodeWithTag(next).performScrollTo().assertIsDisplayed().assertIsEnabled().performClick()
        compose.waitUntil(15_000) {
            compose.onAllNodes(hasTestTag("competition_mahjong_auto_replay_position") and hasText("事件 1 /", substring = true))
                .fetchSemanticsNodes().isNotEmpty()
        }
        capture("archive-replay")
    }

    @Test fun productionArchiveWritesAndReimportsExactMcaeThroughSystemPickers() {
        val db = database
        val archive = nativeArchive()
        val viewer = archive.checkpoint.config.viewerPlayerId
        val store = RoomCompetitionMahjongAutoStore(db, viewer)
        runBlocking {
            store.load(viewer)
            store.save(archive.checkpoint, archive)
        }
        compose.activityRule.scenario.onActivity { activity ->
            val registry = RecordingRegistry(activity.activityResultRegistry)
            val owner = object : ActivityResultRegistryOwner { override val activityResultRegistry = registry }
            activity.setContent {
                CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                    MocsTheme {
                        Box(Modifier.safeDrawingPadding()) {
                            CompetitionMahjongAutoProductionRoute(db, viewer, archiveOnly = true,
                                writers = LocalDataWriterRegistry.forDatabase(db), onLearning = {}, onBack = {})
                        }
                    }
                }
            }
        }

        val fileName = "mocs-mahjong-qa-${UUID.randomUUID()}.mcae"
        val export = "competition_mahjong_auto_archive_export_${archive.key}"
        awaitTag(export)
        compose.onNodeWithTag(export).performScrollTo().assertIsEnabled().performClick()
        compose.onNodeWithTag("competition_mahjong_auto_export_confirm").performClick()
        val fileField = awaitDocumentNode { it.className == "android.widget.EditText" }
        assertTrue(fileField.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, fileName)
        }))
        clickDocumentNode(awaitDocumentNode {
            it.isEnabled && (it.viewIdResourceName?.endsWith(":id/action_menu_select") == true ||
                it.text?.toString() in setOf("SAVE", "Save", "保存"))
        })
        awaitMessage("棋谱已导出")
        val uri = checkNotNull(createdDocument)
        val resolver = instrumentation.targetContext.contentResolver
        val written = checkNotNull(resolver.openInputStream(uri)).use { it.readBytes() }
        assertArrayEquals(CompetitionMahjongAutoExportCodec.encode(archive), written)

        compose.onNodeWithTag("competition_mahjong_auto_archive_delete_${archive.key}").performScrollTo().performClick()
        compose.onNodeWithTag("competition_mahjong_auto_delete_confirm").performClick()
        awaitTag("competition_mahjong_auto_archive_empty")
        compose.onNodeWithTag("competition_mahjong_auto_archive_import").assertIsEnabled().performClick()
        clickDocumentNode(awaitDocumentNode { it.text?.toString() == fileName })
        awaitMessage("棋谱已导入")
        val restored = checkNotNull(runBlocking { store.load(archive.key, viewer) })
        assertArrayEquals(written, CompetitionMahjongAutoExportCodec.encode(restored))
        assertArrayEquals(archive.checkpoint.engineRecord, runBlocking { store.load(viewer)?.engineRecord })
        assertTrue(runBlocking { db.matchOutcomeDao().listAll().isEmpty() })
        capture("mcae-imported")

        checkNotNull(resolver.openOutputStream(uri, "wt")).use {
            it.write(ByteArray(CompetitionMahjongAutoExportCodec.MAX_BYTES + 1))
        }
        compose.onNodeWithTag("competition_mahjong_auto_archive_import").assertIsEnabled().performClick()
        clickDocumentNode(awaitDocumentNode { it.text?.toString() == fileName })
        awaitMessage("棋谱文件超出大小限制或无法读取")
        assertArrayEquals(written, CompetitionMahjongAutoExportCodec.encode(checkNotNull(runBlocking { store.load(archive.key, viewer) })))
    }

    private inner class RecordingRegistry(private val base: ActivityResultRegistry) : ActivityResultRegistry() {
        override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>,
                                     input: I, options: ActivityOptionsCompat?) {
            val launcher = base.register("mcae-qa-${UUID.randomUUID()}", contract) { result ->
                if (contract is ActivityResultContracts.CreateDocument && result is Uri) createdDocument = result
                dispatchResult(requestCode, result)
            }
            launcher.launch(input, options)
        }
    }

    private fun awaitDocumentNode(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo {
        fun search(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (predicate(node)) return node
            for (index in 0 until node.childCount) node.getChild(index)?.let { search(it)?.let { found -> return found } }
            return null
        }
        val automation = instrumentation.uiAutomation
        val deadline = SystemClock.elapsedRealtime() + 15_000
        while (SystemClock.elapsedRealtime() < deadline) {
            if (Build.VERSION.SDK_INT >= 34) automation.clearCache()
            val root = automation.rootInActiveWindow
            if (root?.packageName?.toString()?.contains("documentsui", ignoreCase = true) == true) {
                search(root)?.let { return it }
            }
            SystemClock.sleep(100)
        }
        error("未找到系统文件选择器控件")
    }

    private fun clickDocumentNode(node: AccessibilityNodeInfo) {
        var target: AccessibilityNodeInfo? = node
        while (target != null && !target.isClickable) target = target.parent
        assertTrue(checkNotNull(target).performAction(AccessibilityNodeInfo.ACTION_CLICK))
    }

    private fun awaitMessage(message: String) = compose.waitUntil(15_000) {
        compose.onAllNodesWithText(message).fetchSemanticsNodes().isNotEmpty()
    }

    private fun completeTutorial() = runBlocking {
        val db = database
        val lessons = RoomCompetitionMahjongLearningProofStore(db, db.competitionMahjongLearningProofDao())
        CompetitionMahjongLearningProofCatalog.chapterIds.forEachIndexed { index, chapter ->
            lessons.confirm(CompetitionMahjongLearningProofRequest(
                UUID.randomUUID().toString(), chapter, CompetitionMahjongLearningProofCatalog.cardIds(chapter), index.toLong(),
            ))
        }
    }

    private fun openModes(viewer: Int = 0) {
        compose.onNodeWithTag(HOME_COMPETITION_MAHJONG_TAG).performScrollTo().performClick()
        compose.onNodeWithTag("competition_formal_seat_$viewer").performScrollTo().performClick()
    }

    private fun openAutomatic() {
        compose.onNodeWithTag("competition_auto_entry").performScrollTo().performClick()
        awaitTag("competition_mahjong_auto_step")
    }

    private fun awaitTag(tag: String) = compose.waitUntil(15_000) {
        compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File(images, "$name.png").outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
    }

    private fun captureFailure(name: String) {
        val bitmap = instrumentation.uiAutomation.takeScreenshot() ?: return
        try {
            File(images, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally {
            bitmap.recycle()
        }
    }
}
