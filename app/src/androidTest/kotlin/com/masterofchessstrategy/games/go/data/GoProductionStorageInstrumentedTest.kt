package com.masterofchessstrategy.games.go.data

import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.masterofchessstrategy.data.*
import com.masterofchessstrategy.endgame.ChineseChessEndgamePackParser
import com.masterofchessstrategy.engine.*
import com.masterofchessstrategy.games.go.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException

/** Real Native/Room and the production gate/repository wrappers; never clears application data. */
@RunWith(AndroidJUnit4::class)
class GoProductionStorageInstrumentedTest {
    @Test fun restoreRetiresOldSessionLearningAndEndgameWritersAndFreshOwnersReadTheReplacement() = runBlocking {
        withDatabase { database ->
            val gate = GoStorageEpochGate()
            val sessions = GatedGoSessionRepository(RoomGoSessionRepository(database), gate)
            val learning = GatedGoLearningRepository(RoomGoLearningProgressRepository(database), gate)
            val endgames = GatedGoEndgameRepository(RoomGoEndgameProgressRepository(database), gate)
            val saved = terminal()
            sessions.save(saved)
            learning.save(GoLearningProgress(GoLearningLibrary.lessons.map { it.id }.toSet()))
            endgames.complete(GoEndgameLibrary.puzzles.first().id)
            val registry = registry()
            backup(database, gate, registry).restore(LocalDataBackupCodec.encode(LocalDataSnapshot(5), registry))
            rejected { sessions.save(saved) }
            rejected { learning.save(GoLearningProgress(GoLearningLibrary.lessons.map { it.id }.toSet())) }
            rejected { endgames.complete(GoEndgameLibrary.puzzles.first().id) }
            assertNull(GatedGoSessionRepository(RoomGoSessionRepository(database), gate).load())
            assertTrue(GatedGoLearningRepository(RoomGoLearningProgressRepository(database), gate).load().completedLessonIds.isEmpty())
            assertTrue(GatedGoEndgameRepository(RoomGoEndgameProgressRepository(database), gate).load().completedIds.isEmpty())
            assertTrue(database.gameRecordDao().listAll().isEmpty())
            assertTrue(database.goRecordSourceDao().listAll().isEmpty())
        }
    }

    @Test fun oldActualTutorialViewModelCannotUnionItsCompletedSetBackAfterRestore() = runBlocking {
        withDatabase { database ->
            val gate = GoStorageEpochGate()
            val repository = GatedGoLearningRepository(RoomGoLearningProgressRepository(database), gate)
            repository.save(GoLearningProgress(GoLearningLibrary.lessons.map { it.id }.toSet()))
            val store = ViewModelStore()
            lateinit var model: GoLearningViewModel
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            instrumentation.runOnMainSync { model = GoLearningViewModel(repository); store.put("old-tutorial", model) }
            try {
                withTimeout(15_000) { model.state.first { !it.loading && it.exercise != null } }
                assertTrue(model.state.value.tutorialCompleted)
                val registry = registry()
                backup(database, gate, registry).restore(LocalDataBackupCodec.encode(LocalDataSnapshot(5), registry))
                instrumentation.runOnMainSync { model.selectLesson("connection") }
                withTimeout(15_000) { model.state.first { it.needsSave } }
                assertFalse(model.state.value.tutorialCompleted)
                assertFalse(model.state.value.canOperate)
                assertTrue(model.state.value.error!!.contains("数据已替换"))
                assertNull(database.goProgressDao().find(GameType.GO.code, GoProgressKind.LEARNING.code))
            } finally { instrumentation.runOnMainSync { store.clear() } }
        }
    }

    @Test fun storedRecordLoaderAdmitsRealCompletionAndPreservesUnknownHistoricalSources() = runBlocking {
        withDatabase { database ->
            val session = terminal()
            RoomGoSessionRepository(database).save(session)
            val loader = GoStoredRecordLoader(database)
            val loaded = loader.load(session.identity.recordId)
            assertEquals(session.identity, loaded.source)
            assertEquals(GoSessionOrigin.SGF_IMPORT, loaded.origin)
            val original = loaded.record.engineState
            loaded.record.engineState.fill(0)
            assertArrayEquals(original, loader.load(session.identity.recordId).record.engineState)
            val legacyId = "old-go-without-source"
            val row = database.gameRecordDao().find(session.identity.recordId)!!
            database.gameRecordDao().insert(row.copy(recordId = legacyId))
            val legacy = loader.load(legacyId)
            assertNull(legacy.source)
            assertEquals(GoSessionOrigin.LEGACY_UNKNOWN, legacy.origin)
            assertTrue(database.matchOutcomeDao().listAll().isEmpty())
        }
    }

    @Test fun forgedOngoingArchiveAndCorruptProvenanceAreRejectedWithoutDeletingTheirRows() = runBlocking {
        withDatabase { database ->
            val session = terminal()
            RoomGoSessionRepository(database).save(session)
            database.openHelper.writableDatabase.execSQL("UPDATE go_record_sources SET originCode = 99")
            val loader = GoStoredRecordLoader(database)
            try { loader.load(session.identity.recordId); fail("bad source must fail") }
            catch (_: IllegalArgumentException) { }
            assertEquals(99, database.goRecordSourceDao().find(session.identity.recordId)!!.originCode)
            NativeGoEngine(9).use { engine ->
                val forged = GameRecord("forged-go-not-finished", GameType.GO, StoredGameMode.LOCAL_TWO_PLAYER,
                    null, GameResult.DRAW, engine.serialize(), 0, completedAtEpochMillis = 1)
                database.gameRecordDao().insert(forged.toGameRecordEntity())
                rejected { loader.load(forged.recordId) }
                assertArrayEquals(forged.engineState, database.gameRecordDao().find(forged.recordId)!!.engineState)
            }
        }
    }

    private fun terminal(): GoSavedSession = NativeGoEngine(9).use { engine ->
        repeat(2) { assertEquals(ActionResult.Accepted, engine.apply(GoAction.Pass)) }
        val revision = engine.scoringState().proposalRevision
        assertEquals(ActionResult.Accepted, engine.apply(GoAction.ConfirmScore(GoPlayer.BLACK, revision)))
        assertEquals(ActionResult.Accepted, engine.apply(GoAction.ConfirmScore(GoPlayer.WHITE, revision)))
        GoSavedSession(9, engine.serialize(), GoScoreDisplayMode.STONES,
            identity = GoSessionIdentity.create(GoSessionOrigin.SGF_IMPORT))
    }
    private fun registry() = GameBackupRegistry(listOf(GoLocalSessionBackupAdapter(), GoLocalRecordBackupAdapter(), GoProgressBackupAdapter()))
    private fun backup(database: MocsDatabase, gate: GoStorageEpochGate, registry: GameBackupRegistry): LocalDataBackupRepository =
        GatedGoBackupRepository(RoomLocalDataBackupRepository(database, ChineseChessEndgamePackParser.loadBundled(
            InstrumentationRegistry.getInstrumentation().targetContext.assets), { false }, registry), gate,
            GoLocalBackupPreparation(database, GoLocalLegacySource { null }, GoLearningLegacySource { GoLearningProgress() },
                GoEndgameLegacySource { GoEndgameProgress() }, GoUnsupportedBackupSourceGuard { }))
    private suspend fun withDatabase(block: suspend (MocsDatabase) -> Unit) {
        val database = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext,
            MocsDatabase::class.java).build()
        try { block(database) } finally { database.close() }
    }
    private suspend fun rejected(block: suspend () -> Unit) {
        try { block(); fail("stale or invalid data must fail") } catch (_: IOException) { }
    }
}
