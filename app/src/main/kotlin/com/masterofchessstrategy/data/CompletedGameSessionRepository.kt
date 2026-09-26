package com.masterofchessstrategy.data

import androidx.room.withTransaction
import com.masterofchessstrategy.endgame.ChineseChessEndgamePack
import com.masterofchessstrategy.engine.GameResult

/** One immutable terminal checkpoint; all rewards must agree with this exact game. */
internal data class CompletedGameSession(
    val snapshot: GameSessionSnapshot,
    val record: GameRecord,
    val outcome: MatchOutcome?,
) {
    init {
        require(snapshot.sessionId == record.recordId && snapshot.gameType == record.gameType)
        require(snapshot.mode == record.mode && snapshot.difficulty == record.difficulty)
        require(snapshot.playerIndex == record.playerIndex && snapshot.acceptedMoveCount == record.moveCount)
        require(snapshot.engineState.contentEquals(record.engineState))
        require(snapshot.resultOverride == record.result && snapshot.turnStartedAtEpochMillis == null)
        require((outcome != null) == (snapshot.mode == StoredGameMode.HUMAN_VS_AI))
        outcome?.let {
            require(it.matchId == snapshot.sessionId && it.gameType == snapshot.gameType)
            require(it.mode == snapshot.mode && it.difficulty == snapshot.difficulty)
            require(it.playerIndex == snapshot.playerIndex && it.result == record.result)
        }
    }
}

internal data class CompletedGameCommitResult(val endgame: CompleteEndgameResult? = null)

internal interface CompletedGameSessionRepository : GameSessionRepository {
    suspend fun complete(game: CompletedGameSession): CompletedGameCommitResult
}

/** Includes session, immutable record, ranked ledger and optional endgame reward in one transaction. */
internal class RoomCompletedGameSessionRepository(
    private val database: MocsDatabase,
    private val pack: ChineseChessEndgamePack,
    private val shouldFavorite: (GameRecord) -> Boolean = { false },
) : CompletedGameSessionRepository,
    GameSessionRepository by RoomGameSessionRepository(database.activeGameDao()) {
    override suspend fun complete(game: CompletedGameSession): CompletedGameCommitResult {
        val record = game.record.copy(isFavorite = game.record.isFavorite || shouldFavorite(game.record)).toGameRecordEntity()
        val outcome = game.outcome?.toMatchOutcomeEntity()
        return database.withTransaction {
            val existing = database.gameRecordDao().find(record.recordId)
            // Reopening may have a new wall-clock time, but cannot rewrite a different result or history.
            require(existing == null || record.copy(isFavorite = existing.isFavorite,
                completedAtEpochMillis = existing.completedAtEpochMillis) == existing) { "Conflicting completed record" }
            database.gameRecordDao().insert(record)
            if (outcome != null) {
                val prior = database.matchOutcomeDao().find(outcome.matchId)
                require(prior == null || outcome.copy(settledAtEpochMillis = prior.settledAtEpochMillis) == prior) {
                    "Conflicting ranked outcome"
                }
                database.matchOutcomeDao().insert(outcome)
            }
            val reward = if (game.snapshot.mode == StoredGameMode.ENDGAME && game.record.result == GameResult.FIRST_PLAYER_WIN) {
                val level = pack.levels.singleOrNull { pack.sessionVariantId(it.id) == game.snapshot.sessionVariantId }
                    ?: error("Unknown endgame completion")
                RoomEndgameProgressRepository(database.endgameProgressDao(), pack).complete(
                    level, (record.moveCount + 1) / 2, record.completedAtEpochMillis,
                )
            } else null
            RoomGameSessionRepository(database.activeGameDao()).save(game.snapshot)
            CompletedGameCommitResult(reward)
        }
    }
}
