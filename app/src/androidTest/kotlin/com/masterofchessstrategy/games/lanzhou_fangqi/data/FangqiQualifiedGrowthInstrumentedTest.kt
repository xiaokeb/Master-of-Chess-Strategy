package com.masterofchessstrategy.games.lanzhou_fangqi.data

import com.masterofchessstrategy.data.*
import com.masterofchessstrategy.engine.Difficulty
import com.masterofchessstrategy.engine.GameType
import com.masterofchessstrategy.games.lanzhou_mahjong.data.withOwnedLanzhouDatabase
import org.junit.Assert.*
import org.junit.Test

/** Real Native and UUID disk Room; no default app data, fabricated trusted wins or extra rewards. */
class FangqiQualifiedGrowthInstrumentedTest {
    @Test fun realRoomHistoryConverterBoundsFailureIsUnavailableWithoutErasingSource() = withOwnedLanzhouDatabase { f ->
        FangqiRankedRoomFixtures.start(f.db, "growth-empty-blob")
        f.db.openHelper.writableDatabase.execSQL("UPDATE fangqi_ranked_starts SET initialHistory = X'' WHERE sessionId = 'growth-empty-blob'")
        assertSame(LoadMatchStatisticsResult.Incompatible, RoomMatchStatisticsRepository(f.db).load())
        f.db.openHelper.readableDatabase.query("SELECT length(initialHistory) FROM fangqi_ranked_starts WHERE sessionId = 'growth-empty-blob'").use {
            assertTrue(it.moveToFirst())
            assertEquals(0, it.getInt(0))
        }
    }

    @Test fun sourceFreeOldFangqiWinsAreRetainedButDoNotGrantGrowth() = withOwnedLanzhouDatabase { f ->
        repeat(20) { f.db.matchOutcomeDao().insert(MatchOutcomeEntity("old-fq-$it", 4, 1, 0, 0, 1, 1)) }
        f.db.matchOutcomeDao().insert(MatchOutcomeEntity("old-xq", 0, 1, 1, 0, 1, 1))
        val stats = statistics(f.db)
        assertEquals(1, stats.totalWins)
        assertEquals(20, stats.score)
        assertEquals(0, stats.winsAt(GameType.LANZHOU_SQUARE_CHESS, Difficulty.EASY))
        assertEquals(21, f.db.matchOutcomeDao().listAll().size)
        assertTrue(f.db.fangqiRankedDao().readStarts().isEmpty())
        assertTrue(f.db.fangqiRankedDao().readSettlements().isEmpty())
    }

    @Test fun actualFirstRedWinAddsExactlyOnceAndSurvivesDatabaseReopen() = withOwnedLanzhouDatabase { f ->
        val (repository, initial) = FangqiRankedRoomFixtures.start(f.db, "growth-red")
        val terminal = FangqiRankedRoomFixtures.terminal(initial)
        repository.checkpoint(initial, terminal)
        val first = repository.firstTerminal(terminal, 30)
        assertEquals(first, repository.firstTerminal(terminal, 100))
        repeat(2) { assertRedWin(statistics(f.db)) }
        assertEquals(1, f.db.matchOutcomeDao().listAll().size)
        f.reopen()
        assertRedWin(statistics(f.db))
        assertEquals(30L, RoomFangqiRankedRepository(f.db).load()
            .settlements.getValue(initial.sessionId).receipt.settledAtEpochMillis)
    }

    @Test fun actualBlackWinCountsOneLossAndUsesExistingDifficultyPenalty() = withOwnedLanzhouDatabase { f ->
        val (repository, initial) = FangqiRankedRoomFixtures.start(f.db, "growth-black")
        val terminal = FangqiRankedRoomFixtures.terminal(initial, redWin = false)
        repository.checkpoint(initial, terminal)
        repository.firstTerminal(terminal, 30)
        val stats = statistics(f.db)
        assertEquals(1, stats.completedMatches)
        assertEquals(0, stats.totalWins)
        assertEquals(1, stats.totalLosses)
        assertEquals(-20, stats.score)
        assertEquals(0, stats.netStars)
        assertEquals(1, stats.byGame.getValue(GameType.LANZHOU_SQUARE_CHESS).losses)
    }

