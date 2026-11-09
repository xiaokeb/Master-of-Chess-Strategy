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

    @Test fun sixteenRealHandsSettleWithdrawAndReopenWithHistoryAndDeletionIntact() = withOwnedLanzhouDatabase { f ->
        completeCourse(f.db)
        val config = CompetitionMahjongCompetitionConfig(UUID.randomUUID().toString(), 20261009, 0, 0,
            CompetitionMahjongBotStrategy.POSITION_SEEDED_V1)
        var host = CompetitionMahjongFormalHost(f.db, 0)
        var session = (host.open() as CompetitionMahjongFormalHost.Entry.Ready).session
        var usage = session.policy.freshUsage(config.botLevel)
        // The controller is driven directly. Supply the assistance metadata normally attached by the play VM.
        val store = object : CompetitionMahjongMatchStore {
            override suspend fun load(viewerPlayerId: Int) = session.match.load(viewerPlayerId)
            override suspend fun save(checkpoint: CompetitionMahjongMatchCheckpoint) =
                session.match.save(checkpoint.withAssistance(usage))
        }
        var controller = CompetitionMahjongMatchController(config, store)
        try {
            assertTrue(controller.open())
            assertEquals(0, storedSnapshot(f.db).settledHandRecords!!.size)
            assertEquals(config.botStrategy, storedSnapshot(f.db).active!!.config.botStrategy)
            for (index in 0 until 16) {
                val seating = CompetitionMahjongMatchSeating.forHand(index)
                val frame = checkNotNull(controller.state.value.table)
                assertEquals(seating.seatOfPlayer(0), frame.viewerSeat)
                assertEquals(seating.dealerSeat, frame.dealerSeat)
                assertEquals(seating.roundWind, frame.roundWind)
                if (index == 15) {
                    val sql = f.db.openHelper.writableDatabase
                    sql.execSQL("CREATE TRIGGER reject_series_archive BEFORE UPDATE OF mctx ON competition_mahjong_formal " +
                        "WHEN hex(substr(NEW.mctx,5,1))='03' BEGIN SELECT RAISE(ABORT,'archive fault'); END")
                    try {
                        finishCompetitionMahjongTestHand(controller, index, allowPendingSave = true)
                        assertTrue(controller.state.value.needsSave)
                        assertTrue(storedSnapshot(f.db).archivedSeries.isEmpty())
                        assertTrue(session.statistics.load(0).outcomes.isEmpty())
                    } finally {
                        sql.execSQL("DROP TRIGGER reject_series_archive")
                    }
                    assertTrue(controller.retrySave())
                } else finishCompetitionMahjongTestHand(controller, index)
                if (index == 0) {
                    val before = storedMctx(f.db)
                    val sql = f.db.openHelper.writableDatabase
                    sql.execSQL("CREATE TRIGGER reject_next_hand BEFORE UPDATE OF mctx ON competition_mahjong_formal " +
                        "BEGIN SELECT RAISE(ABORT,'next hand fault'); END")
                    try {
                        assertFalse(controller.nextHand(0))
                        assertEquals(0, controller.state.value.activeHandIndex)
                        assertArrayEquals(before, storedMctx(f.db))
                    } finally {
                        sql.execSQL("DROP TRIGGER reject_next_hand")
                    }
                }
                if (index == 7) {
                    controller.close()
                    host.close()
                    f.reopen()
                    host = CompetitionMahjongFormalHost(f.db, 0)
                    session = (host.open() as CompetitionMahjongFormalHost.Entry.Ready).session
                    controller = CompetitionMahjongMatchController(config, store)
                    assertTrue(controller.open())
                    assertEquals(8, controller.state.value.completedHandCount)
                    assertEquals(7, storedSnapshot(f.db).settledHandRecords!!.size)
                }
                if (index < 15) {
                    val finished = checkNotNull(store.load(0)).engineRecord
                    assertTrue(controller.nextHand(index))
                    val prefix = checkNotNull(storedSnapshot(f.db).settledHandRecords)
                    assertEquals(index + 1, prefix.size)
                    assertArrayEquals(finished, prefix.last())
                }
            }
            assertFalse(controller.nextHand(15))
            val original = session.history.load(0).entries.single()
            assertEquals(config.matchId, original.matchId)
            assertEquals(16, original.hands.size)
            assertEquals(original.viewerStanding.score, session.statistics.load(0).outcomes.single().score)
            assertEquals(config, storedSnapshot(f.db).archivedSeries.single().config)
            val won = original.viewerStanding.rank == 1 && original.viewerStanding.standardPointsDenominator == 1
            val lost = original.viewerStanding.rank != 1
            val growth = (RoomMatchStatisticsRepository(f.db).load() as LoadMatchStatisticsResult.Loaded).statistics
            assertEquals(GameStatistics(1, if (won) 1 else 0, if (lost) 1 else 0, if (!won && !lost) 1 else 0),
                growth.byGame[GameType.STANDARD_MAHJONG])
            assertEquals(if (won) 10 else if (lost) -20 else 0, growth.score)
            assertEquals(if (won) 1L else 0L, session.policy.progress.easyWins)
            assertEquals(listOf(0), session.policy.allowedBotLevels)

            usage = usage.consumeUndo()
            assertTrue(controller.undo(15))
            assertEquals(15, controller.state.value.completedHandCount)
            assertEquals(15, storedSnapshot(f.db).settledHandRecords!!.size)
            assertTrue(storedSnapshot(f.db).archivedSeries.isEmpty())
            assertTrue(session.statistics.load(0).outcomes.isEmpty())
            assertTrue(session.history.load(0).entries.isEmpty())
            assertEquals(0, (RoomMatchStatisticsRepository(f.db).load() as LoadMatchStatisticsResult.Loaded)
                .statistics.byGame.getValue(GameType.STANDARD_MAHJONG).completedMatches)
            finishCompetitionMahjongTestHand(controller, 15)
            val settled = session.history.load(0).entries.single()
            assertEquals(original.hands.take(15), settled.hands.take(15))
            assertEquals(1, session.statistics.load(0).seriesCount)
            assertEquals(config, storedSnapshot(f.db).archivedSeries.single().config)
            assertTrue(session.history.setFavorite(0, config.matchId, true).entries.single().favorite)
            controller.close()
            host.close()

            val beforeBackup = storedMctx(f.db)
            val recorded = storedSnapshot(f.db)
            val prefix = checkNotNull(recorded.settledHandRecords)
            val wrongHand = recorded.withSettledHandRecords(listOf(prefix[1]) + prefix.drop(1))
            val wire = CompetitionMahjongFormalBackupWire.encode(0, CompetitionMahjongFormalSnapshotCodec.encode(wrongHand))
            val proof = f.db.withTransaction { CompetitionMahjongLearningBackupAdapter().read(f.db) }
            val failure = runCatching { f.repository().restore(envelope(listOf(wire) + proof)) }.exceptionOrNull()
            assertTrue(failure.toString(), failure is IllegalArgumentException)
            assertArrayEquals(beforeBackup, storedMctx(f.db))
            val favoriteBackup = f.repository().export(100)
            f.repository().restore(favoriteBackup)
            f.reopen()
            assertArrayEquals(beforeBackup, storedMctx(f.db))
            host = CompetitionMahjongFormalHost(f.db, 0)
            session = (host.open() as CompetitionMahjongFormalHost.Entry.Ready).session
            assertTrue(session.history.load(0).entries.single().favorite)
            assertEquals(16, session.match.load(0)?.ledger?.completedHandCount)
            assertTrue(session.history.delete(0, config.matchId).entries.isEmpty())
            val deletedBackup = f.repository().export(101)
            f.repository().restore(deletedBackup)
            host.close()
            host = CompetitionMahjongFormalHost(f.db, 0)
            session = (host.open() as CompetitionMahjongFormalHost.Entry.Ready).session
            assertTrue(session.history.load(0).entries.isEmpty())
            assertEquals(config.matchId, session.statistics.load(0).outcomes.single().matchId)

            val next = config.copy(matchId = UUID.randomUUID().toString(), seed = config.seed + 1)
            val freshStore = object : CompetitionMahjongMatchStore {
                override suspend fun load(viewerPlayerId: Int): CompetitionMahjongMatchCheckpoint? = null
                override suspend fun save(checkpoint: CompetitionMahjongMatchCheckpoint) =
                    session.match.save(checkpoint.withAssistance(session.policy.freshUsage(next.botLevel)))
            }
            controller = CompetitionMahjongMatchController(next, freshStore)
            assertTrue(controller.open())
            val nextSnapshot = storedSnapshot(f.db)
            assertEquals(next, nextSnapshot.active!!.config)
            val archived = nextSnapshot.archivedSeries.single()
            assertEquals(config, archived.config)
            assertEquals(settled.hands, CompetitionMahjongPrivateSeriesReplay.replayArchive(archived).results)
            controller.close()
            host.close()
            val bytesWithNewMatch = storedMctx(f.db)
            val compactHands = List(16, archived::decisions)
            val broken = CompetitionMahjongArchivedSeries(config, listOf(shortArrayOf()) + compactHands.drop(1))
            val corrupted = nextSnapshot.withPrivateEvidence(nextSnapshot.settledHandRecords, listOf(broken))
            val badWire = CompetitionMahjongFormalBackupWire.encode(0, CompetitionMahjongFormalSnapshotCodec.encode(corrupted))
            val rejected = runCatching { f.repository().restore(envelope(listOf(badWire) + proof)) }.exceptionOrNull()
            assertTrue(rejected.toString(), rejected is IllegalArgumentException)
            assertArrayEquals(bytesWithNewMatch, storedMctx(f.db))
            val crossSeriesBackup = f.repository().export(102)
            f.repository().restore(crossSeriesBackup)
            f.reopen()
            assertArrayEquals(bytesWithNewMatch, storedMctx(f.db))
            assertEquals(config, storedSnapshot(f.db).archivedSeries.single().config)
            assertEquals(1, (RoomMatchStatisticsRepository(f.db).load() as LoadMatchStatisticsResult.Loaded)
                .statistics.byGame.getValue(GameType.STANDARD_MAHJONG).completedMatches)
        } finally {
            controller.close()
            host.close()
        }
    }

    @Test fun rawLegacyOutcomeDoesNotGrantFormalMahjongGrowth() = withOwnedLanzhouDatabase { f ->
        val raw = MatchOutcome("legacy-unverified-mahjong", GameType.STANDARD_MAHJONG, StoredGameMode.HUMAN_VS_AI,
            Difficulty.EASY, 0, GameResult.FIRST_PLAYER_WIN, 1)
        f.db.matchOutcomeDao().insert(raw.toMatchOutcomeEntity())
        val repository = RoomMatchStatisticsRepository(f.db)
        val summary = (repository.load() as LoadMatchStatisticsResult.Loaded).statistics
        assertEquals(0, summary.totalWins)
        assertEquals(0, summary.score)
        assertTrue(runCatching { repository.record(raw.copy(matchId = "direct-mahjong")) }.exceptionOrNull() is IllegalArgumentException)
        assertEquals(listOf(raw.toMatchOutcomeEntity()), f.db.matchOutcomeDao().listAll())
    }

    @Test fun v1SeriesCanAdvanceWithoutInventingEarlierEvidence() = withOwnedLanzhouDatabase { f ->
        val checkpoint = nativeCheckpoint()
        val repository = install(f.db, checkpoint).openRepository()
        val store = object : CompetitionMahjongMatchStore {
            override suspend fun load(viewerPlayerId: Int) = repository.matchStore.load(viewerPlayerId)
            override suspend fun save(checkpoint: CompetitionMahjongMatchCheckpoint) =
                repository.matchStore.save(checkpoint.withAssistance(policy.freshUsage(0).consumeHint()))
        }
        val controller = CompetitionMahjongMatchController(checkpoint.config, store)
        try {
            assertTrue(controller.open())
            finishCompetitionMahjongTestHand(controller, 0)
            assertTrue(controller.nextHand(0))
            assertEquals(1, storedMctx(f.db)[4].toInt())
            val snapshot = storedSnapshot(f.db)
            assertEquals(1, snapshot.active!!.activeHandIndex)
            assertNull(snapshot.settledHandRecords)
        } finally {
            controller.close()
        }
    }

    @Test fun legacyArchiveConfirmationRejectsChangedSourceAndRoundTripsOriginalBytes() = withOwnedLanzhouDatabase { f ->
        val original = legacy(nativeCheckpoint().withoutLegacyAssistance())
        val dao = f.db.competitionMahjongMatchDao()
        dao.upsert(original)
        val host = CompetitionMahjongFormalHost(f.db, 0)
        try {
            val first = host.open() as CompetitionMahjongFormalHost.Entry.ConfirmLegacyArchive
            assertTrue(f.db.competitionMahjongFormalRoomDao().listViewerIds().isEmpty())
            dao.upsert(original.copy(updatedAtEpochMillis = original.updatedAtEpochMillis + 1))
            assertTrue(runCatching { host.confirmLegacyArchive(first) }.exceptionOrNull() is IllegalArgumentException)
            assertTrue(f.db.competitionMahjongFormalRoomDao().listViewerIds().isEmpty())

            val current = host.open() as CompetitionMahjongFormalHost.Entry.ConfirmLegacyArchive
            assertNotEquals(first.sourceSha256, current.sourceSha256)
            val session = host.confirmLegacyArchive(current)
            assertSame(session, host.confirmLegacyArchive(current))
            assertNull(session.match.load(0))
            assertTrue(session.policy.allowedBotLevels.isEmpty())
            assertTrue(session.history.load(0).entries.isEmpty())
            val backup = f.repository().export(200)
            f.repository().restore(backup)
            f.reopen()
            val kept = checkNotNull(f.db.competitionMahjongMatchDao().find(2))
            assertArrayEquals(original.checkpoint, kept.checkpoint)
            assertEquals(original.updatedAtEpochMillis + 1, kept.updatedAtEpochMillis)
            val reopened = (CompetitionMahjongFormalHost(f.db, 0).open() as CompetitionMahjongFormalHost.Entry.Ready).session
            assertNull(reopened.match.load(0))
        } finally {
            host.close()
        }
    }

    private fun CompetitionMahjongMatchCheckpoint.withoutLegacyAssistance() =
        CompetitionMahjongMatchCheckpoint(config, ledger, activeHandIndex, engineRecord)

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
    private suspend fun storedMctx(db: MocsDatabase): ByteArray = db.withTransaction {
        checkNotNull(CompetitionMahjongFormalRoomStorage.readMctxInTransaction(db, db.competitionMahjongFormalRoomDao(), 0))
    }
    private suspend fun storedSnapshot(db: MocsDatabase): CompetitionMahjongFormalSnapshot =
        checkNotNull(CompetitionMahjongFormalSnapshotCodec.decode(storedMctx(db), 0))

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

internal suspend fun finishCompetitionMahjongTestHand(
    controller: CompetitionMahjongMatchController,
    index: Int,
    allowPendingSave: Boolean = false,
) {
    repeat(1024) { batch ->
        controller.advanceOtherSeats(index, index * 1024L + batch + 1, 32)
        val state = controller.state.value
        if (allowPendingSave && state.needsSave) return
        assertFalse(state.error, state.needsSave)
        if (state.completedHandCount > index) return
        val action = state.legalActions.minByOrNull {
            when (it.kind) {
                CompetitionMahjongRoundActionKind.WIN -> 0
                CompetitionMahjongRoundActionKind.PASS -> 1
                CompetitionMahjongRoundActionKind.DISCARD -> 2
                CompetitionMahjongRoundActionKind.DRAW -> 3
                else -> 4
            }
        }
        assertNotNull("Native未终局却无法推进，第${index + 1}盘", action)
        val result = controller.apply(checkNotNull(action), index)
        if (allowPendingSave && controller.state.value.needsSave) return
        assertTrue(result?.accepted == true)
        assertFalse(controller.state.value.error, controller.state.value.needsSave)
        if (controller.state.value.completedHandCount > index) return
    }
    fail("第${index + 1}盘未在有界操作内结束")
}
