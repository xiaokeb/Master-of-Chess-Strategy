package com.masterofchessstrategy.games.competition_mahjong.learning

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import com.masterofchessstrategy.data.MocsDatabase
import com.masterofchessstrategy.games.lanzhou_mahjong.data.withOwnedLanzhouDatabase
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Actual SQLite/KSP contracts; only UUID-owned databases, never the user's default save. */
class CompetitionMahjongLearningProofRoomInstrumentedTest {
    @get:Rule val migration = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), MocsDatabase::class.java)

    @Test fun migration22To23KeepsLegacyProgressAndFormalOwnerWithoutInventingProof() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "cm-learning-migration-${UUID.randomUUID()}.db"
        assertFalse(context.getDatabasePath(name).exists())
        try {
            migration.createDatabase(name, 22).use { db ->
                db.execSQL("INSERT INTO tutorial_progress VALUES (2,1,4,1,7)")
                db.execSQL("INSERT INTO competition_mahjong_formal VALUES (0,'old-owner',4,9,NULL)")
            }
            migration.runMigrationsAndValidate(name, 23, true, MocsDatabase.MIGRATION_22_23).use { db ->
                db.query("SELECT completedStepCount,isCompleted,updatedAtEpochMillis FROM tutorial_progress WHERE gameTypeCode=2").use {
                    assertTrue(it.moveToFirst()); assertEquals(4,it.getInt(0)); assertEquals(1,it.getInt(1)); assertEquals(7L,it.getLong(2))
                }
                db.query("SELECT ownerId,epoch,revision FROM competition_mahjong_formal WHERE viewerPlayerId=0").use {
                    assertTrue(it.moveToFirst()); assertEquals("old-owner",it.getString(0)); assertEquals(4L,it.getLong(1)); assertEquals(9L,it.getLong(2))
                }
                db.query("SELECT count(*) FROM competition_mahjong_learning_proof").use {
                    assertTrue(it.moveToFirst()); assertEquals(0,it.getInt(0))
                }
            }
        } finally { context.deleteDatabase(name) }
    }

    @Test fun oldSharedFourStepCompletionDoesNotGrantAnyNewChapter() = withOwnedLanzhouDatabase { f ->
        f.db.openHelper.writableDatabase.execSQL("INSERT INTO tutorial_progress VALUES (2,1,4,1,7)")
        assertEquals(0, ready(store(f.db).load()).completedChapterCount)
        assertTrue(f.db.competitionMahjongLearningProofDao().readBoundedMetadata().isEmpty())
        f.db.openHelper.readableDatabase.query("SELECT updatedAtEpochMillis FROM tutorial_progress WHERE gameTypeCode=2").use {
            assertTrue(it.moveToFirst()); assertEquals(7L,it.getLong(0))
        }
    }

    @Test fun confirmedChapterReopensWithExactBytesAndDuplicateRequestKeepsFirstReceipt() = withOwnedLanzhouDatabase { f ->
        val request = newRequest(0)
        val expected = CompetitionMahjongLearningProofCodec.encode(store(f.db).confirm(request))
        f.reopen()
        val reopened = store(f.db)
        assertArrayEquals(expected, CompetitionMahjongLearningProofCodec.encode(ready(reopened.load())))
        assertArrayEquals(expected, CompetitionMahjongLearningProofCodec.encode(reopened.confirm(request)))
        assertTrue(runCatching { reopened.confirm(newRequest(0)) }.exceptionOrNull() is IOException)
        assertArrayEquals(expected, f.db.competitionMahjongLearningProofDao().readBoundedPayload(8,4096))
    }

    @Test fun extraForeignSingletonIsPreservedAndCannotBeConfirmedOver() = withOwnedLanzhouDatabase { f ->
        val dao = f.db.competitionMahjongLearningProofDao()
        val bytes = CompetitionMahjongLearningProofCodec.encode(CompetitionMahjongLearningProof())
        dao.upsert(CompetitionMahjongLearningProofRow(0,bytes))
        dao.upsert(CompetitionMahjongLearningProofRow(9,byteArrayOf(1,2)))
        val before = dao.readBoundedMetadata()
        assertEquals(CompetitionMahjongLearningProofRead.Incompatible, store(f.db).load())
        assertTrue(runCatching { store(f.db).confirm(newRequest(0)) }.exceptionOrNull() is IOException)
        assertEquals(before,dao.readBoundedMetadata())
        assertArrayEquals(bytes,dao.readBoundedPayload(8,4096))
        assertEquals(listOf(2,2),f.db.backupStorageInventoryDao().competitionLearningGameTypeCodes())
    }

    @Test fun corruptAndOversizedPayloadsRemainIntactWithoutUnboundedCursorReads() = withOwnedLanzhouDatabase { f ->
        val dao = f.db.competitionMahjongLearningProofDao()
        for (bytes in listOf(byteArrayOf(1),ByteArray(3_132_064) { 7 })) {
            dao.upsert(CompetitionMahjongLearningProofRow(0,bytes))
            assertEquals(CompetitionMahjongLearningProofRead.Incompatible,store(f.db).load())
            assertTrue(runCatching { store(f.db).confirm(newRequest(0)) }.exceptionOrNull() is IOException)
            assertEquals(bytes.size.toLong(),dao.readBoundedMetadata().single().proofBytes)
            assertNull(dao.readBoundedPayload(8,4096))
        }
    }

    @Test fun actualInsertFaultRollsBackAndExactRequestCanBeRetried() = withOwnedLanzhouDatabase { f ->
        val store = store(f.db)
        val first = store.confirm(newRequest(0))
        val before = CompetitionMahjongLearningProofCodec.encode(first)
        val next = newRequest(1)
        f.db.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_owned_learning BEFORE INSERT ON competition_mahjong_learning_proof BEGIN SELECT RAISE(ABORT,'owned confirm failure'); END")
        try {
            assertTrue(runCatching { store.confirm(next) }.isFailure)
            assertArrayEquals(before,CompetitionMahjongLearningProofCodec.encode(ready(store.load())))
        } finally { f.db.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_owned_learning") }
        val committed = store.confirm(next)
        assertEquals(2,committed.completedChapterCount)
        assertEquals(next,committed.receipts.last().request)
    }

    @Test fun twoStoreInstancesConfirmingTheSameRequestCannotDuplicateTheChapter() = withOwnedLanzhouDatabase { f ->
        val request = newRequest(0)
        coroutineScope {
            val one = async { store(f.db).confirm(request) }
            val two = async { store(f.db).confirm(request) }
            assertArrayEquals(CompetitionMahjongLearningProofCodec.encode(one.await()),
                CompetitionMahjongLearningProofCodec.encode(two.await()))
        }
        assertEquals(1,ready(store(f.db).load()).completedChapterCount)
        assertEquals(1,f.db.competitionMahjongLearningProofDao().readBoundedMetadata().size)
    }

    private fun store(db:MocsDatabase) = RoomCompetitionMahjongLearningProofStore(db,db.competitionMahjongLearningProofDao())
    private fun ready(result:CompetitionMahjongLearningProofRead) = (result as CompetitionMahjongLearningProofRead.Ready).proof
    private fun newRequest(index:Int):CompetitionMahjongLearningProofRequest {
        val chapter = CompetitionMahjongLearningProofCatalog.chapterIds[index]
        return CompetitionMahjongLearningProofRequest(UUID.randomUUID().toString(),chapter,
            CompetitionMahjongLearningProofCatalog.cardIds(chapter),100L+index)
    }
}
