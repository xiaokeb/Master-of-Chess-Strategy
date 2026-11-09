package com.masterofchessstrategy.games.competition_mahjong.match.storage.room

import androidx.room.withTransaction
import com.masterofchessstrategy.data.CompetitionMahjongMatchArchive
import com.masterofchessstrategy.data.MocsDatabase
import com.masterofchessstrategy.data.validateCompetitionMahjongMatchArchive
import com.masterofchessstrategy.engine.GameType
import com.masterofchessstrategy.games.competition_mahjong.history.CompetitionMahjongMatchHistoryStore
import com.masterofchessstrategy.games.competition_mahjong.learning.CompetitionMahjongLearningProofRead
import com.masterofchessstrategy.games.competition_mahjong.learning.CompetitionMahjongStorageEpochGate
import com.masterofchessstrategy.games.competition_mahjong.learning.RoomCompetitionMahjongLearningProofStore
import com.masterofchessstrategy.games.competition_mahjong.match.CompetitionMahjongMatchCheckpoint
import com.masterofchessstrategy.games.competition_mahjong.match.CompetitionMahjongMatchStore
import com.masterofchessstrategy.games.competition_mahjong.match.policy.CompetitionMahjongPlayPolicy
import com.masterofchessstrategy.games.competition_mahjong.match.policy.CompetitionMahjongUnlockProgress
import com.masterofchessstrategy.games.competition_mahjong.match.policy.competitionMahjongProductionStrategies
import com.masterofchessstrategy.games.competition_mahjong.match.storage.CompetitionMahjongFormalMigrationPreview
import com.masterofchessstrategy.games.competition_mahjong.match.storage.CompetitionMahjongFormalMigrationSource
import com.masterofchessstrategy.games.competition_mahjong.match.storage.CompetitionMahjongFormalRepository
import com.masterofchessstrategy.games.competition_mahjong.statistics.CompetitionMahjongStatisticsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.UUID

/** One navigation entry owns one seat and one storage generation for its entire lifetime. */
internal class CompetitionMahjongFormalHost(private val database: MocsDatabase, val viewerPlayerId: Int) {
    init { require(viewerPlayerId in 0..3) }

    private val gate = CompetitionMahjongStorageEpochGate.forDatabase(database)
    private val owner = gate.captureOwner()
    private val dao = database.competitionMahjongFormalRoomDao()
    private val learning = RoomCompetitionMahjongLearningProofStore(database, database.competitionMahjongLearningProofDao())
    private var session: Session? = null // Accessed under the database gate.
    private var pending: Entry? = null
    @Volatile private var closed = false
    val ownerKey: String = UUID.randomUUID().toString()
    fun isCurrent(): Boolean = !closed && gate.isCurrent(owner)
    fun close() { closed = true }

    sealed interface Entry {
        data class ConfirmMigration(val preview: CompetitionMahjongFormalMigrationPreview) : Entry
        data class ConfirmLegacyArchive(val sourceSha256: String, val completedHandCount: Int) : Entry
        data class Ready(val session: Session) : Entry
    }

    /** The navigator drains the previous writer before opening or confirming a new host. */
    suspend fun open(): Entry = access {
        session?.let { return@access Entry.Ready(it) }
        pending = null
        val policy = loadPolicy()
        if (dao.find(viewerPlayerId)?.mctxSize != null) {
            val bridge = CompetitionMahjongFormalRoomBridge.acquire(database, dao, viewerPlayerId, policy)
            return@access Entry.Ready(publish(bridge, policy))
        }
        val legacy = readLegacy()
        if (legacy != null) {
            val source = source(legacy)
            val proposal = if (legacy.checkpoint.assistance == null) {
                require(validateCompetitionMahjongMatchArchive(legacy)) { "旧赛事无法通过重放校验，原档已保留" }
                Entry.ConfirmLegacyArchive(source.sha256, legacy.checkpoint.ledger.completedHandCount)
            } else {
                Entry.ConfirmMigration(CompetitionMahjongFormalRoomBridge.previewInitialMigration(viewerPlayerId, policy, source))
            }
            pending = proposal
            return@access proposal
        }
        Entry.Ready(installInitial(policy, source(null).sha256))
    }

    suspend fun confirmMigration(preview: CompetitionMahjongFormalMigrationPreview): Session = access {
        // A repeated click after a successful commit reuses the same lease.
        session ?: run {
            require(Entry.ConfirmMigration(preview) == pending) { "请先预览此座位的旧赛事档案" }
            installInitial(loadPolicy(), preview.sourceSha256)
        }
    }

