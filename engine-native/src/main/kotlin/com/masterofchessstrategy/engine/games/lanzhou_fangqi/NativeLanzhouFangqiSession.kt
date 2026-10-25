package com.masterofchessstrategy.engine.games.lanzhou_fangqi

import com.masterofchessstrategy.engine.Difficulty
import com.masterofchessstrategy.engine.GameResult

/** Owns one opaque native handle and serializes calls for this session. */
class NativeLanzhouFangqiSession private constructor(
    private val bridge: FangqiBridge,
    initialHandle: Long,
) : FangqiSession {
    private val lock = Any()
    private var handle = initialHandle.also {
        check(it > 0) { "Native Fangqi session could not be created" }
    }

    internal constructor(bridge: FangqiBridge) : this(bridge, bridge.create())
    constructor() : this(JniFangqiBridge)

    override fun snapshot(): FangqiSnapshot = withHandle {
        val fields = bridge.snapshot(it)
        check(fields.size == SNAPSHOT_FIELDS) { "Native Fangqi snapshot has an invalid length" }
        val board = fields.take(BOARD_POINTS)
        validateBoard(board)
        val side = when (fields[49]) {
            0 -> FangqiSide.RED
            1 -> FangqiSide.BLACK
            else -> error("Native Fangqi snapshot has an invalid player")
        }
        val pending = fields[50]
        check(pending in 0..board.count { piece -> piece == side.opponent.pieceCode }) {
            "Native Fangqi snapshot has an invalid capture count"
        }
        val result = when (fields[51]) {
            0 -> GameResult.ONGOING
            1 -> GameResult.FIRST_PLAYER_WIN
            2 -> GameResult.SECOND_PLAYER_WIN
            else -> error("Native Fangqi snapshot has an invalid result")
        }
        check(result == GameResult.ONGOING || pending == 0) {
            "Native Fangqi terminal snapshot still requires captures"
        }
        check(fields[52] in 0..1) { "Native Fangqi snapshot has an invalid undo flag" }
        FangqiSnapshot(board, side, pending, result, fields[52] == 1)
    }

    override fun apply(action: FangqiAction): FangqiMoveResult = withHandle {
        val code = when (action) {
            is FangqiAction.Move -> bridge.apply(
                it, 0, action.from.x, action.from.y, action.to.x, action.to.y,
            )
            is FangqiAction.Capture -> bridge.apply(it, 1, action.point.x, action.point.y, 0, 0)
        }
        val rejection = FangqiRejection.entries.firstOrNull { value -> value.code == code }
            ?: error("Native Fangqi returned an invalid rejection code: $code")
        FangqiMoveResult(rejection == FangqiRejection.NONE, rejection)
    }

    override fun legalActions(): List<FangqiAction> = withHandle {
        val fields = bridge.legalActions(it)
        check(fields.size % ACTION_FIELDS == 0) { "Native Fangqi actions have an invalid length" }
        List(fields.size / ACTION_FIELDS) { index -> decodeAction(fields, index * ACTION_FIELDS) }
    }

    override fun undo(): Boolean = withHandle(bridge::undo)
    override fun reset() = withHandle(bridge::reset)
    override fun serialize(): ByteArray = withHandle(bridge::serialize).copyOf()
    override fun restore(data: ByteArray): Boolean = withHandle { bridge.restore(it, data.copyOf()) }

    override fun chooseAction(difficulty: Difficulty): FangqiAction? = withHandle {
        val fields = bridge.chooseAction(it, difficulty.code)
        check(fields.isEmpty() || fields.size == ACTION_FIELDS) {
            "Native Fangqi AI action has an invalid length"
        }
        if (fields.isEmpty()) null else decodeAction(fields, 0)
    }

    override fun close() = synchronized(lock) {
        if (handle != 0L) {
            val releasedHandle = handle
            handle = 0L
            bridge.release(releasedHandle)
        }
    }

    private inline fun <T> withHandle(operation: (Long) -> T): T = synchronized(lock) {
        check(handle != 0L) { "Fangqi session is closed" }
        operation(handle)
    }

    private fun decodeAction(fields: IntArray, offset: Int): FangqiAction {
        check(fields[offset + 1] in 0..6 && fields[offset + 2] in 0..6) {
            "Native Fangqi action has an invalid point"
        }
        val from = FangqiPoint(fields[offset + 1], fields[offset + 2])
        return when (fields[offset]) {
            0 -> {
                check(fields[offset + 3] in 0..6 && fields[offset + 4] in 0..6) {
                    "Native Fangqi move has an invalid destination"
                }
                val to = FangqiPoint(fields[offset + 3], fields[offset + 4])
                check(kotlin.math.abs(from.x - to.x) + kotlin.math.abs(from.y - to.y) == 1) {
                    "Native Fangqi move is not adjacent"
                }
                FangqiAction.Move(from, to)
            }
            1 -> {
                check(fields[offset + 3] == 0 && fields[offset + 4] == 0) {
                    "Native Fangqi capture has unexpected destination fields"
                }
                FangqiAction.Capture(from)
            }
            else -> error("Native Fangqi action has an invalid kind")
        }
    }

    companion object {
        private const val BOARD_POINTS = 49
        private const val SNAPSHOT_FIELDS = 53
        private const val ACTION_FIELDS = 5

        /** A fresh history is intentional for authored puzzles. */
        fun fromPosition(board: List<Int>, side: FangqiSide): NativeLanzhouFangqiSession =
            fromPosition(board, side, JniFangqiBridge)

        internal fun fromPosition(
            board: List<Int>,
            side: FangqiSide,
            bridge: FangqiBridge,
        ): NativeLanzhouFangqiSession {
            require(board.size == BOARD_POINTS && board.all { it in 0..2 }) {
                "Fangqi puzzle board must have 49 valid points"
            }
            require(board.count { it == 1 } <= 14 && board.count { it == 2 } <= 14) {
                "Fangqi puzzle board has too many pieces"
            }
            return NativeLanzhouFangqiSession(bridge, bridge.createFromPosition(board.toIntArray(), side.code))
        }

        private fun validateBoard(board: List<Int>) {
            check(board.all { it in 0..2 } && board.count { it == 1 } <= 14 && board.count { it == 2 } <= 14) {
                "Native Fangqi snapshot has an invalid board"
            }
        }
    }
}
