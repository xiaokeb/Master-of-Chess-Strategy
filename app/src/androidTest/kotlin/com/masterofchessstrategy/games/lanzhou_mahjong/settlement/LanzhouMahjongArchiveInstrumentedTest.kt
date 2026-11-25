package com.masterofchessstrategy.games.lanzhou_mahjong.settlement

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.masterofchessstrategy.engine.ActionResult
import com.masterofchessstrategy.engine.Difficulty
import com.masterofchessstrategy.engine.LanzhouMahjongAction
import com.masterofchessstrategy.engine.LanzhouMahjongActionKind
import com.masterofchessstrategy.engine.LanzhouMahjongBotStop
import com.masterofchessstrategy.engine.NativeLanzhouMahjongSession
import com.masterofchessstrategy.games.lanzhou_mahjong.LanzhouMahjongNativeHistory
import com.masterofchessstrategy.games.lanzhou_mahjong.LanzhouMahjongSessionRecord
import com.masterofchessstrategy.games.lanzhou_mahjong.data.LanzhouMahjongOwnedDatabaseFixture
import com.masterofchessstrategy.games.lanzhou_mahjong.data.LanzhouMahjongStorageEpochGate
import com.masterofchessstrategy.games.lanzhou_mahjong.data.LanzhouMahjongStoredSession
import com.masterofchessstrategy.games.lanzhou_mahjong.data.withOwnedLanzhouDatabase
import java.io.File
import java.io.IOException
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class LanzhouMahjongArchiveInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    @Test fun archiveRestoresRealFramesAndRejectsRetiredReaders() = withOwnedLanzhouDatabase { fixture ->
        val id = createReceipt(fixture)
        val archive = LanzhouMahjongArchive(fixture.db)
        val page = archive.page(0)
        assertFalse(page.hasNext)
        assertEquals(listOf(id), page.items.map { it.id })
        assertTrue(archive.page(1).items.isEmpty())
        val initial = archive.frame(id, 0)
        assertNull(initial.move)
        assertEquals(List(4) { 0L }, initial.view.netScores)
        assertTrue(initial.view.seats.filter { it.seat != initial.viewerSeat }.all { it.concealedTiles.isEmpty() })
        val end = archive.frame(id, 1)
        assertEquals(LanzhouMahjongActionKind.WIN, end.move?.kind)
        assertNull(end.move?.tile)
        assertEquals(listOf(-72L, 24L, 24L, 24L), end.view.netScores)
        val backup = fixture.repository()
        val bytes = backup.export(500)
        backup.restore(bytes)
        assertTrue(runCatching { archive.frame(id, 1) }.exceptionOrNull() is IOException)
        val reopened = LanzhouMahjongArchive(fixture.db)
        assertEquals(end, reopened.frame(id, 1))
        fixture.db.openHelper.writableDatabase.execSQL(
            "UPDATE lanzhou_mahjong_terminal_receipts SET seat0Score = 0 WHERE sessionId = ?", arrayOf(id),
        )
        assertTrue(runCatching { reopened.frame(id, 0) }.exceptionOrNull() is IOException)
    }

    @Test fun realArchiveScreenStepsForwardBackAndReturns() = withOwnedLanzhouDatabase { fixture ->
        val id = createReceipt(fixture)
        val archive = LanzhouMahjongArchive(fixture.db)
        var returned = false
        compose.setContent { MaterialTheme { LanzhouMahjongArchiveScreen(archive) { returned = true } } }
        val row = "lanzhou_record_$id"
        compose.waitUntil(10_000) { compose.onAllNodesWithTag(row).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag(row).performScrollTo().performClick()
        waitForStep("0 / 1 步")
        compose.onNodeWithTag("lanzhou_record_previous").assertIsNotEnabled()
        compose.onNodeWithTag("lanzhou_record_next").performScrollTo().performClick()
        waitForStep("1 / 1 步")
        compose.onNodeWithText("终局：炸胡").performScrollTo().assertIsDisplayed()
        capture()
        compose.onNodeWithTag("lanzhou_record_previous").performScrollTo().performClick()
        waitForStep("0 / 1 步")
        compose.onNodeWithTag("lanzhou_record_back").performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag(row).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("lanzhou_record_back").performScrollTo().performClick()
        compose.runOnIdle { assertTrue(returned) }
    }

    @Test fun aFullWinningHistoryRestoresBothIntermediateAndFinalTables() = withOwnedLanzhouDatabase { fixture ->
        val db = fixture.db
        val writer = RoomLanzhouMahjongSettlement(db, LanzhouMahjongStorageEpochGate.forDatabase(db).captureOwner())
        NativeLanzhouMahjongSession(1, 0, 1, 0).use { engine ->
            fun snapshot(at: Long) = LanzhouMahjongStoredSession(
                LanzhouMahjongSessionRecord(Difficulty.EASY, 0, 1, 0, 1, 1, engine.serialize()), at,
            )
            val initial = writer.create(null, snapshot(100)) {}
            val first = engine.legalActions().first { it.kind == LanzhouMahjongActionKind.DISCARD }
            assertEquals(ActionResult.Accepted, engine.apply(first))
            val intermediate = engine.snapshot()
            repeat(240) { turn ->
                when (engine.advanceOtherSeats(Difficulty.EASY, 1L + turn + 1).stop) {
                    LanzhouMahjongBotStop.TERMINAL -> {
                        val receipt = writer.complete(initial, snapshot(200))
                        assertTrue(receipt.isQualifiedWin)
                        val total = requireNotNull(LanzhouMahjongNativeHistory.actionCount(engine.serialize()))
                        assertTrue(total > 1)
                        val archive = LanzhouMahjongArchive(db)
                        val firstFrame = archive.frame(receipt.terminal.sessionId, 1)
                        assertEquals(intermediate, firstFrame.view)
                        assertEquals(first.tile, firstFrame.move?.tile)
                        assertEquals(engine.snapshot(), archive.frame(receipt.terminal.sessionId, total).view)
                        return@use
                    }
                    LanzhouMahjongBotStop.VIEWER_ACTION -> {
                        val legal = engine.legalActions()
                        val action = legal.firstOrNull { it.kind == LanzhouMahjongActionKind.WIN } ?: legal.first {
                            it.kind in setOf(LanzhouMahjongActionKind.DRAW, LanzhouMahjongActionKind.DISCARD,
                                LanzhouMahjongActionKind.PASS)
                        }
                        assertEquals(ActionResult.Accepted, engine.apply(action))
                    }
                    else -> error("Bot did not yield a legal turn")
                }
            }
            error("Round did not complete")
        }
    }

    private fun waitForStep(text: String) {
        compose.waitUntil(10_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(text).performScrollTo().assertIsDisplayed()
    }

    private fun capture() {
        val token = InstrumentationRegistry.getArguments().getString("mocsVisualToken") ?: return
        require(token.matches(Regex("qa-[a-f0-9]{32}")))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(requireNotNull(context.getExternalFilesDir(null)), "mocs-visual/$token")
        check(directory.isDirectory || directory.mkdirs())
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        try {
            File(directory, "archive.png").outputStream().use {
                check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally {
            bitmap.recycle()
        }
    }

    private suspend fun createReceipt(fixture: LanzhouMahjongOwnedDatabaseFixture): String {
        val db = fixture.db
        val gate = LanzhouMahjongStorageEpochGate.forDatabase(db)
        val writer = RoomLanzhouMahjongSettlement(db, gate.captureOwner())
        return NativeLanzhouMahjongSession(5, 0, 1, 0).use { session ->
            fun snapshot(at: Long) = LanzhouMahjongStoredSession(
                LanzhouMahjongSessionRecord(Difficulty.EASY, 0, 5, 0, 1, 5, session.serialize()), at,
            )
            val active = writer.create(null, snapshot(100)) {}
            assertEquals(ActionResult.Accepted, session.apply(LanzhouMahjongAction(LanzhouMahjongActionKind.WIN)))
            writer.complete(active, snapshot(200)).terminal.sessionId
        }
    }
}
