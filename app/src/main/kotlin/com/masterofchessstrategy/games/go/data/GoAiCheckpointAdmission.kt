package com.masterofchessstrategy.games.go.data

import com.masterofchessstrategy.engine.*
import com.masterofchessstrategy.games.go.*
import java.security.MessageDigest

/** Legal-history facts only. Neither CRC nor native restoration authenticates ranked participation. */
internal class GoAdmittedAiCheckpoint internal constructor(
    private val owned: GoAiSavedMatch,
    val phase: GoPhase,
    val result: GameResult,
    val moveCount: Int,
    val eventCount: Int,
    val openingEventCount: Int,
) {
    // A separate domain prevents collisions with local-session/undo-branch record IDs.
    val recordId: String = MessageDigest.getInstance("SHA-256").digest(
        "MasterofChessStrategy:go-ai-record:v1\u0000${owned.matchId}".toByteArray(Charsets.UTF_8),
    ).joinToString("") { "%02x".format(it.toInt() and 255) }

    // GoAiSavedMatch is immutable and exposes copies of both native byte arrays.
    fun match(): GoAiSavedMatch = owned
}

/** Worker-only. Opens disposable rule engines; never searches, changes storage or grants rewards. */
internal fun admitGoAiCheckpoint(
    match: GoAiSavedMatch,
    engineFactory: (Int, Int, Int) -> GoRecordEngine = { size, komi, handicap -> NativeGoEngine(size, komi, handicap) },
): GoAdmittedAiCheckpoint {
    // Validate strict Unicode, flags, limits and all GAI fields before opening a native owner.
    GoAiMatchFileCodec.encode(match)
    val config = match.config
    val opening = match.copyOpeningBytes()?.let { GoAiOpening.fromSnapshot(it, engineFactory) }
    require(opening == null || opening.config(config.humanPlayer, config.difficulty) == config) {
        "围棋人机开局与存档配置不符"
    }
    val expectedKomi = if (config.handicapStones == 0) 15 else 0
    return engineFactory(config.boardSize, expectedKomi, config.handicapStones).use { candidate ->
        require(candidate.restore(match.copyBytes()) == RestoreResult.Restored) { "围棋人机历史无效" }
        require(candidate.gameType == GameType.GO && candidate.boardSize == config.boardSize &&
            candidate.handicapStones == config.handicapStones && candidate.blackKomiQuarters == expectedKomi) {
            "围棋人机历史与棋盘、让子或贴子不符"
        }
        opening?.checkContinuation(candidate)
        val phase = candidate.phase
        val result = candidate.gameResult()
        val finalScore = candidate.finalScore()
        require((phase == GoPhase.FINISHED) == (result != GameResult.ONGOING) &&
            (phase == GoPhase.FINISHED) == (finalScore != null)) { "围棋人机终局状态与双方确认不符" }
        require(finalScore == null || finalScore.result == result) { "围棋人机最终分数与胜负不符" }
        require(!match.completionReserved || phase == GoPhase.FINISHED) { "未终结的围棋人机局不能固定完成结果" }
        val history = candidate.eventHistory()
        require(history.size <= 16_386)
        val canonical = GoAiSavedMatch(match.matchId, config, candidate.serialize(), match.undoUsed, match.hintUsed,
            match.completionReserved, match.rememberedHuman, match.automaticUsed, opening?.copyBytes(),
            match.autoContinueEnabled, match.autoContinueMaxGames, match.autoContinueGameNumber)
        GoAiMatchFileCodec.encode(canonical)
        GoAdmittedAiCheckpoint(canonical, phase, result,
            history.count { it is GoAction.Place || it == GoAction.Pass }, history.size, opening?.eventCount ?: 0)
    }
}
