package com.masterofchessstrategy.games.lanzhou_fangqi.data

import com.masterofchessstrategy.data.ActiveGameDao
import com.masterofchessstrategy.data.ActiveGameEntity
import com.masterofchessstrategy.data.BackupStorageTable
import com.masterofchessstrategy.data.GameBackupRecord
import com.masterofchessstrategy.engine.Difficulty
import com.masterofchessstrategy.engine.GameType
import com.masterofchessstrategy.games.lanzhou_fangqi.FangqiStoredMode
import com.masterofchessstrategy.games.lanzhou_fangqi.FangqiStoredSession
import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/** Canonicalizers and DAO here are explicit test doubles; no JNI or SQLite is claimed. */
class FangqiBackupAdapterTest {
    private fun adapter() = FangqiBackupAdapter(FangqiSessionAdmission { it })

    @Test fun descriptorOwnsOnlyOneFangqiActiveSlot() {
        val descriptor = adapter().descriptor
        assertEquals("FANGQI_ACTIVE", descriptor.kind)
        assertEquals(5, descriptor.minimumContainerVersion)
        assertEquals(1, descriptor.maximumRecords)
        assertEquals(30, descriptor.canonicalOrder)
        assertEquals(BackupStorageTable.ACTIVE_GAME, descriptor.storageSlot.table)
        assertEquals(GameType.LANZHOU_SQUARE_CHESS, descriptor.storageSlot.gameType)
        assertEquals(2_097_737, descriptor.maximumRecordBytes)
    }

    @Test fun structuralValidationUsesNoNativeAndRequiresContainerFive() {
        var calls = 0
        val adapter = FangqiBackupAdapter(FangqiSessionAdmission { calls++; throw IOException("must not run") })
        val record = adapter.encode(snapshot())
        adapter.validate(record, 5)
        assertEquals(record, adapter.normalize(record, 5))
        listOf(0, 1, 2, 3, 4, 6).forEach { version -> invalid { adapter.validate(record, version) } }
        assertEquals(0, calls)
    }

    @Test fun preservesAllModesDifficultiesAndIndependentAuxiliaryMetadata() = runTest {
        val adapter = adapter()
        val variants = listOf(FangqiStoredMode.LOCAL_TWO_PLAYER to null) +
            listOf(FangqiStoredMode.HUMAN_VS_AI, FangqiStoredMode.AI_AUTO_PLAY).flatMap { mode ->
                Difficulty.entries.map { mode to it }
            }
        val other = row().copy(gameTypeCode = 0, engineFormatVersion = 2, engineState = byteArrayOf(9, 7))
        val dao = Rows().apply { rows[0] = other.owned() }
        variants.forEach { (mode, difficulty) ->
            val source = snapshot(history(2), mode, difficulty)
            val record = adapter.encode(source)
            adapter.validate(record, 5)
            assertSnapshot(source, adapter.decode(record))
            val prepared = adapter.prepareRestore(record)
            assertEquals(FangqiBackupAdapter.SLOT, prepared.storageSlot)
            assertTrue(prepared.completedRecordIds.isEmpty())
            prepared.write(dao)
            assertEquals(record, adapter.read(dao).single())
            assertEquals(other, dao.rows[0])
        }
        assertEquals(0, dao.deletes)
    }

    @Test fun acceptsMaximumHistoryAbove64KiBWithoutEquatingItToAcceptedCount() {
        val source = snapshot(history(16_384), accepted = 0)
        assertTrue(source.engineState.size > 65_536)
        val adapter = adapter()
        val record = adapter.encode(source)
        val decoded = adapter.decode(record)
        assertEquals(16_384, FangqiSessionAdmission.inspectHistory(decoded.engineState))
        assertEquals(0, decoded.acceptedActionCount)
        assertSnapshot(source, decoded)
    }

    @Test fun rejectsForeignKindsOwnersVersionsAndWrongFieldCounts() {
        val adapter = adapter()
        val record = adapter.encode(snapshot())
        listOf("TIGER_GOAT_ACTIVE", "GO_LOCAL_SESSION", "ACTIVE").forEach { kind ->
            invalid { adapter.validate(GameBackupRecord(kind, record.fields), 5) }
        }
        listOf(0 to "0", 0 to "04", 1 to "4", 1 to "05", 2 to "2", 2 to "01").forEach { (i, v) ->
            invalid { adapter.validate(changed(record, i, v), 5) }
        }
        invalid { adapter.validate(GameBackupRecord(record.kind, record.fields.dropLast(1)), 5) }
        invalid { adapter.validate(GameBackupRecord(record.kind, record.fields + "extra"), 5) }
    }

