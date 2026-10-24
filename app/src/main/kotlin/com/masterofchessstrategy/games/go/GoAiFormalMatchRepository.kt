package com.masterofchessstrategy.games.go

/** Explicit production workflow; ordinary load/save must never upgrade an existing match.
 * Creation atomically binds the initial checkpoint and its local participation fact.
 */
interface GoAiFormalMatchRepository : GoAiMatchRepository {
    suspend fun createStandardMatch(match: GoAiSavedMatch)

    /** Explicitly confirmed restart; compare the entire previous checkpoint before replacement. */
    suspend fun restartStandardMatch(match: GoAiSavedMatch, expectedActive: GoAiSavedMatch) = createStandardMatch(match)

    /** Used only after a lost creation acknowledgement; exact checkpoint equality alone is insufficient. */
    suspend fun confirmsStandardCreation(match: GoAiSavedMatch): Boolean
}
