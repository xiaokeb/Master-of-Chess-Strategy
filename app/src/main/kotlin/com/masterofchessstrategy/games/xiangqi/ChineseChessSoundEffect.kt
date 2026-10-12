package com.masterofchessstrategy.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.masterofchessstrategy.game.ChineseChessSoundCue
import com.masterofchessstrategy.game.ChineseChessSoundPlayer
import kotlinx.coroutines.flow.Flow

/** One player per mounted destination; the live event flow has no history replay. */
@Composable
internal fun ChineseChessSoundEffect(events: Flow<ChineseChessSoundCue>, enabled: Boolean) {
    val context = LocalContext.current.applicationContext
    val player = remember(context) { ChineseChessSoundPlayer(context) }
    SideEffect { player.setEnabled(enabled) }
    DisposableEffect(player) {
        onDispose(player::close)
    }
    LaunchedEffect(events, player) {
        events.collect { player.play(it) }
    }
}
