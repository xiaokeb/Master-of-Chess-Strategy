package com.masterofchessstrategy.games.competition_mahjong.data

import androidx.room.withTransaction
import com.masterofchessstrategy.data.*
import com.masterofchessstrategy.engine.*
import com.masterofchessstrategy.games.competition_mahjong.learning.*
import com.masterofchessstrategy.games.competition_mahjong.match.*
import com.masterofchessstrategy.games.competition_mahjong.match.hint.*
import com.masterofchessstrategy.games.competition_mahjong.match.policy.*
import com.masterofchessstrategy.games.competition_mahjong.match.storage.*
import com.masterofchessstrategy.games.competition_mahjong.match.storage.room.*
import com.masterofchessstrategy.games.lanzhou_mahjong.data.withOwnedLanzhouDatabase
import java.io.IOException
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

/** Actual production factory, UUID disk Room and real Native records; QA receipts are not paid UI use. */
class CompetitionMahjongFormalProductionBackupInstrumentedTest {
    private val bindings = mapOf(Difficulty.EASY to 0, Difficulty.MEDIUM to 1, Difficulty.HARD to 2, Difficulty.MASTER to 3)
    private val policy = CompetitionMahjongPlayPolicy.fromProgress(CompetitionMahjongUnlockProgress(true), bindings)

    @Test fun productionRoundTripKeepsActiveFourSeatsAndArchivedLegacyThenReopensDisk() = withOwnedLanzhouDatabase { f ->
        completeCourse(f.db)
        val checkpoint = nativeCheckpoint()
        val legacy = legacy(checkpoint)
        f.db.competitionMahjongMatchDao().upsert(legacy)
        val old = install(f.db, checkpoint)
        installEmpty(f.db, 2)
        val expected = old.exportMctx()
        val backup = f.repository().export(20)
        val oldLearning = learning(f.db)
        oldLearning.load()
        f.repository().restore(backup)
        assertEquals((0..3).toList(), f.db.competitionMahjongFormalRoomDao().listViewerIds())
        denied { old.exportMctx() }
        denied { oldLearning.load() }
        assertArrayEquals(legacy.checkpoint, f.db.competitionMahjongMatchDao().find(2)!!.checkpoint)
        f.reopen()
        val reopened = CompetitionMahjongFormalRoomBridge.acquire(f.db, f.db.competitionMahjongFormalRoomDao(), 0, policy)
        assertArrayEquals(expected, reopened.exportMctx())
        assertArrayEquals(CompetitionMahjongMatchCheckpointCodec.encode(checkpoint),
            CompetitionMahjongMatchCheckpointCodec.encode(reopened.openRepository().matchStore.load(0)!!))
        // Export also revalidates the retained source relationship after acquiring a new lease.
        assertTrue(f.repository().export(30).isNotEmpty())
    }

    @Test fun legacyOnlyRestoreRemovesFormalFactsAndStillAllowsExplicitMigration() = withOwnedLanzhouDatabase { f ->
        completeCourse(f.db)
        val checkpoint = nativeCheckpoint()
        f.db.competitionMahjongMatchDao().upsert(legacy(checkpoint))
        val legacyOnly = f.repository().export(10)
        installEmpty(f.db, 3)
        f.repository().restore(legacyOnly)
        assertTrue(f.db.competitionMahjongFormalRoomDao().listViewerIds().isEmpty())
        val bridge = CompetitionMahjongFormalRoomBridge.acquire(f.db, f.db.competitionMahjongFormalRoomDao(), 0, policy)
        val source: suspend () -> CompetitionMahjongFormalMigrationSource = {
            CompetitionMahjongFormalMigrationSource(0, f.db.competitionMahjongMatchDao().find(2)!!.checkpoint,
                null, null, false, 1)
        }
        val preview = bridge.migration.preview(source)
        assertTrue(bridge.migration.migrate(preview.sourceSha256, source).wasInstalled)
        assertNotNull(bridge.openRepository().matchStore.load(0))
        assertArrayEquals(legacy(checkpoint).checkpoint, f.db.competitionMahjongMatchDao().find(2)!!.checkpoint)
        assertTrue(f.repository().export(11).isNotEmpty())
    }

