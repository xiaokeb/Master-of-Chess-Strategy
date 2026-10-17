package com.masterofchessstrategy.games.competition_mahjong.match.hint

import com.masterofchessstrategy.engine.*
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.util.Collections

/** Only copied viewer/public information crosses the recommendation boundary. */
class CompetitionMahjongHintInput internal constructor(view: CompetitionMahjongPublicView,
                                                      actions: List<CompetitionMahjongAction>) {
    val view: CompetitionMahjongPublicView = view.copy(
        netScores = frozen(view.netScores), winningTiles = frozen(view.winningTiles),
        winningFan = view.winningFan.copy(entries = frozen(view.winningFan.entries)),
        seats = frozen(view.seats.map { seat -> seat.copy(
            flowers = frozen(seat.flowers), discards = frozen(seat.discards),
            concealedTiles = frozen(if (seat.seat == view.viewerSeat) seat.concealedTiles else emptyList()),
            melds = frozen(seat.melds.map { meld ->
                if (seat.seat != view.viewerSeat && meld.kind == CompetitionMahjongMeldKind.KONG &&
                    meld.fromSeat == seat.seat) meld.copy(tile = null) else meld
            })) }))
    val actions: List<CompetitionMahjongAction> = frozen(actions)
}

/** Root injects a read-only, admitted native/public-information recommender; no default heuristic is substituted. */
interface CompetitionMahjongHintAdvisor {
    val identity: String
    suspend fun recommend(input: CompetitionMahjongHintInput, decisionSeed: Long): CompetitionMahjongAction?
}

data class CompetitionMahjongHintReceipt(
    val handIndex: Int,
    val positionSha256: String,
    val advisorIdentity: String,
    val action: CompetitionMahjongAction,
) {
    init {
        require(handIndex in 0..15)
        require(positionSha256.matches(Regex("[0-9a-f]{64}")))
        require(advisorIdentity.matches(Regex("[A-Za-z0-9_.-]{1,64}")))
    }

    internal fun matches(index: Int, view: CompetitionMahjongPublicView,
                         actions: List<CompetitionMahjongAction>): Boolean =
        handIndex == index && action in actions &&
            positionSha256 == competitionMahjongHintPosition(index, view)
}

/** Versioned public-position identity; private engine records and other concealed tiles are never hashed. */
internal fun competitionMahjongHintPosition(handIndex: Int, view: CompetitionMahjongPublicView): String {
    require(handIndex in 0..15 && view.viewerSeat in 0..3 && view.publicFrameVersion == 2)
    require(view.phase != CompetitionMahjongRoundPhase.WON && view.phase != CompetitionMahjongRoundPhase.EXHAUSTIVE_DRAW)
    val safe = CompetitionMahjongHintInput(view, emptyList()).view
    val bytes = ByteArrayOutputStream()
    DataOutputStream(bytes).use { output ->
        output.writeInt(1)
        for (value in listOf(handIndex, safe.viewerSeat, safe.phase.code, safe.dealerSeat, safe.roundWind,
            safe.currentSeat, safe.wallRemaining, safe.pendingTile ?: -1, safe.pendingSeat ?: -1,
            safe.nextDealer, safe.drawnTile ?: -1)) output.writeInt(value)
        output.writeBoolean(safe.canDeclareWin)
        fun ints(values: List<Int>) { output.writeInt(values.size); values.forEach(output::writeInt) }
        ints(safe.netScores)
        output.writeInt(safe.seats.size)
        safe.seats.sortedBy { it.seat }.forEach { seat ->
            output.writeInt(seat.seat)
            output.writeInt(seat.concealedCount)
            output.writeBoolean(seat.winDisabled)
            ints(seat.concealedTiles.sorted())
            ints(seat.flowers)
            ints(seat.discards)
            output.writeInt(seat.melds.size)
            seat.melds.forEach { meld ->
                for (value in listOf(meld.kind.code, meld.tile ?: -1, meld.fromSeat, meld.claimedPosition)) output.writeInt(value)
                output.writeBoolean(meld.addedKong)
            }
        }
    }
    return MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()).joinToString("") {
        (it.toInt() and 255).toString(16).padStart(2, '0')
    }
}

private fun <T> frozen(values: List<T>): List<T> = Collections.unmodifiableList(values.toList())
