package com.masterofchessstrategy.games.competition_mahjong.match.hint

import com.masterofchessstrategy.engine.CompetitionMahjongAction
import com.masterofchessstrategy.engine.CompetitionMahjongRoundActionKind
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Bounded MCMT v4 section: revision/hand/kind/name length, tile/sequence/reserved, digest and ASCII identity. */
internal object CompetitionMahjongHintReceiptCodec {
    const val MIN_BYTES = 41
    const val MAX_BYTES = 104

    fun encode(receipt: CompetitionMahjongHintReceipt): ByteArray {
        val name = receipt.advisorIdentity.toByteArray(Charsets.US_ASCII)
        val output = ByteBuffer.allocate(40 + name.size).order(ByteOrder.BIG_ENDIAN)
        output.put(1).put(receipt.handIndex.toByte()).put(receipt.action.kind.code.toByte()).put(name.size.toByte())
            .put(receipt.action.tile.toByte()).put(receipt.action.sequenceStart.toByte()).putShort(0)
        receipt.positionSha256.chunked(2).forEach { output.put(it.toInt(16).toByte()) }
        output.put(name)
        return output.array()
    }

    fun decode(bytes: ByteArray): CompetitionMahjongHintReceipt? {
        if (bytes.size !in MIN_BYTES..MAX_BYTES) return null
        val input = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        if (input.get().toInt() != 1) return null
        val hand = input.get().toInt()
        val kindCode = input.get().toInt()
        val kind = CompetitionMahjongRoundActionKind.entries.firstOrNull { it.code == kindCode } ?: return null
        val length = input.get().toInt()
        val tile = input.get().toInt()
        val sequence = input.get().toInt()
        if (length !in 1..64 || bytes.size != 40 + length || input.short.toInt() != 0) return null
        val digest = ByteArray(32).also(input::get).joinToString("") {
            (it.toInt() and 255).toString(16).padStart(2, '0')
        }
        val name = ByteArray(length).also(input::get).toString(Charsets.US_ASCII)
        return try {
            CompetitionMahjongHintReceipt(hand, digest, name, CompetitionMahjongAction(kind, tile, sequence))
                .takeIf { encode(it).contentEquals(bytes) }
        } catch (_: IllegalArgumentException) { null }
    }
}
