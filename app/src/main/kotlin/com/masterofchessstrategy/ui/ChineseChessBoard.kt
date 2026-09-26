package com.masterofchessstrategy.ui

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.masterofchessstrategy.engine.BoardPosition
import com.masterofchessstrategy.engine.ChineseChessBoard
import com.masterofchessstrategy.engine.ChineseChessPiece
import com.masterofchessstrategy.engine.ChineseChessPieceType
import com.masterofchessstrategy.engine.ChineseChessSide
import com.masterofchessstrategy.game.ChineseChessGameUiState
import com.masterofchessstrategy.game.ChineseChessFeedback
import com.masterofchessstrategy.R
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt

internal const val CHINESE_CHESS_BOARD_TAG = "chinese_chess_board"
internal const val BOARD_RESET_VIEW_TAG = "board_reset_view"
internal val BoardViewportKey = SemanticsPropertyKey<ChineseChessBoardViewport>("BoardViewport")
internal val BoardMovePulseKey = SemanticsPropertyKey<Boolean>("BoardMovePulse")
internal val BoardMovePulseProgressKey = SemanticsPropertyKey<Float>("BoardMovePulseProgress")

internal data class ChineseChessBoardGeometry(
    val origin: Offset,
    val cellSize: Float,
) {
    fun center(position: BoardPosition): Offset =
        Offset(
            x = origin.x + position.x * cellSize,
            y = origin.y + position.y * cellSize,
        )
}

internal fun calculateBoardGeometry(
    width: Float,
    height: Float,
): ChineseChessBoardGeometry {
    require(width > 0f && height > 0f) { "Board size must be positive" }
    val cellSize = min(
        width / (ChineseChessBoard.WIDTH + 0.2f),
        height / (ChineseChessBoard.HEIGHT + 0.2f),
    )
    val boardWidth = (ChineseChessBoard.WIDTH - 1) * cellSize
    val boardHeight = (ChineseChessBoard.HEIGHT - 1) * cellSize
    return ChineseChessBoardGeometry(
        origin = Offset(
            x = (width - boardWidth) / 2f,
            y = (height - boardHeight) / 2f,
        ),
        cellSize = cellSize,
    )
}

internal fun boardPositionAt(
    tap: Offset,
    width: Float,
    height: Float,
): BoardPosition? {
    if (width <= 0f || height <= 0f) return null
    val geometry = calculateBoardGeometry(width, height)
    val x = ((tap.x - geometry.origin.x) / geometry.cellSize).roundToInt()
    val y = ((tap.y - geometry.origin.y) / geometry.cellSize).roundToInt()
    if (x !in 0 until ChineseChessBoard.WIDTH || y !in 0 until ChineseChessBoard.HEIGHT) {
        return null
    }
    val position = BoardPosition(x, y)
    val center = geometry.center(position)
    return position.takeIf {
        hypot(tap.x - center.x, tap.y - center.y) <= geometry.cellSize * 0.48f
    }
}

@Composable
internal fun ChineseChessBoard(
    state: ChineseChessGameUiState,
    onSquareTap: (BoardPosition) -> Unit,
    modifier: Modifier = Modifier,
) {
    var viewport by remember { mutableStateOf(ChineseChessBoardViewport()) }
    var boardSize by remember { mutableStateOf(Size.Zero) }
    val currentState by rememberUpdatedState(state)
    val currentTap by rememberUpdatedState(onSquareTap)
    val zoomIn = stringResource(R.string.board_zoom_in)
    val zoomOut = stringResource(R.string.board_zoom_out)
    val resetView = stringResource(R.string.board_reset_view)
    val description = stringResource(R.string.board_zoom_description, (viewport.scale * 100).roundToInt())
    fun accessibleZoom(factor: Float): Boolean {
        viewport = viewport.transform(
            boardSize, Offset(boardSize.width / 2, boardSize.height / 2), Offset.Zero, factor,
        )
        return true
    }
    Box(
        modifier = modifier.fillMaxSize().clipToBounds()
            .testTag(CHINESE_CHESS_BOARD_TAG)
            .onSizeChanged {
                val resized = Size(it.width.toFloat(), it.height.toFloat())
                if (resized != boardSize) {
                    boardSize = resized
                    viewport = ChineseChessBoardViewport()
                }
            }
            .semantics {
                contentDescription = "中国象棋棋盘，" + state.currentSide.displayName() +
                    "方行棋" + (state.checkedSide?.let { "，${it.displayName()}方被将军" } ?: "")
                stateDescription = description
                this[BoardViewportKey] = viewport
                customActions = listOf(
                    CustomAccessibilityAction(zoomIn) { accessibleZoom(1.25f) },
                    CustomAccessibilityAction(zoomOut) { accessibleZoom(0.8f) },
                    CustomAccessibilityAction(resetView) {
                        viewport = ChineseChessBoardViewport()
                        true
                    },
                )
            }
            .pointerInput(Unit) {
                detectBoardGestures(
                    canTap = { currentState.isInteractionEnabled },
                    onTransform = { centroid, pan, zoom ->
                        viewport = viewport.transform(boardSize, centroid, pan, zoom)
                    },
                    onTap = { tap ->
                        if (currentState.isInteractionEnabled) {
                            viewport.positionAt(tap, boardSize)?.let(currentTap)
                        }
                    },
                )
            },
    ) {
        ChineseChessBoardCanvas(
            state,
            Modifier.matchParentSize().graphicsLayer {
                scaleX = viewport.scale
                scaleY = viewport.scale
                translationX = viewport.translation.x
                translationY = viewport.translation.y
            },
        )
        if (viewport.scale > 1f) {
            TextButton(
                onClick = { viewport = ChineseChessBoardViewport() },
                modifier = Modifier.align(Alignment.TopEnd).testTag(BOARD_RESET_VIEW_TAG),
            ) { Text(resetView) }
        }
    }
}

