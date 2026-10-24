package com.masterofchessstrategy.games.go

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import com.masterofchessstrategy.engine.*
import kotlin.math.abs

@Composable
fun GoAiMatchScreen(
    page: GoAiMatchPageState,
    scoringActor: GoPlayer,
    displayMode: GoScoreDisplayMode,
    onAction: (GoAction) -> Unit,
    onActor: (GoPlayer) -> Unit,
    onDisplayMode: () -> Unit,
    onUndo: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onSpeed: (Float) -> Unit,
    onRetryModel: () -> Unit,
    onRetrySearch: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onHint: () -> Unit,
    onRestart: () -> Unit,
    onAutomatic: (Boolean) -> Unit,
    onAutoContinuation: (Boolean, Int) -> Unit,
    onRetryAutoContinuation: () -> Unit,
    openingMessage: String?,
    pendingOpening: GoAiOpening?,
    onImportOpening: () -> Unit,
    onDismissOpening: () -> Unit,
    onConfirmOpening: () -> Unit,
    onRetryCompletion: () -> Unit = {},
) {
    var restartDialog by remember { mutableStateOf(false) }
    Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        BoxWithConstraints(Modifier.fillMaxSize().padding(12.dp)) {
            val wide = maxWidth > maxHeight
            val controlsWidth = (maxWidth * 0.44f).coerceAtMost(410.dp)
            val boardHeight = maxHeight * 0.52f
            val match = page.match
            val game = match?.game
            val board: @Composable (Modifier) -> Unit = { target ->
                if (game != null) GoBoard(game.boardSize, game.stones, { x, y ->
                    onAction(if (game.phase == GoPhase.SCORING) GoAction.ToggleDeadGroup(BoardPosition(x, y), scoringActor)
                        else GoAction.Place(BoardPosition(x, y)))
                }, target, lastMoveIndex = game.lastMoveIndex, deadStoneIndices = game.deadStoneIndices,
                    highlightedPoints = match.hint?.highlightedPoints ?: emptySet(),
                    interactionEnabled = match.humanCanPlay || match.canReviewScore)
            }
            val controls: @Composable (Modifier) -> Unit = { target ->
                Column(target.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(if (match?.config?.automatic == true) "围棋 · 自动演局" else "围棋 · 人机对局", style = MaterialTheme.typography.titleLarge)
                        TextButton(onBack) { Text("返回") }
                    }
                    if (page.loadingModel) Text("正在准备围棋 AI…", modifier = Modifier.testTag("go_ai_loading_model"))
                    page.error?.let {
                        Text(it, color = MaterialTheme.colorScheme.error)
                        Button(onRetryModel) { Text("重试加载") }
                    }
                    openingMessage?.let { Text(it, modifier = Modifier.testTag("go_ai_opening_message")) }
                    if (match != null && game != null) {
                        Text("${game.boardSize} 路 · ${goAiDifficultyLabel(match.config.difficulty)}")
                        if (game.handicapStones > 0) Text("让 ${game.handicapStones} 子 · 白方先行")
                        match.config.humanPlayer?.let { Text("你执${goReplayPlayer(it)}") }
                        if (match.hasSpecifiedOpening) Text("指定开局训练局 · 重开回到原开局 · 不计标准人机胜场")
                        Text(goAiMatchPhaseLabel(match), modifier = Modifier.testTag("go_ai_phase"))
                        if (game.loading) Text("正在恢复对局…")
                        if (game.saving) Text("正在保存对局…")
                        if (match.usesImmediateHumanCompletion() && game.phase == GoPhase.FINISHED &&
                            (match.completionReserved || match.completionPending || match.completionStored)) {
                            if (match.completionReserved || match.completionStored)
                                Text("本局结果已固定，不能再悔棋。", modifier = Modifier.testTag("go_ai_completion_fixed"))
                            else Text("本局待完成保存，暂不能悔棋或重开。",
                                modifier = Modifier.testTag("go_ai_completion_fixed_pending"))
                            Text(when {
                                game.saving -> "正在保存完成棋谱…"
                                match.completionStored -> "完成棋谱已保存。"
                                match.completionPending -> "完成棋谱保存未完成，请重试保存。"
                                else -> "本局完成棋谱尚待保存。"
                            }, modifier = Modifier.testTag("go_ai_completion_status"))
                            if (match.completionPending && !match.completionStored) OutlinedButton(onRetryCompletion,
                                enabled = !match.closed && game.canOperate,
                                modifier = Modifier.testTag("go_ai_completion_retry")) { Text("重试保存本局") }
                            Text("棋谱保存不代表已计入人机胜场，成长以正式来源与结算为准。")
                        }
                        game.error?.let {
                            Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("go_ai_error"))
                            if (game.canAct && game.phase == GoPhase.PLAYING && match.config.isAi(game.currentPlayer) && !match.paused)
                                OutlinedButton(onRetrySearch) { Text("重试思考") }
                        }
                        if (game.phase == GoPhase.PLAYING) {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(if (match.paused) onResume else onPause,
                                    enabled = !match.closed && game.canOperate,
                                    modifier = Modifier.testTag("go_ai_pause")) { Text(if (match.paused) "继续思考" else "暂停 AI") }
                                OutlinedButton({ onAction(GoAction.Pass) }, enabled = match.humanCanPlay,
                                    modifier = Modifier.testTag("go_ai_pass")) { Text("虚着") }
                                if (!match.config.automatic) OutlinedButton(onHint, enabled = match.canRequestHint,
                                    modifier = Modifier.testTag("go_ai_hint")) { Text("提示") }
                                OutlinedButton({ onAutomatic(!match.config.automatic) }, enabled = match.canSwitchMode,
                                    modifier = Modifier.testTag("go_ai_mode")) {
                                    Text(if (match.config.automatic) "返回人机（你执${goReplayPlayer(match.rememberedHuman)}）" else "自动接管双方")
                                }
                            }
                            Text(if (match.automaticUsed) "本局使用过自动控制，不计人机胜场。"
                                else "本局使用自动演局后，不计人机胜场。")
                            if (match.hintThinking) Text("正在准备走子提示…", modifier = Modifier.testTag("go_ai_hint_thinking"))
                            match.hint?.let { hint ->
                                Text(if (hint.allLegal && hint.highlightedPoints.isEmpty()) "当前没有合法落点，可以虚着。"
                                    else if (hint.allLegal) "已高亮全部合法落点；你也可以虚着。"
                                    else if (hint.passSuggested) "走子建议：虚着。"
                                    else hint.highlightedPoints.single().let { "走子建议：第${it / game.boardSize + 1}行第${it % game.boardSize + 1}列。" },
                                    modifier = Modifier.testTag("go_ai_hint_result"))
                            }
                            if (!match.config.automatic && match.config.difficulty.hintLimit != Int.MAX_VALUE)
                                Text("剩余提示：${(match.config.difficulty.hintLimit - match.hintUsed).coerceAtLeast(0)}次")
                            if (match.config.automatic) {
                                Text("演局速度 ${match.speed}x")
                                Slider(match.speed, onSpeed, valueRange = 0.5f..4f, enabled = !match.closed,
                                    modifier = Modifier.testTag("go_ai_speed"))
                            }
                        }
                        if (game.phase == GoPhase.SCORING) {
                            Text("双方确认同一死子提议后才结束；有争议时继续行棋。")
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                for (actor in GoPlayer.entries) FilterChip(scoringActor == actor, { onActor(actor) },
                                    enabled = match.canReviewScore, label = { Text("${goReplayPlayer(actor)}方标记") })
                            }
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button({ onAction(GoAction.ConfirmScore(GoPlayer.BLACK, game.proposalRevision)) },
                                    enabled = match.canReviewScore && !game.blackConfirmed) { Text(if (game.blackConfirmed) "黑方已确认" else "黑方确认") }
                                Button({ onAction(GoAction.ConfirmScore(GoPlayer.WHITE, game.proposalRevision)) },
                                    enabled = match.canReviewScore && !game.whiteConfirmed) { Text(if (game.whiteConfirmed) "白方已确认" else "白方确认") }
                            }
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                for (actor in GoPlayer.entries) OutlinedButton({ onAction(GoAction.ResumePlay(actor)) },
                                    enabled = match.canReviewScore) { Text("${goReplayPlayer(actor)}方主张死子，续弈") }
                            }
                        }
                        if (match.config.automatic) {
                            HorizontalDivider()
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Column(Modifier.weight(1f)) {
                                    Text("自动续局", style = MaterialTheme.typography.titleMedium)
                                    Text("仅在双方确认正式终局并保存完成棋谱后开始下一局。")
                                }
                                Switch(match.autoContinueEnabled, {
                                    onAutoContinuation(it, if (it) maxOf(3, match.autoContinueMaxGames) else 1)
                                }, enabled = page.completedStorageReady && match.canConfigureAutoContinue,
                                    modifier = Modifier.testTag("go_ai_auto_continue"))
                            }
                            if (!page.completedStorageReady) Text("完成棋谱保存功能准备好后才能开启自动续局。")
                            if (match.autoContinueEnabled) {
                                Text("连续局 ${match.autoContinueGameNumber} / ${match.autoContinueMaxGames}",
                                    modifier = Modifier.testTag("go_ai_auto_continue_progress"))
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    val choices = (listOf(2, 3, 5, 10) + match.autoContinueMaxGames).distinct().sorted()
                                    for (count in choices) FilterChip(match.autoContinueMaxGames == count, {
                                        onAutoContinuation(true, count)
                                    }, enabled = match.canConfigureAutoContinue && count >= match.autoContinueGameNumber,
                                        label = { Text("最多${count}局") })
                                }
                                if (game.phase == GoPhase.FINISHED && match.autoContinueGameNumber >= match.autoContinueMaxGames)
                                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Text("已达到本次连续开局上限。", modifier = Modifier.testTag("go_ai_auto_continue_limit"))
                                        if (game.error != null) OutlinedButton(onRetryAutoContinuation,
                                            enabled = game.canOperate, modifier = Modifier.testTag("go_ai_auto_continue_retry")) {
                                            Text("重试保存最后一局")
                                        }
                                    }
                                else if (game.phase == GoPhase.FINISHED && match.autoContinuePending)
                                    OutlinedButton(onRetryAutoContinuation, enabled = game.canOperate,
                                        modifier = Modifier.testTag("go_ai_auto_continue_retry")) { Text("重试自动续局") }
                            }
                        }
                        game.previewScore?.let { score ->
                            val margin = score.blackMarginQuarters()
                            val assessment = when {
                                margin > 0 -> "黑领先 ${goScoreText(margin, displayMode)}"
                                margin < 0 -> "白领先 ${goScoreText(abs(margin), displayMode)}"
                                else -> "和棋"
                            }
                            Text("${if (game.phase == GoPhase.FINISHED) "正式结果" else "数子预览"}：$assessment")
                            TextButton(onDisplayMode) { Text(if (displayMode == GoScoreDisplayMode.STONES) "切换点目" else "切换数子") }
                        }
                        OutlinedButton(onUndo, enabled = match.canUndo &&
                            (!match.usesImmediateHumanCompletion() || !match.completionStored),
                            modifier = Modifier.testTag("go_ai_undo")) { Text("悔棋") }
                        if (match.config.difficulty.undoLimit != Int.MAX_VALUE) Text("剩余悔棋：${(match.config.difficulty.undoLimit - match.undoUsed).coerceAtLeast(0)}次")
                        OutlinedButton({ onPause(); restartDialog = true },
                            enabled = page.canReplaceAiMatch(),
                            modifier = Modifier.testTag("go_ai_restart")) { Text("重新开始") }
                        OutlinedButton({ onPause(); onImportOpening() },
                            enabled = page.canReplaceAiMatch(),
                            modifier = Modifier.testTag("go_ai_import_opening")) { Text("指定开局") }
                        if (game.phase == GoPhase.FINISHED && !page.completedStorageReady)
                            Text("完成棋谱保存功能准备好后才能重开；本局已保留。")
                    }
                }
            }
            if (wide) Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                board(Modifier.weight(1f).fillMaxHeight())
                controls(Modifier.width(controlsWidth).fillMaxHeight())
            } else Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                board(Modifier.fillMaxWidth().height(boardHeight))
                controls(Modifier.weight(1f).fillMaxWidth())
            }
        }
    }
    if (restartDialog) AlertDialog(
        modifier = Modifier.testTag("go_ai_restart_dialog"),
        onDismissRequest = { restartDialog = false },
        title = { Text("重新开始本局？") },
        text = { Column(Modifier.heightIn(max = LocalConfiguration.current.screenHeightDp.dp * 0.38f)
            .verticalScroll(rememberScrollState())) { Text(when {
            page.match?.game?.phase == GoPhase.FINISHED && page.match.hasSpecifiedOpening ->
                "将先固定本局结果并保存完成棋谱，再回到同一指定开局。执色和难度保持，辅助次数重新计数。"
            page.match?.game?.phase == GoPhase.FINISHED ->
                "将先固定本局结果并保存完成棋谱，再开始新局。规格、让子、执色和难度保持，辅助次数重新计数。"
            page.match?.hasSpecifiedOpening == true ->
                "当前未完成对局将被替换并回到同一指定开局。执色和难度保持，悔棋与提示次数重新计数。"
            else -> "当前未完成对局将被替换。规格、让子、执色和难度保持，悔棋与提示次数重新计数。"
        }) } },
        confirmButton = { TextButton({ restartDialog = false; onRestart() },
            enabled = page.canReplaceAiMatch(),
            modifier = Modifier.testTag("go_ai_restart_confirm")) { Text("确认重开") } },
        dismissButton = { TextButton({ restartDialog = false }, modifier = Modifier.testTag("go_ai_restart_cancel")) { Text("保留本局") } },
    )
    pendingOpening?.let { preview -> AlertDialog(
        modifier = Modifier.testTag("go_ai_opening_dialog"),
        onDismissRequest = onDismissOpening,
        title = { Text("使用这个指定开局？") },
        text = { Column(Modifier.heightIn(max = LocalConfiguration.current.screenHeightDp.dp * 0.42f)
            .verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("${preview.boardSize}路 · ${if (preview.handicapStones == 0) "不让子" else "让${preview.handicapStones}子"} · " +
                "${preview.eventCount}个历史事件 · ${goReplayPlayer(preview.currentPlayer)}方行棋")
            Text("确认后才替换当前活动对局，并生成新的对局身份。重开将回到这个完整历史端点；本局按训练来源保存，不计标准人机胜场或难度解锁。")
            Text("SGF只保留经过规则验证的主线，不包含原应用之外的死子协商扩展。")
        } },
        confirmButton = { TextButton(onConfirmOpening,
            enabled = page.canReplaceAiMatch(),
            modifier = Modifier.testTag("go_ai_opening_confirm")) { Text("确认使用") } },
        dismissButton = { TextButton(onDismissOpening,
            modifier = Modifier.testTag("go_ai_opening_cancel")) { Text("保留当前对局") } },
    ) }
}

