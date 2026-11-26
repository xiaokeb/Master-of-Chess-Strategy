package com.masterofchessstrategy.games.lanzhou_mahjong.settlement

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import com.masterofchessstrategy.data.MocsDatabase
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
import com.masterofchessstrategy.games.lanzhou_mahjong.data.readLanzhouMahjongTrustedWins
import java.io.File
import java.io.IOException
import java.util.UUID
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule

class LanzhouMahjongArchiveInstrumentedTest {
    private val compose = createComposeRule()
    @get:Rule val composeOnlyForScreenTests = TestRule { statement, test ->
        if (test.methodName in setOf(
                "deletionRequiresConfirmationAndRemovesTheRecordFromTheList",
                "realArchiveScreenStepsForwardBackAndReturns",
            )) compose.apply(statement, test) else statement
    }
    @get:Rule val migrations = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), MocsDatabase::class.java)

    @Test fun migrationKeepsExistingReceiptVisibleAndUnmarked() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "lz-archive-mark-${UUID.randomUUID()}.db"
        try {
            migrations.createDatabase(name, 26).use { db ->
                db.execSQL("INSERT INTO lanzhou_mahjong_formal_creations VALUES ('fixture',1,X'01','digest')")
                db.execSQL("INSERT INTO lanzhou_mahjong_terminal_receipts VALUES " +
                    "('fixture',2,X'02','WIN',0,NULL,6,-2,-2,-2)")
            }
            migrations.runMigrationsAndValidate(name, 27, true, MocsDatabase.MIGRATION_26_27).use { db ->
                db.query("SELECT archiveMark, seat0Score FROM lanzhou_mahjong_terminal_receipts").use { rows ->
                    assertTrue(rows.moveToFirst())
                    assertEquals(0, rows.getInt(0))
                    assertEquals(6, rows.getInt(1))
                }
            }
        } finally { context.deleteDatabase(name) }
    }

    @Test fun favoriteFilterAndDeletionRoundTripThroughFullBackup() = withOwnedLanzhouDatabase { fixture ->
        val id = createReceipt(fixture)
        val archive = LanzhouMahjongArchive(fixture.db)
        assertTrue(archive.page(0, favoritesOnly = true).items.isEmpty())
        archive.setFavorite(id, true)
        assertEquals(listOf(id), archive.page(0, favoritesOnly = true).items.map { it.id })
        assertTrue(archive.frame(id, 1).isFavorite)
        val backup = fixture.repository()
        val favorited = backup.export(500)
        archive.setFavorite(id, false)
        assertTrue(archive.page(0, favoritesOnly = true).items.isEmpty())
        archive.hide(id)
        assertTrue(archive.page(0).items.isEmpty())
        assertTrue(runCatching { archive.frame(id, 0) }.exceptionOrNull() is IOException)
        assertTrue(runCatching { archive.setFavorite(id, true) }.exceptionOrNull() is IOException)
        assertEquals(-72L, fixture.db.lanzhouMahjongSettlementDao().receipt(id)?.seat0Score)
        val hidden = backup.export(600)
        backup.restore(favorited)
        assertTrue(LanzhouMahjongArchive(fixture.db).frame(id, 1).isFavorite)
        backup.restore(hidden)
        assertTrue(LanzhouMahjongArchive(fixture.db).page(0).items.isEmpty())
        assertEquals(-72L, fixture.db.lanzhouMahjongSettlementDao().receipt(id)?.seat0Score)
    }

    @Test fun deletionRequiresConfirmationAndRemovesTheRecordFromTheList() = withOwnedLanzhouDatabase { fixture ->
        val id = createReceipt(fixture)
        val archive = LanzhouMahjongArchive(fixture.db)
        compose.setContent { MaterialTheme { LanzhouMahjongArchiveScreen(archive) {} } }
        val row = "lanzhou_record_$id"
        compose.waitUntil(10_000) { compose.onAllNodesWithTag(row).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag(row).performScrollTo().performClick()
        waitForStep("0 / 1 步")
        compose.onNodeWithTag("lanzhou_record_favorite").performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("取消收藏").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("lanzhou_record_delete").performScrollTo().performClick()
        compose.onNodeWithText("取消").performClick()
        compose.onNodeWithText("0 / 1 步").assertExists()
        compose.onNodeWithTag("lanzhou_record_delete").performScrollTo().performClick()
        capture("archive-delete")
        compose.onNodeWithTag("lanzhou_record_confirm_delete").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("暂无正式棋谱。").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag(row).assertDoesNotExist()
        assertEquals(-72L, fixture.db.lanzhouMahjongSettlementDao().receipt(id)?.seat0Score)
    }

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
                        archive.hide(receipt.terminal.sessionId)
                        assertEquals(mapOf(Difficulty.EASY to 1), db.readLanzhouMahjongTrustedWins())
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

    private fun capture(name: String = "archive") {
        val token = InstrumentationRegistry.getArguments().getString("mocsVisualToken") ?: return
        require(token.matches(Regex("qa-[a-f0-9]{32}")))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(requireNotNull(context.getExternalFilesDir(null)), "mocs-visual/$token")
        check(directory.isDirectory || directory.mkdirs())
        val node = if (name == "archive-delete") compose.onNode(isDialog()) else compose.onRoot()
        val bitmap = node.captureToImage().asAndroidBitmap()
        try {
            File(directory, "$name.png").outputStream().use {
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
