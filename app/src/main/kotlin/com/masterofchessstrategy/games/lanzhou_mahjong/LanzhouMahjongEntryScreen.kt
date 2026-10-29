package com.masterofchessstrategy.games.lanzhou_mahjong

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.masterofchessstrategy.engine.Difficulty

@Composable
internal fun LanzhouMahjongEntryScreen(
    state: LanzhouMahjongEntryState,
    onLearning: () -> Unit,
    onResume: () -> Unit,
    onNewEasy: () -> Unit,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
) {
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
                    } else if (facts.tutorialCompleted) {
                        Button(onNewEasy, modifier = Modifier.fillMaxWidth().testTag("lanzhou_mahjong_entry_new_easy")) {
                            Text("创建简单新局")
                        }
                    } else {
                        Text("完成十课教程后解锁简单新局")
                    }
                    Text("后续难度", style = MaterialTheme.typography.titleMedium)
                    Text("中等：简单累计胜10场\n困难：中等累计胜15场\n大师：困难累计胜20场")
                    Text("可信胜场来源尚未接入，中等、困难与大师暂未开放",
                        modifier = Modifier.testTag("lanzhou_mahjong_entry_trusted_wins_pending"))
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

private fun difficultyName(difficulty: Difficulty): String = when (difficulty) {
    Difficulty.EASY -> "简单"
    Difficulty.MEDIUM -> "中等"
    Difficulty.HARD -> "困难"
    Difficulty.MASTER -> "大师"
}
