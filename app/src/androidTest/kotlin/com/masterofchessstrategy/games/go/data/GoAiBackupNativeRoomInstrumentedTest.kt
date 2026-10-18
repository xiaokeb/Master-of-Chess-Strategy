package com.masterofchessstrategy.games.go.data

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.masterofchessstrategy.data.*
import com.masterofchessstrategy.endgame.ChineseChessEndgamePackParser
import com.masterofchessstrategy.engine.*
import com.masterofchessstrategy.games.go.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

/** Real rules plus private Room databases; factory registration here is not a production switch. */
class GoAiBackupNativeRoomInstrumentedTest {
    @Test fun parentGraphFirstArchivesAndFullActivePolicyRoundTripWithoutRankProjection() = runBlocking {
        withTwoDatabases { source, target ->
            var now = 11L
            val checkpoints = GoAiCheckpointRepository(RoomGoAiCheckpointStore(source), clock = { now })
            val parent = match("backup-parent", terminal = true, series = true)
            val child = match("backup-child", terminal = true, series = true)
            checkpoints.save(parent)
            now = 22
            checkpoints.save(child)
            val final = controls(child)
            now = 33
            checkpoints.save(final)
            val registry = registry()
            val bytes = repository(source, registry).export(100)
            target.localDataMigrationDao().insert(LocalDataMigrationEntity(GoLegacyImportScopes.AI_MATCH, 7))
            val restored = repository(target, registry).restore(bytes)
            assertEquals(1, restored.activeSessionCount)
            assertEquals(0, restored.gameRecordCount) // Raw source is not a shared record-list projection.
            val active = target.goAiCheckpointDao().findActive(GameType.GO.code)!!
            assertArrayEquals(image(final), active.checkpoint)
            assertEquals(33L, active.updatedAtEpochMillis)
            assertEquals(3, active.originCode)
            val parentId = admitGoAiCheckpoint(parent).recordId
            val childId = admitGoAiCheckpoint(child).recordId
            assertEquals(parentId, active.replacedTerminalRecordId)
            val firstParent = target.goAiCheckpointDao().findArchive(parentId)!!
            val firstChild = target.goAiCheckpointDao().findArchive(childId)!!
            assertArrayEquals(image(parent), firstParent.checkpoint)
            assertArrayEquals(image(child), firstChild.checkpoint)
            assertEquals(11L, firstParent.completedAtEpochMillis)
            assertEquals(22L, firstChild.completedAtEpochMillis)
            assertEquals(parentId, firstChild.replacedTerminalRecordId)
            assertEquals(2, target.goAiCheckpointDao().listArchives().size)
            assertTrue(target.gameRecordDao().listAll().isEmpty())
            assertTrue(target.matchOutcomeDao().listAll().isEmpty())
            for (scope in GoLegacyImportScopes.ALL) assertTrue(target.localDataMigrationDao().find(scope)!!.handledVersion >= 1)
            assertEquals(7, target.localDataMigrationDao().find(GoLegacyImportScopes.AI_MATCH)!!.handledVersion)
            assertArrayEquals(bytes, repository(target, registry).export(100))
        }
    }

    @Test fun identicalTerminalActiveAndArchiveUseOneNativeAdmissionPerIndependentBatch() = runBlocking {
        withTwoDatabases { source, target ->
            GoAiCheckpointRepository(RoomGoAiCheckpointStore(source)).save(match("one-admission", terminal = true))
            val calls = AtomicInteger()
            val registry = registry(calls)
            val bytes = repository(source, registry).export(100)
            assertEquals(1, calls.get())
            calls.set(0)
            val result = repository(target, registry).restore(bytes)
            assertEquals(1, calls.get()) // Reusing the registry must not reuse another call's cache.
            assertEquals(1, result.activeSessionCount)
            assertEquals(0, result.gameRecordCount)
            assertEquals(1, target.goAiCheckpointDao().listArchives().size)
        }
    }

    @Test fun aMissingParentIsRejectedBeforeClearingAnyTargetRowsOrCreatingFences() = runBlocking {
        withTwoDatabases { _, target ->
            val settings = AppSettings.DEFAULT.copy(soundEnabled = false, updatedAtEpochMillis = 91)
            RoomAppSettingsRepository(target.appSettingsDao()).save(settings)
            val pair = createGoAiBackupAdapters()
            val registry = registry(ai = pair)
            val saved = match("missing-parent", terminal = true)
            val id = admitGoAiCheckpoint(saved).recordId
            val archive = GoAiArchiveEntity(id, GameType.GO.code, 6, image(saved), 3, "f".repeat(64), 17)
            val wires = (pair[1] as GoAiArchiveBackupAdapter).encodeRows(listOf(archive))
            val bytes = LocalDataBackupCodec.encode(LocalDataSnapshot(1, gameExtensions = wires), registry)
            rejects { repository(target, registry).restore(bytes) }
            assertEquals(settings, (RoomAppSettingsRepository(target.appSettingsDao()).load() as LoadAppSettingsResult.Loaded).settings)
            assertTrue(target.goAiCheckpointDao().listActive().isEmpty())
            assertTrue(target.goAiCheckpointDao().listArchives().isEmpty())
            for (scope in GoLegacyImportScopes.ALL) assertNull(target.localDataMigrationDao().find(scope))
        }
    }

