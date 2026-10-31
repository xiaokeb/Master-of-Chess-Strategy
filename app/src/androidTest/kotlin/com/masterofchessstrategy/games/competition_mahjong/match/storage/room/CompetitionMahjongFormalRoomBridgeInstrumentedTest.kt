package com.masterofchessstrategy.games.competition_mahjong.match.storage.room

import androidx.room.withTransaction
import com.masterofchessstrategy.data.CompetitionMahjongMatchEntity
import com.masterofchessstrategy.data.MocsDatabase
import com.masterofchessstrategy.engine.CompetitionMahjongAction
import com.masterofchessstrategy.engine.CompetitionMahjongRoundActionKind
import com.masterofchessstrategy.engine.CompetitionMahjongRoundPhase
import com.masterofchessstrategy.engine.Difficulty
import com.masterofchessstrategy.engine.NativeCompetitionMahjongSession
import com.masterofchessstrategy.games.competition_mahjong.data.CompetitionMahjongFormalBackupBundle
import com.masterofchessstrategy.games.competition_mahjong.match.*
import com.masterofchessstrategy.games.competition_mahjong.match.hint.CompetitionMahjongHintReceipt
import com.masterofchessstrategy.games.competition_mahjong.match.hint.competitionMahjongHintPosition
import com.masterofchessstrategy.games.competition_mahjong.match.policy.CompetitionMahjongPlayPolicy
import com.masterofchessstrategy.games.competition_mahjong.match.policy.CompetitionMahjongUnlockProgress
import com.masterofchessstrategy.games.competition_mahjong.match.storage.*
import com.masterofchessstrategy.games.lanzhou_mahjong.data.withOwnedLanzhouDatabase
import java.io.IOException
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

/** Actual Native and UUID disk Room. QA permissions/paid receipts do not claim production UI use. */
class CompetitionMahjongFormalRoomBridgeInstrumentedTest {
    private val policy = CompetitionMahjongPlayPolicy.fromProgress(
        CompetitionMahjongUnlockProgress(tutorialCompleted = true),
        mapOf(Difficulty.EASY to 0, Difficulty.MEDIUM to 1, Difficulty.HARD to 2, Difficulty.MASTER to 3),
    )

    @Test fun unmigratedClaimCannotOpenOrExportAndKeepsTheOldMcmt() = withOwnedLanzhouDatabase { f ->
        val checkpoint = nativeV4()
        val legacy = legacy(checkpoint)
        f.db.competitionMahjongMatchDao().upsert(legacy)
        val bridge = CompetitionMahjongFormalRoomBridge.acquire(f.db, f.db.competitionMahjongFormalRoomDao(), 0, policy)
        val claim = requireNotNull(f.db.competitionMahjongFormalRoomDao().find(0))
        assertNull(claim.mctxSize)
        denied { bridge.openRepository() }
        denied { bridge.exportMctx() }
        assertEquals(claim, f.db.competitionMahjongFormalRoomDao().find(0))
        assertArrayEquals(legacy.checkpoint, f.db.competitionMahjongMatchDao().find(2)!!.checkpoint)
    }

    @Test fun emptyReplacementNeedsOuterTransactionAndCommittedBridgeHasAnEmptyRepository() = withOwnedLanzhouDatabase { f ->
        val dao = f.db.competitionMahjongFormalRoomDao()
        val candidate = CompetitionMahjongFormalRoomBackup.emptyForRestore(2, policy)
        try {
            CompetitionMahjongFormalRoomBridge.installBackupInTransaction(f.db, dao, candidate)
            fail("Installation outside the caller's transaction was accepted")
        } catch (_: IllegalStateException) { }
        assertTrue(dao.listViewerIds().isEmpty())
        // The coordinator keeps the returned bridge local until the outer transaction succeeds.
        val bridge = f.db.withTransaction {
            CompetitionMahjongFormalRoomBridge.installBackupInTransaction(f.db, dao, candidate)
        }
        val repository = bridge.openRepository()
        assertNull(repository.matchStore.load(2))
        assertTrue(repository.statisticsStore.load(2).outcomes.isEmpty())
        assertTrue(repository.historyStore.load(2).entries.isEmpty())
        assertArrayEquals(emptyMctx(2), bridge.exportMctx())
        assertTrue(requireNotNull(dao.find(2)).mctxSize!! > 0)
    }

