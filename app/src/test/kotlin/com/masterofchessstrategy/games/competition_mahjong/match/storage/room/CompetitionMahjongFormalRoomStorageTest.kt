package com.masterofchessstrategy.games.competition_mahjong.match.storage.room

import com.masterofchessstrategy.games.competition_mahjong.match.storage.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

/** In-memory DAO/transaction doubles: these tests do not execute SQLite or Native. */
class CompetitionMahjongFormalRoomStorageTest {
    @Test fun acquisitionPreservesPayloadAndPermanentlyFencesPreviousOwner() {
        val db = MemoryRows()
        val first = db.acquire()
        assertNull(first.read())
        first.replace(byteArrayOf(1, 2))
        val second = db.acquire()
        assertSame(first.mutex, second.mutex)
        assertArrayEquals(byteArrayOf(1, 2), second.read())
        rejects { first.read() }
        rejects { first.replace(byteArrayOf(3)) }
        assertArrayEquals(byteArrayOf(1, 2), second.read())
        assertEquals(2L, db.row!!.epoch)
    }

    @Test fun revisionConflictCannotOverwriteAnotherCommit() {
        val db = MemoryRows()
        val storage = db.acquire()
        storage.read()
        db.row = db.row!!.copy(revision = 8, mctx = byteArrayOf(8))
        rejects { storage.replace(byteArrayOf(9)) }
        assertArrayEquals(byteArrayOf(8), db.row!!.mctx)
    }

    @Test fun failureBeforeCommitRollsBackEntireRow() {
        val db = MemoryRows()
        val storage = db.acquire()
        storage.read()
        storage.replace(byteArrayOf(1))
        storage.read()
        db.failBeforeCommit = true
        rejects { storage.replace(byteArrayOf(2)) }
        assertArrayEquals(byteArrayOf(1), db.row!!.mctx)
        assertEquals(1L, db.row!!.revision)
        db.failBeforeCommit = false
        storage.read()
        storage.replace(byteArrayOf(2))
        assertArrayEquals(byteArrayOf(2), db.row!!.mctx)
    }

    @Test fun missingLeaseIsFailureRatherThanMissingGameOrAutomaticReclaim() {
        val db = MemoryRows()
        val storage = db.acquire()
        db.row = null
        rejects { storage.read() }
        assertNull(db.row)
    }

    @Test fun overflowCannotChangePersistedOwner() {
        val db = MemoryRows()
        db.acquire()
        db.row = db.row!!.copy(epoch = Long.MAX_VALUE)
        val before = db.row!!
        try { db.acquire(); fail("overflow expected") } catch (_: ArithmeticException) { }
        assertEquals(before, db.row)
    }

    @Test fun inputAndOutputBytesAreDefensiveCopies() {
        val db = MemoryRows()
        val storage = db.acquire()
        storage.read()
        val value = byteArrayOf(1)
        storage.replace(value)
        value[0] = 3
        val loaded = storage.read()!!
        loaded[0] = 4
        assertArrayEquals(byteArrayOf(1), storage.read())
    }

    @Test fun maximumPayloadUsesBoundedChunksInOneReadTransaction() {
        val db = MemoryRows()
        val storage = db.acquire()
        storage.read()
        val bytes = ByteArray(CompetitionMahjongFormalSnapshotCodec.MAX_BYTES) { (it % 251).toByte() }
        storage.replace(bytes)
        val transactions = db.transactions
        assertArrayEquals(bytes, storage.read())
        assertEquals(transactions + 1, db.transactions)
        assertEquals(CompetitionMahjongFormalRoomStorage.READ_CHUNK_BYTES, db.maximumChunk)
    }

    @Test fun incompleteChunkCannotPublishPartialPayload() {
        val db = MemoryRows()
        val storage = db.acquire()
        storage.read()
        storage.replace(byteArrayOf(1, 2))
        db.truncateChunk = true
        rejects { storage.read() }
        assertArrayEquals(byteArrayOf(1, 2), db.row!!.mctx)
    }

