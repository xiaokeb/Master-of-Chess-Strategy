package com.masterofchessstrategy.games.competition_mahjong.data

import androidx.test.platform.app.InstrumentationRegistry
import com.masterofchessstrategy.data.*
import com.masterofchessstrategy.endgame.ChineseChessEndgamePackParser
import com.masterofchessstrategy.engine.GameType
import com.masterofchessstrategy.games.competition_mahjong.match.storage.room.CompetitionMahjongFormalRoomRow
import com.masterofchessstrategy.games.lanzhou_mahjong.data.withOwnedLanzhouDatabase
import org.junit.Assert.*
import org.junit.Test

/** Real global backup transaction with an explicitly declared test adapter, not a production MCTX factory. */
class CompetitionFormalReplacementGuardInstrumentedTest {
    @Test fun registeredSlotStillRejectsUnknownSourceSeatBeforeAdapterReads() = withOwnedLanzhouDatabase { f ->
        val adapter=DeclaredFormalSlot()
        val repository=repository(f.db,adapter)
        f.db.competitionMahjongFormalRoomDao().insert(CompetitionMahjongFormalRoomRow(9,"unknown",1,0,null))
        val failure=runCatching { repository.export(1) }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
        assertEquals(0,adapter.reads)
        assertNotNull(f.db.competitionMahjongFormalRoomDao().find(9))
    }

    @Test fun registeredSlotStillRejectsUnknownTargetSeatBeforeClearOrInstallationFence() = withOwnedLanzhouDatabase { f ->
        val adapter=DeclaredFormalSlot()
        val repository=repository(f.db,adapter)
        val empty=repository.export(1)
        val kept=MatchOutcomeEntity("kept-before-replacement",0,1,0,0,1,2)
        f.db.matchOutcomeDao().insert(kept)
        f.db.competitionMahjongFormalRoomDao().insert(CompetitionMahjongFormalRoomRow(-1,"unknown",1,0,null))
        val before=f.db.competitionMahjongFormalRoomDao().find(-1)
        // A rollback alone would hide an incorrectly reordered clear; reject even reaching DELETE.
        f.db.openHelper.writableDatabase.execSQL("""
            CREATE TRIGGER reject_premature_clear BEFORE DELETE ON match_outcomes
            BEGIN SELECT RAISE(ABORT, 'clear was reached'); END
        """.trimIndent())
        assertTrue(runCatching { repository.restore(empty) }.exceptionOrNull() is IllegalArgumentException)
        assertEquals(before,f.db.competitionMahjongFormalRoomDao().find(-1))
        assertEquals(listOf(kept),f.db.matchOutcomeDao().listAll())
        assertNull(f.db.localDataMigrationDao().find(GoLegacyImportScopes.SESSION))
        assertEquals(0,adapter.preparations)
    }

    private fun repository(db:MocsDatabase,adapter:GameBackupAdapter)=RoomLocalDataBackupRepository(db,
        ChineseChessEndgamePackParser.loadBundled(InstrumentationRegistry.getInstrumentation().targetContext.assets),
        { true },GameBackupRegistry(listOf(adapter)))

    private class DeclaredFormalSlot:GameBackupAdapter {
        var reads=0
        var preparations=0
        override val descriptor=GameBackupDescriptor("COMPETITION_FORMAL",5,
            BackupStorageSlot(BackupStorageTable.COMPETITION_FORMAL,GameType.STANDARD_MAHJONG),4,50,1000)
        override fun validate(record:GameBackupRecord,containerVersion:Int)=Unit
        override suspend fun read(database:MocsDatabase):List<GameBackupRecord> { reads++;return emptyList() }
        override fun prepareRestore(record:GameBackupRecord):PreparedGameBackup {
            preparations++
            error("No records are supplied in this guard test")
        }
    }
}
