package com.masterofchessstrategy.games.competition_mahjong.match.storage.room

import androidx.room.RoomDatabase
import com.masterofchessstrategy.games.competition_mahjong.match.storage.CompetitionMahjongFormalSnapshotCodec
import com.masterofchessstrategy.games.competition_mahjong.match.storage.CompetitionMahjongFormalStorage
import kotlinx.coroutines.sync.Mutex
import java.io.IOException
import java.util.UUID
import java.util.WeakHashMap
import java.util.concurrent.Callable

/** Test seam replaces transaction execution, not SQLite or Native validation. */
internal fun interface CompetitionMahjongFormalRoomTransaction {
    fun run(action: () -> Unit)
}

internal class CompetitionMahjongFormalRoomStorage private constructor(
    private val dao: CompetitionMahjongFormalRoomDao,
    private val transaction: CompetitionMahjongFormalRoomTransaction,
    private val viewer: Int,
    private val owner: String,
    private val epoch: Long,
) : CompetitionMahjongFormalStorage {
    override val mutex: Mutex = synchronized(locks) {
        locks.getOrPut(dao) { mutableMapOf() }.getOrPut(viewer) { Mutex() }
    }
    private var observedRevision: Long? = null

    override fun read(): ByteArray? {
        var result: ByteArray? = null
        var revision: Long? = null
        // A single SQLite snapshot covers metadata and every bounded CursorWindow result.
        transaction.run {
            val row = ownedRow()
            result = readPayloadInCurrentTransaction(dao, row)
            revision = row.revision
        }
        observedRevision = checkNotNull(revision)
        return result
    }

    override fun replace(bytes: ByteArray) {
        require(bytes.size in 1..CompetitionMahjongFormalSnapshotCodec.MAX_BYTES)
        val revision = checkNotNull(observedRevision) { "正式数据写入必须先读取当前版本" }
        val next = Math.addExact(revision, 1)
        transaction.run {
            // Check the persisted lease and revision inside the SQLite transaction as well as in SQL.
            val row = ownedRow()
            if (row.revision != revision) throw IOException("国标正式数据已变化，请重新加载")
            if (dao.replace(viewer, owner, epoch, revision, next, bytes.copyOf()) != 1)
                throw IOException("国标正式数据写者已退役或版本冲突")
        }
        observedRevision = next
    }

    private fun ownedRow(): CompetitionMahjongFormalRoomMetadata {
        val row = dao.find(viewer) ?: throw IOException("国标正式数据归属行缺失，禁止自动重建")
        if (row.viewerPlayerId != viewer || row.ownerId != owner || row.epoch != epoch || row.revision < 0)
            throw IOException("国标正式数据写者已退役，禁止重新取得归属")
        return row
    }

    companion object {
        private val locks = WeakHashMap<CompetitionMahjongFormalRoomDao, MutableMap<Int, Mutex>>()
        internal const val READ_CHUNK_BYTES = 64 * 1024

        /** No dispatcher switch, mutex or nested transaction: usable from global backup's withTransaction. */
        fun readMctxInTransaction(database: RoomDatabase, dao: CompetitionMahjongFormalRoomDao,
                                  viewer: Int): ByteArray? {
            check(database.inTransaction()) { "正式备份读取必须加入宿主现有Room事务" }
            require(viewer in 0..3)
            val row = dao.find(viewer) ?: return null
            require(row.viewerPlayerId == viewer && row.epoch > 0 && row.revision >= 0)
            require(UUID.fromString(row.ownerId).toString() == row.ownerId)
            return readPayloadInCurrentTransaction(dao, row)
                ?: throw IOException("国标正式数据尚未显式迁移，禁止导出为空存档")
        }

        private fun readPayloadInCurrentTransaction(dao: CompetitionMahjongFormalRoomDao,
                                                    row: CompetitionMahjongFormalRoomMetadata): ByteArray? {
            val size = row.mctxSize ?: return null
            if (size !in 1..CompetitionMahjongFormalSnapshotCodec.MAX_BYTES)
                throw IOException("国标正式数据长度异常，原数据已保留")
            val bytes = ByteArray(size)
            var offset = 0
            while (offset < size) {
                val count = minOf(READ_CHUNK_BYTES, size - offset)
                val chunk = dao.readChunk(row.viewerPlayerId, row.ownerId, row.epoch, row.revision, offset + 1, count)
                if (chunk == null || chunk.size != count)
                    throw IOException("国标正式数据分块读取不完整，原数据已保留")
                chunk.copyInto(bytes, offset)
                offset += count
            }
            return bytes
        }
        /** Coordinator calls once per host lifetime, after stopping all legacy/current writers. */
        fun acquire(database: RoomDatabase, dao: CompetitionMahjongFormalRoomDao,
                    viewer: Int): CompetitionMahjongFormalRoomStorage = acquire(dao,
            CompetitionMahjongFormalRoomTransaction { action ->
                database.runInTransaction(Callable { action(); Unit })
            }, viewer)

        /** Joins the coordinator's existing restore transaction; it opens no nested transaction or mutex. */
        fun installBackupInTransaction(database: RoomDatabase, dao: CompetitionMahjongFormalRoomDao,
                                       backup: CompetitionMahjongFormalRoomBackup): CompetitionMahjongFormalRoomStorage {
            check(database.inTransaction()) { "正式备份必须加入宿主现有Room恢复事务" }
            val transaction = CompetitionMahjongFormalRoomTransaction { action ->
                database.runInTransaction(Callable { action(); Unit })
            }
            return installBackupInCurrentTransaction(dao, transaction, backup)
        }

        /** Caller owns the outer transaction; production callers use the checked RoomDatabase overload. */
        internal fun installBackupInCurrentTransaction(dao: CompetitionMahjongFormalRoomDao,
                                                       transaction: CompetitionMahjongFormalRoomTransaction,
                                                       backup: CompetitionMahjongFormalRoomBackup): CompetitionMahjongFormalRoomStorage =
            claim(dao, transaction, backup.viewerPlayerId, backup.mctx)

        internal fun acquire(dao: CompetitionMahjongFormalRoomDao,
                             transaction: CompetitionMahjongFormalRoomTransaction,
                             viewer: Int): CompetitionMahjongFormalRoomStorage {
            require(viewer in 0..3)
            var storage: CompetitionMahjongFormalRoomStorage? = null
            transaction.run {
                storage = claim(dao, transaction, viewer, null)
            }
            return checkNotNull(storage)
        }

        private fun claim(dao: CompetitionMahjongFormalRoomDao,
                          transaction: CompetitionMahjongFormalRoomTransaction,
                          viewer: Int, backup: ByteArray?): CompetitionMahjongFormalRoomStorage {
            val owner = UUID.randomUUID().toString()
            val row = dao.find(viewer)
            val epoch: Long
            if (row == null) {
                epoch = 1
                dao.insert(CompetitionMahjongFormalRoomRow(viewer, owner, epoch, 0, backup?.copyOf()))
            } else {
                require(row.viewerPlayerId == viewer && row.epoch > 0 && row.revision >= 0)
                require(UUID.fromString(row.ownerId).toString() == row.ownerId)
                epoch = Math.addExact(row.epoch, 1)
                val revision = Math.addExact(row.revision, 1)
                val restoredRevision = if (backup == null) revision else Math.addExact(revision, 1)
                check(dao.claim(viewer, row.ownerId, row.epoch, row.revision, owner, epoch, revision) == 1) {
                    "国标正式数据归属已变化"
                }
                if (backup != null) check(dao.replace(viewer, owner, epoch, revision, restoredRevision, backup.copyOf()) == 1) {
                    "国标正式备份恢复版本冲突"
                }
            }
            return CompetitionMahjongFormalRoomStorage(dao, transaction, viewer, owner, epoch)
        }
    }
}
