package com.masterofchessstrategy.engine

/** Version/count followed by five fields per event; no JNI required to validate. */
internal object GoEventHistoryDecoder {
    const val MAX_EVENTS = 16_386 // Native capacity includes the two final confirmations.

    fun decode(fields: IntArray, boardSize: Int): List<GoAction> {
        require(boardSize in listOf(9, 13, 19))
        check(fields.size >= 2 && fields[0] == 1) { "Invalid Go event header" }
        val count = fields[1]
        check(count in 0..MAX_EVENTS && fields.size == 2 + count * 5) {
            "Invalid Go event count"
        }
        fun point(x: Int, y: Int): BoardPosition {
            check(x in 0 until boardSize && y in 0 until boardSize) { "Invalid Go event point" }
            return BoardPosition(x, y)
        }
        fun player(code: Int): GoPlayer = GoPlayer.entries.firstOrNull { it.code == code }
            ?: error("Invalid Go event player")
        return List(count) { index ->
            val offset = 2 + index * 5
            val kind = fields[offset]
            val a = fields[offset + 1]
            val b = fields[offset + 2]
            val c = fields[offset + 3]
            val d = fields[offset + 4]
            check(d == 0) { "Invalid Go reserved field" }
            when (kind) {
                1 -> { check(c == 0); GoAction.Place(point(a, b)) }
                2 -> { check(a == 0 && b == 0 && c == 0); GoAction.Pass }
                3 -> GoAction.ToggleDeadGroup(point(a, b), player(c))
                4 -> { check(b >= 0 && c == 0); GoAction.ConfirmScore(player(a), b) }
                5 -> { check(b == 0 && c == 0); GoAction.ResumePlay(player(a)) }
                else -> error("Unknown Go event kind")
            }
        }
    }
}
