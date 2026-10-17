package com.masterofchessstrategy.games.go

data class GoSavedSession(
    val boardSize: Int,
    val engineState: ByteArray,
    val displayMode: GoScoreDisplayMode,
    val lastMoveIndex: Int? = null,
    val identity: GoSessionIdentity = GoSessionIdentity.create(),
)

/** The engine validates rule bytes after the repository validates its envelope. */
interface GoSessionRepository {
    suspend fun load(): GoSavedSession?
    suspend fun save(session: GoSavedSession)
    suspend fun clear()
}
