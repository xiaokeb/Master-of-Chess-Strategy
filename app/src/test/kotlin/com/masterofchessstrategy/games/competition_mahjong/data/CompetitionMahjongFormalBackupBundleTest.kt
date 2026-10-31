package com.masterofchessstrategy.games.competition_mahjong.data

import androidx.room.InvalidationTracker
import androidx.room.RoomDatabase
import com.masterofchessstrategy.data.GameBackupRecord
import com.masterofchessstrategy.engine.Difficulty
import com.masterofchessstrategy.engine.GameType
import com.masterofchessstrategy.games.competition_mahjong.match.policy.CompetitionMahjongPlayPolicy
import com.masterofchessstrategy.games.competition_mahjong.match.policy.CompetitionMahjongUnlockProgress
import com.masterofchessstrategy.games.competition_mahjong.match.storage.*
import com.masterofchessstrategy.games.competition_mahjong.match.storage.room.*
import org.junit.Assert.*
import org.junit.Test

/** Room/DAO doubles only; no SQLite or Native execution. Accepted candidates have no active hand. */
class CompetitionMahjongFormalBackupBundleTest {
    @Test fun missingSeatsAreAdmittedAsFourExplicitEmptyFacts() {
        val policies = mutableListOf<Int>()
        val plan = CompetitionMahjongFormalBackupBundle.prepareReplacement(emptyList()) {
            policies += it
            formalPolicy()
        }
        assertEquals((0..3).toList(), policies)
        assertEquals((0..3).toList(), plan.candidates.map { it.viewerPlayerId })
        plan.candidates.forEach { candidate ->
            val snapshot = CompetitionMahjongFormalSnapshotCodec.decode(candidate.mctx, candidate.viewerPlayerId)!!
            assertNull(snapshot.active)
            assertTrue(snapshot.deletedMatchIds.isEmpty())
            assertEquals(0, snapshot.statistics.seriesCount)
        }
    }

    @Test fun historyFavoritesStatisticsAndDeletionSurviveTogether() {
        val first = formalCheckpoint()
        val second = formalCheckpoint(id = 2)
        val complete = CompetitionMahjongFormalSnapshot(0).save(first, formalPolicy(), 100)
            .favorite(first.config.matchId, true).save(second, formalPolicy(), 200).delete(second.config.matchId)
        // History-only is legitimate and requires no Native engine for admission.
        val snapshot = CompetitionMahjongFormalSnapshot(0, statistics = complete.statistics,
            history = complete.history, deletedMatchIds = complete.deletedMatchIds)
        val bytes = CompetitionMahjongFormalSnapshotCodec.encode(snapshot)
        val record = CompetitionMahjongFormalBackupBundle.encode(0, bytes)
        val candidate = CompetitionMahjongFormalBackupBundle.prepareReplacement(listOf(record)) { formalPolicy() }.candidates[0]
        assertArrayEquals(bytes, candidate.mctx)
        val restored = CompetitionMahjongFormalSnapshotCodec.decode(candidate.mctx, 0)!!
        assertEquals(2, restored.statistics.seriesCount)
        assertTrue(restored.history.entries.single().favorite)
        assertEquals(setOf(second.config.matchId), restored.deletedMatchIds)
    }

    @Test fun allFourOwnedSeatsArePreservedAndUnrelatedGamesAreIgnored() {
        val records = (3 downTo 0).map { viewer ->
            CompetitionMahjongFormalBackupBundle.encode(viewer, emptyBytes(viewer))
        } + GameBackupRecord("GO_SESSION", listOf("unrelated"))
        val plan = CompetitionMahjongFormalBackupBundle.prepareReplacement(records) { formalPolicy() }
        plan.candidates.forEach { assertArrayEquals(emptyBytes(it.viewerPlayerId), it.mctx) }
    }

