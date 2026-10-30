package com.masterofchessstrategy.games.lanzhou_fangqi.data

import android.database.sqlite.SQLiteException
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.masterofchessstrategy.data.MocsDatabase
import com.masterofchessstrategy.engine.Difficulty
import com.masterofchessstrategy.engine.GameResult
import com.masterofchessstrategy.engine.GameType
import com.masterofchessstrategy.games.lanzhou_fangqi.*
import com.masterofchessstrategy.games.lanzhou_mahjong.data.withOwnedLanzhouDatabase
import java.io.IOException
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real authored-standard-root Native terminal fixtures are QA inputs, not an AI strength result. */
@RunWith(AndroidJUnit4::class)
class FangqiRankedRepositoryInstrumentedTest {
    @Test fun admissionRereadsActualLessonsAndCreationRetryKeepsOriginalIdentityAndTime() = withOwnedLanzhouDatabase { f ->
        val repository = RoomFangqiRankedRepository(f.db)
        val creation = FangqiRankedRoomFixtures.creation("admission")
        rejected { repository.admitNew(creation) }
        assertTrue(f.db.fangqiRankedDao().readStarts().isEmpty())
        assertNull(f.db.activeGameDao().find(4))
        for (key in FangqiLearningCatalog.keys) {
            f.db.fangqiLearningProgressDao().insert(FangqiLearningProgressEntity(key, 1, 1))
        }
        // Full learning never invents the ten prerequisite wins for medium.
        rejected { repository.admitNew(FangqiRankedRoomFixtures.creation("medium", Difficulty.MEDIUM)) }
        val initial = repository.admitNew(creation)
        val row = f.db.activeGameDao().find(4)
        val again = repository.admitNew(creation)
        assertEquals(10L, again.updatedAtEpochMillis)
        assertArrayEquals(initial.engineState, again.engineState)
        assertEquals(row, f.db.activeGameDao().find(4))
        assertEquals(1, f.db.fangqiRankedDao().readStarts().size)
        rejected { repository.admitNew(creation.copy(initial = creation.initial.copy(updatedAtEpochMillis = 11))) }
        rejected { repository.admitNew(FangqiRankedRoomFixtures.creation("another")) }
        assertEquals(row, f.db.activeGameDao().find(4))
    }

    @Test fun failedInitialActiveInsertRollsBackCreationAndExactRequestCanRetry() = withOwnedLanzhouDatabase { f ->
        for (key in FangqiLearningCatalog.keys) f.db.fangqiLearningProgressDao().insert(FangqiLearningProgressEntity(key, 1, 1))
        val repository = RoomFangqiRankedRepository(f.db)
        val creation = FangqiRankedRoomFixtures.creation("create-fault")
        trigger(f.db, "qa_create_fault", "BEFORE INSERT ON active_games")
        try {
            assertTrue(runCatching { repository.admitNew(creation) }.exceptionOrNull() is SQLiteException)
            assertNull(f.db.activeGameDao().find(4))
            assertNull(f.db.fangqiRankedDao().findStart("create-fault"))
        } finally { drop(f.db, "qa_create_fault") }
        repository.admitNew(creation)
        assertNotNull(f.db.fangqiRankedDao().findStart("create-fault"))
    }

    @Test fun actualBlackWinSettlesOnceWithoutAddingHumanWinsOrChangingRetryTime() = withOwnedLanzhouDatabase { f ->
        val (repository, initial) = FangqiRankedRoomFixtures.start(f.db, "black")
        val terminal = FangqiRankedRoomFixtures.terminal(initial, redWin = false)
        assertEquals(53, terminal.acceptedActionCount)
        repository.checkpoint(initial, terminal)
        val first = repository.firstTerminal(terminal, 30)
        assertEquals(GameResult.SECOND_PLAYER_WIN, first.result)
        assertEquals(first, repository.firstTerminal(terminal, 99))
        assertEquals(30L, first.settledAtEpochMillis)
        assertEquals(30L, f.db.matchOutcomeDao().find("black")!!.settledAtEpochMillis)
        assertEquals(30L, f.db.gameRecordDao().find("black")!!.completedAtEpochMillis)
        assertTrue(repository.load().wins.winsByDifficulty.values.all { it == 0 })
        rejected { repository.firstTerminal(copy(terminal, hints = 1), 99) }
        assertEquals(first, repository.load().settlements.getValue("black").receipt)
    }

    @Test fun actualRedWinAddsExactlyOneQualifiedWinAndFavoriteIsIndependent() = withOwnedLanzhouDatabase { f ->
        val (repository, initial) = FangqiRankedRoomFixtures.start(f.db, "red")
        val terminal = FangqiRankedRoomFixtures.terminal(initial)
        assertEquals(50, terminal.acceptedActionCount)
        repository.checkpoint(initial, terminal)
        val receipt = repository.firstTerminal(terminal, 30)
        assertEquals(GameResult.FIRST_PLAYER_WIN, receipt.result)
        repeat(2) { assertEquals(receipt, repository.firstTerminal(terminal, 31)) }
        assertEquals(1, repository.load().wins.winsByDifficulty.getValue(Difficulty.EASY))
        assertEquals(1, f.db.gameRecordDao().setFavorite("red", true))
        val loaded = repository.load()
        assertEquals(receipt, loaded.settlements.getValue("red").receipt)
        val record = f.db.gameRecordDao().find("red")!!
        assertTrue(record.isFavorite)
        assertArrayEquals(terminal.engineState, record.engineState)
        assertEquals(terminal.acceptedActionCount, record.moveCount)
    }

