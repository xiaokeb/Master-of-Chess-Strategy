package com.masterofchessstrategy.ui

import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.test.platform.app.InstrumentationRegistry
import com.masterofchessstrategy.data.*
import com.masterofchessstrategy.engine.*
import com.masterofchessstrategy.games.go.*
import com.masterofchessstrategy.games.go.data.*
import com.masterofchessstrategy.settings.LocalDataWriterRegistry
import com.masterofchessstrategy.settings.LocalDataWriterRetirement
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Production NavHost, Room and locked asset/Native model. No scripted search or user DB access. */
class GoAiProductionEntryInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<GoLocalDataCheckActivity>()

    @Test fun unfinishedTenLessonProgressKeepsNewGameDisabledInProductionSetup() {
        withOwnedApp(completedLessons = 9) { f ->
            f.mountApp()
            enterSetup()
            compose.onNodeWithText("教程 9 / 10 课；解锁仅代表进度条件，不代表棋力已标定。")
                .performScrollTo().assertExists()
            compose.onNodeWithTag("go_ai_setup_create").performScrollTo().assertIsNotEnabled()
            compose.onNodeWithTag("go_ai_setup_difficulty_0").performScrollTo().assertIsNotEnabled()
            assertNull(f.active())
            assertTrue(runBlocking { f.db.goRankedDao().starts().isEmpty() })
            assertTrue(f.aiOwners().isEmpty())
            f.capture("nine-lessons-locked")
        }
    }

    @Test fun actualProductionModelRepliesAndResumeKeepsOriginalConfigWhileAbandonCancelKeepsOldGame() {
        withOwnedApp(completedLessons = 10) { f ->
            f.mountApp()
            enterSetup()
            compose.onNodeWithTag("go_ai_setup_size_9").performScrollTo().performClick()
            compose.onNodeWithTag("go_ai_setup_create").performScrollTo().assertIsEnabled()
            f.capture("setup-nine-ready")
            compose.onNodeWithTag("go_ai_setup_create").performClick()
            awaitHumanTurn()
            val first = checkNotNull(f.active())
            assertEquals(GoAiMatchConfig(9), first.config)
            assertEquals(1, runBlocking { f.db.goRankedDao().starts().size })
            val firstOwner = f.aiOwners().single()
            assertFalse(firstOwner.state.value.loadingModel)
            assertEquals(first.matchId, firstOwner.state.value.match?.matchId)
            assertTrue(firstOwner.state.value.match?.immediateHumanCompletion == true)
            compose.onNodeWithTag("go_intersection_0_0").performClick()
            // This waits on the real production loader/runtime. A timeout or search error fails;
            // there is no pass-only or legal-move fallback driver in this test.
            compose.waitUntil(60_000) {
                f.active()?.let { saved ->
                    NativeGoEngine(saved.config.boardSize).use {
                        it.restore(saved.copyBytes()) == RestoreResult.Restored &&
                            it.eventHistory().size == 2 && it.currentPlayer == PlayerId(GoPlayer.BLACK.code)
                    }
                } == true
            }
            awaitHumanTurn()
            val replied = checkNotNull(f.active())
            NativeGoEngine(9).use {
                assertEquals(RestoreResult.Restored, it.restore(replied.copyBytes()))
                assertEquals(GoAction.Place(BoardPosition(0, 0)), it.eventHistory().first())
                assertEquals(2, it.eventHistory().size)
                assertEquals(GoPhase.PLAYING, it.phase)
                assertEquals(GameResult.ONGOING, it.gameResult())
            }
            f.capture("actual-nn-legal-reply")
            returnToSetup()
            assertReleased(firstOwner)
            compose.waitUntil(15_000) { f.aiOwners().isEmpty() }
            assertTrue(f.aiOwners().isEmpty())
            compose.onNodeWithText("当前对局：9路 · 简单 · 你执黑").performScrollTo().assertExists()
            f.capture("setup-after-owner-release")

            compose.onNodeWithTag("go_ai_setup_size_13").performScrollTo().performClick()
            compose.onNodeWithTag("go_ai_setup_color_1").performScrollTo().performClick()
            compose.onNodeWithTag("go_ai_setup_continue").performScrollTo().performClick()
            awaitHumanTurn()
            val resumed = checkNotNull(f.active())
            assertEquals(first.matchId, resumed.matchId)
            assertEquals(first.config, resumed.config)
            assertArrayEquals(replied.copyBytes(), resumed.copyBytes())
            val resumedOwner = f.aiOwners().single()
            assertEquals(first.config, resumedOwner.state.value.match?.config)
            f.capture("resume-original-nine-black")
            returnToSetup()
            assertReleased(resumedOwner)
            compose.waitUntil(15_000) { f.aiOwners().isEmpty() }
            assertTrue(f.aiOwners().isEmpty())

            val beforeCancel = checkNotNull(f.active())
            compose.onNodeWithTag("go_ai_setup_size_13").performScrollTo().performClick()
            compose.onNodeWithTag("go_ai_setup_color_1").performScrollTo().performClick()
            compose.onNodeWithTag("go_ai_setup_create").performScrollTo().performClick()
            compose.onNodeWithTag("go_ai_setup_abandon_confirm").assertExists()
            compose.onNodeWithText("保留当前对局").performClick()
            compose.onNodeWithTag("go_ai_setup_abandon_confirm").assertDoesNotExist()
            assertArrayEquals(GoAiMatchFileCodec.encode(beforeCancel),
                GoAiMatchFileCodec.encode(checkNotNull(f.active())))
            assertEquals(1, runBlocking { f.db.goRankedDao().starts().size })
            assertTrue(runBlocking { f.db.goAiCheckpointDao().listArchives().isEmpty() })
            f.capture("abandon-cancel-preserves-current")
        }
    }

    @Test fun validButMissingMatchIdReturnsToSetupWithoutCreatingAnAiOwnerOrCheckpoint() {
        assertRejectedPlayRoute(UUID.randomUUID().toString(), "missing-active-id")
    }

    @Test fun invalidMatchIdReturnsToSetupWithoutCreatingAnAiOwnerOrCheckpoint() {
        // Old persisted matches need not be UUIDs. NUL is invalid under both old and new GAI.
        assertRejectedPlayRoute("invalid\u0000match-id", "invalid-id")
    }

    @Test fun activityRecreationRetainsTheSameLiveNativeOwnerAndCurrentMatch() {
        // Use the isolated debug Activity's own production content/Android-retained VMStore.
        // Reattaching a separate custom store after recreation could first restore another
        // production NavHost and accidentally open a second model; this fixture avoids that.
        withOwnedApp(completedLessons = 10, useActivityStore = true) { f ->
            enterSetup()
            compose.onNodeWithTag("go_ai_setup_size_9").performScrollTo().performClick()
            compose.onNodeWithTag("go_ai_setup_create").performScrollTo().performClick()
            awaitHumanTurn()
            val initial = checkNotNull(f.active())
            val owner = f.aiOwners().single()
            assertFalse(owner.retirementCompletion.isCompleted)
            f.capture("before-activity-recreation")

            compose.activityRule.scenario.recreate()
            awaitHumanTurn()
            assertSame(f.db, compose.activity.ownedDatabase)
            assertSame(owner, f.aiOwners().single())
            assertFalse("Activity recreation must not retire a retained VM", owner.retirementCompletion.isCompleted)
            assertFalse(owner.state.value.match!!.closed)
            val restored = checkNotNull(f.active())
            assertEquals(initial.matchId, restored.matchId)
            assertArrayEquals(GoAiMatchFileCodec.encode(initial), GoAiMatchFileCodec.encode(restored))
            compose.onNodeWithTag("go_intersection_0_0").performClick()
            compose.waitUntil(60_000) {
                f.active()?.let { saved ->
                    NativeGoEngine(9).use {
                        it.restore(saved.copyBytes()) == RestoreResult.Restored && it.eventHistory().size == 2
                    }
                } == true
            }
            awaitHumanTurn()
            assertSame(owner, f.aiOwners().single())
            assertEquals(initial.matchId, f.active()!!.matchId)
            assertEquals(1, runBlocking { f.db.goRankedDao().starts().size })
            f.capture("retained-owner-replies-after-recreation")
            returnToSetup()
            assertReleased(owner)
        }
    }

    @Test fun productionRestartThenActivityRecreationKeepsTheNewIdentityAndSameLiveOwner() {
        withOwnedApp(completedLessons = 10, useActivityStore = true) { f ->
            enterSetup()
            compose.onNodeWithTag("go_ai_setup_size_9").performScrollTo().performClick()
            compose.onNodeWithTag("go_ai_setup_create").performScrollTo().performClick()
            awaitHumanTurn()
            val first = checkNotNull(f.active())
            val owner = f.aiOwners().single()
            compose.onNodeWithTag("go_ai_restart").performScrollTo().performClick()
            compose.onNodeWithTag("go_ai_restart_confirm").performClick()
            compose.waitUntil(15_000) {
                val match = owner.state.value.match
                match != null && (match.matchId != first.matchId || match.game.error != null)
            }
            assertNull("A standard ongoing game must actually restart after confirmation",
                owner.state.value.match?.game?.error)
            val restarted = checkNotNull(f.active())
            assertNotEquals(first.matchId, restarted.matchId)
            assertEquals(first.config, restarted.config)
            assertEquals(restarted.matchId, owner.state.value.match?.matchId)
            assertEquals(2, runBlocking { f.db.goRankedDao().starts().size })
            assertSame(owner, f.aiOwners().single())
            assertFalse(owner.retirementCompletion.isCompleted)
            f.capture("restart-confirmed-new-identity")

            // Flush the ready/persisted identity callback before Android saves page state.
            compose.waitForIdle()
            compose.activityRule.scenario.recreate()
            awaitHumanTurn()
            compose.onNodeWithTag("go_ai_setup").assertDoesNotExist()
            assertSame(f.db, compose.activity.ownedDatabase)
            assertSame(owner, f.aiOwners().single())
            assertFalse(owner.retirementCompletion.isCompleted)
            assertFalse(owner.state.value.match!!.closed)
            val restored = checkNotNull(f.active())
            assertEquals(restarted.matchId, restored.matchId)
            assertArrayEquals(GoAiMatchFileCodec.encode(restarted), GoAiMatchFileCodec.encode(restored))
            compose.onNodeWithTag("go_intersection_0_0").performClick()
            compose.waitUntil(60_000) {
                f.active()?.let { saved ->
                    NativeGoEngine(9).use {
                        it.restore(saved.copyBytes()) == RestoreResult.Restored && it.eventHistory().size == 2
                    }
                } == true
            }
            awaitHumanTurn()
            assertSame(owner, f.aiOwners().single())
            assertEquals(restarted.matchId, f.active()!!.matchId)
            assertEquals(2, runBlocking { f.db.goRankedDao().starts().size })
            f.capture("recreated-new-identity-still-plays")
            returnToSetup()
            assertReleased(owner)
        }
    }

    private fun assertRejectedPlayRoute(matchId: String?, picture: String) {
        withOwnedApp(completedLessons = 0) { f ->
            val returns = AtomicInteger()
            f.mount {
                val nav = rememberNavController()
                NavHost(nav, startDestination = "rejected-play") {
                    composable("rejected-play") {
                        GoAiPlayNavigationRoute(f.storage, f.writers, matchId) {
                            returns.incrementAndGet()
                            nav.navigate("returned-setup") {
                                popUpTo("rejected-play") { inclusive = true }
                            }
                        }
                    }
                    composable("returned-setup") {
                        GoAiSetupNavigationRoute(f.storage, f.writers,
                            onOpen = { error("missing match must not open play") },
                            onLearning = {}, onBack = {})
                    }
                }
            }
            awaitSetupRead()
            assertEquals(1, returns.get())
            compose.onNodeWithTag("go_ai_loading_model").assertDoesNotExist()
            assertTrue(f.aiOwners().isEmpty())
            assertNull(f.active())
            assertTrue(runBlocking { f.db.goRankedDao().starts().isEmpty() })
            assertTrue(runBlocking { f.db.goAiCheckpointDao().listArchives().isEmpty() })
            assertTrue(runBlocking { f.db.gameRecordDao().listAll().isEmpty() })
            f.capture(picture)
        }
    }

    private fun enterSetup() {
        compose.onNodeWithTag(HOME_GO_TAG).performScrollTo().performClick()
        compose.onNodeWithTag("go_navigation_ai").performScrollTo().performClick()
        awaitSetupRead()
    }

    private fun awaitSetupRead() {
        compose.waitUntil(15_000) {
            compose.onAllNodesWithTag("go_ai_setup").fetchSemanticsNodes().isNotEmpty() &&
                compose.onAllNodesWithText("正在读取当前对局与解锁进度…").fetchSemanticsNodes().isEmpty()
        }
    }

    private fun awaitHumanTurn() {
        compose.waitUntil(60_000) {
            compose.onAllNodes(hasTestTag("go_ai_phase") and hasText("轮到你行棋"))
                .fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun returnToSetup() {
        compose.onNodeWithText("返回", useUnmergedTree = false).performScrollTo().performClick()
        awaitSetupRead()
    }

    private fun assertReleased(owner: GoAiMatchViewModel) {
        assertTrue("Back navigation must wait for the captured Native owner", owner.retirementCompletion.isCompleted)
        assertFalse(owner.retirementCompletion.isCancelled)
        runBlocking { withTimeout(60_000) { owner.retirementCompletion.await() } }
    }

    private inner class Fixture(completedLessons: Int, useActivityStore: Boolean) {
        val db = compose.activity.ownedDatabase
        val writers = LocalDataWriterRegistry.forDatabase(db)
        val storage = GoLocalStorage(db, compose.activity)
        private val store = ViewModelStore()
        private val storeOwner = object : ViewModelStoreOwner { override val viewModelStore = store }
        private val mounted = mutableStateOf(true)
        private val pictures = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null),
            "go-ai-production-entry-qa/${UUID.randomUUID()}").apply { check(mkdirs()) }

        init {
            require(completedLessons in 0..10)
            // The debug Activity already owns a UUID database. Seal every legacy source before
            // our first Go factory read; neither real AI files nor preference progress is imported.
            runBlocking {
                db.localDataMigrationDao().sealGoLegacySourcesForRestore()
                RoomGoLearningProgressRepository(db).save(GoLearningProgress(
                    GoLearningLibrary.lessons.take(completedLessons).map { it.id }.toSet()))
            }
            if (!useActivityStore) {
                compose.activityRule.scenario.onActivity { it.setContent {} }
                compose.waitForIdle()
                compose.activityRule.scenario.onActivity { it.viewModelStore.clear() }
            }
        }

        fun mountApp() = mount { MasterOfChessStrategyApp(db, storage) }
        fun mount(content: @Composable () -> Unit) {
            compose.activityRule.scenario.onActivity { activity ->
                activity.setContent {
                    if (mounted.value) CompositionLocalProvider(LocalViewModelStoreOwner provides storeOwner) {
                        content()
                    }
                }
            }
        }

        fun active(): GoAiSavedMatch? = runBlocking { storage.aiRepository().load() }
        fun aiOwners(): List<GoAiMatchViewModel> = writers.snapshot().filterIsInstance<GoAiMatchViewModel>()

        fun capture(label: String) {
            compose.waitForIdle()
            val image = compose.onRoot().captureToImage().asAndroidBitmap()
            try {
                assertTrue("Production app uses its landscape window", image.width > image.height)
                File(pictures, "$label.png").outputStream().use {
                    assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, it))
                }
            } finally { image.recycle() }
        }

        fun close() {
            compose.activityRule.scenario.onActivity { activity ->
                mounted.value = false
                activity.setContent {}
            }
            compose.waitForIdle()
            var receipts = emptyList<Deferred<Unit>>()
            var jobs = emptyList<Job>()
            // Seal and clear on Main. Await all separately owned Native cleanup before DB close.
            compose.activityRule.scenario.onActivity { activity ->
                val owners = writers.snapshot()
                jobs = owners.mapNotNull { it.viewModelScope.coroutineContext[Job] }
                receipts = owners.filterIsInstance<LocalDataWriterRetirement>().map { it.beginRetirement() }
                store.clear()
                activity.viewModelStore.clear()
            }
            runBlocking { withTimeout(60_000) { receipts.awaitAll(); jobs.joinAll() } }
            assertTrue(writers.snapshot().isEmpty())
            val name = compose.activity.ownedDatabaseName
            val legacy = compose.activity.ownedLegacyDirectory
            compose.activityRule.scenario.close()
            GoLocalDataCheckActivity.closeOwnedDatabase(name)
            assertTrue(InstrumentationRegistry.getInstrumentation().targetContext.deleteDatabase(name))
            assertTrue(legacy.listFiles()!!.isEmpty())
            assertTrue(legacy.delete())
        }
    }

    private fun withOwnedApp(completedLessons: Int, useActivityStore: Boolean = false, block: (Fixture) -> Unit) {
        val fixture = Fixture(completedLessons, useActivityStore)
        try { block(fixture) } finally { fixture.close() }
    }
}
