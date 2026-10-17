package com.masterofchessstrategy.games.competition_mahjong.match

import com.masterofchessstrategy.engine.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32

internal fun matchConfig(viewer: Int = 0) = CompetitionMahjongCompetitionConfig(
    "80000000-0000-4000-8000-000000000001", 73, viewer, 2,
)

internal fun matchFrame(config: CompetitionMahjongCompetitionConfig, index: Int, terminal: Boolean = true,
    draw: Boolean = false, winnerPlayer: Int = 0): CompetitionMahjongPublicView {
    val hand = config.handConfig(index)
    val seating = CompetitionMahjongMatchSeating.forHand(index)
    val winner = seating.seatOfPlayer(winnerPlayer)
    val payer = (winner + 1) % 4
    val scores = MutableList(4) { -8 }
    scores[winner] = 32
    scores[payer] = -16
    return CompetitionMahjongPublicView(
        hand.viewerSeat,
        if (!terminal) CompetitionMahjongRoundPhase.DISCARD else if (draw) CompetitionMahjongRoundPhase.EXHAUSTIVE_DRAW else CompetitionMahjongRoundPhase.WON,
        hand.dealerSeat, hand.roundWind, hand.dealerSeat, if (terminal) 0 else 90,
        null, null, if (terminal && !draw) winner else null, if (terminal && !draw) payer else null,
        (hand.dealerSeat + 1) % 4, if (terminal && !draw) scores else listOf(0, 0, 0, 0),
        CompetitionMahjongFanView(0, terminal && !draw, if (draw || !terminal) 0 else 8,
            if (draw || !terminal) 0 else 8, terminal && !draw,
            if (draw || !terminal) emptyList() else listOf(CompetitionMahjongFanEntry(48, "双暗杠", 8, 1))),
        (0..3).map { CompetitionMahjongSeatView(it, 13, false, emptyList(), emptyList(), emptyList(), emptyList()) },
    )
}

/** Envelope fixture only; it does not pretend to be a valid Native MOCM replay. */
internal fun matchRecord(viewerSeat: Int, terminal: Boolean = false): ByteArray {
    val bytes = ByteBuffer.allocate(17).order(ByteOrder.LITTLE_ENDIAN)
    bytes.putInt(0x534d434d).put(1.toByte()).put(viewerSeat.toByte()).putShort(0).putInt(1)
    bytes.put(if (terminal) 1.toByte() else 0.toByte())
    bytes.putInt(CRC32().apply { update(bytes.array(), 0, 13) }.value.toInt())
    return bytes.array()
}

internal fun matchLedger(config: CompetitionMahjongCompetitionConfig, count: Int, draw: Boolean = false): CompetitionMahjongMatchLedger =
    (0 until count).fold(CompetitionMahjongMatchLedger.empty()) { ledger, index ->
        ledger.recordTerminal(config, index, matchFrame(config, index, draw = draw))
    }
