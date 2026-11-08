package com.masterofchessstrategy.games.competition_mahjong.data

import androidx.room.withTransaction
import com.masterofchessstrategy.data.CompetitionMahjongMatchArchive
import com.masterofchessstrategy.data.GameBackupPreparationContext
import com.masterofchessstrategy.data.GameBackupRecord
import com.masterofchessstrategy.data.MocsDatabase
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

/** Actual UUID disk Room/Native complements the production factory tests; not executed privately. */
class CompetitionMahjongFormalBackupAdapterInstrumentedTest {
    private val bindings = Difficulty.entries.associateWith { it.code }
    private val adapter get() = CompetitionMahjongFormalBackupAdapter(bindings)
    private val policy = CompetitionMahjongPlayPolicy.fromProgress(CompetitionMahjongUnlockProgress(true), bindings)

    @Test fun targetValidationIsStructuralWhileCandidateAdmissionRejectsTheNativeIllegalPaidAction() = withOwnedLanzhouDatabase { f ->
        val checkpoint = nativeCheckpoint(illegalHint = true)
        val bytes = mctx(checkpoint)
        val dao = f.db.competitionMahjongFormalRoomDao()
        dao.insert(CompetitionMahjongFormalRoomRow(0, UUID.randomUUID().toString(), 1, 0, bytes))
        val before = dao.find(0)
        val records = f.db.withTransaction {
            CompetitionMahjongFormalBackupAdapter.requireStoredTarget(f.db)
            adapter.read(f.db)
        }
        assertEquals(1, records.size)
        // Native replay is a candidate-stage responsibility, outside any Room transaction.
        val failure = runCatching { adapter.prepareContainer(records + completeProof(), GameBackupPreparationContext()) }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
        assertEquals(before, dao.find(0))
        assertArrayEquals(bytes, f.db.withTransaction {
            CompetitionMahjongFormalRoomStorage.readMctxInTransaction(f.db, dao, 0)
        })
    }

    @Test fun unknownOrMalformedActualTargetsBlockLegacyDeletionBeforeSqlIsReached() = withOwnedLanzhouDatabase { f ->
        val checkpoint = nativeCheckpoint()
        val old = CompetitionMahjongMatchArchive(0, checkpoint.config.matchId, 1,
            CompetitionMahjongMatchCheckpointCodec.encode(checkpoint))
        f.db.competitionMahjongMatchDao().upsert(old.toEntity())
        val oldWire = f.db.withTransaction { CompetitionMahjongBackupAdapter().read(f.db).single() }
        val prepared = adapter.prepareContainer(listOf(oldWire), GameBackupPreparationContext())
        for ((viewer, bytes) in listOf(9 to mctx(checkpoint), 0 to byteArrayOf(1, 2, 3))) {
            f.db.openHelper.writableDatabase.execSQL("DELETE FROM competition_mahjong_formal")
            f.db.competitionMahjongFormalRoomDao().insert(CompetitionMahjongFormalRoomRow(viewer,
                UUID.randomUUID().toString(), 1, 0, bytes))
            val before = f.db.competitionMahjongFormalRoomDao().find(viewer)
            f.db.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_formal_delete BEFORE DELETE ON competition_mahjong_formal BEGIN SELECT RAISE(ABORT,'delete was reached'); END")
            val failure = runCatching { f.db.withTransaction { prepared.write(f.db) } }.exceptionOrNull()
            assertNotNull(failure)
            assertFalse(failure.toString().contains("delete was reached"))
            assertEquals(before, f.db.competitionMahjongFormalRoomDao().find(viewer))
            assertArrayEquals(old.encodedCheckpoint, f.db.competitionMahjongMatchDao().find(2)!!.checkpoint)
            f.db.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_formal_delete")
        }
    }

    @Test fun failedOuterLegacyReplacementRollsBackTheDeletionAndDoesNotPretendSqlLeasesRetired() = withOwnedLanzhouDatabase { f ->
        val checkpoint = nativeCheckpoint()
        val admitted = CompetitionMahjongFormalRoomBackup.admit(0, mctx(checkpoint), policy)
        val bridge = f.db.withTransaction {
            CompetitionMahjongFormalRoomBridge.installBackupInTransaction(f.db, f.db.competitionMahjongFormalRoomDao(), admitted)
        }
        val old = CompetitionMahjongMatchArchive(0, checkpoint.config.matchId, 1,
            CompetitionMahjongMatchCheckpointCodec.encode(checkpoint))
        f.db.competitionMahjongMatchDao().upsert(old.toEntity())
        val oldWire = f.db.withTransaction { CompetitionMahjongBackupAdapter().read(f.db).single() }
        val prepared = adapter.prepareContainer(listOf(oldWire), GameBackupPreparationContext())
        val before = f.db.competitionMahjongFormalRoomDao().find(0)
        val kept = bridge.exportMctx()
        val failure = runCatching { f.db.withTransaction {
            prepared.write(f.db)
            assertTrue(f.db.competitionMahjongFormalRoomDao().listViewerIds().isEmpty())
            throw IOException("injected outer replacement failure")
        } }.exceptionOrNull()
        assertTrue(failure is IOException)
        assertEquals(before, f.db.competitionMahjongFormalRoomDao().find(0))
        assertArrayEquals(kept, bridge.exportMctx())
        assertArrayEquals(old.encodedCheckpoint, f.db.competitionMahjongMatchDao().find(2)!!.checkpoint)
        // This direct transaction has no production epoch wrapper; its SQL rollback keeps the lease.
    }

    private fun nativeCheckpoint(illegalHint: Boolean = false): CompetitionMahjongMatchCheckpoint {
        for (seed in 321L until 337L) NativeCompetitionMahjongSession(seed, 0, 0, 0).use { native ->
            val action = if (illegalHint) (0..33).map { CompetitionMahjongAction(CompetitionMahjongRoundActionKind.DISCARD, it) }
                .first { it !in native.legalActions() }
            else native.legalActions().firstOrNull { it.kind == CompetitionMahjongRoundActionKind.PASS || it.kind == CompetitionMahjongRoundActionKind.DISCARD }
                ?: return@use
            return CompetitionMahjongMatchCheckpoint(CompetitionMahjongCompetitionConfig(UUID.randomUUID().toString(), seed, 0, 0),
                CompetitionMahjongMatchLedger.empty(), 0, native.serialize(), policy.freshUsage(0).consumeHint(),
                CompetitionMahjongMatchPlayback(), CompetitionMahjongHintReceipt(0,
                    competitionMahjongHintPosition(0, native.snapshot()), "adapter-qa", action))
        }
        throw AssertionError("No bounded initial Native fixture")
    }

    private fun mctx(checkpoint: CompetitionMahjongMatchCheckpoint) =
        CompetitionMahjongFormalSnapshotCodec.encode(CompetitionMahjongFormalSnapshot(0, checkpoint))

    private fun completeProof(): GameBackupRecord {
        val proof = CompetitionMahjongLearningProofCatalog.chapterIds.fold(CompetitionMahjongLearningProof()) { current, chapter ->
            current.confirm(CompetitionMahjongLearningProofRequest(UUID.randomUUID().toString(), chapter,
                CompetitionMahjongLearningProofCatalog.cardIds(chapter), 1))
        }
        return CompetitionMahjongLearningBackupAdapter().encode(CompetitionMahjongLearningProofCodec.encode(proof))
    }
}
