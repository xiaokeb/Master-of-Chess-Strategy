package com.masterofchessstrategy.custom

import com.masterofchessstrategy.engine.BoardPosition
import com.masterofchessstrategy.engine.ChineseChessPiece
import com.masterofchessstrategy.engine.ChineseChessPieceType
import com.masterofchessstrategy.engine.ChineseChessPositionCodec
import com.masterofchessstrategy.engine.ChineseChessSide
import com.masterofchessstrategy.engine.PositionedChineseChessPiece

/** A canonical subset of the standard position, never an arbitrary replacement board. */
internal object ChineseChessHandicapConfig {
    private const val PREFIX = "handicap:1:"
    private val idPattern = Regex("[0-9a-f]{32}")
    private val original = standardBoard()
    val removableSquares: Set<Int> = original.indices.filterTo(linkedSetOf()) {
        original[it] != null && original[it]?.type != ChineseChessPieceType.GENERAL
    }

    fun board(removed: Set<Int>): List<ChineseChessPiece?> {
        require(removableSquares.containsAll(removed)) { "Only standard non-general pieces may be removed" }
        return original.mapIndexed { index, piece -> if (index in removed) null else piece }
    }

    /** The nonce separates an explicit new game from continuing an identical previous setup. */
    fun sessionVariant(removed: Set<Int>, id: String): String {
        require(idPattern.matches(id))
        require(removed.isNotEmpty())
        board(removed)
        val squares = removed.sorted().joinToString("") { "%02x".format(it) }
        return "$PREFIX$squares:$id"
    }

    fun removedSquares(variant: String): Set<Int> {
        require(variant.startsWith(PREFIX))
        val fields = variant.removePrefix(PREFIX).split(':')
        require(fields.size == 2 && fields[0].length in 2..60 && fields[0].length % 2 == 0)
        val indices = fields[0].chunked(2).map { requireNotNull(it.toIntOrNull(16)) }
        val removed = indices.toSet()
        require(sessionVariant(removed, fields[1]) == variant) { "Non-canonical handicap variant" }
        return removed
    }

    fun initialState(variant: String): ByteArray {
        val board = board(removedSquares(variant))
        // Only the two central soldiers stand between generals in this standard subset.
        require(board[31] != null || board[58] != null) { "Handicap would expose facing generals" }
        val pieces = board.mapIndexedNotNull { index, piece ->
            piece?.let { PositionedChineseChessPiece(BoardPosition(index % 9, index / 9), it) }
        }
        return ChineseChessPositionCodec.encode(ChineseChessSide.RED, pieces)
    }
}
