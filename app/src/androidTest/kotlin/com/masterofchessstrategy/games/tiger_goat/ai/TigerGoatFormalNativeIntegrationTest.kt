package com.masterofchessstrategy.games.tiger_goat.ai

import com.masterofchessstrategy.engine.games.tiger_goat.NativeTigerGoatSession
import com.masterofchessstrategy.games.tiger_goat.NativeTigerGoatRulesSession
import com.masterofchessstrategy.games.tiger_goat.TigerGoatAiSearchCoordinator
import com.masterofchessstrategy.games.tiger_goat.TigerGoatPhase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TigerGoatFormalNativeIntegrationTest {
    @Test fun nativeGoatReplyIsSavedRestoredAndUndoneWithTheHumanOpening() = runBlocking {
        withContext(Dispatchers.Main) {
            val store = MemoryStore()
            val config = TigerGoatFormalConfig(humanSide = 1)
            fun owner() = TigerGoatFormalGameOwner(store, NativeTigerGoatRulesSession(NativeTigerGoatSession()),
                CoordinatedTigerGoatFormalSearch(TigerGoatAiSearchCoordinator()), config, isQualified = { true })

            val first = owner()
            try {
                first.load()
                assertEquals(0, store.saved?.actionCount)
                first.onPointTap(6)
                assertEquals(1, store.saved?.actionCount)
                assertTrue(first.uiState.value.thinking)
                withTimeout(10_000) {
                    while (store.saved?.actionCount == 1 && first.uiState.value.error == null) {
                        first.advanceAi()
                        delay(10)
                    }
                }
                assertNull(first.uiState.value.error)
                assertEquals(2, store.saved?.actionCount)
                assertFalse(first.uiState.value.thinking)
            } finally {
                first.retire()
            }

            val saved = checkNotNull(store.saved)
            NativeTigerGoatSession().use { native ->
                assertTrue(native.restore(saved.copyBytes()))
                assertEquals(2, native.snapshot().actionCount)
                assertEquals(1, native.snapshot().side)
                assertEquals(13, native.snapshot().reserve)
            }

            val restored = owner()
            try {
                restored.load()
                assertNull(restored.uiState.value.error)
                assertEquals(saved.sessionId, store.saved?.sessionId)
                assertArrayEquals(saved.copyBytes(), store.saved?.copyBytes())
                restored.undo()
                val rewound = checkNotNull(store.saved)
                assertEquals(saved.sessionId, rewound.sessionId)
                assertEquals(saved.revision + 1, rewound.revision)
                assertEquals(0, rewound.actionCount)
                assertEquals(TigerGoatPhase.OPENING_REMOVAL, restored.uiState.value.game?.phase)
            } finally {
                restored.retire()
            }
        }
    }

    private class MemoryStore : TigerGoatFormalSessionStore {
        override val isCurrent = true
        var saved: TigerGoatFormalCheckpoint? = null
            private set
        override suspend fun load() = saved?.let { TigerGoatFormalLoadResult.Loaded(it.copyOwned()) }
            ?: TigerGoatFormalLoadResult.Missing
        override suspend fun save(checkpoint: TigerGoatFormalCheckpoint) {
            saved = checkpoint.copyOwned()
        }
    }
}
