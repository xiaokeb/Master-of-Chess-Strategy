package com.masterofchessstrategy.games.lanzhou_mahjong

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

@Composable
internal fun LanzhouMahjongLearningRoute(viewModel: LanzhouMahjongLearningViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsState()
    val scope = rememberCoroutineScope()
    val currentOnBack by rememberUpdatedState(onBack)
    var leaving by remember(viewModel) { mutableStateOf(false) }
    val leave: () -> Unit = {
        if (!leaving) {
            leaving = true
            val completion = viewModel.beginRetirement()
            scope.launch {
                completion.await()
                currentOnBack()
            }
        }
    }
    BackHandler(onBack = leave)
    LanzhouMahjongLearningScreen(state, viewModel::selectLesson, viewModel::submit, viewModel::reload,
        leave, viewModel::retrySave)
}

@Composable
internal fun LanzhouMahjongLearningScreen(
    state: LanzhouMahjongLearningState,
    onSelectLesson: (Int) -> Unit,
    onAnswer: (Int) -> Unit,
    onReload: () -> Unit,
    onBack: () -> Unit,
    onRetrySave: () -> Unit,
) {
    Surface(Modifier.fillMaxSize().testTag("lanzhou_mahjong_learning")) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onBack, enabled = !state.isBusy) { Text("返回") }
                Text("兰州麻将 · 规则与练习", style = MaterialTheme.typography.titleLarge)
            }
            if (state.retired) {
                Text(state.feedback ?: "请返回后重新进入教程")
            } else if (!state.loaded) {
                Text(state.feedback ?: "正在读取教程进度…")
                if (!state.isBusy && state.feedback != null) Button(onReload) { Text("重新读取") }
            } else {
                val lessons = LanzhouMahjongLearningLibrary.lessons
                Text("已掌握 ${state.completedCount}/${lessons.size} 课")
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    lessons.forEachIndexed { index, lesson ->
                        OutlinedButton({ onSelectLesson(index) },
                            enabled = !state.isBusy && !state.hasPendingSave && index <= state.completedCount,
                            modifier = Modifier.testTag("lanzhou_mahjong_lesson_$index")) {
                            Text("${index + 1} · ${lesson.title}")
                        }
                    }
                }
                Text(state.lesson.title, style = MaterialTheme.typography.titleLarge)
                Text(state.lesson.explanation)
                if (state.lesson.exampleHand.isNotEmpty()) {
                    Text("示例手牌：" + state.lesson.exampleHand.joinToString(" ", transform = ::tileName))
                }
                Text(state.lesson.question, style = MaterialTheme.typography.titleMedium)
                state.lesson.options.forEachIndexed { index, option ->
                    Button({ onAnswer(index) }, enabled = !state.isBusy && !state.hasPendingSave,
                        modifier = Modifier.fillMaxWidth().testTag("lanzhou_mahjong_answer_$index")) {
                        Text(option)
                    }
                }
                state.feedback?.let { Text(it, modifier = Modifier.testTag("lanzhou_mahjong_learning_feedback")) }
                if (state.requiresReload) {
                    Button(onReload, enabled = !state.isBusy,
                        modifier = Modifier.fillMaxWidth().testTag("lanzhou_mahjong_learning_reload")) {
                        Text("重新读取教程进度")
                    }
                } else if (state.hasPendingSave) {
                    Button(onRetrySave, enabled = !state.isBusy,
                        modifier = Modifier.fillMaxWidth().testTag("lanzhou_mahjong_learning_retry")) {
                        Text("重试保存本题")
                    }
                }
                if (state.isCompleted) {
                    Text("教程已完成", style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.testTag("lanzhou_mahjong_learning_complete"))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton({ onSelectLesson(state.lessonIndex - 1) },
                        enabled = !state.isBusy && !state.hasPendingSave && state.lessonIndex > 0) { Text("上一课") }
                    OutlinedButton({ onSelectLesson(state.lessonIndex + 1) },
                        enabled = !state.isBusy && !state.hasPendingSave && state.lessonIndex < lessons.lastIndex &&
                            state.lessonIndex < state.completedCount) { Text("下一课") }
                }
            }
        }
    }
}
