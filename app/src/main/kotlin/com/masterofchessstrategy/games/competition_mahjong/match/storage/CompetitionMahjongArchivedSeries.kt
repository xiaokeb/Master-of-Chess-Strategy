package com.masterofchessstrategy.games.competition_mahjong.match.storage

import com.masterofchessstrategy.games.competition_mahjong.match.CompetitionMahjongBotStrategy
import com.masterofchessstrategy.games.competition_mahjong.match.CompetitionMahjongCompetitionConfig
import java.nio.ByteBuffer
import java.util.UUID

/** Private archive. Native regenerates the wall and AI actions from the config and player decisions. */
internal class CompetitionMahjongArchivedSeries(
    val config: CompetitionMahjongCompetitionConfig,
    decisions: List<ShortArray>,
) {
    private val hands = decisions.map(ShortArray::copyOf)
    private val encodedSize = HEADER_BYTES + 2 * hands.sumOf { 1 + it.size }

    init {
        require(config.botStrategy.positionSeeded)
        require(hands.size == 16 && hands.all { hand ->
            hand.size <= MAX_ACTIONS && hand.all { action ->
                val bits = action.toInt() and 0xffff
                (bits and 15) <= 8 && ((bits ushr 4) and 63) <= 33 && (bits ushr 10) <= 33
            }
        }) { "国标跨场证据动作无效" }
    }

    fun decisions(handIndex: Int): ShortArray = hands[handIndex].copyOf()

    fun encodedSize(versioned: Boolean): Int = encodedSize + if (versioned) 1 else 0

    fun writeTo(output: ByteBuffer, versioned: Boolean) {
        val id = UUID.fromString(config.matchId)
        output.putLong(id.mostSignificantBits).putLong(id.leastSignificantBits)
            .putLong(config.seed).put(config.botLevel.toByte())
        if (versioned) output.put(when (config.botStrategy) {
            CompetitionMahjongBotStrategy.POSITION_SEEDED_V1 -> 1.toByte()
            CompetitionMahjongBotStrategy.POSITION_SEEDED_V2 -> 2.toByte()
            CompetitionMahjongBotStrategy.POSITION_SEEDED_WMO -> 3.toByte()
            CompetitionMahjongBotStrategy.POSITION_SEEDED_WMO_KONGS -> 4.toByte()
            CompetitionMahjongBotStrategy.LEGACY_BATCH -> error("Archive requires a position-seeded strategy")
        })
        hands.forEach { hand ->
            output.putShort(hand.size.toShort())
            hand.forEach { output.putShort(it) }
        }
    }

    companion object {
        private const val HEADER_BYTES = 25
        const val MAX_ACTIONS = 4096
        private const val MIN_BYTES = HEADER_BYTES + 2 * 16

        fun minimumBytes(versioned: Boolean): Int = MIN_BYTES + if (versioned) 1 else 0

        fun read(input: ByteBuffer, viewer: Int, versioned: Boolean): CompetitionMahjongArchivedSeries? {
            if (input.remaining() < minimumBytes(versioned)) return null
            val id = UUID(input.long, input.long).toString()
            val seed = input.long
            val level = input.get().toInt()
            val strategy = when (if (versioned) input.get().toInt() else 1) {
                1 -> CompetitionMahjongBotStrategy.POSITION_SEEDED_V1
                2 -> CompetitionMahjongBotStrategy.POSITION_SEEDED_V2
                3 -> CompetitionMahjongBotStrategy.POSITION_SEEDED_WMO
                4 -> CompetitionMahjongBotStrategy.POSITION_SEEDED_WMO_KONGS
                else -> return null
            }
            val config = CompetitionMahjongCompetitionConfig(id, seed, viewer, level, strategy)
            val hands = List(16) {
                if (input.remaining() < 2) return null
                val count = input.short.toInt()
                if (count !in 0..MAX_ACTIONS || count > input.remaining() / 2) return null
                ShortArray(count) { input.short }
            }
            return CompetitionMahjongArchivedSeries(config, hands)
        }
    }
}