    @Test fun legacySourceCannotBeSilentlyReplacedWithAnEmptyFormalLibrary() {
        val old = GameBackupRecord("COMPETITION_MATCH", listOf("old"))
        assertThrows(IllegalArgumentException::class.java) {
            CompetitionMahjongFormalBackupBundle.prepareReplacement(listOf(old)) { error("policy must not run") }
        }
        assertThrows(IllegalArgumentException::class.java) {
            CompetitionMahjongFormalBackupBundle.prepareReplacement(listOf(old,
                CompetitionMahjongFormalBackupBundle.encode(0, emptyBytes(0)))) { error("policy must not run") }
        }
    }

    @Test fun duplicateSeatAndExcessRecordsFailBeforePermissionsOrAdmission() {
        val record = CompetitionMahjongFormalBackupBundle.encode(0, emptyBytes(0))
        for (records in listOf(listOf(record, record), List(5) { record })) {
            assertThrows(IllegalArgumentException::class.java) {
                CompetitionMahjongFormalBackupBundle.prepareReplacement(records) { error("policy must not run") }
            }
        }
    }

    @Test fun invalidLastSeatDoesNotYieldAPartialRestorePlan() {
        val records = (0..3).map { CompetitionMahjongFormalBackupBundle.encode(it, emptyBytes(it)) }.toMutableList()
        records[3] = GameBackupRecord(CompetitionMahjongFormalBackupBundle.KIND, listOf("2", "3", "ff"))
        assertThrows(IllegalArgumentException::class.java) {
            CompetitionMahjongFormalBackupBundle.prepareReplacement(records) { error("policy must not run") }
        }
    }

    @Test fun foreignSeatCorruptHexWrongFieldsAndOversizeAreRejected() {
        val hex = CompetitionMahjongFormalBackupBundle.encode(0, emptyBytes(0)).fields[2]
        val invalid = listOf(
            listOf("2", "1", hex), listOf("2", "4", hex), listOf("2", "-1", hex), listOf("2", "x", hex),
            listOf("2", "0", ""), listOf("2", "0", "0"), listOf("2", "0", "gg"), listOf("2", "0", hex, "extra"),
            listOf("2", "0", "00".repeat(CompetitionMahjongFormalSnapshotCodec.MAX_BYTES + 1)),
        )
        invalid.forEach { fields ->
            assertThrows(IllegalArgumentException::class.java) {
                CompetitionMahjongFormalBackupBundle.normalize(GameBackupRecord(CompetitionMahjongFormalBackupBundle.KIND, fields))
            }
        }
    }

    @Test fun normalizedWireDoesNotRewriteMctxAndOwnsNoFencingMetadata() {
        val canonical = CompetitionMahjongFormalBackupBundle.encode(0, emptyBytes(0))
        val legacySpelling = GameBackupRecord(canonical.kind, listOf("2", "+00", canonical.fields[2].uppercase()))
        assertEquals(canonical, CompetitionMahjongFormalBackupBundle.normalize(legacySpelling))
        assertEquals(3, canonical.fields.size)
        assertTrue(canonical.encodedSizeBytes <= CompetitionMahjongFormalBackupBundle.MAXIMUM_RECORD_BYTES)
    }

    @Test fun callerMutationCannotAlterPreparedReplacement() {
        val bytes = emptyBytes(2)
        val records = mutableListOf(CompetitionMahjongFormalBackupBundle.encode(2, bytes))
        val plan = CompetitionMahjongFormalBackupBundle.prepareReplacement(records) { formalPolicy() }
        bytes.fill(0)
        records.clear()
        plan.candidates[2].mctx.fill(0)
        assertArrayEquals(emptyBytes(2), plan.candidates[2].mctx)
        @Suppress("UNCHECKED_CAST")
        assertThrows(UnsupportedOperationException::class.java) { (plan.candidates as MutableList).clear() }
    }

