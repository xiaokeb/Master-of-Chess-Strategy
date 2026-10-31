package com.masterofchessstrategy.games.competition_mahjong.match.storage.room

import androidx.room.testing.MigrationTestHelper
import androidx.room.withTransaction
import androidx.test.platform.app.InstrumentationRegistry
import com.masterofchessstrategy.data.*
import com.masterofchessstrategy.games.lanzhou_mahjong.data.withOwnedLanzhouDatabase
import java.util.UUID
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Real SQLite and generated DAO. Arbitrary large BLOBs here are not admitted MCTX records. */
class CompetitionMahjongFormalSchemaInstrumentedTest {
    @get:Rule val migration = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), MocsDatabase::class.java)

    @Test fun migration21To22PreservesOldSourcesAndCreatesNoClaimOrFormalFacts() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "cm-formal-migration-${UUID.randomUUID()}.db"
        assertFalse(context.getDatabasePath(name).exists())
        try {
            migration.createDatabase(name, 21).use {
                it.execSQL("INSERT INTO competition_mahjong_matches VALUES (2,1,'old-match',4,X'010203',70)")
                it.execSQL("INSERT INTO fangqi_learning_progress VALUES ('lesson:opening',1,55)")
                it.execSQL("INSERT INTO lanzhou_mahjong_learning_progress VALUES (0,2,10,77)")
                it.execSQL("INSERT INTO match_outcomes VALUES ('old-outcome',0,1,0,0,1,88)")
            }
            migration.runMigrationsAndValidate(name, 22, true, MocsDatabase.MIGRATION_21_22).use { db ->
                db.query("SELECT count(*) FROM competition_mahjong_formal").use { assertTrue(it.moveToFirst()); assertEquals(0,it.getInt(0)) }
                db.query("SELECT viewerPlayerId,checkpoint,updatedAtEpochMillis FROM competition_mahjong_matches").use {
                    assertTrue(it.moveToFirst()); assertEquals(1,it.getInt(0)); assertArrayEquals(byteArrayOf(1,2,3),it.getBlob(1)); assertEquals(70L,it.getLong(2))
                }
                db.query("SELECT completedAtEpochMillis FROM fangqi_learning_progress").use { assertTrue(it.moveToFirst()); assertEquals(55L,it.getLong(0)) }
                db.query("SELECT updatedAtEpochMillis FROM lanzhou_mahjong_learning_progress").use { assertTrue(it.moveToFirst()); assertEquals(77L,it.getLong(0)) }
                db.query("SELECT settledAtEpochMillis FROM match_outcomes").use { assertTrue(it.moveToFirst()); assertEquals(88L,it.getLong(0)) }
            }
        } finally { context.deleteDatabase(name) }
    }

    @Test fun freshDatabaseHasNoImplicitClaimsAndOldMatchDoesNotPopulateFormalTable() = withOwnedLanzhouDatabase { f ->
        val dao=f.db.competitionMahjongFormalRoomDao()
        for (viewer in 0..3) assertNull(dao.find(viewer))
        f.db.competitionMahjongMatchDao().upsert(CompetitionMahjongMatchEntity(2,0,"legacy",4,byteArrayOf(1),1))
        assertNull(dao.find(0))
        assertTrue(f.db.backupStorageInventoryDao().competitionFormalGameTypeCodes().isEmpty())
    }

    @Test fun metadataAndBoundedChunksRoundTripPayloadLargerThanCursorWindowAfterReopen() = withOwnedLanzhouDatabase { f ->
        val bytes=ByteArray(3_132_064) { (it * 31).toByte() }
        val owner=UUID.randomUUID().toString()
        f.db.competitionMahjongFormalRoomDao().insert(CompetitionMahjongFormalRoomRow(2,owner,1,4,bytes))
        bytes[0]=99
        f.reopen()
        val dao=f.db.competitionMahjongFormalRoomDao()
        f.db.withTransaction {
            val metadata=requireNotNull(dao.find(2))
            assertEquals(3_132_064,metadata.mctxSize)
            assertEquals(owner,metadata.ownerId)
            val restored=ByteArray(metadata.mctxSize!!)
            var offset=0
            while(offset<restored.size) {
                val count=minOf(65_536,restored.size-offset)
                val chunk=requireNotNull(dao.readChunk(2,owner,1,4,offset+1,count))
                assertEquals(count,chunk.size)
                chunk.copyInto(restored,offset);offset+=count
            }
            assertArrayEquals(ByteArray(restored.size) { (it * 31).toByte() },restored)
        }
    }

    @Test fun ownerEpochRevisionCasFencesOldAndStaleWritesWithoutReclaim() = withOwnedLanzhouDatabase { f ->
        val dao=f.db.competitionMahjongFormalRoomDao();val first=UUID.randomUUID().toString();val next=UUID.randomUUID().toString()
        dao.insert(CompetitionMahjongFormalRoomRow(0,first,1,0,null))
        assertNull(dao.find(0)!!.mctxSize)
        assertEquals(1,dao.claim(0,first,1,0,next,2,1))
        assertEquals(0,dao.replace(0,first,1,0,1,byteArrayOf(9)))
        assertNull(dao.readChunk(0,first,1,0,1,64))
        assertEquals(1,dao.replace(0,next,2,1,2,byteArrayOf(1,2)))
        assertEquals(0,dao.replace(0,next,2,1,2,byteArrayOf(8)))
        assertEquals(2L,dao.find(0)!!.revision)
        assertArrayEquals(byteArrayOf(1,2),dao.readChunk(0,next,2,2,1,64))
    }

    @Test fun enclosingTransactionFailureRollsBackLeaseAndPayloadTogether() = withOwnedLanzhouDatabase { f ->
        val dao=f.db.competitionMahjongFormalRoomDao();val owner=UUID.randomUUID().toString()
        dao.insert(CompetitionMahjongFormalRoomRow(1,owner,1,0,byteArrayOf(3)))
        val before=dao.find(1)
        val fault=IllegalStateException("owned transaction fault")
        try {
            f.db.withTransaction {
                assertEquals(1,dao.claim(1,owner,1,0,"replacement",2,1))
                assertEquals(1,dao.replace(1,"replacement",2,1,2,byteArrayOf(4)))
                throw fault
            }
        } catch(actual:IllegalStateException) { assertEquals(fault.message,actual.message) }
        assertEquals(before,dao.find(1))
        assertArrayEquals(byteArrayOf(3),dao.readChunk(1,owner,1,0,1,64))
    }

    @Test fun viewerPrimaryKeyAndDuplicateInsertCannotOverwriteExistingRow() = withOwnedLanzhouDatabase { f ->
        val dao=f.db.competitionMahjongFormalRoomDao();val owner=UUID.randomUUID().toString()
        for(viewer in 0..3) dao.insert(CompetitionMahjongFormalRoomRow(viewer,owner,1,0,byteArrayOf(viewer.toByte())))
        assertTrue(runCatching { dao.insert(CompetitionMahjongFormalRoomRow(0,"other",2,1,byteArrayOf(99))) }.isFailure)
        for(viewer in 0..3) assertArrayEquals(byteArrayOf(viewer.toByte()),dao.readChunk(viewer,owner,1,0,1,64))
    }
}
