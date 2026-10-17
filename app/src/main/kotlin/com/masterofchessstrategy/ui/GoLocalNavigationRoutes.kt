package com.masterofchessstrategy.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import com.masterofchessstrategy.games.go.GoGameRoute
import com.masterofchessstrategy.games.go.GoLearningRoute
import com.masterofchessstrategy.games.go.GoEndgameRoute
import com.masterofchessstrategy.games.go.data.GoLocalStorage
import com.masterofchessstrategy.settings.LocalDataWriterRegistry

/** NavBackStackEntry owns each VM; restored data always receives a fresh epoch-specific owner. */
@Composable
internal fun GoLocalNavigationRoute(storage: GoLocalStorage, writers: LocalDataWriterRegistry, onBack: () -> Unit) {
    val epoch by storage.gate.epoch.collectAsState()
    val repository = remember(storage, epoch) { storage.sessionRepository() }
    GoGameRoute(onBack, repository = repository, viewModelKey = "go-local-$epoch", onWriterReady = writers::register)
}

@Composable
internal fun GoLearningNavigationRoute(storage: GoLocalStorage, writers: LocalDataWriterRegistry, onBack: () -> Unit) {
    val epoch by storage.gate.epoch.collectAsState()
    val repository = remember(storage, epoch) { storage.learningRepository() }
    GoLearningRoute(onBack, repository = repository, viewModelKey = "go-learning-$epoch", onWriterReady = writers::register)
}

@Composable
internal fun GoEndgameNavigationRoute(storage: GoLocalStorage, writers: LocalDataWriterRegistry, onBack: () -> Unit) {
    val epoch by storage.gate.epoch.collectAsState()
    val repository = remember(storage, epoch) { storage.endgameRepository() }
    GoEndgameRoute(onBack, repository = repository, viewModelKey = "go-endgame-$epoch", onWriterReady = writers::register)
}
