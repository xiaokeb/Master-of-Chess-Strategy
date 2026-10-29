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
import com.masterofchessstrategy.data.TutorialProgressEntity
import com.masterofchessstrategy.games.lanzhou_mahjong.data.LanzhouMahjongLearningAdvance
import com.masterofchessstrategy.games.lanzhou_mahjong.data.LanzhouMahjongLearningSnapshot
import com.masterofchessstrategy.games.lanzhou_mahjong.data.LanzhouMahjongOwnedDatabaseFixture
import com.masterofchessstrategy.games.lanzhou_mahjong.data.RoomLanzhouMahjongLearningRepository
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real Route/VM, Room and production backup factory; the clock and SQL fault are explicit QA inputs. */
@RunWith(AndroidJUnit4::class)
class LanzhouMahjongLearningProductionInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    @Test fun actualTenLessonAnswersPersistAndSqlFailureRetriesTheOriginalTimestamp() {
        val directory = visualDirectory()
        val clock = AtomicLong(1_000)
        val clockReads = AtomicLong(0)
        val legacy = TutorialProgressEntity(3, 1, 4, true, 77)
        withPage(now = { clockReads.incrementAndGet(); clock.get() }, prepare = { fixture ->
            io { fixture.db.tutorialProgressDao().upsert(legacy) }
        }) { fixture, page ->
            awaitReady(page, count = 0)
            compose.onNodeWithText("已掌握 0/10 课").assertIsDisplayed()
            capture(directory, "learning-01-initial")

            compose.onNodeWithTag("lanzhou_mahjong_answer_0").performScrollTo().assertIsEnabled().performClick()
            compose.waitUntil(20_000) { !page.state.value.isBusy && page.state.value.feedback?.startsWith("再想一想") == true }
            assertEquals(0, page.state.value.completedCount)
            assertEquals(0L, clockReads.get())
            assertTrue(io { fixture.db.lanzhouMahjongLearningProgressDao().readBoundedRows().isEmpty() })
            compose.onNodeWithText("下一课").performScrollTo().assertIsNotEnabled()

            io {
                fixture.db.openHelper.writableDatabase.execSQL("CREATE TRIGGER lzmj_learning_ui_fault BEFORE INSERT ON " +
                    "lanzhou_mahjong_learning_progress BEGIN SELECT RAISE(ABORT, 'owned UI learning write fault'); END")
            }
            compose.onNodeWithTag("lanzhou_mahjong_answer_1").performScrollTo().performClick()
            compose.waitUntil(20_000) { !page.state.value.isBusy && page.state.value.hasPendingSave }
            assertEquals(0, page.state.value.completedCount)
            assertTrue(page.lastFailure is android.database.sqlite.SQLiteException)
            assertEquals(1L, clockReads.get())
            assertTrue(io { fixture.db.lanzhouMahjongLearningProgressDao().readBoundedRows().isEmpty() })
            compose.onNodeWithTag("lanzhou_mahjong_lesson_0").assertIsNotEnabled()
            compose.onNodeWithTag("lanzhou_mahjong_lesson_1").assertIsNotEnabled()
            compose.onNodeWithTag("lanzhou_mahjong_answer_1").assertIsNotEnabled()
            compose.onNodeWithText("下一课").performScrollTo().assertIsNotEnabled()
            compose.onNodeWithTag("lanzhou_mahjong_learning_retry").performScrollTo().assertIsDisplayed().assertIsEnabled()
            capture(directory, "learning-02-failed")

            io { fixture.db.openHelper.writableDatabase.execSQL("DROP TRIGGER lzmj_learning_ui_fault") }
            clock.set(2_000)
            compose.onNodeWithTag("lanzhou_mahjong_learning_retry").performClick()
            awaitReady(page, count = 1)
            val first = io { requireNotNull(RoomLanzhouMahjongLearningRepository(fixture.db).load()) }
            assertEquals(LanzhouMahjongLearningSnapshot(2, 1, 1_000), first)
            assertEquals(1L, clockReads.get())
            compose.onNodeWithText("下一课").performScrollTo().assertIsDisplayed().assertIsEnabled()
            capture(directory, "learning-03-retried")

            for (index in 1 until LanzhouMahjongLearningLibrary.lessons.size) {
                compose.onNodeWithText("下一课").performScrollTo().assertIsEnabled().performClick()
                compose.waitUntil(20_000) { !page.state.value.isBusy && page.state.value.lessonIndex == index }
                val lesson = LanzhouMahjongLearningLibrary.lessons[index]
                compose.onNodeWithText(lesson.title).performScrollTo().assertIsDisplayed()
                compose.onNodeWithText(lesson.question).performScrollTo().assertIsDisplayed()
                compose.onNodeWithText("下一课").performScrollTo().assertIsNotEnabled()
                if (index == 1) {
                    compose.onNodeWithText("示例手牌：", substring = true).performScrollTo().assertIsDisplayed()
                    capture(directory, "learning-04-example-hand")
                } else if (index == 9) {
                    compose.onNodeWithText("新局按项目桌规", substring = true).performScrollTo().assertIsDisplayed()
                    assertTrue(lesson.explanation.contains("各赔24倍") && lesson.explanation.contains("合计72倍"))
                    assertTrue(lesson.explanation.contains("旧v1存档仍按每家8倍"))
                    capture(directory, "learning-05-false-win")
                }
                clock.incrementAndGet()
                compose.onNodeWithTag("lanzhou_mahjong_answer_${lesson.answerIndex}")
                    .performScrollTo().assertIsDisplayed().assertIsEnabled().performClick()
                awaitReady(page, count = index + 1)
                assertEquals(index + 1, io { requireNotNull(RoomLanzhouMahjongLearningRepository(fixture.db).load()).completedCount })
            }

            assertEquals(10L, clockReads.get())
            val completed = io { requireNotNull(RoomLanzhouMahjongLearningRepository(fixture.db).load()) }
            assertTrue(completed.isCompleted)
            assertEquals(2, completed.contentVersion)
            compose.onNodeWithTag("lanzhou_mahjong_learning_complete").performScrollTo().assertIsDisplayed()
            capture(directory, "learning-06-complete")
            compose.onNodeWithTag("lanzhou_mahjong_answer_1").performScrollTo().performClick()
            compose.waitForIdle()
            assertEquals(10L, clockReads.get())
            assertEquals(completed, io { RoomLanzhouMahjongLearningRepository(fixture.db).load() })
            assertEquals(legacy, io { fixture.db.tutorialProgressDao().find(3) })
            assertTrue(io { fixture.db.matchOutcomeDao().listAll().isEmpty() })

            returnThroughRoute(page)
            capture(directory, "learning-07-returned")
            writeMetadata(directory, "learning", page, clockReads.get())
        }
    }

    @Test fun productionRestoreRetiresTheVisibleOwnerAndRouteReturnsAfterRetirement() {
        val directory = visualDirectory()
        val original = LanzhouMahjongLearningSnapshot(2, 1, 1_000)
        withPage(now = { 2_000 }, prepare = { fixture ->
            io { RoomLanzhouMahjongLearningRepository(fixture.db).advance(LanzhouMahjongLearningAdvance(null, original)) }
        }) { fixture, page ->
            awaitReady(page, count = 1)
            compose.onNodeWithText("已掌握 1/10 课").assertIsDisplayed()
            capture(directory, "restore-01-before")
            val backup = io { fixture.repository().export(5_000) }
            assertTrue(backup.toString(Charsets.UTF_8).contains("\nLANZHOU_MAHJONG_LEARNING|3|1|2|1|1000\n"))
            compose.onNodeWithTag("lanzhou_mahjong_answer_0").performScrollTo().performClick()
            awaitReady(page, count = 2)
            assertEquals(2, io { requireNotNull(RoomLanzhouMahjongLearningRepository(fixture.db).load()).completedCount })

            io { fixture.repository().restore(backup) }
            compose.waitUntil(20_000) { page.state.value.retired && !page.state.value.isBusy }
            io { page.retirementCompletion.await() }
            compose.onNodeWithText("本地数据已更换或页面已关闭，请返回后重新进入").assertIsDisplayed()
            compose.onNodeWithTag("lanzhou_mahjong_answer_0").assertDoesNotExist()
            compose.runOnUiThread { page.submit(0); page.retrySave(); page.reload() }
            compose.waitForIdle()
            assertEquals(original, io { RoomLanzhouMahjongLearningRepository(fixture.db).load() })
            assertTrue(io { fixture.db.matchOutcomeDao().listAll().isEmpty() })
            capture(directory, "restore-02-retired")
            returnThroughRoute(page)
            assertEquals(original, io { RoomLanzhouMahjongLearningRepository(fixture.db).load() })
            capture(directory, "restore-03-returned")
            writeMetadata(directory, "restore", page, null)
        }
    }

    private fun withPage(
        now: () -> Long,
        prepare: (LanzhouMahjongOwnedDatabaseFixture) -> Unit,
        block: (LanzhouMahjongOwnedDatabaseFixture, LanzhouMahjongLearningViewModel) -> Unit,
    ) {
        val fixture = LanzhouMahjongOwnedDatabaseFixture()
        val models = ViewModelStore()
        var page: LanzhouMahjongLearningViewModel? = null
        var returned by mutableStateOf(false)
        var returnCount = 0
        try {
            prepare(fixture)
            compose.runOnUiThread {
                page = LanzhouMahjongLearningViewModel(RoomLanzhouMahjongLearningRepository(fixture.db), Dispatchers.IO, now)
                models.put("lanzhou-mahjong-learning-qa", requireNotNull(page))
            }
            val current = requireNotNull(page)
            compose.setContent {
                MaterialTheme {
                    if (returned) {
                        Column(Modifier.fillMaxSize().testTag(RETURNED_TAG),
                            verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("已安全返回兰州麻将学习")
                        }
                    } else LanzhouMahjongLearningRoute(current, onBack = {
                        check(current.retirementCompletion.isCompleted && !current.retirementCompletion.isCancelled)
                        check(++returnCount == 1)
                        returned = true
                    })
                }
            }
            block(fixture, current)
        } finally {
            // Stop admission, await every accepted Room operation, then clear the VM and delete only this UUID DB.
            val current = page
            if (current == null) fixture.close()
            else {
                var retirement: Deferred<Unit>? = null
                compose.runOnUiThread { retirement = current.beginRetirement() }
                io { requireNotNull(retirement).await() }
                compose.runOnUiThread { models.clear() }
                fixture.close()
            }
        }
    }

    private fun awaitReady(page: LanzhouMahjongLearningViewModel, count: Int) = compose.waitUntil(20_000) {
        page.state.value.let { it.loaded && !it.isBusy && !it.hasPendingSave && it.completedCount == count }
    }

    private fun returnThroughRoute(page: LanzhouMahjongLearningViewModel) {
        compose.onNodeWithText("返回").performScrollTo().assertIsEnabled().performClick()
        compose.waitUntil(20_000) {
            page.retirementCompletion.isCompleted &&
                compose.onAllNodesWithTag(RETURNED_TAG).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag(RETURNED_TAG).assertIsDisplayed()
        assertFalse(page.retirementCompletion.isCancelled)
    }

    private fun visualDirectory(): File {
        val token = requireNotNull(InstrumentationRegistry.getArguments().getString("mocsVisualToken"))
        require(token.matches(Regex("qa-[a-f0-9]{32}")))
        val base = requireNotNull(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null))
        return File(base, "mocs-visual/$token").apply { check(isDirectory || mkdirs()) }
    }

    private fun capture(directory: File, name: String) {
        require(name.matches(Regex("(?:learning-0[1-7]|restore-0[1-3])-[a-z-]+")))
        compose.waitForIdle()
        val target = File(directory, "$name.png")
        check(!target.exists()) { "Visual evidence must use a fresh token" }
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        try {
            assertTrue(bitmap.width > 0 && bitmap.height > 0)
            target.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { bitmap.recycle() }
    }

    private fun writeMetadata(directory: File, prefix: String, page: LanzhouMahjongLearningViewModel, clockReads: Long?) {
        val target = File(directory, "$prefix-metadata.json")
        check(!target.exists())
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        target.writeText(JSONObject()
            .put("fontScale", context.resources.configuration.fontScale)
            .put("clockReadCount", clockReads ?: JSONObject.NULL)
            .put("clockIsQaInput", true).put("sqlFaultIsQaInput", prefix == "learning")
            .put("retirementAcknowledged", page.retirementCompletion.isCompleted && !page.retirementCompletion.isCancelled)
            .put("defaultDatabaseTouched", false).toString(2))
    }

    private fun <T> io(operation: suspend () -> T): T = runBlocking {
        withTimeout(20_000) { withContext(Dispatchers.IO) { operation() } }
    }

    companion object { private const val RETURNED_TAG = "lanzhou_mahjong_learning_qa_returned" }
}
