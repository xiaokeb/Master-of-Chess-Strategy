package com.masterofchessstrategy.games.lanzhou_fangqi

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.masterofchessstrategy.games.lanzhou_fangqi.data.FangqiLearningSnapshot

/** Completion labels are derived only from a loaded/committed Store snapshot. */
@Composable
internal fun FangqiLearningReadyScreen(
    progress: FangqiLearningSnapshot,
    busy: Boolean,
    onAcknowledgeLesson: (String) -> Unit,
    onOpenPractice: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("兰州方棋学习", style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.testTag("fangqi_learning_ready"))
        Text(if (progress.tutorialComplete) "六课学习进度已保存" else "阅读讲解后，确认学会再保存进度")
        for (lesson in LanzhouFangqiLearningContent.lessons) {
            Card(Modifier.testTag("fangqi_learning_lesson_${lesson.id}")) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(lesson.title, style = MaterialTheme.typography.titleMedium)
                    Text(lesson.body)
                    Button(onClick = { onAcknowledgeLesson(lesson.id) },
                        enabled = !busy && lesson.id !in progress.completedLessons,
                        modifier = Modifier.testTag("fangqi_learning_ack_${lesson.id}")) {
                        Text(if (lesson.id in progress.completedLessons) "已学会（已保存）" else "我已学会")
                    }
                }
            }
        }
        Text("分步练习", style = MaterialTheme.typography.titleLarge)
        for (practice in LanzhouFangqiLearningContent.practices) {
            Button(onClick = { onOpenPractice(practice.id) }, enabled = !busy,
                modifier = Modifier.testTag("fangqi_learning_practice_${practice.id}")) {
                Text(practice.title + if (practice.id in progress.completedPractices) "（已完成）" else "")
            }
        }
        Button(onClick = onBack, modifier = Modifier.testTag("fangqi_learning_back")) { Text("返回") }
    }
}
