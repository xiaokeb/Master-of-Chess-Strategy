package com.masterofchessstrategy.games.competition_mahjong.match.hint

import com.masterofchessstrategy.engine.*

/** Prefer discards with an eight-fan wait supported by publicly unaccounted copies. */
internal object CompetitionMahjongReadyHintAdvisor : CompetitionMahjongHintAdvisor {
    override val identity = "public-ready-v1"

    override suspend fun recommend(input: CompetitionMahjongHintInput, decisionSeed: Long): CompetitionMahjongAction? {
        val view = input.view
        val own = view.seats.singleOrNull { it.seat == view.viewerSeat }
        val discards = input.actions.filter { it.kind == CompetitionMahjongRoundActionKind.DISCARD }
        if (view.publicFrameVersion != 2 || view.phase != CompetitionMahjongRoundPhase.DISCARD ||
            view.viewerSeat != view.currentSeat || view.wallRemaining <= 0 || own == null || own.winDisabled ||
            own.concealedTiles.any { it !in 0..33 } || discards.isEmpty() ||
            input.actions.any { it.kind == CompetitionMahjongRoundActionKind.WIN }) {
            return CompetitionMahjongPublicShapeHintAdvisor.recommend(input, decisionSeed)
        }
        val ownCounts = IntArray(34)
        own.concealedTiles.forEach { ownCounts[it]++ }
        val known = competitionMahjongKnownCopies(view, ownCounts)
        val assessed = NativeCompetitionMahjongDiscardWaits.evaluate(view, known, discards.map { it.tile }.distinct(), input.scoring)
        val best = assessed.maxWithOrNull(compareBy({ it.availableCopies }, { it.claimableCopies }))
        if (best == null || best.availableCopies == 0) {
            return CompetitionMahjongPublicShapeHintAdvisor.recommend(input, decisionSeed)
        }
        val tied = assessed.filter {
            it.availableCopies == best.availableCopies && it.claimableCopies == best.claimableCopies
        }.mapTo(mutableSetOf()) { it.tile }
        return CompetitionMahjongPublicShapeHintAdvisor.recommend(
            CompetitionMahjongHintInput(view, discards.filter { it.tile in tied }, input.scoring), decisionSeed,
        )
    }
}
