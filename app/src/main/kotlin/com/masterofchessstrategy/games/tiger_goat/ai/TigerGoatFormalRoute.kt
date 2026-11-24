package com.masterofchessstrategy.games.tiger_goat.ai

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.masterofchessstrategy.games.tiger_goat.TigerGoatGameScreen
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
internal fun TigerGoatFormalRoute(model: TigerGoatFormalViewModel, onBack: () -> Unit) {
    val page by model.uiState.collectAsState()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()
    var leaving by remember { mutableStateOf(false) }
    var confirmLeave by remember { mutableStateOf(false) }
    var exitError by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(model, lifecycle, page.thinking) {
        if (page.thinking) lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                model.advance()
                delay(50)
            }
        }
    }
    fun leaveNow() {
        if (leaving) return
        leaving = true
        scope.launch {
            try {
                model.beginRetirement().await()
                onBack()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                exitError = "对局资源释放失败，请重试返回"
            } finally {
                leaving = false
            }
        }
    }
    fun leave() {
        if (!page.retired && (page.busy || page.canRetrySave)) confirmLeave = true
        else leaveNow()
    }
    BackHandler { leave() }
    val spec = page.boardSpec
    val game = page.game
    if (spec != null && game != null) {
        val side = if (page.config?.humanSide == 2) "执羊" else "执虎"
        TigerGoatGameScreen(spec, game.copy(feedback = if (page.thinking) "AI 正在行棋…" else game.feedback),
            model::tap, model::endChain, model::undo, model::restart, model::resign, ::leave, model::passGoat,
            modifier = Modifier.testTag("tiger_goat_formal_board"), title = "正式人机 · 简单 · $side")
    } else {
        Column(Modifier.padding(24.dp)) {
            Text("正在载入正式人机…")
            TextButton(onClick = ::leave) { Text("返回") }
        }
    }
    val error = exitError ?: page.error ?: if (page.retired && !leaving) "请返回后重新打开虎羊棋" else null
    if (error != null && !confirmLeave) {
        AlertDialog(onDismissRequest = {}, title = { Text("正式人机暂未就绪") }, text = { Text(error) },
            confirmButton = { TextButton(onClick = ::leaveNow, enabled = !leaving) { Text("返回") } },
            dismissButton = {
                if (!page.retired) when {
                    page.canRetrySave -> TextButton(onClick = model::retrySave) { Text("重试保存") }
                    page.canRetryAi -> Column {
                        TextButton(onClick = model::retryAi) { Text("重试 AI") }
                        if (game?.canUndo == true) TextButton(onClick = model::undo) { Text("悔棋") }
                    }
                    page.recoveryBlocked -> TextButton(onClick = model::retryLoad) { Text("重试载入") }
                    else -> Unit
                }
            })
    }
    if (confirmLeave) {
        AlertDialog(onDismissRequest = { confirmLeave = false }, title = { Text("对局正在保存") },
            text = { Text("退出后将从最后成功保存的棋局继续。") },
            confirmButton = { TextButton(onClick = { confirmLeave = false; leaveNow() }) { Text("仍然返回") } },
            dismissButton = { TextButton(onClick = { confirmLeave = false }) { Text("取消") } })
    }
}
