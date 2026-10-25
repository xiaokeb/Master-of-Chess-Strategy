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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.testTag

/** Root retains/registers the VM; this surface owns neither AI nor the application save queue. */
@Composable
internal fun FangqiGameRoute(model: FangqiGameViewModel, onBack: () -> Unit) {
    val page by model.state.collectAsState()
    val currentBack by rememberUpdatedState(onBack)
    var exitRequested by rememberSaveable(model) { mutableStateOf(false) }
    var retryNonce by rememberSaveable(model) { mutableStateOf(0) }
    var backDelivered by rememberSaveable(model) { mutableStateOf(false) }
    val leave: () -> Unit = {
        if (!backDelivered && (!exitRequested || page.error != null)) {
            exitRequested = true
            retryNonce++
        }
    }
    // Rotation cancels only this waiter. The same saved intent reconnects to the VM-owned attempt.
    LaunchedEffect(model, exitRequested, retryNonce) {
        if (exitRequested && !backDelivered && model.leave(retryNonce)) {
            backDelivered = true
            currentBack()
        }
    }
    BackHandler(onBack = leave)
    Box(Modifier.fillMaxSize()) {
        val session = page.session
        val interaction = page.interaction
        if (session != null && interaction != null && !page.retired) {
            Column(Modifier.fillMaxSize()) {
                Text(when (page.mode) {
                    FangqiStoredMode.HUMAN_VS_AI -> "人机对弈"
                    FangqiStoredMode.AI_AUTO_PLAY -> "AI自动演局"
                    else -> "本地双人"
                }, Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
                if (page.error != null && !page.leaving) {
                    Text(page.error!!, Modifier.padding(8.dp).testTag("fangqi_owner_error"), color = MaterialTheme.colorScheme.error)
                    if (page.retryAi) Button(onClick = model::retryAi) { Text("重试AI") }
                }
                LanzhouFangqiScreen(session, onBack = leave, modifier = Modifier.weight(1f),
                    aiDifficulty = page.difficulty, interactionOverride = interaction, aiManagedExternally = true,
                    undoAllowed = page.undoAllowed)
            }
        } else if (!page.leaving) {
            Column(Modifier.align(Alignment.Center).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(if (page.loading) "正在读取并保存方棋对局" else page.error ?: "方棋对局暂不可用",
                    Modifier.testTag(if (page.loading) "fangqi_owner_loading" else "fangqi_owner_error"))
                if (page.retryLoad) Button(onClick = model::retryLoad) { Text("重试读取") }
                if (page.retrySave) Button(onClick = model::retrySave) { Text("重试保存") }
                Button(onClick = leave) { Text("返回") }
            }
        }
        if (page.leaving) {
            // Cover the board while its interaction fence rejects all further operations.
            Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).padding(24.dp),
                verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                Text(if (page.retired) "本地数据已替换，请重新进入方棋" else page.error ?: "正在保存对局，请稍候",
                    Modifier.testTag(if (page.error != null) "fangqi_owner_error" else "fangqi_owner_loading"))
                if (!page.retired && page.error != null) Button(onClick = leave,
                    modifier = Modifier.testTag("fangqi_owner_exit_retry")) { Text("重试返回") }
            }
        }
    }
}
