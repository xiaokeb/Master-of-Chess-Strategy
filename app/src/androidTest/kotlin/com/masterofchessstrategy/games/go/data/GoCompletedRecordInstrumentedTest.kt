package com.masterofchessstrategy.games.go.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.masterofchessstrategy.data.GameRecord
import com.masterofchessstrategy.data.CompletedGameSession
import com.masterofchessstrategy.data.GameSessionSnapshot
import com.masterofchessstrategy.data.GameRecordCategory
import com.masterofchessstrategy.data.LoadGameRecordsResult
import com.masterofchessstrategy.data.MocsDatabase
import com.masterofchessstrategy.data.RoomGameRecordRepository
import com.masterofchessstrategy.data.RoomCompletedGameSessionRepository
import com.masterofchessstrategy.data.StoredGameMode
import com.masterofchessstrategy.data.toGameRecordEntity
import com.masterofchessstrategy.engine.ActionResult
import com.masterofchessstrategy.engine.BoardPosition
import com.masterofchessstrategy.engine.GameResult
import com.masterofchessstrategy.engine.GameType
import com.masterofchessstrategy.engine.GoAction
import com.masterofchessstrategy.engine.GoPhase
import com.masterofchessstrategy.engine.GoPlayer
import com.masterofchessstrategy.engine.NativeGoEngine
import com.masterofchessstrategy.endgame.ChineseChessEndgamePackParser
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real Native/Room, with an owned in-memory DB. No production files or app data are cleared. */
@RunWith(AndroidJUnit4::class)
class GoCompletedRecordInstrumentedTest {
    @Test fun agreedResultRoundTripsAndCanBeFavorited() = runBlocking {
        withRepository { _, repository ->
            NativeGoEngine(9).use { engine ->
                enterScoring(engine)
                finish(engine)
                val original = record(engine)
                assertTrue(repository.saveCompleted(original))
                assertFalse(repository.saveCompleted(original))
                assertTrue(repository.setFavorite(original.recordId, true))
                val loaded = requireNotNull(repository.load(original.recordId))
                assertArrayEquals(original.engineState, loaded.engineState)
                assertEquals(3, loaded.engineFormatVersion)
                assertEquals(engine.gameResult(), loaded.result)
                assertTrue(loaded.isFavorite)
                assertEquals(1, (repository.list(GameRecordCategory.FAVORITES) as LoadGameRecordsResult.Loaded).records.size)
            }
        }
    }

    @Test fun passesAndSingleConfirmationCannotBeRelabeledAsFinished() = runBlocking {
        withRepository { database, repository ->
            NativeGoEngine(13).use { engine ->
                enterScoring(engine)
                for (confirmation in 0..1) {
                    val forged = record(engine, declaredResult = GameResult.DRAW)
                    assertThrows(IllegalArgumentException::class.java) { runBlocking { repository.saveCompleted(forged) } }
                    if (confirmation == 0) accept(engine, GoAction.ConfirmScore(GoPlayer.BLACK, engine.scoringState().proposalRevision))
                }
                assertEquals(GoPhase.SCORING, engine.phase)
                assertTrue(database.gameRecordDao().listAll().isEmpty())
            }
        }
    }

    @Test fun wrongResultMoveCountAndIllegalHistoryNeverWriteAndBadRowsRemainUntouched() = runBlocking {
        withRepository { database, repository ->
            NativeGoEngine(19).use { engine ->
                enterScoring(engine)
                finish(engine)
                val valid = record(engine)
                val otherResult = if (valid.result == GameResult.FIRST_PLAYER_WIN) GameResult.SECOND_PLAYER_WIN else GameResult.FIRST_PLAYER_WIN
                val malformed = valid.engineState.copyOf().apply { this[16] = 99 }
                for (forged in listOf(valid.copy(result = otherResult), valid.copy(moveCount = 3), valid.copy(engineState = malformed))) {
                    assertThrows(IllegalArgumentException::class.java) { runBlocking { repository.saveCompleted(forged) } }
                }
                assertTrue(database.gameRecordDao().listAll().isEmpty())
                val resultCode = if (otherResult == GameResult.FIRST_PLAYER_WIN) 1 else 2
                database.gameRecordDao().insert(valid.toGameRecordEntity().copy(resultCode = resultCode))
                assertNull(repository.load(valid.recordId))
                assertSame(LoadGameRecordsResult.Incompatible, repository.list(GameRecordCategory.ALL))
                assertEquals(1, database.gameRecordDao().listAll().size)
            }
        }
    }

