package com.masterofchessstrategy.games.competition_mahjong.match.storage.room

import androidx.room.RoomDatabase
import com.masterofchessstrategy.engine.NativeCompetitionMahjongSession
import com.masterofchessstrategy.engine.RestoreResult
import com.masterofchessstrategy.games.competition_mahjong.match.CompetitionMahjongMatchCheckpoint
import com.masterofchessstrategy.games.competition_mahjong.match.policy.CompetitionMahjongPlayPolicy
import com.masterofchessstrategy.games.competition_mahjong.match.storage.CompetitionMahjongFormalMigration
import com.masterofchessstrategy.games.competition_mahjong.match.storage.CompetitionMahjongFormalRepository
import com.masterofchessstrategy.games.competition_mahjong.match.storage.CompetitionMahjongFormalSnapshot
import com.masterofchessstrategy.games.competition_mahjong.match.storage.CompetitionMahjongFormalSnapshotCodec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException

/** One host shares repository.matchStore/statisticsStore/historyStore across its three routes. */
internal class CompetitionMahjongFormalRoomBridge private constructor(
    private val storage: CompetitionMahjongFormalRoomStorage,
    private val viewer: Int,
    private val policy: CompetitionMahjongPlayPolicy,
) {
    val migration = CompetitionMahjongFormalMigration(storage, viewer, policy, ::admitCompetitionMahjongFormalNative, Dispatchers.IO)

    /** A new or unmigrated row is intentionally unusable until explicit migration succeeds. */
    suspend fun openRepository(): CompetitionMahjongFormalRepository = withContext(Dispatchers.IO) {
        storage.mutex.withLock {
            val bytes = storage.read() ?: throw IOException("国标正式数据尚未显式迁移，旧来源已保留")
            admitSnapshot(bytes)
        }
        CompetitionMahjongFormalRepository(storage, viewer, policy, ::admitCompetitionMahjongFormalNative)
    }

    /** Standalone IO export; global backup instead uses readMctxInTransaction without switching threads. */
    suspend fun exportMctx(): ByteArray = withContext(Dispatchers.IO) {
        storage.mutex.withLock {
            val bytes = storage.read() ?: throw IOException("国标正式数据尚未显式迁移")
            admitSnapshot(bytes)
            bytes.copyOf()
        }
    }

    private fun admitSnapshot(bytes: ByteArray) {
        val snapshot = CompetitionMahjongFormalSnapshotCodec.decode(bytes, viewer)
            ?: throw IOException("国标正式数据损坏或版本不兼容，原数据已保留")
        snapshot.active?.let {
            policy.requireAuthorized(it.config.botLevel, it.assistance)
            admitCompetitionMahjongFormalNative(it)
        }
    }

    companion object {
        /** Call under the coordinator's retired-writer barrier; this does not retire legacy MCMT writers. */
        suspend fun acquire(database: RoomDatabase, dao: CompetitionMahjongFormalRoomDao,
                            viewer: Int, policy: CompetitionMahjongPlayPolicy): CompetitionMahjongFormalRoomBridge =
            withContext(Dispatchers.IO) {
                CompetitionMahjongFormalRoomBridge(
                    CompetitionMahjongFormalRoomStorage.acquire(database, dao, viewer), viewer, policy)
            }

        /** Candidate admission happens before the global restore transaction. Publish only after commit. */
        fun installBackupInTransaction(database: RoomDatabase, dao: CompetitionMahjongFormalRoomDao,
                                       backup: CompetitionMahjongFormalRoomBackup): CompetitionMahjongFormalRoomBridge =
            CompetitionMahjongFormalRoomBridge(
                CompetitionMahjongFormalRoomStorage.installBackupInTransaction(database, dao, backup),
                backup.viewerPlayerId, backup.playPolicy)
    }
}

/** A decoded and Native-admitted restore candidate; fencing metadata is deliberately absent. */
internal class CompetitionMahjongFormalRoomBackup private constructor(
    val viewerPlayerId: Int,
    bytes: ByteArray,
    internal val playPolicy: CompetitionMahjongPlayPolicy,
) {
    private val bytes = bytes.copyOf()
    internal val mctx: ByteArray get() = bytes.copyOf()

    companion object {
        /** An absent formal slot in a replacement backup must seal old sources with an explicit empty fact. */
        fun emptyForRestore(viewer: Int, policy: CompetitionMahjongPlayPolicy): CompetitionMahjongFormalRoomBackup =
            admit(viewer, CompetitionMahjongFormalSnapshotCodec.encode(CompetitionMahjongFormalSnapshot(viewer)), policy)

        /** Synchronous for GameBackupAdapter.prepareRestore; caller supplies its existing IO context. */
        fun admit(viewer: Int, bytes: ByteArray,
                  policy: CompetitionMahjongPlayPolicy): CompetitionMahjongFormalRoomBackup {
            // Copy before validation: caller mutation cannot alter the admitted candidate.
            val candidate = bytes.copyOf()
            val snapshot = CompetitionMahjongFormalSnapshotCodec.decode(candidate, viewer)
                ?: throw IOException("国标正式备份损坏或版本不兼容")
            snapshot.active?.let {
                policy.requireAuthorized(it.config.botLevel, it.assistance)
                admitCompetitionMahjongFormalNative(it)
            }
            return CompetitionMahjongFormalRoomBackup(viewer, candidate, policy)
        }
    }
}

/** Includes v4 paid-hint legality, not just the current public frame and terminal ledger. */
private fun admitCompetitionMahjongFormalNative(checkpoint: CompetitionMahjongMatchCheckpoint) {
    val hand = checkpoint.config.handConfig(checkpoint.activeHandIndex)
    NativeCompetitionMahjongSession(hand.seed, hand.dealerSeat, hand.roundWind, hand.viewerSeat).use { session ->
        require(session.restore(checkpoint.engineRecord) == RestoreResult.Restored) {
            "国标正式数据未通过Native重放准入"
        }
        checkpoint.validateRestoredFrame(session.snapshot(), session.legalActions())
    }
}
