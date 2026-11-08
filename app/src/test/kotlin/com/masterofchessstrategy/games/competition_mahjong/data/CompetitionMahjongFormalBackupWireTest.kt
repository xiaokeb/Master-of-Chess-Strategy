package com.masterofchessstrategy.games.competition_mahjong.data

import com.masterofchessstrategy.data.CompetitionMahjongMatchArchive
import com.masterofchessstrategy.data.GameBackupRecord
import com.masterofchessstrategy.games.competition_mahjong.learning.CompetitionMahjongLearningBackupAdapter
import com.masterofchessstrategy.games.competition_mahjong.learning.CompetitionMahjongLearningProof
import com.masterofchessstrategy.games.competition_mahjong.learning.CompetitionMahjongLearningProofCodec
import com.masterofchessstrategy.games.competition_mahjong.match.CompetitionMahjongMatchCheckpointCodec
import com.masterofchessstrategy.games.competition_mahjong.match.storage.CompetitionMahjongFormalSnapshot
import com.masterofchessstrategy.games.competition_mahjong.match.storage.CompetitionMahjongFormalSnapshotCodec
import com.masterofchessstrategy.games.competition_mahjong.match.storage.formalCheckpoint
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Test

/** Structural-only fixtures. No Native restore, DAO, install, or permission source is substituted. */
class CompetitionMahjongFormalBackupWireTest {
    @Test fun legacyOnlyRemainsAnExplicitBranchWithAllCompanionsPreserved() {
        val legacy = legacy()
        val learning = learning()
        val other = GameBackupRecord("GO_PROGRESS", listOf("1", "example"))
        val records = mutableListOf(other, legacy, learning)
        val result = wire.decodeContainer(records) as CompetitionMahjongFormalBackupCandidate.LegacyOnly
        records.clear()
        assertEquals(listOf(other, legacy, learning), result.records)
        assertEquals(legacy, result.legacyRecord)
        assertSame(learning, result.records.last())
        assertThrows(UnsupportedOperationException::class.java) { (result.records as MutableList).clear() }
    }

    @Test fun absenceOfBothKindsIsAFormalEmptyCandidateRatherThanLegacyOnly() {
        val learning = learning()
        val result = wire.decodeContainer(listOf(learning)) as CompetitionMahjongFormalBackupCandidate.Formal
        assertSame(learning, result.strictRecords.single())
        assertNull(result.retainedLegacyRecord)
        assertTrue((wire.decodeContainer(emptyList()) as CompetitionMahjongFormalBackupCandidate.Formal).strictRecords.isEmpty())
    }

    @Test fun oldThreeFieldFormalRecordsAreAcceptedOnlyWithoutLegacy() {
        val old = CompetitionMahjongFormalBackupBundle.encode(0, emptyBytes(0))
        assertEquals(old, wire.normalize(old))
        val result = wire.decodeContainer(listOf(old)) as CompetitionMahjongFormalBackupCandidate.Formal
        assertEquals(listOf(old), result.strictRecords)
        assertThrows(IllegalArgumentException::class.java) { wire.decodeContainer(listOf(old, legacy())) }
    }

    @Test fun newNoSourceWirePreservesItsVersionAndRoleUntilContainerSplit() {
        val record = wire.encode(0, emptyBytes(0))
        assertEquals(listOf("2", "1", "0", CompetitionMahjongFormalBackupBundle.encode(0, emptyBytes(0)).fields[2], "-"), record.fields)
        assertEquals(record, wire.normalize(record))
        val result = wire.decodeContainer(listOf(record)) as CompetitionMahjongFormalBackupCandidate.Formal
        assertEquals(3, result.strictRecords.single().fields.size)
        assertEquals(CompetitionMahjongFormalBackupBundle.encode(0, emptyBytes(0)), result.strictRecords.single())
        assertNull(result.retainedLegacyRecord)
    }

    @Test fun fourSeatsShareOneExplicitRetainedSourceAndKeepCandidateMclp() {
        val legacy = legacy()
        val learning = learning()
        val records = (3 downTo 0).map { viewer -> wire.encode(viewer, emptyBytes(viewer), legacy) }
        records.forEach { assertEquals(wire.retainedLegacySourceSha256(legacy), wire.normalize(it).fields[4]) }
        val result = wire.decodeContainer(records + listOf(learning, legacy)) as CompetitionMahjongFormalBackupCandidate.Formal
        assertEquals(legacy, result.retainedLegacyRecord)
        assertEquals(5, result.strictRecords.size)
        assertEquals(listOf("3", "2", "1", "0"), result.strictRecords.dropLast(1).map { it.fields[1] })
        assertSame(learning, result.strictRecords.last())
        assertTrue(result.strictRecords.none { it.kind == "COMPETITION_MATCH" })
    }

