package com.masterofchessstrategy.engine

import com.masterofchessstrategy.engine.internal.NativeBindings

/** Result of checking whether the bundled native engine can serve requests. */
sealed interface NativeEngineStatus {
    data class Available(val protocol: String) : NativeEngineStatus

    data class Unavailable(val message: String) : NativeEngineStatus
}

/** Safe application-facing entry point for the initial JNI health check. */
object NativeEngineStatusProvider {
    fun check(): NativeEngineStatus =
        checkNativeEngineStatus {
            NativeBindings.healthCheck()
        }
}

internal inline fun checkNativeEngineStatus(
    healthCheck: () -> String,
): NativeEngineStatus =
    try {
        NativeEngineStatus.Available(healthCheck())
    } catch (_: LinkageError) {
        // Keep loader details and machine paths out of user-visible state.
        NativeEngineStatus.Unavailable("Native library could not be loaded")
    } catch (_: RuntimeException) {
        NativeEngineStatus.Unavailable("Native engine health check failed")
    }
