package com.masterofchessstrategy.games.go

import com.masterofchessstrategy.engine.GoPlayer

internal const val GO_SETUP_FORMAT_VERSION = 6
internal const val GO_SETUP_BOARD_OFFSET = 17

/** Called only on canonical, Native-replayed records. Compare actor and original stones. */
internal fun goSetupRoot(record: ByteArray): ByteArray? {
    if (record.size < GO_SETUP_BOARD_OFFSET || record[4].toInt() != GO_SETUP_FORMAT_VERSION ||
        !record.copyOfRange(0, 4).contentEquals(byteArrayOf(77, 79, 67, 71))) return null
    val size = record[5].toInt()
    require(size in setOf(9, 13, 19) && record.size >= GO_SETUP_BOARD_OFFSET + size * size)
    return record.copyOfRange(GO_SETUP_BOARD_OFFSET - 1, GO_SETUP_BOARD_OFFSET + size * size)
}

/** Generate only FF4 setup properties; Native validates liberties and the first legal turn. */
internal fun goSetupSgf(size: Int, cells: IntArray, first: GoPlayer): String {
    require(size in setOf(9, 13, 19) && cells.size == size * size && cells.all { it in 0..2 })
    return buildString {
        append("(;FF[4]GM[1]SZ[$size]RU[Chinese]KM[7.5]")
        for ((stone, property) in listOf(1 to "AB", 2 to "AW")) {
            val points = cells.indices.filter { cells[it] == stone }
            if (points.isEmpty()) continue
            append(property)
            for (point in points) {
                append('[')
                append('a' + point % size)
                append('a' + point / size)
                append(']')
            }
        }
        append(if (first == GoPlayer.BLACK) "PL[B])" else "PL[W])")
    }
}
