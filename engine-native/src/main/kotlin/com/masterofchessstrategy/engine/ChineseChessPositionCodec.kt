package com.masterofchessstrategy.engine

import java.util.zip.CRC32

/** One piece in a custom, history-free Chinese chess position. */
data class PositionedChineseChessPiece(
    val position: BoardPosition,
    val piece: ChineseChessPiece,
)

/**
 * Encodes reviewed puzzle/opening positions in the native MOCX v2 format.
 *
 * Runtime content never constructs native memory directly; it passes these
 * checks and then uses the engine's ordinary restore validation.
 */
object ChineseChessPositionCodec {
    fun encode(
        sideToMove: ChineseChessSide,
        pieces: List<PositionedChineseChessPiece>,
    ): ByteArray {
        require(pieces.size in 2..32) { "A position must contain 2 to 32 pieces" }
        require(pieces.map { it.position }.distinct().size == pieces.size) {
            "A position cannot place two pieces on one intersection"
        }
        pieces.forEach { ChineseChessBoard.requireInside(it.position) }
        ChineseChessSide.entries.forEach { side ->
            require(
                pieces.count {
                    it.piece.side == side &&
                        it.piece.type == ChineseChessPieceType.GENERAL
                } == 1,
            ) {
                "A position must contain exactly one general for each side"
            }
        }

        val data = ByteArray(PAYLOAD_SIZE + CHECKSUM_SIZE)
        MAGIC.copyInto(data)
        data[4] = FORMAT_VERSION
        data[5] = GameType.CHINESE_CHESS.code.toByte()
        data[6] = sideToMove.code.toByte()
        // no-capture plies, adjudicated result, and history size remain zero.
        pieces.forEach { positioned ->
            val square = positioned.position.y * ChineseChessBoard.WIDTH +
                positioned.position.x
            val sideBit = if (positioned.piece.side == ChineseChessSide.BLACK) {
                0x80
            } else {
                0
            }
            data[HEADER_SIZE + square] =
                (sideBit or positioned.piece.type.code).toByte()
        }
        val checksum = CRC32().apply { update(data, 0, PAYLOAD_SIZE) }.value
        repeat(CHECKSUM_SIZE) { byte ->
            data[PAYLOAD_SIZE + byte] = (checksum ushr (byte * 8)).toByte()
        }
        return data
    }

    private const val HEADER_SIZE = 12
    private const val PAYLOAD_SIZE = HEADER_SIZE + 90
    private const val CHECKSUM_SIZE = 4
    private const val FORMAT_VERSION: Byte = 2
    private val MAGIC = byteArrayOf('M'.code.toByte(), 'O'.code.toByte(), 'C'.code.toByte(), 'X'.code.toByte())
}
