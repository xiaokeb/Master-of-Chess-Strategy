package com.masterofchessstrategy.games.competition_mahjong.learning

import androidx.room.withTransaction
import com.masterofchessstrategy.data.*
import com.masterofchessstrategy.games.lanzhou_mahjong.data.withOwnedLanzhouDatabase
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import org.junit.Assert.*
import org.junit.Test

/** Real production composition and UUID disk Room; no legacy count is treated as a chapter fact. */
class CompetitionMahjongLearningBackupInstrumentedTest {
    @Test fun productionRoundTripKeepsReceiptsAndLegacyHistoryAndRetiresOldWriter() = withOwnedLanzhouDatabase { f ->
        f.db.openHelper.writableDatabase.execSQL("INSERT INTO tutorial_progress VALUES (2,1,4,1,7)")
        val old = guarded(f.db)
        old.confirm(request(0)); val expected = bytes(old.confirm(request(1)))
        val backup = f.repository().export(20)
        old.confirm(request(2))
        f.repository().restore(backup)
        denied { old.load() }; denied { old.confirm(request(2)) }
        assertArrayEquals(expected,bytes(ready(guarded(f.db).load())))
        assertEquals(4,f.db.tutorialProgressDao().find(2)!!.completedStepCount)
        assertEquals(7L,f.db.tutorialProgressDao().find(2)!!.updatedAtEpochMillis)
        f.reopen()
        assertArrayEquals(expected,bytes(ready(guarded(f.db).load())))
    }

    @Test fun backupWithoutProofClearsDedicatedFactsWithoutUpgradingSharedFourSteps() = withOwnedLanzhouDatabase { f ->
        f.db.openHelper.writableDatabase.execSQL("INSERT INTO tutorial_progress VALUES (2,1,4,1,7)")
        val previous = f.repository().export(10)
        val old = guarded(f.db)
        old.confirm(request(0))
        f.repository().restore(previous)
        denied { old.load() }
        assertEquals(0,ready(guarded(f.db).load()).completedChapterCount)
        assertTrue(f.db.competitionMahjongLearningProofDao().readBoundedMetadata().isEmpty())
        assertTrue(f.db.tutorialProgressDao().find(2)!!.isCompleted)
    }

