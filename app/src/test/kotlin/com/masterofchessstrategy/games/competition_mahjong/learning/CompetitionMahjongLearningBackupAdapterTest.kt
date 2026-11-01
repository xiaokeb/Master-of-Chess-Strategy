package com.masterofchessstrategy.games.competition_mahjong.learning

import com.masterofchessstrategy.data.*
import com.masterofchessstrategy.engine.GameType
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class CompetitionMahjongLearningBackupAdapterTest {
    private val adapter = CompetitionMahjongLearningBackupAdapter()

    @Test fun everyActualCurriculumPrefixRoundTripsWithoutOutcomesOrRecords() {
        var proof = CompetitionMahjongLearningProof()
        for(count in 0..4) {
            val bytes = CompetitionMahjongLearningProofCodec.encode(proof)
            val wire = adapter.encode(bytes)
            adapter.validate(wire,5)
            assertEquals("2",wire.fields[0])
            assertArrayEquals(bytes,adapter.decode(wire))
            assertTrue(adapter.prepareRestore(wire).completedRecordIds.isEmpty())
            if(count < 4) {
                val chapter = CompetitionMahjongLearningProofCatalog.chapterIds[count]
                proof = proof.confirm(CompetitionMahjongLearningProofRequest(UUID.randomUUID().toString(),chapter,
                    CompetitionMahjongLearningProofCatalog.cardIds(chapter),100L+count))
            }
        }
    }

    @Test fun newKindRequiresV5AndOnlyOwnsItsDedicatedMahjongSlot() {
        val wire = emptyRecord()
        assertThrows(IllegalArgumentException::class.java) { adapter.validate(wire,4) }
        assertEquals(CompetitionMahjongLearningBackupAdapter.SLOT,adapter.prepareRestore(wire).storageSlot)
        for(type in GameType.entries.filter { it != GameType.STANDARD_MAHJONG }) {
            assertThrows(IllegalArgumentException::class.java) { BackupStorageSlot(BackupStorageTable.COMPETITION_LEARNING,type) }
        }
    }

    @Test fun foreignKindOwnerAndWrongFieldCountsAreRejected() {
        val good = emptyRecord()
        val variants = listOf(GameBackupRecord("FOREIGN",good.fields),
            GameBackupRecord(good.kind,listOf("3",good.fields[1])),
            GameBackupRecord(good.kind,listOf("02",good.fields[1])),
            GameBackupRecord(good.kind,good.fields.drop(1)),
            GameBackupRecord(good.kind,good.fields+"extra"))
        variants.forEach { assertThrows(IllegalArgumentException::class.java) { adapter.decode(it) } }
    }

    @Test fun malformedHexOversizeAndSharedCountTextCannotBecomeProof() {
        val good = emptyRecord()
        for(hex in listOf("",good.fields[1].dropLast(1),good.fields[1].uppercase(),
            "00".repeat(4097),"00".repeat(8),"0104010000000000","four chapters")) {
            assertThrows(IllegalArgumentException::class.java) { adapter.decode(GameBackupRecord(good.kind,listOf("2",hex))) }
        }
    }

    @Test fun registryRejectsMissingAdapterAndDuplicateReceiptsBeforeReplacement() {
        val wire = emptyRecord()
        val registry = GameBackupRegistry(listOf(adapter))
        assertEquals(listOf(wire),registry.canonicalRecords(listOf(wire),5))
        assertThrows(IllegalArgumentException::class.java) { registry.validate(listOf(wire,wire),5) }
        assertThrows(IllegalArgumentException::class.java) { GameBackupRegistry(emptyList()).validate(listOf(wire),5) }
    }

    @Test fun inventoryIncludesEveryActualRowAndCannotBeSatisfiedByTheOldMatchSlot() {
        val slots = BackupStorageInventory.fromCodes(emptyList(),emptyList(),competitionLearningCodes=listOf(2,2))
        assertEquals(setOf(CompetitionMahjongLearningBackupAdapter.SLOT),slots)
        assertThrows(IllegalArgumentException::class.java) { GameBackupRegistry(emptyList()).requireSupportedSlots(slots) }
    }

    @Test fun missingAndExplicitEmptyRowsStayDistinguishableAndBytesAreOwned() = runBlocking {
        val dao = Rows()
        assertNull(CompetitionMahjongLearningBackupAdapter.readBytes(dao))
        val bytes = CompetitionMahjongLearningProofCodec.encode(CompetitionMahjongLearningProof())
        dao.row = CompetitionMahjongLearningProofRow(0,bytes)
        val actual = requireNotNull(CompetitionMahjongLearningBackupAdapter.readBytes(dao))
        actual[0] = 0
        assertArrayEquals(bytes,dao.row!!.proof)
    }

    @Test fun unknownOrOversizeMetadataRejectsBeforeAnyPayloadRead() = runBlocking {
        for(row in listOf(CompetitionMahjongLearningProofRow(9,ByteArray(8)),CompetitionMahjongLearningProofRow(0,ByteArray(4097)))) {
            val dao = Rows().apply { this.row=row }
            assertTrue(runCatching { CompetitionMahjongLearningBackupAdapter.readBytes(dao) }.exceptionOrNull() is IllegalArgumentException)
            assertEquals(0,dao.payloadReads)
        }
    }

    @Test fun corruptKnownPayloadIsNeverOmittedAsAnEmptyBackup() = runBlocking {
        val dao = Rows().apply { row=CompetitionMahjongLearningProofRow(0,ByteArray(8)) }
        assertTrue(runCatching { CompetitionMahjongLearningBackupAdapter.readBytes(dao) }.exceptionOrNull() is IllegalArgumentException)
        assertEquals(1,dao.payloadReads)
        assertArrayEquals(ByteArray(8),dao.row!!.proof)
    }

    private fun emptyRecord() = adapter.encode(CompetitionMahjongLearningProofCodec.encode(CompetitionMahjongLearningProof()))
    private class Rows : CompetitionMahjongLearningProofDao {
        var row:CompetitionMahjongLearningProofRow?=null
        var payloadReads=0
        override suspend fun readBoundedMetadata() = row?.let { listOf(CompetitionMahjongLearningProofMetadata(it.singletonId,it.proof.size.toLong())) } ?: emptyList()
        override suspend fun readBoundedPayload(minimumBytes:Int,maximumBytes:Int):ByteArray? {
            payloadReads++
            return row?.takeIf { it.singletonId == 0 && it.proof.size in minimumBytes..maximumBytes }?.proof?.copyOf()
        }
        override suspend fun upsert(row:CompetitionMahjongLearningProofRow) { this.row=row.copy(proof=row.proof.copyOf()) }
        override suspend fun deleteAll() { row=null }
    }
}
