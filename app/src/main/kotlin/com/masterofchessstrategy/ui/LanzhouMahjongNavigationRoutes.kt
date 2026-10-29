package com.masterofchessstrategy.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.masterofchessstrategy.data.MocsDatabase
import com.masterofchessstrategy.engine.Difficulty
import com.masterofchessstrategy.games.lanzhou_mahjong.*
import com.masterofchessstrategy.games.lanzhou_mahjong.data.*
import com.masterofchessstrategy.settings.LocalDataWriterRegistry
import kotlinx.coroutines.launch

/** Menu facts and every production writer share the exact database's epoch and restore barrier. */
@Composable
internal fun LanzhouMahjongEntryNavigationRoute(
    database: MocsDatabase, owner: ViewModelStoreOwner, writers: LocalDataWriterRegistry,
    onLearning: () -> Unit, onResume: () -> Unit, onNewEasy: () -> Unit, onBack: () -> Unit,
) {
    val gate = remember(database) { LanzhouMahjongStorageEpochGate.forDatabase(database) }
    val epoch by gate.epoch.collectAsState()
    val factory = remember(database, epoch, writers) { viewModelFactory {
        initializer { LanzhouMahjongEntryViewModel(RoomLanzhouMahjongEntryRepository(database)).also(writers::register) }
    } }
    val model: LanzhouMahjongEntryViewModel = viewModel(owner, key = "lanzhou-mahjong-entry:$epoch", factory = factory)
    SideEffect { writers.register(model) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { model.resume() }
    val state by model.state.collectAsState()
    val scope = rememberCoroutineScope()
    val latestBack by rememberUpdatedState(onBack)
    var leaving by remember(model) { mutableStateOf(false) }
    val back: () -> Unit = {
        if (!leaving) {
            leaving = true
            val completion = model.beginRetirement()
            scope.launch { completion.await(); latestBack() }
        }
    }
    BackHandler(onBack = back)
    LanzhouMahjongEntryScreen(state, onLearning, onResume, onNewEasy, back, model::reload)
}

@Composable
internal fun LanzhouMahjongLearningNavigationRoute(
    database: MocsDatabase, owner: ViewModelStoreOwner, writers: LocalDataWriterRegistry, onBack: () -> Unit,
) {
    val gate = remember(database) { LanzhouMahjongStorageEpochGate.forDatabase(database) }
    val epoch by gate.epoch.collectAsState()
    val factory = remember(database, epoch, writers) { viewModelFactory {
        initializer { LanzhouMahjongLearningViewModel(RoomLanzhouMahjongLearningRepository(database)).also(writers::register) }
    } }
    val model: LanzhouMahjongLearningViewModel = viewModel(owner, key = "lanzhou-mahjong-learning:$epoch", factory = factory)
    SideEffect { writers.register(model) }
    LanzhouMahjongLearningRoute(model, onBack)
}

@Composable
internal fun LanzhouMahjongPlayNavigationRoute(
    database: MocsDatabase, owner: ViewModelStoreOwner, writers: LocalDataWriterRegistry,
    mode: String?, onBack: () -> Unit,
) {
    val openMode = when (mode) {
        "new" -> LanzhouMahjongOpenMode.NEW
        "resume" -> LanzhouMahjongOpenMode.RESUME
        else -> null
    }
    if (openMode == null) {
        BackHandler(onBack = onBack)
        Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("对局入口无效，原存档已保留")
            Button(onBack) { Text("返回") }
        }
        return
    }
    val gate = remember(database) { LanzhouMahjongStorageEpochGate.forDatabase(database) }
    val epoch by gate.epoch.collectAsState()
    val seed = rememberSaveable { System.nanoTime() and Long.MAX_VALUE }
    val factory = remember(database, epoch, seed, openMode, writers) { viewModelFactory {
        initializer { LanzhouMahjongGameViewModel(database.createLanzhouMahjongHostStorage(),
            LanzhouMahjongLaunchConfig(Difficulty.EASY, seed), openMode = openMode).also(writers::register) }
    } }
    val model: LanzhouMahjongGameViewModel = viewModel(owner, key = "lanzhou-mahjong-play:$epoch:$mode", factory = factory)
    SideEffect { writers.register(model) }
    LanzhouMahjongGameRoute(model, onBack)
}
