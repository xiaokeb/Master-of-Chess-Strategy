package com.masterofchessstrategy.games.lanzhou_fangqi

import com.masterofchessstrategy.engine.games.lanzhou_fangqi.FangqiPoint
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt

internal data class FangqiBoardGeometry(
    val originX: Float,
    val originY: Float,
    val spacing: Float,
) {
    fun centerX(point: FangqiPoint): Float = originX + point.x * spacing
    fun centerY(point: FangqiPoint): Float = originY + point.y * spacing
}

internal fun fangqiBoardGeometry(width: Float, height: Float): FangqiBoardGeometry {
    require(width > 0f && height > 0f)
    val spacing = min(width, height) / 7f
    return FangqiBoardGeometry(
        originX = (width - 6f * spacing) / 2f,
        originY = (height - 6f * spacing) / 2f,
        spacing = spacing,
    )
}

internal fun fangqiPointAt(
    tapX: Float,
    tapY: Float,
    width: Float,
    height: Float,
): FangqiPoint? {
    if (width <= 0f || height <= 0f) return null
    val geometry = fangqiBoardGeometry(width, height)
    val x = ((tapX - geometry.originX) / geometry.spacing).roundToInt()
    val y = ((tapY - geometry.originY) / geometry.spacing).roundToInt()
    if (x !in 0..6 || y !in 0..6) return null
    val point = FangqiPoint(x, y)
    return point.takeIf {
        hypot(tapX - geometry.centerX(it), tapY - geometry.centerY(it)) <= geometry.spacing * 0.45f
    }
}