    @Test fun failureAfterAiWritesRollsBackClearRawGraphSettingsAndAllFourFences() = runBlocking {
        withTwoDatabases { _, target ->
            val settings = AppSettings.DEFAULT.copy(soundEnabled = false, updatedAtEpochMillis = 91)
            RoomAppSettingsRepository(target.appSettingsDao()).save(settings)
            var reached = false
            val failing = object : GameBackupAdapter {
                override val descriptor = GameBackupDescriptor("TEST_AI_LATE_WRITE", 5,
                    BackupStorageSlot(BackupStorageTable.ACTIVE_GAME, GameType.TIGER_AND_GOAT), 1, 15, 100)
                override fun validate(record: GameBackupRecord, containerVersion: Int) {
                    require(record.fields == listOf(GameType.TIGER_AND_GOAT.code.toString()))
                }
                override suspend fun read(database: MocsDatabase) = emptyList<GameBackupRecord>()
                override fun prepareRestore(record: GameBackupRecord) = object : PreparedGameBackup {
                    override val storageSlot = descriptor.storageSlot
                    override suspend fun write(database: MocsDatabase) {
                        assertNotNull(database.goAiCheckpointDao().findActive(GameType.GO.code))
                        assertEquals(1, database.goAiCheckpointDao().listArchives().size)
                        for (scope in GoLegacyImportScopes.ALL) assertNotNull(database.localDataMigrationDao().find(scope))
                        reached = true
                        throw IOException("test failure after raw AI writes")
                    }
                }
            }
            val pair = createGoAiBackupAdapters()
            val registry = registry(extra = listOf(failing), ai = pair)
            val saved = match("late-write", terminal = true)
            val active = (pair[0] as GoAiSessionBackupAdapter).encodeRows(listOf(GoAiSessionEntity(
                GameType.GO.code, 6, image(saved), 2, null, 31)))
            val archive = (pair[1] as GoAiArchiveBackupAdapter).encodeRows(listOf(GoAiArchiveEntity(
                admitGoAiCheckpoint(saved).recordId, GameType.GO.code, 6, image(saved), 2, null, 17)))
            val wires = active + archive + GameBackupRecord(failing.descriptor.kind, listOf(GameType.TIGER_AND_GOAT.code.toString()))
            val bytes = LocalDataBackupCodec.encode(LocalDataSnapshot(1, gameExtensions = wires), registry)
            try { repository(target, registry).restore(bytes); fail("Late write must roll back") }
            catch (error: IOException) { assertEquals("test failure after raw AI writes", error.message) }
            assertTrue(reached)
            assertEquals(settings, (RoomAppSettingsRepository(target.appSettingsDao()).load() as LoadAppSettingsResult.Loaded).settings)
            assertTrue(target.goAiCheckpointDao().listActive().isEmpty())
            assertTrue(target.goAiCheckpointDao().listArchives().isEmpty())
            for (scope in GoLegacyImportScopes.ALL) assertNull(target.localDataMigrationDao().find(scope))
        }
    }

    @Test fun aValidOuterGaiChecksumCannotSmuggleBadNativeHistoryIntoReplacement() = runBlocking {
        withTwoDatabases { _, target ->
            val settings = AppSettings.DEFAULT.copy(updatedAtEpochMillis = 92)
            RoomAppSettingsRepository(target.appSettingsDao()).save(settings)
            val saved = match("bad-native")
            val badNative = saved.copyBytes().also { it[0] = 0 }
            val malformed = GoAiSavedMatch(saved.matchId, saved.config, badNative, saved.undoUsed, saved.hintUsed)
            val pair = createGoAiBackupAdapters()
            val registry = registry(ai = pair)
            val wires = (pair[0] as GoAiSessionBackupAdapter).encodeRows(listOf(
                GoAiSessionEntity(GameType.GO.code, 6, image(malformed), 2, null, 31)))
            val bytes = LocalDataBackupCodec.encode(LocalDataSnapshot(1, gameExtensions = wires), registry)
            rejects { repository(target, registry).restore(bytes) }
            assertEquals(settings, (RoomAppSettingsRepository(target.appSettingsDao()).load() as LoadAppSettingsResult.Loaded).settings)
            assertTrue(target.goAiCheckpointDao().listActive().isEmpty())
            for (scope in GoLegacyImportScopes.ALL) assertNull(target.localDataMigrationDao().find(scope))
        }
    }

