package com.masterofchessstrategy.games.lanzhou_mahjong.data

import com.masterofchessstrategy.data.ActiveGameDao
import com.masterofchessstrategy.data.ActiveGameEntity
import com.masterofchessstrategy.data.BackupStorageTable
import com.masterofchessstrategy.data.GameBackupRecord
import com.masterofchessstrategy.data.GameBackupRegistry
import com.masterofchessstrategy.engine.Difficulty
import com.masterofchessstrategy.engine.GameType
import com.masterofchessstrategy.games.lanzhou_mahjong.LanzhouMahjongAssistanceUsage
import com.masterofchessstrategy.games.lanzhou_mahjong.LanzhouMahjongDecisionPoint
import com.masterofchessstrategy.games.lanzhou_mahjong.LanzhouMahjongSessionRecord
import com.masterofchessstrategy.games.lanzhou_mahjong.LanzhouMahjongSessionRecordCodec
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/** Fake Native canonicalizers and DAO only; no SQLite, JNI or full app restore is executed. */
class LanzhouMahjongBackupAdapterTest {
    private val pass = LanzhouMahjongSessionAdmission { it.nativeState }
    private fun adapter() = LanzhouMahjongBackupAdapter(pass)

    @Test fun descriptorAndRegistryOwnExactlyOneLanzhouActiveSlot() {
        val adapter = adapter()
        val descriptor = adapter.descriptor
        assertEquals("LANZHOU_MAHJONG_ACTIVE", descriptor.kind)
        assertEquals(5, descriptor.minimumContainerVersion)
        assertEquals(BackupStorageTable.ACTIVE_GAME, descriptor.storageSlot.table)
        assertEquals(GameType.LANZHOU_MAHJONG, descriptor.storageSlot.gameType)
        assertEquals(1, descriptor.maximumRecords)
        assertEquals(40, descriptor.canonicalOrder)
        assertEquals(65_536, descriptor.maximumRecordBytes)
        val registry = GameBackupRegistry(listOf(adapter))
        val record = adapter.encode(snapshot())
        assertEquals(5, registry.containerVersion(listOf(record)))
        invalid { registry.validate(listOf(record, record), 5) }
        invalid { GameBackupRegistry(listOf(adapter, adapter())) }
    }

    @Test fun validateAndNormalizeUseOnlyStructureWithoutOpeningNative() {
        var calls = 0
        val adapter = LanzhouMahjongBackupAdapter(LanzhouMahjongSessionAdmission { calls++; error("must not run") })
        val record = adapter.encode(snapshot())
        adapter.validate(record, 5)
        assertEquals(record, adapter.normalize(record, 5))
        for (version in listOf(0, 1, 2, 3, 4, 6)) invalid { adapter.validate(record, version) }
        assertEquals(0, calls)
    }

    @Test fun roundTripPreservesBothNativeRulesUsageDecisionsAndThe69CharacterId() = runTest {
        for (rule in 1..2) {
            val source = snapshot(rule = rule, actions = 2, usage = 2)
            val adapter = adapter()
            val wire = adapter.encode(source)
            assertEquals(listOf("3", "1", "123"), wire.fields.take(3))
            val prepared = adapter.prepareRestore(wire)
            val dao = Rows()
            prepared.write(dao)
            val row = requireNotNull(dao.row)
            assertEquals(69, row.sessionId.length)
            assertEquals(LanzhouMahjongSessionAdmission.sessionId(source.record), row.sessionId)
            assertEquals(0, row.undoUseCount)
            assertEquals(0, row.hintUseCount)
            val restored = pass.admitRow(row)
            assertArrayEquals(source.record.nativeState, restored.nativeState)
            assertEquals(2, restored.assistance.undoUsed)
            assertEquals(source.record.decisions, restored.decisions)
            assertEquals(source.record.nextChoiceSeed, restored.nextChoiceSeed)
            assertEquals(wire, adapter.read(dao).single())
            assertEquals(LanzhouMahjongBackupAdapter.SLOT, prepared.storageSlot)
            assertTrue(prepared.completedRecordIds.isEmpty())
            assertEquals(0, dao.deletes)
        }
    }

