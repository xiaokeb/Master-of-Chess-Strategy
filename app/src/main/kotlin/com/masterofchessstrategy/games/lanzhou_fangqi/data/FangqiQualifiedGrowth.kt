package com.masterofchessstrategy.games.lanzhou_fangqi.data

import androidx.room.withTransaction
import com.masterofchessstrategy.data.MatchOutcomeEntity
import com.masterofchessstrategy.data.MocsDatabase
import com.masterofchessstrategy.engine.GameType
import com.masterofchessstrategy.games.go.data.qualifiedMatchOutcomeRows
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** One read transaction; Native admission runs off main and never repairs or settles data. */
internal suspend fun MocsDatabase.qualifiedPlayerGrowthRows(): List<MatchOutcomeEntity> =
    withContext(Dispatchers.IO) {
        withTransaction {
            val outcomes = qualifiedMatchOutcomeRows()
            val ranked = fangqiRankedDao()
            val snapshot = FangqiRankedStorageSnapshot(ranked.readStarts(), ranked.readSettlements(),
                activeGameDao().find(GameType.LANZHOU_SQUARE_CHESS.code), outcomes,
                gameRecordDao().listAll())
            qualifiedFangqiGrowthRows(snapshot)
        }
    }

/** The same validator used by creation and complete backups supplies the only accepted IDs. */
internal fun qualifiedFangqiGrowthRows(
    snapshot: FangqiRankedStorageSnapshot,
    validator: FangqiRankedSnapshotValidator = FangqiRankedSnapshotValidator(),
): List<MatchOutcomeEntity> {
    val verified = validator.validate(snapshot)
    return snapshot.outcomes.filter { it.gameTypeCode != GameType.LANZHOU_SQUARE_CHESS.code ||
        it.matchId in verified.settlements }
}
