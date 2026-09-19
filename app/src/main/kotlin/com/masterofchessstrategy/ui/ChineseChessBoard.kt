package com.masterofchessstrategy.ui

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.masterofchessstrategy.engine.BoardPosition
import com.masterofchessstrategy.engine.ChineseChessBoard
import com.masterofchessstrategy.engine.ChineseChessPiece
import com.masterofchessstrategy.engine.ChineseChessPieceType
import com.masterofchessstrategy.engine.ChineseChessSide
import com.masterofchessstrategy.game.ChineseChessGameUiState
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt

internal const val CHINESE_CHESS_BOARD_TAG = "chinese_chess_board"

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
    val boardColor = Color(0xFFD9B878)
    val lineColor = Color(0xFF55351F)
    val redColor = Color(0xFF9F2D20)
    val blackColor = Color(0xFF202020)
    val pieceColor = Color(0xFFF4DFB0)
    val legalColor = Color(0xFF2D7A55)
    val selectedColor = Color(0xFFFFC857)
    val hintColor = Color(0xFF3567B7)

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .testTag(CHINESE_CHESS_BOARD_TAG)
            .semantics {
                contentDescription = "中国象棋棋盘，" + state.currentSide.displayName() + "方行棋"
            }
            .pointerInput(state.isInteractionEnabled, state.result) {
                if (state.isInteractionEnabled) {
                    detectTapGestures { tap ->
                        boardPositionAt(tap, size.width.toFloat(), size.height.toFloat())
                            ?.let(onSquareTap)
                    }
                }
            },
    ) {
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