    @Test fun legacyOuterV1NormalizesOnlyTheOuterContainerWithoutNativeCalls() {
        var calls = 0
        val adapter = LanzhouMahjongBackupAdapter(LanzhouMahjongSessionAdmission { calls++; it.nativeState })
        for (rule in 1..2) {
            val snapshot = snapshot(rule = rule)
            val raw = LanzhouMahjongSessionRecordCodec.encode(snapshot.record).copyOf(174)
            raw[4] = 1
            ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).putInt(170,
                CRC32().apply { update(raw, 0, 170) }.value.toInt())
            val legacy = GameBackupRecord(LanzhouMahjongBackupAdapter.KIND, listOf("3", "1", "123", hex(raw)))
            adapter.validate(legacy, 5)
            val normalized = adapter.normalize(legacy, 5)
            assertEquals(adapter.encode(snapshot), normalized)
            assertArrayEquals(snapshot.record.nativeState, adapter.decode(normalized).record.nativeState)
        }
        assertEquals(0, calls)
    }

    @Test fun missingReadIsEmptyAndCorruptMetadataIsRetainedWithoutDeletion() = runTest {
        val dao = Rows()
        val adapter = adapter()
        assertTrue(adapter.read(dao).isEmpty())
        val bad = pass.createRow(snapshot().record, 123).copy(sessionId = "wrong")
        dao.row = bad
        try { adapter.read(dao); fail() } catch (_: IOException) { }
        assertSame(bad, dao.row)
        assertEquals(0, dao.deletes)
        assertEquals(3, dao.lastQuery)
    }

    @Test fun readAndPrepareEachPerformOneCompleteAdmissionAndWriteDoesNotRepeatIt() = runTest {
        var calls = 0
        val adapter = LanzhouMahjongBackupAdapter(LanzhouMahjongSessionAdmission { calls++; it.nativeState })
        val dao = Rows().apply { row = pass.createRow(snapshot().record, 123) }
        val wire = adapter.read(dao).single()
        assertEquals(1, calls)
        val prepared = adapter.prepareRestore(wire)
        assertEquals(2, calls)
        prepared.write(dao)
        prepared.write(dao)
        assertEquals(2, calls)
        assertEquals(2, dao.writes)
    }

    @Test fun structurallyValidHistoryStillNeedsNativeAndFailureNeverWrites() = runTest {
        val wire = adapter().encode(snapshot(actions = 1))
        val reject = LanzhouMahjongBackupAdapter(LanzhouMahjongSessionAdmission { throw IOException("fake illegal history") })
        reject.validate(wire, 5)
        val dao = Rows().apply { row = pass.createRow(snapshot().record, 99) }
        val before = dao.row
        assertThrows(IOException::class.java) { reject.prepareRestore(wire) }
        assertSame(before, dao.row)
        assertEquals(0, dao.writes)
    }

    @Test fun foreignOwnersFormatsAndFieldCountsAreRejected() {
        val adapter = adapter()
        val record = adapter.encode(snapshot())
        for (kind in listOf("ACTIVE", "FANGQI_ACTIVE", "GO_LOCAL_SESSION")) {
            invalid { adapter.validate(GameBackupRecord(kind, record.fields), 5) }
        }
        for ((index, value) in listOf(0 to "0", 0 to "03", 1 to "2", 1 to "01")) {
            invalid { adapter.validate(changed(record, index, value), 5) }
        }
        invalid { adapter.validate(GameBackupRecord(record.kind, record.fields.dropLast(1)), 5) }
        invalid { adapter.validate(GameBackupRecord(record.kind, record.fields + "extra"), 5) }
    }

    @Test fun noncanonicalTimesAndHexAreRejectedBeforeDecodingNative() {
        val adapter = adapter()
        val record = adapter.encode(snapshot())
        for (value in listOf("", "00", "01", "+1", "-1", "-0", "1.0", "１", "9223372036854775808")) {
            invalid { adapter.validate(changed(record, 2, value), 5) }
        }
        for (value in listOf("", "0", "zz", record.fields[3].uppercase(), "00".repeat(15_187))) {
            invalid { adapter.validate(changed(record, 3, value), 5) }
        }
        val corrupt = record.fields[3].toCharArray().also { it[it.lastIndex] = if (it.last() == '0') '1' else '0' }
        invalid { adapter.validate(changed(record, 3, String(corrupt)), 5) }
        val oversized = "00".repeat(33_000)
        invalid { adapter.validate(changed(record, 3, oversized), 5) }
    }

    @Test fun maximumLzsrDecisionsFitTheDeclaredWireCeilingWithoutDroppingHistory() {
        val source = snapshot(actions = 1000)
        assertEquals(15_186, LanzhouMahjongSessionRecordCodec.encode(source.record).size)
        val adapter = adapter()
        val wire = adapter.encode(source)
        assertTrue(wire.encodedSizeBytes < LanzhouMahjongBackupAdapter.MAX_RECORD_BYTES)
        val restored = adapter.decode(wire)
        assertEquals(1000, restored.record.decisions.size)
        assertArrayEquals(source.record.nativeState, restored.record.nativeState)
    }

    @Test fun preparedConstructorAndEveryWriteOwnTheirByteArrays() = runTest {
        val source = pass.createRow(snapshot(actions = 1).record, 123)
        val expected = source.engineState.copyOf()
        val prepared = LanzhouMahjongBackupAdapter.PreparedLanzhouMahjongBackup(source)
        source.engineState.fill(0)
        val dao = Rows().apply { mutateWriteArgument = true }
        prepared.write(dao)
        assertArrayEquals(expected, dao.row!!.engineState)
        prepared.write(dao)
        assertArrayEquals(expected, dao.row!!.engineState)
        assertEquals(2, dao.writes)
    }

    private fun snapshot(rule: Int = 2, actions: Int = 0, usage: Int = 0): LanzhouMahjongStoredSession {
        // Envelope-only fixture; pass canonicalizer does not prove any action legality.
        val bytes = ByteArray(130 + actions * 3)
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put(byteArrayOf(76, 90, 77, 74, rule.toByte(), 0)); buffer.putLong(1)
        repeat(108) { buffer.put((it / 4).toByte()) }; buffer.putInt(actions)
        repeat(actions) { buffer.put(byteArrayOf(1, 0, 0)) }
        var hash = 0x811c9dc5.toInt()
        for (index in 0 until bytes.size - 4) hash = (hash xor (bytes[index].toInt() and 255)) * 16777619
        buffer.putInt(hash)
        val record = LanzhouMahjongSessionRecord(Difficulty.EASY, 0, 7, 0, 1, 19, bytes,
            LanzhouMahjongAssistanceUsage(Difficulty.EASY, usage, usage),
            List(actions) { LanzhouMahjongDecisionPoint(it, 55L + it) })
        return LanzhouMahjongStoredSession(record, 123)
    }

    private fun changed(record: GameBackupRecord, index: Int, value: String) =
        GameBackupRecord(record.kind, record.fields.toMutableList().also { it[index] = value })

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun invalid(block: () -> Unit) { assertThrows(IllegalArgumentException::class.java, block) }

    private class Rows : ActiveGameDao {
        var row: ActiveGameEntity? = null
        var lastQuery = -1
        var deletes = 0
        var writes = 0
        var mutateWriteArgument = false
        override suspend fun find(gameTypeCode: Int): ActiveGameEntity? { lastQuery = gameTypeCode; return row }
        override suspend fun upsert(entity: ActiveGameEntity) {
            writes++
            row = entity.copy(engineState = entity.engineState.copyOf())
            if (mutateWriteArgument) entity.engineState.fill(0)
        }
        override suspend fun delete(gameTypeCode: Int) { deletes++ }
        override suspend fun deleteAll() { deletes++ }
    }
}
