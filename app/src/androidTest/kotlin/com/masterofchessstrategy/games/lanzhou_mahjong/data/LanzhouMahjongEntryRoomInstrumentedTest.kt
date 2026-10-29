package com.masterofchessstrategy.games.lanzhou_mahjong.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.masterofchessstrategy.data.TutorialProgressEntity
import com.masterofchessstrategy.engine.Difficulty
import com.masterofchessstrategy.games.lanzhou_mahjong.*
import java.io.IOException
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Actual SQLite, strict Native replay and production Host binding; UUID-owned databases only. */
@RunWith(AndroidJUnit4::class)
class LanzhouMahjongEntryRoomInstrumentedTest {
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
            assertEquals("此难度的可信胜场资格尚未接入", resume.state.value.table!!.feedback)
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