    @Test fun checksummedBadIncomingProofIsRejectedBeforeAnyClear() = withOwnedLanzhouDatabase { f ->
        val old = guarded(f.db)
        val kept = bytes(old.confirm(request(0)))
        val wire = GameBackupRecord(CompetitionMahjongLearningBackupAdapter.KIND,listOf("2","00".repeat(8)))
        // Test-only envelope encoder permits the malformed payload; production admission must reject it.
        val backup = LocalDataBackupCodec.encode(LocalDataSnapshot(20,gameExtensions=listOf(wire)),
            GameBackupRegistry(listOf(WireOnlyAdapter())))
        f.db.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_learning_clear BEFORE DELETE ON competition_mahjong_learning_proof BEGIN SELECT RAISE(ABORT,'clear was reached'); END")
        try {
            assertTrue(runCatching { f.repository().restore(backup) }.exceptionOrNull() is IllegalArgumentException)
            denied { old.load() }
            assertArrayEquals(kept,bytes(ready(guarded(f.db).load())))
        } finally { f.db.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_learning_clear") }
    }

    @Test fun corruptAndForeignTargetRowsAreRejectedBeforeDeleteAndRemainOnDisk() = withOwnedLanzhouDatabase { f ->
        val backup = f.repository().export(10)
        val dao = f.db.competitionMahjongLearningProofDao()
        f.db.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_learning_clear BEFORE DELETE ON competition_mahjong_learning_proof BEGIN SELECT RAISE(ABORT,'clear was reached'); END")
        try {
            for(row in listOf(CompetitionMahjongLearningProofRow(0,ByteArray(8)),CompetitionMahjongLearningProofRow(9,ByteArray(8)))) {
                dao.upsert(row)
                val before = dao.readBoundedMetadata()
                assertTrue(runCatching { f.repository().restore(backup) }.exceptionOrNull() is IllegalArgumentException)
                assertEquals(before,dao.readBoundedMetadata())
            }
        } finally { f.db.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_learning_clear") }
    }

    @Test fun insertFailureRollsBackTheWholeReplacementButStillRetiresOldMemory() = withOwnedLanzhouDatabase { f ->
        val old = guarded(f.db)
        old.confirm(request(0)); old.confirm(request(1))
        val backup = f.repository().export(20)
        val kept = bytes(old.confirm(request(2)))
        f.db.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_learning_install BEFORE INSERT ON competition_mahjong_learning_proof BEGIN SELECT RAISE(ABORT,'owned install failure'); END")
        try {
            assertTrue(runCatching { f.repository().restore(backup) }.isFailure)
            denied { old.load() }
            assertArrayEquals(kept,bytes(ready(guarded(f.db).load())))
        } finally { f.db.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_learning_install") }
    }

    @Test fun actualFactoryWaitsForAcceptedConfirmationAndRejectsLaterOldWriter() = withOwnedLanzhouDatabase { f ->
        coroutineScope {
            val backup = f.repository().export(10)
            val gate = CompetitionMahjongStorageEpochGate.forDatabase(f.db)
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            val raw = RoomCompetitionMahjongLearningProofStore(f.db,f.db.competitionMahjongLearningProofDao())
            val old = GatedCompetitionMahjongLearningProofStore(paused(raw,entered,release),gate)
            val accepted = async(start=CoroutineStart.UNDISPATCHED) { old.confirm(request(0)) }
            entered.await()
            try {
                val replacement = async(start=CoroutineStart.UNDISPATCHED) { f.repository().restore(backup) }
                val late = async(start=CoroutineStart.UNDISPATCHED) { runCatching { old.confirm(request(1)) }.exceptionOrNull() }
                assertFalse(replacement.isCompleted); assertFalse(late.isCompleted)
                assertEquals(old.ownerEpoch,gate.epoch.value)
                release.complete(Unit); accepted.await(); replacement.await()
                assertTrue(late.await() is IOException)
                assertEquals(0,ready(guarded(f.db).load()).completedChapterCount)
            } finally { release.complete(Unit) }
        }
    }

    @Test fun cancelledQueuedReplacementDoesNotRetireTheConfirmingOwner() = withOwnedLanzhouDatabase { f ->
        coroutineScope {
            val backup = f.repository().export(10)
            val gate = CompetitionMahjongStorageEpochGate.forDatabase(f.db)
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            val raw = RoomCompetitionMahjongLearningProofStore(f.db,f.db.competitionMahjongLearningProofDao())
            val old = GatedCompetitionMahjongLearningProofStore(paused(raw,entered,release),gate)
            val accepted = async(start=CoroutineStart.UNDISPATCHED) { old.confirm(request(0)) }
            entered.await()
            try {
                val queued = async(start=CoroutineStart.UNDISPATCHED) { f.repository().restore(backup) }
                assertFalse(queued.isCompleted)
                queued.cancelAndJoin()
                assertEquals(old.ownerEpoch,gate.epoch.value)
                release.complete(Unit); accepted.await()
                assertEquals(2,old.confirm(request(1)).completedChapterCount)
            } finally { release.complete(Unit) }
        }
    }

    @Test fun preparedLearningPayloadRequiresTheEnclosingReplacementTransaction() = withOwnedLanzhouDatabase { f ->
        val adapter = CompetitionMahjongLearningBackupAdapter()
        val prepared = adapter.prepareRestore(adapter.encode(bytes(CompetitionMahjongLearningProof())))
        assertTrue(runCatching { prepared.write(f.db) }.exceptionOrNull() is IllegalStateException)
        assertTrue(f.db.competitionMahjongLearningProofDao().readBoundedMetadata().isEmpty())
        f.db.withTransaction { prepared.write(f.db) }
        assertEquals(0,ready(guarded(f.db).load()).completedChapterCount)
        assertEquals(1,f.db.competitionMahjongLearningProofDao().readBoundedMetadata().size)
    }

    private fun guarded(db:MocsDatabase) = GatedCompetitionMahjongLearningProofStore(
        RoomCompetitionMahjongLearningProofStore(db,db.competitionMahjongLearningProofDao()),CompetitionMahjongStorageEpochGate.forDatabase(db))
    private fun ready(read:CompetitionMahjongLearningProofRead) = (read as CompetitionMahjongLearningProofRead.Ready).proof
    private fun bytes(proof:CompetitionMahjongLearningProof) = CompetitionMahjongLearningProofCodec.encode(proof)
    private fun request(index:Int):CompetitionMahjongLearningProofRequest {
        val chapter = CompetitionMahjongLearningProofCatalog.chapterIds[index]
        return CompetitionMahjongLearningProofRequest(UUID.randomUUID().toString(),chapter,
            CompetitionMahjongLearningProofCatalog.cardIds(chapter),100L+index)
    }
    private suspend fun denied(action:suspend ()->Any?) {
        try { action(); fail("Retired learning writer was accepted") } catch (_:IOException) { }
    }
    private fun paused(delegate:CompetitionMahjongLearningProofStore,entered:CompletableDeferred<Unit>,release:CompletableDeferred<Unit>) =
        object:CompetitionMahjongLearningProofStore {
            override suspend fun load() = delegate.load()
            override suspend fun confirm(request:CompetitionMahjongLearningProofRequest):CompetitionMahjongLearningProof {
                entered.complete(Unit); release.await(); return delegate.confirm(request)
            }
        }
    private class WireOnlyAdapter:GameBackupAdapter {
        override val descriptor = CompetitionMahjongLearningBackupAdapter().descriptor
        override fun validate(record:GameBackupRecord,containerVersion:Int) = Unit
        override suspend fun read(database:MocsDatabase) = emptyList<GameBackupRecord>()
        override fun prepareRestore(record:GameBackupRecord):PreparedGameBackup = error("Envelope-only fixture")
    }
}
