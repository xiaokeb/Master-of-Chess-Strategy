package com.masterofchessstrategy.games.lanzhou_fangqi

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.masterofchessstrategy.R
import com.masterofchessstrategy.engine.Difficulty
import com.masterofchessstrategy.engine.GameResult
import com.masterofchessstrategy.engine.games.lanzhou_fangqi.FangqiPoint
import com.masterofchessstrategy.engine.games.lanzhou_fangqi.FangqiSession
import com.masterofchessstrategy.engine.games.lanzhou_fangqi.FangqiSide
import com.masterofchessstrategy.engine.games.lanzhou_fangqi.FangqiSnapshot

/** A game-specific surface; the shared navigation route supplies and owns the session. */
@Composable
internal fun LanzhouFangqiScreen(
    session: FangqiSession,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    aiDifficulty: Difficulty? = null,
    onPositionChanged: ((FangqiSnapshot, ByteArray, (Boolean) -> Unit) -> Unit)? = null,
    interactionOverride: LanzhouFangqiInteraction? = null,
    aiManagedExternally: Boolean = false,
    undoAllowed: Boolean = true,
    restartAllowed: Boolean = true,
) {
    val checkpointWriter by rememberUpdatedState(onPositionChanged)
    val interaction = interactionOverride ?: remember(session, aiDifficulty != null, onPositionChanged != null) {
        LanzhouFangqiInteraction(
            session, humanSide = if (aiDifficulty != null) FangqiSide.RED else null,
            onPositionChanged = if (onPositionChanged == null) null else { snapshot, bytes, done ->
                checkpointWriter!!(snapshot, bytes, done)
            },
        )
    }
    val ui by interaction.state.collectAsState()
    val snapshot = ui.snapshot

    LaunchedEffect(
        interaction, aiDifficulty, snapshot.currentPlayer,
        snapshot.result,
        ui.savePending, ui.saveFailed,
    ) {
        if (!aiManagedExternally && aiDifficulty != null && snapshot.currentPlayer == FangqiSide.BLACK
            && snapshot.result == GameResult.ONGOING
        ) interaction.playBlackAi(aiDifficulty)
    }

    Row(modifier.fillMaxSize().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        FangqiBoard(
            ui = ui,
            onPoint = interaction::tap,
            modifier = Modifier.weight(1f).fillMaxHeight(),
        )
        Column(
            Modifier.width(240.dp)
                .fillMaxHeight().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(stringResource(R.string.lanzhou_fangqi_title), style = MaterialTheme.typography.headlineSmall)
            val turn = if (snapshot.currentPlayer == FangqiSide.RED) {
                stringResource(R.string.lanzhou_fangqi_red)
            } else stringResource(R.string.lanzhou_fangqi_black)
            val status = when (snapshot.result) {
                GameResult.FIRST_PLAYER_WIN -> stringResource(R.string.lanzhou_fangqi_red_wins)
                GameResult.SECOND_PLAYER_WIN -> stringResource(R.string.lanzhou_fangqi_black_wins)
                GameResult.DRAW -> stringResource(R.string.lanzhou_fangqi_draw)
                GameResult.ONGOING -> if (snapshot.pendingCaptures > 0) {
                    stringResource(R.string.lanzhou_fangqi_captures_due, turn, snapshot.pendingCaptures)
                } else stringResource(R.string.lanzhou_fangqi_turn, turn)
            }
            Text(status, modifier = Modifier.testTag("lanzhou_fangqi_status"))
            val feedback = when (ui.feedback) {
                FangqiFeedback.HISTORY_LIMIT -> null
                FangqiFeedback.NONE -> null
                FangqiFeedback.SELECT_OWN -> R.string.lanzhou_fangqi_select_own
                FangqiFeedback.SELECT_CAPTURE -> R.string.lanzhou_fangqi_select_capture
                FangqiFeedback.GAME_OVER -> R.string.lanzhou_fangqi_game_over
                FangqiFeedback.AI_THINKING -> R.string.lanzhou_fangqi_ai_thinking
                FangqiFeedback.ILLEGAL -> R.string.lanzhou_fangqi_illegal
                FangqiFeedback.REPEATED -> R.string.lanzhou_fangqi_repeated
            }
            if (feedback != null) Text(stringResource(feedback), color = MaterialTheme.colorScheme.error)
            if (ui.feedback == FangqiFeedback.HISTORY_LIMIT) {
                Text("对局记录已达上限，可返回备份当前局或重开", color = MaterialTheme.colorScheme.error)
            }
            if (ui.savePending) Text("正在保存对局")
            if (ui.saveFailed) {
                Text("保存失败，请重试后继续", color = MaterialTheme.colorScheme.error)
                Button(onClick = interaction::retrySave, modifier = Modifier.testTag("lanzhou_fangqi_retry_save")) { Text("重试保存") }
            }
            OutlinedButton(onClick = interaction::undo, enabled = undoAllowed && !ui.aiThinking && !ui.savePending && !ui.saveFailed && snapshot.canUndo,
                modifier = Modifier.testTag("lanzhou_fangqi_undo")) {
                Text(stringResource(R.string.lanzhou_fangqi_undo))
            }
            OutlinedButton(onClick = interaction::reset, enabled = restartAllowed && !ui.aiThinking && !ui.savePending && !ui.saveFailed,
                modifier = Modifier.testTag("lanzhou_fangqi_restart")) {
                Text(stringResource(R.string.lanzhou_fangqi_restart))
            }
            if (!restartAllowed) Text("开始下一局请返回人机入口")
            Button(onClick = onBack, modifier = Modifier.testTag("lanzhou_fangqi_back")) { Text(stringResource(R.string.lanzhou_fangqi_back)) }
        }
    }
}

