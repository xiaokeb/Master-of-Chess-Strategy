package com.masterofchessstrategy.games.competition_mahjong.data

import androidx.room.InvalidationTracker
import androidx.room.RoomDatabase
import com.masterofchessstrategy.data.BackupStorageTable
import com.masterofchessstrategy.data.CompetitionMahjongMatchArchive
import com.masterofchessstrategy.data.GameBackupPreparationContext
import com.masterofchessstrategy.data.GameBackupRecord
import com.masterofchessstrategy.engine.Difficulty
import com.masterofchessstrategy.games.competition_mahjong.learning.CompetitionMahjongLearningBackupAdapter
import com.masterofchessstrategy.games.competition_mahjong.learning.CompetitionMahjongLearningProof
import com.masterofchessstrategy.games.competition_mahjong.learning.CompetitionMahjongLearningProofCodec
import com.masterofchessstrategy.games.competition_mahjong.match.CompetitionMahjongMatchCheckpointCodec
import com.masterofchessstrategy.games.competition_mahjong.match.storage.CompetitionMahjongFormalSnapshot
import com.masterofchessstrategy.games.competition_mahjong.match.storage.CompetitionMahjongFormalSnapshotCodec
import com.masterofchessstrategy.games.competition_mahjong.match.storage.formalCheckpoint
import com.masterofchessstrategy.games.competition_mahjong.match.storage.formalPolicy
import com.masterofchessstrategy.games.competition_mahjong.match.storage.room.CompetitionMahjongFormalRoomDao
import com.masterofchessstrategy.games.competition_mahjong.match.storage.room.CompetitionMahjongFormalRoomMetadata
import com.masterofchessstrategy.games.competition_mahjong.match.storage.room.CompetitionMahjongFormalRoomRow
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Transaction/DAO doubles only; accepted formal candidates have no active Native record. */
class CompetitionMahjongFormalBackupAdapterTest {
    @Test fun descriptorAndSingleRecordEntryRequireTheFormalContainerContract() {
        val adapter = adapter()
        assertEquals("COMPETITION_FORMAL", adapter.descriptor.kind)
        assertEquals(BackupStorageTable.COMPETITION_FORMAL, adapter.descriptor.storageSlot.table)
        assertEquals(5, adapter.descriptor.minimumContainerVersion)
        assertEquals(4, adapter.descriptor.maximumRecords)
        assertEquals(43, adapter.descriptor.canonicalOrder)
        assertEquals(CompetitionMahjongFormalBackupWire.MAXIMUM_RECORD_BYTES, adapter.descriptor.maximumRecordBytes)
        val wire = CompetitionMahjongFormalBackupWire.encode(0, empty(0))
        assertThrows(UnsupportedOperationException::class.java) { adapter.prepareRestore(wire) }
    }

    @Test fun versionValidationAndNormalizationPreserveTheRetainedRole() {
        val adapter = adapter()
        val wire = CompetitionMahjongFormalBackupWire.encode(0, empty(0), legacy())
        assertEquals(wire, adapter.normalize(wire, 5))
        adapter.validate(wire, 5)
        for (version in listOf(1, 4, 6)) {
            assertThrows(IllegalArgumentException::class.java) { adapter.validate(wire, version) }
        }
        assertEquals(5, adapter.normalize(wire, 5).fields.size)
    }

    @Test fun readsEveryActualSeatAndAssociatesOneCanonicalLegacyRecord() = runBlocking {
        val rows = Rows().apply { add(3); add(1) }
        val old = legacy()
        var sourceReads = 0
        val records = adapter().readInTransaction(TransactionRoom(true), rows) { sourceReads++; listOf(old) }
        assertEquals(1, sourceReads)
        assertEquals(listOf("1", "3"), records.map { it.fields[2] })
        assertTrue(records.all { it.fields[4] == CompetitionMahjongFormalBackupWire.retainedLegacySourceSha256(old) })
        assertEquals(0, rows.writes)
        val split = CompetitionMahjongFormalBackupWire.decodeContainer(records + old) as CompetitionMahjongFormalBackupCandidate.Formal
        assertEquals(old, split.retainedLegacyRecord)
    }

