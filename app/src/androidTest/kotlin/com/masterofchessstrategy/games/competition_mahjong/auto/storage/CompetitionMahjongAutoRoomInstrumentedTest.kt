package com.masterofchessstrategy.games.competition_mahjong.auto.storage

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import com.masterofchessstrategy.data.GameBackupRecord
import com.masterofchessstrategy.data.GameBackupRegistry
import com.masterofchessstrategy.data.LocalDataBackupCodec
import com.masterofchessstrategy.data.LocalDataSnapshot
import com.masterofchessstrategy.data.MocsDatabase
import com.masterofchessstrategy.data.RoomLocalDataBackupRepository
import com.masterofchessstrategy.endgame.ChineseChessEndgamePackParser
import com.masterofchessstrategy.engine.CompetitionMahjongAutoConfig
import com.masterofchessstrategy.engine.NativeCompetitionMahjongAutoSession
import com.masterofchessstrategy.games.competition_mahjong.auto.CompetitionMahjongAutoArchive
import com.masterofchessstrategy.games.competition_mahjong.auto.CompetitionMahjongAutoCheckpoint
import com.masterofchessstrategy.games.competition_mahjong.auto.CompetitionMahjongAutoCheckpointCodec
import com.masterofchessstrategy.games.competition_mahjong.auto.CompetitionMahjongAutoRunConfig
import com.masterofchessstrategy.games.competition_mahjong.auto.replay.CompetitionMahjongAutoExportCodec
import com.masterofchessstrategy.games.competition_mahjong.auto.replay.CompetitionMahjongAutoImportResult
import com.masterofchessstrategy.games.competition_mahjong.auto.replay.CompetitionMahjongAutoReplayInstrumentedTest.Companion.nativeArchive
import com.masterofchessstrategy.games.competition_mahjong.learning.CompetitionMahjongCoordinatedBackupRepository
import com.masterofchessstrategy.games.competition_mahjong.learning.CompetitionMahjongStorageEpochGate
import java.io.IOException
import java.util.UUID
import kotlin.io.encoding.Base64
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class CompetitionMahjongAutoRoomInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    @get:Rule val migration = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), MocsDatabase::class.java)

    @Test fun version23MigrationKeepsExistingLearningRows() {
        val name = "competition-auto-migration-${UUID.randomUUID()}.db"
        try {
            migration.createDatabase(name, 23).use {
                it.execSQL("INSERT INTO competition_mahjong_learning_proof VALUES (0,X'010203')")
            }
            migration.runMigrationsAndValidate(name, 24, true, MocsDatabase.MIGRATION_23_24).use { db ->
                db.query("SELECT hex(proof) FROM competition_mahjong_learning_proof").use {
                    assertTrue(it.moveToFirst())
                    assertEquals("010203", it.getString(0))
                }
                db.query("SELECT COUNT(*) FROM competition_mahjong_auto").use {
                    assertTrue(it.moveToFirst())
                    assertEquals(0, it.getInt(0))
                }
            }
        } finally { assertTrue(context.deleteDatabase(name)) }
    }

    @Test fun activityAndArchiveRollbackTogetherAndFavoriteSurvivesDiskReopen() = runBlocking {
        val name = "competition-auto-room-${UUID.randomUUID()}.db"
        val archive = nativeArchive()
        val viewer = archive.checkpoint.config.viewerPlayerId
        val first = initial()
        try {
            withDatabase(open(name)) { db ->
                val store = RoomCompetitionMahjongAutoStore(db, viewer) { 100 }
                val stale = RoomCompetitionMahjongAutoStore(db, viewer)
                assertNull(store.load(viewer))
                assertNull(stale.load(viewer))
                store.save(first)
                assertTrue(runCatching { stale.save(archive.checkpoint, archive) }.isFailure)
                db.openHelper.writableDatabase.execSQL("""
                    CREATE TRIGGER abort_auto_activity BEFORE UPDATE ON competition_mahjong_auto
                    WHEN NEW.recordKey = '' BEGIN SELECT RAISE(ABORT, 'activity failure'); END
                """.trimIndent())
                assertTrue(runCatching { store.save(archive.checkpoint, archive) }.isFailure)
                assertTrue(store.list(viewer).isEmpty())
                assertArrayEquals(first.engineRecord, store.load(viewer)?.engineRecord)
                db.openHelper.writableDatabase.execSQL("DROP TRIGGER abort_auto_activity")
                store.save(archive.checkpoint, archive)
                store.setFavorite(archive.key, viewer, true)
                store.save(archive.checkpoint, archive)
                assertTrue(store.list(viewer).single().favorite)
            }
            withDatabase(open(name)) { db ->
                val store = RoomCompetitionMahjongAutoStore(db, viewer)
                assertEquals(100L, store.list(viewer).single().savedAtEpochMillis)
                assertTrue(store.list(viewer).single().favorite)
                assertEquals(archive.checkpoint.ledger.results, store.load(viewer)?.ledger?.results)
                assertTrue(archive.sameContent(checkNotNull(store.load(archive.key, viewer))))
                store.delete(archive.key, viewer)
                assertNull(store.load(archive.key, viewer))
                assertArrayEquals(archive.checkpoint.engineRecord, store.load(viewer)?.engineRecord)
                assertTrue(db.matchOutcomeDao().listAll().isEmpty())
            }
        } finally { assertTrue(context.deleteDatabase(name)) }
    }

    @Test fun coordinatedBackupRestoresArchiveAndRejectsForgedStandardWallBeforeClearing() = runBlocking {
        withDatabase(Room.inMemoryDatabaseBuilder(context, MocsDatabase::class.java).build()) { db ->
            val archive = nativeArchive()
            val viewer = archive.checkpoint.config.viewerPlayerId
            val registry = GameBackupRegistry(listOf(CompetitionMahjongAutoBackupAdapter()))
            val repository = CompetitionMahjongCoordinatedBackupRepository(
                RoomLocalDataBackupRepository(db, ChineseChessEndgamePackParser.loadBundled(context.assets), { false }, registry),
                CompetitionMahjongStorageEpochGate.forDatabase(db),
            )
            val old = RoomCompetitionMahjongAutoStore(db, viewer) { 100 }
            old.load(viewer)
            old.save(archive.checkpoint, archive)
            old.setFavorite(archive.key, viewer, true)
            val exported = repository.export(200)
            val envelope = LocalDataBackupCodec.decode(exported, registry)
            assertEquals(2, envelope.gameExtensions.size)

            val other = initial(differentWall = true)
            val active = envelope.gameExtensions.single { it.fields[3] == "-" }
            val forgedRecord = GameBackupRecord(active.kind,
                active.fields.dropLast(1) + Base64.encode(CompetitionMahjongAutoCheckpointCodec.encode(other)))
            val forged = LocalDataBackupCodec.encode(LocalDataSnapshot(200, gameExtensions = listOf(forgedRecord)), registry)
            assertTrue(runCatching { repository.restore(forged) }.isFailure)
            assertArrayEquals(exported, repository.export(200))
            assertTrue(runCatching { old.save(archive.checkpoint, archive) }.exceptionOrNull() is IOException)

            val current = RoomCompetitionMahjongAutoStore(db, viewer)
            current.delete(archive.key, viewer)
            repository.restore(exported)
            val restored = RoomCompetitionMahjongAutoStore(db, viewer)
            assertTrue(restored.list(viewer).single().favorite)
            assertTrue(archive.sameContent(checkNotNull(restored.load(archive.key, viewer))))
            assertArrayEquals(exported, repository.export(200))
            assertTrue(db.matchOutcomeDao().listAll().isEmpty())
        }
    }

    @Test fun singleFileImportChecksNativeHistoryAndNeverReplacesExistingRows() = runBlocking {
        withDatabase(Room.inMemoryDatabaseBuilder(context, MocsDatabase::class.java).build()) { db ->
            val archive = nativeArchive()
            val checkpoint = archive.checkpoint
            val config = checkpoint.config
            val viewer = config.viewerPlayerId
            val document = CompetitionMahjongAutoExportCodec.encode(archive)
            val store = RoomCompetitionMahjongAutoStore(db, viewer) { 100 }
            val before = initial()
            store.load(viewer)
            store.save(before)
            assertTrue(runCatching { store.importArchive(document) }.isFailure)
            assertTrue(store.list(viewer).isEmpty())
            assertArrayEquals(before.engineRecord, store.load(viewer)?.engineRecord)

            val other = CompetitionMahjongAutoRunConfig(UUID.randomUUID().toString(), config.seed, viewer,
                config.playerBotLevels, config.decisionSeed)
            store.save(CompetitionMahjongAutoCheckpoint(other, before.settings, 0, 0, emptyList(),
                before.baseline, before.engineRecord))
            val activeRow = checkNotNull(db.competitionMahjongAutoDao().find(viewer, ""))
            val badCrc = document.copyOf().apply { this[lastIndex] = (last().toInt() xor 1).toByte() }
            assertTrue(runCatching { store.importArchive(badCrc) }.isFailure)
            val otherViewer = RoomCompetitionMahjongAutoStore(db, (viewer + 1) % 4)
            assertTrue(runCatching { otherViewer.importArchive(document) }.isFailure)

            val wrongSeed = CompetitionMahjongAutoRunConfig(config.runId, config.seed + 1, viewer,
                config.playerBotLevels, config.decisionSeed)
            val forged = CompetitionMahjongAutoExportCodec.encode(copyArchive(archive, wrongSeed, checkpoint.elapsedMillis))
            assertNotNull(CompetitionMahjongAutoExportCodec.decode(forged, viewer))
            assertTrue(runCatching { store.importArchive(forged) }.isFailure)
            assertTrue(store.list(viewer).isEmpty())

            assertEquals(CompetitionMahjongAutoImportResult.IMPORTED, store.importArchive(document))
            store.setFavorite(archive.key, viewer, true)
            val anotherOwner = RoomCompetitionMahjongAutoStore(db, viewer) { 200 }
            assertEquals(CompetitionMahjongAutoImportResult.ALREADY_PRESENT, anotherOwner.importArchive(document))
            val conflict = CompetitionMahjongAutoExportCodec.encode(copyArchive(archive, config, checkpoint.elapsedMillis + 1))
            assertTrue(runCatching { anotherOwner.importArchive(conflict) }.isFailure)
            assertEquals(100L, store.list(viewer).single().savedAtEpochMillis)
            assertTrue(store.list(viewer).single().favorite)
            assertArrayEquals(document, CompetitionMahjongAutoExportCodec.encode(checkNotNull(store.load(archive.key, viewer))))
            assertArrayEquals(activeRow.payload, db.competitionMahjongAutoDao().find(viewer, "")?.payload)
            assertTrue(otherViewer.list((viewer + 1) % 4).isEmpty())
            assertTrue(db.matchOutcomeDao().listAll().isEmpty())

            CompetitionMahjongStorageEpochGate.forDatabase(db).exclusive(replacing = true) {}
            assertTrue(runCatching { store.importArchive(document) }.isFailure)
        }
    }

    private fun copyArchive(archive: CompetitionMahjongAutoArchive, config: CompetitionMahjongAutoRunConfig,
                            elapsedMillis: Long): CompetitionMahjongAutoArchive = archive.checkpoint.let {
        CompetitionMahjongAutoArchive(CompetitionMahjongAutoCheckpoint(config, it.settings, it.seriesIndex,
            it.activeHandIndex, it.completedHands, it.baseline, it.engineRecord, it.openingRecord, elapsedMillis))
    }

    private fun open(name: String) = Room.databaseBuilder(context, MocsDatabase::class.java, name).build()

    private inline fun withDatabase(database: MocsDatabase, block: (MocsDatabase) -> Unit) {
        try { block(database) } finally { database.close() }
    }

    private fun initial(differentWall: Boolean = false): CompetitionMahjongAutoCheckpoint {
        val complete = nativeArchive().checkpoint
        val expected = complete.config.handConfig(0, 0, false)
        val config = if (differentWall) CompetitionMahjongAutoConfig(
            expected.seed + 1, expected.dealerSeat, expected.roundWind, expected.viewerSeat, expected.botLevels, expected.decisionSeed,
        ) else expected
        val record = NativeCompetitionMahjongAutoSession(config).use { it.serialize() }
        return CompetitionMahjongAutoCheckpoint(complete.config, complete.settings, 0, 0, emptyList(), record, record)
    }
}
