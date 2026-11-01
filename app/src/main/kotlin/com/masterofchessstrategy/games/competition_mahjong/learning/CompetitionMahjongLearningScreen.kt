package com.masterofchessstrategy.games.competition_mahjong.learning

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.masterofchessstrategy.games.competition_mahjong.competitionMahjongTileName
import com.masterofchessstrategy.settings.LocalDataWriterRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** The shared navigator supplies its Room-backed store and owns difficulty unlock refresh. */
@Composable
internal fun CompetitionMahjongLearningRoute(
    store: GatedCompetitionMahjongLearningProofStore,
    writers: LocalDataWriterRegistry,
    onBack: () -> Unit,
    onCompleted: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val learning: CompetitionMahjongLearningViewModel = viewModel(key = "competition-mahjong-learning:${store.ownerEpoch}",
        factory = remember(store, writers) {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                CompetitionMahjongLearningViewModel(store).also(writers::register) as T
        }
    })
    SideEffect { writers.register(learning) }
    val state by learning.state.collectAsState()
    val scope = rememberCoroutineScope()
    val currentOnBack by rememberUpdatedState(onBack)
    val currentOnCompleted by rememberUpdatedState(onCompleted)
    var leaving by remember(learning) { mutableStateOf(false) }
    var leaveError by remember(learning) { mutableStateOf<String?>(null) }
    fun leave(completed: Boolean) {
        if (leaving) return
        if (completed && (!learning.state.value.tutorialCompleted || store.epoch.value != store.ownerEpoch)) return
        leaving = true
        val completion = learning.beginRetirement()
        scope.launch {
            try {
                completion.await()
                if (completed && store.epoch.value == store.ownerEpoch) currentOnCompleted() else currentOnBack()
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                leaveError = "正在等待教程保存结束，请重试返回。"
                leaving = false
            }
        }
    }
    CompetitionMahjongLearningScreen(state.copy(error = leaveError ?: state.error),
        { leave(false) }, { leave(true) }, learning::selectChapter,
        learning::acknowledgeReading, learning::selectOption, learning::toggleTile,
        learning::submit, learning::advance, learning::load, learning::retrySave, modifier)
}

