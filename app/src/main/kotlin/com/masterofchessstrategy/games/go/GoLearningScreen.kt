package com.masterofchessstrategy.games.go

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.masterofchessstrategy.engine.BoardPosition
import com.masterofchessstrategy.engine.GameResult
import com.masterofchessstrategy.engine.GoPlayer

@Composable
fun GoLearningRoute(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    repository: GoLearningProgressRepository? = null,
    viewModelKey: String = "go-learning",
    onWriterReady: (ViewModel) -> Unit = {},
) {
    val context = LocalContext.current.applicationContext
    val progress = remember(context, repository) { repository ?: GoLearningPreferencesRepository(context) }
    val lessons: GoLearningViewModel = viewModel(key = viewModelKey, factory = remember(progress, onWriterReady) {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = GoLearningViewModel(progress).also(onWriterReady) as T
        }
    })
    onWriterReady(lessons)
    val state by lessons.state.collectAsState()
    GoLearningScreen(state, lessons::answer, lessons::selectLesson, { lessons.restart() },
        lessons::retry, onBack, modifier)
}

@Composable
fun GoLearningScreen(
    state: GoLearningUiState,
    onAnswer: (GoLessonAnswer) -> Unit,
    onSelectLesson: (String) -> Unit,
    onRestart: () -> Unit,
    onRetrySave: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showCatalog by remember { mutableStateOf(false) }
    val exercise = state.exercise
    val lesson = exercise?.lesson
    Surface(modifier.fillMaxSize()) {
        BoxWithConstraints(Modifier.fillMaxSize().padding(12.dp)) {
            val board: @Composable (Modifier) -> Unit = { boardModifier ->
                if (exercise != null) {
                    val selectsPoint = lesson!!.goal is GoLessonGoal.FindLiberties ||
                        lesson.goal is GoLessonGoal.Place || lesson.goal is GoLessonGoal.ForbiddenPoint
                    GoBoard(lesson.boardSize, exercise.stones,
                        { x, y -> onAnswer(GoLessonAnswer.Point(BoardPosition(x, y))) }, boardModifier,
                        highlightedPoints = exercise.selectedPoints,
                        deadStoneIndices = exercise.deadStoneIndices,
                        interactionEnabled = state.canOperate && !exercise.completed && selectsPoint)
                } else {
                    Column(boardModifier.padding(16.dp)) { Text(if (state.loading) "正在打开教程…" else "教程暂不可用") }
                }
            }
            val controls: @Composable (Modifier) -> Unit = { controlsModifier ->
                Column(controlsModifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("围棋入门", style = MaterialTheme.typography.titleLarge)
                        TextButton(onClick = onBack) { Text("返回") }
                    }
                    Text("已完成 ${state.progress.completedLessonIds.size}/${GoLearningLibrary.lessons.size}")
                    TextButton(onClick = { showCatalog = true }, enabled = state.canOperate) { Text("知识点目录") }
                    if (exercise != null && lesson != null) {
                        val index = GoLearningLibrary.lessons.indexOfFirst { it.id == lesson.id }
                        Text("${index + 1}. ${lesson.title}", style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.testTag("go_lesson_title"))
                        Text(lesson.explanation)
                        Text(lesson.instruction, style = MaterialTheme.typography.bodyLarge)
                        Text(exercise.feedback, modifier = Modifier.testTag("go_lesson_feedback"))
                        val enabled = state.canOperate && !exercise.completed
                        when (val goal = lesson.goal) {
                            GoLessonGoal.SecondPass -> Button(onClick = { onAnswer(GoLessonAnswer.Pass) }, enabled = enabled) {
                                Text("虚着")
                            }
                            is GoLessonGoal.Confirm -> Button(onClick = { onAnswer(GoLessonAnswer.Confirm(goal.actor)) }, enabled = enabled) {
                                Text(if (goal.actor == GoPlayer.BLACK) "黑方确认" else "白方确认")
                            }
                            is GoLessonGoal.Resume -> Button(onClick = { onAnswer(GoLessonAnswer.Resume(goal.actor)) }, enabled = enabled) {
                                Text(if (goal.actor == GoPlayer.BLACK) "由黑方先行续弈" else "由白方先行续弈")
                            }
                            is GoLessonGoal.PredictResult -> {
                                for ((result, label) in listOf(GameResult.FIRST_PLAYER_WIN to "黑方领先",
                                    GameResult.SECOND_PLAYER_WIN to "白方领先", GameResult.DRAW to "两方相同")) {
                                    OutlinedButton(onClick = { onAnswer(GoLessonAnswer.Result(result)) }, enabled = enabled) { Text(label) }
                                }
                            }
                            else -> Unit
                        }
                        if (exercise.completed) Text("本项完成", color = MaterialTheme.colorScheme.primary)
                        OutlinedButton(onClick = onRestart, enabled = state.canOperate) { Text("重新练习本项") }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { onSelectLesson(GoLearningLibrary.lessons[index - 1].id) },
                                enabled = state.canOperate && index > 0) { Text("上一知识点") }
                            Button(onClick = { onSelectLesson(GoLearningLibrary.lessons[index + 1].id) },
                                enabled = state.canOperate && index < GoLearningLibrary.lessons.lastIndex) { Text("下一知识点") }
                        }
                    }
                    if (state.tutorialCompleted) Text("入门教程完成，简单难度的教程条件已满足。")
                    if (state.saving) Text("正在保存教程进度…")
                    if (state.error != null) {
                        Text(state.error, color = MaterialTheme.colorScheme.error)
                        Button(onClick = onRetrySave, enabled = !state.loading && !state.saving) { Text("重试") }
                    }
                }
            }
            if (maxWidth > maxHeight) {
                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    board(Modifier.weight(1f).fillMaxSize())
                    controls(Modifier.width(320.dp))
                }
            } else {
                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    board(Modifier.fillMaxWidth().weight(1f))
                    controls(Modifier.fillMaxWidth().height(300.dp))
                }
            }
        }
    }
    if (showCatalog) {
        AlertDialog(onDismissRequest = { showCatalog = false }, title = { Text("围棋知识点") }, text = {
            Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                for (item in GoLearningLibrary.lessons) {
                    TextButton(onClick = { showCatalog = false; onSelectLesson(item.id) }) {
                        Text(item.title + if (item.id in state.progress.completedLessonIds) " · 已完成" else "")
                    }
                }
            }
        }, confirmButton = { TextButton(onClick = { showCatalog = false }) { Text("关闭") } })
    }
}