    @Test fun everyFirstSettlementInsertFailureRollsBackAllThreeFamilies() {
        for (table in listOf("match_outcomes", "game_records", "fangqi_ranked_settlements")) {
            withOwnedLanzhouDatabase { f ->
                val (repository, initial) = FangqiRankedRoomFixtures.start(f.db, "settle-fault")
                val terminal = FangqiRankedRoomFixtures.terminal(initial)
                repository.checkpoint(initial, terminal)
                val active = f.db.activeGameDao().find(4)
                val start = f.db.fangqiRankedDao().findStart("settle-fault")
                trigger(f.db, "qa_settle_fault", "BEFORE INSERT ON $table")
                try {
                    assertTrue("Real SQLite fault at $table", runCatching {
                        repository.firstTerminal(terminal, 30)
                    }.exceptionOrNull() is SQLiteException)
                    assertNull(f.db.matchOutcomeDao().find("settle-fault"))
                    assertNull(f.db.gameRecordDao().find("settle-fault"))
                    assertNull(f.db.fangqiRankedDao().findSettlement("settle-fault"))
                    assertEquals(active, f.db.activeGameDao().find(4))
                    assertEquals(start, f.db.fangqiRankedDao().findStart("settle-fault"))
                    assertEquals(0, repository.load().wins.winsByDifficulty.getValue(Difficulty.EASY))
                } finally { drop(f.db, "qa_settle_fault") }
                assertEquals(30L, repository.firstTerminal(terminal, 30).settledAtEpochMillis)
                assertEquals(1, repository.load().wins.winsByDifficulty.getValue(Difficulty.EASY))
            }
        }
    }

    @Test fun explicitDeleteRollsBackOnSqlFaultThenKeepsReceiptAndNeverResurrectsRecord() = withOwnedLanzhouDatabase { f ->
        val (repository, initial) = FangqiRankedRoomFixtures.start(f.db, "deleted")
        val terminal = FangqiRankedRoomFixtures.terminal(initial)
        repository.checkpoint(initial, terminal)
        val receipt = repository.firstTerminal(terminal, 30)
        val record = f.db.gameRecordDao().find("deleted")
        val outcome = f.db.matchOutcomeDao().find("deleted")
        trigger(f.db, "qa_delete_fault", "BEFORE DELETE ON game_records")
        try {
            assertTrue(runCatching { repository.deleteRecord("deleted", 40) }.exceptionOrNull() is SQLiteException)
            assertNull(f.db.fangqiRankedDao().findSettlement("deleted")!!.recordDeletedAtEpochMillis)
            assertEquals(record, f.db.gameRecordDao().find("deleted"))
        } finally { drop(f.db, "qa_delete_fault") }
        assertEquals(40L, repository.deleteRecord("deleted", 40))
        assertEquals(40L, repository.deleteRecord("deleted", 40))
        rejected { repository.deleteRecord("deleted", 41) }
        assertNull(f.db.gameRecordDao().find("deleted"))
        assertEquals(outcome, f.db.matchOutcomeDao().find("deleted"))
        assertEquals(receipt, repository.firstTerminal(terminal, 99))
        assertNull(f.db.gameRecordDao().find("deleted"))
        assertEquals(1, repository.load().wins.winsByDifficulty.getValue(Difficulty.EASY))
        f.reopen()
        val reopened = RoomFangqiRankedRepository(f.db).load()
        assertEquals(40L, reopened.settlements.getValue("deleted").recordDeletedAtEpochMillis)
        assertEquals(receipt, reopened.settlements.getValue("deleted").receipt)
        assertEquals(1, reopened.wins.winsByDifficulty.getValue(Difficulty.EASY))
    }

    @Test fun observedUnsettledTerminalCannotBeUndoneOrReplacedBeforeFirstSettlement() = withOwnedLanzhouDatabase { f ->
        val (repository, initial) = FangqiRankedRoomFixtures.start(f.db, "terminal-locked")
        val terminal = FangqiRankedRoomFixtures.terminal(initial)
        repository.checkpoint(initial, terminal)
        val stored = f.db.activeGameDao().find(4)
        val undo = copy(initial, time = 21, undos = 1)
        rejected { repository.checkpoint(terminal, undo) }
        rejected { repository.admitNew(FangqiRankedRoomFixtures.creation("new-before-settled"), terminal) }
        assertEquals(stored, f.db.activeGameDao().find(4))
        assertNull(f.db.fangqiRankedDao().findSettlement("terminal-locked"))
        assertArrayEquals(terminal.engineState, repository.checkpoint(initial, terminal).engineState)
        repository.firstTerminal(terminal, 30)
    }

