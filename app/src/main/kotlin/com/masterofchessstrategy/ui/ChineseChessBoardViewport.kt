package com.masterofchessstrategy.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import com.masterofchessstrategy.engine.BoardPosition
import com.masterofchessstrategy.engine.ChineseChessPiece

/** A view transform only. It never changes coordinates stored by the engine. */
internal data class ChineseChessBoardViewport(
    val scale: Float = 1f,
    val translation: Offset = Offset.Zero,
) {
    fun transform(size: Size, centroid: Offset, pan: Offset, zoom: Float): ChineseChessBoardViewport {
        if (!size.isUsable() || !centroid.isFinite() || !pan.isFinite() ||
            !zoom.isFinite() || zoom <= 0f) return this
        val nextScale = (scale * zoom).coerceIn(1f, MAX_SCALE)
        val center = Offset(size.width / 2, size.height / 2)
        // Keep the point under the fingers stable, except at the content edge.
        val shifted = (translation + center - centroid) * (nextScale / scale) + centroid - center + pan
        val cell = calculateBoardGeometry(size.width, size.height).cellSize
        val maxX = ((cell * 9.2f * nextScale - size.width) / 2).coerceAtLeast(0f)
        val maxY = ((cell * 10.2f * nextScale - size.height) / 2).coerceAtLeast(0f)
        return ChineseChessBoardViewport(
            nextScale, Offset(shifted.x.coerceIn(-maxX, maxX), shifted.y.coerceIn(-maxY, maxY)),
        )
    }

    fun boardPoint(screenPoint: Offset, size: Size): Offset {
        val center = Offset(size.width / 2, size.height / 2)
        return (screenPoint - center - translation) / scale + center
    }

    fun screenPoint(boardPoint: Offset, size: Size): Offset {
        val center = Offset(size.width / 2, size.height / 2)
        return (boardPoint - center) * scale + center + translation
    }

    fun positionAt(tap: Offset, size: Size): BoardPosition? {
        if (!size.isUsable() || !tap.isFinite() || tap.x !in 0f..size.width ||
            tap.y !in 0f..size.height) return null
        return boardPositionAt(boardPoint(tap, size), size.width, size.height)
    }

    companion object { const val MAX_SCALE = 2.5f }
}

private fun Offset.isFinite() = x.isFinite() && y.isFinite()
private fun Size.isUsable() = width.isFinite() && height.isFinite() && width > 0 && height > 0

internal data class ChineseChessMoveFeedback(val destination: BoardPosition, val isCapture: Boolean)

/** Ignore setup/reset snapshots; only a single piece relocation gets a pulse. */
internal fun boardMoveFeedback(
    before: List<ChineseChessPiece?>,
    after: List<ChineseChessPiece?>,
): ChineseChessMoveFeedback? {
    if (before.size != 90 || after.size != 90) return null
    val changed = before.indices.filter { before[it] != after[it] }
    if (changed.size != 2) return null
    val from = changed.singleOrNull { before[it] != null && after[it] == null } ?: return null
    val to = changed.single { it != from }
    val moving = before[from] ?: return null
    if (after[to] != moving || before[to]?.side == moving.side) return null
    return ChineseChessMoveFeedback(BoardPosition(to % 9, to / 9), before[to] != null)
}
