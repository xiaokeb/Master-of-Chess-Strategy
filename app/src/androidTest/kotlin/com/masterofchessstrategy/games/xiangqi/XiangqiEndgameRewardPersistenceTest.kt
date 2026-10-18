package com.masterofchessstrategy.games.xiangqi

import android.os.SystemClock
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.masterofchessstrategy.data.CompletedGameSession
import com.masterofchessstrategy.data.EndgameProgress
import com.masterofchessstrategy.data.GameRecord
import com.masterofchessstrategy.data.GameSessionSnapshot
import com.masterofchessstrategy.data.LoadEndgameProgressResult
import com.masterofchessstrategy.data.MocsDatabase
import com.masterofchessstrategy.data.RoomCompletedGameSessionRepository
import com.masterofchessstrategy.data.RoomEndgameProgressRepository
import com.masterofchessstrategy.data.StoredGameMode
import com.masterofchessstrategy.endgame.ChineseChessEndgameLevel
import com.masterofchessstrategy.endgame.ChineseChessEndgamePackParser
import com.masterofchessstrategy.endgame.ChineseChessEndgameViewModel
import com.masterofchessstrategy.engine.ActionResult
import com.masterofchessstrategy.engine.Difficulty
import com.masterofchessstrategy.engine.GameResult
import com.masterofchessstrategy.engine.GameType
import com.masterofchessstrategy.engine.NativeChineseChessEngine
import com.masterofchessstrategy.engine.RestoreResult
import java.io.Closeable
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Native terminal states and real Room transactions; all storage is test-owned. */
@RunWith(AndroidJUnit4::class)
class XiangqiEndgameRewardPersistenceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val pack by lazy { ChineseChessEndgamePackParser.loadBundled(context.assets) }

    @Test
    fun addedLevelsAwardOnceAndKeepOriginalRecordsAfterDatabaseReopen() = runBlocking {
        TestDatabase().use { store ->
            assertEquals(35, pack.levels.size)
            assertEquals(19, NEW_IDS.size)
            val completions = NEW_IDS.map { id -> terminal(level(id)) }
            completions.forEach { completion ->
                val reward = repository(store.database).complete(completion).endgame!!
                val level = pack.levels.single { pack.sessionVariantId(it.id) == completion.snapshot.sessionVariantId }
                assertTrue(reward.firstCompletion)
                assertEquals(level.starReward, reward.awardedStars)
                assertEquals(level.scoreReward, reward.awardedScore)
                assertArrayEquals(completion.record.engineState,
                    store.database.gameRecordDao().find(completion.record.recordId)!!.engineState)
            }
            val originalRecords = store.database.gameRecordDao().listAll()
            val originalProgress = progress(store.database)
            assertEquals(NEW_IDS.toSet(), originalProgress.completedById.keys)
            assertEquals(NEW_IDS.sumOf { level(it).starReward }, originalProgress.totalStars)
            assertEquals(NEW_IDS.sumOf { level(it).scoreReward }, originalProgress.totalScore)
            store.reopen()

            // A later retry must not rewrite the immutable completion timestamp.
            completions.forEach { completion ->
                val retried = completion.copy(record = completion.record.copy(completedAtEpochMillis = 9_000))
                val reward = repository(store.database).complete(retried).endgame!!
                assertFalse(reward.firstCompletion)
                assertFalse(reward.bestMovesImproved)
                assertEquals(0, reward.awardedStars)
                assertEquals(0, reward.awardedScore)
            }
            assertEquals(originalRecords, store.database.gameRecordDao().listAll())
            assertEquals(originalProgress, progress(store.database))
            assertTrue(store.database.matchOutcomeDao().listAll().isEmpty())
            val last = completions.last()
            assertArrayEquals(last.snapshot.engineState,
                store.database.activeGameDao().find(GameType.CHINESE_CHESS.code)!!.engineState)
        }
    }

    @Test
    fun legacyRewardAndAddedLevelsFollowRealRoomCatalogUnlocks() = runBlocking {
        TestDatabase().use { store ->
            val coveredLevels = pack.levels.filter {
                it.difficulty in setOf(Difficulty.EASY, Difficulty.MEDIUM)
            }
            assertEquals(31, coveredLevels.size)
            assertEquals(19, NEW_IDS.size)
            val prerequisites = coveredLevels.filter { it.id !in NEW_IDS }
            assertEquals(12, prerequisites.size)
            prerequisites.forEach { repository(store.database).complete(terminal(it)) }
            val legacy = prerequisites.first()
            // Explicit compatibility fixture, not a claim that an old APK ran here.
            store.database.openHelper.writableDatabase.execSQL(
                "UPDATE endgame_progress SET contentVersion = ? WHERE levelId = ?",
                arrayOf<Any>(pack.version - 1, legacy.id),
            )
            val legacyRow = store.database.endgameProgressDao().listAll().single { it.levelId == legacy.id }
            store.reopen()
            var expectedCount = prerequisites.size
            createCatalog(store.database).use { catalog ->
                awaitCatalog(catalog.model, expectedCount)
                NEW_IDS.forEachIndexed { index, id ->
                    instrumentation.runOnMainSync {
                        assertTrue(id, catalog.model.uiState.entries.single { it.level.id == id }.isUnlocked)
                        NEW_IDS.drop(index + 1).filter { level(it).difficulty == level(id).difficulty }.forEach { later ->
                            assertFalse(later, catalog.model.uiState.entries.single { it.level.id == later }.isUnlocked)
                        }
                    }
                    val commit = repository(store.database).complete(terminal(level(id)))
                    assertTrue(commit.endgame!!.firstCompletion)
                    // Use the formal post-transaction refresh, not catalog.complete().
                    instrumentation.runOnMainSync { catalog.model.refreshAfterCommit(commit.endgame) }
                    awaitCatalog(catalog.model, ++expectedCount)
                }
            }
            val allProgress = progress(store.database)
            assertEquals(31, expectedCount)
            assertEquals(coveredLevels.size, expectedCount)
            assertEquals(expectedCount, allProgress.completedById.size)
            assertEquals(legacyRow, store.database.endgameProgressDao().listAll().single { it.levelId == legacy.id })
            store.reopen()
            createCatalog(store.database).use { catalog ->
                awaitCatalog(catalog.model, expectedCount)
                instrumentation.runOnMainSync {
                    assertEquals(allProgress, catalog.model.uiState.progress)
                    NEW_IDS.forEach { id ->
                        assertTrue(catalog.model.uiState.entries.single { it.level.id == id }.isCompleted)
                    }
                }
            }
        }
    }

    @Test
    fun rewardInsertFailureRollsBackRecordAndSessionThenRetryCommitsOnce() = runBlocking {
        TestDatabase().use { store ->
            val selected = level("xq-medium-012")
            val completed = terminal(selected)
            val initial = initialSnapshot(completed, selected)
            repository(store.database).save(initial)
            val original = store.database.activeGameDao().find(GameType.CHINESE_CHESS.code)!!
            store.database.openHelper.writableDatabase.execSQL(
                "CREATE TRIGGER reject_endgame_reward BEFORE INSERT ON endgame_progress " +
                    "BEGIN SELECT RAISE(ABORT, 'injected endgame reward failure'); END",
            )
            assertRejected { repository(store.database).complete(completed) }
            assertTrue(store.database.gameRecordDao().listAll().isEmpty())
            assertTrue(store.database.endgameProgressDao().listAll().isEmpty())
            assertTrue(store.database.matchOutcomeDao().listAll().isEmpty())
            assertEquals(original, store.database.activeGameDao().find(GameType.CHINESE_CHESS.code))
            store.database.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_endgame_reward")

            val reward = repository(store.database).complete(completed).endgame!!
            assertTrue(reward.firstCompletion)
            assertEquals(selected.starReward, reward.awardedStars)
            assertEquals(selected.scoreReward, reward.awardedScore)
            store.reopen()
            val repeated = repository(store.database).complete(completed).endgame!!
            assertFalse(repeated.firstCompletion)
            assertEquals(0, repeated.awardedStars)
            assertEquals(0, repeated.awardedScore)
            assertEquals(1, store.database.gameRecordDao().listAll().size)
            assertEquals(1, store.database.endgameProgressDao().listAll().size)
            assertArrayEquals(completed.snapshot.engineState,
                store.database.activeGameDao().find(GameType.CHINESE_CHESS.code)!!.engineState)
        }
    }

    @Test
    fun invalidDeclaredMovesAndUnknownLevelLeaveNoPartialCompletion() = runBlocking {
        TestDatabase().use { store ->
            val selected = level("xq-medium-013")
            val completed = terminal(selected)
            repository(store.database).save(initialSnapshot(completed, selected))
            val original = store.database.activeGameDao().find(GameType.CHINESE_CHESS.code)!!
            // Deliberately malformed declarations; these are never accepted games.
            for (moves in listOf(0, selected.maxPlayerMoves * 2 + 1)) {
                val invalid = completed.copy(
                    snapshot = completed.snapshot.copy(acceptedMoveCount = moves),
                    record = completed.record.copy(moveCount = moves),
                )
                assertRejected { repository(store.database).complete(invalid) }
            }
            assertRejected {
                repository(store.database).complete(completed.copy(
                    snapshot = completed.snapshot.copy(sessionVariantId = "unregistered@v${pack.version}"),
                ))
            }
            assertRejected {
                RoomEndgameProgressRepository(store.database.endgameProgressDao(), pack).complete(
                    selected.copy(title = "not registered"), 2, 1_000,
                )
            }
            assertTrue(store.database.gameRecordDao().listAll().isEmpty())
            assertTrue(store.database.endgameProgressDao().listAll().isEmpty())
            assertEquals(original, store.database.activeGameDao().find(GameType.CHINESE_CHESS.code))
        }
    }

    private fun level(id: String) = pack.levels.single { it.id == id }

    @Test
    fun knownLevelMismatchAndDeclaredNonNativeWinAreRejectedWithoutWrites() = runBlocking {
        TestDatabase().use { store ->
            val selected = level("xq-medium-012")
            val completed = terminal(selected)
            repository(store.database).save(initialSnapshot(completed, selected))
            val original = store.database.activeGameDao().find(GameType.CHINESE_CHESS.code)!!
            val ongoing = initialSnapshot(completed, selected).engineState
            val invalid = listOf(
                completed.copy(snapshot = completed.snapshot.copy(
                    sessionVariantId = pack.sessionVariantId("xq-medium-013"),
                )),
                completed.copy(
                    snapshot = completed.snapshot.copy(difficulty = Difficulty.EASY),
                    record = completed.record.copy(difficulty = Difficulty.EASY),
                ),
                completed.copy(
                    snapshot = completed.snapshot.copy(engineState = ongoing),
                    record = completed.record.copy(engineState = ongoing.copyOf()),
                ),
                completed.copy(
                    snapshot = completed.snapshot.copy(acceptedMoveCount = 1),
                    record = completed.record.copy(moveCount = 1),
                ),
            )
            invalid.forEach { assertRejected { repository(store.database).complete(it) } }
            assertTrue(store.database.gameRecordDao().listAll().isEmpty())
            assertTrue(store.database.endgameProgressDao().listAll().isEmpty())
            assertEquals(original, store.database.activeGameDao().find(GameType.CHINESE_CHESS.code))
        }
    }

    private fun terminal(level: ChineseChessEndgameLevel): CompletedGameSession {
        val bytes = NativeChineseChessEngine().use { engine ->
            assertEquals(RestoreResult.Restored, engine.restore(level.initialEngineState))
            level.principalVariation.forEach { assertEquals(ActionResult.Accepted, engine.apply(it)) }
            assertEquals(level.id, GameResult.FIRST_PLAYER_WIN, engine.gameResult())
            engine.serialize()
        }
        val snapshot = GameSessionSnapshot(
            gameType = GameType.CHINESE_CHESS, mode = StoredGameMode.ENDGAME,
            difficulty = level.difficulty, engineState = bytes, updatedAtEpochMillis = 1_000,
            sessionId = UUID.randomUUID().toString(), acceptedMoveCount = level.principalVariation.size,
            resultOverride = GameResult.FIRST_PLAYER_WIN, sessionVariantId = pack.sessionVariantId(level.id),
        )
        val record = GameRecord(
            recordId = snapshot.sessionId, gameType = snapshot.gameType, mode = snapshot.mode,
            difficulty = snapshot.difficulty, result = GameResult.FIRST_PLAYER_WIN,
            engineState = bytes.copyOf(), moveCount = snapshot.acceptedMoveCount,
            isEndgame = true, completedAtEpochMillis = 1_000,
        )
        return CompletedGameSession(snapshot, record, null)
    }

    private fun initialSnapshot(completed: CompletedGameSession, level: ChineseChessEndgameLevel) =
        NativeChineseChessEngine().use { engine ->
            assertEquals(RestoreResult.Restored, engine.restore(level.initialEngineState))
            assertEquals(GameResult.ONGOING, engine.gameResult())
            completed.snapshot.copy(engineState = engine.serialize(), acceptedMoveCount = 0, resultOverride = null)
        }

    private fun repository(database: MocsDatabase) = RoomCompletedGameSessionRepository(database, pack)

    private suspend fun progress(database: MocsDatabase): EndgameProgress {
        val result = RoomEndgameProgressRepository(database.endgameProgressDao(), pack).load()
        assertTrue(result is LoadEndgameProgressResult.Loaded)
        return (result as LoadEndgameProgressResult.Loaded).progress
    }

    private suspend fun assertRejected(action: suspend () -> Unit) {
        var rejected = false
        try { action() } catch (_: RuntimeException) { rejected = true }
        assertTrue("Malformed or interrupted completion must be rejected", rejected)
    }

    private inner class TestDatabase : Closeable {
        private val name = "xiangqi-endgame-reward-${UUID.randomUUID()}.db"
        var database = open()
            private set
        private fun open() = Room.databaseBuilder(context, MocsDatabase::class.java, name).build()
        fun reopen() { database.close(); database = open() }
        override fun close() { database.close(); context.deleteDatabase(name) }
    }

    private fun createCatalog(database: MocsDatabase): ManagedCatalog {
        val store = ViewModelStore()
        lateinit var model: ChineseChessEndgameViewModel
        instrumentation.runOnMainSync {
            val factory = viewModelFactory { initializer {
                ChineseChessEndgameViewModel(pack, RoomEndgameProgressRepository(database.endgameProgressDao(), pack))
            } }
            model = ViewModelProvider(store, factory)[ChineseChessEndgameViewModel::class.java]
        }
        return ManagedCatalog(model, store)
    }

    private inner class ManagedCatalog(val model: ChineseChessEndgameViewModel, private val store: ViewModelStore) : Closeable {
        override fun close() = instrumentation.runOnMainSync { store.clear() }
    }

    private fun awaitCatalog(model: ChineseChessEndgameViewModel, expectedCount: Int) {
        val deadline = SystemClock.uptimeMillis() + 15_000
        while (true) {
            var ready = false
            instrumentation.runOnMainSync {
                ready = !model.uiState.isLoading && model.uiState.completedCount == expectedCount
            }
            if (ready) return
            check(SystemClock.uptimeMillis() < deadline) { "Endgame Room catalog did not load expected progress" }
            SystemClock.sleep(20)
        }
    }

    private companion object {
        // All nineteen additions since the published sixteen-level seed pack.
        val NEW_IDS = (11..18).map { "xq-easy-${it.toString().padStart(3, '0')}" } +
            (3..13).map { "xq-medium-${it.toString().padStart(3, '0')}" }
    }
}