@Composable
private fun FangqiBoard(
    ui: FangqiUiState,
    onPoint: (FangqiPoint) -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier.background(Color(0xFFE9C990)).testTag("lanzhou_fangqi_board")) {
        Canvas(Modifier.fillMaxSize()) {
            val geometry = fangqiBoardGeometry(size.width, size.height)
            val grid = Color(0xFF67452E)
            repeat(7) { line ->
                val x = geometry.originX + line * geometry.spacing
                val y = geometry.originY + line * geometry.spacing
                drawLine(grid, Offset(x, geometry.originY), Offset(x, geometry.originY + 6 * geometry.spacing), 2f)
                drawLine(grid, Offset(geometry.originX, y), Offset(geometry.originX + 6 * geometry.spacing, y), 2f)
            }
            for (y in 0..6) for (x in 0..6) {
                val point = FangqiPoint(x, y)
                val center = Offset(geometry.centerX(point), geometry.centerY(point))
                val piece = ui.snapshot.board[point.index]
                if (point in ui.legalPoints) {
                    drawCircle(Color(0xFF277845), geometry.spacing * 0.13f, center)
                }
                if (piece == 0) continue
                val color = if (piece == FangqiSide.RED.pieceCode) Color(0xFFB33431) else Color(0xFF24262C)
                val radius = geometry.spacing * 0.36f
                drawCircle(Color(0xFFF8E5BD), radius + 3f, center)
                drawCircle(color, radius, center)
                if (point in ui.legalPoints) {
                    drawCircle(Color(0xFF277845), radius + 5f, center, style = Stroke(width = 4f))
                }
                if (point == ui.selected) {
                    drawCircle(Color(0xFFFFDB63), radius + 5f, center, style = Stroke(width = 4f))
                }
            }
        }
        val spacing = minOf(maxWidth, maxHeight) / 7f
        val originX = (maxWidth - spacing * 6f) / 2f
        val originY = (maxHeight - spacing * 6f) / 2f
        for (y in 0..6) for (x in 0..6) {
            val point = FangqiPoint(x, y)
            val pieceName = stringResource(when (ui.snapshot.board[point.index]) {
                1 -> R.string.lanzhou_fangqi_red_piece
                2 -> R.string.lanzhou_fangqi_black_piece
                else -> R.string.lanzhou_fangqi_empty_point
            })
            val label = stringResource(R.string.lanzhou_fangqi_point_label, x + 1, y + 1, pieceName)
            val actionLabel = stringResource(if (ui.snapshot.pendingCaptures > 0) {
                R.string.lanzhou_fangqi_capture_action
            } else R.string.lanzhou_fangqi_select_action)
            val pointState = if (point in ui.legalPoints) {
                stringResource(R.string.lanzhou_fangqi_legal_target)
            } else ""
            Box(
                Modifier.offset(originX + spacing * x - spacing / 2f, originY + spacing * y - spacing / 2f)
                    .size(spacing)
                    .testTag("lanzhou_fangqi_point_${x}_$y")
                    .semantics {
                        contentDescription = label
                        selected = point == ui.selected
                        stateDescription = pointState
                    }
                    .clickable(enabled = !ui.aiThinking, role = Role.Button, onClickLabel = actionLabel) {
                        onPoint(point)
                    },
            )
        }
    }
}