    suspend fun confirmLegacyArchive(proposal: Entry.ConfirmLegacyArchive): Session = access {
        session ?: run {
            require(proposal == pending) { "请先预览此座位的旧赛事档案" }
            val policy = loadPolicy()
            val empty = CompetitionMahjongFormalRoomBackup.emptyForRestore(viewerPlayerId, policy)
            val bridge = database.withTransaction {
                if (dao.find(viewerPlayerId)?.mctxSize != null) throw IOException("正式赛事已存在，请重新打开")
                val archive = readLegacy()
                require(archive != null && archive.checkpoint.assistance == null &&
                    source(archive).sha256 == proposal.sourceSha256) { "旧赛事已变化，请重新预览" }
                // Publish an empty formal seat and keep the original MCMT for export.
                CompetitionMahjongFormalRoomBridge.installBackupInTransaction(database, dao, empty)
            }
            publish(bridge, policy)
        }
    }

    private suspend fun installInitial(policy: CompetitionMahjongPlayPolicy, expectedSha: String): Session {
        val bridge = CompetitionMahjongFormalRoomBridge.installInitialMigration(
            database, dao, viewerPlayerId, policy, expectedSha,
        ) { source(readLegacy()) }
        return publish(bridge, policy)
    }

    private suspend fun publish(bridge: CompetitionMahjongFormalRoomBridge, policy: CompetitionMahjongPlayPolicy): Session =
        Session(bridge.openRepository(), policy).also {
            pending = null
            session = it
        }

    private suspend fun loadPolicy(): CompetitionMahjongPlayPolicy {
        val proof = when (val result = learning.load()) {
            is CompetitionMahjongLearningProofRead.Ready -> result.proof
            CompetitionMahjongLearningProofRead.Incompatible -> throw IOException("国标麻将教程证明不兼容，原数据已保留")
        }
        return CompetitionMahjongPlayPolicy.fromProgress(
            CompetitionMahjongUnlockProgress(tutorialCompleted = proof.isCompleted),
            competitionMahjongProductionStrategies,
        )
    }

    private suspend fun readLegacy(): CompetitionMahjongMatchArchive? {
        val row = database.competitionMahjongMatchDao().find(GameType.STANDARD_MAHJONG.code) ?: return null
        val archive = CompetitionMahjongMatchArchive.fromEntity(row)
        if (archive.viewerPlayerId == viewerPlayerId) return archive
        // A mixed backup archives the legacy source. Resolve its seat before creating another formal slot.
        if (dao.find(archive.viewerPlayerId)?.mctxSize == null) {
            val seat = "东南西北"[archive.viewerPlayerId]
            throw IOException("请先处理初始${seat}位的旧赛事档案")
        }
        return null
    }

    private fun source(archive: CompetitionMahjongMatchArchive?) = CompetitionMahjongFormalMigrationSource(
        viewerPlayerId, archive?.encodedCheckpoint, null, null,
        materializeActiveCompletion = archive?.checkpoint?.ledger?.isComplete == true,
        activeSettledAtEpochMillis = archive?.updatedAtEpochMillis ?: 0,
    )

    private suspend fun <T> access(operation: suspend () -> T): T = gate.access(owner) {
        if (closed) throw IOException("国标麻将赛事页面已关闭")
        withContext(Dispatchers.IO) { operation() }
    }

    inner class Session internal constructor(
        private val repository: CompetitionMahjongFormalRepository,
        val policy: CompetitionMahjongPlayPolicy,
    ) {
        val match: CompetitionMahjongMatchStore = object : CompetitionMahjongMatchStore {
            override suspend fun load(viewerPlayerId: Int) = access { repository.matchStore.load(viewerPlayerId) }
            override suspend fun save(checkpoint: CompetitionMahjongMatchCheckpoint) =
                access { repository.matchStore.save(checkpoint) }
        }
        val statistics: CompetitionMahjongStatisticsStore = object : CompetitionMahjongStatisticsStore by repository.statisticsStore {
            override suspend fun load(viewerPlayerId: Int) = access { repository.statisticsStore.load(viewerPlayerId) }
        }
        val history: CompetitionMahjongMatchHistoryStore = object : CompetitionMahjongMatchHistoryStore by repository.historyStore {
            override suspend fun load(viewerPlayerId: Int) = access { repository.historyStore.load(viewerPlayerId) }
            override suspend fun setFavorite(viewerPlayerId: Int, matchId: String, favorite: Boolean) =
                access { repository.historyStore.setFavorite(viewerPlayerId, matchId, favorite) }
            override suspend fun delete(viewerPlayerId: Int, matchId: String) =
                access { repository.historyStore.delete(viewerPlayerId, matchId) }
        }
    }
}
