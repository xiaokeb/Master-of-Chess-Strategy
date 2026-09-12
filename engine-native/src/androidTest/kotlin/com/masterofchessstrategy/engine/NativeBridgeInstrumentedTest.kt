package com.masterofchessstrategy.engine

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeBridgeInstrumentedTest {
    @Test
    fun bundledLibraryReturnsExpectedProtocol() {
        val status = NativeEngineStatusProvider.check()

        assertTrue(status is NativeEngineStatus.Available)
        assertEquals(
            "MasterofChessStrategy Engine/1",
            (status as NativeEngineStatus.Available).protocol,
        )
    }
}