    @Test fun canonicalHashCoversKindEveryFieldAndTheFinalLf() {
        val legacy = legacy()
        val line = (listOf(legacy.kind) + legacy.fields).joinToString("|") + "\n"
        val digest = MessageDigest.getInstance("SHA-256").digest(line.toByteArray(Charsets.UTF_8)).hex()
        assertEquals(digest, wire.retainedLegacySourceSha256(legacy))
        assertNotEquals(digest, MessageDigest.getInstance("SHA-256").digest(line.dropLast(1).toByteArray()).hex())
        assertNotEquals(digest, wire.retainedLegacySourceSha256(legacy(timestamp = 43)))
        assertNotEquals(digest, wire.retainedLegacySourceSha256(legacy(id = 2)))
        assertNotEquals(digest, wire.retainedLegacySourceSha256(legacy(viewer = 1)))
        assertNotEquals(digest, wire.retainedLegacySourceSha256(legacy(count = 1)))
    }

    @Test fun oldAdapterNormalizationRunsBeforeHashingAndPreservesRetainedCheckpoint() {
        val canonical = legacy()
        val spelling = GameBackupRecord(canonical.kind, canonical.fields.toMutableList().also {
            it[0] = "+0002"; it[1] = "+001"; it[2] = "-0"; it[3] = it[3].uppercase()
            it[4] = "+00042"; it[5] = it[5].uppercase()
        })
        assertEquals(wire.retainedLegacySourceSha256(canonical), wire.retainedLegacySourceSha256(spelling))
        val formal = wire.encode(0, emptyBytes(0), spelling)
        val result = wire.decodeContainer(listOf(spelling, formal)) as CompetitionMahjongFormalBackupCandidate.Formal
        assertEquals(canonical, result.retainedLegacyRecord)
        assertEquals(canonical.fields[5], result.retainedLegacyRecord!!.fields[5])
        val only = wire.decodeContainer(listOf(spelling)) as CompetitionMahjongFormalBackupCandidate.LegacyOnly
        assertEquals(canonical, only.records.single())
    }

    @Test fun missingExtraAndIncorrectlyAssociatedSourcesAreRejected() {
        val legacy = legacy()
        val retained = wire.encode(0, emptyBytes(0), legacy)
        val none = wire.encode(0, emptyBytes(0))
        assertThrows(IllegalArgumentException::class.java) { wire.decodeContainer(listOf(retained)) }
        assertThrows(IllegalArgumentException::class.java) { wire.decodeContainer(listOf(none, legacy)) }
        assertThrows(IllegalArgumentException::class.java) { wire.decodeContainer(listOf(retained, legacy(timestamp = 43))) }
        assertThrows(IllegalArgumentException::class.java) { wire.decodeContainer(listOf(none, GameBackupRecord("COMPETITION_MATCH", listOf("bad")))) }
    }

    @Test fun inconsistentRolesAndOldNewMixCannotHideALegacyConflict() {
        val legacy = legacy()
        val retained = wire.encode(0, emptyBytes(0), legacy)
        val none = wire.encode(1, emptyBytes(1))
        val old = CompetitionMahjongFormalBackupBundle.encode(1, emptyBytes(1))
        assertThrows(IllegalArgumentException::class.java) { wire.decodeContainer(listOf(retained, none, legacy)) }
        assertThrows(IllegalArgumentException::class.java) { wire.decodeContainer(listOf(retained, old, legacy)) }
        val another = wire.encode(1, emptyBytes(1), legacy(timestamp = 43))
        assertThrows(IllegalArgumentException::class.java) { wire.decodeContainer(listOf(retained, another, legacy)) }
        // Without legacy, old wire's absent role and new '-' have the same meaning.
        assertEquals(2, (wire.decodeContainer(listOf(CompetitionMahjongFormalBackupBundle.encode(0, emptyBytes(0)), none))
            as CompetitionMahjongFormalBackupCandidate.Formal).strictRecords.size)
    }

    @Test fun duplicatedSeatsAndExcessFormalRecordsRejectDuringTheSplit() {
        val one = wire.encode(0, emptyBytes(0))
        val sameSeatOld = CompetitionMahjongFormalBackupBundle.encode(0, emptyBytes(0))
        for (records in listOf(listOf(one, one), listOf(one, sameSeatOld), List(5) { one })) {
            assertThrows(IllegalArgumentException::class.java) { wire.decodeContainer(records) }
        }
    }

    @Test fun moreThanOneLegacyRecordRejectsInBothBranches() {
        val legacy = legacy()
        for (records in listOf(listOf(legacy, legacy), listOf(legacy, legacy(id = 2)),
            listOf(wire.encode(0, emptyBytes(0), legacy), legacy, legacy))) {
            assertThrows(IllegalArgumentException::class.java) { wire.decodeContainer(records) }
        }
    }

