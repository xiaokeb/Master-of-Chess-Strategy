package com.masterofchessstrategy.games.competition_mahjong.data

import androidx.room.RoomDatabase
import com.masterofchessstrategy.data.BackupStorageSlot
import com.masterofchessstrategy.data.BackupStorageTable
import com.masterofchessstrategy.data.GameBackupAdapter
import com.masterofchessstrategy.data.GameBackupContainerPreparation
import com.masterofchessstrategy.data.GameBackupDescriptor
import com.masterofchessstrategy.data.GameBackupPreparationContext
import com.masterofchessstrategy.data.GameBackupRecord
import com.masterofchessstrategy.data.MocsDatabase
import com.masterofchessstrategy.data.PreparedGameBackup
import com.masterofchessstrategy.engine.Difficulty
import com.masterofchessstrategy.engine.GameType
import com.masterofchessstrategy.games.competition_mahjong.match.policy.CompetitionMahjongPlayPolicy
import com.masterofchessstrategy.games.competition_mahjong.match.policy.CompetitionMahjongUnlockProgress
import com.masterofchessstrategy.games.competition_mahjong.match.storage.CompetitionMahjongFormalSnapshotCodec
import com.masterofchessstrategy.games.competition_mahjong.match.storage.room.CompetitionMahjongFormalRoomDao
import com.masterofchessstrategy.games.competition_mahjong.match.storage.room.CompetitionMahjongFormalRoomStorage
import java.util.Collections

/** Whole-candidate formal replacement. The coordinator owns retirement, clearing, and publication. */
internal class CompetitionMahjongFormalBackupAdapter(strategyBindings: Map<Difficulty, Int>) :
    GameBackupAdapter, GameBackupContainerPreparation {
    private val bindings = Collections.unmodifiableMap(HashMap(strategyBindings))

    init { CompetitionMahjongPlayPolicy.fromProgress(CompetitionMahjongUnlockProgress(false), bindings) }

    override val descriptor = GameBackupDescriptor(CompetitionMahjongFormalBackupWire.KIND, 5, SLOT,
        4, 43, CompetitionMahjongFormalBackupWire.MAXIMUM_RECORD_BYTES)

    override suspend fun read(database: MocsDatabase): List<GameBackupRecord> =
        readInTransaction(database, database.competitionMahjongFormalRoomDao()) {
            CompetitionMahjongBackupAdapter().read(database)
        }

    /** The callback reads the old slot in the same snapshot, after validating every actual target. */
    internal suspend fun readInTransaction(database: RoomDatabase, dao: CompetitionMahjongFormalRoomDao,
                                          readLegacy: suspend () -> List<GameBackupRecord>): List<GameBackupRecord> {
        val targets = readTargets(database, dao)
        val legacy = readLegacy().toList()
        require(legacy.size <= 1) { "Multiple retained legacy records" }
        val retained = legacy.singleOrNull()
        // Validate an existing source even when there are no formal rows to export.
        retained?.let(CompetitionMahjongFormalBackupWire::retainedLegacySourceSha256)
        return targets.map { (viewer, bytes) -> CompetitionMahjongFormalBackupWire.encode(viewer, bytes, retained) }
    }

    override fun validate(record: GameBackupRecord, containerVersion: Int) {
        normalize(record, containerVersion)
    }

    override fun normalize(record: GameBackupRecord, containerVersion: Int): GameBackupRecord {
        require(containerVersion == 5) { "Formal backup requires container version 5" }
        return CompetitionMahjongFormalBackupWire.normalize(record)
    }

    override fun prepareRestore(record: GameBackupRecord): PreparedGameBackup =
        throw UnsupportedOperationException("Formal backup requires one complete-container preparation")

    override fun prepareContainer(records: List<GameBackupRecord>, context: GameBackupPreparationContext): PreparedGameBackup =
        when (val candidate = CompetitionMahjongFormalBackupWire.decodeContainer(records)) {
            is CompetitionMahjongFormalBackupCandidate.LegacyOnly -> PreparedLegacyOnly()
            is CompetitionMahjongFormalBackupCandidate.Formal -> PreparedFormal(
                CompetitionMahjongFormalRestorePreparer.prepare(candidate.strictRecords, bindings),
            )
        }

    /** The old adapter installs the retained legacy slot; this owner only removes stale formal rows. */
    internal class PreparedLegacyOnly : PreparedGameBackup {
        override val storageSlot = SLOT
        override suspend fun write(database: MocsDatabase) {
            writeInTransaction(database, database.competitionMahjongFormalRoomDao()) {
                database.openHelper.writableDatabase.execSQL("DELETE FROM competition_mahjong_formal")
            }
        }

        internal fun writeInTransaction(database: RoomDatabase, dao: CompetitionMahjongFormalRoomDao,
                                        deleteFormal: () -> Unit) {
            requireStoredTarget(database, dao)
            deleteFormal()
        }
    }

    internal class PreparedFormal(private val plan: CompetitionMahjongFormalRestorePlan) : PreparedGameBackup {
        override val storageSlot = SLOT
        override suspend fun write(database: MocsDatabase) {
            writeInTransaction(database, database.competitionMahjongFormalRoomDao())
        }

        internal fun writeInTransaction(database: RoomDatabase, dao: CompetitionMahjongFormalRoomDao) {
            requireStoredTarget(database, dao)
            // Installation preserves existing lease evolution; no bridge escapes this transaction.
            plan.installInTransaction(database, dao)
        }
    }

    companion object {
        val SLOT = BackupStorageSlot(BackupStorageTable.COMPETITION_FORMAL, GameType.STANDARD_MAHJONG)

        /** The global replacement must call this before clearing any table, inside its transaction. */
        fun requireStoredTarget(database: MocsDatabase) =
            requireStoredTarget(database, database.competitionMahjongFormalRoomDao())

        internal fun requireStoredTarget(database: RoomDatabase, dao: CompetitionMahjongFormalRoomDao) {
            readTargets(database, dao)
        }

        private fun readTargets(database: RoomDatabase, dao: CompetitionMahjongFormalRoomDao): List<Pair<Int, ByteArray>> {
            check(database.inTransaction()) { "Formal backup requires the enclosing Room transaction" }
            return CompetitionMahjongFormalBackupBundle.requireStoredViewers(dao).map { viewer ->
                val bytes = requireNotNull(CompetitionMahjongFormalRoomStorage.readMctxInTransaction(database, dao, viewer)) {
                    "An actual formal row disappeared during the snapshot"
                }
                requireNotNull(CompetitionMahjongFormalSnapshotCodec.decode(bytes, viewer)) {
                    "Stored formal payload is incompatible; original rows are preserved"
                }
                viewer to bytes
            }
        }
    }
}
