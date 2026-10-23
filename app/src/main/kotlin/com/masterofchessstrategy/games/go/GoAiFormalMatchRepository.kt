package com.masterofchessstrategy.games.go

/** Explicit production workflow; ordinary load/save must never upgrade an existing match.
 * Creation atomically binds the initial checkpoint and its local participation fact.
 */
interface GoAiFormalMatchRepository : GoAiMatchRepository {
    suspend fun createStandardMatch(match: GoAiSavedMatch)

    /** Used only after a lost creation acknowledgement; exact checkpoint equality alone is insufficient. */
    suspend fun confirmsStandardCreation(match: GoAiSavedMatch): Boolean
}
