package com.masterofchessstrategy.games.tiger_goat.data

import com.masterofchessstrategy.data.ActiveGameDao
import com.masterofchessstrategy.data.ActiveGameEntity
import com.masterofchessstrategy.data.BackupStorageTable
import com.masterofchessstrategy.data.GameBackupRecord
import com.masterofchessstrategy.data.GameBackupRegistry
import com.masterofchessstrategy.data.TigerGoatActiveArchive
import com.masterofchessstrategy.engine.GameType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Injected admission and in-memory DAO only; these tests do not claim actual Native/Room execution. */
class TigerGoatBackupAdapterTest {
    @Test fun descriptorOwnsOnlyOneTigerActiveSlotAndRequiresV5() {
        val descriptor = adapter().descriptor
        assertEquals("TIGER_GOAT_ACTIVE", descriptor.kind)
        assertEquals(5, descriptor.minimumContainerVersion)
        assertEquals(BackupStorageTable.ACTIVE_GAME, descriptor.storageSlot.table)
        assertEquals(GameType.TIGER_AND_GOAT, descriptor.storageSlot.gameType)
        assertEquals(1, descriptor.maximumRecords)
        assertEquals(0, descriptor.canonicalOrder)
        assertEquals(393_879, descriptor.maximumRecordBytes)
    }

    @Test fun normalizationPreservesLegacyNumericSpellingsAndUppercaseHex() {
        val owner = adapter()
        val legacy = changed(record(id = "棋|\n"), mapOf(0 to "+0005", 1 to "01", 2 to "+02",
            4 to "-0", 5 to "+0009")).let {
            changed(it, mapOf(3 to it.fields[3].uppercase(), 6 to it.fields[6].uppercase()))
        }
        val normalized = GameBackupRegistry(listOf(owner)).normalizeRecord(legacy, 5)
        assertEquals(record(id = "棋|\n"), normalized)
        assertEquals(normalized, owner.normalize(normalized, 5))
    }

    @Test fun legacyLeadingZeroLengthIsNotConfusedWithCanonicalByteBudget() {
        val owner = adapter()
        val legacy = changed(record(), mapOf(0 to "0".repeat(owner.descriptor.maximumRecordBytes) + "5"))
        assertTrue(legacy.encodedSizeBytes > owner.descriptor.maximumRecordBytes)
        assertEquals(record(), GameBackupRegistry(listOf(owner)).normalizeRecord(legacy, 5))
    }

    @Test fun validationAndNormalizationNeverInvokeAdmission() {
        var calls = 0
        val owner = TigerGoatBackupAdapter { calls++; error("Must not run") }
        owner.validate(record(), 5)
        owner.normalize(record(), 5)
        assertEquals(0, calls)
        for (version in listOf(1, 2, 3, 4, 6)) {
            rejected { owner.validate(record(), version) }
            rejected { owner.normalize(record(), version) }
        }
    }

    @Test fun v1AndV2NativeBytesAreNotRewritten() = runBlocking {
        for (version in 1..2) {
            val owner = adapter()
            val source = record(version = version)
            val dao = MemoryDao()
            owner.prepareRestore(source).write(dao)
            assertArrayEquals(state(version), dao.rows.getValue(5).engineState)
            assertEquals(version, dao.rows.getValue(5).engineFormatVersion)
            assertEquals(listOf(source), owner.read(dao))
        }
    }

    @Test fun foreignKindShapeGameAndArchiveFormatAreRejected() {
        val owner = adapter()
        rejected { owner.validate(GameBackupRecord("ACTIVE", record().fields), 5) }
        rejected { owner.validate(GameBackupRecord("TIGER_GOAT_ACTIVE", record().fields.dropLast(1)), 5) }
        rejected { owner.validate(GameBackupRecord("TIGER_GOAT_ACTIVE", record().fields + "extra"), 5) }
        for ((index, values) in listOf(0 to listOf("0", "6", "x"), 1 to listOf("0", "2", "x"),
            2 to listOf("0", "3", "255", "x", "1"))) {
            for (value in values) rejected { owner.validate(changed(record(), mapOf(index to value)), 5) }
        }
    }

