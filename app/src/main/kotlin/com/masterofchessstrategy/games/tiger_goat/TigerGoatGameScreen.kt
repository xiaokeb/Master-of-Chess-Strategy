package com.masterofchessstrategy.games.tiger_goat

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

enum class TigerGoatPhase { OPENING_REMOVAL, PLAY, CAPTURE_CHAIN, FINISHED }

enum class TigerGoatWinner { NONE, TIGER, GOAT }

data class TigerGoatDisplayState(
    val pieces: List<Int>,
    val phase: TigerGoatPhase,
    val toMove: Int,
    val reserve: Int,
    val captured: Int,
    val winner: TigerGoatWinner,
    val selectedPoint: Int? = null,
    val legalDestinations: Set<Int> = emptySet(),
    val chainVisited: Set<Int> = emptySet(),
    val canUndo: Boolean = false,
    val canPassGoat: Boolean = false,
    val busy: Boolean = false,
    val feedback: String? = null,
) {
    fun message(): String = when {
        winner == TigerGoatWinner.TIGER -> "老虎获胜"
        winner == TigerGoatWinner.GOAT -> "羊获胜"
        phase == TigerGoatPhase.OPENING_REMOVAL -> "老虎先从中央环移去一只羊"
        phase == TigerGoatPhase.CAPTURE_CHAIN -> "老虎可继续跳吃，也可结束本回合"
        toMove == 2 && reserve > 0 -> "羊放置一只，待摆 $reserve 只"
        canPassGoat -> "羊已全部摆入且无合法走步，请停着交由老虎行动"
        toMove == 2 -> "羊沿线走一步"
        else -> "老虎沿直线走步或跳吃"
    }
}

@Composable
fun TigerGoatGameScreen(
    spec: TigerGoatBoardSpec,
    state: TigerGoatDisplayState,
    onPointTap: (Int) -> Unit,
    onEndChain: () -> Unit,
    onUndo: () -> Unit,
    onRestart: () -> Unit,
    onResign: () -> Unit,
    onBack: () -> Unit,
    onPassGoat: () -> Unit,
    modifier: Modifier = Modifier,
    onTutorial: (() -> Unit)? = null,
    title: String = "老虎吃羊棋",
) {
    require(state.pieces.size == spec.points.size)
    var confirmResign by remember { mutableStateOf(false) }
    var confirmRestart by remember { mutableStateOf(false) }

    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.padding(12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(title, style = MaterialTheme.typography.titleLarge)
                TextButton(onClick = onBack) { Text("返回") }
            }
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TigerGoatBoard(
                    spec = spec,
                    state = state,
                    onPointTap = onPointTap,
                    modifier = Modifier.weight(3f).fillMaxHeight(),
                )
                Column(
                    modifier = Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(state.message(), style = MaterialTheme.typography.titleMedium)
                    Text("已吃 ${state.captured} 只羊 · 待摆 ${state.reserve} 只")
                    if (onTutorial != null) {
                        TextButton(onClick = onTutorial, enabled = !state.busy,
                            modifier = Modifier.testTag("tiger_goat_open_tutorial")) { Text("规则教程") }
                    }
                    state.feedback?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    if (state.phase == TigerGoatPhase.CAPTURE_CHAIN) {
                        Button(onClick = onEndChain, enabled = !state.busy) { Text("结束连跳") }
                    }
                    if (state.canPassGoat) {
                        Button(
                            onClick = onPassGoat,
                            enabled = !state.busy,
                            modifier = Modifier.testTag("tiger_goat_pass"),
                        ) { Text("羊方停着") }
                    }
                    TextButton(onClick = onUndo, enabled = state.canUndo && !state.busy) {
                        Text("悔棋")
                    }
                    TextButton(onClick = { confirmRestart = true }, enabled = !state.busy) {
                        Text("重开")
                    }
                    TextButton(
                        onClick = { confirmResign = true },
                        enabled = state.winner == TigerGoatWinner.NONE && !state.busy,
                    ) { Text("认输") }
                }
            }
        }
    }
    if (confirmResign) {
        AlertDialog(
            onDismissRequest = { confirmResign = false },
            title = { Text("确认认输？") },
            confirmButton = {
                TextButton(onClick = { confirmResign = false; onResign() }) { Text("认输") }
            },
            dismissButton = {
                TextButton(onClick = { confirmResign = false }) { Text("取消") }
            },
        )
    }
    if (confirmRestart) {
        AlertDialog(
            onDismissRequest = { confirmRestart = false },
            title = { Text("确认重开？") },
            confirmButton = {
                TextButton(onClick = { confirmRestart = false; onRestart() }) { Text("重开") }
            },
            dismissButton = {
                TextButton(onClick = { confirmRestart = false }) { Text("取消") }
            },
        )
    }
}

