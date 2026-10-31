package com.masterofchessstrategy.games.competition_mahjong.data

import com.masterofchessstrategy.data.*
import com.masterofchessstrategy.engine.GameType
import com.masterofchessstrategy.games.competition_mahjong.match.storage.CompetitionMahjongFormalSnapshot
import com.masterofchessstrategy.games.competition_mahjong.match.storage.CompetitionMahjongFormalSnapshotCodec
import org.junit.Assert.*
import org.junit.Test

/** Real registry ownership contract and MCTX codec; no persistence or Native admission is claimed. */
class CompetitionFormalRegistryTest {
    private val adapter=object:GameBackupAdapter {
        override val descriptor=GameBackupDescriptor(CompetitionMahjongFormalBackupBundle.KIND,5,
            BackupStorageSlot(BackupStorageTable.COMPETITION_FORMAL,GameType.STANDARD_MAHJONG),4,50,
            CompetitionMahjongFormalBackupBundle.MAXIMUM_RECORD_BYTES)
        override fun validate(record:GameBackupRecord,containerVersion:Int) {
            require(containerVersion==5)
            CompetitionMahjongFormalBackupBundle.normalize(record)
        }
        override fun normalize(record:GameBackupRecord,containerVersion:Int)=CompetitionMahjongFormalBackupBundle.normalize(record)
        override suspend fun read(database:MocsDatabase):List<GameBackupRecord> = error("No database in this test")
        override fun prepareRestore(record:GameBackupRecord):PreparedGameBackup = error("No restore in this ownership test")
    }

    @Test fun allFourViewerRecordsSatisfyActualPublicRegistryOwnership() {
        val records=(0..3).map { viewer -> CompetitionMahjongFormalBackupBundle.encode(viewer,
            CompetitionMahjongFormalSnapshotCodec.encode(CompetitionMahjongFormalSnapshot(viewer))) }
        val registry=GameBackupRegistry(listOf(adapter))
        registry.validate(records,5)
        assertTrue(records.all { it.fields.first()==GameType.STANDARD_MAHJONG.code.toString() })
        assertEquals(listOf("0","1","2","3"),records.map { it.fields[1] })
    }

    @Test fun oldTwoFieldBundleAndForeignGamePrefixCannotPassRegistry() {
        val proper=CompetitionMahjongFormalBackupBundle.encode(0,
            CompetitionMahjongFormalSnapshotCodec.encode(CompetitionMahjongFormalSnapshot(0)))
        val registry=GameBackupRegistry(listOf(adapter))
        for(bad in listOf(GameBackupRecord(proper.kind,proper.fields.drop(1)),
            GameBackupRecord(proper.kind,listOf("1")+proper.fields.drop(1)))) {
            assertThrows(IllegalArgumentException::class.java) { registry.validate(listOf(bad),5) }
        }
    }
}
