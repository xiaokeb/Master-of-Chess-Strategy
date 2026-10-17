package com.masterofchessstrategy.games.go

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.*

/** Source data is immutable and cannot alias an active game's serialization buffer. */
sealed interface GoReplaySource {
    fun open(): GoReplayReader

    class Snapshot(bytes: ByteArray) : GoReplaySource {
        private val data = bytes.copyOf()
        init { require(data.isNotEmpty() && data.size <= 1_000_000) }
        override fun open() = GoReplaySession.openSnapshot(data)
    }

    class Sgf(private val text: String) : GoReplaySource {
        override fun open() = GoReplaySession.openSgf(text)
    }
}

class GoReplayViewModel(
    source: GoReplaySource,
    worker: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {
    val controller = GoReplayController(viewModelScope, source::open, worker)

    override fun onCleared() {
        // viewModelScope is already cancelled; use a bounded cleanup lifetime for Native join.
        val cleanup = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        cleanup.launch {
            try { controller.closeAndJoin() } finally { cleanup.cancel() }
        }
        super.onCleared()
    }
}

/** recordKey must identify the immutable source, including its version, in the nav entry. */
@Composable
fun GoReplayRoute(
    recordKey: String,
    source: GoReplaySource,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    extraControls: @Composable (GoReplayFrame) -> Unit = {},
) {
    val replay: GoReplayViewModel = viewModel(key = "go-replay-$recordKey", factory = remember(source) {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = GoReplayViewModel(source) as T
        }
    })
    val controller = replay.controller
    val state by controller.state.collectAsState()
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { controller.pause() }
    GoReplayScreen(state, controller::seek, { controller.play() }, { controller.pause() },
        { controller.setSpeed(it) }, { controller.pause(); onBack() }, modifier, extraControls = extraControls)
}
