package com.masterofchessstrategy.games.go.data

import com.masterofchessstrategy.data.GameRecord
import com.masterofchessstrategy.data.StoredGameMode
import com.masterofchessstrategy.engine.*
import com.masterofchessstrategy.games.go.*

/** Worker-only lossless projection; no storage, participation authentication or reward effects. */
internal fun projectGoAiRecord(
    archive: GoAiCheckpointArchive,
    engineFactory: (Int, Int, Int) -> GoRecordEngine = { size, komi, handicap -> NativeGoEngine(size, komi, handicap) },
): GameRecord {
    val bytes = archive.checkpoint.copyBytes()
    val original = GoAiBackupWire.canonical(bytes)
    // Admission is performed here, not supplied as independently forgeable metadata.
    val admitted = admitGoAiCheckpoint(original, engineFactory)
    return projectAdmittedGoAiRecord(archive, admitted)
}

/** Prepared backup admissions may reuse their per-operation Native result; never a rank proof. */
internal fun projectAdmittedGoAiRecord(archive: GoAiCheckpointArchive, admitted: GoAdmittedAiCheckpoint): GameRecord {
    val bytes = archive.checkpoint.copyBytes()
    require(GoAiMatchFileCodec.encode(admitted.match()).contentEquals(bytes)) {
        "Go AI archive differs from its admitted canonical history"
    }
    require(archive.checkpoint.lineage.replacedTerminalRecordId != archive.recordId &&
        admitted.recordId == archive.recordId && admitted.phase == GoPhase.FINISHED &&
        admitted.result != GameResult.ONGOING) { "Go AI archive identity or terminal state is invalid" }
    val match = admitted.match()
    val mode = when {
        match.config.automatic && match.hasSpecifiedOpening -> StoredGameMode.OPENING_AUTO_PLAY
        match.config.automatic -> StoredGameMode.AI_AUTO_PLAY
        else -> StoredGameMode.HUMAN_VS_AI
    }
    // automaticUsed is a permanent mixed-participation fact, not the current mode. It remains
    // in the authoritative GAI; a mixed human game must never be relabelled as pure automatic.
    return GameRecord(archive.recordId, GameType.GO, mode, when (match.config.difficulty) {
        GoAiDifficulty.SIMPLE -> Difficulty.EASY
        GoAiDifficulty.MEDIUM -> Difficulty.MEDIUM
        GoAiDifficulty.HARD -> Difficulty.HARD
        GoAiDifficulty.MASTER -> Difficulty.MASTER
    }, admitted.result, match.copyBytes(), admitted.moveCount,
        completedAtEpochMillis = archive.completedAtEpochMillis,
        playerIndex = if (match.config.humanPlayer == GoPlayer.WHITE) 1 else 0)
}

/** Idempotency comparison only. Caller retains existing favorite and checks raw provenance. */
internal fun requireSameGoAiRecord(existing: GameRecord, candidate: GameRecord) {
    require(existing.gameType == GameType.GO && candidate.gameType == GameType.GO &&
        existing.recordId == candidate.recordId && existing.mode == candidate.mode &&
        existing.difficulty == candidate.difficulty && existing.result == candidate.result &&
        existing.engineFormatVersion == candidate.engineFormatVersion &&
        existing.engineState.contentEquals(candidate.engineState) && existing.moveCount == candidate.moveCount &&
        existing.isEndgame == candidate.isEndgame && existing.playerIndex == candidate.playerIndex &&
        existing.completedAtEpochMillis == candidate.completedAtEpochMillis) {
        "Conflicting Go AI completed record"
    }
}