    @Test fun lockedActiveDifficultyFailsBeforeNativeIsCreated() {
        val snapshot = CompetitionMahjongFormalSnapshot(0).save(formalCheckpoint(count = 0), formalPolicy(), 100)
        val record = CompetitionMahjongFormalBackupBundle.encode(0, CompetitionMahjongFormalSnapshotCodec.encode(snapshot))
        val easyOnly = CompetitionMahjongPlayPolicy.fromProgress(CompetitionMahjongUnlockProgress(true),
            Difficulty.entries.associateWith { it.code })
        assertThrows(IllegalArgumentException::class.java) {
            CompetitionMahjongFormalBackupBundle.prepareReplacement(listOf(record)) { easyOnly }
        }
    }

    @Test fun everySeatUsesTheSharedGameOwnershipPrefixAndThreeFields() {
        for (viewer in 0..3) {
            val record = CompetitionMahjongFormalBackupBundle.encode(viewer, emptyBytes(viewer))
            assertEquals(GameType.STANDARD_MAHJONG.code.toString(), record.fields[0])
            assertEquals(viewer.toString(), record.fields[1])
            assertEquals(3, record.fields.size)
            assertEquals(record, CompetitionMahjongFormalBackupBundle.normalize(record))
        }
    }

    @Test fun unpublishedTwoFieldWireCannotEnterRestoreOrNormalization() {
        val canonical = CompetitionMahjongFormalBackupBundle.encode(0, emptyBytes(0))
        val old = GameBackupRecord(canonical.kind, canonical.fields.drop(1))
        assertThrows(IllegalArgumentException::class.java) { CompetitionMahjongFormalBackupBundle.normalize(old) }
        assertThrows(IllegalArgumentException::class.java) {
            CompetitionMahjongFormalBackupBundle.prepareReplacement(listOf(old)) { error("policy must not run") }
        }
    }

    @Test fun wrongOrNoncanonicalGameCodeIsRejectedBeforePermissionReads() {
        val canonical = CompetitionMahjongFormalBackupBundle.encode(0, emptyBytes(0))
        for (game in listOf("0", "1", "3", "4", "5", "02", "+2", "x")) {
            val wrong = GameBackupRecord(canonical.kind, listOf(game) + canonical.fields.drop(1))
            assertThrows(IllegalArgumentException::class.java) { CompetitionMahjongFormalBackupBundle.normalize(wrong) }
            assertThrows(IllegalArgumentException::class.java) {
                CompetitionMahjongFormalBackupBundle.prepareReplacement(listOf(wrong)) { error("policy must not run") }
            }
        }
    }

    @Test fun recordBudgetIncludesTheOwnershipAndViewerFieldsBeforeAllocation() {
        val canonical = CompetitionMahjongFormalBackupBundle.encode(0, emptyBytes(0))
        val oversized = GameBackupRecord(canonical.kind, listOf("2",
            "0".repeat(CompetitionMahjongFormalBackupBundle.MAXIMUM_RECORD_BYTES), canonical.fields[2]))
        assertTrue(oversized.encodedSizeBytes > CompetitionMahjongFormalBackupBundle.MAXIMUM_RECORD_BYTES)
        assertThrows(IllegalArgumentException::class.java) { CompetitionMahjongFormalBackupBundle.normalize(oversized) }
    }

    @Test fun readsActualEnumeratedSeatsAndReturnsCanonicalViewerOrder() {
        val rows = EnumeratedRows(listOf(3, 1), mapOf(1 to emptyBytes(1), 3 to emptyBytes(3)))
        val records = CompetitionMahjongFormalBackupBundle.readInTransaction(TransactionRoom(true), rows)
        assertEquals(listOf("1", "3"), records.map { it.fields[1] })
        assertEquals(2, rows.findCalls)
        assertEquals(0, rows.writes)
    }

