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
    val encodedSize = HEADER_BYTES + 2 * hands.sumOf { 1 + it.size }

    init {
        require(config.botStrategy == CompetitionMahjongBotStrategy.POSITION_SEEDED_V1)
        require(hands.size == 16 && hands.all { hand ->
            hand.size <= MAX_ACTIONS && hand.all { action ->
                val bits = action.toInt() and 0xffff
                (bits and 15) <= 8 && ((bits ushr 4) and 63) <= 33 && (bits ushr 10) <= 33
            }
        }) { "国标跨场证据动作无效" }
    }

    fun decisions(handIndex: Int): ShortArray = hands[handIndex].copyOf()

    fun writeTo(output: ByteBuffer) {
        val id = UUID.fromString(config.matchId)
        output.putLong(id.mostSignificantBits).putLong(id.leastSignificantBits)
            .putLong(config.seed).put(config.botLevel.toByte())
        hands.forEach { hand ->
            output.putShort(hand.size.toShort())
            hand.forEach { output.putShort(it) }
        }
    }

    companion object {
        private const val HEADER_BYTES = 25
        const val MAX_ACTIONS = 4096
        const val MIN_BYTES = HEADER_BYTES + 2 * 16

        fun read(input: ByteBuffer, viewer: Int): CompetitionMahjongArchivedSeries? {
            if (input.remaining() < MIN_BYTES) return null
            val id = UUID(input.long, input.long).toString()
            val config = CompetitionMahjongCompetitionConfig(id, input.long, viewer, input.get().toInt(),
                CompetitionMahjongBotStrategy.POSITION_SEEDED_V1)
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
