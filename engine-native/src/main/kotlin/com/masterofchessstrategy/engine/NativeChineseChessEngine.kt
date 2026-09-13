package com.masterofchessstrategy.engine

import com.masterofchessstrategy.engine.internal.ChineseChessBridge
import com.masterofchessstrategy.engine.internal.JniChineseChessBridge

/** Thread-safe Kotlin owner for one native Chinese chess session. */
class NativeChineseChessEngine internal constructor(
    private val bridge: ChineseChessBridge,
    private val masterNetworkPathProvider: (() -> String)? = null,
) : ChineseChessAiEngine {
    private val lock = Any()
    private var handle = bridge.create().also {
        check(it > 0) { "Native engine could not be created" }
    }

    constructor() : this(JniChineseChessBridge)

    constructor(masterNetworkPathProvider: () -> String) :
        this(JniChineseChessBridge, masterNetworkPathProvider)

    override val gameType: GameType = GameType.CHINESE_CHESS

    override val currentPlayer: PlayerId
        get() =
            when (val code = withHandle(bridge::currentPlayer)) {
                0, 1 -> PlayerId(code)
                else -> protocolFailure("current player", code)
            }

    override fun reset() {
        withHandle(bridge::reset)
    }

    override fun apply(action: BoardMove): ActionResult {
        ChineseChessBoard.requireInside(action.from)
        ChineseChessBoard.requireInside(action.to)
        val code = withHandle {
            bridge.applyMove(
                it,
                action.from.x,
                action.from.y,
                action.to.x,
                action.to.y,
            )
        }
        return if (code == NATIVE_SUCCESS) {
            ActionResult.Accepted
        } else {
            ActionResult.Rejected(engineError(code))
        }
    }

    override fun undo(): Boolean = withHandle(bridge::undo)

    override fun legalActions(): List<BoardMove> {
        val encoded = withHandle(bridge::legalMoves)
        check(encoded.size % MOVE_FIELD_COUNT == 0) {
            "Native engine returned malformed moves"
        }
        return buildList(encoded.size / MOVE_FIELD_COUNT) {
            for (offset in encoded.indices step MOVE_FIELD_COUNT) {
                val from = BoardPosition(encoded[offset], encoded[offset + 1])
                val to = BoardPosition(encoded[offset + 2], encoded[offset + 3])
                ChineseChessBoard.requireInside(from)
                ChineseChessBoard.requireInside(to)
                add(BoardMove(from, to))
            }
        }
    }

    override fun chooseMove(difficulty: Difficulty): BoardMove? {
        val networkPath = if (difficulty == Difficulty.MASTER) {
            checkNotNull(masterNetworkPathProvider) {
                "Master Chinese chess AI requires the bundled network"
            }.invoke()
        } else {
            null
        }
        val encoded = withHandle {
            bridge.bestMove(it, difficulty.code, networkPath)
        }
        check(encoded.size == 0 || encoded.size == MOVE_FIELD_COUNT) {
            "Native engine returned malformed AI move"
        }
        if (encoded.isEmpty()) {
            return null
        }
        val from = BoardPosition(encoded[0], encoded[1])
        val to = BoardPosition(encoded[2], encoded[3])
        ChineseChessBoard.requireInside(from)
        ChineseChessBoard.requireInside(to)
        return BoardMove(from, to)
    }

    override fun gameResult(): GameResult =
        when (val code = withHandle(bridge::gameResult)) {
            0 -> GameResult.ONGOING
            1 -> GameResult.FIRST_PLAYER_WIN
            2 -> GameResult.SECOND_PLAYER_WIN
            3 -> GameResult.DRAW
            else -> protocolFailure("game result", code)
        }

    override fun serialize(): ByteArray =
        withHandle(bridge::serialize).copyOf()

    override fun restore(data: ByteArray): RestoreResult {
        val code = withHandle { bridge.restore(it, data.copyOf()) }
        return if (code == NATIVE_SUCCESS) {
            RestoreResult.Restored
        } else {
            RestoreResult.Rejected(engineError(code))
        }
    }

    override fun pieceAt(position: BoardPosition): ChineseChessPiece? {
        ChineseChessBoard.requireInside(position)
        val encoded = withHandle {
            bridge.pieceAt(it, position.x, position.y)
        }
        if (encoded == EMPTY_SQUARE) {
            return null
        }
        check(
            encoded in 1..MAX_ENCODED_PIECE &&
                encoded and PIECE_RESERVED_BITS == 0
        ) {
            "Native engine returned malformed piece"
        }
        val typeCode = encoded and PIECE_TYPE_MASK
        val type = ChineseChessPieceType.entries.firstOrNull {
            it.code == typeCode
        } ?: protocolFailure("piece type", typeCode)
        val side = if (encoded and BLACK_SIDE_BIT == 0) {
            ChineseChessSide.RED
        } else {
            ChineseChessSide.BLACK
        }
        return ChineseChessPiece(type, side)
    }

    override fun close() {
        synchronized(lock) {
            if (handle != CLOSED_HANDLE) {
                bridge.release(handle)
                handle = CLOSED_HANDLE
            }
        }
    }

    private inline fun <T> withHandle(block: (Long) -> T): T =
        synchronized(lock) {
            check(handle != CLOSED_HANDLE) { "Engine session is closed" }
            block(handle)
        }

    private fun engineError(code: Int): EngineError =
        when (code) {
            1 -> EngineError.ILLEGAL_ACTION
            2 -> EngineError.INVALID_STATE
            3 -> EngineError.CORRUPTED_DATA
            4 -> EngineError.UNSUPPORTED
            else -> protocolFailure("engine error", code)
        }

    private fun protocolFailure(field: String, code: Int): Nothing =
        error("Native engine returned unknown $field code: $code")

    private companion object {
        const val CLOSED_HANDLE = 0L
        const val NATIVE_SUCCESS = 0
        const val EMPTY_SQUARE = 0
        const val BLACK_SIDE_BIT = 0x80
        const val PIECE_TYPE_MASK = 0x07
        const val PIECE_RESERVED_BITS = 0x78
        const val MAX_ENCODED_PIECE = 0xFF
        const val MOVE_FIELD_COUNT = 4
    }
}