    @Test fun actualInitialAndOneBotActionV4SaveAndReopenWithExactCompleteBytes() = withOwnedLanzhouDatabase { f ->
        val checkpoints = listOf(nativeV4(), nativeV4(afterOneBotAction = true))
        for (checkpoint in checkpoints) {
            val viewer = checkpoint.config.viewerPlayerId
            val bridge = installEmpty(f.db, viewer)
            val repository = bridge.openRepository()
            repository.matchStore.save(checkpoint)
            assertCheckpoint(checkpoint, repository.matchStore.load(viewer))
            assertArrayEquals(mctx(checkpoint), bridge.exportMctx())
            assertTrue(repository.statisticsStore.load(viewer).outcomes.isEmpty())
            assertTrue(repository.historyStore.load(viewer).entries.isEmpty())
        }
        val before = checkpoints.associate { it.config.viewerPlayerId to
            requireNotNull(f.db.competitionMahjongFormalRoomDao().find(it.config.viewerPlayerId)) }
        f.reopen()
        for (checkpoint in checkpoints) {
            val viewer = checkpoint.config.viewerPlayerId
            val bridge = CompetitionMahjongFormalRoomBridge.acquire(f.db, f.db.competitionMahjongFormalRoomDao(), viewer, policy)
            assertCheckpoint(checkpoint, bridge.openRepository().matchStore.load(viewer))
            assertArrayEquals(mctx(checkpoint), bridge.exportMctx())
            val after = requireNotNull(f.db.competitionMahjongFormalRoomDao().find(viewer))
            assertNotEquals(before.getValue(viewer).ownerId, after.ownerId)
            assertTrue(after.epoch > before.getValue(viewer).epoch)
        }
    }

    @Test fun reacquireRejectsOldRepositoryAllThreeViewsAndExportWithoutChangingPayload() = withOwnedLanzhouDatabase { f ->
        val checkpoint = nativeV4()
        val oldBridge = install(f.db, checkpoint)
        val oldRepository = oldBridge.openRepository()
        val before = oldBridge.exportMctx()
        val fresh = CompetitionMahjongFormalRoomBridge.acquire(f.db, f.db.competitionMahjongFormalRoomDao(), 0, policy)
        denied { oldRepository.matchStore.load(0) }
        denied { oldRepository.matchStore.save(checkpoint) }
        denied { oldRepository.statisticsStore.load(0) }
        denied { oldRepository.historyStore.load(0) }
        denied { oldBridge.exportMctx() }
        assertArrayEquals(before, fresh.exportMctx())
        assertCheckpoint(checkpoint, fresh.openRepository().matchStore.load(0))
    }

    @Test fun outerFailureRestoresLeaseAndPayloadAndTheAbortedBridgeCannotOpenAfterRollback() = withOwnedLanzhouDatabase { f ->
        val checkpoint = nativeV4()
        val original = install(f.db, checkpoint)
        val before = requireNotNull(f.db.competitionMahjongFormalRoomDao().find(0))
        val originalBytes = original.exportMctx()
        val candidate = CompetitionMahjongFormalRoomBackup.emptyForRestore(0, policy)
        var aborted: CompetitionMahjongFormalRoomBridge? = null
        val failure = IllegalStateException("owned outer restore failure")
        try {
            f.db.withTransaction {
                aborted = CompetitionMahjongFormalRoomBridge.installBackupInTransaction(
                    f.db, f.db.competitionMahjongFormalRoomDao(), candidate)
                assertNotEquals(before.ownerId, f.db.competitionMahjongFormalRoomDao().find(0)!!.ownerId)
                throw failure
            }
        } catch (actual: IllegalStateException) { assertEquals(failure.message, actual.message) }
        assertEquals(before, f.db.competitionMahjongFormalRoomDao().find(0))
        assertArrayEquals(originalBytes, original.exportMctx())
        assertCheckpoint(checkpoint, original.openRepository().matchStore.load(0))
        denied { requireNotNull(aborted).openRepository() }
    }