    @Test fun readsWithoutALegacySourceUseExplicitNoSourceRoles() = runBlocking {
        val rows = Rows().apply { add(0) }
        assertEquals("-", adapter().readInTransaction(TransactionRoom(true), rows) { emptyList() }.single().fields[4])
        assertTrue(adapter().readInTransaction(TransactionRoom(true), Rows()) { emptyList() }.isEmpty())
    }

    @Test fun readRequiresAnExistingTransactionBeforeAnyDaoOrLegacyAccess() {
        val rows = Rows()
        var sourceReads = 0
        assertThrows(IllegalStateException::class.java) { runBlocking {
            adapter().readInTransaction(TransactionRoom(false), rows) { sourceReads++; emptyList() }
        } }
        assertEquals(0, rows.keyReads)
        assertEquals(0, sourceReads)
    }

    @Test fun unknownOrDuplicateActualKeysRejectBeforeLoadingOrReadingLegacy() {
        for (keys in listOf(listOf(-1), listOf(0, 9), listOf(0, 0), listOf(0, 1, 2, 3, 4))) {
            val rows = Rows().apply { overrideKeys = keys; add(0) }
            var sourceReads = 0
            assertThrows(IllegalArgumentException::class.java) { runBlocking {
                adapter().readInTransaction(TransactionRoom(true), rows) { sourceReads++; emptyList() }
            } }
            assertEquals(0, rows.finds)
            assertEquals(0, rows.writes)
            assertEquals(0, sourceReads)
        }
    }

    @Test fun incompatibleNullOversizeAndMissingPayloadsFailWithoutTargetWrites() {
        val invalid = listOf(
            Rows().apply { add(0, bytes = byteArrayOf(1, 2, 3)) },
            Rows().apply { add(0, bytes = null) },
            Rows().apply { add(0); metadataSize = CompetitionMahjongFormalSnapshotCodec.MAX_BYTES + 1 },
            Rows().apply { add(0); missingChunk = true },
            Rows().apply { overrideKeys = listOf(0) },
            Rows().apply { add(0, bytes = empty(1)) },
        )
        invalid.forEach { rows ->
            assertThrows(Exception::class.java) { CompetitionMahjongFormalBackupAdapter.requireStoredTarget(TransactionRoom(true), rows) }
            assertEquals(0, rows.writes)
        }
    }

    @Test fun largeValidTargetUsesBoundedChunksWithoutAnyNativeReplay() {
        var snapshot = CompetitionMahjongFormalSnapshot(0)
        repeat(120) { snapshot = snapshot.save(formalCheckpoint(id = it + 1), formalPolicy(), it.toLong()) }
        val history = CompetitionMahjongFormalSnapshot(0, statistics = snapshot.statistics, history = snapshot.history)
        val bytes = CompetitionMahjongFormalSnapshotCodec.encode(history)
        assertTrue(bytes.size > 64 * 1024)
        val rows = Rows().apply { add(0, bytes = bytes) }
        CompetitionMahjongFormalBackupAdapter.requireStoredTarget(TransactionRoom(true), rows)
        assertTrue(rows.chunks > 1)
        assertTrue(rows.largestChunk <= 64 * 1024)
        assertEquals(0, rows.writes)
    }

    @Test fun multipleOrBadLegacyRecordsRejectEvenWhenNoFormalRowsExist() {
        for (records in listOf(listOf(legacy(), legacy()), listOf(GameBackupRecord("COMPETITION_MATCH", listOf("bad"))))) {
            assertThrows(IllegalArgumentException::class.java) { runBlocking {
                adapter().readInTransaction(TransactionRoom(true), Rows()) { records }
            } }
        }
    }

