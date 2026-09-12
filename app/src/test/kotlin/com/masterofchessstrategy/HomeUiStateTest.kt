package com.masterofchessstrategy

import com.masterofchessstrategy.engine.NativeEngineStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class HomeUiStateTest {
    @Test
    fun availableEngineShowsReadyStateAndProtocol() {
        val state = HomeUiState.from(
            NativeEngineStatus.Available("MasterofChessStrategy Engine/1"),
        )

        assertEquals("原生引擎已就绪", state.engineTitle)
        assertEquals("MasterofChessStrategy Engine/1", state.engineDetail)
    }

    @Test
    fun unavailableEngineShowsOnlyStableDiagnostic() {
        val state = HomeUiState.from(
            NativeEngineStatus.Unavailable("Native library could not be loaded"),
        )

        assertEquals("原生引擎不可用", state.engineTitle)
        assertEquals("Native library could not be loaded", state.engineDetail)
        assertFalse(state.engineDetail.contains(":\\"))
    }
}
