package com.masterofchessstrategy.games.go.data

import com.masterofchessstrategy.data.*
import com.masterofchessstrategy.engine.GameResult
import com.masterofchessstrategy.engine.GameType
import com.masterofchessstrategy.engine.GoPhase
import com.masterofchessstrategy.games.go.*
import org.junit.Assert.*
import org.junit.Test

/** Native seams test envelopes, ownership and relationships, not legal Go outcomes. */
class GoLocalBackupAdaptersTest {
    private val identity = GoSessionIdentity("backup-fixture", GoSessionOrigin.LOCAL)
    private val payload = byteArrayOf(77, 79, 67, 71, 3, 9, 15, 0, 0, 0, 0, 0, 0, 0, 0, 0)
    private val recordAdapter = GoLocalRecordBackupAdapter { }
    private val progressAdapter = GoProgressBackupAdapter()
    private fun checkpointAdapter(phase: GoPhase = GoPhase.PLAYING) = GoLocalSessionBackupAdapter { session ->
        GoAdmittedLocalSession(session, phase, if (phase == GoPhase.FINISHED) GameResult.DRAW else GameResult.ONGOING, 0)
    }
    private fun archive(id: String = identity.recordId) = GameRecord(id, GameType.GO, StoredGameMode.LOCAL_TWO_PLAYER,
        null, GameResult.DRAW, payload.copyOf(), 0, isFavorite = true, completedAtEpochMillis = 19)
    private fun sessionWire(adapter: GoLocalSessionBackupAdapter = checkpointAdapter()) = adapter.encodeRows(listOf(
        GoLocalSessionEntity(GameType.GO.code, 3, GoSessionFileCodec.encode(GoSavedSession(9, payload.copyOf(),
            GoScoreDisplayMode.POINTS, identity = identity)), 17))).single()

    @Test fun allThreeSlotsAreRegisteredAndUseVersionFiveWithoutChangingCoreRecord() {
        val registry = registry()
        registry.requireSupportedSlots(BackupStorageTable.entries.filter { it.name.startsWith("GO_") }
            .map { BackupStorageSlot(it, GameType.GO) }.toSet())
        val wire = sessionWire()
        assertEquals(5, registry.containerVersion(listOf(wire)))
        rejects { registry.validate(listOf(wire), 4) }
        assertTrue(recordAdapter.descriptor.kind != "RECORD")
    }

    @Test fun localCheckpointRoundTripsIdentityModeAndTimestampWithOwnedPayload() {
        val adapter = checkpointAdapter()
        val wire = sessionWire(adapter)
        val prepared = registry(adapter).prepareRestore(listOf(wire), 5).single() as PreparedGoLocalBackup
        assertEquals(identity, prepared.admitted.session().identity)
        assertEquals(GoScoreDisplayMode.POINTS, prepared.admitted.session().displayMode)
        val bytes = prepared.admitted.session().engineState
        bytes.fill(0)
        assertArrayEquals(payload, prepared.admitted.session().engineState)
        assertEquals("17", wire.fields[2])
    }

    @Test fun terminalCheckpointRequiresMatchingArchiveAndSourceBeforeAnyWrites() {
        val adapter = checkpointAdapter(GoPhase.FINISHED)
        val registry = registry(adapter)
        val local = sessionWire(adapter)
        rejects { registry.prepareRestore(listOf(local), 5) }
        val withoutSource = recordAdapter.encode(archive(), null)
        rejects { registry.prepareRestore(listOf(local, withoutSource), 5) }
        val paired = recordAdapter.encode(archive(), identity)
        assertEquals(2, registry.prepareRestore(listOf(local, paired), 5).size)
        val wrongResult = recordAdapter.encode(archive().copy(result = GameResult.FIRST_PLAYER_WIN), identity)
        rejects { registry.prepareRestore(listOf(local, wrongResult), 5) }
        val wrongState = recordAdapter.encode(archive().copy(engineState = payload.copyOf().also { it[6] = 0 }), identity)
        rejects { registry.prepareRestore(listOf(local, wrongState), 5) }
    }

    @Test fun ongoingBranchCannotAlreadyHaveAnArchivedRecordButOlderBranchesRemain() {
        val registry = registry()
        val current = recordAdapter.encode(archive(), identity)
        rejects { registry.prepareRestore(listOf(sessionWire(), current), 5) }
        val previous = identity.copy(archiveRevision = 1)
        val history = recordAdapter.encode(archive(previous.recordId), previous)
        assertEquals(2, registry.prepareRestore(listOf(sessionWire(), history), 5).size)
    }

