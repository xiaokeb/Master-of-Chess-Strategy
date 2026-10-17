package com.masterofchessstrategy.games.go

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.masterofchessstrategy.engine.*
import java.time.LocalDate

data class GoEndgameUiState(
    val loading: Boolean = true,
    val exercise: GoEndgameState? = null,
    val completedIds: Set<String> = emptySet(),
    val error: String? = null,
    val needsSave: Boolean = false,
    val mode: GoEndgameChallengeMode = GoEndgameChallengeMode.MAINLINE,
    val dailyDate: LocalDate? = null,
) {
    val canOperate get() = !loading && !needsSave && error == null && exercise != null
    fun isUnlocked(id: String): Boolean {
        val index = GoEndgameLibrary.puzzles.indexOfFirst { it.id == id }
        return index >= 0 && index <= GoEndgameLibrary.puzzles.takeWhile { it.id in completedIds }.size
    }
}

@Composable
fun GoEndgameRoute(onBack: () -> Unit, modifier: Modifier = Modifier,
    repository: GoEndgameProgressRepository? = null,
    viewModelKey: String = "go-endgame",
    onWriterReady: (ViewModel) -> Unit = {},
) {
    val context = LocalContext.current.applicationContext
    val storage = remember(context, repository) { repository ?: GoEndgameFileRepository(context) }
    val factory = remember(storage, onWriterReady) { object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = GoEndgameViewModel(storage).also(onWriterReady) as T
    } }
    val model: GoEndgameViewModel = viewModel(key = viewModelKey, factory = factory)
    onWriterReady(model)
    val state by model.state.collectAsState()
    GoEndgameScreen(state, model::answer, { model.select(it) }, model::reset, model::retry, onBack, modifier,
        onDaily = model::openDaily, onRandom = model::openRandom,
        onThemedSelect = { model.select(it, GoEndgameChallengeMode.THEME) })
}

