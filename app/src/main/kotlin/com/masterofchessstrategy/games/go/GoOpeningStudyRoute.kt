package com.masterofchessstrategy.games.go

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.masterofchessstrategy.engine.GoRecordEngine
import com.masterofchessstrategy.engine.NativeGoEngine
import kotlinx.coroutines.*

class GoOpeningStudyViewModel(
    val line: GoOpeningStudyLine,
    worker: CoroutineDispatcher = Dispatchers.Default,
    engineFactory: (Int, Int, Int) -> GoRecordEngine = { size, komi, handicap -> NativeGoEngine(size, komi, handicap) },
) : ViewModel() {
    @Volatile private var verifiedPlan: GoOpeningStudyPlan? = null
    val controller = GoReplayController(viewModelScope, {
        val plan = GoOpeningStudyLibrary.open(line, engineFactory)
        val reader = GoReplaySession.openSnapshot(plan.copySnapshotBytes()) { size -> engineFactory(size, 15, 0) }
        verifiedPlan = plan
        reader
    }, worker)

    fun trainingOpening() = verifiedPlan?.opening

    override fun onCleared() {
        verifiedPlan = null
        val cleanup = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        cleanup.launch { try { controller.closeAndJoin() } finally { cleanup.cancel() } }
        super.onCleared()
    }
}

/** Standalone Go-owned entry. The shared navigator may expose it after its own unlock checks. */
@Composable
fun GoOpeningStudyHub(
    availableDifficulties: Set<GoAiDifficulty>,
    onStartAutomatic: (GoAiOpening, GoAiMatchConfig) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    val line = selectedId?.let(GoOpeningStudyLibrary::line)
    if (line == null) GoOpeningStudyCatalog(availableDifficulties, { selectedId = it }, onBack, modifier)
    else {
        BackHandler { selectedId = null }
        val store = remember(line.id) { ViewModelStore() }
        val owner = remember(store) { object : ViewModelStoreOwner { override val viewModelStore = store } }
        DisposableEffect(store) { onDispose { store.clear() } }
        CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
            GoOpeningStudyRoute(line, availableDifficulties, onStartAutomatic, { selectedId = null }, modifier)
        }
    }
}

@Composable
private fun GoOpeningStudyCatalog(
    difficulties: Set<GoAiDifficulty>,
    onLine: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier,
) {
    Surface(modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("围棋开局学习", style = MaterialTheme.typography.headlineSmall)
                TextButton(onBack) { Text("返回") }
            }
            Text("项目自编线路 · 每一着均由正式规则引擎验证 · 不标作职业定式结论")
            if (difficulties.isEmpty()) Text("完成十课教程后开放简单难度的自动续弈。")
            else Text("可用自动演局难度：${difficulties.sortedBy { it.ordinal }.joinToString("、", transform = ::goAiDifficultyLabel)}")
            for (line in GoOpeningStudyLibrary.lines) ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(line.title, style = MaterialTheme.typography.titleMedium)
                    Text("${line.boardSize}路 · ${line.steps.size}着")
                    Text(line.focus)
                    Button({ onLine(line.id) }, modifier = Modifier.testTag("go_opening_line_${line.id}")) { Text("学习线路") }
                }
            }
        }
    }
}

@Composable
private fun GoOpeningStudyRoute(
    line: GoOpeningStudyLine,
    difficulties: Set<GoAiDifficulty>,
    onStartAutomatic: (GoAiOpening, GoAiMatchConfig) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier,
) {
    val model: GoOpeningStudyViewModel = viewModel(key = "go-opening-study-${line.id}", factory = remember(line) {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = GoOpeningStudyViewModel(line) as T
        }
    })
    val state by model.controller.state.collectAsState()
    var choice by rememberSaveable(line.id) { mutableStateOf<GoAiDifficulty?>(null) }
    val difficulty = choice?.takeIf { it in difficulties } ?: difficulties.minByOrNull { it.ordinal }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { model.controller.pause() }
    GoReplayScreen(state, model.controller::seek, model.controller::play, model.controller::pause,
        model.controller::setSpeed, { model.controller.pause(); onBack() }, modifier, title = line.title) { frame ->
        HorizontalDivider()
        Text(line.focus)
        Text(if (frame.eventIndex == 0) "从空盘开始，使用下一步或播放查看线路。"
            else line.steps[frame.eventIndex - 1].explanation, modifier = Modifier.testTag("go_opening_explanation"))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (candidate in difficulties.sortedBy { it.ordinal }) FilterChip(difficulty == candidate,
                { choice = candidate }, label = { Text(goAiDifficultyLabel(candidate)) })
        }
        Button({
            val opening = model.trainingOpening() ?: return@Button
            val selected = difficulty ?: return@Button
            onStartAutomatic(opening, opening.config(humanPlayer = null, difficulty = selected))
        }, enabled = difficulty != null && state.ready && frame.eventIndex == frame.eventCount && model.trainingOpening() != null,
            modifier = Modifier.testTag("go_opening_start_auto")) { Text("从末端开启自动演局") }
        Text("进入棋盘后可开始自动演局。指定开局训练不计标准人机胜场。")
    }
}
