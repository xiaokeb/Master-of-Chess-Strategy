package com.masterofchessstrategy.games.lanzhou_fangqi

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/** Root supplies a retained, registered VM. Composition owns only a reconnectable exit intent. */
@Composable
internal fun FangqiLearningRoute(model: FangqiLearningViewModel, onBack: () -> Unit) {
    val page by model.state.collectAsState()
    val currentBack by rememberUpdatedState(onBack)
    var exitRequested by rememberSaveable(model) { mutableStateOf(false) }
    var exitToLessons by rememberSaveable(model) { mutableStateOf(false) }
    var exitNonce by rememberSaveable(model) { mutableStateOf(0) }
    var backDelivered by rememberSaveable(model) { mutableStateOf(false) }
    val requestBack: () -> Unit = {
        if (!backDelivered && (!exitRequested || page.error != null)) {
            if (!exitRequested) exitToLessons = page.practiceId != null
            exitRequested = true
            exitNonce++
        }
    }
    LaunchedEffect(model, exitRequested, exitNonce) {
        if (exitRequested && !backDelivered) {
            val finished = if (exitToLessons) model.backToLessons(exitNonce) else model.leave(exitNonce)
            if (finished) {
                if (exitToLessons) exitRequested = false else {
                    backDelivered = true
                    currentBack()
                }
            }
        }
    }
    BackHandler(onBack = requestBack)
    Box(Modifier.fillMaxSize()) {
        if (!page.leaving && !page.retired) {
            val progress = page.progress
            val session = page.session
            val interaction = page.interaction
            if (page.practiceId != null && session != null && interaction != null) {
                val practice = LanzhouFangqiLearningContent.practices.single { it.id == page.practiceId }
                Column(Modifier.fillMaxSize()) {
                    Text(practice.title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(12.dp))
                    Text(practice.instruction, modifier = Modifier.padding(horizontal = 12.dp))
                    val saved = progress != null && practice.id in progress.completedPractices
                    // Reserve the status line so completing a puzzle does not move the board.
                    Text(if (saved) "本题通关进度已保存" else "完成练习后保存进度",
                        Modifier.padding(horizontal = 12.dp).testTag(
                            if (saved) "fangqi_learning_practice_saved" else "fangqi_learning_practice_status"))
                    if (page.error != null) Text(page.error!!, Modifier.padding(8.dp).testTag("fangqi_learning_error"),
                        color = MaterialTheme.colorScheme.error)
                    LanzhouFangqiScreen(session, requestBack, Modifier.weight(1f),
                        interactionOverride = interaction, aiManagedExternally = true)
                }
            } else if (page.practiceId == null && progress != null) {
                Column(Modifier.fillMaxSize()) {
                    if (page.pendingContentKey != null) {
                        Text(if (page.saving) "正在保存学习进度" else page.error ?: "进度尚未保存",
                            Modifier.padding(12.dp).testTag("fangqi_learning_progress_pending"))
                        if (!page.saving) Button(onClick = model::retrySave,
                            modifier = Modifier.padding(horizontal = 12.dp).testTag("fangqi_learning_retry_save")) {
                            Text("重试保存")
                        }
                    }
                    FangqiLearningReadyScreen(progress, busy = page.pendingContentKey != null,
                        onAcknowledgeLesson = model::acknowledgeLesson, onOpenPractice = model::openPractice,
                        onBack = requestBack, modifier = Modifier.weight(1f))
                }
            } else {
                Column(Modifier.align(Alignment.Center).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(if (page.loading) "正在读取方棋学习进度" else if (page.openingPractice) "正在准备练习"
                        else page.error ?: "方棋学习暂不可用",
                        Modifier.testTag(if (page.loading || page.openingPractice) "fangqi_learning_loading" else "fangqi_learning_error"))
                    if (!page.loading && !page.openingPractice && page.practiceId == null) {
                        Button(onClick = model::retryLoad) { Text("重试读取") }
                    }
                    Button(onClick = requestBack) { Text(if (page.practiceId != null) "返回课程" else "返回") }
                }
            }
        }
        if (page.leaving || page.retired) {
            Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).padding(24.dp),
                verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                Text(page.error ?: if (page.retired) "方棋学习页面已结束，请重新进入" else "正在保存进度并结束练习，请稍候",
                    Modifier.testTag(if (page.error == null) "fangqi_learning_leaving" else "fangqi_learning_error"))
                if (!page.retired && page.error != null && !page.closeFailed) {
                    if (page.pendingContentKey != null && !page.saving) {
                        Button(onClick = model::retrySave, modifier = Modifier.testTag("fangqi_learning_retry_save")) { Text("重试保存") }
                    }
                    Button(onClick = requestBack, modifier = Modifier.testTag("fangqi_learning_retry_back")) { Text("重试返回") }
                }
            }
        }
    }
}