@Composable
private fun ChineseChessBoardCanvas(state: ChineseChessGameUiState, modifier: Modifier) {
    var previousBoard by remember { mutableStateOf(state.board) }
    var feedback by remember { mutableStateOf<ChineseChessMoveFeedback?>(null) }
    val pulse = remember { Animatable(1f) }
    LaunchedEffect(state.board) {
        feedback = boardMoveFeedback(previousBoard, state.board).takeUnless {
            state.isRestoring || state.isBlindChess || state.feedback == ChineseChessFeedback.GAME_RESTORED
        }
        previousBoard = state.board
        if (feedback != null) {
            pulse.snapTo(0f)
            pulse.animateTo(1f, tween(180))
            feedback = null
        }
    }
    val boardColor = Color(0xFFD9B878)
    val lineColor = Color(0xFF55351F)
    val redColor = Color(0xFF9F2D20)
    val blackColor = Color(0xFF202020)
    val pieceColor = Color(0xFFF4DFB0)
    val legalColor = Color(0xFF2D7A55)
    val selectedColor = Color(0xFFFFC857)
    val hintColor = Color(0xFF3567B7)
    val checkColor = Color(0xFFE6482E)

    Canvas(
        modifier = modifier.semantics {
            this[BoardMovePulseKey] = feedback != null
            this[BoardMovePulseProgressKey] = pulse.value
        },
    ) {
        // During navigation Compose can draw a zero-sized transitional canvas.
        if (size.width <= 0f || size.height <= 0f) return@Canvas
        val geometry = calculateBoardGeometry(size.width, size.height)
        val cell = geometry.cellSize
        val origin = geometry.origin
        val boardRight = origin.x + 8 * cell
        val boardBottom = origin.y + 9 * cell

        drawRoundRect(
            color = boardColor,
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(cell * 0.22f),
        )

        repeat(ChineseChessBoard.HEIGHT) { y ->
            val lineY = origin.y + y * cell
            drawLine(
                color = lineColor,
                start = Offset(origin.x, lineY),
                end = Offset(boardRight, lineY),
                strokeWidth = cell * 0.035f,
            )
        }
        repeat(ChineseChessBoard.WIDTH) { x ->
            val lineX = origin.x + x * cell
            if (x == 0 || x == ChineseChessBoard.WIDTH - 1) {
                drawLine(
                    color = lineColor,
                    start = Offset(lineX, origin.y),
                    end = Offset(lineX, boardBottom),
                    strokeWidth = cell * 0.035f,
                )
            } else {
                drawLine(
                    color = lineColor,
                    start = Offset(lineX, origin.y),
                    end = Offset(lineX, origin.y + 4 * cell),
                    strokeWidth = cell * 0.035f,
                )
                drawLine(
                    color = lineColor,
                    start = Offset(lineX, origin.y + 5 * cell),
                    end = Offset(lineX, boardBottom),
                    strokeWidth = cell * 0.035f,
                )
            }
        }

        listOf(
            BoardPosition(3, 0) to BoardPosition(5, 2),
            BoardPosition(5, 0) to BoardPosition(3, 2),
            BoardPosition(3, 7) to BoardPosition(5, 9),
            BoardPosition(5, 7) to BoardPosition(3, 9),
        ).forEach { (from, to) ->
            drawLine(
                color = lineColor,
                start = geometry.center(from),
                end = geometry.center(to),
                strokeWidth = cell * 0.035f,
            )
        }

        val riverPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = lineColor.toArgb()
            textAlign = Paint.Align.CENTER
            textSize = cell * 0.38f
            typeface = android.graphics.Typeface.create(
                android.graphics.Typeface.SERIF,
                android.graphics.Typeface.BOLD,
            )
        }
        drawIntoCanvas { canvas ->
            val riverY = origin.y + 4.63f * cell
            canvas.nativeCanvas.drawText("楚河", origin.x + 2f * cell, riverY, riverPaint)
            canvas.nativeCanvas.drawText("汉界", origin.x + 6f * cell, riverY, riverPaint)
        }

        state.legalDestinations.takeUnless { state.isBlindChess }.orEmpty().forEach { destination ->
            drawCircle(
                color = legalColor,
                radius = cell * 0.11f,
                center = geometry.center(destination),
            )
        }

        state.hintedDestinations.takeUnless { state.isBlindChess }.orEmpty().forEach { destination ->
            drawCircle(
                color = hintColor,
                radius = cell * 0.15f,
                center = geometry.center(destination),
                style = Stroke(width = cell * 0.07f),
            )
        }

        state.hintedOrigins.takeUnless { state.isBlindChess }.orEmpty().forEach { originPosition ->
            drawCircle(
                color = hintColor,
                radius = cell * 0.47f,
                center = geometry.center(originPosition),
                style = Stroke(width = cell * 0.08f),
            )
        }

        state.selectedPosition?.let { selected ->
            drawCircle(
                color = selectedColor,
                radius = cell * 0.43f,
                center = geometry.center(selected),
                style = Stroke(width = cell * 0.09f),
            )
        }

        feedback?.takeUnless { state.isBlindChess }?.let { move ->
            drawCircle(
                color = (if (move.isCapture) redColor else legalColor).copy(alpha = 1f - pulse.value),
                radius = cell * (0.44f + pulse.value * 0.25f),
                center = geometry.center(move.destination),
                style = Stroke(width = cell * 0.055f),
            )
        }

        if (!state.isBlindChess) {
            state.checkedSide?.let { side ->
                val generalIndex = state.board.indexOfFirst {
                    it?.side == side && it.type == ChineseChessPieceType.GENERAL
                }
                if (generalIndex >= 0) {
                    drawCircle(
                        color = checkColor,
                        radius = cell * 0.47f,
                        center = geometry.center(
                            BoardPosition(
                                generalIndex % ChineseChessBoard.WIDTH,
                                generalIndex / ChineseChessBoard.WIDTH,
                            ),
                        ),
                        style = Stroke(width = cell * 0.09f),
                    )
                }
            }
        }

        val piecePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            textSize = cell * 0.45f
            typeface = android.graphics.Typeface.create(
                android.graphics.Typeface.SERIF,
                android.graphics.Typeface.BOLD,
            )
        }
        state.board.forEachIndexed { index, piece ->
            if (piece == null) return@forEachIndexed
            val position = BoardPosition(
                x = index % ChineseChessBoard.WIDTH,
                y = index / ChineseChessBoard.WIDTH,
            )
            val center = geometry.center(position)
            val sideColor = if (piece.side == ChineseChessSide.RED) redColor else blackColor
            drawCircle(color = pieceColor, radius = cell * 0.39f, center = center)
            drawCircle(
                color = sideColor,
                radius = cell * 0.34f,
                center = center,
                style = Stroke(width = cell * 0.045f),
            )
            if (state.isBlindChess) {
                drawCircle(
                    color = sideColor.copy(alpha = 0.2f),
                    radius = cell * 0.22f,
                    center = center,
                )
                drawCircle(
                    color = sideColor,
                    radius = cell * 0.14f,
                    center = center,
                    style = Stroke(width = cell * 0.035f),
                )
            } else {
                piecePaint.color = sideColor.toArgb()
                drawIntoCanvas { canvas ->
                    val baseline = center.y - (piecePaint.ascent() + piecePaint.descent()) / 2f
                    canvas.nativeCanvas.drawText(piece.label(), center.x, baseline, piecePaint)
                }
            }
        }
    }
}

private fun ChineseChessPiece.label(): String =
    when (type) {
        ChineseChessPieceType.GENERAL -> if (side == ChineseChessSide.RED) "帅" else "将"
        ChineseChessPieceType.ADVISOR -> if (side == ChineseChessSide.RED) "仕" else "士"
        ChineseChessPieceType.ELEPHANT -> if (side == ChineseChessSide.RED) "相" else "象"
        ChineseChessPieceType.HORSE -> "马"
        ChineseChessPieceType.CHARIOT -> "车"
        ChineseChessPieceType.CANNON -> "炮"
        ChineseChessPieceType.SOLDIER -> if (side == ChineseChessSide.RED) "兵" else "卒"
    }

private fun ChineseChessSide.displayName(): String =
    if (this == ChineseChessSide.RED) "红" else "黑"

private fun Color.toArgb(): Int =
    android.graphics.Color.argb(
        (alpha * 255).roundToInt(),
        (red * 255).roundToInt(),
        (green * 255).roundToInt(),
        (blue * 255).roundToInt(),
    )
