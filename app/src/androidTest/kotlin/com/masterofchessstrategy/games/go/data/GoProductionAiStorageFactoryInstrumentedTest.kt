package com.masterofchessstrategy.games.go.data

import android.content.Context
import android.content.ContextWrapper
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.masterofchessstrategy.data.*
import com.masterofchessstrategy.endgame.ChineseChessEndgamePackParser
import com.masterofchessstrategy.engine.*
import com.masterofchessstrategy.games.go.*
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Exercises the actual production factory, with a UUID database and isolated legacy paths. */
class GoProductionAiStorageFactoryInstrumentedTest {
    @Test fun roomAiAndCompleteBackupSwitchTogetherAndFreshFactoryCapturesRestoredEpoch() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val id = UUID.randomUUID().toString()
        val directory = File(context.cacheDir, "go-factory-cutover-$id")
        check(directory.mkdir())
        val databaseName = "go-factory-cutover-$id.db"
        val database = Room.databaseBuilder(context, MocsDatabase::class.java, databaseName).build()
        val isolated = object : ContextWrapper(context.applicationContext) {
            override fun getApplicationContext(): Context = this
            override fun getDataDir(): File = directory
            override fun getFileStreamPath(name: String): File {
                require(name.matches(Regex("[a-z0-9-]+\\.bin")))
                return File(directory, name)
            }
        }
        try {
            val terminal = NativeGoEngine(9).use { engine ->
                assertEquals(ActionResult.Accepted, engine.apply(GoAction.Place(BoardPosition(0, 0))))
                repeat(2) { assertEquals(ActionResult.Accepted, engine.apply(GoAction.Pass)) }
                val revision = engine.scoringState().proposalRevision
                assertEquals(ActionResult.Accepted, engine.apply(GoAction.ConfirmScore(GoPlayer.BLACK, revision)))
                assertEquals(ActionResult.Accepted, engine.apply(GoAction.ConfirmScore(GoPlayer.WHITE, revision)))
                GoAiSavedMatch("production-factory-terminal", GoAiMatchConfig(9), engine.serialize())
            }
            val file = isolated.getFileStreamPath("go-ai-match-v1.bin")
            val original = GoAiMatchFileCodec.encode(terminal)
            file.writeBytes(original)
            val storage = GoLocalStorage(database, isolated)
            val stale = storage.aiRepository()
            assertEquals(terminal.matchId, stale.load()!!.matchId)
            assertArrayEquals(original, database.goAiCheckpointDao().findActive(GameType.GO.code)!!.checkpoint)
            assertEquals(1, database.goAiCheckpointDao().listArchives().size)
            lateinit var registry: GameBackupRegistry
            val backup = storage.backupRepository { adapters ->
                registry = GameBackupRegistry(adapters)
                assertEquals(6, adapters.size)
                RoomLocalDataBackupRepository(database,
                    ChineseChessEndgamePackParser.loadBundled(context.assets), { true }, registry)
            }
            val exported = LocalDataBackupCodec.decode(backup.export(100), registry)
            assertTrue(exported.gameExtensions.any { it.kind == "GO_AI_SESSION" })
            assertTrue(exported.gameExtensions.any { it.kind == "GO_AI_ARCHIVE" })
            backup.restore(LocalDataBackupCodec.encode(LocalDataSnapshot(200), registry))
            try { stale.load(); fail("Old factory facade survived replacement") } catch (_: IOException) { }
            val fresh = storage.aiRepository()
            assertNull(fresh.load())
            val next = NativeGoEngine(9).use { engine ->
                GoAiSavedMatch("production-factory-new", GoAiMatchConfig(9), engine.serialize())
            }
            fresh.save(next)
            assertEquals(next.matchId, fresh.load()!!.matchId)
            assertArrayEquals(GoAiMatchFileCodec.encode(next),
                database.goAiCheckpointDao().findActive(GameType.GO.code)!!.checkpoint)
            assertTrue(database.goAiCheckpointDao().listArchives().isEmpty())
            assertArrayEquals(original, file.readBytes())
            GoLegacyImportScopes.ALL.forEach { assertNotNull(database.localDataMigrationDao().find(it)) }
            assertTrue(database.gameRecordDao().listAll().isEmpty())
            assertTrue(database.matchOutcomeDao().listAll().isEmpty())
        } finally {
            database.close()
            check(context.deleteDatabase(databaseName))
            directory.listFiles()!!.forEach { file ->
                check(file.name == "go-ai-match-v1.bin" && file.isFile && file.delete())
            }
            check(directory.delete())
        }
    }
}