    @Test fun bundleReplacementAdmitsAllFourSeatsAndMissingSeatsBecomeExplicitEmptyFacts() = withOwnedLanzhouDatabase { f ->
        val old = (0..3).map { installEmpty(f.db, it) }
        val checkpoint = nativeV4(afterOneBotAction = true)
        val record = CompetitionMahjongFormalBackupBundle.encode(1, mctx(checkpoint))
        assertEquals(listOf("2", "1"), record.fields.take(2))
        val plan = CompetitionMahjongFormalBackupBundle.prepareReplacement(listOf(record)) { policy }
        val bridges = f.db.withTransaction {
            plan.installInTransaction(f.db, f.db.competitionMahjongFormalRoomDao())
        }
        assertEquals(listOf(0, 1, 2, 3), f.db.competitionMahjongFormalRoomDao().listViewerIds())
        for (viewer in 0..3) {
            val expected = if (viewer == 1) mctx(checkpoint) else emptyMctx(viewer)
            assertArrayEquals(expected, bridges[viewer].exportMctx())
            val repository = bridges[viewer].openRepository()
            if (viewer == 1) assertCheckpoint(checkpoint, repository.matchStore.load(viewer))
            else assertNull(repository.matchStore.load(viewer))
            denied { old[viewer].exportMctx() }
        }
        val exported = f.db.withTransaction {
            CompetitionMahjongFormalBackupBundle.readInTransaction(f.db, f.db.competitionMahjongFormalRoomDao())
        }
        assertEquals(4, exported.size)
        exported.forEachIndexed { viewer, value ->
            assertEquals(listOf("2", viewer.toString()), value.fields.take(2))
            assertEquals(value, CompetitionMahjongFormalBackupBundle.normalize(value))
        }
    }

    @Test fun invalidActualViewerRejectsReadAndWholePlanBeforeAnyClaimOrPayloadWrite() = withOwnedLanzhouDatabase { f ->
        val old = (0..3).map { installEmpty(f.db, it) }
        val dao = f.db.competitionMahjongFormalRoomDao()
        val foreignBytes = emptyMctx(0)
        dao.insert(CompetitionMahjongFormalRoomRow(9, UUID.randomUUID().toString(), 1, 0, foreignBytes))
        val before = dao.listViewerIds().associateWith { requireNotNull(dao.find(it)) }
        val plan = CompetitionMahjongFormalBackupBundle.prepareReplacement(emptyList()) { policy }
        invalid {
            f.db.withTransaction { CompetitionMahjongFormalBackupBundle.readInTransaction(f.db, dao) }
        }
        invalid { f.db.withTransaction { plan.installInTransaction(f.db, dao) } }
        assertEquals(before, dao.listViewerIds().associateWith { requireNotNull(dao.find(it)) })
        val foreign = before.getValue(9)
        assertArrayEquals(foreignBytes, dao.readChunk(9, foreign.ownerId, foreign.epoch, foreign.revision, 1, foreignBytes.size))
        for (viewer in 0..3) assertArrayEquals(emptyMctx(viewer), old[viewer].exportMctx())
    }

    @Test fun structurallyValidV4WithIllegalPaidActionIsRejectedByRealNativeBeforeAnyClaim() = withOwnedLanzhouDatabase { f ->
        val checkpoint = nativeV4()
        val illegal = NativeCompetitionMahjongSession(checkpoint.config.seed, 0, 0, 0).use { native ->
            val legal = native.legalActions()
            (0..33).map { CompetitionMahjongAction(CompetitionMahjongRoundActionKind.DISCARD, it) }
                .first { it !in legal }
        }
        val rejected = CompetitionMahjongMatchCheckpoint(checkpoint.config, checkpoint.ledger, 0, checkpoint.engineRecord,
            checkpoint.assistance, checkpoint.playback, requireNotNull(checkpoint.hintReceipt).copy(action = illegal))
        val bytes = mctx(rejected)
        assertNotNull(CompetitionMahjongFormalSnapshotCodec.decode(bytes, 0))
        invalid { CompetitionMahjongFormalRoomBackup.admit(0, bytes, policy) }
        assertTrue(f.db.competitionMahjongFormalRoomDao().listViewerIds().isEmpty())
    }

    @Test fun explicitNativeMigrationRechecksSourceAndKeepsOriginalMcmtAfterIdempotentRetry() = withOwnedLanzhouDatabase { f ->
        val checkpoint = nativeV4()
        val legacy = legacy(checkpoint)
        f.db.competitionMahjongMatchDao().upsert(legacy)
        val bridge = CompetitionMahjongFormalRoomBridge.acquire(f.db, f.db.competitionMahjongFormalRoomDao(), 0, policy)
        val source: suspend () -> CompetitionMahjongFormalMigrationSource = {
            val actual = requireNotNull(f.db.competitionMahjongMatchDao().find(2))
            CompetitionMahjongFormalMigrationSource(0, actual.checkpoint, null, null, false, 1)
        }
        val preview = bridge.migration.preview(source)
        assertEquals(checkpoint.config.matchId, preview.activeMatchId)
        assertNull(f.db.competitionMahjongFormalRoomDao().find(0)!!.mctxSize)
        assertTrue(bridge.migration.migrate(preview.sourceSha256, source).wasInstalled)
        val first = bridge.exportMctx()
        assertFalse(bridge.migration.migrate(preview.sourceSha256, source).wasInstalled)
        assertArrayEquals(first, bridge.exportMctx())
        assertCheckpoint(checkpoint, bridge.openRepository().matchStore.load(0))
        assertArrayEquals(legacy.checkpoint, f.db.competitionMahjongMatchDao().find(2)!!.checkpoint)
        f.db.competitionMahjongMatchDao().upsert(legacy(nativeV4(seedStart = 400)))
        invalid { bridge.migration.migrate(preview.sourceSha256, source) }
        assertArrayEquals(first, bridge.exportMctx())
    }