@Composable
fun GoEndgameScreen(
    state: GoEndgameUiState,
    onAnswer: (BoardPosition) -> Unit,
    onSelect: (String) -> Unit,
    onReset: () -> Unit,
    onRetry: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onDaily: () -> Unit = {},
    onRandom: (GoEndgameTheme?, GoEndgameLevel?) -> Unit = { _, _ -> },
    onThemedSelect: (String) -> Unit = onSelect,
) {
    var catalog by remember { mutableStateOf(false) }
    var theme by remember { mutableStateOf<GoEndgameTheme?>(null) }
    var level by remember { mutableStateOf<GoEndgameLevel?>(null) }
    val exercise = state.exercise
    Surface(modifier.fillMaxSize()) {
        BoxWithConstraints(Modifier.fillMaxSize().padding(12.dp)) {
            val board: @Composable (Modifier) -> Unit = { boardModifier ->
                if (exercise == null) Box(boardModifier) { Text(if (state.loading) "正在验证残局…" else "残局暂不可用") }
                else GoBoard(exercise.puzzle.boardSize, exercise.stones,
                    { x, y -> onAnswer(BoardPosition(x, y)) }, boardModifier,
                    lastMoveIndex = exercise.lastMoveIndex,
                    interactionEnabled = state.canOperate && !exercise.completed)
            }
            val controls: @Composable (Modifier) -> Unit = { controlsModifier ->
                Column(controlsModifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("围棋死活练习", style = MaterialTheme.typography.titleLarge)
                        TextButton(onClick = onBack) { Text("返回") }
                    }
                    Text("项目自编种子题 · 已完成 ${state.completedIds.size}/${GoEndgameLibrary.puzzles.size}")
                    Text(state.mode.label + if (state.mode == GoEndgameChallengeMode.DAILY) " · ${state.dailyDate}" else "",
                        modifier = Modifier.testTag("go_endgame_mode"))
                    OutlinedButton(onClick = { catalog = true }, enabled = state.canOperate,
                        modifier = Modifier.testTag("go_endgame_catalog")) { Text("关卡目录") }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = onDaily, enabled = state.canOperate,
                            modifier = Modifier.testTag("go_endgame_daily")) { Text("每日残局") }
                        OutlinedButton(onClick = { onRandom(null, null) }, enabled = state.canOperate,
                            modifier = Modifier.testTag("go_endgame_random")) { Text("随机挑战") }
                    }
                    if (exercise != null) {
                        Text(exercise.puzzle.title, style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.testTag("go_endgame_title"))
                        Text("${exercise.puzzle.level.label} · ${exercise.puzzle.theme.label}")
                        Text(exercise.puzzle.instruction)
                        Text(exercise.feedback, modifier = Modifier.testTag("go_endgame_feedback"))
                        Text("尝试 ${exercise.attempts} 次")
                        if (exercise.completed && !state.needsSave) Text("本关完成", color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.testTag("go_endgame_completed"))
                        if (state.needsSave) Text("本题已解，通关进度尚未保存。", modifier = Modifier.testTag("go_endgame_pending_save"))
                        OutlinedButton(onClick = onReset, enabled = state.canOperate) { Text("重置本关") }
                        val index = GoEndgameLibrary.puzzles.indexOfFirst { it.id == exercise.puzzle.id }
                        if (exercise.completed && state.mode == GoEndgameChallengeMode.MAINLINE && index < GoEndgameLibrary.puzzles.lastIndex) Button(
                            onClick = { onSelect(GoEndgameLibrary.puzzles[index + 1].id) }, enabled = state.canOperate &&
                                state.isUnlocked(GoEndgameLibrary.puzzles[index + 1].id)) {
                            Text("下一关")
                        }
                        if (state.mode != GoEndgameChallengeMode.MAINLINE) OutlinedButton(onClick = {
                            val next = GoEndgameLibrary.puzzles.takeWhile { it.id in state.completedIds }.size
                            onSelect(GoEndgameLibrary.puzzles[minOf(next, GoEndgameLibrary.puzzles.lastIndex)].id)
                        }, enabled = state.canOperate) { Text("返回主线") }
                    }
                    if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                    state.error?.let { Text(it, color = MaterialTheme.colorScheme.error);
                        Button(onClick = onRetry, enabled = !state.loading) { Text("重试") } }
                }
            }
            if (maxWidth > maxHeight) Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                board(Modifier.weight(1f).fillMaxSize()); controls(Modifier.width(340.dp))
            } else Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                board(Modifier.weight(1f).fillMaxWidth()); controls(Modifier.fillMaxWidth().height(300.dp))
            }
        }
    }
    if (catalog) AlertDialog(onDismissRequest = { catalog = false }, title = { Text("围棋残局") }, text = {
        Column(Modifier.heightIn(max = 340.dp).verticalScroll(rememberScrollState())) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(theme == null, { theme = null }, label = { Text("全部主题") })
                GoEndgameTheme.entries.forEach { option -> FilterChip(theme == option, { theme = option },
                    label = { Text(option.label) }, modifier = Modifier.testTag("go_endgame_filter_${option.name}")) }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(level == null, { level = null }, label = { Text("全部难度") })
                GoEndgameLevel.entries.forEach { option -> FilterChip(level == option, { level = option },
                    label = { Text(option.label) }, modifier = Modifier.testTag("go_endgame_level_${option.name}")) }
            }
            val filtered = GoEndgameChallenges.catalog(theme, level)
            if (filtered.isEmpty()) Text("当前筛选没有题目。")
            val hasAvailable = filtered.any { state.isUnlocked(it.id) }
            OutlinedButton(onClick = { catalog = false; onRandom(theme, level) }, enabled = state.canOperate && hasAvailable,
                modifier = Modifier.testTag("go_endgame_filtered_random")) { Text("按筛选随机挑战") }
            if (!hasAvailable && filtered.isNotEmpty()) Text("先完成前置主线关卡，再挑战这些题目。")
            filtered.forEach { puzzle ->
                val index = GoEndgameLibrary.puzzles.indexOfFirst { it.id == puzzle.id }
                val unlocked = state.isUnlocked(puzzle.id)
                TextButton(onClick = { catalog = false;
                    if (theme != null || level != null) onThemedSelect(puzzle.id) else onSelect(puzzle.id)
                }, enabled = unlocked && state.canOperate, modifier = Modifier.testTag("go_endgame_puzzle_${puzzle.id}")) {
                    Text("${index + 1}. ${puzzle.title} · ${puzzle.level.label}" +
                        if (puzzle.id in state.completedIds) " · 已完成" else if (!unlocked) " · 未解锁" else "")
                }
            }
        }
    }, confirmButton = { TextButton(onClick = { catalog = false }) { Text("关闭") } })
}