    @Test fun missingFormalKindStillPreparesAndInstallsFourExplicitEmptyFacts() {
        val prepared = adapter().prepareContainer(emptyList(), GameBackupPreparationContext()) as CompetitionMahjongFormalBackupAdapter.PreparedFormal
        val rows = Rows().apply { add(0, epoch = 4, revision = 7) }
        val before = rows.rows.getValue(0)
        prepared.writeInTransaction(TransactionRoom(true), rows)
        assertEquals((0..3).toList(), rows.rows.keys.sorted())
        val after = rows.rows.getValue(0)
        assertNotEquals(before.ownerId, after.ownerId)
        assertEquals(5L, after.epoch)
        assertTrue(after.revision > before.revision)
        rows.rows.forEach { (viewer, row) -> assertArrayEquals(empty(viewer), row.mctx) }
    }

    @Test fun legacyOnlyPreparedDeletesFormalRowsButDoesNotInstallOrRewriteLegacy() {
        val prepared = adapter().prepareContainer(listOf(legacy()), GameBackupPreparationContext()) as CompetitionMahjongFormalBackupAdapter.PreparedLegacyOnly
        val rows = Rows().apply { add(0); add(3) }
        var deletes = 0
        prepared.writeInTransaction(TransactionRoom(true), rows) { deletes++; rows.rows.clear() }
        assertEquals(1, deletes)
        assertTrue(rows.rows.isEmpty())
        assertEquals(0, rows.writes)
        assertEquals(BackupStorageTable.COMPETITION_FORMAL, prepared.storageSlot.table)
    }

    @Test fun bothPreparedBranchesRejectOutsideTransactionsAndProtectBadTargetsBeforeWriting() {
        val formal = adapter().prepareContainer(emptyList(), GameBackupPreparationContext()) as CompetitionMahjongFormalBackupAdapter.PreparedFormal
        val legacy = adapter().prepareContainer(listOf(legacy()), GameBackupPreparationContext()) as CompetitionMahjongFormalBackupAdapter.PreparedLegacyOnly
        val rows = Rows().apply { add(0) }
        assertThrows(IllegalStateException::class.java) { formal.writeInTransaction(TransactionRoom(false), rows) }
        assertThrows(IllegalStateException::class.java) { legacy.writeInTransaction(TransactionRoom(false), rows) { fail("Delete outside transaction") } }
        rows.add(9)
        assertThrows(IllegalArgumentException::class.java) { formal.writeInTransaction(TransactionRoom(true), rows) }
        assertThrows(IllegalArgumentException::class.java) { legacy.writeInTransaction(TransactionRoom(true), rows) { fail("Delete before validation") } }
        assertEquals(0, rows.writes)
    }

    @Test fun batchRejectsBadCandidateMclpAndUnassociatedMixedSources() {
        val bad = GameBackupRecord(CompetitionMahjongLearningBackupAdapter.KIND, listOf("2", "bad"))
        val formal = CompetitionMahjongFormalBackupWire.encode(0, empty(0))
        assertThrows(IllegalArgumentException::class.java) { adapter().prepareContainer(listOf(bad), GameBackupPreparationContext()) }
        assertThrows(IllegalArgumentException::class.java) { adapter().prepareContainer(listOf(formal, legacy()), GameBackupPreparationContext()) }
        val proof = CompetitionMahjongLearningBackupAdapter().encode(CompetitionMahjongLearningProofCodec.encode(CompetitionMahjongLearningProof()))
        assertThrows(IllegalArgumentException::class.java) { adapter().prepareContainer(listOf(proof, proof), GameBackupPreparationContext()) }
    }

    @Test fun copiedBindingsRemainValidAfterCallerMutationAndInvalidBindingsRejectAtConstruction() {
        val bindings = Difficulty.entries.associateWith { it.code }.toMutableMap()
        val adapter = CompetitionMahjongFormalBackupAdapter(bindings)
        bindings.clear()
        assertTrue(adapter.prepareContainer(emptyList(), GameBackupPreparationContext()) is CompetitionMahjongFormalBackupAdapter.PreparedFormal)
        assertThrows(IllegalArgumentException::class.java) { CompetitionMahjongFormalBackupAdapter(emptyMap()) }
    }

