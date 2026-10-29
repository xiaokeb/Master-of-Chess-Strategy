package com.masterofchessstrategy.games.lanzhou_mahjong.data

import com.masterofchessstrategy.data.ActiveGameDao
import com.masterofchessstrategy.data.ActiveGameEntity
import com.masterofchessstrategy.engine.Difficulty
import com.masterofchessstrategy.games.lanzhou_mahjong.HostFixture
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/** DAO doubles test admission policy; actual SQLite/Native coverage is separate. */
@OptIn(ExperimentalCoroutinesApi::class)
class RoomLanzhouMahjongEntryRepositoryTest {
    @Test fun missingTenLessonProgressDoesNotAuthorizeNewGame() = runTest {
        val f = Fixture()
        val repo = f.repository(StandardTestDispatcher(testScheduler))
        assertNull(repo.loadFacts().learning)
        denied(LanzhouMahjongEntryDenial.TUTORIAL_REQUIRED) { repo.authorizeNew(Difficulty.EASY, null) }
        assertEquals(0, f.writes)
    }

    @Test fun onlyAllTenConfirmedLessonsAuthorizeEasyWithoutWriting() = runTest {
        val f = Fixture()
        val repo = f.repository(StandardTestDispatcher(testScheduler))
        for (count in 0..9) {
            f.learning = listOf(progress(count))
            denied(LanzhouMahjongEntryDenial.TUTORIAL_REQUIRED) { repo.authorizeNew(Difficulty.EASY, null) }
        }
        f.learning = listOf(progress(10))
        assertTrue(repo.loadFacts().tutorialCompleted)
        repo.authorizeNew(Difficulty.EASY, null)
        assertEquals(0, f.writes)
    }

    @Test fun higherDifficultiesNeverInferWinsFromUnrelatedGenericOutcomes() = runTest {
        val f = Fixture().apply { learning = listOf(progress(10)) }
        val repo = f.repository(StandardTestDispatcher(testScheduler))
        for (level in listOf(Difficulty.MEDIUM, Difficulty.HARD, Difficulty.MASTER)) {
            denied(LanzhouMahjongEntryDenial.TRUSTED_WINS_UNAVAILABLE) { repo.authorizeNew(level, null) }
        }
        assertEquals(0, f.writes)
    }

    @Test fun existingCheckpointMustBeExplicitlyNamedAndExactForReplacement() = runTest {
        val worker = StandardTestDispatcher(testScheduler)
        val f = Fixture().apply { learning = listOf(progress(10)) }
        val saved = LanzhouMahjongStoredSession(HostFixture(worker).record(), 11)
        f.active = f.admission.createRow(saved.record, saved.updatedAtEpochMillis)
        val repo = f.repository(worker)
        denied(LanzhouMahjongEntryDenial.ACTIVE_GAME_CHANGED) { repo.authorizeNew(Difficulty.EASY, null) }
        val exactRead = requireNotNull(repo.loadFacts().active)
        repo.authorizeNew(Difficulty.EASY, exactRead)
        denied(LanzhouMahjongEntryDenial.ACTIVE_GAME_CHANGED) {
            repo.authorizeNew(Difficulty.EASY, exactRead.copy(updatedAtEpochMillis = 12))
        }
        assertEquals(0, f.writes)
    }

    @Test fun disappearedCheckpointIsNotTreatedAsPermissionToReplace() = runTest {
        val worker = StandardTestDispatcher(testScheduler)
        val f = Fixture().apply { learning = listOf(progress(10)) }
        denied(LanzhouMahjongEntryDenial.ACTIVE_GAME_CHANGED) {
            f.repository(worker).authorizeNew(Difficulty.EASY, LanzhouMahjongStoredSession(HostFixture(worker).record(), 1))
        }
    }