    @Test fun realAgreementHistoryOver64KiBIsStoredWithoutTruncation() = runBlocking {
        withRepository { _, repository ->
            NativeGoEngine(9).use { engine ->
                accept(engine, GoAction.Place(BoardPosition(0, 0)))
                enterScoring(engine)
                repeat(3_200) { accept(engine, GoAction.ToggleDeadGroup(BoardPosition(0, 0), GoPlayer.BLACK)) }
                finish(engine)
                val original = record(engine, moves = 3)
                assertTrue(original.engineState.size > 65_536)
                assertTrue(repository.saveCompleted(original))
                val loaded = requireNotNull(repository.load(original.recordId))
                assertArrayEquals(original.engineState, loaded.engineState)
                assertEquals(3, loaded.moveCount)
                assertEquals(engine.gameResult(), loaded.result)
            }
        }
    }

    @Test fun oldCompletionPathRejectsGoBeforeWritingOrUsingXiangqiRewards() = runBlocking {
        withRepository { database, _ ->
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val existing = GameRecord("existing-xiangqi", GameType.CHINESE_CHESS,
                StoredGameMode.LOCAL_TWO_PLAYER, null, GameResult.DRAW, byteArrayOf(1), 0,
                completedAtEpochMillis = 1).toGameRecordEntity()
            database.gameRecordDao().insert(existing)
            val repository = RoomCompletedGameSessionRepository(database,
                ChineseChessEndgamePackParser.loadBundled(context.assets),
                shouldFavorite = { error("Go reached the old completion callback") })
            NativeGoEngine(9).use { engine ->
                accept(engine, GoAction.Place(BoardPosition(0, 0)))
                enterScoring(engine); finish(engine)
                for (mode in listOf(StoredGameMode.LOCAL_TWO_PLAYER, StoredGameMode.ENDGAME)) {
                    val record = record(engine, moves = 3).copy(mode = mode, isEndgame = mode == StoredGameMode.ENDGAME)
                    val snapshot = GameSessionSnapshot(GameType.GO, mode, null,
                        record.engineState.copyOf(), 1, sessionId = record.recordId,
                        acceptedMoveCount = record.moveCount, resultOverride = record.result,
                        sessionVariantId = if (mode == StoredGameMode.ENDGAME) "go-test-only" else "")
                    assertThrows(IllegalArgumentException::class.java) { runBlocking {
                        repository.complete(CompletedGameSession(snapshot, record, outcome = null))
                    } }
                }
            }
            assertEquals(listOf(existing), database.gameRecordDao().listAll())
            assertNull(database.activeGameDao().find(GameType.GO.code))
        }
    }

    private suspend fun withRepository(block: suspend (MocsDatabase, RoomGameRecordRepository) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, MocsDatabase::class.java).build()
        try { block(database, RoomGameRecordRepository(database.gameRecordDao())) } finally { database.close() }
    }

    private fun record(engine: NativeGoEngine, moves: Int = 2, declaredResult: GameResult = engine.gameResult()) =
        GameRecord("go-native-${UUID.randomUUID()}", GameType.GO, StoredGameMode.LOCAL_TWO_PLAYER,
            null, declaredResult, engine.serialize(), moves, completedAtEpochMillis = 1)

    private fun enterScoring(engine: NativeGoEngine) {
        accept(engine, GoAction.Pass); accept(engine, GoAction.Pass)
        assertEquals(GoPhase.SCORING, engine.phase)
    }

    private fun finish(engine: NativeGoEngine) {
        val revision = engine.scoringState().proposalRevision
        accept(engine, GoAction.ConfirmScore(GoPlayer.BLACK, revision))
        accept(engine, GoAction.ConfirmScore(GoPlayer.WHITE, revision))
        assertEquals(GoPhase.FINISHED, engine.phase)
    }

    private fun accept(engine: NativeGoEngine, action: GoAction) {
        assertEquals(ActionResult.Accepted, engine.apply(action))
    }
}
