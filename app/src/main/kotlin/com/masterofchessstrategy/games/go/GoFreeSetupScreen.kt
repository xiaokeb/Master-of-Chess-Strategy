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
import com.masterofchessstrategy.engine.Difficulty
import com.masterofchessstrategy.engine.GoPlayer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun GoFreeSetupScreen(
    snapshot: GoAiEntrySnapshot?,
    onPlay: (GoAiOpening, GoAiMatchConfig) -> Unit,
    onBack: () -> Unit,
) {
    var size by rememberSaveable { mutableIntStateOf(19) }
    var cells by rememberSaveable { mutableStateOf(IntArray(19 * 19)) }
    var tool by rememberSaveable { mutableIntStateOf(1) }
    var firstCode by rememberSaveable { mutableIntStateOf(0) }
    var humanCode by rememberSaveable { mutableIntStateOf(0) }
    var difficulty by rememberSaveable { mutableIntStateOf(0) }
    var checking by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val available = snapshot?.eligibility?.condition(Difficulty.entries[difficulty])?.isUnlocked == true
    BackHandler { if (!checking) onBack() }
    Surface(Modifier.fillMaxSize().testTag("go_free_setup")) {
        BoxWithConstraints(Modifier.fillMaxSize().padding(16.dp)) {
            val portraitBoardHeight = maxHeight * 0.55f
            val controls: @Composable (Modifier) -> Unit = { target ->
                Column(target.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("围棋 · 自由摆局", style = MaterialTheme.typography.headlineSmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (choice in listOf(9, 13, 19)) FilterChip(size == choice, {
                            if (size != choice) { size = choice; cells = IntArray(choice * choice); error = null }
                        }, enabled = !checking, label = { Text("${choice}路") },
                            modifier = Modifier.testTag("go_free_size_$choice"))
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for ((id, label) in listOf(1 to "放黑子", 2 to "放白子", 0 to "擦除")) {
                            FilterChip(tool == id, { tool = id }, enabled = !checking, label = { Text(label) },
                                modifier = Modifier.testTag("go_free_tool_$id"))
                        }
                        TextButton({ cells = IntArray(size * size); error = null }, enabled = !checking) {
                            Text("清空棋盘")
                        }
                    }
                    for ((label, selection, update) in listOf(
                        Triple("先行方", firstCode, { side: Int -> firstCode = side; error = null }),
                        Triple("玩家执色", humanCode, { side: Int -> humanCode = side }),
                    )) {
                        Text(label)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            for (side in GoPlayer.entries) FilterChip(selection == side.code, { update(side.code) },
                                enabled = !checking, label = { Text(goReplayPlayer(side)) },
                                modifier = Modifier.testTag(if (label == "先行方") "go_free_first_${side.code}" else "go_free_human_${side.code}"))
                        }
                    }
                    Text("难度")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for ((index, level) in GoAiDifficulty.entries.withIndex()) FilterChip(difficulty == index, {
                            difficulty = index
                        }, enabled = !checking && snapshot?.eligibility?.condition(Difficulty.entries[index])?.isUnlocked == true,
                            label = { Text(goAiDifficultyLabel(level)) })
                    }
                    if (!available) Text("完成教程或对应难度的胜场后可开始。")
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    Button({
                        val sgf = goSetupSgf(size, cells, GoPlayer.entries[firstCode])
                        val player = GoPlayer.entries[humanCode]
                        val level = GoAiDifficulty.entries[difficulty]
                        checking = true
                        error = null
                        scope.launch {
                            try {
                                val opening = withContext(Dispatchers.Default) { GoAiOpening.fromSgf(sgf) }
                                onPlay(opening, opening.config(player, level))
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                error = "原生规则拒绝该局面。请检查无气棋块或先行方是否有合法落子。"
                            } finally { checking = false }
                        }
                    }, enabled = available && !checking, modifier = Modifier.testTag("go_free_start")) {
                        Text(if (checking) "正在校验" else "开始陪练")
                    }
                    TextButton(onBack, enabled = !checking) { Text("返回设置") }
                }
            }
            val board: @Composable (Modifier) -> Unit = { target ->
                GoBoard(size, cells.toList(), { x, y ->
                    if (!checking) { cells = cells.copyOf().also { it[y * size + x] = tool }; error = null }
                }, target, interactionEnabled = !checking)
            }
            if (maxWidth >= 560.dp) {
                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    board(Modifier.weight(1f).fillMaxHeight())
                    controls(Modifier.width(320.dp).fillMaxHeight())
                }
            } else {
                Column(Modifier.fillMaxSize()) {
                    board(Modifier.fillMaxWidth().heightIn(max = portraitBoardHeight))
                    controls(Modifier.weight(1f).fillMaxWidth())
                }
            }
        }
    }
}
