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
import com.masterofchessstrategy.engine.Difficulty
import com.masterofchessstrategy.games.lanzhou_fangqi.FangqiGameRoute
import com.masterofchessstrategy.games.lanzhou_fangqi.FangqiGameViewModel
import com.masterofchessstrategy.games.lanzhou_fangqi.data.FangqiLocalStorage
import com.masterofchessstrategy.settings.LocalDataWriterRegistry

@Composable
internal fun LanzhouFangqiRoute(
    database: MocsDatabase,
    owner: ViewModelStoreOwner,
    writers: LocalDataWriterRegistry,
    storage: FangqiLocalStorage,
    onBack: () -> Unit,
    aiDifficulty: Difficulty? = null,
    expectedSessionId: String? = null,
    formal: Boolean = false,
) {
    val epoch by storage.epoch.collectAsState()
    val factory = remember(database, storage, aiDifficulty, expectedSessionId, formal, writers) {
        viewModelFactory {
            initializer { FangqiGameViewModel(storage, aiDifficulty,
                expectedSessionId = expectedSessionId, formal = formal).also(writers::register) }
        }
    }
    val game: FangqiGameViewModel = viewModel(owner, key = "fangqi:$epoch:$expectedSessionId:$formal", factory = factory)
    writers.register(game) // Retained owners also participate after Activity recreation.
    FangqiGameRoute(game, onBack)
}
