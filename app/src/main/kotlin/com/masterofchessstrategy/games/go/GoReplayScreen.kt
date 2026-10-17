package com.masterofchessstrategy.games.go

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.masterofchessstrategy.engine.*
import kotlin.math.roundToInt

@Composable
fun GoReplayScreen(
    state: GoReplayUiState,
    onSeek: (Int) -> Unit,
    onPlay: () -> Unit,
    onPause: () -> Unit,
    onSpeed: (Float) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    title: String = "围棋棋谱回放",
    extraControls: @Composable (GoReplayFrame) -> Unit = {},
) {
    Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        BoxWithConstraints(Modifier.fillMaxSize().padding(12.dp)) {
            val wide = maxWidth > maxHeight
            val controlsWidth = (maxWidth * 0.43f).coerceAtMost(390.dp)
            val boardHeight = maxHeight * 0.53f
            val frame = state.frame
            val board: @Composable (Modifier) -> Unit = { target ->
                if (frame != null) GoBoard(frame.boardSize, frame.stones, { _, _ -> }, target,
                    lastMoveIndex = frame.lastMoveIndex, deadStoneIndices = frame.deadStoneIndices,
                    interactionEnabled = false)
            }
            val controls: @Composable (Modifier) -> Unit = { target ->
                Column(target.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(title, style = MaterialTheme.typography.titleLarge)
                        TextButton(onClick = onBack) { Text("返回") }
                    }
                    if (state.loading) Text("正在读取棋谱…")
                    state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("go_replay_error")) }
                    if (frame != null) {
                        Text("${frame.boardSize} 路${if (frame.handicapStones > 0) " · 让 ${frame.handicapStones} 子" else ""}")
                        Text("行棋 ${frame.moveIndex} / ${frame.moveCount} 着 · 事件 ${frame.eventIndex} / ${frame.eventCount}",
                            modifier = Modifier.testTag("go_replay_cursor"))
                        Text(goReplayEventLabel(frame.lastEvent), modifier = Modifier.testTag("go_replay_event"))
                        Text(when (frame.phase) {
                            GoPhase.PLAYING -> "${goReplayPlayer(frame.currentPlayer)}方行棋"
                            GoPhase.SCORING -> "死子协商：黑方${if (frame.blackConfirmed) "已确认" else "未确认"}，白方${if (frame.whiteConfirmed) "已确认" else "未确认"}"
                            GoPhase.FINISHED -> when (frame.result) {
                                GameResult.FIRST_PLAYER_WIN -> "正式终局：黑方获胜"
                                GameResult.SECOND_PLAYER_WIN -> "正式终局：白方获胜"
                                GameResult.DRAW -> "正式终局：和棋"
                                GameResult.ONGOING -> "对局结束"
                            }
                        })
                        var cursor by remember(frame.eventIndex, frame.eventCount) { mutableFloatStateOf(frame.eventIndex.toFloat()) }
                        if (frame.eventCount > 0) Slider(cursor, { cursor = it },
                            onValueChangeFinished = { onSeek(cursor.roundToInt()) },
                            valueRange = 0f..frame.eventCount.toFloat(), enabled = state.ready && !state.seeking,
                            modifier = Modifier.testTag("go_replay_seek"))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton({ onSeek(0) }, enabled = state.ready && frame.canPrevious) { Text("首步") }
                            OutlinedButton({ onSeek((frame.eventIndex - 1).coerceAtLeast(0)) }, enabled = state.ready && frame.canPrevious) { Text("上一步") }
                            OutlinedButton({ onSeek((frame.eventIndex + 1).coerceAtMost(frame.eventCount)) }, enabled = state.ready && frame.canNext) { Text("下一步") }
                            OutlinedButton({ onSeek(frame.eventCount) }, enabled = state.ready && frame.canNext) { Text("末步") }
                        }
                        Button(if (state.playing) onPause else onPlay,
                            enabled = state.ready && (state.playing || (!state.seeking && frame.canNext)),
                            modifier = Modifier.testTag("go_replay_play")) { Text(if (state.playing) "暂停" else "播放") }
                        Text("播放速度 ${state.speed}x")
                        Slider(state.speed, onSpeed, valueRange = 0.5f..4f, enabled = !state.closed,
                            modifier = Modifier.testTag("go_replay_speed"))
                        if (state.seeking) Text("正在加载步骤…")
                        Text("只读查看；不改变当前对局、教程进度或战绩。")
                        extraControls(frame)
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
}

internal fun goReplayPlayer(player: GoPlayer) = if (player == GoPlayer.BLACK) "黑" else "白"

internal fun goReplayEventLabel(action: GoAction?): String = when (action) {
    null -> "初始布点"
    is GoAction.Place -> "落子：第${action.point.y + 1}行第${action.point.x + 1}列"
    GoAction.Pass -> "虚着"
    is GoAction.ToggleDeadGroup -> "${goReplayPlayer(action.actor)}方调整死子提议"
    is GoAction.ConfirmScore -> "${goReplayPlayer(action.actor)}方确认死子提议"
    is GoAction.ResumePlay -> "争议续弈，${goReplayPlayer(action.firstPlayer)}方先行"
}
