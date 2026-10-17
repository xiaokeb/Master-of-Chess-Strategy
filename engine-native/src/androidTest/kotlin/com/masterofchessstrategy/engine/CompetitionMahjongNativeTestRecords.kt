package com.masterofchessstrategy.engine

/** Synthetic, inventory-checked records. Never reads, clears, or rewrites an App/user save. */
internal object CompetitionMahjongNativeTestRecords {
    fun record(firstHand: List<Int>, viewer: Int, events: List<List<Int>> = emptyList(),
               backDraws: List<Int> = emptyList()): ByteArray {
        check(viewer in 0..3 && firstHand.size <= 14)
        val remaining = IntArray(42) { if (it < 34) 4 else 1 }
        val hands = Array(4) { mutableListOf<Int>() }
        hands[0].addAll(firstHand)
        for (tile in firstHand + backDraws) check(tile in 0..33 && --remaining[tile] >= 0)
        for (seat in 0..3) {
            val target = if (seat == 0) 14 else 13
            for (tile in 0..33) while (remaining[tile] > 0 && hands[seat].size < target) {
                hands[seat].add(tile); --remaining[tile]
            }
            check(hands[seat].size == target)
        }
        val positions = IntArray(4)
        val wall = mutableListOf<Int>()
        repeat(3) { for (seat in 0..3) repeat(4) { wall.add(hands[seat][positions[seat]++]) } }
        repeat(2) { wall.add(hands[0][positions[0]++]) }
        for (seat in 1..3) wall.add(hands[seat][positions[seat]++])
        for (tile in 0..41) repeat(remaining[tile]) { wall.add(tile) }
        wall.addAll(backDraws.reversed())
        check(wall.size == 144)
        val payload = mutableListOf(77, 79, 67, 77, 1, 0, 0)
        payload.addAll(wall)
        put32(payload, events.size.toLong())
        for (event in events) { check(event.size == 4); payload.addAll(event) }
        var checksum = 2166136261L
        for (byte in payload) checksum = ((checksum xor byte.toLong()) * 16777619L) and 0xffffffffL
        put32(payload, checksum)
        val envelope = mutableListOf(77, 67, 77, 83, 1, viewer, 0, 0)
        put32(envelope, payload.size.toLong())
        envelope.addAll(payload)
        val crc = java.util.zip.CRC32()
        crc.update(envelope.map { it.toByte() }.toByteArray())
        put32(envelope, crc.value)
        return envelope.map { it.toByte() }.toByteArray()
    }

    private fun put32(target: MutableList<Int>, value: Long) {
        for (shift in 0..3) target.add(((value ushr (8 * shift)) and 255).toInt())
    }
}
