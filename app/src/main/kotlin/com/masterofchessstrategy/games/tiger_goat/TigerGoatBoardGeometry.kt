package com.masterofchessstrategy.games.tiger_goat

import kotlin.math.hypot
import kotlin.math.min

data class TigerGoatPoint(val id: Int, val x: Int, val y: Int)

data class TigerGoatEdge(val from: Int, val to: Int)

data class TigerGoatBoardSpec(
    val points: List<TigerGoatPoint>,
    val edges: List<TigerGoatEdge>,
) {
    init {
        require(points.isNotEmpty() && points.map { it.id } == points.indices.toList())
        require(points.map { it.x to it.y }.distinct().size == points.size)
        require(edges.all { it.from in points.indices && it.to in points.indices && it.from != it.to })
        require(edges.map { minOf(it.from, it.to) to maxOf(it.from, it.to) }.distinct().size == edges.size)
    }
}

data class TigerGoatPixelPoint(val x: Float, val y: Float)

data class TigerGoatViewport(
    val centers: List<TigerGoatPixelPoint>,
    val unit: Float,
) {
    fun hitTest(tap: TigerGoatPixelPoint): Int? {
        val radius = unit * 0.46f
        return centers.indices.minByOrNull { index ->
            hypot(tap.x - centers[index].x, tap.y - centers[index].y)
        }?.takeIf { index ->
            hypot(tap.x - centers[index].x, tap.y - centers[index].y) <= radius
        }
    }
}

fun tigerGoatViewport(width: Float, height: Float, spec: TigerGoatBoardSpec): TigerGoatViewport {
    require(width > 0f && height > 0f)
    val minX = spec.points.minOf { it.x }
    val maxX = spec.points.maxOf { it.x }
    val minY = spec.points.minOf { it.y }
    val maxY = spec.points.maxOf { it.y }
    val unit = min(width / (maxX - minX + 1.2f), height / (maxY - minY + 1.2f))
    val left = (width - (maxX - minX) * unit) / 2f
    val top = (height - (maxY - minY) * unit) / 2f
    return TigerGoatViewport(
        centers = spec.points.map { point ->
            TigerGoatPixelPoint(left + (point.x - minX) * unit, top + (point.y - minY) * unit)
        },
        unit = unit,
    )
}