    @Test fun staleCheckpointDoesNotWriteAndActualAcknowledgementSurvivesReopen() = withOwnedLanzhouDatabase { f ->
        val (repository, initial) = FangqiRankedRoomFixtures.start(f.db, "cas")
        val terminal = FangqiRankedRoomFixtures.terminal(initial)
        val one = prefix(terminal, 1, 11)
        val two = prefix(terminal, 2, 12)
        repository.checkpoint(initial, one)
        val stored = f.db.activeGameDao().find(4)
        rejected { repository.checkpoint(initial, two) }
        assertEquals(stored, f.db.activeGameDao().find(4))
        assertArrayEquals(one.engineState, repository.checkpoint(initial, one).engineState)
        f.reopen()
        val reopened = RoomFangqiRankedRepository(f.db).load()
        assertEquals(1, reopened.active!!.acceptedActionCount)
        assertArrayEquals(one.engineState, reopened.active!!.engineState)
        assertTrue(reopened.settlements.isEmpty())
    }

    @Test fun nextCreationExplicitlyReplacesOnlySettledOriginalAndKeepsPriorProvenance() = withOwnedLanzhouDatabase { f ->
        val (repository, initial) = FangqiRankedRoomFixtures.start(f.db, "prior")
        val terminal = FangqiRankedRoomFixtures.terminal(initial)
        repository.checkpoint(initial, terminal)
        val receipt = repository.firstTerminal(terminal, 30)
        val next = FangqiRankedRoomFixtures.creation("next", time = 40)
        rejected { repository.admitNew(next) }
        val newInitial = repository.admitNew(next, terminal)
        assertEquals("next", newInitial.sessionId)
        val loaded = repository.load()
        assertEquals(setOf("prior", "next"), loaded.creations.keys)
        assertEquals(receipt, loaded.settlements.getValue("prior").receipt)
        assertNotNull(f.db.matchOutcomeDao().find("prior"))
        assertNotNull(f.db.gameRecordDao().find("prior"))
        assertEquals(1, loaded.wins.winsByDifficulty.getValue(Difficulty.EASY))
    }

    private fun trigger(db: MocsDatabase, name: String, clause: String) {
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER $name $clause BEGIN SELECT RAISE(ABORT, 'owned QA fault'); END")
    }
    private fun drop(db: MocsDatabase, name: String) = db.openHelper.writableDatabase.execSQL("DROP TRIGGER $name")
    private suspend fun rejected(operation: suspend () -> Unit) {
        try { operation(); fail("Invalid request changed ranked storage") } catch (_: IOException) { }
    }
    private fun copy(value: FangqiStoredSession, time: Long = value.updatedAtEpochMillis,
        undos: Int = value.undoUseCount, hints: Int = value.hintUseCount) = FangqiStoredSession(value.mode,
        value.difficulty, value.engineState, value.sessionId, time, value.acceptedActionCount, undos, hints)
    private fun prefix(value: FangqiStoredSession, count: Int, time: Long): FangqiStoredSession {
        val parts = value.engineState.toString(Charsets.US_ASCII).split('|', limit = 6)
        val bytes = (parts.take(4).joinToString("|") + "|$count|" + parts[5].split(';').take(count).joinToString(";"))
            .toByteArray(Charsets.US_ASCII)
        return FangqiStoredSession(value.mode, value.difficulty, bytes, value.sessionId, time, count)
    }
}

/** Shared only by owned-DB QA tests. These histories are generated legal games, not mocked outcomes. */
internal object FangqiRankedRoomFixtures {
    suspend fun start(db: MocsDatabase, id: String = "ranked-qa"): Pair<RoomFangqiRankedRepository, FangqiStoredSession> {
        for (key in FangqiLearningCatalog.keys) db.fangqiLearningProgressDao().insert(FangqiLearningProgressEntity(key, 1, 1))
        val repository = RoomFangqiRankedRepository(db)
        return repository to repository.admitNew(creation(id))
    }

    fun terminal(initial: FangqiStoredSession, redWin: Boolean = true): FangqiStoredSession {
        val color = if (redWin) "red" else "black"
        val bytes = InstrumentationRegistry.getInstrumentation().context.assets
            .open("games/lanzhou_fangqi/ranked/standard-$color-win.mocs-lfq")
            .bufferedReader(Charsets.US_ASCII).use { it.readText().trimEnd('\r', '\n') }.toByteArray(Charsets.US_ASCII)
        val count = FangqiSessionAdmission.inspectHistory(bytes)
        return FangqiStoredSession(initial.mode, initial.difficulty, bytes, initial.sessionId, 20, count,
            initial.undoUseCount, initial.hintUseCount)
    }

    fun creation(id: String, difficulty: Difficulty = Difficulty.EASY, time: Long = 10) = FangqiRankedCreation(1,
        FangqiRankedStartOrigin.NEW_STANDARD_HUMAN_GAME, FangqiRankedPosition(FangqiRankedIdentity(id,
            GameType.LANZHOU_SQUARE_CHESS, FangqiStoredMode.HUMAN_VS_AI, difficulty, 0,
            FangqiRankedLedger.RULES_VERSION, 1), FangqiRankedLedger.standardInitialHistory(), time, 0, 0, 0))
}