    @Test fun olderBackupWithoutEitherKindInstallsFourEmptyFactsWithoutBorrowingOldLearning() = withOwnedLanzhouDatabase { f ->
        val empty = f.repository().export(1)
        completeCourse(f.db)
        val old = install(f.db, nativeCheckpoint())
        f.repository().restore(empty)
        denied { old.exportMctx() }
        assertEquals((0..3).toList(), f.db.competitionMahjongFormalRoomDao().listViewerIds())
        assertTrue(f.db.competitionMahjongLearningProofDao().readBoundedMetadata().isEmpty())
        for (viewer in 0..3) {
            val bridge = CompetitionMahjongFormalRoomBridge.acquire(f.db, f.db.competitionMahjongFormalRoomDao(), viewer, policy)
            assertNull(bridge.openRepository().matchStore.load(viewer))
        }
    }

    @Test fun candidateWithoutLearningCannotBorrowCompletedTargetPermission() = withOwnedLanzhouDatabase { f ->
        completeCourse(f.db)
        val old = install(f.db, nativeCheckpoint())
        val kept = old.exportMctx()
        val record = CompetitionMahjongFormalBackupWire.encode(0, kept)
        val backup = envelope(listOf(record))
        assertTrue(runCatching { f.repository().restore(backup) }.exceptionOrNull() is IllegalArgumentException)
        assertArrayEquals(kept, old.exportMctx())
        assertEquals(4, (learning(f.db).load() as CompetitionMahjongLearningProofRead.Ready).proof.completedChapterCount)
    }

    @Test fun realNativeRejectsAnIllegalPaidActionBeforeAnyClear() = withOwnedLanzhouDatabase { f ->
        completeCourse(f.db)
        val checkpoint = nativeCheckpoint()
        val old = install(f.db, checkpoint)
        val kept = old.exportMctx()
        val illegal = NativeCompetitionMahjongSession(checkpoint.config.seed, 0, 0, 0).use { native ->
            (0..33).map { CompetitionMahjongAction(CompetitionMahjongRoundActionKind.DISCARD, it) }
                .first { it !in native.legalActions() }
        }
        val bad = CompetitionMahjongMatchCheckpoint(checkpoint.config, checkpoint.ledger, 0, checkpoint.engineRecord,
            checkpoint.assistance, checkpoint.playback, checkpoint.hintReceipt!!.copy(action = illegal))
        val wire = CompetitionMahjongFormalBackupWire.encode(0, mctx(bad))
        val proof = f.db.withTransaction { CompetitionMahjongLearningBackupAdapter().read(f.db) }
        val backup = envelope(listOf(wire) + proof)
        f.db.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_clear BEFORE DELETE ON competition_mahjong_learning_proof BEGIN SELECT RAISE(ABORT,'clear reached'); END")
        assertTrue(runCatching { f.repository().restore(backup) }.exceptionOrNull() is IllegalArgumentException)
        assertArrayEquals(kept, old.exportMctx())
    }

    @Test fun fourthSeatSqlFaultRollsBackEarlierSeatsAndCoreButRetiresTheGateOwner() = withOwnedLanzhouDatabase { f ->
        completeCourse(f.db)
        val bridges = (0..3).map { installEmpty(f.db, it) }
        val backup = f.repository().export(1)
        val dao = f.db.competitionMahjongFormalRoomDao()
        val before = (0..3).map { dao.find(it) }
        val bytes = bridges.map { it.exportMctx() }
        val oldLearning = learning(f.db)
        val kept = MatchOutcomeEntity("kept-on-rollback", 0, 1, 0, 0, 1, 2)
        f.db.matchOutcomeDao().insert(kept)
        f.db.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_fourth BEFORE UPDATE ON competition_mahjong_formal WHEN OLD.viewerPlayerId=3 BEGIN SELECT RAISE(ABORT,'fourth seat fault'); END")
        assertNotNull(runCatching { f.repository().restore(backup) }.exceptionOrNull())
        assertEquals(before, (0..3).map { dao.find(it) })
        assertEquals(listOf(kept), f.db.matchOutcomeDao().listAll())
        for (viewer in 0..3) assertArrayEquals(bytes[viewer], bridges[viewer].exportMctx())
        // SQL rollback preserves old leases; the production epoch still retires page Owners.
        denied { oldLearning.load() }
    }

