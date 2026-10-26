package com.masterofchessstrategy.engine

/** Validates bounded public frames before publishing them to a table. */
internal object LanzhouMahjongBridgeDecoder {
    fun advance(fields: IntArray, stepLimit: Int): LanzhouMahjongBotAdvance {
        require(stepLimit in 1..512)
        check(fields.size == 3 && fields[0] == 1 && fields[2] in 0..stepLimit)
        val stop = LanzhouMahjongBotStop.entries.firstOrNull { it.code == fields[1] }
            ?: error("Invalid Lanzhou Mahjong bot stop")
        check(stop != LanzhouMahjongBotStop.STEP_LIMIT || fields[2] == stepLimit)
        return LanzhouMahjongBotAdvance(stop, fields[2])
    }

    fun seat(seat: Int, viewer: Int, fields: IntArray): LanzhouMahjongSeatView {
        require(seat in 0..3 && viewer in 0..3)
        check(fields.size in 6..140 && fields[0] in 0..14 && fields[1] in 0..1 &&
            fields[2] in 0..1 && fields[3] in 0..4) { "Malformed Mahjong seat" }
        var index = 4
        val melds = List(fields[3]) {
            check(index + 3 <= fields.size) { "Truncated Mahjong melds" }
            val kind = fields[index++]
            val tile = fields[index++]
            val from = fields[index++]
            val meldKind = LanzhouMahjongMeldKind.entries.firstOrNull { it.code == kind }
                ?: error("Invalid Mahjong meld kind")
            check(from in 0..3)
            val hidden = meldKind == LanzhouMahjongMeldKind.CONCEALED_KONG && seat != viewer
            check(if (hidden) tile == -1 else tile in 0..26) { "Mahjong kong privacy failed" }
            check(meldKind != LanzhouMahjongMeldKind.CONCEALED_KONG || from == seat)
            LanzhouMahjongMeld(meldKind, tile, from)
        }
        check(index < fields.size && fields[index] in 0..108)
        val discardCount = fields[index++]
        check(index + discardCount < fields.size) { "Truncated Mahjong discards" }
        val discards = fields.slice(index until index + discardCount)
        check(discards.all { it in 0..26 })
        index += discardCount
        val visibleCount = fields[index++]
        check(visibleCount in 0..14 && fields.size == index + visibleCount)
        check(visibleCount == if (seat == viewer) fields[0] else 0) { "Mahjong hand privacy failed" }
        val tiles = fields.slice(index until fields.size)
        check(tiles.all { it in 0..26 })
        return LanzhouMahjongSeatView(seat, fields[0], fields[1] == 1, fields[2] == 1,
            melds, discards, tiles)
    }
}