    @Test fun unknownContentOrDuplicateSingletonIsPreservedAndNeverBecomesEmptyProgress() = runTest {
        val f = Fixture()
        val repo = f.repository(StandardTestDispatcher(testScheduler))
        for (rows in listOf(listOf(progress(10).copy(contentVersion = 1)),
            listOf(progress(10), progress(10).copy(singletonId = 1)), listOf(progress(10).copy(completedCount = 11)))) {
            f.learning = rows
            try { repo.loadFacts(); fail("Invalid stored progress became empty") } catch (_: IOException) { }
            assertEquals(rows, f.learning)
        }
        assertEquals(0, f.writes)
    }

    @Test fun malformedActiveStateIsPreservedAndBlocksAuthorization() = runTest {
        val worker = StandardTestDispatcher(testScheduler)
        val f = Fixture().apply { learning = listOf(progress(10)) }
        val valid = f.admission.createRow(HostFixture(worker).record(), 1)
        val bad = valid.copy(engineState = byteArrayOf(0))
        f.active = bad
        try { f.repository(worker).authorizeNew(Difficulty.EASY, null); fail("Corrupt active row ignored") }
        catch (_: IOException) { }
        assertSame(bad, f.active)
        assertEquals(0, f.writes)
    }

    @Test fun storageErrorRemainsErrorInsteadOfAZeroProgressFallback() = runTest {
        val f = Fixture()
        val original = IOException("private SQL failure")
        f.readFailure = original
        try { f.repository(StandardTestDispatcher(testScheduler)).loadFacts(); fail("Read failure hidden") }
        catch (failure: IOException) { assertTrue(generateSequence<Throwable>(failure) { it.cause }.any { it === original }) }
    }

    @Test fun failedRestoreStillRetiresOldEntryOwner() = runTest {
        val f = Fixture().apply { learning = listOf(progress(10)) }
        val old = f.repository(StandardTestDispatcher(testScheduler))
        try { f.gate.exclusive(replacing = true) { throw IOException("restore rollback") } } catch (_: IOException) { }
        try { old.authorizeNew(Difficulty.EASY, null); fail("Retired owner authorized") } catch (_: IOException) { }
        f.repository(StandardTestDispatcher(testScheduler)).authorizeNew(Difficulty.EASY, null)
    }

    @Test fun cancellationIsNotTranslatedToMissingFacts() = runTest {
        val f = Fixture().apply { readFailure = CancellationException("cancelled QA read") }
        try { f.repository(StandardTestDispatcher(testScheduler)).loadFacts(); fail("Cancellation hidden") }
        catch (_: CancellationException) { }
    }

    private suspend fun denied(reason: LanzhouMahjongEntryDenial, operation: suspend () -> Unit) {
        try { operation(); fail("Expected denied admission") }
        catch (failure: LanzhouMahjongEntryDeniedException) { assertEquals(reason, failure.reason) }
    }

    private class Fixture {
        val gate = LanzhouMahjongStorageEpochGate()
        val admission = LanzhouMahjongSessionAdmission { it.nativeState }
        var learning = emptyList<LanzhouMahjongLearningProgressEntity>()
        var active: ActiveGameEntity? = null
        var readFailure: Exception? = null
        var writes = 0
        val learningDao = object : LanzhouMahjongLearningProgressDao {
            override suspend fun readBoundedRows(): List<LanzhouMahjongLearningProgressEntity> {
                readFailure?.let { throw it }; return learning
            }
            override suspend fun upsert(row: LanzhouMahjongLearningProgressEntity) { writes++; error("Admission must not write") }
            override suspend fun deleteAll() { writes++; error("Admission must not delete") }
        }
        val activeDao = object : ActiveGameDao {
            override suspend fun find(gameTypeCode: Int) = active
            override suspend fun upsert(entity: ActiveGameEntity) { writes++; error("Admission must not write") }
            override suspend fun delete(gameTypeCode: Int) { writes++; error("Admission must not delete") }
            override suspend fun deleteAll() { writes++; error("Admission must not delete") }
        }
        fun repository(worker: kotlinx.coroutines.CoroutineDispatcher) = RoomLanzhouMahjongEntryRepository(
            learningDao, activeDao, gate, gate.captureOwner(), admission, worker, { it() })
    }

    companion object { private fun progress(count: Int) = LanzhouMahjongLearningProgressEntity(0, 2, count, 100) }
}
