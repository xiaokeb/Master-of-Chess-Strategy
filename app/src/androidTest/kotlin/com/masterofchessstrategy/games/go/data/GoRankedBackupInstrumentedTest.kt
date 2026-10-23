package com.masterofchessstrategy.games.go.data

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.room.withTransaction
import androidx.test.platform.app.InstrumentationRegistry
import com.masterofchessstrategy.data.*
import com.masterofchessstrategy.endgame.ChineseChessEndgamePackParser
import com.masterofchessstrategy.engine.*
import com.masterofchessstrategy.games.go.*
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Real Native and isolated disk Room. Every damaged backup is re-encoded with a valid checksum. */
class GoRankedBackupInstrumentedTest {
    @get:Rule val migration = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), MocsDatabase::class.java)

    @Test fun seventeenToEighteenPreservesUnknownRecordsAndDoesNotInventRankedFacts() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "go-ranked-migration-${UUID.randomUUID()}.db"
        try {
            migration.createDatabase(name, 17).use {
                it.execSQL("INSERT INTO match_outcomes VALUES ('old-go',1,1,0,1,2,99)")
                it.execSQL("INSERT INTO game_records (recordId,gameTypeCode,modeCode,difficultyCode,resultCode," +
                    "engineFormatVersion,engineState,moveCount,isFavorite,isEndgame,completedAtEpochMillis,playerIndex) " +
                    "VALUES ('old-go',1,1,0,2,3,X'010203',2,1,0,99,1)")
            }
            migration.runMigrationsAndValidate(name, 18, true, MocsDatabase.MIGRATION_17_18).use {
                for (table in listOf("go_ranked_starts", "go_ranked_settlements")) {
                    it.query("SELECT COUNT(*) FROM $table").use { c -> assertTrue(c.moveToFirst()); assertEquals(0, c.getInt(0)) }
                }
                it.query("SELECT hex(engineState),isFavorite FROM game_records WHERE recordId='old-go'").use { c ->
                    assertTrue(c.moveToFirst()); assertEquals("010203", c.getString(0)); assertEquals(1, c.getInt(1))
                }
                it.query("SELECT settledAtEpochMillis FROM match_outcomes WHERE matchId='old-go'").use { c ->
                    assertTrue(c.moveToFirst()); assertEquals(99L, c.getLong(0))
                }
                it.query("PRAGMA foreign_key_check").use { c -> assertFalse(c.moveToFirst()) }
            }
        } finally { assertTrue(context.deleteDatabase(name)) }
    }

    @Test fun productionBundleRestoresCreationRawProjectionFavoriteOutcomeAndSettlementTogether() = runBlocking {
        owned { source, target ->
            val id = seed(source)
            val bytes = backup(source).export(500)
            val snapshot = LocalDataBackupCodec.decode(bytes, registry())
            assertEquals(1, snapshot.matchOutcomes.size)
            assertEquals(1, snapshot.gameExtensions.count { it.kind == GoRankedStartBackupAdapter.KIND })
            assertEquals(1, snapshot.gameExtensions.count { it.kind == GoRankedSettlementBackupAdapter.KIND })
            val result = backup(target).restore(bytes)
            assertEquals(1, result.gameRecordCount)
            assertEquals(1, result.matchOutcomeCount)
            assertArrayEquals(source.goRankedDao().start(id)!!.initialCheckpoint,
                target.goRankedDao().start(id)!!.initialCheckpoint)
            assertEquals(1, target.goRankedDao().settlements().size)
            assertTrue(target.gameRecordDao().find(id)!!.isFavorite)
            assertEquals(1, statistics(target).totalWins)
            assertEquals(10, statistics(target).score)
            assertArrayEquals(bytes, backup(target).export(500))
        }
    }

    @Test fun missingCreationRawProjectionRecordOrOutcomeRejectsBeforeReplacement() = runBlocking {
        owned { source, target ->
            seed(source)
            val snapshot = LocalDataBackupCodec.decode(backup(source).export(500), registry())
            val settings = AppSettings.DEFAULT.copy(soundEnabled = false, updatedAtEpochMillis = 71)
            RoomAppSettingsRepository(target.appSettingsDao()).save(settings)
            val variants = listOf(GoRankedStartBackupAdapter.KIND, GoAiArchiveBackupAdapter.KIND,
                GoAiProjectionBackupAdapter.KIND, GoLocalRecordBackupAdapter.KIND).map { kind ->
                snapshot.copy(gameExtensions = snapshot.gameExtensions.filterNot { it.kind == kind })
            } + snapshot.copy(matchOutcomes = emptyList())
            for (invalid in variants) {
                rejects { backup(target).restore(LocalDataBackupCodec.encode(invalid, registry())) }
                assertTrue(target.goRankedDao().starts().isEmpty())
                assertTrue(target.gameRecordDao().listAll().isEmpty())
                assertEquals(settings, (RoomAppSettingsRepository(target.appSettingsDao()).load() as LoadAppSettingsResult.Loaded).settings)
                for (scope in GoLegacyImportScopes.ALL) assertNull(target.localDataMigrationDao().find(scope))
            }
        }
    }

    @Test fun alteredOutcomeCreationIdentityOrTimeCannotTurnChecksummedBackupIntoGrowth() = runBlocking {
        owned { source, target ->
            seed(source)
            val snapshot = LocalDataBackupCodec.decode(backup(source).export(500), registry())
            val outcome = snapshot.matchOutcomes.single()
            val variants = listOf(
                snapshot.copy(matchOutcomes = listOf(outcome.copy(playerIndex = 0))),
                snapshot.copy(matchOutcomes = listOf(outcome.copy(difficulty = Difficulty.MASTER))),
                snapshot.copy(matchOutcomes = listOf(outcome.copy(settledAtEpochMillis = 201))),
                snapshot.copy(gameExtensions = snapshot.gameExtensions.map {
                    if (it.kind != GoRankedStartBackupAdapter.KIND) it else
                        GameBackupRecord(it.kind, it.fields.toMutableList().apply { this[2] = "a".repeat(64) })
                }),
                snapshot.copy(gameExtensions = snapshot.gameExtensions.map {
                    if (it.kind != GoRankedStartBackupAdapter.KIND) it else
                        GameBackupRecord(it.kind, it.fields.toMutableList().apply { this[3] = "201" })
                }),
            )
            for (invalid in variants) {
                rejects { backup(target).restore(LocalDataBackupCodec.encode(invalid, registry())) }
                assertTrue(target.goRankedDao().starts().isEmpty())
                assertTrue(target.matchOutcomeDao().listAll().isEmpty())
            }
        }
    }

    @Test fun oldRegistryCannotDropNonemptyFormalCreationEvenBeforeAnyOutcomeExists() = runBlocking {
        owned { source, _ ->
            seed(source, complete = false)
            val old = registry(includeRanked = false)
            rejects { delegate(source, old).export(500) }
            assertEquals(1, source.goRankedDao().starts().size)
            assertTrue(source.matchOutcomeDao().listAll().isEmpty())
        }
    }

    @Test fun restoreWriteFailureRollsBackClearAllNewTablesAndLegacyFences() = runBlocking {
        owned { source, target ->
            seed(source)
            val bytes = backup(source).export(500)
            val targetId = seed(target)
            val before = backup(target).export(600)
            val actual = GoRankedSettlementBackupAdapter()
            val failure = object : GameBackupAdapter by actual, GameBackupSnapshotGuard by actual,
                GameBackupCoreSnapshotGuard by actual {
                override fun prepareRestore(record: GameBackupRecord): PreparedGameBackup {
                    val prepared = actual.prepareRestore(record)
                    return object : PreparedGameBackup by prepared {
                        override suspend fun write(database: MocsDatabase) {
                            prepared.write(database)
                            throw IOException("owned ranked restore late failure")
                        }
                    }
                }
            }
            try { delegate(target, registry(settlement = failure)).restore(bytes); fail("restore failure hidden") }
            catch (_: IOException) { }
            assertNotNull(target.goRankedDao().settlement(targetId))
            assertArrayEquals(before, backup(target).export(600))
        }
    }

    @Test fun oldBackupReplacesNewTablesWithoutGrantingOldGoOutcomesRankedStatus() = runBlocking {
        owned { source, target ->
            seed(source)
            val fresh = backup(source).export(500)
            backup(target).restore(fresh)
            val oldOutcome = MatchOutcome("legacy-go", GameType.GO, StoredGameMode.HUMAN_VS_AI,
                Difficulty.MASTER, 1, GameResult.SECOND_PLAYER_WIN, 5)
            val old = LocalDataBackupCodec.encode(LocalDataSnapshot(600, matchOutcomes = listOf(oldOutcome)), registry())
            backup(target).restore(old)
            assertTrue(target.goRankedDao().starts().isEmpty())
            assertTrue(target.goRankedDao().settlements().isEmpty())
            assertTrue(target.goAiProjectionDao().listAll().isEmpty())
            assertEquals(1, target.matchOutcomeDao().listAll().size)
            assertEquals(0, statistics(target).totalWins)
            backup(target).restore(fresh)
            assertEquals(1, statistics(target).totalWins)
        }
    }

    @Test fun invalidDeferredSettlementRollsBackWithinBodyAndConnectionRetries() = runBlocking {
        owned { source, _ ->
            try {
                source.withTransaction {
                    source.goRankedDao().insertSettlement(GoRankedSettlementEntity("b".repeat(64)))
                    source.requireGoRankedParents()
                }
                fail("orphan settlement committed")
            } catch (_: IllegalStateException) { }
            assertTrue(source.goRankedDao().settlements().isEmpty())
            seed(source)
            assertEquals(1, statistics(source).totalWins)
        }
    }

    private fun bundle(database: MocsDatabase) = GoCompleteStorageBundle(database,
        GoLocalLegacySource { null }, GoLearningLegacySource { GoLearningProgress() },
        GoEndgameLegacySource { GoEndgameProgress() }, GoAiLegacySource { null })
    private fun registry(includeRanked: Boolean = true, settlement: GameBackupAdapter = GoRankedSettlementBackupAdapter()) =
        GameBackupRegistry(listOf(GoLocalSessionBackupAdapter(), GoLocalRecordBackupAdapter(), GoProgressBackupAdapter()) +
            createGoAiBackupAdapters() + GoAiProjectionBackupAdapter() +
            if (includeRanked) listOf(GoRankedStartBackupAdapter(), settlement) else emptyList())
    private fun delegate(database: MocsDatabase, registry: GameBackupRegistry) = RoomLocalDataBackupRepository(database,
        ChineseChessEndgamePackParser.loadBundled(InstrumentationRegistry.getInstrumentation().targetContext.assets), { false }, registry)
    private fun backup(database: MocsDatabase) = bundle(database).backupRepository {
        assertEquals(8, it.size)
        delegate(database, GameBackupRegistry(it))
    }
    private suspend fun statistics(database: MocsDatabase) =
        (RoomMatchStatisticsRepository(database).load() as LoadMatchStatisticsResult.Loaded).statistics
    private suspend fun seed(database: MocsDatabase, complete: Boolean = true): String {
        val learning = RoomGoLearningProgressRepository(database)
        learning.save(GoLearningProgress(GoLearningLibrary.lessons.map { it.id }.toSet()))
        val checkpoint = GoAiCheckpointRepository(RoomGoAiCheckpointStore(database), clock = { 200L })
        return NativeGoEngine(9).use { engine ->
            val initial = GoAiSavedMatch(UUID.randomUUID().toString(), GoAiMatchConfig(9, humanPlayer = GoPlayer.WHITE), engine.serialize())
            val start = RoomGoRankedMatchCreator(database, checkpoint, learning, clock = { 100L }).create(initial)
            if (complete) {
                repeat(2) { assertEquals(ActionResult.Accepted, engine.apply(GoAction.Pass)) }
                val revision = engine.scoringState().proposalRevision
                for (side in GoPlayer.entries) assertEquals(ActionResult.Accepted, engine.apply(GoAction.ConfirmScore(side, revision)))
                val terminal = GoAiSavedMatch(initial.matchId, initial.config, engine.serialize(), completionReserved = true)
                checkpoint.save(terminal)
                RoomGoAiCompletionRepository(database).storeCompleted(terminal)
                assertEquals(1, database.gameRecordDao().setFavorite(start.recordId, true))
            }
            start.recordId
        }
    }
    private suspend fun rejects(block: suspend () -> Unit) {
        try { block(); fail("invalid backup accepted") } catch (_: IllegalArgumentException) { }
    }
    private suspend fun owned(block: suspend (MocsDatabase, MocsDatabase) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val names = List(2) { "go-ranked-backup-${UUID.randomUUID()}.db" }
        val databases = names.map { Room.databaseBuilder(context, MocsDatabase::class.java, it).build() }
        try { block(databases[0], databases[1]) } finally {
            databases.forEach { it.close() }
            // Room opens lazily; tests that use only the first DB never create the second file.
            names.forEach { if (context.getDatabasePath(it).exists()) check(context.deleteDatabase(it)) }
        }
    }
}
