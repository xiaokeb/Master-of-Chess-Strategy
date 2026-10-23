package com.masterofchessstrategy.games.go.data

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.room.withTransaction
import androidx.test.platform.app.InstrumentationRegistry
import com.masterofchessstrategy.data.*
import com.masterofchessstrategy.endgame.ChineseChessEndgamePackParser
import com.masterofchessstrategy.engine.*
import com.masterofchessstrategy.games.go.*
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** UUID disk databases, actual Native, actual backup registry and SQL migration. No installation data. */
class GoAiProjectionIntegrationInstrumentedTest {
    @get:Rule val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), MocsDatabase::class.java)

    @Test fun sixteenToSeventeenPreservesExistingDataWithoutInventingProjectionProvenance() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "go-projection-migration-${UUID.randomUUID()}.db"
        try {
            helper.createDatabase(name, 16).use { db ->
                db.execSQL("INSERT INTO game_records (recordId,gameTypeCode,modeCode,difficultyCode,resultCode," +
                    "engineFormatVersion,engineState,moveCount,isFavorite,isEndgame,completedAtEpochMillis,playerIndex) " +
                    "VALUES ('prior',1,1,0,3,3,X'010203',4,1,0,99,1)")
            }
            helper.runMigrationsAndValidate(name, 17, true, MocsDatabase.MIGRATION_16_17).use { db ->
                db.query("SELECT COUNT(*) FROM go_ai_projections").use { assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0)) }
                db.query("SELECT hex(engineState),isFavorite,completedAtEpochMillis,playerIndex FROM game_records WHERE recordId='prior'").use {
                    assertTrue(it.moveToFirst()); assertEquals("010203", it.getString(0))
                    assertEquals(1, it.getInt(1)); assertEquals(99L, it.getLong(2)); assertEquals(1, it.getInt(3))
                }
                db.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
            }
        } finally { assertTrue(context.deleteDatabase(name)) }
    }

    @Test fun completeBundleRestoresProjectionRawAndOneFavoriteSharedRecordTogether() = runBlocking {
        withOwned { database ->
            val bundle = bundle(database)
            val id = seed(database, bundle)
            val registry = registry()
            val backup = backup(database, bundle, registry)
            assertNotNull(GoStoredRecordLoader(database).load(id).aiSource)
            val bytes = backup.export(200)
            val snapshot = LocalDataBackupCodec.decode(bytes, registry)
            assertEquals(1, snapshot.gameExtensions.count { it.kind == GoAiProjectionBackupAdapter.KIND })
            assertEquals(1, snapshot.gameExtensions.count { it.kind == GoLocalRecordBackupAdapter.KIND })
            backup.restore(LocalDataBackupCodec.encode(LocalDataSnapshot(300), registry))
            assertTrue(database.goAiProjectionDao().listAll().isEmpty())
            assertTrue(database.gameRecordDao().listAll().isEmpty())
            backup.restore(bytes)
            assertEquals(listOf(GoAiProjectionEntity(id)), database.goAiProjectionDao().listAll())
            val record = GoStoredRecordLoader(database).load(id)
            assertTrue(record.record.isFavorite)
            assertNotNull(record.aiSource)
            assertEquals(1, database.gameRecordDao().listAll().size)
            assertTrue(database.matchOutcomeDao().listAll().isEmpty())
        }
    }

    @Test fun missingRawMissingRecordOrMismatchedFirstTimeRejectBeforeReplacingData() = runBlocking {
        withOwned { database ->
            val bundle = bundle(database)
            val id = seed(database, bundle)
            val registry = registry()
            val backup = backup(database, bundle, registry)
            val snapshot = LocalDataBackupCodec.decode(backup.export(200), registry)
            val invalid = listOf(
                snapshot.gameExtensions.filterNot { it.kind == GoAiArchiveBackupAdapter.KIND },
                snapshot.gameExtensions.filterNot { it.kind == GoLocalRecordBackupAdapter.KIND },
                snapshot.gameExtensions.map { record ->
                    if (record.kind != GoAiArchiveBackupAdapter.KIND) record else
                        GameBackupRecord(record.kind, record.fields.toMutableList().apply { this[3] = "999" })
                },
            )
            for (extensions in invalid) {
                val bytes = LocalDataBackupCodec.encode(snapshot.copy(gameExtensions = extensions), registry)
                try { backup.restore(bytes); fail("invalid projection graph accepted") } catch (_: IllegalArgumentException) { }
                assertEquals(listOf(GoAiProjectionEntity(id)), database.goAiProjectionDao().listAll())
                assertTrue(GoStoredRecordLoader(database).load(id).record.isFavorite)
                assertTrue(database.matchOutcomeDao().listAll().isEmpty())
            }
        }
    }

    @Test fun oldRegistryCannotSilentlyOmitProjectionMetadata() = runBlocking {
        withOwned { database ->
            seed(database, bundle(database))
            val old = GameBackupRegistry(listOf(GoLocalSessionBackupAdapter(), GoLocalRecordBackupAdapter(), GoProgressBackupAdapter()) + createGoAiBackupAdapters())
            try { delegate(database, old).export(200); fail("old registry dropped association") } catch (_: IllegalArgumentException) { }
            assertEquals(1, database.goAiProjectionDao().listAll().size)
        }
    }

    @Test fun legacyRecordWithoutAssociationRemainsUnknownAndDoesNotGainRewards() = runBlocking {
        withOwned { database ->
            val id = seed(database, bundle(database))
            database.goAiProjectionDao().deleteAll() // Simulate an old unassociated record.
            val record = GoStoredRecordLoader(database).load(id)
            assertNull(record.aiSource)
            assertEquals(GoSessionOrigin.LEGACY_UNKNOWN, record.origin)
            assertTrue(database.matchOutcomeDao().listAll().isEmpty())
        }
    }

    @Test fun invalidDeferredAssociationRollsBackInsideBodyAndConnectionCanRetry() = runBlocking {
        withOwned { database ->
            try {
                database.withTransaction {
                    database.goAiProjectionDao().insert(GoAiProjectionEntity("a".repeat(64)))
                    database.requireGoAiProjectionParents()
                }
                fail("missing parents committed")
            } catch (_: IllegalStateException) { }
            assertTrue(database.goAiProjectionDao().listAll().isEmpty())
            seed(database, bundle(database))
            assertEquals(1, database.goAiProjectionDao().listAll().size)
        }
    }

    private fun bundle(database: MocsDatabase) = GoCompleteStorageBundle(database,
        GoLocalLegacySource { null }, GoLearningLegacySource { GoLearningProgress() },
        GoEndgameLegacySource { GoEndgameProgress() }, GoAiLegacySource { null })

    private fun registry() = GameBackupRegistry(listOf(GoLocalSessionBackupAdapter(), GoLocalRecordBackupAdapter(),
        GoProgressBackupAdapter()) + createGoAiBackupAdapters() + GoAiProjectionBackupAdapter())

    private fun delegate(database: MocsDatabase, registry: GameBackupRegistry) = RoomLocalDataBackupRepository(database,
        ChineseChessEndgamePackParser.loadBundled(InstrumentationRegistry.getInstrumentation().targetContext.assets), { false }, registry)

    private fun backup(database: MocsDatabase, bundle: GoCompleteStorageBundle, registry: GameBackupRegistry) =
        bundle.backupRepository { adapters -> assertEquals(8, adapters.size); delegate(database, registry) }

    private suspend fun seed(database: MocsDatabase, bundle: GoCompleteStorageBundle): String {
        val match = NativeGoEngine(9).use { engine ->
            assertEquals(ActionResult.Accepted, engine.apply(GoAction.Place(BoardPosition(0, 0))))
            repeat(2) { assertEquals(ActionResult.Accepted, engine.apply(GoAction.Pass)) }
            val revision = engine.scoringState().proposalRevision
            assertEquals(ActionResult.Accepted, engine.apply(GoAction.ConfirmScore(GoPlayer.BLACK, revision)))
            assertEquals(ActionResult.Accepted, engine.apply(GoAction.ConfirmScore(GoPlayer.WHITE, revision)))
            GoAiSavedMatch("projection-roundtrip", GoAiMatchConfig(9), engine.serialize(), completionReserved = true)
        }
        bundle.aiRepository().save(match)
        bundle.completedMatchSink().storeCompleted(match)
        val id = admitGoAiCheckpoint(match).recordId
        assertEquals(1, database.gameRecordDao().setFavorite(id, true))
        return id
    }

    private suspend fun withOwned(block: suspend (MocsDatabase) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "go-projection-${UUID.randomUUID()}.db"
        val database = Room.databaseBuilder(context, MocsDatabase::class.java, name).build()
        try { block(database) } finally { database.close(); check(context.deleteDatabase(name)) }
    }
}
