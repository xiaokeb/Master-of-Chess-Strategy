package com.masterofchessstrategy.games.lanzhou_fangqi.data

import com.masterofchessstrategy.data.*
import com.masterofchessstrategy.engine.Difficulty
import com.masterofchessstrategy.games.go.data.GoProgressBackupAdapter
import com.masterofchessstrategy.games.lanzhou_fangqi.*
import com.masterofchessstrategy.games.lanzhou_mahjong.data.withOwnedLanzhouDatabase
import org.junit.Assert.*
import org.junit.Test

/** Production factory, Native admission and real SQLite; only UUID-owned test databases are replaced. */
class FangqiRankedBackupInstrumentedTest {
    private fun registry() = GameBackupRegistry(listOf(FangqiBackupAdapter(), FangqiLearningBackupAdapter(),
        FangqiRankedStartBackupAdapter(), FangqiRankedSettlementBackupAdapter(), FangqiRecordBackupAdapter(),
        // Production export prepares even empty legacy Go progress in the isolated fixture.
        GoProgressBackupAdapter()))

    @Test fun productionRoundTripRestoresAllFiveFamiliesAndRetiresOldOwner() = withOwnedLanzhouDatabase { f ->
        val (owner, initial) = FangqiRankedRoomFixtures.start(f.db)
        val terminal = FangqiRankedRoomFixtures.terminal(initial)
        owner.checkpoint(initial, terminal)
        val receipt = owner.firstTerminal(terminal, 30)
        val backup = f.repository().export(40)
        val snapshot = LocalDataBackupCodec.decode(backup, registry())
        assertTrue(snapshot.gameRecords.isEmpty()) // Fangqi bytes never enter the Xiangqi core RECORD segment.
        assertTrue(snapshot.gameExtensions.any { it.kind == FangqiRecordBackupAdapter.KIND })
        val summary = f.repository().restore(backup)
        assertEquals(1, summary.activeSessionCount)
        assertEquals(1, summary.matchOutcomeCount)
        assertEquals(1, summary.gameRecordCount)
        assertTrue(runCatching { owner.load() }.isFailure)
        val restored = RoomFangqiRankedRepository(f.db).load()
        assertEquals(receipt, restored.settlements[initial.sessionId]?.receipt)
        assertEquals(1, restored.wins.winsByDifficulty[Difficulty.EASY])
        assertArrayEquals(terminal.engineState, restored.active?.engineState)
        f.reopen()
        assertEquals(receipt, RoomFangqiRankedRepository(f.db).load().settlements[initial.sessionId]?.receipt)
    }

    @Test fun deletedRecordRemainsDeletedAfterRestoreAndTerminalRetry() = withOwnedLanzhouDatabase { f ->
        val (owner, initial) = FangqiRankedRoomFixtures.start(f.db)
        val terminal = FangqiRankedRoomFixtures.terminal(initial)
        owner.checkpoint(initial, terminal)
        val receipt = owner.firstTerminal(terminal, 30)
        owner.deleteRecord(initial.sessionId, 40)
        val backup = f.repository().export(50)
        assertFalse(LocalDataBackupCodec.decode(backup, registry()).gameExtensions.any { it.kind == FangqiRecordBackupAdapter.KIND })
        assertEquals(0, f.repository().restore(backup).gameRecordCount)
        val next = RoomFangqiRankedRepository(f.db)
        assertEquals(receipt, next.firstTerminal(terminal, 99))
        assertNull(f.db.gameRecordDao().find(initial.sessionId))
        assertEquals(40L, next.load().settlements[initial.sessionId]?.recordDeletedAtEpochMillis)
        assertEquals(1, next.load().wins.winsByDifficulty[Difficulty.EASY])
    }

