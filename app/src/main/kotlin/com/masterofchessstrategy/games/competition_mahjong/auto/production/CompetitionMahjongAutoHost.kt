package com.masterofchessstrategy.games.competition_mahjong.auto.production

import androidx.room.withTransaction
import com.masterofchessstrategy.data.MocsDatabase
import com.masterofchessstrategy.games.competition_mahjong.auto.storage.RoomCompetitionMahjongAutoStore
import com.masterofchessstrategy.games.competition_mahjong.learning.CompetitionMahjongLearningProofRead
import com.masterofchessstrategy.games.competition_mahjong.learning.CompetitionMahjongStorageEpochGate
import com.masterofchessstrategy.games.competition_mahjong.learning.RoomCompetitionMahjongLearningProofStore
import com.masterofchessstrategy.games.competition_mahjong.match.policy.CompetitionMahjongPlayPolicy
import com.masterofchessstrategy.games.competition_mahjong.match.policy.CompetitionMahjongUnlockProgress
import com.masterofchessstrategy.games.competition_mahjong.match.policy.competitionMahjongProductionStrategies
import com.masterofchessstrategy.games.competition_mahjong.match.storage.CompetitionMahjongFormalSnapshotCodec
import com.masterofchessstrategy.games.competition_mahjong.match.storage.qualifiedGrowth
import com.masterofchessstrategy.games.competition_mahjong.match.storage.room.CompetitionMahjongFormalRoomStorage
import java.io.IOException
import java.util.UUID

/** A destination reads trusted eligibility without claiming the formal human-match writer. */
internal class CompetitionMahjongAutoHost(private val database: MocsDatabase, val viewer: Int) {
    private val gate = CompetitionMahjongStorageEpochGate.forDatabase(database)
    private val owner = gate.captureOwner()
    val key: String = UUID.randomUUID().toString()
    val store = RoomCompetitionMahjongAutoStore(database, viewer, owner)

    fun isCurrent(): Boolean = gate.isCurrent(owner)

    suspend fun loadPolicy(): CompetitionMahjongPlayPolicy = gate.access(owner) {
        database.withTransaction {
            val learning = RoomCompetitionMahjongLearningProofStore(database, database.competitionMahjongLearningProofDao())
            val completed = when (val proof = learning.load()) {
                is CompetitionMahjongLearningProofRead.Ready -> proof.proof.isCompleted
                CompetitionMahjongLearningProofRead.Incompatible -> throw IOException("国标麻将教程证明不兼容，原数据已保留")
            }
            val base = CompetitionMahjongPlayPolicy.fromProgress(
                CompetitionMahjongUnlockProgress(tutorialCompleted = completed), competitionMahjongProductionStrategies,
            )
            val dao = database.competitionMahjongFormalRoomDao()
            val bytes = CompetitionMahjongFormalRoomStorage.readMctxInTransaction(database, dao, viewer)
                ?: return@withTransaction base
            val snapshot = CompetitionMahjongFormalSnapshotCodec.decode(bytes, viewer)
                ?: throw IOException("国标麻将正式存档不兼容，原数据已保留")
            snapshot.qualifiedGrowth(base).policy
        }
    }
}
