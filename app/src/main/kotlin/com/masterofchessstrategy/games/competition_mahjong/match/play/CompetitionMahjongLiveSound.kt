package com.masterofchessstrategy.games.competition_mahjong.match.play

import com.masterofchessstrategy.engine.CompetitionMahjongAction
import com.masterofchessstrategy.engine.CompetitionMahjongPublicView
import com.masterofchessstrategy.engine.CompetitionMahjongRoundActionKind
import com.masterofchessstrategy.engine.CompetitionMahjongRoundPhase
import com.masterofchessstrategy.game.ChineseChessSoundCue

/** Derives one short cue from an accepted action or a saved batch of public bot actions. */
internal fun competitionMahjongLiveSound(
    before: CompetitionMahjongPublicView,
    after: CompetitionMahjongPublicView,
    action: CompetitionMahjongAction? = null,
    falseWin: Boolean = false,
): ChineseChessSoundCue? {
    if (before.phase != after.phase) {
        when (after.phase) {
            CompetitionMahjongRoundPhase.WON -> return if (after.winnerSeat == after.viewerSeat) {
                ChineseChessSoundCue.VICTORY
            } else ChineseChessSoundCue.DEFEAT
            CompetitionMahjongRoundPhase.EXHAUSTIVE_DRAW -> return ChineseChessSoundCue.DRAW
            else -> Unit
        }
    }
    if (falseWin) return ChineseChessSoundCue.DEFEAT
    if (after.seats.zip(before.seats).any { (current, previous) ->
            current.melds.size > previous.melds.size ||
                current.melds.zip(previous.melds).any { (new, old) -> new.kind != old.kind }
        }) return ChineseChessSoundCue.CAPTURE
    if (action?.kind == CompetitionMahjongRoundActionKind.DISCARD ||
        after.seats.zip(before.seats).any { (current, previous) -> current.discards != previous.discards }) {
        return ChineseChessSoundCue.MOVE
    }
    return null
}