    @Test fun repositoryCommitsActiveStatisticsHistoryAndDeletionTogether() = runBlocking {
        val db = MemoryRows()
        val storage = db.acquire()
        val repository = repository(storage)
        val completed = formalCheckpoint()
        repository.matchStore.save(completed)
        val snapshot = CompetitionMahjongFormalSnapshotCodec.decode(db.row!!.mctx!!, 0)!!
        assertEquals(16, snapshot.active!!.ledger.completedHandCount)
        assertEquals(1, snapshot.statistics.seriesCount)
        assertEquals(1, snapshot.history.entries.size)
        repository.historyStore.delete(0, completed.config.matchId)
        repository.matchStore.save(completed)
        val reopened = repository(db.acquire())
        assertEquals(1, reopened.statisticsStore.load(0).seriesCount)
        assertTrue(reopened.historyStore.load(0).entries.isEmpty())
        assertTrue(CompetitionMahjongFormalSnapshotCodec.decode(db.row!!.mctx!!, 0)!!
            .deletedMatchIds.contains(completed.config.matchId))
    }

    @Test fun terminalWithdrawalAndAcknowledgementLossAreOneFact() = runBlocking {
        val db = MemoryRows()
        val repository = repository(db.acquire())
        repository.matchStore.save(formalCheckpoint())
        db.failAfterCommit = true
        try { repository.matchStore.save(formalCheckpoint(count = 15)); fail("ack expected") }
        catch (_: IOException) { }
        db.failAfterCommit = false
        repository.matchStore.save(formalCheckpoint(count = 15))
        val row = CompetitionMahjongFormalSnapshotCodec.decode(db.row!!.mctx!!, 0)!!
        assertEquals(15, row.active!!.ledger.completedHandCount)
        assertEquals(0, row.statistics.seriesCount)
        assertTrue(row.history.entries.isEmpty())
    }

    @Test fun nativeAdmissionFailureDoesNotWriteAnyProjection() = runBlocking {
        val db = MemoryRows()
        val repository = CompetitionMahjongFormalRepository(db.acquire(), 0, formalPolicy(),
            { throw IOException("Native rejected") }, dispatcher = Dispatchers.Unconfined)
        try { repository.matchStore.save(formalCheckpoint()); fail("admission expected") }
        catch (_: IOException) { }
        assertNull(db.row!!.mctx)
        assertEquals(0L, db.row!!.revision)
    }

    @Test fun explicitMigrationPreservesLegacySourceAndRefusesDifferentTarget() = runBlocking {
        val db = MemoryRows()
        val repository = repository(db.acquire())
        val checkpoint = formalCheckpoint(count = 0)
        val legacy = com.masterofchessstrategy.games.competition_mahjong.match.CompetitionMahjongMatchCheckpointCodec.encode(checkpoint)
        val original = legacy.copyOf()
        val source = CompetitionMahjongFormalMigrationSource(0, legacy, null, null, false, 0)
        val preview = repository.migration.preview { source }
        assertTrue(repository.migration.migrate(preview.sourceSha256) { source }.wasInstalled)
        assertArrayEquals(original, legacy)
        assertFalse(repository.migration.migrate(preview.sourceSha256) { source }.wasInstalled)
        repository.matchStore.save(formalCheckpoint(id = 2, count = 0))
        val before = db.row!!.mctx!!.copyOf()
        try { repository.migration.migrate(preview.sourceSha256) { source }; fail("conflict expected") }
        catch (_: IllegalArgumentException) { }
        assertArrayEquals(before, db.row!!.mctx)
    }

    @Test fun admittedBackupReplacesPayloadAndFencesOldWriterInsideOuterTransaction() = runBlocking {
        val db = MemoryRows()
        val old = db.acquire()
        old.read()
        old.replace(byteArrayOf(7))
        val bytes = CompetitionMahjongFormalSnapshotCodec.encode(CompetitionMahjongFormalSnapshot(0))
        val candidate = CompetitionMahjongFormalRoomBackup.admit(0, bytes, formalPolicy())
        bytes[0] = 0
        val ownerBefore = db.row!!.ownerId
        var restored: CompetitionMahjongFormalRoomStorage? = null
        val transactionsBefore = db.transactions
        db.run {
            restored = CompetitionMahjongFormalRoomStorage.installBackupInCurrentTransaction(db, db, candidate)
        }
        assertEquals(transactionsBefore + 1, db.transactions)
        assertNotEquals(ownerBefore, db.row!!.ownerId)
        assertEquals(2L, db.row!!.epoch)
        assertNotNull(CompetitionMahjongFormalSnapshotCodec.decode(restored!!.read()!!, 0))
        rejects { old.replace(byteArrayOf(9)) }
        assertArrayEquals(candidate.mctx, db.row!!.mctx)
    }

