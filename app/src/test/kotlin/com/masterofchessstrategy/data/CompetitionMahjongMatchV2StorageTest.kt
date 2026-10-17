package com.masterofchessstrategy.data

import com.masterofchessstrategy.engine.Difficulty
import com.masterofchessstrategy.engine.GameType
import com.masterofchessstrategy.games.competition_mahjong.data.CompetitionMahjongBackupAdapter
import com.masterofchessstrategy.games.competition_mahjong.match.*
import com.masterofchessstrategy.games.competition_mahjong.match.policy.CompetitionMahjongAssistanceUsage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32

/** Envelope-only fixtures; native replay and real SQL are checked separately on the emulator. */
class CompetitionMahjongMatchV2StorageTest {
    private val adapter = CompetitionMahjongBackupAdapter()
    private val registry = GameBackupRegistry(listOf(adapter))
    @Test fun everyDifficultyPreservesItsCountsInTheExistingOuterFormat() = runBlocking {
        val usages = listOf(CompetitionMahjongAssistanceUsage(Difficulty.EASY, 37, 48),
            CompetitionMahjongAssistanceUsage(Difficulty.MEDIUM, 2, 3),
            CompetitionMahjongAssistanceUsage(Difficulty.HARD, 1, 1),
            CompetitionMahjongAssistanceUsage(Difficulty.MASTER))
        for (usage in usages) {
            val dao = MemoryDao()
            val store = RoomCompetitionMahjongMatchStore(dao) { 42 }
            val source = checkpoint(usage)
            store.save(source)
            assertEquals(1, dao.row!!.formatVersion)
            assertEquals(2, dao.row!!.checkpoint[4].toInt())
            val restored = store.load(0)!!
            assertEquals(usage, restored.assistance)
            assertArrayEquals(source.engineRecord, restored.engineRecord)
        }
    }

    @Test fun pendingUndoAndAllowanceSurviveCanonicalBackupWithoutBeingCompleted() {
        val usage = CompetitionMahjongAssistanceUsage(Difficulty.HARD, 1, 1, true)
        val source = checkpoint(usage)
        val archive = CompetitionMahjongMatchArchive(0, source.config.matchId, 42,
            CompetitionMahjongMatchCheckpointCodec.encode(source))
        val snapshot = LocalDataSnapshot(43, gameExtensions = listOf(adapter.encodeArchive(archive)))
        val bytes = LocalDataBackupCodec.encode(snapshot, registry)
        assertTrue(bytes.toString(Charsets.UTF_8).startsWith("MOCS-BACKUP|4\n"))
        val restored = LocalDataBackupCodec.decode(bytes, registry)
        assertEquals(usage, adapter.decodeRecord(restored.gameExtensions.single()).checkpoint.assistance)
        assertArrayEquals(bytes, LocalDataBackupCodec.encode(restored, registry))
    }

    @Test fun failedReplacementKeepsTheOldCountersAndPendingState() = runBlocking {
        val dao = MemoryDao()
        val store = RoomCompetitionMahjongMatchStore(dao) { 42 }
        val old = checkpoint(CompetitionMahjongAssistanceUsage(Difficulty.MEDIUM, 1, 2, true))
        store.save(old)
        val original = dao.row!!.checkpoint.copyOf()
        dao.fail = true
        assertThrows(IOException::class.java) { runBlocking {
            store.save(checkpoint(CompetitionMahjongAssistanceUsage(Difficulty.MEDIUM, 2, 2)))
        } }
        assertArrayEquals(original, dao.row!!.checkpoint)
        assertEquals(old.assistance, store.load(0)!!.assistance)
    }

    @Test fun oldV1IsPreservedWithoutInventingUsage() = runBlocking {
        val source = checkpoint(null)
        val bytes = CompetitionMahjongMatchCheckpointCodec.encode(source)
        val dao = MemoryDao()
        dao.row = CompetitionMahjongMatchEntity(GameType.STANDARD_MAHJONG.code, 0,
            source.config.matchId, 1, bytes, 42)
        val loaded = RoomCompetitionMahjongMatchStore(dao).load(0)!!
        assertNull(loaded.assistance)
        assertArrayEquals(bytes, CompetitionMahjongMatchCheckpointCodec.encode(loaded))
        assertArrayEquals(bytes, dao.row!!.checkpoint)
    }

    @Test fun repairedCrcDoesNotMakeUnknownPolicyOrOverspentAllowanceValid() = runBlocking {
        val source = checkpoint(CompetitionMahjongAssistanceUsage(Difficulty.HARD, 1))
        val encoded = CompetitionMahjongMatchCheckpointCodec.encode(source)
        // Empty ledger: header12, UUID length1 + UUID36, seed8, viewer/level/index/count4.
        val offset = 61
        for (mutate in listOf<(ByteArray) -> Unit>(
            { it[offset] = 2 }, { it[offset + 2] = 2 },
            { ByteBuffer.wrap(it).order(ByteOrder.BIG_ENDIAN).putInt(offset + 4, 2) },
        )) {
            val bytes = encoded.copyOf().also(mutate)
            ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN).putInt(bytes.size - 4,
                CRC32().apply { update(bytes, 0, bytes.size - 4) }.value.toInt())
            val dao = MemoryDao().also { it.row = CompetitionMahjongMatchEntity(
                GameType.STANDARD_MAHJONG.code, 0, source.config.matchId, 1, bytes, 42) }
            assertThrows(IOException::class.java) { runBlocking { RoomCompetitionMahjongMatchStore(dao).load(0) } }
            assertArrayEquals(bytes, dao.row!!.checkpoint)
        }
    }

    private fun checkpoint(usage: CompetitionMahjongAssistanceUsage?): CompetitionMahjongMatchCheckpoint {
        val config = matchConfig().copy(botLevel = usage?.difficulty?.code ?: 2)
        return CompetitionMahjongMatchCheckpoint(config, CompetitionMahjongMatchLedger.empty(), 0, matchRecord(0), usage)
    }

    private class MemoryDao : CompetitionMahjongMatchDao {
        var row: CompetitionMahjongMatchEntity? = null
        var fail = false
        override suspend fun find(gameTypeCode: Int) = row?.takeIf { it.gameTypeCode == gameTypeCode }
        override suspend fun listAll() = listOfNotNull(row)
        override suspend fun upsert(entity: CompetitionMahjongMatchEntity) {
            if (fail) throw IOException("injected v2 replacement failure")
            row = entity.copy(checkpoint = entity.checkpoint.copyOf())
        }
        override suspend fun deleteAll() { row = null }
    }
}