    @Test fun fixedGameVersionViewerAndRoleSpellingAreValidatedBeforeReturningACandidate() {
        val good = wire.encode(0, emptyBytes(0))
        val invalid = listOf(
            0 to "3", 0 to "02", 0 to "+2", 1 to "0", 1 to "2", 1 to "01", 1 to "+1",
            2 to "-1", 2 to "4", 2 to "x", 2 to "1", 4 to "", 4 to "A".repeat(64),
            4 to "0".repeat(63), 4 to "0".repeat(65), 4 to "g".repeat(64),
        )
        invalid.forEach { (index, value) ->
            val record = GameBackupRecord(good.kind, good.fields.toMutableList().also { it[index] = value })
            assertThrows(IllegalArgumentException::class.java) { wire.decodeContainer(listOf(record)) }
        }
        assertThrows(IllegalArgumentException::class.java) { wire.normalize(GameBackupRecord("OTHER", good.fields)) }
    }

    @Test fun normalizationRetainsNewMetadataWhileCanonicalizingAcceptedViewerAndMctxSpellings() {
        val good = wire.encode(0, emptyBytes(0), legacy())
        val spelling = GameBackupRecord(good.kind, good.fields.toMutableList().also { it[2] = "+00"; it[3] = it[3].uppercase() })
        assertEquals(good, wire.normalize(spelling))
        assertEquals(5, wire.normalize(spelling).fields.size)
        assertEquals(good.fields[4], wire.normalize(spelling).fields[4])
    }

    @Test fun malformedFieldsPayloadAndWireBudgetAreRejected() {
        val good = wire.encode(0, emptyBytes(0))
        val invalid = listOf(good.fields.dropLast(1), good.fields + "extra", listOf("2", "0"),
            good.fields.toMutableList().also { it[3] = "0" }, good.fields.toMutableList().also { it[3] = "ff" },
            good.fields.toMutableList().also { it[3] = "00".repeat(CompetitionMahjongFormalSnapshotCodec.MAX_BYTES + 1) },
            good.fields.toMutableList().also { it[2] = "0".repeat(wire.MAXIMUM_RECORD_BYTES) })
        invalid.forEach { fields ->
            assertThrows(IllegalArgumentException::class.java) { wire.decodeContainer(listOf(GameBackupRecord(good.kind, fields))) }
        }
        assertTrue(wire.MAXIMUM_RECORD_BYTES >= 128 + 2 * CompetitionMahjongFormalSnapshotCodec.MAX_BYTES)
    }

    @Test fun returnedRecordsAndInputBytesCannotBeMutatedThroughThePreparedSplit() {
        val bytes = emptyBytes(0)
        val record = wire.encode(0, bytes)
        bytes.fill(0)
        val records = mutableListOf(record, learning())
        val result = wire.decodeContainer(records) as CompetitionMahjongFormalBackupCandidate.Formal
        records.clear()
        assertEquals(2, result.strictRecords.size)
        assertEquals(CompetitionMahjongFormalBackupBundle.encode(0, emptyBytes(0)), result.strictRecords.first())
        assertThrows(UnsupportedOperationException::class.java) { (result.strictRecords as MutableList).clear() }
        assertThrows(UnsupportedOperationException::class.java) { (result.strictRecords.first().fields as MutableList).clear() }
    }

    @Test fun allMclpCandidatesArePreservedForTheLaterStrictPreparerToValidate() {
        val learning = learning()
        val invalidLearning = GameBackupRecord(learning.kind, listOf("2", "bad"))
        val result = wire.decodeContainer(listOf(wire.encode(0, emptyBytes(0)), learning, invalidLearning))
            as CompetitionMahjongFormalBackupCandidate.Formal
        assertSame(learning, result.strictRecords[1])
        assertSame(invalidLearning, result.strictRecords[2])
    }

    private val wire get() = CompetitionMahjongFormalBackupWire

    private fun emptyBytes(viewer: Int) = CompetitionMahjongFormalSnapshotCodec.encode(CompetitionMahjongFormalSnapshot(viewer))

    private fun legacy(id: Int = 1, viewer: Int = 0, timestamp: Long = 42, count: Int = 0): GameBackupRecord {
        val checkpoint = formalCheckpoint(id = id, count = count, viewer = viewer)
        return CompetitionMahjongBackupAdapter().encodeArchive(CompetitionMahjongMatchArchive(viewer,
            checkpoint.config.matchId, timestamp, CompetitionMahjongMatchCheckpointCodec.encode(checkpoint)))
    }

    private fun learning() = CompetitionMahjongLearningBackupAdapter().encode(
        CompetitionMahjongLearningProofCodec.encode(CompetitionMahjongLearningProof()))

    private fun ByteArray.hex() = joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
}
