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
import com.masterofchessstrategy.data.GameRecord
import com.masterofchessstrategy.data.GameSessionSnapshot
import com.masterofchessstrategy.data.MatchOutcome
import com.masterofchessstrategy.data.MocsDatabase
import com.masterofchessstrategy.data.RoomCompletedGameSessionRepository
import com.masterofchessstrategy.data.StoredGameMode
import com.masterofchessstrategy.endgame.ChineseChessEndgamePackParser
import com.masterofchessstrategy.engine.ActionResult
import com.masterofchessstrategy.engine.BoardMove
import com.masterofchessstrategy.engine.BoardPosition
import com.masterofchessstrategy.engine.ChineseChessFenCodec
import com.masterofchessstrategy.engine.ChineseChessRuleRevision
import com.masterofchessstrategy.engine.Difficulty
import com.masterofchessstrategy.engine.GameResult
import com.masterofchessstrategy.engine.GameType
import com.masterofchessstrategy.engine.NativeChineseChessEngine
import com.masterofchessstrategy.engine.RestoreResult
import com.masterofchessstrategy.game.ChineseChessGameViewModel
import java.util.UUID
import java.util.zip.CRC32
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Real Room, JNI and ViewModel; never opens the user's application database. */
@RunWith(AndroidJUnit4::class)
class XiangqiRuleRevisionPersistenceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test
    fun inProgressProfilesSurviveReopenAndSettleWithoutChangingTheirJudge() = runBlocking {
        for (profile in Profile.entries) {
            val dbName = "xiangqi-rule-progress-${UUID.randomUUID()}.db"
            var database = open(dbName)
            var managed: ManagedGame? = null
            try {
                val bytes = cycleState(profile, rounds = 1)
                val snapshot = snapshot("progress-${profile.name}", bytes, 4)
                repository(database).save(snapshot)
                database.close()
                database = open(dbName)
                managed = createGame(database)
                val game = managed.game
                awaitReady(game)
                assertEquals(GameResult.ONGOING, game.uiState.result)
                assertEquals(profile.revision, game.uiState.ruleRevision)
                assertArrayEquals(bytes, database.activeGameDao().find(GameType.CHINESE_CHESS.code)!!.engineState)

                for (move in CYCLE) {
                    instrumentation.runOnMainSync {
                        game.onSquareTap(move.from)
                        game.onSquareTap(move.to)
                    }
                    awaitReady(game)
                }
                assertEquals(profile.result, game.uiState.result)
                val terminal = database.activeGameDao().find(GameType.CHINESE_CHESS.code)!!
                val record = database.gameRecordDao().listAll().single()
                assertEquals(8, record.moveCount)
                assertEquals(profile.resultCode, record.resultCode)
                assertArrayEquals(cycleState(profile, rounds = 2), terminal.engineState)
                assertArrayEquals(terminal.engineState, record.engineState)
                assertTrue(database.matchOutcomeDao().listAll().isEmpty())

                managed.close()
                managed = null
                database.close()
                database = open(dbName)
                managed = createGame(database)
                awaitReady(managed.game)
                assertEquals(profile.result, managed.game.uiState.result)
                assertEquals(profile.revision, managed.game.uiState.ruleRevision)
                assertEquals(record, database.gameRecordDao().listAll().single())
                assertArrayEquals(terminal.engineState, database.activeGameDao().find(GameType.CHINESE_CHESS.code)!!.engineState)

                instrumentation.runOnMainSync { managed.game.restartWithAiFirst(false) }
                awaitReady(managed.game)
                assertEquals(GameResult.ONGOING, managed.game.uiState.result)
                assertEquals(ChineseChessRuleRevision.CONTINUOUS_CHECKING_MATE, managed.game.uiState.ruleRevision)
                assertEquals(record, database.gameRecordDao().listAll().single())
                assertTrue(database.matchOutcomeDao().listAll().isEmpty())
            } finally {
                managed?.close()
                database.close()
                context.deleteDatabase(dbName)
            }
        }
    }

    @Test
    fun completedRankedProfilesKeepTheirRecordAndLedgerOnRestoreAndRejectConflict() = runBlocking {
        for (profile in Profile.entries) {
            val dbName = "xiangqi-rule-ledger-${UUID.randomUUID()}.db"
            var database = open(dbName)
            var managed: ManagedGame? = null
            try {
                val bytes = cycleState(profile, rounds = 2)
                val saved = snapshot("ranked-${profile.name}", bytes, 8).copy(
                    mode = StoredGameMode.HUMAN_VS_AI,
                    difficulty = Difficulty.EASY,
                    resultOverride = profile.result,
                )
                val record = GameRecord(
                    recordId = saved.sessionId,
                    gameType = saved.gameType,
                    mode = saved.mode,
                    difficulty = saved.difficulty,
                    result = profile.result,
                    engineState = bytes.copyOf(),
                    moveCount = 8,
                    completedAtEpochMillis = 1_000,
                )
                val outcome = MatchOutcome(
                    matchId = saved.sessionId,
                    gameType = saved.gameType,
                    mode = saved.mode,
                    difficulty = Difficulty.EASY,
                    playerIndex = 0,
                    result = profile.result,
                    settledAtEpochMillis = 1_000,
                )
                val completed = CompletedGameSession(saved, record, outcome)
                repository(database).complete(completed)
                val originalRecord = database.gameRecordDao().listAll().single()
                val originalLedger = database.matchOutcomeDao().listAll().single()
                database.close()
                database = open(dbName)
                managed = createGame(database, saved.mode, saved.difficulty)
                awaitReady(managed.game)
                assertEquals(profile.result, managed.game.uiState.result)
                assertEquals(profile.revision, managed.game.uiState.ruleRevision)
                assertFalse(managed.game.uiState.hasUncommittedResult)
                assertEquals(originalRecord, database.gameRecordDao().listAll().single())
                assertEquals(originalLedger, database.matchOutcomeDao().listAll().single())
                val originalSession = database.activeGameDao().find(GameType.CHINESE_CHESS.code)!!
                assertArrayEquals(bytes, originalSession.engineState)

                val conflictingResult = if (profile.result == GameResult.DRAW) GameResult.SECOND_PLAYER_WIN else GameResult.DRAW
                val conflict = CompletedGameSession(
                    saved.copy(resultOverride = conflictingResult),
                    record.copy(result = conflictingResult),
                    outcome.copy(result = conflictingResult),
                )
                var rejected = false
                try {
                    repository(database).complete(conflict)
                } catch (_: IllegalArgumentException) {
                    rejected = true
                }
                assertTrue("Conflicting completion must be rejected", rejected)
                assertEquals(originalSession, database.activeGameDao().find(GameType.CHINESE_CHESS.code))
                assertEquals(originalRecord, database.gameRecordDao().listAll().single())
                assertEquals(originalLedger, database.matchOutcomeDao().listAll().single())
            } finally {
                managed?.close()
                database.close()
                context.deleteDatabase(dbName)
            }
        }
    }

    private fun open(name: String) = Room.databaseBuilder(context, MocsDatabase::class.java, name).build()

    private fun repository(database: MocsDatabase) = RoomCompletedGameSessionRepository(
        database, ChineseChessEndgamePackParser.loadBundled(context.assets),
    )

    private fun createGame(
        database: MocsDatabase,
        mode: StoredGameMode = StoredGameMode.LOCAL_TWO_PLAYER,
        difficulty: Difficulty? = null,
    ): ManagedGame {
        val store = ViewModelStore()
        lateinit var game: ChineseChessGameViewModel
        instrumentation.runOnMainSync {
            val factory = viewModelFactory {
                initializer {
                    ChineseChessGameViewModel(
                        sessionRepository = repository(database),
                        mode = mode,
                        difficulty = difficulty,
                        clockTickIntervalMillis = null,
                        engineFactory = { NativeChineseChessEngine() },
                    )
                }
            }
            game = ViewModelProvider(store, factory)[ChineseChessGameViewModel::class.java]
        }
        return ManagedGame(game, store)
    }

    private inner class ManagedGame(val game: ChineseChessGameViewModel, private val store: ViewModelStore) {
        fun close() = instrumentation.runOnMainSync { store.clear() }
    }

    private fun awaitReady(game: ChineseChessGameViewModel) {
        val deadline = SystemClock.uptimeMillis() + 15_000
        while (true) {
            var ready = false
            instrumentation.runOnMainSync { ready = !game.uiState.isRestoring && !game.uiState.isPersisting }
            if (ready) return
            check(SystemClock.uptimeMillis() < deadline) { "Rule revision checkpoint timed out" }
            SystemClock.sleep(20)
        }
    }

    private fun snapshot(id: String, bytes: ByteArray, moves: Int) = GameSessionSnapshot(
        gameType = GameType.CHINESE_CHESS,
        mode = StoredGameMode.LOCAL_TWO_PLAYER,
        difficulty = null,
        engineState = bytes.copyOf(),
        updatedAtEpochMillis = 1_000,
        sessionId = id,
        acceptedMoveCount = moves,
    )

    private fun cycleState(profile: Profile, rounds: Int): ByteArray = NativeChineseChessEngine().use { engine ->
        assertEquals(RestoreResult.Restored, engine.restore(ChineseChessFenCodec.parse(CYCLE_FEN).toEngineState()))
        if (profile != Profile.CURRENT) {
            val current = engine.serialize()
            val old = if (profile == Profile.V3) current.copyOf(current.size - 1).also { it[4] = 3 }
                else current.copyOf().also { it[it.size - 5] = 0 }
            val crc = CRC32().apply { update(old, 0, old.size - 4) }.value
            repeat(4) { byte -> old[old.size - 4 + byte] = (crc ushr (8 * byte)).toByte() }
            assertEquals(RestoreResult.Restored, engine.restore(old))
        }
        repeat(rounds) { CYCLE.forEach { assertEquals(ActionResult.Accepted, engine.apply(it)) } }
        assertEquals(if (rounds == 1) GameResult.ONGOING else profile.result, engine.gameResult())
        engine.serialize()
    }

    private enum class Profile(val revision: ChineseChessRuleRevision, val result: GameResult, val resultCode: Int) {
        V3(ChineseChessRuleRevision.LEGACY_COMPATIBILITY, GameResult.DRAW, 3),
        V4_LEGACY(ChineseChessRuleRevision.LEGACY_COMPATIBILITY, GameResult.DRAW, 3),
        CURRENT(ChineseChessRuleRevision.CONTINUOUS_CHECKING_MATE, GameResult.SECOND_PLAYER_WIN, 2),
    }

    private companion object {
        const val CYCLE_FEN = "2b6/4k4/3a5/2PC2C2/3NPN3/9/9/5K3/9/9 w - - 0 1"
        val CYCLE = listOf(
            BoardMove(BoardPosition(3, 3), BoardPosition(4, 3)),
            BoardMove(BoardPosition(4, 1), BoardPosition(3, 1)),
            BoardMove(BoardPosition(4, 3), BoardPosition(3, 3)),
            BoardMove(BoardPosition(3, 1), BoardPosition(4, 1)),
        )
    }
}
