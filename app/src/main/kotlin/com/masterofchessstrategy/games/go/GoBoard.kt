package com.masterofchessstrategy.games.go

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.ViewConfiguration
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.DpSize
import kotlin.math.min

internal const val GO_BOARD_TAG = "go_board"

internal data class GoBoardPoint(val x: Int, val y: Int)

/** Maps a tap in a square board to the intersection at the center of its cell. */
internal fun goBoardPointAt(
    xPx: Float,
    yPx: Float,
    sidePx: Float,
    boardSize: Int,
): GoBoardPoint? {
    if (boardSize !in setOf(9, 13, 19) || !xPx.isFinite() || !yPx.isFinite() ||
        !sidePx.isFinite() || sidePx <= 0f ||
        xPx < 0f || yPx < 0f || xPx >= sidePx || yPx >= sidePx
    ) return null
    val cell = sidePx / boardSize
    return GoBoardPoint(
        x = (xPx / cell).toInt().coerceAtMost(boardSize - 1),
        y = (yPx / cell).toInt().coerceAtMost(boardSize - 1),
    )
}

/** All nine project star positions, including the side points used by fixed handicap. */
internal fun goBoardStarPoints(boardSize: Int): Set<Int> {
    require(boardSize in setOf(9, 13, 19))
    val edge = if (boardSize == 9) 2 else 3
    val middle = boardSize / 2
    val far = boardSize - 1 - edge
    return setOf(edge, middle, far).flatMap { y ->
        setOf(edge, middle, far).map { x -> y * boardSize + x }
    }.toSet()
}

internal fun goBoardPointDescription(
    x: Int,
    y: Int,
    stone: Int,
    isLastMove: Boolean,
    isDead: Boolean,
    isHighlighted: Boolean,
): String = buildString {
    append("第${y + 1}行第${x + 1}列，")
    append(when (stone) {
        1 -> "黑子"
        2 -> "白子"
        else -> "空点"
    })
    if (isLastMove) append("，最后落点")
    if (isDead) append("，待确认死子")
    if (isHighlighted) append("，高亮点")
}

