package com.masterofchessstrategy.games.lanzhou_fangqi

import kotlinx.coroutines.flow.StateFlow

/** One gate/Room snapshot; a menu read never creates participation or settles a game. */
internal data class FangqiAiEntryFacts(
    val eligibility: FangqiAiEligibility,
    val active: FangqiStoredSession?,
    val formalActive: Boolean,
    val settledActive: Boolean,
) {
    init {
        require(active != null || (!formalActive && !settledActive))
        require(!settledActive || formalActive)
    }
}

internal data class FangqiAiEntryLaunch(val sessionId: String, val formal: Boolean)

internal interface FangqiAiEntryRepository {
    val ownerEpoch: Long
    val epoch: StateFlow<Long>
    suspend fun loadFacts(): FangqiAiEntryFacts
    suspend fun create(creation: FangqiRankedCreation, expectedPrevious: FangqiStoredSession? = null): FangqiStoredSession
}