    @Test fun duplicateCompletedIdsAreRejectedIncludingConflictingProvenance() {
        val wire = recordAdapter.encode(archive(), identity)
        val noSource = recordAdapter.encode(archive(), null)
        rejects { registry().prepareRestore(listOf(wire, wire), 5) }
        rejects { registry().prepareRestore(listOf(wire, noSource), 5) }
    }

    @Test fun extensionRecordCannotCollideWithACoreRecordIdBeforeReplacement() {
        val wire = recordAdapter.encode(archive(), identity)
        rejects { registry().prepareRestore(listOf(wire), 5, setOf(identity.recordId)) }
        val prepared = registry().prepareRestore(listOf(wire), 5, setOf("independent-xiangqi-record"))
        assertEquals(setOf(identity.recordId), prepared.single().completedRecordIds)
    }

    @Test fun standaloneLegacyArchiveNeverInventsSourceAndRetainsFavoriteAndOriginalBytes() {
        val original = archive("old-record-no-source")
        val wire = recordAdapter.encode(original, null)
        val prepared = registry().prepareRestore(listOf(wire), 5).single() as PreparedGoRecordBackup
        assertNull(prepared.source)
        assertEquals(original.recordId, prepared.recordId)
        assertTrue(prepared.record().isFavorite)
        assertEquals(19L, prepared.record().completedAtEpochMillis)
        original.engineState.fill(0)
        val obtained = prepared.record().engineState
        obtained.fill(0)
        assertArrayEquals(payload, prepared.record().engineState)
        assertEquals(listOf("-", "-", "-"), wire.fields.takeLast(3))
    }

    @Test fun orphanForeignOrMismatchedSourcesAreRejectedInsteadOfBeingDropped() {
        val source = GoRecordSourceEntity(identity.recordId, GameType.GO.code,
            identity.sessionId, identity.origin.code, identity.archiveRevision)
        rejects { recordAdapter.encodeRows(emptyList(), listOf(source)) }
        rejects { recordAdapter.encodeRows(listOf(archive().toGameRecordEntity()), listOf(source.copy(gameTypeCode = 0))) }
        rejects { recordAdapter.encodeRows(listOf(archive().toGameRecordEntity()), listOf(source.copy(originCode = 99))) }
        rejects { recordAdapter.encodeRows(listOf(archive().toGameRecordEntity()), listOf(source.copy(sessionId = "other"))) }
        rejects { recordAdapter.encode(archive().copy(mode = StoredGameMode.HUMAN_VS_AI), identity) }
        val rows = recordAdapter.encodeRows(listOf(archive().toGameRecordEntity()), listOf(source))
        assertEquals(recordAdapter.encode(archive(), identity), rows.single())
    }

    @Test fun invalidFieldAliasesOwnersVersionsHexAndPartialSourcesAreRejected() {
        val valid = recordAdapter.encode(archive(), identity)
        val changes = listOf(0 to "0", 1 to "2", 3 to "+0", 4 to "99", 5 to "0", 6 to "4",
            7 to "a", 8 to "-1", 9 to "true", 10 to "2", 11 to "019", 12 to "01", 14 to "9", 15 to "-1")
        for ((index, value) in changes) rejects { recordAdapter.validate(replace(valid, index, value), 5) }
        rejects { recordAdapter.validate(replace(valid, 13, "-"), 5) }
        rejects { recordAdapter.validate(replace(valid, 2, "ff"), 5) }
        rejects { recordAdapter.validate(GameBackupRecord(valid.kind, valid.fields.dropLast(1)), 5) }
        rejects { recordAdapter.validate(valid, 4) }
        val local = sessionWire()
        for ((index, value) in listOf(0 to "9", 1 to "0", 2 to "-1", 3 to "gg")) {
            rejects { checkpointAdapter().validate(replace(local, index, value), 5) }
        }
    }

    @Test fun nativeAdmissionRejectsBeforeReturningPreparedAndCannotMutateOwnedArchives() {
        val rejecting = GoLocalRecordBackupAdapter { throw IllegalArgumentException("native rejected") }
        rejects { registry(records = rejecting).prepareRestore(listOf(recordAdapter.encode(archive(), identity)), 5) }
        val mutating = GoLocalRecordBackupAdapter { it.engineState.fill(0) }
        val prepared = registry(records = mutating).prepareRestore(listOf(recordAdapter.encode(archive(), identity)), 5)
            .single() as PreparedGoRecordBackup
        assertArrayEquals(payload, prepared.record().engineState)
        val badLocal = GoLocalSessionBackupAdapter { session ->
            GoAdmittedLocalSession(session.copy(displayMode = GoScoreDisplayMode.STONES), GoPhase.PLAYING, GameResult.ONGOING, 0)
        }
        rejects { registry(badLocal).prepareRestore(listOf(sessionWire()), 5) }
    }

