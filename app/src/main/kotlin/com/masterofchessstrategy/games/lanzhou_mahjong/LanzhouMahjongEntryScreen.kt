package com.masterofchessstrategy.games.lanzhou_mahjong

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.masterofchessstrategy.engine.Difficulty
import com.masterofchessstrategy.games.lanzhou_mahjong.data.lanzhouMahjongWinRequirements
import com.masterofchessstrategy.games.lanzhou_mahjong.settlement.LanzhouMahjongStatisticsContent

@Composable
internal fun LanzhouMahjongEntryScreen(
    state: LanzhouMahjongEntryState,
    onLearning: () -> Unit,
    onResume: () -> Unit,
    onNew: (Difficulty) -> Unit,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onRecords: (() -> Unit)? = null,
) {
    var showStatistics by rememberSaveable { mutableStateOf(false) }
    Surface(Modifier.fillMaxSize().testTag("lanzhou_mahjong_entry")) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onBack, modifier = Modifier.testTag("lanzhou_mahjong_entry_back")) { Text("返回") }
            Text("兰州麻将", style = MaterialTheme.typography.titleLarge)
            when {
                state.retired -> Text(state.error ?: "请返回后重新进入兰州麻将")
                state.loading -> Text("正在读取学习与对局进度…")
                state.error != null -> Text(state.error, Modifier.testTag("lanzhou_mahjong_entry_error"))
                state.facts != null -> {
                    val facts = state.facts
                    Text("已掌握 ${facts.learning?.completedCount ?: 0}/10 课",
                        modifier = Modifier.testTag("lanzhou_mahjong_entry_progress"))
                    Button(onLearning, modifier = Modifier.fillMaxWidth().testTag("lanzhou_mahjong_entry_learning")) {
                        Text(if (facts.tutorialCompleted) "复习十课教程" else "学习十课教程")
                    }
                    val active = facts.active
                    if (active != null) {
                        Text("已有${difficultyName(active.record.difficulty)}对局，将保留原难度与存档继续")
                        Button(onResume, modifier = Modifier.fillMaxWidth().testTag("lanzhou_mahjong_entry_resume")) {
                            Text("继续${difficultyName(active.record.difficulty)}对局")
                        }
                    }
                    if (facts.tutorialCompleted && (active == null || facts.activeCompleted)) {
                        for (difficulty in Difficulty.entries.filter { it != active?.record?.difficulty }) {
                            val eligible = facts.canStart(difficulty)
                            val requirement = lanzhouMahjongWinRequirements[difficulty]
                            Button(
                                onClick = { onNew(difficulty) },
                                enabled = eligible,
                                modifier = Modifier.fillMaxWidth()
                                    .testTag("lanzhou_mahjong_entry_new_${difficulty.name.lowercase()}"),
                                colors = ButtonDefaults.buttonColors(
                                    disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                ),
                            ) {
                                if (eligible || requirement == null) {
                                    Text("创建${difficultyName(difficulty)}新局")
                                } else {
                                    val wins = facts.trustedWins[requirement.previous] ?: 0
                                    Text("${difficultyName(difficulty)} · ${difficultyName(requirement.previous)}可信胜场 $wins/${requirement.minimum}")
                                }
                            }
                        }
                    } else if (active == null) {
                        Text("完成十课教程后解锁简单新局")
                    }
                    OutlinedButton(
                        onClick = { showStatistics = !showStatistics },
                        modifier = Modifier.fillMaxWidth().testTag("lanzhou_mahjong_entry_statistics"),
                    ) {
                        Text(if (showStatistics) "收起四席统计" else "查看四席统计")
                    }
                    if (showStatistics) LanzhouMahjongStatisticsContent(facts.statistics)
                    if (onRecords != null) {
                        OutlinedButton(onRecords, modifier = Modifier.fillMaxWidth()
                            .testTag("lanzhou_mahjong_entry_records")) { Text("查看正式棋谱") }
                    }
                }
            }
            if (!state.retired) {
                OutlinedButton(onRefresh, enabled = !state.loading,
                    modifier = Modifier.fillMaxWidth().testTag("lanzhou_mahjong_entry_refresh")) {
                    Text(if (state.error == null) "刷新进度" else "重新读取")
                }
            }
        }
    }
}

internal fun difficultyName(difficulty: Difficulty): String = when (difficulty) {
    Difficulty.EASY -> "简单"
    Difficulty.MEDIUM -> "中等"
    Difficulty.HARD -> "困难"
    Difficulty.MASTER -> "大师"
}
