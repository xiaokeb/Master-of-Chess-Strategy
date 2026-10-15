package com.masterofchessstrategy.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.masterofchessstrategy.data.MocsDatabase
import com.masterofchessstrategy.engine.games.tiger_goat.NativeTigerGoatSession
import com.masterofchessstrategy.games.tiger_goat.NativeTigerGoatRulesSession
import com.masterofchessstrategy.games.tiger_goat.RoomTigerGoatSessionStore
import com.masterofchessstrategy.games.tiger_goat.TigerGoatGameRoute
import com.masterofchessstrategy.games.tiger_goat.TigerGoatGameViewModel
import com.masterofchessstrategy.settings.LocalDataWriterRegistry

/** The NavBackStackEntry owns the VM; only completed scopes leave the restore writer registry. */
@Composable
internal fun TigerGoatNavigationRoute(
    database: MocsDatabase,
    owner: ViewModelStoreOwner,
    writers: LocalDataWriterRegistry,
    onBack: () -> Unit,
) {
    val factory = remember(database, writers) {
        viewModelFactory {
            initializer {
                TigerGoatGameViewModel(
                    store = RoomTigerGoatSessionStore(database.activeGameDao()),
                    sessionFactory = { NativeTigerGoatRulesSession(NativeTigerGoatSession()) },
                ).also(writers::register)
            }
        }
    }
    val game: TigerGoatGameViewModel = viewModel(viewModelStoreOwner = owner, factory = factory)
    writers.register(game) // Also registers a retained VM when the activity is recreated.
    TigerGoatGameRoute(game, onBack)
}
