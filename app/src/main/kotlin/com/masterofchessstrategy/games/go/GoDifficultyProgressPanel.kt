package com.masterofchessstrategy.games.go

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.masterofchessstrategy.engine.Difficulty

/** Read-only product conditions; no button may open an unverified AI route. */
@Composable
internal fun GoDifficultyProgressPanel(state: GoDifficultyProgressState, onRetry: () -> Unit) {
    Column(Modifier.testTag("go_unlock_progress"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("人机难度条件", style = MaterialTheme.typography.titleMedium)
        when {
            state.loading -> Text("正在读取围棋进度…", Modifier.testTag("go_unlock_loading"))
            state.error != null -> {
                Text(state.error, Modifier.testTag("go_unlock_error"))
                TextButton(onClick = onRetry, modifier = Modifier.testTag("go_unlock_retry")) { Text("重试读取") }
            }
            state.eligibility != null -> {
                val facts = state.eligibility
                Text("入门教程：已完成 ${facts.completedLessonCount}/${facts.totalLessonCount} 课",
                    Modifier.testTag("go_unlock_lessons"))
                facts.conditions.forEach { condition ->
                    val requirement = if (condition.precedingDifficulty == null) "完成全部入门教程"
                    else "完成全部教程，且${condition.precedingDifficulty.label()}有效排位胜场 " +
                        "${condition.qualifyingWins}/${condition.requiredWins}"
                    val status = if (condition.isUnlocked) "条件已满足" else "条件未满足"
                    Text("${condition.difficulty.label()}：$status；$requirement",
                        Modifier.testTag("go_unlock_${condition.difficulty.code}"))
                }
            }
            else -> {
                Text("围棋进度尚不可用", Modifier.testTag("go_unlock_error"))
                TextButton(onClick = onRetry, modifier = Modifier.testTag("go_unlock_retry")) { Text("重试读取") }
            }
        }
        Text("以上为本机已保存的解锁条件，不代表棋力标定。本地对局、练习、自动演局和导入棋谱不计标准人机胜场。",
            Modifier.testTag("go_unlock_availability"), style = MaterialTheme.typography.bodyMedium)
    }
}

private fun Difficulty.label(): String = when (this) {
    Difficulty.EASY -> "简单"
    Difficulty.MEDIUM -> "中等"
    Difficulty.HARD -> "困难"
    Difficulty.MASTER -> "大师"
}
