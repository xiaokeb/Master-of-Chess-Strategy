package com.masterofchessstrategy

import com.masterofchessstrategy.engine.NativeEngineStatus

/** Immutable first-screen state derived from the native boundary result. */
data class HomeUiState(
    val engineTitle: String,
    val engineDetail: String,
) {
    companion object {
        fun from(status: NativeEngineStatus): HomeUiState =
            when (status) {
                is NativeEngineStatus.Available ->
                    HomeUiState(
                        engineTitle = "原生引擎已就绪",
                        engineDetail = status.protocol,
                    )

                is NativeEngineStatus.Unavailable ->
                    HomeUiState(
                        engineTitle = "原生引擎不可用",
                        engineDetail = status.message,
                    )
            }
    }
}
