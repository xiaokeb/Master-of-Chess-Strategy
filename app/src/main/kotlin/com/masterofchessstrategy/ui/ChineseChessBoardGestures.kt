package com.masterofchessstrategy.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerInputScope

/** Two fingers manipulate the view; one finger remains available for page scrolling. */
internal suspend fun PointerInputScope.detectBoardGestures(
    canTap: () -> Boolean,
    onTransform: (centroid: Offset, pan: Offset, zoom: Float) -> Unit,
    onTap: (Offset) -> Unit,
) {
    awaitEachGesture {
        val first = awaitFirstDown()
        val tapAllowedAtStart = canTap()
        var multiplePointers = false
        var cancelled = false
        do {
            val event = awaitPointerEvent()
            if (event.changes.count { it.pressed || it.previousPressed } > 1) multiplePointers = true
            if (event.changes.any { it.isConsumed }) cancelled = true
            if (multiplePointers && !cancelled) {
                if (event.changes.count { it.pressed && it.previousPressed } >= 2) {
                    onTransform(event.calculateCentroid(useCurrent = false), event.calculatePan(), event.calculateZoom())
                }
                // Consume even a stationary second finger. Releasing a pinch
                // must never become a board tap or a parent scroll gesture.
                event.changes.forEach { it.consume() }
            } else if (!multiplePointers) {
                val pointer = event.changes.firstOrNull { it.id == first.id }
                if (pointer == null || (pointer.position - first.position).getDistance() > viewConfiguration.touchSlop) {
                    cancelled = true
                }
                if (pointer != null && !pointer.pressed && !cancelled && tapAllowedAtStart) {
                    pointer.consume()
                    onTap(pointer.position)
                }
            }
        } while (event.changes.any { it.pressed })
    }
}