private fun GoAiMatchUiState.usesImmediateHumanCompletion() = immediateHumanCompletion && !config.automatic

/** Recheck dialogs too, without turning a legacy save-on-restart into immediate completion. */
private fun GoAiMatchPageState.canReplaceAiMatch(): Boolean = match?.let {
    !it.closed && it.game.canOperate && !it.completionPending &&
        (!it.usesImmediateHumanCompletion() || !it.completionReserved || it.completionStored) &&
        (it.game.phase != GoPhase.FINISHED || completedStorageReady)
} == true

internal fun goAiDifficultyLabel(difficulty: GoAiDifficulty) = when (difficulty) {
    GoAiDifficulty.SIMPLE -> "简单"
    GoAiDifficulty.MEDIUM -> "中等"
    GoAiDifficulty.HARD -> "困难"
    GoAiDifficulty.MASTER -> "大师"
}

internal fun goAiMatchPhaseLabel(match: GoAiMatchUiState): String {
    if (match.closed) return "对局已关闭"
    if (match.game.loading) return "正在恢复对局…"
    if (!match.game.sessionReady) return "对局尚未就绪"
    if (match.game.saving) return "正在保存对局…"
    return when (match.game.phase) {
    GoPhase.PLAYING -> when {
        match.thinking -> "${goReplayPlayer(match.game.currentPlayer)}方 AI 正在思考…"
        match.hintThinking -> "正在准备走子提示…"
        match.config.isAi(match.game.currentPlayer) -> if (match.paused) "AI 已暂停" else "等待 AI 着法"
        else -> "轮到你行棋"
    }
    GoPhase.SCORING -> "等待双方复核死子与数子"
    GoPhase.FINISHED -> when (match.game.result) {
        GameResult.FIRST_PLAYER_WIN -> "黑方获胜"
        GameResult.SECOND_PLAYER_WIN -> "白方获胜"
        GameResult.DRAW -> "和棋"
        GameResult.ONGOING -> "对局结束"
    }
}
}
