package com.masterofchessstrategy.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.masterofchessstrategy.game.ChineseChessGameViewModel
import com.masterofchessstrategy.game.ChineseChessBackgroundController

/** Reports visibility only: the existing session remains the sole engine and persistence owner. */
@Composable
internal fun ChineseChessRuntimeEffect(game: ChineseChessGameViewModel) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val controller = ChineseChessBackgroundController.get(LocalContext.current)
    DisposableEffect(game, lifecycle, controller) {
        game.registerBackgroundRuntime(controller::attach)
        fun synchronize() {
            game.setRuntimeForeground(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
            controller.refresh()
        }
        val observer = LifecycleEventObserver { _, _ -> synchronize() }
        lifecycle.addObserver(observer)
        synchronize()
        onDispose {
            lifecycle.removeObserver(observer)
            // A settings destination may cover a still-active game; do not stop its clocks/AI.
            game.setRuntimeForeground(false)
        }
    }
}
