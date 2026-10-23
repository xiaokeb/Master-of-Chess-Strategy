package com.masterofchessstrategy.games.go.data

import com.masterofchessstrategy.data.MatchOutcome
import com.masterofchessstrategy.data.StoredGameMode
import com.masterofchessstrategy.engine.*
import com.masterofchessstrategy.games.go.*
import java.util.UUID

/** Local creation fact, not authentication, an imported-history upgrade or a reward receipt. */
internal class GoRankedStart(
    val recordId: String,
    canonicalInitialGai: ByteArray,
    val createdAtEpochMillis: Long,
    val version: Int = 1,
) {
    private val initial = canonicalInitialGai.copyOf()
    init {
        require(version == 1 && recordId.matches(Regex("[0-9a-f]{64}")) && createdAtEpochMillis >= 0)
    }
    fun copyBytes(): ByteArray = initial.copyOf()
}

/** Worker-only; the caller must atomically persist this fact with its newly created checkpoint. */
internal fun admitGoRankedStart(
    match: GoAiSavedMatch,
    time: Long,
    engineFactory: (Int, Int, Int) -> GoRecordEngine = { size, komi, handicap -> NativeGoEngine(size, komi, handicap) },
): GoRankedStart {
    require(time >= 0)
    require(runCatching { UUID.fromString(match.matchId).toString() }.getOrNull() == match.matchId) {
        "Ranked Go creation requires a canonical UUID"
    }
    val bytes = GoAiMatchFileCodec.encode(match)
    val admitted = admitGoAiCheckpoint(match, engineFactory)
    require(GoAiMatchFileCodec.encode(admitted.match()).contentEquals(bytes)) { "Noncanonical Go creation history" }
    require(admitted.phase == GoPhase.PLAYING && admitted.result == GameResult.ONGOING && admitted.eventCount == 0 &&
        match.config.handicapStones == 0 && match.config.humanPlayer != null && !match.hasSpecifiedOpening &&
        !match.automaticUsed && match.undoUsed == 0 && match.hintUsed == 0 && !match.completionReserved &&
        !match.autoContinueEnabled && match.autoContinueMaxGames == 1 && match.autoContinueGameNumber == 1) {
        "Ranked Go creation must be a fresh standard human game"
    }
    return GoRankedStart(admitted.recordId, bytes, time)
}

/** No storage or reward effects. Nonstandard completions remain ordinary lossless records. */
internal fun qualifiedGoOutcome(
    start: GoRankedStart,
    firstRawArchive: GoAiCheckpointArchive,
    engineFactory: (Int, Int, Int) -> GoRecordEngine = { size, komi, handicap -> NativeGoEngine(size, komi, handicap) },
): MatchOutcome? {
    val initial = GoAiBackupWire.canonical(start.copyBytes())
    val checkedStart = admitGoRankedStart(initial, start.createdAtEpochMillis, engineFactory)
    require(start.version == 1 && checkedStart.recordId == start.recordId &&
        checkedStart.copyBytes().contentEquals(start.copyBytes())) { "Conflicting Go creation identity" }
    val archive = firstRawArchive
    val final = GoAiBackupWire.canonical(archive.checkpoint.copyBytes())
    val admitted = admitGoAiCheckpoint(final, engineFactory)
    require(GoAiMatchFileCodec.encode(admitted.match()).contentEquals(archive.checkpoint.copyBytes())) {
        "Noncanonical Go completion history"
    }
    require(archive.recordId == start.recordId && admitted.recordId == start.recordId && final.matchId == initial.matchId) {
        "Conflicting Go completion identity"
    }
    // Local clock rollback is not corruption: keep the record but do not settle growth.
    if (archive.completedAtEpochMillis < start.createdAtEpochMillis ||
        archive.checkpoint.lineage.origin != GoAiCheckpointOrigin.UNVERIFIED_SESSION ||
        archive.checkpoint.lineage.replacedTerminalRecordId != null || final.config != initial.config ||
        final.rememberedHuman != initial.rememberedHuman || final.automaticUsed || final.hasSpecifiedOpening ||
        final.autoContinueEnabled || final.autoContinueMaxGames != 1 || final.autoContinueGameNumber != 1 ||
        admitted.phase != GoPhase.FINISHED || admitted.result == GameResult.ONGOING) return null
    // Existing GAI construction/admission enforces each difficulty's allowed assistance quota.
    return MatchOutcome(start.recordId, GameType.GO, StoredGameMode.HUMAN_VS_AI,
        when (final.config.difficulty) {
            GoAiDifficulty.SIMPLE -> Difficulty.EASY
            GoAiDifficulty.MEDIUM -> Difficulty.MEDIUM
            GoAiDifficulty.HARD -> Difficulty.HARD
            GoAiDifficulty.MASTER -> Difficulty.MASTER
        }, if (final.config.humanPlayer == GoPlayer.WHITE) 1 else 0, admitted.result,
        archive.completedAtEpochMillis)
}