@Composable
internal fun CompetitionMahjongLearningScreen(
    state: CompetitionMahjongLearningState,
    onBack: () -> Unit,
    onCompleted: () -> Unit,
    onChapter: (Int) -> Unit,
    onReading: () -> Unit,
    onOption: (Int) -> Unit,
    onTile: (Int) -> Unit,
    onSubmit: () -> Unit,
    onAdvance: () -> Unit,
    onRetryLoad: () -> Unit,
    onRetrySave: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var confirmLeave by remember { mutableStateOf(false) }
    fun leave() { if (state.needsSave && !state.retired && !state.busy) confirmLeave = true else onBack() }
    BackHandler(onBack = ::leave)
    Surface(modifier.fillMaxSize().testTag("competition_mahjong_learning")) {
        Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = ::leave) { Text("返回") }
                Column(Modifier.weight(1f)) {
                    Text("国标麻将 · 入门教程", style = MaterialTheme.typography.titleMedium)
                    Text("已保存 ${state.completedChapterCount}/4 章", style = MaterialTheme.typography.bodySmall)
                }
                if (state.tutorialCompleted) {
                    Button(onClick = onCompleted, modifier = Modifier.testTag("competition_mahjong_learning_complete")) {
                        Text("完成教程，返回模式")
                    }
                }
            }
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (state.retired) {
                Text(state.error ?: "请返回后重新进入教程。", modifier = Modifier.testTag("competition_mahjong_learning_retired"))
            } else if (!state.loaded) {
                Text(state.error ?: "正在读取教程进度…", modifier = Modifier.testTag("competition_mahjong_learning_load"))
                if (state.error != null) Button(onClick = onRetryLoad, enabled = !state.busy) { Text("重试读取") }
            } else {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                    .testTag("competition_mahjong_learning_chapters"),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CompetitionMahjongLearningLibrary.chapters.forEachIndexed { index, chapter ->
                        val visibility = remember(index) { BringIntoViewRequester() }
                        LaunchedEffect(state.chapterIndex, state.completedChapterCount) {
                            if (index == state.chapterIndex) {
                                withFrameNanos { }
                                visibility.bringIntoView()
                            }
                        }
                        FilterChip(selected = index == state.chapterIndex,
                            onClick = { onChapter(index) },
                            enabled = state.canOperate && index <= state.completedChapterCount,
                            label = { Text("${index + 1}. ${chapter.title}" +
                                if (index < state.completedChapterCount) " ✓" else if (index > state.completedChapterCount) " · 未解锁" else "") },
                            modifier = Modifier.bringIntoViewRequester(visibility)
                                .testTag("competition_mahjong_learning_chapter_$index"))
                    }
                }
                key(state.card.id) {
                    Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("第${state.chapterIndex + 1}章 · ${state.cardIndex + 1}/${state.chapter.cards.size}",
                            style = MaterialTheme.typography.bodySmall)
                        Text(state.card.title, style = MaterialTheme.typography.titleLarge,
                            modifier = Modifier.testTag("competition_mahjong_learning_card"))
                        Text(state.card.explanation)
                        when (val task = state.card.task) {
                            MahjongLessonTask.Reading -> OutlinedButton(onClick = onReading,
                                enabled = state.canOperate && !state.solved,
                                modifier = Modifier.testTag("competition_mahjong_learning_read")) { Text("我已阅读") }
                            is MahjongLessonTask.Choice -> {
                                task.options.forEachIndexed { index, option ->
                                    FilterChip(selected = index == state.selectedOption,
                                        onClick = { onOption(index) }, enabled = state.canOperate && !state.solved,
                                        label = { Text(option) }, modifier = Modifier.fillMaxWidth()
                                            .testTag("competition_mahjong_learning_option_$index"))
                                }
                            }
                            is MahjongLessonTask.Tiles -> {
                                Text(task.prompt)
                                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    task.tiles.forEachIndexed { index, tile ->
                                        val name = competitionMahjongTileName(tile)
                                        FilterChip(selected = index in state.selectedTiles,
                                            onClick = { onTile(index) }, enabled = state.canOperate && !state.solved,
                                            label = { Text(name) }, modifier = Modifier
                                                .testTag("competition_mahjong_learning_tile_$index")
                                                .semantics { contentDescription = "第${index + 1}张，$name" })
                                    }
                                }
                                Text("已选 ${state.selectedTiles.size} 张；可再次点击取消选择。")
                            }
                        }
                        if (state.card.task != MahjongLessonTask.Reading) {
                            Button(onClick = onSubmit, enabled = state.canOperate && !state.solved &&
                                (state.selectedOption != null || state.selectedTiles.isNotEmpty()),
                                modifier = Modifier.testTag("competition_mahjong_learning_submit")) { Text("检查答案") }
                        }
                        state.feedback?.let { Text(it, modifier = Modifier.testTag("competition_mahjong_learning_feedback")) }
                        state.error?.let {
                            Text(it, color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.testTag("competition_mahjong_learning_error"))
                            if (state.needsSave) Button(onClick = onRetrySave, enabled = !state.busy,
                                modifier = Modifier.testTag("competition_mahjong_learning_retry")) { Text("重试保存") }
                        }
                        Button(onClick = onAdvance, enabled = state.canAdvance,
                            modifier = Modifier.testTag("competition_mahjong_learning_next")) {
                            Text(if (state.cardIndex == state.chapter.cards.lastIndex) "保存本章并继续" else "下一步")
                        }
                        Text("本章通过后自动保存。中途返回，下次从本章开头重新练习。",
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
    if (confirmLeave && state.needsSave && !state.retired) {
        AlertDialog(onDismissRequest = { confirmLeave = false },
            title = { Text("本章进度尚未保存") },
            text = { Text("已保存的章节不受影响。重试可保存本章；现在返回，下次需重新练习本章。") },
            confirmButton = { TextButton(onClick = onRetrySave, enabled = !state.busy) { Text("重试保存") } },
            dismissButton = {
                Column {
                    TextButton(onClick = { confirmLeave = false }) { Text("留在教程") }
                    TextButton(onClick = { confirmLeave = false; onBack() }, enabled = !state.busy,
                        modifier = Modifier.testTag("competition_mahjong_learning_leave_unsaved")) { Text("返回，下次重做本章") }
                }
            })
    }
}