    @Test fun restoreRollbackPreservesPayloadAndOldLease() = runBlocking {
        val db = MemoryRows()
        val old = db.acquire()
        old.read()
        old.replace(byteArrayOf(7))
        val candidate = CompetitionMahjongFormalRoomBackup.admit(0,
            CompetitionMahjongFormalSnapshotCodec.encode(CompetitionMahjongFormalSnapshot(0)), formalPolicy())
        val ownerBefore = db.row!!.ownerId
        db.failBeforeCommit = true
        rejects {
            db.run { CompetitionMahjongFormalRoomStorage.installBackupInCurrentTransaction(db, db, candidate) }
        }
        db.failBeforeCommit = false
        assertEquals(ownerBefore, db.row!!.ownerId)
        assertEquals(1L, db.row!!.epoch)
        assertArrayEquals(byteArrayOf(7), old.read())
    }

    @Test fun corruptAndCrossViewerBackupsCannotBecomeRestoreCandidates() = runBlocking {
        val valid = CompetitionMahjongFormalSnapshotCodec.encode(CompetitionMahjongFormalSnapshot(0))
        for ((viewer, bytes) in listOf(1 to valid, 0 to valid.copyOf().also { it[0] = 0 })) {
            try { CompetitionMahjongFormalRoomBackup.admit(viewer, bytes, formalPolicy()); fail("invalid expected") }
            catch (_: IOException) { }
        }
    }

    private fun repository(storage: CompetitionMahjongFormalRoomStorage) =
        CompetitionMahjongFormalRepository(storage, 0, formalPolicy(), {}, { 100 }, Dispatchers.Unconfined)

    private fun rejects(action: () -> Unit) {
        try { action(); fail("IOException expected") } catch (_: IOException) { }
    }

    private class MemoryRows : CompetitionMahjongFormalRoomDao, CompetitionMahjongFormalRoomTransaction {
        var row: CompetitionMahjongFormalRoomRow? = null
        var failBeforeCommit = false
        var failAfterCommit = false
        var transactions = 0
        var maximumChunk = 0
        var truncateChunk = false
        fun acquire() = CompetitionMahjongFormalRoomStorage.acquire(this, this, 0)
        override fun listViewerIds(): List<Int> = row?.let { listOf(it.viewerPlayerId) } ?: emptyList()
        override fun run(action: () -> Unit) {
            transactions++
            val before = row?.copy(mctx = row?.mctx?.copyOf())
            try {
                action()
                if (failBeforeCommit && row?.revision != before?.revision) throw IOException("before SQLite commit (double)")
            } catch (failure: Exception) { row = before; throw failure }
            if (failAfterCommit && row?.revision != before?.revision) throw IOException("commit acknowledgement lost (double)")
        }
        override fun find(viewer: Int) = row?.takeIf { it.viewerPlayerId == viewer }?.let {
            CompetitionMahjongFormalRoomMetadata(it.viewerPlayerId, it.ownerId, it.epoch, it.revision, it.mctx?.size)
        }
        override fun readChunk(viewer: Int, owner: String, epoch: Long, revision: Long, offset: Int, count: Int): ByteArray? {
            maximumChunk = maxOf(maximumChunk, count)
            val current = row?.takeIf { it.viewerPlayerId == viewer && it.ownerId == owner && it.epoch == epoch && it.revision == revision }
                ?: return null
            val bytes = current.mctx ?: return null
            return bytes.copyOfRange(offset - 1, offset - 1 + count - if (truncateChunk) 1 else 0)
        }
        override fun insert(row: CompetitionMahjongFormalRoomRow) { check(this.row == null); this.row = row }
        override fun claim(viewer: Int, owner: String, epoch: Long, revision: Long,
                           nextOwner: String, nextEpoch: Long, nextRevision: Long): Int {
            val current = row?.takeIf { it.viewerPlayerId == viewer && it.ownerId == owner && it.epoch == epoch && it.revision == revision } ?: return 0
            row = current.copy(ownerId = nextOwner, epoch = nextEpoch, revision = nextRevision)
            return 1
        }
        override fun replace(viewer: Int, owner: String, epoch: Long, revision: Long,
                             nextRevision: Long, bytes: ByteArray): Int {
            val current = row?.takeIf { it.viewerPlayerId == viewer && it.ownerId == owner && it.epoch == epoch && it.revision == revision } ?: return 0
            row = current.copy(revision = nextRevision, mctx = bytes.copyOf())
            return 1
        }
    }
}