    private class TransactionRoom(private val active: Boolean) : RoomDatabase() {
        override fun inTransaction() = active
        override fun createInvalidationTracker(): InvalidationTracker = error("No SQLite in JVM tests")
        override fun clearAllTables(): Unit = error("No global clearing in this adapter")
    }

    private class Rows : CompetitionMahjongFormalRoomDao {
        val rows = linkedMapOf<Int, CompetitionMahjongFormalRoomRow>()
        var overrideKeys: List<Int>? = null
        var metadataSize: Int? = null
        var missingChunk = false
        var keyReads = 0
        var finds = 0
        var chunks = 0
        var largestChunk = 0
        var writes = 0

        fun add(viewer: Int, bytes: ByteArray? = empty(viewer.coerceIn(0, 3)), epoch: Long = 1, revision: Long = 0) {
            rows[viewer] = CompetitionMahjongFormalRoomRow(viewer, OWNER, epoch, revision, bytes?.copyOf())
        }
        override fun listViewerIds(): List<Int> { keyReads++; return overrideKeys ?: rows.keys.sorted() }
        override fun find(viewer: Int): CompetitionMahjongFormalRoomMetadata? {
            finds++
            return rows[viewer]?.let { CompetitionMahjongFormalRoomMetadata(viewer, it.ownerId, it.epoch, it.revision, metadataSize ?: it.mctx?.size) }
        }
        override fun readChunk(viewer: Int, owner: String, epoch: Long, revision: Long, offset: Int, count: Int): ByteArray? {
            chunks++; largestChunk = maxOf(largestChunk, count)
            if (missingChunk) return null
            val row = rows[viewer] ?: return null
            if (row.ownerId != owner || row.epoch != epoch || row.revision != revision) return null
            return row.mctx?.copyOfRange(offset - 1, offset - 1 + count)
        }
        override fun insert(row: CompetitionMahjongFormalRoomRow) { check(row.viewerPlayerId !in rows); writes++; rows[row.viewerPlayerId] = row.copy(mctx = row.mctx?.copyOf()) }
        override fun claim(viewer: Int, owner: String, epoch: Long, revision: Long, nextOwner: String, nextEpoch: Long, nextRevision: Long): Int {
            val row = rows[viewer] ?: return 0
            if (row.ownerId != owner || row.epoch != epoch || row.revision != revision) return 0
            writes++; rows[viewer] = row.copy(ownerId = nextOwner, epoch = nextEpoch, revision = nextRevision)
            return 1
        }
        override fun replace(viewer: Int, owner: String, epoch: Long, revision: Long, nextRevision: Long, bytes: ByteArray): Int {
            val row = rows[viewer] ?: return 0
            if (row.ownerId != owner || row.epoch != epoch || row.revision != revision) return 0
            writes++; rows[viewer] = row.copy(revision = nextRevision, mctx = bytes.copyOf())
            return 1
        }
        companion object { const val OWNER = "00000000-0000-0000-0000-000000000001" }
    }

    companion object {
        private fun adapter() = CompetitionMahjongFormalBackupAdapter(Difficulty.entries.associateWith { it.code })
        private fun empty(viewer: Int) = CompetitionMahjongFormalSnapshotCodec.encode(CompetitionMahjongFormalSnapshot(viewer))
        private fun legacy(): GameBackupRecord {
            val checkpoint = formalCheckpoint(count = 0)
            return CompetitionMahjongBackupAdapter().encodeArchive(CompetitionMahjongMatchArchive(0, checkpoint.config.matchId,
                42, CompetitionMahjongMatchCheckpointCodec.encode(checkpoint)))
        }
    }
}
