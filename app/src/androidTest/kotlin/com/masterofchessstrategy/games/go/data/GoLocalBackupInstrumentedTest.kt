package com.masterofchessstrategy.games.go.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.masterofchessstrategy.data.*
import com.masterofchessstrategy.endgame.ChineseChessEndgamePackParser
import com.masterofchessstrategy.engine.*
import com.masterofchessstrategy.games.go.*
import com.masterofchessstrategy.engine.games.tiger_goat.NativeTigerGoatSession
import com.masterofchessstrategy.games.tiger_goat.data.TigerGoatBackupAdapter
import com.masterofchessstrategy.games.competition_mahjong.data.CompetitionMahjongBackupAdapter
import com.masterofchessstrategy.games.competition_mahjong.match.*
import com.masterofchessstrategy.games.competition_mahjong.match.policy.CompetitionMahjongAssistanceUsage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.util.UUID

/** Actual Native admission and one Room replacement; no caller data or preferences are cleared. */
@RunWith(AndroidJUnit4::class)
class GoLocalBackupInstrumentedTest {
    @Test fun mixedCoreTigerCompetitionAndGoRoundTripAndCoreIdCollisionRejectsBeforeClear() = runBlocking {
        withDatabase { source -> withDatabase { target ->
            val identity = seed(source)
            val chess = NativeChineseChessEngine().use { it.serialize() }
            RoomGameSessionRepository(source.activeGameDao()).save(GameSessionSnapshot(GameType.CHINESE_CHESS,
                StoredGameMode.LOCAL_TWO_PLAYER, null, chess, 7, sessionId = "mixed-xiangqi"))
            // Core supports external agreements: a zero-move agreed draw retains a legal initial board.
            val chessRecord = GameRecord("mixed-xiangqi-agreed-draw", GameType.CHINESE_CHESS,
                StoredGameMode.LOCAL_TWO_PLAYER, null, GameResult.DRAW, chess, 0, completedAtEpochMillis = 8)
            RoomGameRecordRepository(source.gameRecordDao()).saveCompleted(chessRecord)
            NativeTigerGoatSession().use { tiger ->
                source.activeGameDao().upsert(TigerGoatActiveArchive("mixed-tiger", tiger.serialize(),
                    tiger.snapshot().actionCount, 9).toEntity())
            }
            val config = CompetitionMahjongCompetitionConfig(UUID.randomUUID().toString(), 925, 0, 2)
            NativeCompetitionMahjongSession(925).use { hand ->
                RoomCompetitionMahjongMatchStore(source.competitionMahjongMatchDao()) { 10 }.save(
                    CompetitionMahjongMatchCheckpoint(config, CompetitionMahjongMatchLedger.empty(), 0, hand.serialize(),
                        CompetitionMahjongAssistanceUsage(Difficulty.HARD)))
            }
            val registry = registry(TigerGoatBackupAdapter(), CompetitionMahjongBackupAdapter())
            val bytes = repository(source, registry).export(50)
            val summary = repository(target, registry).restore(bytes)
            assertEquals(3, summary.activeSessionCount)
            assertEquals(2, summary.gameRecordCount)
            assertEquals(1, summary.competitionMatchCount)
            assertArrayEquals(bytes, repository(target, registry).export(50))
            val snapshot = LocalDataBackupCodec.decode(bytes, registry)
            val conflicting = snapshot.copy(gameRecords = listOf(chessRecord.copy(recordId = identity.recordId)))
            val conflictBytes = LocalDataBackupCodec.encode(conflicting, registry)
            // Any DELETE would fail with a different exception, independently proving pre-clear rejection.
            target.openHelper.writableDatabase.execSQL("CREATE TRIGGER forbid_mixed_clear BEFORE DELETE ON active_games " +
                "BEGIN SELECT RAISE(ABORT, 'must-not-clear-on-record-id-conflict'); END")
            rejects { repository(target, registry).restore(conflictBytes) }
            assertArrayEquals(bytes, repository(target, registry).export(50))
        } }
    }

