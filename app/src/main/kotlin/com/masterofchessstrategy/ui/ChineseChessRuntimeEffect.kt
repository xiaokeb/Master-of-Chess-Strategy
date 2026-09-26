package com.masterofchessstrategy.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.masterofchessstrategy.game.ChineseChessGameViewModel

/** Reports visibility only: the existing session remains the sole engine and persistence owner. */
@Composable
internal fun ChineseChessRuntimeEffect(game: ChineseChessGameViewModel) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(game, lifecycle) {
        fun synchronize() {
            game.setRuntimeForeground(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
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
