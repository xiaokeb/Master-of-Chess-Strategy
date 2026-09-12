package com.masterofchessstrategy.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class NativeEngineStatusProviderTest {
    @Test
    fun protocolIsMappedToAvailableStatus() {
        val status = checkNativeEngineStatus {
            "MasterofChessStrategy Engine/1"
        }

        assertEquals(
            NativeEngineStatus.Available("MasterofChessStrategy Engine/1"),
            status,
        )
    }

    @Test
    fun linkageFailureIsMappedWithoutLeakingTheException() {
        val status = checkNativeEngineStatus {
            throw UnsatisfiedLinkError("machine-specific details")
        }

        assertEquals(
            NativeEngineStatus.Unavailable("Native library could not be loaded"),
            status,
        )
    }

    @Test
    fun runtimeFailureIsMappedToStableMessage() {
        val status = checkNativeEngineStatus {
            error("machine-specific details")
        }

        assertEquals(
            NativeEngineStatus.Unavailable("Native engine health check failed"),
            status,
        )
    }
}
