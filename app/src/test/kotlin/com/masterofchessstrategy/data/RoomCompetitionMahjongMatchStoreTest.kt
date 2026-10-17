package com.masterofchessstrategy.data

import com.masterofchessstrategy.engine.GameType
import com.masterofchessstrategy.games.competition_mahjong.match.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

/** Envelope fixtures are not native-legal replays; device tests cover native admission. */
class RoomCompetitionMahjongMatchStoreTest {
    @Test fun missingRowReturnsNullButWrongStablePlayerPreservesTheRecord() = runBlocking {
        val dao = MemoryDao()
        val store = RoomCompetitionMahjongMatchStore(dao) { 9 }
        assertNull(store.load(0))
        store.save(checkpoint(3, 4))
        val original = dao.row!!.checkpoint.copyOf()
        assertThrows(IOException::class.java) { runBlocking { store.load(0) } }
        assertArrayEquals(original, dao.row!!.checkpoint)
        assertEquals(3, store.load(3)!!.config.viewerPlayerId)
    }

    @Test fun crossRoundPhysicalSeatIsNotConfusedWithTheStablePlayer() = runBlocking {
        for (viewer in 0..3) for (index in listOf(0, 4, 8, 12)) {
            val dao = MemoryDao()
            val store = RoomCompetitionMahjongMatchStore(dao) { 42 }
            val source = checkpoint(viewer, index)
            store.save(source)
            val restored = store.load(viewer)!!
            assertEquals(viewer, dao.row!!.viewerPlayerId)
            assertEquals(viewer, restored.config.viewerPlayerId)
            assertEquals(source.config.handConfig(index).viewerSeat, restored.config.handConfig(index).viewerSeat)
            assertEquals(index, restored.activeHandIndex)
            assertEquals(source.ledger.results, restored.ledger.results)
            assertArrayEquals(source.engineRecord, restored.engineRecord)
        }
    }

    @Test fun corruptUnknownVersionAndMetadataAreNotTreatedAsMissing() = runBlocking {
        val dao = MemoryDao()
        val store = RoomCompetitionMahjongMatchStore(dao) { 9 }
        store.save(checkpoint())
        val original = dao.row!!
        for (bad in listOf(original.copy(formatVersion = 2), original.copy(viewerPlayerId = 4),
            original.copy(matchId = "not-the-record-id"), original.copy(updatedAtEpochMillis = -1),
            original.copy(checkpoint = byteArrayOf(1, 2, 3)))) {
            dao.row = bad
            assertThrows(IOException::class.java) { runBlocking { store.load(0) } }
            assertArrayEquals(bad.checkpoint, dao.row!!.checkpoint)
        }
    }

    @Test fun failedReplacementKeepsTheOldWholeCheckpoint() = runBlocking {
        val dao = MemoryDao()
        val store = RoomCompetitionMahjongMatchStore(dao) { 9 }
        store.save(checkpoint())
        val original = dao.row!!.checkpoint.copyOf()
        dao.fail = true
        assertThrows(IOException::class.java) { runBlocking { store.save(checkpoint(0, 4)) } }
        assertArrayEquals(original, dao.row!!.checkpoint)
    }

    @Test fun negativeClockRejectsBeforeAnyWrite() = runBlocking {
        val dao = MemoryDao()
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { RoomCompetitionMahjongMatchStore(dao) { -1 }.save(checkpoint()) }
        }
        assertNull(dao.row)
    }

    @Test fun immutableArchiveDoesNotRetainOrExposeMutableBytes() {
        val encoded = CompetitionMahjongMatchCheckpointCodec.encode(checkpoint())
        val archive = CompetitionMahjongMatchArchive(0, matchConfig().matchId, 2, encoded)
        val expected = encoded.copyOf()
        encoded.fill(0)
        archive.encodedCheckpoint.fill(0)
        archive.toEntity().checkpoint.fill(0)
        assertArrayEquals(expected, archive.encodedCheckpoint)
        assertEquals(archive, CompetitionMahjongMatchArchive.fromEntity(archive.toEntity()))
        assertFalse(archive.toString().contains(expected.contentToString()))
    }

    @Test fun wrongGameAndOversizedPayloadAreRejected() {
        val row = CompetitionMahjongMatchArchive(0, matchConfig().matchId, 2,
            CompetitionMahjongMatchCheckpointCodec.encode(checkpoint())).toEntity()
        assertThrows(IllegalArgumentException::class.java) {
            CompetitionMahjongMatchArchive.fromEntity(row.copy(gameTypeCode = GameType.LANZHOU_MAHJONG.code))
        }
        assertThrows(IllegalArgumentException::class.java) {
            CompetitionMahjongMatchArchive(0, matchConfig().matchId, 2,
                ByteArray(CompetitionMahjongMatchArchive.MAX_CHECKPOINT_BYTES + 1))
        }
    }

    private fun checkpoint(viewer: Int = 0, index: Int = 0): CompetitionMahjongMatchCheckpoint {
        val config = matchConfig(viewer)
        return CompetitionMahjongMatchCheckpoint(config, matchLedger(config, index), index,
            matchRecord(config.handConfig(index).viewerSeat))
    }

    private class MemoryDao : CompetitionMahjongMatchDao {
        var row: CompetitionMahjongMatchEntity? = null
        var fail = false
        override suspend fun find(gameTypeCode: Int) = row?.takeIf { it.gameTypeCode == gameTypeCode }
        override suspend fun listAll() = listOfNotNull(row)
        override suspend fun upsert(entity: CompetitionMahjongMatchEntity) {
            if (fail) throw IOException("injected write failure")
            row = entity.copy(checkpoint = entity.checkpoint.copyOf())
        }
        override suspend fun deleteAll() { row = null }
    }
}
