package com.masterofchessstrategy.engine

/** Canonical six-field Pikafish-style FEN for a history-free Xiangqi setup. */
data class ChineseChessFenPosition(
    val sideToMove: ChineseChessSide,
    val pieces: List<PositionedChineseChessPiece>,
    val noCapturePlies: Int,
    val fullMoveNumber: Int,
) {
    /** The FEN full-move number is display metadata; MOCX stores the board and half-move clock. */
    fun toEngineState(): ByteArray =
        ChineseChessPositionCodec.encode(sideToMove, pieces, noCapturePlies)
}

/** Parses and formats the exact FEN dialect emitted by the bundled native engine. */
object ChineseChessFenCodec {
    fun parse(fen: String): ChineseChessFenPosition {
        require(fen.length in 1..MAX_FEN_LENGTH) { "FEN is empty or too long" }
        val fields = fen.trim().split(Regex("\\s+"))
        require(fields.size == 6 && fields[2] == "-" && fields[3] == "-") {
            "Xiangqi FEN needs six fields with no castling or en-passant rights"
        }
        val side = when (fields[1]) {
            "w" -> ChineseChessSide.RED
            "b" -> ChineseChessSide.BLACK
            else -> throw IllegalArgumentException("Unknown FEN side to move")
        }
        val noCapturePlies = fields[4].toIntOrNull()
        require(noCapturePlies != null && noCapturePlies in 0..120) {
            "FEN no-capture counter is outside 0..120"
        }
        val fullMoveNumber = fields[5].toIntOrNull()
        require(fullMoveNumber != null && fullMoveNumber >= 1) {
            "FEN full-move number must be positive"
        }

        val ranks = fields[0].split('/')
        require(ranks.size == ChineseChessBoard.HEIGHT) { "FEN needs ten ranks" }
        val pieces = buildList {
            ranks.forEachIndexed { y, rank ->
                var x = 0
                var previousWasEmpty = false
                rank.forEach { symbol ->
                    if (symbol in '1'..'9') {
                        require(!previousWasEmpty) { "Adjacent FEN empty counts are not canonical" }
                        x += symbol.digitToInt()
                        previousWasEmpty = true
                    } else {
                        require(x < ChineseChessBoard.WIDTH) { "FEN rank is too wide" }
                        require(symbol in 'A'..'Z' || symbol in 'a'..'z') {
                            "FEN piece symbols must be ASCII letters"
                        }
                        val type = SYMBOLS[symbol.lowercaseChar()]
                            ?: throw IllegalArgumentException("Unknown FEN piece symbol")
                        add(
                            PositionedChineseChessPiece(
                                BoardPosition(x, y),
                                ChineseChessPiece(
                                    type,
                                    if (symbol.isUpperCase()) {
                                        ChineseChessSide.RED
                                    } else {
                                        ChineseChessSide.BLACK
                                    },
                                ),
                            ),
                        )
                        ++x
                        previousWasEmpty = false
                    }
                    require(x <= ChineseChessBoard.WIDTH) { "FEN rank is too wide" }
                }
                require(x == ChineseChessBoard.WIDTH) { "FEN rank is not nine files wide" }
            }
        }
        val position = ChineseChessFenPosition(side, pieces, noCapturePlies, fullMoveNumber)
        position.toEngineState() // Apply the same count, general, and duplicate checks as setups.
        return position
    }

    fun format(position: ChineseChessFenPosition): String {
        position.toEngineState()
        require(position.fullMoveNumber >= 1) { "FEN full-move number must be positive" }
        val bySquare = position.pieces.associateBy { it.position }
        val board = buildString {
            repeat(ChineseChessBoard.HEIGHT) { y ->
                if (y > 0) append('/')
                var empty = 0
                repeat(ChineseChessBoard.WIDTH) { x ->
                    val piece = bySquare[BoardPosition(x, y)]?.piece
                    if (piece == null) {
                        ++empty
                    } else {
                        if (empty > 0) {
                            append(empty)
                            empty = 0
                        }
                        val symbol = requireNotNull(SYMBOLS.entries.firstOrNull {
                            it.value == piece.type
                        }?.key)
                        append(
                            if (piece.side == ChineseChessSide.RED) {
                                symbol.uppercaseChar()
                            } else {
                                symbol
                            },
                        )
                    }
                }
                if (empty > 0) append(empty)
            }
        }
        val side = if (position.sideToMove == ChineseChessSide.RED) "w" else "b"
        return "$board $side - - ${position.noCapturePlies} ${position.fullMoveNumber}"
    }

    private const val MAX_FEN_LENGTH = 256
    private val SYMBOLS = mapOf(
        'k' to ChineseChessPieceType.GENERAL,
        'a' to ChineseChessPieceType.ADVISOR,
        'b' to ChineseChessPieceType.ELEPHANT,
        'n' to ChineseChessPieceType.HORSE,
        'r' to ChineseChessPieceType.CHARIOT,
        'c' to ChineseChessPieceType.CANNON,
        'p' to ChineseChessPieceType.SOLDIER,
    )
}
