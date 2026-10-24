package com.masterofchessstrategy.games.go

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.viewmodel.compose.viewModel

@Composable
internal fun GoAiSetupRoute(
    repository: GoAiEntryRepository,
    viewModelKey: String,
    onOpen: (String) -> Unit,
    onLearning: () -> Unit,
    onBack: () -> Unit,
    onWriterReady: (GoAiSetupViewModel) -> Unit,
) {
    val model: GoAiSetupViewModel = viewModel(key = viewModelKey, factory = remember(repository) {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                GoAiSetupViewModel(repository).also(onWriterReady) as T
        }
    })
    SideEffect { onWriterReady(model) }
    val state by model.state.collectAsState()
    var leaving by rememberSaveable(viewModelKey) { mutableStateOf(false) }
    val leave: () -> Unit = {
        if (!leaving && !state.busy) {
            leaving = true
            model.beginRetirement()
        }
    }
    LaunchedEffect(model, leaving) { if (leaving) { model.beginRetirement().await(); onBack() } }
    BackHandler { leave() }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { model.resume() }
    LaunchedEffect(state.readyMatch?.matchId) {
        if (!leaving) model.takeReadyMatch()?.let { onOpen(it.matchId) }
    }
    GoAiSetupScreen(state, model::requestCreate, model::continueMatch, model::confirmAbandon,
        model::dismissAbandon, model::reload, { if (!leaving) onLearning() }, leave)
}
