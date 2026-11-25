package com.masterofchessstrategy.games.lanzhou_mahjong.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.masterofchessstrategy.data.TutorialProgressEntity
import com.masterofchessstrategy.engine.ActionResult
import com.masterofchessstrategy.engine.Difficulty
import com.masterofchessstrategy.engine.LanzhouMahjongAction
import com.masterofchessstrategy.engine.LanzhouMahjongActionKind
import com.masterofchessstrategy.engine.LanzhouMahjongBotStop
import com.masterofchessstrategy.engine.NativeLanzhouMahjongSession
import com.masterofchessstrategy.games.lanzhou_mahjong.*
import com.masterofchessstrategy.games.lanzhou_mahjong.settlement.RoomLanzhouMahjongSettlement
import java.io.IOException
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Actual SQLite, strict Native replay and production Host binding; UUID-owned databases only. */
@RunWith(AndroidJUnit4::class)
class LanzhouMahjongEntryRoomInstrumentedTest {
    @Test fun eachTrustedThresholdReachesTheProductionCreationGate() = withOwnedLanzhouDatabase { f ->
        val db = f.db
        db.lanzhouMahjongLearningProgressDao().upsert(LanzhouMahjongLearningProgressEntity(0, 2, 10, 1))
        val gate = LanzhouMahjongStorageEpochGate.forDatabase(db)
        val room = RoomLanzhouMahjongSettlement(db, gate.captureOwner())
        val entry = RoomLanzhouMahjongEntryRepository(db)
        val store = db.createLanzhouMahjongHostStorage().store
        for ((target, requirement) in lanzhouMahjongWinRequirements) {
            repeat(requirement.minimum) { index ->
                val facts = entry.loadFacts()
                assertEquals(index, facts.trustedWins[requirement.previous] ?: 0)
                assertFalse(facts.canStart(target))
                if (index == requirement.minimum - 1) {
                    val denial = runCatching { entry.authorizeNew(target, facts.active) }.exceptionOrNull()
                    assertEquals(LanzhouMahjongEntryDenial.TRUSTED_WINS_UNAVAILABLE,
                        (denial as? LanzhouMahjongEntryDeniedException)?.reason)
                }
                trustedTerminal(room, f, requirement.previous, index + 1L)
            }
            val facts = entry.loadFacts()
            assertEquals(requirement.minimum, facts.trustedWins[requirement.previous])
            assertTrue(facts.canStart(target))
            entry.authorizeNew(target, facts.active)
            assertTrue(facts.activeCompleted)
            val host = LanzhouMahjongGameHost(
                db.createLanzhouMahjongHostStorage(), LanzhouMahjongLaunchConfig(target, 99), now = { 300 },
            )
            try {
                withTimeout(15_000) { host.open() }
                assertFalse(host.state.value.restoreFailed)
                assertEquals(target, store.load()?.record?.difficulty)
                assertTrue(host.leave())
            } finally {
                withTimeout(15_000) { host.closeAndJoin() }
            }
        }
    }

    @Test fun completedLegacyRoundCanSwitchToEasyWithoutCreditingWins() = withOwnedLanzhouDatabase { f ->
        f.db.lanzhouMahjongLearningProgressDao().upsert(LanzhouMahjongLearningProgressEntity(0, 2, 10, 1))
        NativeLanzhouMahjongSession(5, 0, 1, 0).use { session ->
            assertEquals(ActionResult.Accepted, session.apply(LanzhouMahjongAction(LanzhouMahjongActionKind.WIN)))
            val record = LanzhouMahjongSessionRecord(Difficulty.HARD, 0, 5, 0, 1, 5, session.serialize())
            f.store().save(LanzhouMahjongStoredSession(record, 100))
        }
        val facts = RoomLanzhouMahjongEntryRepository(f.db).loadFacts()
        assertTrue(facts.activeCompleted)
        assertTrue(facts.trustedWins.isEmpty())
        val host = LanzhouMahjongGameHost(
            f.db.createLanzhouMahjongHostStorage(), LanzhouMahjongLaunchConfig(Difficulty.EASY, 99),
        )
        try {
            withTimeout(15_000) { host.open() }
            assertFalse(host.state.value.restoreFailed)
            assertEquals(Difficulty.EASY, f.store().load()?.record?.difficulty)
            assertTrue(f.db.lanzhouMahjongSettlementDao().readReceipts().isEmpty())
        } finally {
            withTimeout(15_000) { host.closeAndJoin() }
        }
    }

