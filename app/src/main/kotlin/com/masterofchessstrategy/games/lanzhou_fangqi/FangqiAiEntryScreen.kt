package com.masterofchessstrategy.games.lanzhou_fangqi

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.masterofchessstrategy.engine.Difficulty

/** Difficulty conditions remain visible; a disabled choice never silently becomes another tier. */
@Composable
internal fun FangqiAiEntryScreen(
    page: FangqiAiEntryState,
    onSelect: (Int) -> Unit,
    onCreate: () -> Unit,
    onRetryCreate: () -> Unit,
    onResume: () -> Unit,
    onReload: () -> Unit,
    onLearning: () -> Unit,
    onBack: () -> Unit,
) {
    val busy = page.loading || page.creating || page.opening || page.retired
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)
        .testTag("fangqi_ai_entry"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("兰州方棋 · 人机对弈", style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.weight(1f))
            TextButton(onClick = onBack, modifier = Modifier.testTag("fangqi_ai_back")) { Text("返回") }
        }
        if (page.loading) Text("正在读取教程、难度与当前对局", Modifier.testTag("fangqi_ai_loading"))
        if (page.creating) Text("正在确认并保存新局，请稍候", Modifier.testTag("fangqi_ai_creating"))
        if (page.opening) Text("正在打开已保存对局", Modifier.testTag("fangqi_ai_opening"))
        page.error?.let { Text(it, Modifier.testTag("fangqi_ai_error"), color = MaterialTheme.colorScheme.error) }
        if (page.error != null && !busy && !page.pendingCreation) {
            OutlinedButton(onClick = onReload, modifier = Modifier.testTag("fangqi_ai_reload")) { Text("重试读取") }
        }
        if (page.pendingCreation && !page.retired) {
            Button(onClick = onRetryCreate, enabled = !page.creating,
                modifier = Modifier.testTag("fangqi_ai_retry_create")) { Text("重试确认新局") }
        }
        val facts = page.facts
        if (facts != null && !page.retired) {
            OutlinedButton(onClick = onLearning, enabled = !busy && !page.pendingCreation,
                modifier = Modifier.testTag("fangqi_ai_learning")) { Text("学习规则与练习") }
            facts.active?.let { active ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("当前对局：" + when (active.mode) {
                            FangqiStoredMode.LOCAL_TWO_PLAYER -> "本地双人"
                            FangqiStoredMode.HUMAN_VS_AI -> "人机 · ${tierName(active.difficulty!!)}"
                            FangqiStoredMode.AI_AUTO_PLAY -> "自动演局 · ${tierName(active.difficulty!!)}"
                        })
                        Text(when {
                            facts.settledActive -> "本局已结束，可继续查看或明确创建下一局。"
                            facts.formalActive -> "继续原局会保留难度、悔棋次数与已保存走子。"
                            else -> "旧对局按原模式继续，不补记正式胜场。"
                        })
                        Button(onClick = onResume, enabled = !busy && !page.pendingCreation,
                            modifier = Modifier.testTag("fangqi_ai_resume")) { Text("继续当前对局") }
                    }
                }
            }
            if (page.difficultyCode != null && Difficulty.entries.none { it.code == page.difficultyCode }) {
                Text("原难度选择已失效，请重新选择", color = MaterialTheme.colorScheme.error)
            }
            facts.eligibility.conditions.forEach { condition ->
                Card(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        RadioButton(selected = page.difficultyCode == condition.difficulty.code,
                            onClick = { onSelect(condition.difficulty.code) },
                            enabled = condition.isUnlocked && !busy && !page.pendingCreation,
                            modifier = Modifier.testTag("fangqi_ai_difficulty_${condition.difficulty.code}"))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(tierName(condition.difficulty), style = MaterialTheme.typography.titleMedium)
                            Text(conditionText(condition), Modifier.testTag("fangqi_ai_condition_${condition.difficulty.code}"))
                        }
                    }
                }
            }
            val eligible = facts.eligibility.select(page.difficultyCode) is FangqiAiSelection.Eligible
            Button(onClick = onCreate, enabled = eligible && !busy && !page.pendingCreation &&
                (facts.active == null || facts.settledActive), modifier = Modifier.testTag("fangqi_ai_create")) {
                Text(if (facts.settledActive) "创建下一局" else "创建正式对局")
            }
            if (facts.active != null && !facts.settledActive) Text("请先继续当前对局；新建不会覆盖未结束的对局。")
        }
    }
}

private fun tierName(difficulty: Difficulty) = when (difficulty) {
    Difficulty.EASY -> "简单"; Difficulty.MEDIUM -> "中等"; Difficulty.HARD -> "困难"; Difficulty.MASTER -> "大师"
}

private fun conditionText(condition: FangqiAiDifficultyCondition): String = when (condition.blockedReason) {
    FangqiAiBlockReason.LEARNING_UNAVAILABLE -> "教程进度暂不可用，请重试读取"
    FangqiAiBlockReason.LEARNING_INCOMPATIBLE -> "教程记录不兼容，原记录已保留，请重试读取"
    FangqiAiBlockReason.TUTORIAL_INCOMPLETE -> "完成六课教程后解锁（${condition.completedLessons ?: 0}/${condition.totalLessons}）"
    FangqiAiBlockReason.QUALIFIED_WINS_UNAVAILABLE -> "正式胜场暂不可用，请重试读取"
    FangqiAiBlockReason.QUALIFIED_WINS_INCOMPATIBLE -> "正式胜场记录不兼容，原记录已保留"
    FangqiAiBlockReason.INSUFFICIENT_WINS -> "${tierName(checkNotNull(condition.precedingDifficulty))}正式胜场 " +
        "${condition.qualifyingWins}/${condition.requiredWins}，还需${condition.remainingWins}胜"
    null -> "已解锁"
}

/** Back/recreation await the VM-owned operation; navigation receives only a confirmed saved ID. */
@Composable
internal fun FangqiAiEntryRoute(model: FangqiAiEntryViewModel, onOpen: (FangqiAiEntryLaunch) -> Unit,
    onLearning: () -> Unit, onBack: () -> Unit,
) {
    val page by model.state.collectAsState()
    val currentOpen by rememberUpdatedState(onOpen)
    val currentBack by rememberUpdatedState(onBack)
    var exitRequested by rememberSaveable(model) { mutableStateOf(false) }
    var backDelivered by rememberSaveable(model) { mutableStateOf(false) }
    val leave = {
        model.beginRetirement()
        exitRequested = true
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { model.resume() }
    LaunchedEffect(model, page.launch) {
        if (!exitRequested) model.consumeLaunch()?.let(currentOpen)
    }
    LaunchedEffect(model, exitRequested) {
        if (exitRequested && !backDelivered) {
            model.beginRetirement().await()
            backDelivered = true
            currentBack()
        }
    }
    BackHandler(onBack = leave)
    if (exitRequested && !backDelivered) {
        Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
            Text("正在等待操作确认后返回", Modifier.testTag("fangqi_ai_leaving"))
        }
    } else FangqiAiEntryScreen(page, model::selectDifficulty, model::createNew, model::retryCreate,
        model::resumeActive, model::reload, onLearning, leave)
}
