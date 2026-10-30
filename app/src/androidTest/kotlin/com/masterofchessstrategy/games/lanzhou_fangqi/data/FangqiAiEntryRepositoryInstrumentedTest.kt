package com.masterofchessstrategy.games.lanzhou_fangqi.data

import com.masterofchessstrategy.data.MatchOutcomeEntity
import com.masterofchessstrategy.engine.Difficulty
import com.masterofchessstrategy.games.lanzhou_fangqi.*
import com.masterofchessstrategy.games.lanzhou_mahjong.data.withOwnedLanzhouDatabase
import org.junit.Assert.*
import org.junit.Test

/** Actual Room/Native on UUID-owned databases. QA lesson rows do not claim user interaction. */
class FangqiAiEntryRepositoryInstrumentedTest {
    @Test fun actualLearningUnlocksOnlyEasyAndGenericOldWinsNeverUnlockMedium() = withOwnedLanzhouDatabase { f ->
        val repository = RoomFangqiRankedRepository(f.db)
        assertTrue(repository.loadFacts().eligibility.conditions.none { it.isUnlocked })
        for (id in FangqiLearningCatalog.lessonIds) f.db.fangqiLearningProgressDao()
            .insert(FangqiLearningProgressEntity("lesson:$id", 1, 1))
        repeat(20) { f.db.matchOutcomeDao().insert(MatchOutcomeEntity("old-$it", 4, 1, 0, 0, 1, 2)) }
        val facts = repository.loadFacts()
        assertTrue(facts.eligibility.condition(Difficulty.EASY).isUnlocked)
        assertFalse(facts.eligibility.condition(Difficulty.MEDIUM).isUnlocked)
        assertEquals(0, facts.eligibility.condition(Difficulty.MEDIUM).qualifyingWins)
        assertNull(facts.active)
        assertFalse(facts.formalActive)
        assertTrue(f.db.fangqiRankedDao().readStarts().isEmpty())
    }

    @Test fun incompatibleLearningBlocksNewQualificationButPreservesExplicitFormalResume() = withOwnedLanzhouDatabase { f ->
        val (repository, initial) = FangqiRankedRoomFixtures.start(f.db, "keep-formal")
        f.db.openHelper.writableDatabase.execSQL("UPDATE fangqi_learning_progress SET contentVersion = 99 WHERE contentKey = 'lesson:opening'")
        val facts = repository.loadFacts()
        val active = requireNotNull(facts.active)
        assertEquals(FangqiAiBlockReason.LEARNING_INCOMPATIBLE, facts.eligibility.condition(Difficulty.EASY).blockedReason)
        assertTrue(facts.formalActive)
        assertFalse(facts.settledActive)
        assertEquals(initial.sessionId, active.sessionId)
        assertArrayEquals(initial.engineState, active.engineState)
        assertEquals(99, f.db.fangqiLearningProgressDao().readAll().single { it.contentKey == "lesson:opening" }.contentVersion)
    }

    @Test fun actualRedTerminalProjectsOneTrustedWinAndKeepsOriginalTerminalForReplacement() = withOwnedLanzhouDatabase { f ->
        val (repository, initial) = FangqiRankedRoomFixtures.start(f.db, "one-win")
        val terminal = FangqiRankedRoomFixtures.terminal(initial)
        repository.checkpoint(initial, terminal)
        repository.firstTerminal(terminal, 30)
        val facts = repository.loadFacts()
        val active = requireNotNull(facts.active)
        assertTrue(facts.formalActive)
        assertTrue(facts.settledActive)
        assertEquals(1, facts.eligibility.condition(Difficulty.MEDIUM).qualifyingWins)
        assertFalse(facts.eligibility.condition(Difficulty.MEDIUM).isUnlocked)
        assertArrayEquals(terminal.engineState, active.engineState)
        assertEquals(terminal.updatedAtEpochMillis, active.updatedAtEpochMillis)
    }

    @Test fun activityRaceAfterMenuReadCannotOverwriteANewOrdinarySave() = withOwnedLanzhouDatabase { f ->
        for (id in FangqiLearningCatalog.lessonIds) f.db.fangqiLearningProgressDao()
            .insert(FangqiLearningProgressEntity("lesson:$id", 1, 1))
        val repository = RoomFangqiRankedRepository(f.db)
        assertNull(repository.loadFacts().active)
        RoomFangqiSessionStore(f.db).save(FangqiStoredSession(FangqiStoredMode.LOCAL_TWO_PLAYER, null,
            FangqiRankedLedger.standardInitialHistory().bytes, "racing-local", 10))
        val original = f.db.activeGameDao().find(4)
        assertTrue(runCatching { repository.create(FangqiRankedRoomFixtures.creation("rejected-new"), null) }.isFailure)
        assertEquals(original, f.db.activeGameDao().find(4))
        assertTrue(f.db.fangqiRankedDao().readStarts().isEmpty())
    }

    @Test fun explicitLeaseOwnerCannotRefreshItsEpochAfterReplacement() = withOwnedLanzhouDatabase { f ->
        val gate = FangqiStorageEpochGate.forDatabase(f.db)
        val owner = gate.captureOwner()
        val repository = RoomFangqiRankedRepository(f.db, gate, owner)
        gate.exclusive(true) { }
        assertEquals(0L, repository.ownerEpoch)
        assertEquals(1L, repository.epoch.value)
        assertTrue(runCatching { repository.loadFacts() }.isFailure)
        assertNull(RoomFangqiRankedRepository(f.db).loadFacts().active)
        assertThrows(IllegalArgumentException::class.java) { RoomFangqiRankedRepository(f.db, FangqiStorageEpochGate()) }
    }

    @Test fun boundFormalWriterResumesAndConfirmsDeletedTerminalWithoutResurrectingRecord() = withOwnedLanzhouDatabase { f ->
        val (repository, initial) = FangqiRankedRoomFixtures.start(f.db, "deleted-resume")
        val terminal = FangqiRankedRoomFixtures.terminal(initial)
        repository.checkpoint(initial, terminal)
        val first = repository.firstTerminal(terminal, 30)
        repository.deleteRecord(initial.sessionId, 40)
        val store = RankedFangqiSessionStore(repository, initial.sessionId) { 100L }
        val loaded = store.load()
        assertArrayEquals(terminal.engineState, loaded.engineState)
        store.save(loaded)
        store.save(loaded)
        assertNull(f.db.gameRecordDao().find(initial.sessionId))
        val verified = repository.load()
        assertEquals(first, verified.settlements.getValue(initial.sessionId).receipt)
        assertEquals(40L, verified.settlements.getValue(initial.sessionId).recordDeletedAtEpochMillis)
        assertEquals(1, verified.wins.winsByDifficulty[Difficulty.EASY])
    }
}