    private suspend fun trustedTerminal(
        room: RoomLanzhouMahjongSettlement,
        fixture: LanzhouMahjongOwnedDatabaseFixture,
        difficulty: Difficulty,
        base: Long,
    ) = NativeLanzhouMahjongSession(1, 0, base, 0).use { session ->
        fun snapshot(time: Long) = LanzhouMahjongStoredSession(
            LanzhouMahjongSessionRecord(
                difficulty, 0, 1, 0, base, 1, session.serialize(),
            ), time,
        )
        val initial = room.create(fixture.row(), snapshot(100)) {}
        // Use a fixed, legal move sequence for all evidence fixtures. This tests eligibility rather than AI strength.
        repeat(240) { turn ->
            when (session.advanceOtherSeats(Difficulty.EASY, 1L + turn).stop) {
                LanzhouMahjongBotStop.TERMINAL -> {
                    val receipt = room.complete(initial, snapshot(200))
                    assertTrue(receipt.isQualifiedWin)
                    return@use
                }
                LanzhouMahjongBotStop.VIEWER_ACTION -> {
                    val actions = session.legalActions()
                    val choice = actions.firstOrNull { it.kind == LanzhouMahjongActionKind.WIN } ?: actions.first {
                        it.kind in setOf(LanzhouMahjongActionKind.DRAW, LanzhouMahjongActionKind.DISCARD,
                            LanzhouMahjongActionKind.PASS)
                    }
                    assertEquals(ActionResult.Accepted, session.apply(choice))
                }
                else -> error("Native bot did not yield a legal turn")
            }
        }
        error("Seeded round did not finish")
    }

    @Test fun legacyFourStepCompletionCannotAuthorizeButAllTenIndependentLessonsCan() = withOwnedLanzhouDatabase { f ->
        f.db.tutorialProgressDao().upsert(TutorialProgressEntity(3, 1, 4, true, 17))
        val entry = RoomLanzhouMahjongEntryRepository(f.db)
        assertFalse(entry.loadFacts().tutorialCompleted)
        denied(LanzhouMahjongEntryDenial.TUTORIAL_REQUIRED) { entry.authorizeNew(Difficulty.EASY, null) }
        for (count in 0..9) {
            f.db.lanzhouMahjongLearningProgressDao().upsert(progress(count))
            denied(LanzhouMahjongEntryDenial.TUTORIAL_REQUIRED) { entry.authorizeNew(Difficulty.EASY, null) }
        }
        f.db.lanzhouMahjongLearningProgressDao().upsert(progress(10))
        entry.authorizeNew(Difficulty.EASY, null)
        assertNull(f.row())
        assertEquals(TutorialProgressEntity(3, 1, 4, true, 17), f.db.tutorialProgressDao().find(3))
        f.reopen()
        assertTrue(RoomLanzhouMahjongEntryRepository(f.db).loadFacts().tutorialCompleted)
    }

    @Test fun productionHostRejectsMissingTutorialAndDirectHigherDifficulty() = withOwnedLanzhouDatabase { f ->
        suspend fun rejected(difficulty: Difficulty) {
            val host = LanzhouMahjongGameHost(f.db.createLanzhouMahjongHostStorage(), LanzhouMahjongLaunchConfig(difficulty, 321))
            try {
                withTimeout(20_000) { host.open() }
                assertNull(host.state.value.table)
                assertTrue(host.state.value.restoreFailed)
                assertNull(f.row())
            } finally { host.closeAndJoin() }
        }
        rejected(Difficulty.EASY)
        f.db.lanzhouMahjongLearningProgressDao().upsert(progress(10))
        for (difficulty in listOf(Difficulty.MEDIUM, Difficulty.HARD, Difficulty.MASTER)) rejected(difficulty)
    }

    @Test fun restartRechecksTutorialBeforeClosingAndExactOldCheckpointRemainsPlayable() = withOwnedLanzhouDatabase { f ->
        f.db.lanzhouMahjongLearningProgressDao().upsert(progress(10))
        val host = LanzhouMahjongGameHost(f.db.createLanzhouMahjongHostStorage(),
            LanzhouMahjongLaunchConfig(Difficulty.EASY, 321), newSeed = { 999 })
        try {
            withTimeout(20_000) { host.open() }
            assertNotNull(host.state.value.table)
            val before = requireNotNull(f.row())
            f.db.lanzhouMahjongLearningProgressDao().deleteAll()
            host.restart()
            assertEquals(before, f.row())
            assertEquals("请先完成兰州麻将十课教程", host.state.value.table!!.feedback)
            assertFalse(host.state.value.table!!.blocked)
            assertTrue(host.state.value.table!!.actions.isNotEmpty())
            f.db.lanzhouMahjongLearningProgressDao().upsert(progress(10))
            host.restart()
            assertFalse(host.state.value.restoreFailed)
            assertNotEquals(before.sessionId, requireNotNull(f.row()).sessionId)
            assertEquals(999L, requireNotNull(f.store().load()).record.seed)
        } finally { host.closeAndJoin() }
    }

