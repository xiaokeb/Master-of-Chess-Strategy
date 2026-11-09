package com.masterofchessstrategy.games.competition_mahjong.data

import com.masterofchessstrategy.data.MatchOutcomeEntity
import com.masterofchessstrategy.data.MocsDatabase
import com.masterofchessstrategy.data.toMatchOutcomeEntity
import com.masterofchessstrategy.games.competition_mahjong.learning.CompetitionMahjongLearningProofRead
import com.masterofchessstrategy.games.competition_mahjong.learning.RoomCompetitionMahjongLearningProofStore
import com.masterofchessstrategy.games.competition_mahjong.match.policy.CompetitionMahjongPlayPolicy
import com.masterofchessstrategy.games.competition_mahjong.match.policy.CompetitionMahjongUnlockProgress
import com.masterofchessstrategy.games.competition_mahjong.match.policy.competitionMahjongProductionStrategies
import com.masterofchessstrategy.games.competition_mahjong.match.storage.CompetitionMahjongFormalSnapshotCodec
import com.masterofchessstrategy.games.competition_mahjong.match.storage.qualifiedGrowth
import com.masterofchessstrategy.games.competition_mahjong.match.storage.room.CompetitionMahjongFormalRoomStorage
import java.io.IOException

/** Uses only formal rows committed by the live writer or admitted by backup restore. */
internal suspend fun MocsDatabase.qualifiedCompetitionMahjongRowsInTransaction(): List<MatchOutcomeEntity> {
    check(inTransaction())
    val dao = competitionMahjongFormalRoomDao()
    val snapshots = CompetitionMahjongFormalBackupBundle.requireStoredViewers(dao).mapNotNull { viewer ->
        if (dao.find(viewer)?.mctxSize == null) return@mapNotNull null
        val bytes = checkNotNull(CompetitionMahjongFormalRoomStorage.readMctxInTransaction(this, dao, viewer))
        val snapshot = CompetitionMahjongFormalSnapshotCodec.decode(bytes, viewer)
            ?: throw IOException("国标麻将成长存档损坏或版本不兼容")
        snapshot.takeIf { it.archivedSeries.isNotEmpty() }
    }
    if (snapshots.isEmpty()) return emptyList()
    val tutorial = when (val read = RoomCompetitionMahjongLearningProofStore(this, competitionMahjongLearningProofDao()).load()) {
        is CompetitionMahjongLearningProofRead.Ready -> read.proof.isCompleted
        CompetitionMahjongLearningProofRead.Incompatible -> throw IOException("国标麻将教程证明不兼容")
    }
    val base = CompetitionMahjongPlayPolicy.fromProgress(
        CompetitionMahjongUnlockProgress(tutorial), competitionMahjongProductionStrategies)
    return snapshots.flatMap { snapshot -> snapshot.qualifiedGrowth(base).outcomes.map { it.toMatchOutcomeEntity() } }
}
