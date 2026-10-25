package com.masterofchessstrategy.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.masterofchessstrategy.data.MocsDatabase
import com.masterofchessstrategy.games.lanzhou_fangqi.FangqiLearningRoute
import com.masterofchessstrategy.games.lanzhou_fangqi.FangqiLearningViewModel
import com.masterofchessstrategy.games.lanzhou_fangqi.data.FangqiLocalStorage
import com.masterofchessstrategy.settings.LocalDataWriterRegistry

/** Retain practice Native across recreation and include it in the app's awaitable restore barrier. */
@Composable
internal fun LanzhouFangqiLearningNavigationRoute(
    database: MocsDatabase,
    owner: ViewModelStoreOwner,
    writers: LocalDataWriterRegistry,
    storage: FangqiLocalStorage,
    onBack: () -> Unit,
) {
    val epoch by storage.epoch.collectAsState()
    val factory = remember(database, storage, writers) {
        viewModelFactory {
            initializer { FangqiLearningViewModel.forDatabase(database, storage).also(writers::register) }
        }
    }
    val model: FangqiLearningViewModel = viewModel(owner, key = "fangqi-learning:$epoch", factory = factory)
    writers.register(model)
    FangqiLearningRoute(model, onBack)
}