    @Test fun allLocalCategoriesRoundTripIncludingLargeHistoryAndStandaloneUnknownSourceRecord() = runBlocking {
        withDatabase { database ->
            val identity = seed(database, large = true)
            val original = database.gameRecordDao().find(identity.recordId)!!
            assertTrue(original.engineState.size > 65_536)
            val legacyId = "legacy-go-archive-no-source"
            database.gameRecordDao().insert(original.copy(recordId = legacyId))
            val registry = registry()
            val repository = repository(database, registry)
            val backup = repository.export(50)
            val snapshot = LocalDataBackupCodec.decode(backup, registry)
            assertTrue(snapshot.gameRecords.isEmpty()) // Never enter the Xiangqi RECORD wire/Native validator.
            assertEquals(2, snapshot.gameExtensions.count { it.kind == GoLocalRecordBackupAdapter.KIND })
            assertTrue(snapshot.gameExtensions.any { it.kind == GoProgressBackupAdapter.KIND })
            val previousCheckpoint = database.goLocalSessionDao().find(GameType.GO.code)!!.checkpoint
            database.gameRecordDao().setFavorite(identity.recordId, false)
            RoomGoLearningProgressRepository(database).clear()
            val summary = repository.restore(backup)
            assertArrayEquals(previousCheckpoint, database.goLocalSessionDao().find(GameType.GO.code)!!.checkpoint)
            assertEquals(original, database.gameRecordDao().find(identity.recordId))
            assertEquals(2, database.gameRecordDao().listAll().size)
            assertNull(database.goRecordSourceDao().find(legacyId))
            assertEquals(identity.origin.code, database.goRecordSourceDao().find(identity.recordId)!!.originCode)
            assertTrue(RoomGoLearningProgressRepository(database).load().isCompleted)
            val endgame = RoomGoEndgameProgressRepository(database).load()
            assertEquals(LocalDate.of(2026, 10, 16), endgame.dailyDate)
            assertEquals(GoEndgameChallengeMode.THEME, endgame.lastMode)
            assertArrayEquals(backup, repository.export(50))
            assertTrue(database.matchOutcomeDao().listAll().isEmpty())
            assertTrue(database.endgameProgressDao().listAll().isEmpty())
            assertNull(database.tutorialProgressDao().find(GameType.CHINESE_CHESS.code))
            // The public summary must count extension-owned records too, not silently report zero.
            assertEquals(2, summary.gameRecordCount)
        }
    }

    @Test fun emptyValidBackupClearsAllGoRowsAndSealsEveryLegacySource() = runBlocking {
        withDatabase { database ->
            seed(database)
            val registry = registry()
            repository(database, registry).restore(LocalDataBackupCodec.encode(LocalDataSnapshot(50), registry))
            assertTrue(database.goLocalSessionDao().listAll().isEmpty())
            assertTrue(database.goRecordSourceDao().listAll().isEmpty())
            assertTrue(database.goProgressDao().listAll().isEmpty())
            assertTrue(database.gameRecordDao().listAll().isEmpty())
            for (scope in GoLegacyImportScopes.ALL) assertNotNull(database.localDataMigrationDao().find(scope))
            assertNull(RoomGoSessionRepository(database).load())
            assertTrue(RoomGoLearningProgressRepository(database).load().completedLessonIds.isEmpty())
            assertTrue(RoomGoEndgameProgressRepository(database).load().completedIds.isEmpty())
        }
    }

    @Test fun realNativeRejectsForgedCompletedHistoryAndMissingTerminalPairBeforeDeletingAnything() = runBlocking {
        withDatabase { database ->
            seed(database)
            val registry = registry()
            val repository = repository(database, registry)
            val before = repository.export(50)
            val snapshot = LocalDataBackupCodec.decode(before, registry)
            val missingRecords = snapshot.copy(gameExtensions = snapshot.gameExtensions.filter { it.kind != GoLocalRecordBackupAdapter.KIND })
            rejects { repository.restore(LocalDataBackupCodec.encode(missingRecords, registry)) }
            assertArrayEquals(before, repository.export(50))
            NativeGoEngine(9).use { engine ->
                val forged = GameRecord("forged-ongoing-as-draw", GameType.GO, StoredGameMode.LOCAL_TWO_PLAYER,
                    null, GameResult.DRAW, engine.serialize(), 0, completedAtEpochMillis = 3)
                val wire = GoLocalRecordBackupAdapter().encode(forged, null)
                rejects { repository.restore(LocalDataBackupCodec.encode(LocalDataSnapshot(50, gameExtensions = listOf(wire)), registry)) }
            }
            assertArrayEquals(before, repository.export(50))
        }
    }