    @Test fun bothProgressKindsPreserveAllTenLessonsAndDetailedEndgameReturnState() {
        val rows = progressRows()
        val wire = progressAdapter.encodeRows(rows).single()
        val prepared = registry().prepareRestore(listOf(wire), 5).single() as PreparedGoProgressBackup
        val restored = prepared.rows()
        assertEquals(2, restored.size)
        assertEquals(10, GoLearningProgressCodec.decode(restored[0].payload)!!.completedLessonIds.size)
        assertEquals("connection", GoLearningProgressCodec.decode(restored[0].payload)!!.lastLessonId)
        val endgame = GoEndgameProgressCodec.decode(restored[1].payload)!!
        assertEquals(GoEndgameChallengeMode.THEME, endgame.lastMode)
        assertEquals(GoEndgameLibrary.puzzles[1].id, endgame.lastPuzzleId)
        assertEquals(setOf(GoEndgameLibrary.puzzles.first().id), endgame.completedIds)
        rows.forEach { it.payload.fill(0) }
        restored.forEach { it.payload.fill(0) }
        assertNotNull(GoLearningProgressCodec.decode(prepared.rows()[0].payload))
        assertEquals(listOf(21L, 22L), prepared.rows().map { it.updatedAtEpochMillis })
    }

    @Test fun optionalProgressKindsRemainAbsentAndEmptyOrDuplicateBundlesAreRejected() {
        assertTrue(progressAdapter.encodeRows(emptyList()).isEmpty())
        for (row in progressRows()) {
            val wire = progressAdapter.encodeRows(listOf(row)).single()
            val prepared = registry().prepareRestore(listOf(wire), 5).single() as PreparedGoProgressBackup
            assertEquals(row.kindCode, prepared.rows().single().kindCode)
            rejects { registry().prepareRestore(listOf(wire, wire), 5) }
        }
        val wire = progressAdapter.encodeRows(progressRows()).single()
        rejects { progressAdapter.validate(GameBackupRecord(wire.kind, listOf("1", "1", "-", "-", "-", "-")), 5) }
        rejects { progressAdapter.validate(replace(wire, 2, "-"), 5) }
        rejects { progressAdapter.validate(replace(wire, 4, "-01"), 5) }
    }

    @Test fun foreignUnknownCorruptOrFutureProgressRowsFailClosed() {
        val row = progressRows().first()
        for (bad in listOf(row.copy(gameTypeCode = 0), row.copy(kindCode = 9), row.copy(formatVersion = 2),
            row.copy(contentVersion = 2), row.copy(updatedAtEpochMillis = -1), row.copy(payload = byteArrayOf(0)))) {
            rejects { progressAdapter.encodeRows(listOf(bad)) }
        }
        rejects { progressAdapter.encodeRows(listOf(row, row)) }
        val wire = progressAdapter.encodeRows(listOf(row)).single()
        rejects { progressAdapter.validate(replace(wire, 3, wire.fields[3].uppercase()), 5) }
        rejects { progressAdapter.validate(wire, 4) }
    }

    private fun progressRows() = listOf(
        GoProgressEntity(1, GoProgressKind.LEARNING.code, 1, 1,
            GoLearningProgressCodec.encode(GoLearningProgress(GoLearningLibrary.lessons.map { it.id }.toSet(), "connection")), 21),
        GoProgressEntity(1, GoProgressKind.ENDGAME.code, 1, 1, GoEndgameProgressCodec.encode(GoEndgameProgress(
            setOf(GoEndgameLibrary.puzzles.first().id), GoEndgameLibrary.puzzles[1].id, GoEndgameChallengeMode.THEME)), 22),
    )

    private fun registry(local: GoLocalSessionBackupAdapter = checkpointAdapter(), records: GoLocalRecordBackupAdapter = recordAdapter) =
        GameBackupRegistry(listOf(local, records, progressAdapter))
    private fun replace(record: GameBackupRecord, index: Int, value: String) = GameBackupRecord(record.kind,
        record.fields.toMutableList().also { it[index] = value })
    private fun rejects(block: () -> Unit) {
        try { block(); fail("expected rejection") } catch (_: IllegalArgumentException) { }
    }
}
