package com.masterofchessstrategy.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.masterofchessstrategy.endgame.ChineseChessEndgamePack
import com.masterofchessstrategy.endgame.ChineseChessEndgamePackParser
import com.masterofchessstrategy.engine.ActionResult
import com.masterofchessstrategy.engine.Difficulty
import com.masterofchessstrategy.engine.GameResult
import com.masterofchessstrategy.engine.GameType
import com.masterofchessstrategy.engine.NativeChineseChessEngine
import com.masterofchessstrategy.engine.RestoreResult
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CompletedGameSessionRepositoryInstrumentedTest {
    private lateinit var database: MocsDatabase
    private lateinit var pack: ChineseChessEndgamePack
    private lateinit var repository: RoomCompletedGameSessionRepository
    @Before fun prepare() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        database = Room.inMemoryDatabaseBuilder(context, MocsDatabase::class.java).build()
        pack = ChineseChessEndgamePackParser.loadBundled(context.assets)
        repository = RoomCompletedGameSessionRepository(database, pack) { true }
    }
    @After fun cleanup() = database.close()

    @Test fun rankedCommitIsIdempotentAndPreservesManualFavoriteAndOriginalTimes() = runBlocking {
        val game = ranked()
        repository.complete(game)
        assertEquals(game.snapshot.sessionId, database.activeGameDao().find(GameType.CHINESE_CHESS.code)?.sessionId)
        assertEquals(1, database.gameRecordDao().listAll().size)
        assertEquals(1, database.matchOutcomeDao().listAll().size)
        database.gameRecordDao().setFavorite(game.record.recordId, false)
        repository.complete(game.copy(record = game.record.copy(completedAtEpochMillis = 9_000L),
            outcome = game.outcome!!.copy(settledAtEpochMillis = 9_000L)))
        assertFalse(database.gameRecordDao().listAll().single().isFavorite)
        assertEquals(1_000L, database.gameRecordDao().listAll().single().completedAtEpochMillis)
        assertEquals(1_000L, database.matchOutcomeDao().listAll().single().settledAtEpochMillis)
    }

    @Test fun finalSessionWriteFailureRollsBackRecordAndRankedOutcomeTogether() = runBlocking {
        val game = ranked()
        repository.save(game.snapshot.copy(resultOverride = null))
        val before = database.activeGameDao().find(GameType.CHINESE_CHESS.code)
        failFinalWrite()
        try {
            repository.complete(game)
            fail("Transaction must fail")
        } catch (_: android.database.SQLException) {
            assertEquals(before, database.activeGameDao().find(GameType.CHINESE_CHESS.code))
            assertTrue(database.gameRecordDao().listAll().isEmpty())
            assertTrue(database.matchOutcomeDao().listAll().isEmpty())
        } finally { allowFinalWrite() }
        repository.complete(game)
        assertEquals(2, database.activeGameDao().find(GameType.CHINESE_CHESS.code)?.resultOverrideCode)
        assertEquals(1, database.gameRecordDao().listAll().size)
        assertEquals(1, database.matchOutcomeDao().listAll().size)
    }

    @Test fun finalSessionWriteFailureAlsoRollsBackEndgameRewards() = runBlocking {
        val level = pack.levels.first()
        val state = NativeChineseChessEngine().use { engine ->
            assertEquals(RestoreResult.Restored, engine.restore(level.initialEngineState))
            level.principalVariation.forEach { assertEquals(ActionResult.Accepted, engine.apply(it)) }
            assertEquals(GameResult.FIRST_PLAYER_WIN, engine.gameResult())
            engine.serialize()
        }
        val game = completed(StoredGameMode.ENDGAME, state, GameResult.FIRST_PLAYER_WIN,
            level.principalVariation.size, pack.sessionVariantId(level.id), level.difficulty)
        failFinalWrite()
        try {
            repository.complete(game)
            fail("Transaction must fail")
        } catch (_: android.database.SQLException) {
            assertTrue(database.gameRecordDao().listAll().isEmpty())
            assertTrue(database.endgameProgressDao().listAll().isEmpty())
            assertNull(database.activeGameDao().find(GameType.CHINESE_CHESS.code))
        } finally { allowFinalWrite() }
        assertTrue(requireNotNull(repository.complete(game).endgame).firstCompletion)
        assertFalse(requireNotNull(repository.complete(game).endgame).firstCompletion)
        assertEquals(1, database.endgameProgressDao().listAll().size)
        assertEquals(level.starReward, database.endgameProgressDao().listAll().single().starsAwarded)
        assertTrue(database.matchOutcomeDao().listAll().isEmpty())
    }

    @Test fun conflictingTerminalResultCannotReplaceAnAlreadySettledGame() = runBlocking {
        val game = ranked()
        repository.complete(game)
        val before = database.activeGameDao().find(GameType.CHINESE_CHESS.code)
        val conflict = game.copy(snapshot = game.snapshot.copy(resultOverride = GameResult.FIRST_PLAYER_WIN),
            record = game.record.copy(result = GameResult.FIRST_PLAYER_WIN),
            outcome = game.outcome!!.copy(result = GameResult.FIRST_PLAYER_WIN))
        try { repository.complete(conflict); fail("Conflicting result must be rejected") }
        catch (_: IllegalArgumentException) { }
        assertEquals(before, database.activeGameDao().find(GameType.CHINESE_CHESS.code))
        assertEquals(2, database.gameRecordDao().listAll().single().resultCode)
        assertEquals(2, database.matchOutcomeDao().listAll().single().resultCode)
    }

    private fun failFinalWrite() = database.openHelper.writableDatabase.execSQL(
        "CREATE TRIGGER reject_terminal_save BEFORE INSERT ON active_games BEGIN SELECT RAISE(ABORT, 'injected final-write failure'); END",
    )
    private fun allowFinalWrite() = database.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_terminal_save")
    private fun ranked() = NativeChineseChessEngine().use {
        completed(StoredGameMode.HUMAN_VS_AI, it.serialize(), GameResult.SECOND_PLAYER_WIN)
    }
    private fun completed(mode: StoredGameMode, state: ByteArray, result: GameResult,
        moves: Int = 0, variant: String = "", difficulty: Difficulty = Difficulty.EASY): CompletedGameSession {
        val snapshot = GameSessionSnapshot(GameType.CHINESE_CHESS, mode, difficulty, state, 1_000L,
            sessionId = "atomic-game", acceptedMoveCount = moves, resultOverride = result, sessionVariantId = variant)
        val record = GameRecord("atomic-game", GameType.CHINESE_CHESS, mode, difficulty, result, state, moves,
            isEndgame = mode == StoredGameMode.ENDGAME, completedAtEpochMillis = 1_000L)
        val outcome = if (mode == StoredGameMode.HUMAN_VS_AI) MatchOutcome("atomic-game", GameType.CHINESE_CHESS,
            mode, difficulty, 0, result, 1_000L) else null
        return CompletedGameSession(snapshot, record, outcome)
    }
}
