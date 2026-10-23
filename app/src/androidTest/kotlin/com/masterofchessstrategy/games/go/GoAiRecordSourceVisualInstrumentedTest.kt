package com.masterofchessstrategy.games.go

import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.masterofchessstrategy.engine.*
import com.masterofchessstrategy.games.go.data.*
import com.masterofchessstrategy.ui.GoLocalDataCheckActivity
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Actual read-only replay route with owned Room data; screenshots are not AI gameplay evidence. */
class GoAiRecordSourceVisualInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<GoLocalDataCheckActivity>()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val database get() = compose.activity.ownedDatabase
    private var controller: GoReplayController? = null
    private val runId = UUID.randomUUID().toString()

    @Test fun associatedAiRecordDisplaysUnrankedSourceWithoutChangingSavedData() = checkSource(true)
    @Test fun oldUnassociatedRecordStillDisplaysUnknownSource() = checkSource(false)

    private fun checkSource(associated: Boolean) {
        val id = runBlocking {
            val saved = NativeGoEngine(9).use { engine ->
                assertEquals(ActionResult.Accepted, engine.apply(GoAction.Place(BoardPosition(0, 0))))
                repeat(2) { assertEquals(ActionResult.Accepted, engine.apply(GoAction.Pass)) }
                val revision = engine.scoringState().proposalRevision
                GoPlayer.entries.forEach { assertEquals(ActionResult.Accepted, engine.apply(GoAction.ConfirmScore(it, revision))) }
                GoAiSavedMatch("visual-$runId", GoAiMatchConfig(9), engine.serialize(), completionReserved = true)
            }
            GoAiCheckpointRepository(RoomGoAiCheckpointStore(database)).save(saved)
            RoomGoAiCompletionRepository(database).storeCompleted(saved)
            if (!associated) database.goAiProjectionDao().deleteAll()
            database.gameRecordDao().listAll().single().recordId
        }
        val gate = GoStorageEpochGate.forDatabase(database)
        compose.activityRule.scenario.onActivity { activity ->
            activity.setContent { MaterialTheme { GoStoredRecordRoute(id, database, gate, onBack = {}) } }
        }
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("go_stored_record_origin").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("go_stored_record_origin").performScrollTo().assertIsDisplayed()
            .assertTextEquals(if (associated) "来源：人机／自动对局原始归档（不授予排名资格）" else "来源：历史记录（未知）")
        compose.activityRule.scenario.onActivity { activity ->
            controller = ViewModelProvider(activity)["go-replay-stored-${gate.epoch.value}-$id", GoReplayViewModel::class.java].controller
        }
        val directory = File(context.getExternalFilesDir(null), "go-ai-record-source-qa/$runId")
        check(directory.mkdirs())
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File(directory, if (associated) "ai-associated.png" else "legacy-unknown.png").outputStream().use {
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
        runBlocking {
            assertEquals(1, database.gameRecordDao().listAll().size)
            assertEquals(if (associated) 1 else 0, database.goAiProjectionDao().listAll().size)
            assertTrue(database.matchOutcomeDao().listAll().isEmpty())
        }
    }

    @After fun closeOnlyOwnedReplayAndDatabase() {
        val name = compose.activity.ownedDatabaseName
        val legacy = compose.activity.ownedLegacyDirectory
        compose.activityRule.scenario.onActivity { it.setContent { }; it.viewModelStore.clear() }
        val cleanup = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val done = cleanup.async { controller?.closeAndJoin() }
        try {
            compose.waitUntil(15_000) { done.isCompleted }
            runBlocking { done.await() }
            GoLocalDataCheckActivity.closeOwnedDatabase(name)
            check(context.deleteDatabase(name))
            check(legacy.listFiles()!!.isEmpty() && legacy.delete())
        } finally { cleanup.cancel() }
    }
}
