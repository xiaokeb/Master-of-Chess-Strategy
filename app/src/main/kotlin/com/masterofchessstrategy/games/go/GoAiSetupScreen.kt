package com.masterofchessstrategy.games.go

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.masterofchessstrategy.engine.*

/** Selection only; a successful repository transaction is the sole play-navigation trigger. */
@Composable
internal fun GoAiSetupScreen(
    state: GoAiSetupState,
    onCreate: (GoAiMatchConfig) -> Unit,
    onContinue: () -> Unit,
    onConfirmAbandon: () -> Unit,
    onDismissAbandon: () -> Unit,
    onRetry: () -> Unit,
    onLearning: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var size by rememberSaveable { mutableIntStateOf(19) }
    var color by rememberSaveable { mutableIntStateOf(0) } // 0 black, 1 white, 2 automatic.
    var difficultyIndex by rememberSaveable { mutableIntStateOf(0) }
    var handicap by rememberSaveable { mutableIntStateOf(0) }
    val selected = GoAiMatchConfig(size, handicap,
        when (color) { 0 -> GoPlayer.BLACK; 1 -> GoPlayer.WHITE; else -> null },
        GoAiDifficulty.entries[difficultyIndex])
    val facts = state.snapshot
    val enabled = !state.loading && !state.busy
    Surface(modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)
            .testTag("go_ai_setup"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("围棋 · AI 对局设置", style = MaterialTheme.typography.headlineSmall)
            Text("标准真人局使用中国规则数子；自动、让子与指定开局为训练，不计标准胜场。")
            if (state.loading) Text("正在读取当前对局与解锁进度…")
            if (state.busy) Text("正在保存新局，完成后进入棋盘…", Modifier.testTag("go_ai_setup_saving"))
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (!state.loading && facts == null) OutlinedButton(onRetry,
                enabled = enabled, modifier = Modifier.testTag("go_ai_setup_retry")) { Text("重新读取") }
            facts?.active?.let { active ->
                HorizontalDivider()
                Text("当前对局：${active.config.boardSize}路 · ${goAiDifficultyLabel(active.config.difficulty)} · " +
                    (active.config.humanPlayer?.let { "你执${goReplayPlayer(it)}" } ?: "自动演局"))
                val unfinishedResult = facts.phase == GoPhase.FINISHED && !facts.completionStored
                if (unfinishedResult) Text("上局结果待保存；请先查看并保存，不能通过新建跳过。")
                Button(onContinue, enabled = enabled, modifier = Modifier.testTag("go_ai_setup_continue")) {
                    Text(if (unfinishedResult) "查看并保存结果" else "继续当前对局")
                }
                Text("继续保留原棋盘、执色与难度；下方设置只用于新局。")
            }
            HorizontalDivider()
            Text("棋盘")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (choice in listOf(9, 13, 19)) FilterChip(size == choice, { size = choice },
                    enabled = enabled, label = { Text("${choice}路") }, modifier = Modifier.testTag("go_ai_setup_size_$choice"))
            }
            Text("执色／模式")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("执黑", "执白", "自动演局").forEachIndexed { index, label ->
                    FilterChip(color == index, { color = index }, enabled = enabled,
                        label = { Text(label) }, modifier = Modifier.testTag("go_ai_setup_color_$index"))
                }
            }
            Text("难度与解锁")
            Text("教程 ${facts?.eligibility?.completedLessonCount ?: 0} / ${GoLearningLibrary.lessons.size} 课；解锁仅代表进度条件，不代表棋力已标定。")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GoAiDifficulty.entries.forEachIndexed { index, level ->
                    val condition = facts?.eligibility?.condition(Difficulty.entries[index])
                    FilterChip(difficultyIndex == index, { difficultyIndex = index },
                        enabled = enabled && condition?.isUnlocked == true,
                        label = { Text(goAiDifficultyLabel(level)) }, modifier = Modifier.testTag("go_ai_setup_difficulty_$index"))
                }
            }
            facts?.eligibility?.conditions?.filter { it.requiredWins > 0 }?.forEach {
                Text("${goAiDifficultyLabel(GoAiDifficulty.entries[Difficulty.entries.indexOf(it.difficulty)])}：上一档有效胜场 ${it.qualifyingWins} / ${it.requiredWins}")
            }
            OutlinedButton(onLearning, enabled = enabled) { Text("前往十课教程") }
            Text("让子训练")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (count in listOf(0) + (2..9)) FilterChip(handicap == count, { handicap = count },
                    enabled = enabled, label = { Text(if (count == 0) "不让子" else "让${count}子") })
            }
            if (handicap > 0) Text("让子局白方先行、不贴子；不计标准人机胜场。")
            val allowed = facts?.eligibility?.condition(Difficulty.entries[difficultyIndex])?.isUnlocked == true &&
                !(facts.phase == GoPhase.FINISHED && !facts.completionStored)
            Button({ onCreate(selected) }, enabled = enabled && allowed,
                modifier = Modifier.fillMaxWidth().testTag("go_ai_setup_create")) { Text("保存并开始新局") }
            OutlinedButton(onBack, enabled = !state.busy, modifier = Modifier.testTag("go_ai_setup_back")) { Text("返回") }
        }
    }
    state.pendingConfig?.let { pending ->
        AlertDialog(onDismissRequest = onDismissAbandon, title = { Text("放弃当前未完成对局？") },
            text = { Text("当前行棋或数子进度将被替换为${pending.boardSize}路新局；已完成棋谱不会删除。只有新局保存成功才替换，失败保留旧局。") },
            confirmButton = { TextButton(onConfirmAbandon, enabled = !state.busy,
                modifier = Modifier.testTag("go_ai_setup_abandon_confirm")) { Text("确认放弃并新建") } },
            dismissButton = { TextButton(onDismissAbandon) { Text("保留当前对局") } })
    }
}
