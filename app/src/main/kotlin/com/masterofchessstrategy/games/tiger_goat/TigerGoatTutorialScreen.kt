package com.masterofchessstrategy.games.tiger_goat

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun TigerGoatTutorialScreen(progress: TigerGoatTutorialProgress, onBack: () -> Unit) {
    var count by remember(progress) { mutableStateOf<Int?>(null) }
    var error by remember(progress) { mutableStateOf<String?>(null) }
    var saving by remember(progress) { mutableStateOf(false) }
    var pending by remember(progress) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val index = count
    fun saveStep() {
        if (index == null || saving) return
        pending = true
        saving = true
        error = null
        scope.launch {
            try {
                count = progress.complete(index)
                pending = false
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { error = if (progress.current) "保存失败，请重试。" else "本地数据已替换，请重新打开教程。" }
            finally { saving = false }
        }
    }
    BackHandler { if (!saving) onBack() }
    LaunchedEffect(progress) {
        try { count = progress.load() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { error = "虎羊棋教程记录无法读取。" }
    }
    val step = index?.let(TigerGoatTutorialStep.entries::getOrNull)
    val practice = remember(step) {
        step?.takeIf { it != TigerGoatTutorialStep.QUIZ }?.let(::TigerGoatTutorialPractice)
    }
    var board by remember(practice) { mutableStateOf(practice?.state()) }
    var wrongAnswer by remember(step) { mutableStateOf(false) }
    DisposableEffect(practice) { onDispose { practice?.close() } }
    Column(Modifier.fillMaxSize().padding(16.dp).testTag("tiger_goat_tutorial")) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("老虎吃羊棋教程", style = MaterialTheme.typography.titleLarge)
            TextButton(onClick = onBack, enabled = !saving) { Text("返回对局") }
        }
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            val currentBoard = board
            if (practice != null && currentBoard != null) {
                TigerGoatBoard(practice.spec, currentBoard.copy(busy = saving || pending), { point ->
                    if (!saving && !pending) {
                        val complete = practice.tap(point)
                        board = practice.state()
                        if (complete) saveStep()
                    }
                }, Modifier.weight(1.3f).fillMaxSize())
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                when {
                    pending -> Button(onClick = ::saveStep, enabled = !saving && progress.current) { Text("重试保存") }
                    index == null -> if (error == null) Text("正在读取教程进度…")
                    step == null -> Text("教程已完成 · 已符合简单人机资格", Modifier.testTag("tiger_goat_tutorial_complete"))
                    else -> {
                        Text("${index + 1} / ${TigerGoatTutorialStep.entries.size} · ${step.title}",
                            style = MaterialTheme.typography.titleMedium)
                        Text(step.prompt)
                        if (step == TigerGoatTutorialStep.QUIZ) {
                            OutlinedButton(onClick = { wrongAnswer = true }) { Text("必须继续跳吃") }
                            OutlinedButton(onClick = ::saveStep, modifier = Modifier.testTag("tiger_goat_quiz_correct")) {
                                Text("可以继续跳吃，也可结束本回合")
                            }
                            if (wrongAnswer) Text("连跳可以自愿结束，结束后由羊方行动。")
                        }
                    }
                }
            }
        }
    }
}