    @Test fun invalidCountsTimestampsAndHexAreRejected() {
        val owner = adapter()
        for (value in listOf("-1", "65536", "2147483648", "1", "0.0", " 0")) {
            rejected { owner.validate(changed(record(), mapOf(4 to value)), 5) }
        }
        for (value in listOf("-1", "9223372036854775808", "0.0", " 9")) {
            rejected { owner.validate(changed(record(), mapOf(5 to value)), 5) }
        }
        for (value in listOf("", "-", "0", "zz", "00".repeat(52), "00".repeat(196_659))) {
            rejected { owner.validate(changed(record(), mapOf(6 to value)), 5) }
        }
    }

    @Test fun invalidIdsMalformedUtf8AndUtf16LengthCannotBeNormalizedAway() {
        val owner = adapter()
        for (value in listOf("-", "", "20", "61".repeat(65), "61".repeat(257), "ff", "eda080", "0", "gg")) {
            rejected { owner.normalize(changed(record(), mapOf(3 to value)), 5) }
        }
        owner.validate(record(id = "棋".repeat(64)), 5)
        owner.validate(record(id = "😀".repeat(32)), 5)
        rejected { owner.validate(record(id = "😀".repeat(33)), 5) }
    }

    @Test fun magicGeometryEmbeddedCountAndChecksumMustMatch() {
        val owner = adapter()
        for (bytes in listOf(state().also { it[0] = 0; checksum(it) },
            state().also { it[5] = 2; checksum(it) }, state().also { it[47] = 1; checksum(it) },
            state().also { it[10] = 3 }, state().also { it[4] = 3; checksum(it) })) {
            rejected { owner.validate(changed(record(), mapOf(6 to bytes.hex())), 5) }
        }
    }

    @Test fun exactNativeSizeAndActionBoundRemainAdmissibleToEnvelopeOnly() {
        val owner = adapter()
        val bytes = state(count = 65_535)
        val source = changed(record(), mapOf(4 to "65535", 5 to Long.MAX_VALUE.toString(), 6 to bytes.hex()))
        owner.validate(source, 5)
        assertEquals(source, owner.normalize(source, 5))
        assertTrue(source.encodedSizeBytes <= owner.descriptor.maximumRecordBytes)
        // Dummy actions pass only envelope checks. Actual Native must still reject/replay them.
    }

    @Test fun readUsesOnlyGameFiveAndStrictRowMetadata() = runBlocking {
        val owner = adapter()
        val dao = MemoryDao().also { it.rows[0] = row().copy(gameTypeCode = 0) }
        assertTrue(owner.read(dao).isEmpty())
        assertEquals(listOf(5), dao.lookups)
        for (bad in listOf(row().copy(modeCode = 1), row().copy(difficultyCode = 0),
            row().copy(envelopeVersion = 4), row().copy(engineFormatVersion = 1),
            row().copy(acceptedMoveCount = 1), row().copy(undoUseCount = 1), row().copy(hintUseCount = 1),
            row().copy(resultOverrideCode = 1), row().copy(timeControlMinutes = 1),
            row().copy(redRemainingMillis = 1), row().copy(blackRemainingMillis = 1),
            row().copy(turnStartedAtEpochMillis = 1), row().copy(pendingDrawOfferSideCode = 0),
            row().copy(autoPlayPaused = true), row().copy(autoPlaySpeedPermille = 999),
            row().copy(completedAutoGames = 1), row().copy(sessionVariantId = "extra"), row().copy(playerIndex = 1),
            row().copy(gameTypeCode = 0))) {
            dao.rows[5] = bad
            try { owner.read(dao); fail("Accepted invalid metadata") } catch (_: IllegalArgumentException) { }
        }
        dao.rows[5] = row()
        assertEquals(listOf(record()), owner.read(dao))
    }