    /** Real histories; v4 receipt is QA metadata tied to an actually legal public action. */
    private fun nativeV4(afterOneBotAction: Boolean = false, seedStart: Long = 321): CompetitionMahjongMatchCheckpoint {
        val viewer = if (afterOneBotAction) 1 else 0
        for (seed in seedStart until seedStart + 16) {
            NativeCompetitionMahjongSession(seed, 0, 0, viewer).use { native ->
                if (afterOneBotAction && native.advanceBots(0, seed, 1) != 1) return@use
                val view = native.snapshot()
                if (afterOneBotAction && view.phase != CompetitionMahjongRoundPhase.CLAIM) return@use
                val actions = native.legalActions()
                val action = actions.firstOrNull { it.kind == CompetitionMahjongRoundActionKind.PASS }
                    ?: actions.firstOrNull { it.kind == CompetitionMahjongRoundActionKind.DISCARD }
                    ?: return@use
                val config = CompetitionMahjongCompetitionConfig(UUID.randomUUID().toString(), seed, viewer, 0)
                val checkpoint = CompetitionMahjongMatchCheckpoint(config, CompetitionMahjongMatchLedger.empty(), 0,
                    native.serialize(), policy.freshUsage(0).consumeHint(), CompetitionMahjongMatchPlayback(),
                    CompetitionMahjongHintReceipt(0, competitionMahjongHintPosition(0, view), "room-qa", action))
                checkpoint.validateRestoredFrame(view, actions)
                assertEquals(4, CompetitionMahjongMatchCheckpointCodec.encode(checkpoint)[4].toInt())
                return checkpoint
            }
        }
        throw AssertionError("No bounded real Native v4 QA position was available")
    }

    private fun legacy(checkpoint: CompetitionMahjongMatchCheckpoint) = CompetitionMahjongMatchEntity(
        2, checkpoint.config.viewerPlayerId, checkpoint.config.matchId, 4, CompetitionMahjongMatchCheckpointCodec.encode(checkpoint), 1)
    private fun mctx(checkpoint: CompetitionMahjongMatchCheckpoint) = CompetitionMahjongFormalSnapshotCodec.encode(
        CompetitionMahjongFormalSnapshot(checkpoint.config.viewerPlayerId, checkpoint))
    private fun emptyMctx(viewer: Int) = CompetitionMahjongFormalSnapshotCodec.encode(CompetitionMahjongFormalSnapshot(viewer))
    private suspend fun install(db: MocsDatabase, checkpoint: CompetitionMahjongMatchCheckpoint): CompetitionMahjongFormalRoomBridge {
        val candidate = CompetitionMahjongFormalRoomBackup.admit(checkpoint.config.viewerPlayerId, mctx(checkpoint), policy)
        return db.withTransaction { CompetitionMahjongFormalRoomBridge.installBackupInTransaction(db, db.competitionMahjongFormalRoomDao(), candidate) }
    }
    private suspend fun installEmpty(db: MocsDatabase, viewer: Int): CompetitionMahjongFormalRoomBridge {
        val candidate = CompetitionMahjongFormalRoomBackup.emptyForRestore(viewer, policy)
        return db.withTransaction { CompetitionMahjongFormalRoomBridge.installBackupInTransaction(db, db.competitionMahjongFormalRoomDao(), candidate) }
    }
    private fun assertCheckpoint(expected: CompetitionMahjongMatchCheckpoint, actual: CompetitionMahjongMatchCheckpoint?) {
        assertNotNull(actual)
        assertArrayEquals(CompetitionMahjongMatchCheckpointCodec.encode(expected),
            CompetitionMahjongMatchCheckpointCodec.encode(requireNotNull(actual)))
    }
    private suspend fun denied(action: suspend () -> Any?) {
        try { action(); fail("A missing or retired owner was accepted") } catch (_: IOException) { }
    }
    private suspend fun invalid(action: suspend () -> Any?) {
        try { action(); fail("An incompatible candidate was accepted") } catch (_: IllegalArgumentException) { }
    }
}