    @Test fun explicitRecordDeletionKeepsTheFirstGrowthReceiptAndNeverRecreatesRecord() = withOwnedLanzhouDatabase { f ->
        val (repository, initial) = FangqiRankedRoomFixtures.start(f.db, "growth-deleted")
        val terminal = FangqiRankedRoomFixtures.terminal(initial)
        repository.checkpoint(initial, terminal)
        val first = repository.firstTerminal(terminal, 30)
        repository.deleteRecord(initial.sessionId, 40)
        assertRedWin(statistics(f.db))
        f.reopen()
        assertRedWin(statistics(f.db))
        assertNull(f.db.gameRecordDao().find(initial.sessionId))
        val stored = RoomFangqiRankedRepository(f.db).load().settlements.getValue(initial.sessionId)
        assertEquals(first, stored.receipt)
        assertEquals(40L, stored.recordDeletedAtEpochMillis)
    }

    @Test fun missingUnmarkedCompanionIsUnavailableAndDoesNotRepairOrEraseEvidence() = withOwnedLanzhouDatabase { f ->
        val (repository, initial) = FangqiRankedRoomFixtures.start(f.db, "growth-corrupt")
        val terminal = FangqiRankedRoomFixtures.terminal(initial)
        repository.checkpoint(initial, terminal)
        repository.firstTerminal(terminal, 30)
        f.db.openHelper.writableDatabase.execSQL("DELETE FROM game_records WHERE recordId = 'growth-corrupt'")
        assertSame(LoadMatchStatisticsResult.Incompatible, RoomMatchStatisticsRepository(f.db).load())
        assertEquals(1, f.db.matchOutcomeDao().listAll().size)
        assertEquals(1, f.db.fangqiRankedDao().readStarts().size)
        assertEquals(1, f.db.fangqiRankedDao().readSettlements().size)
        assertNull(f.db.gameRecordDao().find(initial.sessionId))
    }

    @Test fun checkpointAndFailedSettlementGrantNothingUntilAtomicRetryConfirmsOutcome() = withOwnedLanzhouDatabase { f ->
        val (repository, initial) = FangqiRankedRoomFixtures.start(f.db, "growth-retry")
        val terminal = FangqiRankedRoomFixtures.terminal(initial)
        repository.checkpoint(initial, terminal)
        f.db.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_growth_receipt BEFORE INSERT ON fangqi_ranked_settlements BEGIN SELECT RAISE(ABORT, 'owned growth fault'); END")
        try {
            repository.firstTerminal(terminal, 30)
            fail("SQL fault was not exercised")
        } catch (failure: RuntimeException) {
            assertTrue(failure.message.orEmpty().contains("owned growth fault"))
        }
        assertEquals(0, statistics(f.db).completedMatches)
        assertTrue(f.db.matchOutcomeDao().listAll().isEmpty())
        f.db.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_growth_receipt")
        repository.firstTerminal(terminal, 30)
        assertRedWin(statistics(f.db))
    }

    private suspend fun statistics(db: MocsDatabase) =
        (RoomMatchStatisticsRepository(db).load() as LoadMatchStatisticsResult.Loaded).statistics

    private fun assertRedWin(stats: PlayerStatistics) {
        assertEquals(1, stats.completedMatches)
        assertEquals(1, stats.totalWins)
        assertEquals(0, stats.totalLosses)
        assertEquals(10, stats.score)
        assertEquals(1, stats.netStars)
        assertEquals(1, stats.winsAt(GameType.LANZHOU_SQUARE_CHESS, Difficulty.EASY))
    }
}