    @Test fun failedOrThrowingAdmissionDoesNotProduceWritablePreparedData() {
        var calls = 0
        val rejecting = TigerGoatBackupAdapter { calls++; false }
        rejected { rejecting.prepareRestore(record()) }
        assertEquals(1, calls)
        val sentinel = IllegalStateException("Native unavailable")
        val throwing = TigerGoatBackupAdapter { throw sentinel }
        assertSame(sentinel, assertThrows(IllegalStateException::class.java) { throwing.prepareRestore(record()) })
        // Preparation has no database argument; failure cannot execute a write or clear.
    }

    @Test fun preparedOwnsAdmittedBytesAndWritesOnlyItsSlotWithoutClearing() = runBlocking {
        var admitted: TigerGoatActiveArchive? = null
        val owner = TigerGoatBackupAdapter { admitted = it; true }
        val fields = record().fields.toMutableList()
        val source = GameBackupRecord("TIGER_GOAT_ACTIVE", fields)
        val prepared = owner.prepareRestore(source)
        fields.fill("corrupt")
        admitted!!.engineState.fill(0)
        val dao = MemoryDao().also { it.rows[0] = row().copy(gameTypeCode = 0) }
        val oldChinese = dao.rows.getValue(0)
        prepared.write(dao)
        dao.rows.getValue(5).engineState.fill(0)
        prepared.write(dao)
        assertEquals(owner.descriptor.storageSlot, prepared.storageSlot)
        assertEquals(oldChinese, dao.rows.getValue(0))
        assertArrayEquals(state(), dao.rows.getValue(5).engineState)
        assertEquals(listOf(5, 5), dao.writes)
        assertEquals(0, dao.deletions)
    }

    private fun adapter() = TigerGoatBackupAdapter { true }
    private fun row() = TigerGoatActiveArchive("save", state(), 0, 9).toEntity()
    private fun record(version: Int = 2, id: String = "save") = GameBackupRecord("TIGER_GOAT_ACTIVE",
        listOf("5", "1", version.toString(), id.toByteArray(Charsets.UTF_8).hex(), "0", "9", state(version).hex()))
    private fun changed(record: GameBackupRecord, values: Map<Int, String>) = GameBackupRecord(record.kind,
        record.fields.toMutableList().also { fields -> values.forEach { (index, value) -> fields[index] = value } })
    private fun rejected(action: () -> Unit) { assertThrows(IllegalArgumentException::class.java, action) }

    private class MemoryDao : ActiveGameDao {
        val rows = mutableMapOf<Int, ActiveGameEntity>()
        val lookups = mutableListOf<Int>()
        val writes = mutableListOf<Int>()
        var deletions = 0
        override suspend fun find(gameTypeCode: Int): ActiveGameEntity? {
            lookups += gameTypeCode
            return rows[gameTypeCode]
        }
        override suspend fun upsert(entity: ActiveGameEntity) { writes += entity.gameTypeCode; rows[entity.gameTypeCode] = entity }
        override suspend fun delete(gameTypeCode: Int) { deletions++; rows.remove(gameTypeCode) }
        override suspend fun deleteAll() { deletions++; rows.clear() }
    }

    private fun state(version: Int = 2, count: Int = 0) = ByteArray(53 + count * 3).also { bytes ->
        byteArrayOf(77, 79, 67, 84, version.toByte(), 1, 0, 1, 14, 0).copyInto(bytes)
        bytes[20] = 1; bytes[24] = 1
        for (point in listOf(6, 7, 8, 11, 13, 16, 17, 18)) bytes[10 + point] = 2
        bytes[47] = count.toByte(); bytes[48] = (count ushr 8).toByte()
        checksum(bytes)
    }
    private fun checksum(bytes: ByteArray) {
        var hash = 0x811c9dc5.toInt()
        for (index in 0 until bytes.size - 4) hash = (hash xor (bytes[index].toInt() and 255)) * 16777619
        repeat(4) { bytes[bytes.size - 4 + it] = (hash ushr (8 * it)).toByte() }
    }
    private fun ByteArray.hex() = joinToString("") { "%02x".format(it.toInt() and 255) }
}
