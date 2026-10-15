package com.masterofchessstrategy.games.tiger_goat

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.masterofchessstrategy.data.MocsDatabase
import com.masterofchessstrategy.engine.games.tiger_goat.NativeTigerGoatSession
import com.masterofchessstrategy.engine.games.tiger_goat.TigerGoatAction
import com.masterofchessstrategy.engine.games.tiger_goat.TigerGoatActionKind
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class TigerGoatPassRoomInstrumentedTest {
    @Test fun realNativeAdmissionPersistsPassAndRestoresUndoHistory() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), MocsDatabase::class.java).build()
        try {
            val store = RoomTigerGoatSessionStore(database.activeGameDao())
            NativeTigerGoatSession().use { native ->
                assertTrue(native.restore(tigerGoatBlockedGoatsSave(1)))
                assertEquals(0, native.apply(TigerGoatAction(TigerGoatActionKind.PASS_GOAT)))
                val saved = native.serialize()
                assertEquals(2, saved[4].toInt())
                store.save(TigerGoatSavedSession("pass-test", saved, 1, 1))
                val loaded = store.load() as TigerGoatLoadResult.Loaded
                NativeTigerGoatSession().use { restored ->
                    assertTrue(restored.restore(loaded.saved.bytes))
                    assertEquals(1, restored.snapshot().side)
                    assertTrue(restored.undo())
                    assertEquals(TigerGoatAction(TigerGoatActionKind.PASS_GOAT), restored.legalActions().single())
                }
                val invalid = saved.copyOf().also { it[10] = 3 }
                try { store.save(TigerGoatSavedSession("bad", invalid, 1, 1)); fail("Native admission must reject corruption") }
                catch (_: IllegalArgumentException) { }
                assertArrayEquals(saved, (store.load() as TigerGoatLoadResult.Loaded).saved.bytes)
            }
        } finally {
            database.close()
        }
    }
}
