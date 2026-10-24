package com.masterofchessstrategy.games.go

import java.io.IOException

/** A production play route may resume or explicitly restart, never invent a default on load. */
internal class GoAiExistingMatchRepository(
    private val delegate: GoAiFormalMatchRepository,
    private val expectedMatchId: String,
) : GoAiFormalMatchRepository {
    private var firstLoad = true

    override suspend fun load(): GoAiSavedMatch {
        val saved = delegate.load() ?: throw IOException("围棋活动对局已变更，请返回设置重新选择")
        if (firstLoad && saved.matchId != expectedMatchId) {
            throw IOException("围棋活动对局已变更，请返回设置重新选择")
        }
        firstLoad = false
        return saved
    }

    override suspend fun save(match: GoAiSavedMatch) = delegate.save(match)
    override suspend fun createStandardMatch(match: GoAiSavedMatch) = delegate.createStandardMatch(match)
    override suspend fun restartStandardMatch(match: GoAiSavedMatch, expectedActive: GoAiSavedMatch) =
        delegate.restartStandardMatch(match, expectedActive)
    override suspend fun confirmsStandardCreation(match: GoAiSavedMatch) = delegate.confirmsStandardCreation(match)
}
