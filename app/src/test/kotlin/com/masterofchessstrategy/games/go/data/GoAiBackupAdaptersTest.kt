package com.masterofchessstrategy.games.go.data

import com.masterofchessstrategy.data.*
import com.masterofchessstrategy.engine.GameResult
import com.masterofchessstrategy.engine.GameType
import com.masterofchessstrategy.engine.GoPhase
import com.masterofchessstrategy.engine.GoPlayer
import com.masterofchessstrategy.games.go.*
import org.junit.Assert.*
import org.junit.Test

/** Structural/ownership/relationship checks use an admission double, not legal Native histories. */
class GoAiBackupAdaptersTest {
    private val pair = createGoAiBackupAdapters(::admit)
    private val session = pair.filterIsInstance<GoAiSessionBackupAdapter>().single()
    private val archive = pair.filterIsInstance<GoAiArchiveBackupAdapter>().single()
    private val lineage = GoAiCheckpointLineage(GoAiCheckpointOrigin.UNVERIFIED_SESSION)
    private fun match(id: String = "backup/一", terminal: Boolean = false) = GoAiSavedMatch(id,
        GoAiMatchConfig(13, 5, null, GoAiDifficulty.HARD), byteArrayOf(if (terminal) 1 else 0, 5, 3),
        undoUsed = 1, hintUsed = 1, rememberedHuman = GoPlayer.WHITE, automaticUsed = true,
        openingBytes = byteArrayOf(1, 4), autoContinueEnabled = true, autoContinueMaxGames = 7, autoContinueGameNumber = 3)
    private fun admit(saved: GoAiSavedMatch) = GoAdmittedAiCheckpoint(saved,
        if (saved.copyBytes()[0] == 1.toByte()) GoPhase.FINISHED else GoPhase.PLAYING,
        if (saved.copyBytes()[0] == 1.toByte()) GameResult.DRAW else GameResult.ONGOING, 3, 5, 1)
    private fun activeWire(saved: GoAiSavedMatch = match(), source: GoAiCheckpointLineage = lineage) =
        session.encodeRows(listOf(GoAiCheckpointRows.active(
            GoAiCheckpointEntry(GoAiMatchFileCodec.encode(saved), source), 19))).single()
    private fun archiveWire(saved: GoAiSavedMatch = match(terminal = true), source: GoAiCheckpointLineage = lineage) =
        archive.encodeRows(listOf(GoAiCheckpointRows.archive(GoAiCheckpointArchive(admit(saved).recordId,
            GoAiCheckpointEntry(GoAiMatchFileCodec.encode(saved), source), 11)))).single()

    @Test fun twoRawSlotsUseVersionFiveAndNeverClaimSharedGameRecords() {
        val registry = GameBackupRegistry(pair)
        registry.requireSupportedSlots(setOf(BackupStorageSlot(BackupStorageTable.GO_AI_SESSION, GameType.GO),
            BackupStorageSlot(BackupStorageTable.GO_AI_ARCHIVE, GameType.GO)))
        assertEquals(5, registry.containerVersion(listOf(activeWire())))
        val prepared = registry.prepareRestore(listOf(activeWire()), 5).single()
        assertTrue(prepared.completedRecordIds.isEmpty())
        assertEquals("GO_AI_SESSION", session.descriptor.kind)
        assertEquals("GO_AI_ARCHIVE", archive.descriptor.kind)
        assertEquals(1, session.descriptor.maximumRecords)
        assertEquals(200_000, archive.descriptor.maximumRecords)
        rejects { registry.validate(listOf(activeWire()), 4) }
    }

    @Test fun activeRoundTripKeepsAllGaiFieldsSourceTimeAndOwnedBytes() {
        val saved = match()
        val prepared = session.prepareRestore(activeWire(saved)) as PreparedGoAiSessionBackup
        assertEquals(19L, prepared.updatedAtEpochMillis)
        assertEquals(lineage, prepared.lineage)
        assertArrayEquals(GoAiMatchFileCodec.encode(saved), GoAiMatchFileCodec.encode(prepared.admitted.match()))
        prepared.admitted.match().copyBytes().fill(0)
        prepared.admitted.match().copyOpeningBytes()!!.fill(0)
        assertArrayEquals(saved.copyBytes(), prepared.admitted.match().copyBytes())
        assertEquals(GoPlayer.WHITE, prepared.admitted.match().rememberedHuman)
        assertEquals(7, prepared.admitted.match().autoContinueMaxGames)
        assertEquals(3, prepared.admitted.match().autoContinueGameNumber)
    }

    @Test fun explicitOriginCodesRoundTripWithoutInventingEligibility() {
        val parent = "a".repeat(64)
        val cases = listOf(GoAiCheckpointLineage(GoAiCheckpointOrigin.LEGACY_UNVERIFIED) to "1",
            lineage to "2", GoAiCheckpointLineage(GoAiCheckpointOrigin.TERMINAL_CONTINUATION, parent) to "3")
        for ((source, code) in cases) {
            val wire = activeWire(source = source)
            assertEquals(code, wire.fields[3])
            assertEquals(source, (session.prepareRestore(wire) as PreparedGoAiSessionBackup).lineage)
        }
    }