/** Visual board only. The caller owns legality, phase, scoring and persistence. */
@Composable
fun GoBoard(
    size: Int,
    stones: List<Int>,
    onIntersectionTap: (x: Int, y: Int) -> Unit,
    modifier: Modifier = Modifier,
    lastMoveIndex: Int? = null,
    deadStoneIndices: Set<Int> = emptySet(),
    highlightedPoints: Set<Int> = emptySet(),
    interactionEnabled: Boolean = true,
) {
    require(size in setOf(9, 13, 19)) { "Go board must be 9, 13 or 19 lines" }
    require(stones.size == size * size && stones.all { it in 0..2 }) {
        "Go stones must contain exactly size² values in 0..2"
    }
    val boardSize = size
    val currentTap = rememberUpdatedState(onIntersectionTap)
    BoxWithConstraints(modifier = modifier.fillMaxSize().testTag(GO_BOARD_TAG)) {
        val side = minOf(maxWidth, maxHeight)
        val cell = side / boardSize
        Box(
            modifier = Modifier.size(side).align(Alignment.Center)
                .clip(RoundedCornerShape(8.dp))
                .semantics {
                    contentDescription = "${boardSize}路围棋棋盘"
                    stateDescription = if (interactionEnabled) "可操作" else "暂不可操作"
                },
        ) {
            Canvas(
                modifier = Modifier.matchParentSize().testTag("go_board_canvas").pointerInput(boardSize, interactionEnabled) {
                    detectTapGestures { tap ->
                        if (interactionEnabled) {
                            goBoardPointAt(tap.x, tap.y, this.size.width.toFloat(), boardSize)
                                ?.let { currentTap.value(it.x, it.y) }
                        }
                    }
                },
            ) {
                val sidePx = min(this.size.width, this.size.height)
                val cellPx = sidePx / boardSize
                val first = cellPx / 2f
                val last = sidePx - first
                val lineWidth = min(1.25.dp.toPx(), cellPx * 0.07f)
                val wood = Color(0xFFD9B878)
                val ink = Color(0xFF5A4028)
                val black = Color(0xFF1D2023)
                val white = Color(0xFFF6F4EA)

                drawRect(wood)
                for (index in 0 until boardSize) {
                    val p = first + index * cellPx
                    drawLine(ink, Offset(first, p), Offset(last, p), lineWidth)
                    drawLine(ink, Offset(p, first), Offset(p, last), lineWidth)
                }
                for (index in goBoardStarPoints(boardSize)) {
                    val center = Offset(first + (index % boardSize) * cellPx,
                        first + (index / boardSize) * cellPx)
                    drawCircle(ink, radius = min(3.dp.toPx(), cellPx * 0.11f), center = center)
                }
                for (index in stones.indices) {
                    val center = Offset(first + (index % boardSize) * cellPx,
                        first + (index / boardSize) * cellPx)
                    val stone = stones[index]
                    if (stone != 0) {
                        val radius = cellPx * 0.43f
                        drawCircle(Color(0x55000000), radius,
                            center + Offset(1.dp.toPx(), 1.dp.toPx()))
                        drawCircle(if (stone == 1) black else white, radius, center)
                        if (stone == 2) drawCircle(ink, radius, center,
                            style = Stroke(width = min(1.dp.toPx(), cellPx * 0.04f)))
                    }
                    if (index in highlightedPoints) {
                        if (stone == 0) {
                            drawCircle(Color(0xB52B795A), cellPx * 0.18f, center)
                        } else {
                            drawCircle(Color(0xFF2B795A), cellPx * 0.48f, center,
                                style = Stroke(width = min(2.dp.toPx(), cellPx * 0.09f)))
                        }
                    }
                    if (index == lastMoveIndex && stone != 0) {
                        drawCircle(if (stone == 1) white else black, cellPx * 0.095f, center)
                    }
                    if (index in deadStoneIndices && stone != 0) {
                        val half = cellPx * 0.21f
                        val stroke = min(2.6.dp.toPx(), cellPx * 0.12f)
                        val red = Color(0xFFD42E22)
                        drawLine(red, center + Offset(-half, -half),
                            center + Offset(half, half), stroke)
                        drawLine(red, center + Offset(-half, half),
                            center + Offset(half, -half), stroke)
                    }
                }
            }
            // Expanding dense cells to 48dp overlaps neighbours and shifts Android focus bounds.
            // Keep this override local to the board's semantic cells; controls use normal targets.
            val viewConfiguration = LocalViewConfiguration.current
            val cellConfiguration = remember(viewConfiguration, cell) {
                object : ViewConfiguration by viewConfiguration {
                    override val minimumTouchTargetSize = DpSize(cell, cell)
                }
            }
            CompositionLocalProvider(LocalViewConfiguration provides cellConfiguration) {
            // Semantics-only cells keep every intersection reachable with a screen reader.
            for (y in 0 until boardSize) {
                for (x in 0 until boardSize) {
                    val index = y * boardSize + x
                    Box(
                        modifier = Modifier.offset(x = cell * x, y = cell * y).size(cell)
                            .testTag("go_intersection_${x}_${y}")
                            .semantics {
                                contentDescription = goBoardPointDescription(
                                    x, y, stones[index], index == lastMoveIndex,
                                    index in deadStoneIndices && stones[index] != 0,
                                    index in highlightedPoints,
                                )
                                if (interactionEnabled) {
                                    onClick(label = "选择第${y + 1}行第${x + 1}列") {
                                        onIntersectionTap(x, y)
                                        true
                                    }
                                }
                            },
                    )
                }
            }
            }
        }
    }
}

@Preview(showBackground = true, widthDp = 720, heightDp = 400)
@Composable
private fun GoBoardPreview() {
    val stones = List(13 * 13) { index ->
        when (index) {
            42, 83, 84 -> 1
            43, 82, 85 -> 2
            else -> 0
        }
    }
    GoBoard(
        size = 13,
        stones = stones,
        onIntersectionTap = { _, _ -> },
        lastMoveIndex = 85,
        deadStoneIndices = setOf(42),
        highlightedPoints = setOf(86),
    )
}
