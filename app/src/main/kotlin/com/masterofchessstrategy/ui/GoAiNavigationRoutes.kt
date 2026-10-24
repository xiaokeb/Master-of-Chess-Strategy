package com.masterofchessstrategy.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.masterofchessstrategy.engine.NativeGoSearchRuntime
import com.masterofchessstrategy.games.go.*
import com.masterofchessstrategy.games.go.data.GoLocalStorage
import com.masterofchessstrategy.settings.LocalDataWriterRegistry
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun GoProductionModesNavigationRoute(storage: GoLocalStorage, writers: LocalDataWriterRegistry,
    onBack: () -> Unit, onLocalGame: () -> Unit, onLearning: () -> Unit,
    onAiGame: () -> Unit, onEndgames: (() -> Unit)? = null,
) {
    val epoch by storage.gate.epoch.collectAsState()
    val repository = remember(storage, epoch) { storage.difficultyEligibilityRepository() }
    val model: GoDifficultyProgressViewModel = viewModel(key = "go-ai-progress-$epoch", factory = remember(repository) {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                GoDifficultyProgressViewModel(repository).also(writers::register) as T
        }
    })
    SideEffect { writers.register(model) }
    val state by model.state.collectAsState()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { model.resume() }
    GoNavigationModesScreen(onBack, onLocalGame, onLearning, state, model::reload, onEndgames, onAiGame)
}

@Composable
internal fun GoAiSetupNavigationRoute(storage: GoLocalStorage, writers: LocalDataWriterRegistry,
    onOpen: (String) -> Unit, onLearning: () -> Unit, onBack: () -> Unit,
) {
    val epoch by storage.gate.epoch.collectAsState()
    val repository = remember(storage, epoch) { storage.aiEntryRepository() }
    GoAiSetupRoute(repository, "go-ai-setup-$epoch", onOpen, onLearning, onBack, writers::register)
}

private data class GoAiPlayEntry(val loading: Boolean = true, val match: GoAiSavedMatch? = null,
    val missing: Boolean = false, val error: String? = null)

/** Read the actual checkpoint before loading the model. No route argument supplies a new config. */
@Composable
internal fun GoAiPlayNavigationRoute(storage: GoLocalStorage, writers: LocalDataWriterRegistry,
    matchId: String?, onBack: () -> Unit,
) {
    val epoch by storage.gate.epoch.collectAsState()
    val repository = remember(storage, epoch) { storage.formalAiRepository() }
    val completed = remember(storage, epoch) { storage.completedMatchSink() }
    var attempt by remember(matchId, epoch) { mutableIntStateOf(0) }
    var ownedMatchId by rememberSaveable(matchId) { mutableStateOf(matchId) }
    val ownerKey = "$epoch-$matchId"
    var exitRequested by rememberSaveable(ownerKey) { mutableStateOf(false) }
    val valid = matchId != null && matchId.isNotBlank() && '\u0000' !in matchId && matchId.toByteArray(Charsets.UTF_8).size <= 128
    val entry by produceState(GoAiPlayEntry(), repository, matchId, attempt) {
        value = GoAiPlayEntry()
        if (!valid) { value = GoAiPlayEntry(loading = false, missing = true); return@produceState }
        try {
            // A configuration change retains this page's VM. Drain its entered save before
            // reading, and accept only that owner's acknowledged restart/continuation identity.
            val retained = writers.snapshot().filterIsInstance<GoAiMatchViewModel>()
                .singleOrNull { it.navigationOwnerKey == ownerKey }
            val retainedPage = retained?.state?.first {
                !it.loadingModel && (it.match?.game?.saving != true || it.match?.closed == true)
            }
            val expected = retainedPage?.match?.matchId?.takeIf { it.isNotBlank() } ?: ownedMatchId
            val saved = withContext(Dispatchers.IO) { repository.load() }
            value = if (saved == null || saved.matchId != expected) GoAiPlayEntry(loading = false, missing = true)
                else GoAiPlayEntry(loading = false, match = saved)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { value = GoAiPlayEntry(loading = false, error = "围棋存档读取失败；原局已保留，请重试或返回设置。") }
    }
    LaunchedEffect(entry.missing) { if (entry.missing) exitRequested = true }
    LaunchedEffect(exitRequested) {
        if (exitRequested) {
            writers.snapshot().filterIsInstance<GoAiMatchViewModel>().filter { it.navigationOwnerKey == ownerKey }
                .forEach { it.beginRetirement().await() }
            onBack()
        }
    }
    val saved = entry.match
    if (saved != null) {
        val context = LocalContext.current.applicationContext
        val loader = remember(context) { GoAiAssetModelLoader(context,
            runtimeFactory = { file -> NativeGoSearchRuntime(file.absolutePath) }) }
        val loadEngine: suspend () -> GoAiMatchEngineOwner = remember(loader) { { loader.load() } }
        GoAiMatchRoute(ownerKey, saved.config, loadEngine, onBack,
            repository = repository, completedSink = completed, completeHumanImmediately = true,
            expectedMatchId = saved.matchId, onWriterReady = writers::register,
            onMatchIdentity = { ownedMatchId = it })
    } else Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(if (entry.loading) "正在读取围棋活动对局…" else entry.error ?: "活动对局已变更，返回设置…")
            if (entry.error != null) OutlinedButton({ attempt++ }, modifier = Modifier.testTag("go_ai_entry_retry")) { Text("重新读取") }
            TextButton({ exitRequested = true }) { Text("返回设置") }
        }
    }
}