    @Test fun unmigratedActualTargetIsPreservedBeforeAnyCoreDelete() = withOwnedLanzhouDatabase { f ->
        val empty = f.repository().export(1)
        CompetitionMahjongFormalRoomBridge.acquire(f.db, f.db.competitionMahjongFormalRoomDao(), 0, policy)
        val row = f.db.competitionMahjongFormalRoomDao().find(0)
        val kept = MatchOutcomeEntity("kept-before-clear", 0, 1, 0, 0, 1, 2)
        f.db.matchOutcomeDao().insert(kept)
        f.db.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_core_delete BEFORE DELETE ON match_outcomes BEGIN SELECT RAISE(ABORT,'clear reached'); END")
        val failure = runCatching { f.repository().restore(empty) }.exceptionOrNull()
        assertNotNull(failure)
        assertFalse(failure.toString().contains("clear reached"))
        assertEquals(row, f.db.competitionMahjongFormalRoomDao().find(0))
        assertEquals(listOf(kept), f.db.matchOutcomeDao().listAll())
    }

    private fun nativeCheckpoint(): CompetitionMahjongMatchCheckpoint {
        for (seed in 321L until 337L) NativeCompetitionMahjongSession(seed, 0, 0, 0).use { native ->
            val view = native.snapshot()
            val action = native.legalActions().firstOrNull { it.kind == CompetitionMahjongRoundActionKind.PASS || it.kind == CompetitionMahjongRoundActionKind.DISCARD }
                ?: return@use
            return CompetitionMahjongMatchCheckpoint(CompetitionMahjongCompetitionConfig(UUID.randomUUID().toString(), seed, 0, 0),
                CompetitionMahjongMatchLedger.empty(), 0, native.serialize(), policy.freshUsage(0).consumeHint(),
                CompetitionMahjongMatchPlayback(), CompetitionMahjongHintReceipt(0, competitionMahjongHintPosition(0, view), "backup-qa", action))
        }
        throw AssertionError("No bounded initial Native position with a legal QA action")
    }
    private fun legacy(c: CompetitionMahjongMatchCheckpoint) = CompetitionMahjongMatchArchive(c.config.viewerPlayerId,
        c.config.matchId, 1, CompetitionMahjongMatchCheckpointCodec.encode(c)).toEntity()
    private fun mctx(c: CompetitionMahjongMatchCheckpoint) = CompetitionMahjongFormalSnapshotCodec.encode(CompetitionMahjongFormalSnapshot(0, c))
    private suspend fun install(db: MocsDatabase, c: CompetitionMahjongMatchCheckpoint): CompetitionMahjongFormalRoomBridge {
        val candidate = CompetitionMahjongFormalRoomBackup.admit(0, mctx(c), policy)
        return db.withTransaction { CompetitionMahjongFormalRoomBridge.installBackupInTransaction(db, db.competitionMahjongFormalRoomDao(), candidate) }
    }
    private suspend fun installEmpty(db: MocsDatabase, viewer: Int) = db.withTransaction {
        CompetitionMahjongFormalRoomBridge.installBackupInTransaction(db, db.competitionMahjongFormalRoomDao(), CompetitionMahjongFormalRoomBackup.emptyForRestore(viewer, policy))
    }
    private fun learning(db: MocsDatabase) = GatedCompetitionMahjongLearningProofStore(
        RoomCompetitionMahjongLearningProofStore(db, db.competitionMahjongLearningProofDao()), CompetitionMahjongStorageEpochGate.forDatabase(db))
    private suspend fun completeCourse(db: MocsDatabase) {
        val store = learning(db)
        CompetitionMahjongLearningProofCatalog.chapterIds.forEach { chapter ->
            store.confirm(CompetitionMahjongLearningProofRequest(UUID.randomUUID().toString(), chapter, CompetitionMahjongLearningProofCatalog.cardIds(chapter), 1))
        }
    }
    private fun envelope(records: List<GameBackupRecord>) = LocalDataBackupCodec.encode(LocalDataSnapshot(10, gameExtensions = records),
        GameBackupRegistry(listOf(WireOnly(CompetitionMahjongFormalBackupAdapter(bindings).descriptor), WireOnly(CompetitionMahjongLearningBackupAdapter().descriptor))))
    private class WireOnly(override val descriptor: GameBackupDescriptor) : GameBackupAdapter {
        override fun validate(record: GameBackupRecord, containerVersion: Int) = Unit
        override suspend fun read(database: MocsDatabase) = emptyList<GameBackupRecord>()
        override fun prepareRestore(record: GameBackupRecord): PreparedGameBackup = error("Encoder-only fixture")
    }
    private suspend fun denied(action: suspend () -> Any?) {
        try { action(); fail("Retired Owner accepted") } catch (_: IOException) { }
    }
}
