package com.masterofchessstrategy.games.go

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.masterofchessstrategy.engine.GameResult
import com.masterofchessstrategy.engine.GoPhase
import com.masterofchessstrategy.engine.GoPlayer
import kotlin.math.abs

@Composable
fun GoGameScreen(
    state: GoGameUiState,
    onIntersectionTap: (Int, Int) -> Unit,
    onPass: () -> Unit,
    onUndo: () -> Unit,
    onConfirmScore: (GoPlayer) -> Unit,
    onResumePlay: (GoPlayer) -> Unit,
    onScoringActorSelect: (GoPlayer) -> Unit,
    onScoreModeToggle: () -> Unit,
    onLegalPointsToggle: () -> Unit,
    onRetrySave: () -> Unit,
    onNewGame: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onImportSgf: (() -> Unit)? = null,
    onExportSgf: (() -> Unit)? = null,
    recordMessage: String? = null,
) {
    val title = when (state.result) {
        GameResult.FIRST_PLAYER_WIN -> "黑方获胜"
        GameResult.SECOND_PLAYER_WIN -> "白方获胜"
        GameResult.DRAW -> "和棋"
        GameResult.ONGOING -> when (state.phase) {
            GoPhase.PLAYING -> "${if (state.currentPlayer == GoPlayer.BLACK) "黑" else "白"}方行棋"
            GoPhase.SCORING -> "双方确认死子与数子"
            GoPhase.FINISHED -> "对局结束"
        }
    }
    Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        BoxWithConstraints(Modifier.fillMaxSize().padding(12.dp)) {
            val wide = maxWidth > maxHeight
            val board: @Composable (Modifier) -> Unit = { boardModifier ->
                GoBoard(
                    size = state.boardSize,
                    stones = state.stones,
                    onIntersectionTap = onIntersectionTap,
                    modifier = boardModifier,
                    lastMoveIndex = state.lastMoveIndex,
                    deadStoneIndices = state.deadStoneIndices,
                    highlightedPoints = if (state.phase == GoPhase.PLAYING && state.showLegalPoints)
                        state.legalPoints else emptySet(),
                    interactionEnabled = state.canAct &&
                        (state.phase == GoPhase.PLAYING || state.phase == GoPhase.SCORING),
                )
            }
            val controls: @Composable (Modifier) -> Unit = { controlModifier ->
                Column(
                    controlModifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("围棋 · ${state.boardSize} 路", style = MaterialTheme.typography.titleLarge)
                        TextButton(onClick = onBack) { Text("返回") }
                    }
                    TextButton(onClick = onNewGame,
                        enabled = !state.loading && !state.saving && !state.needsSave && !state.importing,
                    ) { Text("新对局") }
                    if (state.recordAvailable && onImportSgf != null && onExportSgf != null) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = onImportSgf, enabled = state.canOperate,
                                modifier = Modifier.testTag("go_import_sgf")) { Text("导入 SGF") }
                            OutlinedButton(onClick = onExportSgf, enabled = state.canOperate,
                                modifier = Modifier.testTag("go_export_sgf")) { Text("导出 SGF") }
                        }
                        Text("SGF 交换行棋主线；不包含死子协商和完整终局。")
                    }
                    if (recordMessage != null) Text(recordMessage, modifier = Modifier.testTag("go_record_message"))
                    Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("go_phase"))
                    if (state.handicapStones > 0) Text("让 ${state.handicapStones} 子 · 白方先行")
                    if (state.phase == GoPhase.PLAYING) {
                        TextButton(onClick = onLegalPointsToggle, enabled = state.canAct) {
                            Text(if (state.showLegalPoints) "隐藏合法点" else "显示合法点")
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = onPass, enabled = state.canAct) { Text("虚着") }
                            OutlinedButton(onClick = onUndo, enabled = state.canOperate) { Text("悔棋") }
                        }
                    } else {
                        val score = state.previewScore
                        if (score != null) {
                            Text("黑方数子评估：${goScoreText(score.blackAreaQuarters, GoScoreDisplayMode.STONES)}")
                            Text("白方数子评估：${goScoreText(score.whiteAreaQuarters, GoScoreDisplayMode.STONES)}")
                            val margin = score.blackMarginQuarters()
                            val assessment = when {
                                margin > 0 -> "黑领先 ${goScoreText(margin, state.displayMode)}"
                                margin < 0 -> "白领先 ${goScoreText(abs(margin), state.displayMode)}"
                                else -> "和棋"
                            }
                            Text("${if (state.phase == GoPhase.FINISHED) "正式结果" else "预览"}：$assessment")
                            if (score.blackKomiQuarters > 0) {
                                Text("黑贴：${goScoreText(score.blackKomiQuarters, state.displayMode)}")
                            }
                            if (score.handicapAdjustmentQuarters > 0) {
                                Text("让子还子：${goScoreText(score.handicapAdjustmentQuarters / 2, state.displayMode)}")
                            }
                            TextButton(onClick = onScoreModeToggle) {
                                Text(if (state.displayMode == GoScoreDisplayMode.STONES) "切换点目" else "切换数子")
                            }
                        }
                        if (state.phase == GoPhase.SCORING) {
                            Text("由${if (state.scoringActor == GoPlayer.BLACK) "黑" else "白"}方标记整组死子；有争议时由主张死子方先行续弈。")
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = { onScoringActorSelect(GoPlayer.BLACK) }, enabled = state.canAct) { Text("黑方标记") }
                                OutlinedButton(onClick = { onScoringActorSelect(GoPlayer.WHITE) }, enabled = state.canAct) { Text("白方标记") }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(
                                    onClick = { onConfirmScore(GoPlayer.BLACK) },
                                    enabled = state.canAct && !state.blackConfirmed,
                                ) { Text(if (state.blackConfirmed) "黑方已确认" else "黑方确认") }
                                Button(
                                    onClick = { onConfirmScore(GoPlayer.WHITE) },
                                    enabled = state.canAct && !state.whiteConfirmed,
                                ) { Text(if (state.whiteConfirmed) "白方已确认" else "白方确认") }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = { onResumePlay(GoPlayer.BLACK) }, enabled = state.canAct) {
                                    Text("黑方主张死子，续弈")
                                }
                                OutlinedButton(onClick = { onResumePlay(GoPlayer.WHITE) }, enabled = state.canAct) {
                                    Text("白方主张死子，续弈")
                                }
                            }
                        }
                        OutlinedButton(onClick = onUndo, enabled = state.canOperate) { Text("悔棋") }
                    }
                    if (state.saving) Text("正在保存对局…")
                    if (state.importing) Text("正在校验并保存导入棋谱…")
                    if (state.error != null) Text(state.error, color = MaterialTheme.colorScheme.error)
                    if (state.needsSave) {
                        Button(onClick = onRetrySave) { Text("重试保存") }
                    }
                }
            }
            if (wide) {
                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    board(Modifier.weight(1f).fillMaxSize())
                    controls(Modifier.width(300.dp))
                }
            } else {
                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    board(Modifier.fillMaxWidth().weight(1f))
                    controls(Modifier.fillMaxWidth().height(240.dp))
                }
            }
        }
    }
}
