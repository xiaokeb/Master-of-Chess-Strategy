package com.masterofchessstrategy.games.competition_mahjong.data

import com.masterofchessstrategy.data.*
import com.masterofchessstrategy.engine.GameType
import com.masterofchessstrategy.games.competition_mahjong.match.storage.room.CompetitionMahjongFormalRoomRow
import com.masterofchessstrategy.games.lanzhou_mahjong.data.withOwnedLanzhouDatabase
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

/** Production complete backup with UUID disk databases; formal adapters are intentionally absent. */
class CompetitionFormalInventoryInstrumentedTest {
    private val slot=BackupStorageSlot(BackupStorageTable.COMPETITION_FORMAL,GameType.STANDARD_MAHJONG)

    @Test fun emptyFormalTableAllowsExistingCompleteBackupAndRestore() = withOwnedLanzhouDatabase { f ->
        val original=outcome()
        f.db.matchOutcomeDao().insert(original)
        val bytes=f.repository().export(10)
        f.db.matchOutcomeDao().insert(original.copy(matchId="later"))
        f.repository().restore(bytes)
        assertEquals(listOf(original),f.db.matchOutcomeDao().listAll())
        assertTrue(f.db.backupStorageInventoryDao().competitionFormalGameTypeCodes().isEmpty())
    }

    @Test fun unmigratedClaimBlocksProductionExportAndRestoreBeforeAnyClear() = withOwnedLanzhouDatabase { f ->
        val original=outcome();f.db.matchOutcomeDao().insert(original)
        val bytes=f.repository().export(10)
        val dao=f.db.competitionMahjongFormalRoomDao()
        dao.insert(CompetitionMahjongFormalRoomRow(0,UUID.randomUUID().toString(),1,0,null))
        val before=dao.find(0)
        assertTrue(slot in BackupStorageInventory.read(f.db))
        assertTrue(runCatching { f.repository().export(20) }.isFailure)
        assertTrue(runCatching { f.repository().restore(bytes) }.isFailure)
        assertEquals(before,dao.find(0))
        assertEquals(listOf(original),f.db.matchOutcomeDao().listAll())
    }

    @Test fun corruptOrForeignClaimRowsAreNotFilteredOutOfInventory() {
        val rows=listOf(
            CompetitionMahjongFormalRoomRow(9,"bad-viewer",-1,-1,null),
            CompetitionMahjongFormalRoomRow(0,"bad-owner",1,0,byteArrayOf()),
            CompetitionMahjongFormalRoomRow(1,UUID.randomUUID().toString(),1,0,byteArrayOf(1,2,3)))
        for(row in rows) withOwnedLanzhouDatabase { f ->
            f.db.matchOutcomeDao().insert(outcome())
            val bytes=f.repository().export(10)
            val dao=f.db.competitionMahjongFormalRoomDao();dao.insert(row)
            val before=dao.find(row.viewerPlayerId)
            assertEquals(listOf(2),f.db.backupStorageInventoryDao().competitionFormalGameTypeCodes())
            assertTrue(slot in BackupStorageInventory.read(f.db))
            assertTrue(runCatching { f.repository().export(20) }.isFailure)
            assertTrue(runCatching { f.repository().restore(bytes) }.isFailure)
            assertEquals(before,dao.find(row.viewerPlayerId))
            assertEquals(listOf(outcome()),f.db.matchOutcomeDao().listAll())
        }
    }

    @Test fun protectionSurvivesDatabaseReopenAndPreservesOldMcmtSource() = withOwnedLanzhouDatabase { f ->
        val bytes=f.repository().export(10)
        val legacy=CompetitionMahjongMatchEntity(2,0,"old-only",4,byteArrayOf(7,8),5)
        f.db.competitionMahjongMatchDao().upsert(legacy)
        val owner=UUID.randomUUID().toString()
        f.db.competitionMahjongFormalRoomDao().insert(CompetitionMahjongFormalRoomRow(3,owner,1,0,null))
        f.reopen()
        assertTrue(slot in BackupStorageInventory.read(f.db))
        assertTrue(runCatching { f.repository().restore(bytes) }.isFailure)
        assertEquals(owner,f.db.competitionMahjongFormalRoomDao().find(3)!!.ownerId)
        val old=f.db.competitionMahjongMatchDao().find(2)!!
        assertEquals(legacy.matchId,old.matchId)
        assertArrayEquals(legacy.checkpoint,old.checkpoint)
    }

    private fun outcome()=MatchOutcomeEntity("inventory-kept",0,1,0,0,1,1)
}
