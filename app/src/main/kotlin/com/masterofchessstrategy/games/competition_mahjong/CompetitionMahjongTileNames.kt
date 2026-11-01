package com.masterofchessstrategy.games.competition_mahjong

/** Canonical labels for engine tile ids used by lessons and tables. */
internal fun competitionMahjongTileName(tile: Int): String {
    require(tile in 0..41)
    return when (tile) {
        in 0..8 -> "${tile + 1}万"
        in 9..17 -> "${tile - 8}条"
        in 18..26 -> "${tile - 17}筒"
        else -> listOf("东", "南", "西", "北", "中", "发", "白",
            "春", "夏", "秋", "冬", "梅", "兰", "竹", "菊")[tile - 27]
    }
}