    @Test fun rejectsNoncanonicalOverflowAndNegativeNumbers() {
        val adapter = adapter()
        val record = adapter.encode(snapshot())
        listOf(3, 6, 7, 8, 9).forEach { index ->
            listOf("", "00", "01", "+1", "-0", "-1", "1.0", "１", "9223372036854775808").forEach { value ->
                invalid { adapter.validate(changed(record, index, value), 5) }
            }
        }
        invalid { adapter.validate(changed(record, 6, "16385"), 5) }
        listOf(7, 8).forEach { invalid { adapter.validate(changed(record, it, "2147483648"), 5) } }
    }

    @Test fun rejectsUnknownModesDifficultiesAndInconsistentModePairs() {
        val adapter = adapter()
        val local = adapter.encode(snapshot())
        invalid { adapter.validate(changed(local, 3, "3"), 5) }
        invalid { adapter.validate(changed(local, 4, "0"), 5) }
        invalid { adapter.validate(changed(local, 3, "1"), 5) }
        val ai = adapter.encode(snapshot(mode = FangqiStoredMode.HUMAN_VS_AI, difficulty = Difficulty.EASY))
        listOf("4", "00", "+1", "-1", "").forEach { invalid { adapter.validate(changed(ai, 4, it), 5) } }
    }

    @Test fun rejectsNoncanonicalHexInvalidUtf8AndInvalidIdentity() {
        val adapter = adapter()
        val record = adapter.encode(snapshot())
        listOf("A0", "a", "zz", "c080", "eda080", "ff", "", "20", "61".repeat(65)).forEach { value ->
            invalid { adapter.validate(changed(record, 5, value), 5) }
        }
        invalid { adapter.validate(changed(record, 5, "61".repeat(257)), 5) }
        invalid { adapter.encode(snapshot(id = "\uD800")) }
        listOf("A0", "a", "zz", "00", "").forEach { value ->
            invalid { adapter.validate(changed(record, 10, value), 5) }
        }
        invalid { adapter.validate(changed(record, 10, "20".repeat(1_048_577)), 5) }
    }

    @Test fun rejectsTruncatedNoncanonicalOrTooLongHistoriesBeforeNative() {
        var calls = 0
        val adapter = FangqiBackupAdapter(FangqiSessionAdmission { calls++; it })
        val histories = listOf(history(16_385), history(1).toString(Charsets.US_ASCII).plus(';').toByteArray(),
            history().toString(Charsets.US_ASCII).replace("|0|", "|00|").toByteArray(),
            history().toString(Charsets.US_ASCII).replace("|0|", "|1|").toByteArray())
        histories.forEach { bytes -> invalid { adapter.prepareRestore(changed(adapter.encode(snapshot()), 10, hex(bytes))) } }
        assertEquals(0, calls)
    }

    @Test fun missingRowIsEmptyButMalformedExistingRowsNeverDisappear() = runTest {
        var calls = 0
        val adapter = FangqiBackupAdapter(FangqiSessionAdmission { calls++; it })
        val dao = Rows()
        assertTrue(adapter.read(dao).isEmpty())
        val base = row()
        val invalidRows = listOf(base.copy(gameTypeCode = 0), base.copy(envelopeVersion = 4),
            base.copy(engineFormatVersion = 2), base.copy(modeCode = 3), base.copy(difficultyCode = 0),
            base.copy(sessionId = ""), base.copy(updatedAtEpochMillis = -1), base.copy(acceptedMoveCount = 16_385),
            base.copy(undoUseCount = -1), base.copy(hintUseCount = -1), base.copy(resultOverrideCode = 0),
            base.copy(timeControlMinutes = 5), base.copy(redRemainingMillis = 1), base.copy(blackRemainingMillis = 1),
            base.copy(turnStartedAtEpochMillis = 1), base.copy(pendingDrawOfferSideCode = 0),
            base.copy(autoPlayPaused = true), base.copy(autoPlaySpeedPermille = 2_000),
            base.copy(completedAutoGames = 1), base.copy(sessionVariantId = "foreign"), base.copy(playerIndex = 1))
        invalidRows.forEach { bad ->
            dao.rows[4] = bad.owned()
            invalidSuspend { adapter.read(dao) }
            assertEquals(bad, dao.rows[4])
        }
        assertEquals(0, calls)
        assertEquals(0, dao.writes)
        assertEquals(0, dao.deletes)
    }

    @Test fun nativeFailureAndNoncanonicalReplayPropagateWithoutWrites() = runTest {
        val dao = Rows().apply { rows[4] = row() }
        val before = dao.rows.getValue(4).owned()
        val record = adapter().encode(snapshot())
        val rejected = FangqiBackupAdapter(FangqiSessionAdmission { throw IOException("Native rejected") })
        invalid { rejected.prepareRestore(record) }
        invalidSuspend { rejected.read(dao) }
        val changed = FangqiBackupAdapter(FangqiSessionAdmission { history(1) })
        invalid { changed.prepareRestore(record) }
        invalidSuspend { changed.read(dao) }
        assertEquals(before, dao.rows[4])
        assertEquals(0, dao.writes)
        assertEquals(0, dao.deletes)
    }