    @Test fun lateUnresolvedSqlParentFailsInsideTheTransactionAndTheConnectionRemainsUsable() = runBlocking {
        withTwoDatabases { _, target ->
            val settings = AppSettings.DEFAULT.copy(updatedAtEpochMillis = 93)
            RoomAppSettingsRepository(target.appSettingsDao()).save(settings)
            val orphan = GoAiArchiveEntity("a".repeat(64), GameType.GO.code, 6,
                image(match("injected-orphan", terminal = true)), 3, "f".repeat(64), 31)
            val injector = object : GameBackupAdapter {
                override val descriptor = GameBackupDescriptor("TEST_AI_SQL_ORPHAN", 5,
                    BackupStorageSlot(BackupStorageTable.ACTIVE_GAME, GameType.TIGER_AND_GOAT), 1, 15, 100)
                override fun validate(record: GameBackupRecord, containerVersion: Int) {
                    require(record.fields == listOf(GameType.TIGER_AND_GOAT.code.toString()))
                }
                override suspend fun read(database: MocsDatabase) = emptyList<GameBackupRecord>()
                override fun prepareRestore(record: GameBackupRecord) = object : PreparedGameBackup {
                    override val storageSlot = descriptor.storageSlot
                    override suspend fun write(database: MocsDatabase) {
                        // Deliberately bypass the game guard to test the final SQL safety net.
                        database.goAiCheckpointDao().insertArchive(orphan)
                    }
                }
            }
            val registry = registry(extra = listOf(injector))
            val bytes = LocalDataBackupCodec.encode(LocalDataSnapshot(1, gameExtensions = listOf(
                GameBackupRecord(injector.descriptor.kind, listOf(GameType.TIGER_AND_GOAT.code.toString())))), registry)
            try { repository(target, registry).restore(bytes); fail("Unresolved parent must roll back") }
            catch (error: android.database.sqlite.SQLiteConstraintException) {
                assertEquals("Unresolved Go AI archive parent before commit", error.message)
            }
            assertTrue(target.goAiCheckpointDao().listArchives().isEmpty())
            for (scope in GoLegacyImportScopes.ALL) assertNull(target.localDataMigrationDao().find(scope))
            assertEquals(settings, (RoomAppSettingsRepository(target.appSettingsDao()).load() as LoadAppSettingsResult.Loaded).settings)
            // A subsequent real transaction must succeed, not inherit a failed COMMIT's rows.
            GoAiCheckpointRepository(RoomGoAiCheckpointStore(target)).save(match("after-rejection"))
            assertNotNull(target.goAiCheckpointDao().findActive(GameType.GO.code))
            assertFalse(target.goAiCheckpointDao().hasMissingArchiveParent())
        }
    }

    private fun registry(calls: AtomicInteger? = null, extra: List<GameBackupAdapter> = emptyList(),
        ai: List<GameBackupAdapter> = createGoAiBackupAdapters { saved -> calls?.incrementAndGet(); admitGoAiCheckpoint(saved) }) =
        GameBackupRegistry(listOf(GoLocalSessionBackupAdapter(), GoLocalRecordBackupAdapter(), GoProgressBackupAdapter()) + ai + extra)

    private fun repository(database: MocsDatabase, registry: GameBackupRegistry) = RoomLocalDataBackupRepository(database,
        ChineseChessEndgamePackParser.loadBundled(InstrumentationRegistry.getInstrumentation().targetContext.assets), { false }, registry)

    private fun image(saved: GoAiSavedMatch) = GoAiMatchFileCodec.encode(saved)

    private fun match(id: String, terminal: Boolean = false, series: Boolean = false): GoAiSavedMatch = NativeGoEngine(9).use { engine ->
        assertEquals(ActionResult.Accepted, engine.apply(GoAction.Place(BoardPosition(0, 0))))
        if (terminal) {
            repeat(2) { assertEquals(ActionResult.Accepted, engine.apply(GoAction.Pass)) }
            val revision = engine.scoringState().proposalRevision
            assertEquals(ActionResult.Accepted, engine.apply(GoAction.ConfirmScore(GoPlayer.BLACK, revision)))
            assertEquals(ActionResult.Accepted, engine.apply(GoAction.ConfirmScore(GoPlayer.WHITE, revision)))
        }
        GoAiSavedMatch(id, GoAiMatchConfig(9, humanPlayer = if (series) null else GoPlayer.BLACK), engine.serialize(),
            undoUsed = 1, hintUsed = 1, autoContinueEnabled = series, autoContinueMaxGames = if (series) 3 else 1,
            autoContinueGameNumber = if (series) 2 else 1)
    }

    private fun controls(saved: GoAiSavedMatch) = GoAiSavedMatch(saved.matchId, saved.config, saved.copyBytes(),
        saved.undoUsed, saved.hintUsed, true, saved.rememberedHuman, saved.automaticUsed, saved.copyOpeningBytes())

    private suspend fun rejects(block: suspend () -> Any?) {
        try { block(); fail("Invalid AI backup accepted") } catch (_: IllegalArgumentException) { }
    }

    private suspend fun withTwoDatabases(block: suspend (MocsDatabase, MocsDatabase) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val source = Room.inMemoryDatabaseBuilder(context, MocsDatabase::class.java).build()
        val target = Room.inMemoryDatabaseBuilder(context, MocsDatabase::class.java).build()
        try { block(source, target) } finally { target.close(); source.close() }
    }
}