    @Test fun explicitNewCannotOverwriteLegacyHigherGameAndResumeCannotReauthorizeItsRestart() = withOwnedLanzhouDatabase { f ->
        f.store().save(LanzhouMahjongNativeFixtures.snapshot(discard = false, usage = 0, decisions = false))
        val original = requireNotNull(f.row())
        val create = LanzhouMahjongGameHost(f.db.createLanzhouMahjongHostStorage(), LanzhouMahjongLaunchConfig(Difficulty.EASY, 321))
        try {
            create.open()
            assertNull(create.state.value.table)
            assertEquals(original, f.row())
        } finally { create.closeAndJoin() }
        val resume = LanzhouMahjongGameHost(f.db.createLanzhouMahjongHostStorage(),
            LanzhouMahjongLaunchConfig(Difficulty.EASY, 321), openMode = LanzhouMahjongOpenMode.RESUME)
        try {
            resume.open()
            assertNotNull(resume.state.value.table)
            val current = requireNotNull(f.row())
            f.db.lanzhouMahjongLearningProgressDao().upsert(progress(10))
            resume.restart()
            assertEquals(current, f.row())
            assertEquals("前一难度的可信胜场尚未达到解锁要求", resume.state.value.table!!.feedback)
            assertFalse(resume.state.value.table!!.blocked)
            assertEquals(Difficulty.MEDIUM, requireNotNull(f.store().load()).record.difficulty)
        } finally { resume.closeAndJoin() }
    }

    @Test fun malformedLearningOrActiveRowsBlockEntryWithoutErasingStoredData() = withOwnedLanzhouDatabase { f ->
        val invalid = progress(10).copy(contentVersion = 1)
        f.db.lanzhouMahjongLearningProgressDao().upsert(invalid)
        val entry = RoomLanzhouMahjongEntryRepository(f.db)
        try { entry.loadFacts(); fail("Bad lesson accepted") } catch (_: IOException) { }
        assertEquals(listOf(invalid), f.db.lanzhouMahjongLearningProgressDao().readBoundedRows())
        f.db.lanzhouMahjongLearningProgressDao().upsert(progress(10))
        f.store().save(LanzhouMahjongNativeFixtures.snapshot(discard = false, usage = 0, decisions = false))
        val row = requireNotNull(f.row())
        val bad = row.copy(engineState = byteArrayOf(0))
        f.db.activeGameDao().upsert(bad)
        try { entry.authorizeNew(Difficulty.EASY, null); fail("Bad active row ignored") } catch (_: IOException) { }
        assertEquals(bad, f.row())
    }

    @Test fun failedRestoreRetiresEntryOwnerAndNewOwnerReadsPreservedFacts() = withOwnedLanzhouDatabase { f ->
        f.db.lanzhouMahjongLearningProgressDao().upsert(progress(10))
        val old = RoomLanzhouMahjongEntryRepository(f.db)
        val backup = f.repository()
        val bytes = backup.export(900)
        f.db.openHelper.writableDatabase.execSQL("CREATE TRIGGER qa_entry_restore_failure BEFORE INSERT ON lanzhou_mahjong_learning_progress BEGIN SELECT RAISE(ABORT, 'QA restore failure'); END")
        try { assertTrue(runCatching { backup.restore(bytes) }.exceptionOrNull() is android.database.sqlite.SQLiteException) }
        finally { f.db.openHelper.writableDatabase.execSQL("DROP TRIGGER qa_entry_restore_failure") }
        try { old.authorizeNew(Difficulty.EASY, null); fail("Retired owner authorized") } catch (_: IOException) { }
        val current = RoomLanzhouMahjongEntryRepository(f.db)
        assertTrue(current.loadFacts().tutorialCompleted)
        current.authorizeNew(Difficulty.EASY, null)
    }

    private suspend fun denied(reason: LanzhouMahjongEntryDenial, operation: suspend () -> Unit) {
        try { operation(); fail("Expected admission refusal") }
        catch (failure: LanzhouMahjongEntryDeniedException) { assertEquals(reason, failure.reason) }
    }

    companion object { private fun progress(count: Int) = LanzhouMahjongLearningProgressEntity(0, 2, count, 100) }
}