    @Test fun archivePreservesItsFirstFullEnvelopeAndTimeWithoutClaimingAProjection() {
        val saved = match(terminal = true)
        val prepared = GameBackupRegistry(pair).prepareRestore(listOf(archiveWire(saved)), 5)
            .single() as PreparedGoAiArchiveBackup
        assertEquals(11L, prepared.completedAtEpochMillis)
        assertEquals(admit(saved).recordId, prepared.recordId)
        assertTrue(prepared.completedRecordIds.isEmpty())
        assertArrayEquals(GoAiMatchFileCodec.encode(saved), GoAiMatchFileCodec.encode(prepared.admitted.match()))
    }

    @Test fun strictNumericOriginParentHexAndFieldAliasesAreRejected() {
        val active = activeWire()
        for ((index, value) in listOf(0 to "99", 1 to "01", 2 to "019", 2 to "+19", 2 to "-1",
            3 to "0", 3 to "01", 3 to "99", 3 to "3", 4 to "a".repeat(64), 5 to "gg", 5 to "a")) {
            rejects { session.validate(replace(active, index, value), 5) }
        }
        rejects { session.validate(replace(active, 5, active.fields[5].uppercase()), 5) }
        rejects { session.validate(GameBackupRecord(active.kind, active.fields.dropLast(1)), 5) }
        val record = archiveWire()
        for ((index, value) in listOf(2 to "A".repeat(64), 2 to "a".repeat(63), 3 to "-1",
            4 to "03", 5 to "invalid", 6 to "")) rejects { archive.validate(replace(record, index, value), 5) }
        rejects { archive.prepareRestore(replace(record, 2, "a".repeat(64))) }
        rejects { archive.validate(replace(replace(record, 4, "3"), 5, record.fields[2]), 5) }
    }

    @Test fun fullTwoNativeImageEnvelopeBudgetIsAcceptedWithoutHistoryTruncation() {
        val saved = GoAiSavedMatch("x".repeat(128), GoAiMatchConfig(19), ByteArray(1_000_000) { 0 },
            openingBytes = ByteArray(1_000_000) { 2 })
        val bytes = GoAiMatchFileCodec.encode(saved)
        assertEquals(2_000_172, bytes.size)
        val wire = activeWire(saved)
        session.validate(wire, 5)
        assertEquals(bytes.size * 2, wire.fields.last().length)
        assertTrue(wire.encodedSizeBytes <= session.descriptor.maximumRecordBytes)
        assertArrayEquals(bytes, GoAiMatchFileCodec.encode(
            (session.prepareRestore(wire) as PreparedGoAiSessionBackup).admitted.match()))
        rejects { session.validate(replace(wire, 5, wire.fields.last() + "00"), 5) }
    }

    @Test fun noNativeWorkDuringStructuralValidationButPrepareRejectsNativeFailure() {
        var calls = 0
        val rejected = createGoAiBackupAdapters { calls++; throw IllegalArgumentException("Native rejected") }
            .filterIsInstance<GoAiSessionBackupAdapter>().single()
        val wire = activeWire()
        rejected.validate(wire, 5)
        assertEquals(0, calls)
        rejects { rejected.prepareRestore(wire) }
        assertEquals(1, calls)
        val corrupt = GoAiMatchFileCodec.encode(match()).apply { this[lastIndex] = 99 }
        rejects { rejected.validate(replace(wire, 5, GoAiBackupWire.hex(corrupt)), 5) }
        assertEquals(1, calls)
    }

    @Test fun duplicateActiveAndArchiveRowsCannotSilentlyReplaceEachOther() {
        val row = GoAiCheckpointRows.active(GoAiCheckpointEntry(GoAiMatchFileCodec.encode(match()), lineage), 0)
        rejects { session.encodeRows(listOf(row, row)) }
        val archived = GoAiCheckpointRows.archive(GoAiCheckpointArchive(admit(match(terminal = true)).recordId,
            GoAiCheckpointEntry(GoAiMatchFileCodec.encode(match(terminal = true)), lineage), 0))
        rejects { archive.encodeRows(listOf(archived, archived)) }
        rejects { GameBackupRegistry(pair).prepareRestore(listOf(activeWire(), activeWire()), 5) }
        rejects { GameBackupRegistry(pair).prepareRestore(listOf(archiveWire(), archiveWire()), 5) }
    }

    @Test fun aggregateLimitRejectsTheWholeSnapshotRatherThanTruncatingIt() {
        val wire = activeWire()
        val registry = GameBackupRegistry(pair, maximumExtensionBytes = wire.encodedSizeBytes)
        rejects { registry.prepareRestore(listOf(wire), 5) } // Canonical LF is part of the budget.
        assertEquals(1, GameBackupRegistry(pair, wire.encodedSizeBytes + 1).prepareRestore(listOf(wire), 5).size)
    }

    private fun replace(record: GameBackupRecord, index: Int, value: String) =
        GameBackupRecord(record.kind, record.fields.toMutableList().apply { this[index] = value })
    private fun rejects(block: () -> Unit) {
        try { block(); fail("Invalid Go AI backup accepted") } catch (_: IllegalArgumentException) { }
    }
}