    @Test fun unknownOrDuplicatePhysicalViewersFailBeforeAnyPayloadRead() {
        for (keys in listOf(listOf(0, 9), listOf(-1, 0), listOf(0, 0), listOf(0, 1, 2, 3, 9))) {
            val rows = EnumeratedRows(keys, mapOf(0 to emptyBytes(0)))
            assertThrows(IllegalArgumentException::class.java) {
                CompetitionMahjongFormalBackupBundle.readInTransaction(TransactionRoom(true), rows)
            }
            assertEquals(0, rows.findCalls)
            assertEquals(0, rows.writes)
        }
    }

    @Test fun enumeratedMissingRowCannotBeSilentlyExportedAsEmpty() {
        val rows = EnumeratedRows(listOf(0))
        assertThrows(IllegalArgumentException::class.java) {
            CompetitionMahjongFormalBackupBundle.readInTransaction(TransactionRoom(true), rows)
        }
        assertEquals(1, rows.findCalls)
    }

    @Test fun emptyReadStillRequiresTheCallersExistingTransaction() {
        val rows = EnumeratedRows(emptyList())
        assertThrows(IllegalStateException::class.java) {
            CompetitionMahjongFormalBackupBundle.readInTransaction(TransactionRoom(false), rows)
        }
        assertEquals(0, rows.keyReads)
    }

    @Test fun installRejectsUnknownActualViewerBeforeAnyLeaseOrPayloadWrite() {
        val plan = CompetitionMahjongFormalBackupBundle.prepareReplacement(emptyList()) { formalPolicy() }
        val rows = EnumeratedRows(listOf(0, 9), mapOf(0 to emptyBytes(0)))
        assertThrows(IllegalArgumentException::class.java) { plan.installInTransaction(TransactionRoom(true), rows) }
        assertEquals(0, rows.findCalls)
        assertEquals(0, rows.writes)
    }

    @Test fun installRequiresTheCallersTransactionBeforeScanningOrWriting() {
        val plan = CompetitionMahjongFormalBackupBundle.prepareReplacement(emptyList()) { formalPolicy() }
        val rows = EnumeratedRows(emptyList())
        assertThrows(IllegalStateException::class.java) { plan.installInTransaction(TransactionRoom(false), rows) }
        assertEquals(0, rows.keyReads)
        assertEquals(0, rows.writes)
    }

    private class TransactionRoom(private val active: Boolean) : RoomDatabase() {
        override fun inTransaction() = active
        override fun createInvalidationTracker(): InvalidationTracker = error("No real database")
        override fun clearAllTables(): Unit = error("No clearing in JVM tests")
    }

    private class EnumeratedRows(private val keys: List<Int>, private val payloads: Map<Int, ByteArray> = emptyMap()) :
        CompetitionMahjongFormalRoomDao {
        var keyReads = 0
        var findCalls = 0
        var writes = 0
        override fun listViewerIds(): List<Int> { keyReads++; return keys }
        override fun find(viewer: Int): CompetitionMahjongFormalRoomMetadata? {
            findCalls++
            return payloads[viewer]?.let { CompetitionMahjongFormalRoomMetadata(viewer, OWNER, 1, 0, it.size) }
        }
        override fun readChunk(viewer: Int, owner: String, epoch: Long, revision: Long, offset: Int, count: Int): ByteArray? =
            payloads[viewer]?.copyOfRange(offset - 1, offset - 1 + count)
        override fun insert(row: CompetitionMahjongFormalRoomRow) { writes++; error("Unexpected insert") }
        override fun claim(viewer: Int, owner: String, epoch: Long, revision: Long,
                           nextOwner: String, nextEpoch: Long, nextRevision: Long): Int { writes++; error("Unexpected claim") }
        override fun replace(viewer: Int, owner: String, epoch: Long, revision: Long,
                             nextRevision: Long, bytes: ByteArray): Int { writes++; error("Unexpected replacement") }

        companion object { private const val OWNER = "00000000-0000-0000-0000-000000000001" }
    }

    private fun emptyBytes(viewer: Int) = CompetitionMahjongFormalSnapshotCodec.encode(CompetitionMahjongFormalSnapshot(viewer))
}
