package com.masterofchessstrategy.engine

/** Versioned JNI frame. No scoring action or non-finite estimate crosses the public boundary. */
internal object GoSearchResultDecoder {
    fun decode(frame: DoubleArray): GoSearchResult {
        check(frame.size == 9 && frame.all(Double::isFinite)) { "Invalid Go search frame" }
        check(frame[0] == 1.0) { "Unsupported Go search frame" }
        val status = integer(frame[1], 0..3)
        if (status != 0) {
            check(frame[2] == 0.0 && frame[3] == -1.0 && frame[4] == -1.0 &&
                frame.sliceArray(5..8).all { it == 0.0 }) { "Non-move frame carries a candidate" }
            return when (status) {
                1 -> GoSearchResult.Cancelled
                2 -> GoSearchResult.Busy
                else -> GoSearchResult.BudgetExhausted
            }
        }
        val action = when (integer(frame[2], 1..2)) {
            1 -> GoAction.Place(BoardPosition(integer(frame[3], 0..18), integer(frame[4], 0..18)))
            else -> {
                check(frame[3] == -1.0 && frame[4] == -1.0) { "Pass has board coordinates" }
                GoAction.Pass
            }
        }
        check(frame[6] >= 0.0 && frame[7] in 0.0..1.0) { "Invalid Go search estimates" }
        return GoSearchResult.Move(action, GoSearchMetrics(integer(frame[5], 1..512), frame[6], frame[7], frame[8]))
    }

    private fun integer(value: Double, range: IntRange): Int {
        check(value >= range.first && value <= range.last && value == value.toInt().toDouble()) {
            "Invalid integer field in Go search frame"
        }
        return value.toInt()
    }
}