    @Test fun failureAfterAllGoPreparedWritesRollsBackEveryGoTableAndOldPublicSettings() = runBlocking {
        withDatabase { database ->
            seed(database)
            var sawReplacement = false
            val failing = object : GameBackupAdapter {
                override val descriptor = GameBackupDescriptor("TEST_AFTER_GO", 5,
                    BackupStorageSlot(BackupStorageTable.ACTIVE_GAME, GameType.LANZHOU_SQUARE_CHESS), 1, 99, 100)
                fun record() = GameBackupRecord(descriptor.kind, listOf(GameType.LANZHOU_SQUARE_CHESS.code.toString()))
                override fun validate(record: GameBackupRecord, containerVersion: Int) { require(record == record()) }
                override suspend fun read(database: MocsDatabase) = emptyList<GameBackupRecord>()
                override fun prepareRestore(record: GameBackupRecord) = object : PreparedGameBackup {
                    override val storageSlot = descriptor.storageSlot
                    override suspend fun write(database: MocsDatabase) {
                        assertEquals(1, database.goLocalSessionDao().listAll().size)
                        assertEquals(1, database.gameRecordDao().listAll().size)
                        assertEquals(1, database.goRecordSourceDao().listAll().size)
                        assertEquals(2, database.goProgressDao().listAll().size)
                        sawReplacement = true
                        throw IllegalStateException("failure after all Go writes")
                    }
                }
            }
            val registry = registry(failing)
            val repository = repository(database, registry)
            val before = repository.export(50)
            val original = LocalDataBackupCodec.decode(before, registry)
            val failingBackup = LocalDataBackupCodec.encode(original.copy(gameExtensions = original.gameExtensions + failing.record()), registry)
            val oldSettings = AppSettings.DEFAULT.copy(soundEnabled = false, updatedAtEpochMillis = 88)
            RoomAppSettingsRepository(database.appSettingsDao()).save(oldSettings)
            val retained = repository.export(50)
            try { repository.restore(failingBackup); fail("must roll back") }
            catch (error: IllegalStateException) { assertEquals("failure after all Go writes", error.message) }
            assertTrue(sawReplacement)
            assertArrayEquals(retained, repository.export(50))
        }
    }

    private suspend fun seed(database: MocsDatabase, large: Boolean = false): GoSessionIdentity {
        val identity = GoSessionIdentity.create(GoSessionOrigin.SGF_IMPORT)
        NativeGoEngine(9).use { engine ->
            if (large) assertEquals(ActionResult.Accepted, engine.apply(GoAction.Place(BoardPosition(0, 0))))
            repeat(2) { assertEquals(ActionResult.Accepted, engine.apply(GoAction.Pass)) }
            if (large) repeat(3_200) {
                assertEquals(ActionResult.Accepted, engine.apply(GoAction.ToggleDeadGroup(BoardPosition(0, 0), GoPlayer.BLACK)))
            }
            val revision = engine.scoringState().proposalRevision
            assertEquals(ActionResult.Accepted, engine.apply(GoAction.ConfirmScore(GoPlayer.BLACK, revision)))
            assertEquals(ActionResult.Accepted, engine.apply(GoAction.ConfirmScore(GoPlayer.WHITE, revision)))
            RoomGoSessionRepository(RoomGoLocalSessionStore(database), clock = { 17 }).save(
                GoSavedSession(9, engine.serialize(), GoScoreDisplayMode.POINTS, identity = identity))
            database.gameRecordDao().setFavorite(identity.recordId, true)
        }
        RoomGoLearningProgressRepository(database).save(GoLearningProgress(GoLearningLibrary.lessons.map { it.id }.toSet(), "connection"))
        val endgame = RoomGoEndgameProgressRepository(database)
        val first = GoEndgameLibrary.puzzles.first().id
        endgame.complete(first)
        val date = LocalDate.of(2026, 10, 16)
        endgame.selectDaily(date, GoEndgameChallenges.daily(endgame.load(), date).id)
        endgame.select(GoEndgameLibrary.puzzles[1].id, GoEndgameChallengeMode.THEME)
        return identity
    }

    private fun registry(vararg extra: GameBackupAdapter) = GameBackupRegistry(
        listOf(GoLocalSessionBackupAdapter(), GoLocalRecordBackupAdapter(), GoProgressBackupAdapter()) + extra)
    private fun repository(database: MocsDatabase, registry: GameBackupRegistry) = RoomLocalDataBackupRepository(
        database, ChineseChessEndgamePackParser.loadBundled(InstrumentationRegistry.getInstrumentation().targetContext.assets),
        { bytes -> NativeChineseChessEngine().use { it.restore(bytes) == RestoreResult.Restored } }, registry)
    private suspend fun withDatabase(block: suspend (MocsDatabase) -> Unit) {
        val database = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext,
            MocsDatabase::class.java).build()
        try { block(database) } finally { database.close() }
    }
    private suspend fun rejects(block: suspend () -> Unit) {
        try { block(); fail("must reject before clearing") } catch (_: IllegalArgumentException) { }
    }
}
