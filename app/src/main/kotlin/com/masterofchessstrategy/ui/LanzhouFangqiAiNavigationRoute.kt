package com.masterofchessstrategy.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.masterofchessstrategy.data.MocsDatabase
import com.masterofchessstrategy.games.lanzhou_fangqi.*
import com.masterofchessstrategy.games.lanzhou_fangqi.data.FangqiLocalStorage
import com.masterofchessstrategy.games.lanzhou_fangqi.data.RoomFangqiRankedRepository
import com.masterofchessstrategy.settings.LocalDataWriterRegistry

@Composable
internal fun LanzhouFangqiAiEntryNavigationRoute(database: MocsDatabase, owner: ViewModelStoreOwner,
    writers: LocalDataWriterRegistry, storage: FangqiLocalStorage, onOpen: (FangqiAiEntryLaunch) -> Unit,
    onLearning: () -> Unit, onBack: () -> Unit, initialDifficultyCode: Int? = null,
) {
    val epoch by storage.epoch.collectAsState()
    val factory = remember(database, epoch, writers, initialDifficultyCode) {
        viewModelFactory { initializer {
            FangqiAiEntryViewModel(RoomFangqiRankedRepository(database),
                initialDifficultyCode = initialDifficultyCode).also(writers::register)
        } }
    }
    val model: FangqiAiEntryViewModel = viewModel(owner, key = "fangqi-entry:$epoch", factory = factory)
    writers.register(model)
    FangqiAiEntryRoute(model, onOpen, onLearning, onBack)
}

@Composable
internal fun LanzhouFangqiSavedNavigationRoute(database: MocsDatabase, owner: ViewModelStoreOwner,
    writers: LocalDataWriterRegistry, storage: FangqiLocalStorage, encodedId: String?, formalCode: Int?,
    onBack: () -> Unit,
) {
    val id = remember(encodedId) { decodeFangqiSavedRouteId(encodedId) }
    if (id == null || formalCode !in listOf(0, 1)) {
        Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("对局参数无效，请返回后重新选择", Modifier.testTag("fangqi_saved_route_error"))
            Button(onClick = onBack) { Text("返回") }
        }
    } else LanzhouFangqiRoute(database, owner, writers, storage, onBack,
        expectedSessionId = id, formal = formalCode == 1)
}

/** Canonical UTF-8 hex keeps legacy IDs (including '/' and '|') inside one navigation segment. */
internal fun decodeFangqiSavedRouteId(value: String?): String? {
    val hex = "0123456789abcdef"
    if (value == null || value.length !in 2..512 || value.length % 2 != 0 || value.any { it !in hex }) return null
    val bytes = ByteArray(value.length / 2) { (hex.indexOf(value[it * 2]) * 16 + hex.indexOf(value[it * 2 + 1])).toByte() }
    val decoded = bytes.toString(Charsets.UTF_8)
    return decoded.takeIf { it.isNotBlank() && it.length <= 64 && it.toByteArray(Charsets.UTF_8).contentEquals(bytes) }
}
