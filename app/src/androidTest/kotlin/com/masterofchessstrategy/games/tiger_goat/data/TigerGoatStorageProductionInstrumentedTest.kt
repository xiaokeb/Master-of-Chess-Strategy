package com.masterofchessstrategy.games.tiger_goat.data

import com.masterofchessstrategy.engine.GameType
import com.masterofchessstrategy.engine.games.tiger_goat.NativeTigerGoatSession
import com.masterofchessstrategy.engine.games.tiger_goat.TigerGoatActionKind
import com.masterofchessstrategy.games.lanzhou_mahjong.data.withOwnedLanzhouDatabase
import com.masterofchessstrategy.games.tiger_goat.RoomTigerGoatSessionStore
import com.masterofchessstrategy.games.tiger_goat.TigerGoatLoadResult
import com.masterofchessstrategy.games.tiger_goat.TigerGoatSavedSession
import com.masterofchessstrategy.games.tiger_goat.TigerGoatSessionStore
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