    @Test fun missingOrConflictingCompanionsRejectBeforeClearAndKeepDiskFacts() = withOwnedLanzhouDatabase { f ->
        val (owner, initial) = FangqiRankedRoomFixtures.start(f.db)
        val terminal = FangqiRankedRoomFixtures.terminal(initial)
        owner.checkpoint(initial, terminal)
        owner.firstTerminal(terminal, 30)
        val registry = registry()
        val good = LocalDataBackupCodec.decode(f.repository().export(40), registry)
        val start = f.db.fangqiRankedDao().findStart(initial.sessionId)
        val receipt = f.db.fangqiRankedDao().findSettlement(initial.sessionId)
        val record = f.db.gameRecordDao().find(initial.sessionId)
        val outcome = f.db.matchOutcomeDao().find(initial.sessionId)
        val variants = listOf(
            good.copy(gameExtensions = good.gameExtensions.filterNot { it.kind == FangqiRankedBackupWire.START }),
            good.copy(gameExtensions = good.gameExtensions.filterNot { it.kind == FangqiRankedBackupWire.SETTLEMENT }),
            good.copy(gameExtensions = good.gameExtensions.filterNot { it.kind == FangqiRecordBackupAdapter.KIND }),
            good.copy(matchOutcomes = emptyList()),
            good.copy(gameExtensions = good.gameExtensions.map { wire ->
                if (wire.kind != FangqiRecordBackupAdapter.KIND) wire else GameBackupRecord(wire.kind,
                    wire.fields.toMutableList().also { it[5] = "2" })
            }),
        )
        for (invalid in variants) {
            // Canonical wire/checksum remain valid; complete Native/companion validation must reject it.
            val bytes = LocalDataBackupCodec.encode(invalid, registry)
            val oldOwner = RoomFangqiRankedRepository(f.db)
            assertTrue(runCatching { f.repository().restore(bytes) }.isFailure)
            assertTrue(runCatching { oldOwner.load() }.isFailure)
            assertEquals(start, f.db.fangqiRankedDao().findStart(initial.sessionId))
            assertEquals(receipt, f.db.fangqiRankedDao().findSettlement(initial.sessionId))
            assertEquals(record, f.db.gameRecordDao().find(initial.sessionId))
            assertEquals(outcome, f.db.matchOutcomeDao().find(initial.sessionId))
            assertEquals(1, RoomFangqiRankedRepository(f.db).load().wins.winsByDifficulty[Difficulty.EASY])
        }
    }

    @Test fun restoreWriteFailureRollsBackClearAndAllPreparedFamilies() = withOwnedLanzhouDatabase { f ->
        val (owner, initial) = FangqiRankedRoomFixtures.start(f.db)
        val terminal = FangqiRankedRoomFixtures.terminal(initial)
        owner.checkpoint(initial, terminal)
        owner.firstTerminal(terminal, 30)
        val backup = f.repository().export(40)
        val old = f.db.gameRecordDao().find(initial.sessionId)
        f.db.openHelper.writableDatabase.execSQL("CREATE TRIGGER qa_fail_ranked_restore BEFORE INSERT ON fangqi_ranked_settlements BEGIN SELECT RAISE(ABORT, 'qa restore failure'); END")
        try {
            assertTrue(runCatching { f.repository().restore(backup) }.isFailure)
            assertTrue(runCatching { owner.load() }.isFailure)
            assertEquals(old, f.db.gameRecordDao().find(initial.sessionId))
            assertNotNull(f.db.fangqiRankedDao().findStart(initial.sessionId))
            assertNotNull(f.db.matchOutcomeDao().find(initial.sessionId))
            assertEquals(1, RoomFangqiRankedRepository(f.db).load().wins.winsByDifficulty[Difficulty.EASY])
        } finally { f.db.openHelper.writableDatabase.execSQL("DROP TRIGGER qa_fail_ranked_restore") }
    }

    @Test fun independentLegacyRecordRoundTripsWithoutGrantingFormalWins() = withOwnedLanzhouDatabase { f ->
        val (owner, initial) = FangqiRankedRoomFixtures.start(f.db)
        val terminal = FangqiRankedRoomFixtures.terminal(initial, redWin = false)
        owner.checkpoint(initial, terminal)
        owner.firstTerminal(terminal, 30)
        val legacyId = "旧棋谱|一"
        val legacy = requireNotNull(f.db.gameRecordDao().find(initial.sessionId)).copy(recordId = legacyId,
            engineFormatVersion = 2, engineState = byteArrayOf(1, 2, 3))
        f.db.gameRecordDao().insert(legacy)
        val backup = f.repository().export(40)
        assertEquals(2, f.repository().restore(backup).gameRecordCount)
        assertEquals(legacy, f.db.gameRecordDao().find(legacyId))
        val verified = RoomFangqiRankedRepository(f.db).load()
        assertEquals(1, verified.creations.size)
        assertEquals(1, verified.settlements.size)
        assertEquals(0, verified.wins.winsByDifficulty[Difficulty.EASY])
    }
}