@Composable
internal fun TigerGoatBoard(
    spec: TigerGoatBoardSpec,
    state: TigerGoatDisplayState,
    onPointTap: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val currentTap by rememberUpdatedState(onPointTap)
    val lineColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f)
    val selectedColor = MaterialTheme.colorScheme.primary
    val goatColor = Color(0xfff0e2c4)
    val tigerColor = Color(0xffac552f)
    val borderColor = Color(0xff493322)
    val acceptsMoves = !state.busy && state.winner == TigerGoatWinner.NONE
    Layout(modifier = modifier.testTag("tiger_goat_board").semantics { contentDescription = "老虎吃羊棋棋盘" },
    content = {
        Canvas(Modifier.fillMaxSize().pointerInput(spec, acceptsMoves) {
                detectTapGestures { tap ->
                    if (acceptsMoves) {
                        tigerGoatViewport(size.width.toFloat(), size.height.toFloat(), spec)
                            .hitTest(TigerGoatPixelPoint(tap.x, tap.y))
                            ?.let(currentTap)
                    }
                }
            }) {
            val viewport = tigerGoatViewport(size.width, size.height, spec)
            for (edge in spec.edges) {
                val from = viewport.centers[edge.from]
                val to = viewport.centers[edge.to]
                drawLine(lineColor, Offset(from.x, from.y), Offset(to.x, to.y),
                         strokeWidth = viewport.unit * 0.055f)
            }
            for (point in spec.points) {
                val center = viewport.centers[point.id]
                val at = Offset(center.x, center.y)
                val piece = state.pieces[point.id]
                drawCircle(borderColor, viewport.unit * 0.08f, at)
                if (point.id in state.legalDestinations) {
                    drawCircle(selectedColor, viewport.unit * 0.17f, at, style = Stroke(viewport.unit * 0.045f))
                }
                if (piece == 1 || piece == 2) {
                    drawCircle(borderColor, viewport.unit * 0.33f, at)
                    drawCircle(if (piece == 1) tigerColor else goatColor, viewport.unit * 0.29f, at)
                }
                if (point.id == state.selectedPoint || point.id in state.chainVisited) {
                    drawCircle(selectedColor, viewport.unit * 0.39f, at, style = Stroke(viewport.unit * 0.055f))
                }
            }
        }
        // Each point has its own focus target at the drawn position.
        spec.points.forEach { point ->
            val piece = when (state.pieces[point.id]) {
                1 -> "老虎"
                2 -> "羊"
                else -> "空点"
            }
            Spacer(Modifier.semantics {
                contentDescription = "$piece，落点 ${point.id}"
                if (acceptsMoves) onClick { currentTap(point.id); true } else disabled()
            })
        }
    }) { children, constraints ->
        val board = children.first().measure(constraints)
        if (board.width == 0 || board.height == 0) {
            layout(board.width, board.height) { board.place(0, 0) }
        } else {
            val viewport = tigerGoatViewport(board.width.toFloat(), board.height.toFloat(), spec)
            val diameter = (viewport.unit * 0.8f).roundToInt()
            val points = children.drop(1).map { it.measure(Constraints.fixed(diameter, diameter)) }
            layout(board.width, board.height) {
                board.place(0, 0)
                points.forEachIndexed { index, target ->
                    val center = viewport.centers[index]
                    target.place((center.x - diameter / 2f).roundToInt(), (center.y - diameter / 2f).roundToInt())
                }
            }
        }
    }
}
