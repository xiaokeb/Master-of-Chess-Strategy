package com.masterofchessstrategy.games.tiger_goat.data

import com.masterofchessstrategy.engine.GameType
import com.masterofchessstrategy.engine.games.tiger_goat.NativeTigerGoatSession
import com.masterofchessstrategy.engine.games.tiger_goat.TigerGoatActionKind
import com.masterofchessstrategy.games.lanzhou_mahjong.data.withOwnedLanzhouDatabase
import com.masterofchessstrategy.games.tiger_goat.RoomTigerGoatSessionStore
import com.masterofchessstrategy.games.tiger_goat.TigerGoatLoadResult
import com.masterofchessstrategy.games.tiger_goat.TigerGoatSavedSession
import com.masterofchessstrategy.games.tiger_goat.TigerGoatSessionStore
import com.masterofchessstrategy.games.tiger_goat.TigerGoatTutorialProgress
import com.masterofchessstrategy.games.tiger_goat.ai.TigerGoatFormalCheckpoint
import com.masterofchessstrategy.games.tiger_goat.ai.TigerGoatFormalConfig
import com.masterofchessstrategy.games.tiger_goat.ai.TigerGoatFormalLoadResult
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import org.junit.Assert.*
import org.junit.Test

/** Production full-backup composition with real Native and UUID-owned disk Room only. */
class TigerGoatStorageProductionInstrumentedTest {
    @Test fun modeChangeRequiresQualificationAndRetiresOnlyTheReplacedOwners() = withOwnedLanzhouDatabase { f ->
        val gate = TigerGoatStorageEpochGate.forDatabase(f.db)
        val dao = f.db.activeGameDao()
        val oldLocal = GatedTigerGoatSessionStore(RoomTigerGoatSessionStore(dao), gate)
        val firstLocal = saved(1)
        oldLocal.save(firstLocal)
        val config = TigerGoatFormalConfig(humanSide = 2)
        val change = TigerGoatModeChange(f.db)
        assertTrue(runCatching { change.prepare(config) }.isFailure)
        for (index in 0..3) TigerGoatTutorialProgress(f.db).complete(index)
        val stale = change.prepare(config)
        assertTrue(stale.replacesSavedGame)
        val latestLocal = saved(2)
        oldLocal.save(latestLocal)
        assertTrue(runCatching { change.start(stale) }.isFailure)
        assertSaved(latestLocal, oldLocal.load())

        val confirmed = change.prepare(config)
        val epoch = gate.epoch.value
        f.db.openHelper.writableDatabase.execSQL("""
            CREATE TRIGGER reject_mode_change BEFORE INSERT ON active_games
            WHEN NEW.gameTypeCode = 5 BEGIN SELECT RAISE(ABORT, 'owned mode failure'); END
        """.trimIndent())
        try {
            assertTrue(runCatching { change.start(confirmed) }.isFailure)
            assertEquals(epoch, gate.epoch.value)
            assertSaved(latestLocal, oldLocal.load())
        } finally { f.db.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_mode_change") }
        change.start(confirmed)
        assertTrue(runCatching { oldLocal.load() }.isFailure)
        val formal = roomTigerGoatFormalStore(f.db)
        val checkpoint = (formal.load() as TigerGoatFormalLoadResult.Loaded).checkpoint
        assertEquals(config, checkpoint.config)
        assertEquals(0, checkpoint.actionCount)
        val reverse = TigerGoatModeChange(f.db)
        reverse.start(reverse.prepare(null))
        assertFalse(formal.isCurrent)
        assertTrue(runCatching { oldLocal.save(firstLocal) }.isFailure)
        f.reopen()
        val newLocal = RoomTigerGoatSessionStore(f.db.activeGameDao())
        val restored = (newLocal.load() as TigerGoatLoadResult.Loaded).saved
        assertEquals(0, restored.actionCount)
        assertNotEquals(firstLocal.id, restored.id)
    }

    @Test fun backupRestoreInvalidatesAnOtherwiseIdenticalModeProposal() = withOwnedLanzhouDatabase { f ->
        val dao = f.db.activeGameDao()
        RoomTigerGoatSessionStore(dao).save(saved(1))
        for (index in 0..3) TigerGoatTutorialProgress(f.db).complete(index)
        val change = TigerGoatModeChange(f.db)
        val proposal = change.prepare(TigerGoatFormalConfig(humanSide = 1))
        val row = dao.find(GameType.TIGER_AND_GOAT.code)
        f.repository().restore(f.repository().export(50))
        assertEquals(row, dao.find(GameType.TIGER_AND_GOAT.code))
        assertTrue(runCatching { change.start(proposal) }.isFailure)
        assertTrue(runCatching { TigerGoatModeChange(f.db).start(proposal) }.isFailure)
        assertEquals(row, dao.find(GameType.TIGER_AND_GOAT.code))
    }

    @Test fun courseProgressRoundTripsAndRejectsSkippingOrWritingAfterRestore() = withOwnedLanzhouDatabase { f ->
        val first = TigerGoatTutorialProgress(f.db)
        assertEquals(0, first.load())
        assertTrue(runCatching { first.complete(1) }.isFailure)
        assertEquals(1, first.complete(0))
        assertEquals(1, first.complete(0))
        val partial = f.repository().export(20)
        for (index in 1..3) assertEquals(index + 1, first.complete(index))
        val completed = f.repository().export(21)
        f.repository().restore(partial)
        denied { first.complete(3) }
        assertEquals(1, TigerGoatTutorialProgress(f.db).load())
        f.repository().restore(completed)
        f.reopen()
        assertEquals(4, TigerGoatTutorialProgress(f.db).load())
        assertNull(f.db.activeGameDao().find(GameType.TIGER_AND_GOAT.code))
    }

    @Test fun formalCheckpointSurvivesProductionBackupAndRejectsAStaleOwner() = withOwnedLanzhouDatabase { f ->
        val first = roomTigerGoatFormalStore(f.db)
        val stale = roomTigerGoatFormalStore(f.db)
        assertEquals(TigerGoatFormalLoadResult.Missing, first.load())
        assertEquals(TigerGoatFormalLoadResult.Missing, stale.load())
        val checkpoint = formal(2, 9)
        first.save(checkpoint)
        assertTrue(runCatching { stale.save(formal(1, 1)) }.isFailure)
        val originalRow = f.db.activeGameDao().find(GameType.TIGER_AND_GOAT.code)
        val backup = f.repository().export(21)
        first.save(formal(0, 10))
        assertEquals(1, f.repository().restore(backup).activeSessionCount)
        assertFalse(first.isCurrent)
        denied { first.save(formal(0, 11)) }
        f.reopen()
        assertEquals(originalRow, f.db.activeGameDao().find(GameType.TIGER_AND_GOAT.code))
        val restored = (roomTigerGoatFormalStore(f.db).load() as TigerGoatFormalLoadResult.Loaded).checkpoint
        assertEquals(checkpoint.sessionId, restored.sessionId)
        assertEquals(9L, restored.revision)
        assertEquals(TigerGoatFormalConfig(humanSide = 2), restored.config)
        assertEquals(2, restored.actionCount)
        assertArrayEquals(checkpoint.copyBytes(), restored.copyBytes())
    }

    @Test fun eachModeRejectsTheOtherModesActiveRow() = withOwnedLanzhouDatabase { f ->
        val dao = f.db.activeGameDao()
        val local = GatedTigerGoatSessionStore(RoomTigerGoatSessionStore(dao), TigerGoatStorageEpochGate.forDatabase(f.db))
        val formalStore = roomTigerGoatFormalStore(f.db)
        val localCheckpoint = saved(1)
        local.save(localCheckpoint)
        assertEquals(TigerGoatFormalLoadResult.LocalGame, formalStore.load())
        assertTrue(runCatching { formalStore.save(formal(2, 1)) }.isFailure)
        assertSaved(localCheckpoint, local.load())

        dao.upsert(TigerGoatFormalActiveRow.to(formal(2, 1)))
        val before = dao.find(GameType.TIGER_AND_GOAT.code)
        assertEquals(TigerGoatLoadResult.Incompatible, local.load())
        assertTrue(runCatching { local.save(localCheckpoint) }.isFailure)
        assertEquals(before, dao.find(GameType.TIGER_AND_GOAT.code))
    }

    @Test fun exportKeepsTheFixedOwnerAndItsNativeSessionWritable() = withOwnedLanzhouDatabase { f ->
        val gate = TigerGoatStorageEpochGate.forDatabase(f.db)
        val store = GatedTigerGoatSessionStore(RoomTigerGoatSessionStore(f.db.activeGameDao()), gate)
        val before = saved(1)
        store.save(before)
        f.repository().export(20)
        assertEquals(store.ownerEpoch, gate.epoch.value)
        assertSaved(before, store.load())
        val after = saved(2)
        store.save(after)
        assertSaved(after, store.load())
    }

    @Test fun replacementRestoresExactNativeBytesRetiresTheOldOwnerAndReopensDisk() = withOwnedLanzhouDatabase { f ->
        val gate = TigerGoatStorageEpochGate.forDatabase(f.db)
        val old = GatedTigerGoatSessionStore(RoomTigerGoatSessionStore(f.db.activeGameDao()), gate)
        val original = saved(1)
        old.save(original)
        val backup = f.repository().export(20)
        old.save(saved(2))
        assertEquals(1, f.repository().restore(backup).activeSessionCount)
        assertEquals(old.ownerEpoch + 1, gate.epoch.value)
        denied { old.load() }
        denied { old.save(saved(2)) }
        assertSaved(original, GatedTigerGoatSessionStore(RoomTigerGoatSessionStore(f.db.activeGameDao()), gate).load())
        f.reopen()
        assertSaved(original, GatedTigerGoatSessionStore(RoomTigerGoatSessionStore(f.db.activeGameDao()),
            TigerGoatStorageEpochGate.forDatabase(f.db)).load())
    }

    @Test fun malformedBackupRetiresOldMemoryAndPreservesTheDiskCheckpoint() = withOwnedLanzhouDatabase { f ->
        val gate = TigerGoatStorageEpochGate.forDatabase(f.db)
        val old = GatedTigerGoatSessionStore(RoomTigerGoatSessionStore(f.db.activeGameDao()), gate)
        val kept = saved(2)
        old.save(kept)
        assertTrue(runCatching { f.repository().restore(byteArrayOf(0)) }.isFailure)
        assertEquals(old.ownerEpoch + 1, gate.epoch.value)
        denied { old.load() }
        denied { old.save(saved(0)) }
        assertSaved(kept, GatedTigerGoatSessionStore(RoomTigerGoatSessionStore(f.db.activeGameDao()), gate).load())
    }

    @Test fun failedInsertRollsBackTheWholeReplacementButKeepsTheOldOwnerRetired() = withOwnedLanzhouDatabase { f ->
        val gate = TigerGoatStorageEpochGate.forDatabase(f.db)
        val old = GatedTigerGoatSessionStore(RoomTigerGoatSessionStore(f.db.activeGameDao()), gate)
        old.save(saved(0))
        val backup = f.repository().export(20)
        val kept = saved(2)
        old.save(kept)
        val before = f.db.activeGameDao().find(GameType.TIGER_AND_GOAT.code)
        f.db.openHelper.writableDatabase.execSQL("""
            CREATE TRIGGER reject_tiger_restore BEFORE INSERT ON active_games
            WHEN NEW.gameTypeCode = 5 BEGIN SELECT RAISE(ABORT, 'owned restore failure'); END
        """.trimIndent())
        try {
            assertTrue(runCatching { f.repository().restore(backup) }.isFailure)
            assertEquals(before, f.db.activeGameDao().find(GameType.TIGER_AND_GOAT.code))
            denied { old.load() }
            assertSaved(kept, GatedTigerGoatSessionStore(RoomTigerGoatSessionStore(f.db.activeGameDao()), gate).load())
        } finally { f.db.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_tiger_restore") }
    }

    @Test fun actualFactoryWaitsForAcceptedSaveThenRejectsTheQueuedOldWrite() = withOwnedLanzhouDatabase { f ->
        coroutineScope {
            val gate = TigerGoatStorageEpochGate.forDatabase(f.db)
            val room = RoomTigerGoatSessionStore(f.db.activeGameDao())
            room.save(saved(0))
            val backup = f.repository().export(20)
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val old = GatedTigerGoatSessionStore(paused(room, entered, release), gate)
            val accepted = async(start = CoroutineStart.UNDISPATCHED) { old.save(saved(1)) }
            entered.await()
            try {
                val replacement = async(start = CoroutineStart.UNDISPATCHED) { f.repository().restore(backup) }
                val late = async(start = CoroutineStart.UNDISPATCHED) { runCatching { old.save(saved(2)) }.exceptionOrNull() }
                assertFalse(replacement.isCompleted)
                assertFalse(late.isCompleted)
                assertEquals(old.ownerEpoch, gate.epoch.value)
                release.complete(Unit)
                accepted.await()
                replacement.await()
                assertTrue(late.await() is IOException)
                assertSaved(saved(0), GatedTigerGoatSessionStore(room, gate).load())
            } finally { release.complete(Unit) }
        }
    }

    @Test fun cancellingQueuedProductionReplacementKeepsTheCurrentOwnerUsable() = withOwnedLanzhouDatabase { f ->
        coroutineScope {
            val gate = TigerGoatStorageEpochGate.forDatabase(f.db)
            val room = RoomTigerGoatSessionStore(f.db.activeGameDao())
            room.save(saved(0))
            val backup = f.repository().export(20)
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val old = GatedTigerGoatSessionStore(paused(room, entered, release), gate)
            val accepted = async(start = CoroutineStart.UNDISPATCHED) { old.save(saved(1)) }
            entered.await()
            try {
                val queued = async(start = CoroutineStart.UNDISPATCHED) { f.repository().restore(backup) }
                assertFalse(queued.isCompleted)
                queued.cancelAndJoin()
                assertEquals(old.ownerEpoch, gate.epoch.value)
                release.complete(Unit)
                accepted.await()
                old.save(saved(2))
                assertSaved(saved(2), old.load())
            } finally { release.complete(Unit) }
        }
    }

    private fun paused(delegate: TigerGoatSessionStore, entered: CompletableDeferred<Unit>, release: CompletableDeferred<Unit>) =
        object : TigerGoatSessionStore {
            override suspend fun load() = delegate.load()
            override suspend fun save(saved: TigerGoatSavedSession) {
                entered.complete(Unit)
                release.await()
                delegate.save(saved)
            }
        }

    private fun saved(actions: Int) = NativeTigerGoatSession().use { native ->
        repeat(actions) { assertEquals(0, native.apply(native.legalActions().first { it.kind != TigerGoatActionKind.RESIGN })) }
        TigerGoatSavedSession("owned-tiger", native.serialize(), native.snapshot().actionCount, 10L + actions)
    }

    private fun formal(actions: Int, revision: Long) = saved(actions).let {
        TigerGoatFormalCheckpoint(it.id, revision, TigerGoatFormalConfig(humanSide = 2), it.actionCount, it.updatedAt, it.bytes)
    }

    private fun assertSaved(expected: TigerGoatSavedSession, result: TigerGoatLoadResult) {
        assertTrue(result is TigerGoatLoadResult.Loaded)
        val actual = (result as TigerGoatLoadResult.Loaded).saved
        assertEquals(expected.id, actual.id)
        assertEquals(expected.actionCount, actual.actionCount)
        assertEquals(expected.updatedAt, actual.updatedAt)
        assertArrayEquals(expected.bytes, actual.bytes)
    }

    private suspend fun denied(action: suspend () -> Any?) {
        try { action(); fail("A retired owner was accepted") } catch (_: IOException) { }
    }
}
