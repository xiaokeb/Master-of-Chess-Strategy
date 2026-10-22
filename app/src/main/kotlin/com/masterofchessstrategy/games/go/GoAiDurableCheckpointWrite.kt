package com.masterofchessstrategy.games.go

import kotlinx.coroutines.CancellationException
import java.io.IOException

internal class GoAiCheckpointWriteUncertainException(cause: Exception) :
    IOException("围棋存档写入结果无法确认，请返回并重新打开；旧页面已停用。", cause)

/** Resolve a lost write acknowledgement by exact reread, never by assuming an exception rolled back. */
internal suspend fun saveGoAiCheckpoint(repository: GoAiMatchRepository, previous: GoAiSavedMatch?, next: GoAiSavedMatch) {
    val nextBytes = GoAiMatchFileCodec.encode(next)
    val previousBytes = previous?.let(GoAiMatchFileCodec::encode)
    try {
        repository.save(next)
    } catch (failure: Exception) {
        if (failure is CancellationException) throw failure
        val actual = try { repository.load()?.let(GoAiMatchFileCodec::encode) }
        catch (readFailure: Exception) {
            throw GoAiCheckpointWriteUncertainException(failure).also { it.addSuppressed(readFailure) }
        }
        if (actual != null && actual.contentEquals(nextBytes)) return
        if (actual == null && previousBytes == null ||
            actual != null && previousBytes != null && actual.contentEquals(previousBytes)) throw failure
        throw GoAiCheckpointWriteUncertainException(failure)
    }
}