    @Test fun preparedAndWireDataOwnBytesEvenAgainstAMutatingWriter() = runTest {
        val bytes = history()
        val source = snapshot(bytes)
        val adapter = adapter()
        val fields = adapter.encode(source).fields.toMutableList()
        val wire = GameBackupRecord(FangqiBackupAdapter.KIND, fields)
        val prepared = adapter.prepareRestore(wire)
        fields[10] = "00"
        bytes.fill(0)
        source.engineState.fill(0)
        val first = Rows().apply { mutateIncoming = true }
        prepared.write(first)
        val second = Rows()
        prepared.write(second)
        assertArrayEquals(history(), second.rows.getValue(4).engineState)
        assertEquals(adapter.encode(snapshot()), wire)
    }

    @Test fun readAndWriteFaultsPropagateWithoutClearOrCrossGameOperations() = runTest {
        val dao = Rows().apply { findFailure = IOException("read unavailable") }
        invalidSuspend { adapter().read(dao) }
        dao.findFailure = null
        dao.writeFailure = IOException("write unavailable")
        invalidSuspend { adapter().prepareRestore(adapter().encode(snapshot())).write(dao) }
        assertEquals(0, dao.deletes)
        assertTrue(dao.rows.isEmpty())
    }

    private class Rows : ActiveGameDao {
        val rows = mutableMapOf<Int, ActiveGameEntity>()
        var writes = 0
        var deletes = 0
        var mutateIncoming = false
        var findFailure: IOException? = null
        var writeFailure: IOException? = null
        override suspend fun find(gameTypeCode: Int): ActiveGameEntity? {
            check(gameTypeCode == 4)
            findFailure?.let { throw it }
            return rows[gameTypeCode]?.owned()
        }
        override suspend fun upsert(entity: ActiveGameEntity) {
            check(entity.gameTypeCode == 4)
            writes++
            writeFailure?.let { throw it }
            if (mutateIncoming) entity.engineState.fill(0)
            rows[entity.gameTypeCode] = entity.owned()
        }
        override suspend fun delete(gameTypeCode: Int) { deletes++; error("Adapter must not clear") }
        override suspend fun deleteAll() { deletes++; error("Adapter must not clear") }
    }

    companion object {
        private fun ActiveGameEntity.owned() = copy(engineState = engineState.copyOf())
        private fun row() = ActiveGameEntity(4, 0, null, 5, 1, history(), Long.MAX_VALUE, "方棋-保存", 16_384,
            Int.MAX_VALUE, Int.MAX_VALUE)
        private fun history(count: Int = 0) = ("MOCS-LFQ|1|1|" + "0".repeat(49) + "|$count|" +
            List(count) { "m,1,0,1" }.joinToString(";")).toByteArray(Charsets.US_ASCII)
        private fun snapshot(bytes: ByteArray = history(), mode: FangqiStoredMode = FangqiStoredMode.LOCAL_TWO_PLAYER,
            difficulty: Difficulty? = null, id: String = "方棋-保存", accepted: Int = 16_384) =
            FangqiStoredSession(mode, difficulty, bytes, id, Long.MAX_VALUE, accepted, Int.MAX_VALUE, Int.MAX_VALUE)
        private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
        private fun changed(record: GameBackupRecord, index: Int, value: String) =
            GameBackupRecord(record.kind, record.fields.toMutableList().apply { this[index] = value })
        private fun invalid(block: () -> Unit) {
            try { block(); fail("Expected a rejected backup") } catch (_: Exception) { }
        }
        private suspend fun invalidSuspend(block: suspend () -> Unit) {
            try { block(); fail("Expected a rejected backup") } catch (_: Exception) { }
        }
        private fun assertSnapshot(expected: FangqiStoredSession, actual: FangqiStoredSession) {
            assertEquals(expected.mode, actual.mode)
            assertEquals(expected.difficulty, actual.difficulty)
            assertEquals(expected.sessionId, actual.sessionId)
            assertEquals(expected.updatedAtEpochMillis, actual.updatedAtEpochMillis)
            assertEquals(expected.acceptedActionCount, actual.acceptedActionCount)
            assertEquals(expected.undoUseCount, actual.undoUseCount)
            assertEquals(expected.hintUseCount, actual.hintUseCount)
            assertArrayEquals(expected.engineState, actual.engineState)
        }
    }
}
