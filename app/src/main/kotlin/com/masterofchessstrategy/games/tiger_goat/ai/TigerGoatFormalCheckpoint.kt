package com.masterofchessstrategy.games.tiger_goat.ai

import com.masterofchessstrategy.engine.Difficulty

/** Initial formal entry supports easy, standard play; higher levels need qualification evidence. */
internal data class TigerGoatFormalConfig(
    val humanSide: Int,
    val difficulty: Difficulty = Difficulty.EASY,
) {
    init {
        require(humanSide == 1 || humanSide == 2)
        require(difficulty == Difficulty.EASY)
    }
}

/** Versioned mode metadata surrounds the complete, independently validated MOCT history. */
internal class TigerGoatFormalCheckpoint(
    val sessionId: String,
    val revision: Long,
    val config: TigerGoatFormalConfig,
    val actionCount: Int,
    val updatedAt: Long,
    bytes: ByteArray,
    val version: Int = 1,
) {
    private val history = bytes.copyOf()

    init {
        require(version == 1)
        require(sessionId.isNotBlank() && sessionId.length <= 64)
        require(revision >= 0 && updatedAt >= 0 && actionCount in 0..65_535)
        require(history.isNotEmpty())
    }

    fun copyBytes(): ByteArray = history.copyOf()
    fun copyOwned() = TigerGoatFormalCheckpoint(
        sessionId, revision, config, actionCount, updatedAt, history, version,
    )
}
