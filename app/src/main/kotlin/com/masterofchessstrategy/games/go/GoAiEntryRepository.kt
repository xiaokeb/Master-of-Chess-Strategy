package com.masterofchessstrategy.games.go

import com.masterofchessstrategy.engine.GoPhase

/** One consistent read for setup. Saved matches and eligibility expose no mutable byte arrays. */
internal data class GoAiEntrySnapshot(
    val active: GoAiSavedMatch?,
    val phase: GoPhase?,
    val completionStored: Boolean,
    val eligibility: GoDifficultyEligibility,
) {
    init {
        require((active == null) == (phase == null))
        require(!completionStored || phase == GoPhase.FINISHED && active?.completionReserved == true)
    }
}

/** New-game acknowledgement precedes navigation; a failed acknowledgement is followed by reload,
 * never an automatic retry with another identity. Null expectedActive means "still no active game".
 */
internal interface GoAiEntryRepository {
    val isCurrent: Boolean
    suspend fun load(): GoAiEntrySnapshot
    suspend fun create(
        config: GoAiMatchConfig,
        expectedActive: GoAiSavedMatch?,
        abandonOngoing: Boolean = false,
    ): GoAiSavedMatch
}
