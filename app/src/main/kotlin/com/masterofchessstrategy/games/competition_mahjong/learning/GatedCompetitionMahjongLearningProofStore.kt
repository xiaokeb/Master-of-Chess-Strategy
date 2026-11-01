package com.masterofchessstrategy.games.competition_mahjong.learning

import kotlinx.coroutines.flow.StateFlow

/** One immutable owner per learning page; retries never renew a replaced database identity. */
internal class GatedCompetitionMahjongLearningProofStore(
    private val delegate: CompetitionMahjongLearningProofStore,
    private val gate: CompetitionMahjongStorageEpochGate,
    private val owner: CompetitionMahjongStorageEpochGate.Owner = gate.captureOwner(),
) : CompetitionMahjongLearningProofStore {
    val ownerEpoch: Long get() = owner.epoch
    val epoch: StateFlow<Long> get() = gate.epoch

    override suspend fun load(): CompetitionMahjongLearningProofRead = gate.access(owner) { delegate.load() }

    override suspend fun confirm(request: CompetitionMahjongLearningProofRequest): CompetitionMahjongLearningProof =
        gate.access(owner) { delegate.confirm(request) }
}
