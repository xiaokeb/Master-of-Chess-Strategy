package com.masterofchessstrategy.data

import com.masterofchessstrategy.engine.GameType
import com.masterofchessstrategy.engine.GameResult
import com.masterofchessstrategy.engine.ChineseChessSide
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test

class RoomGameSessionRepositoryTest {
    @Test
    fun saveMapsStableCodesAndCopiesEngineBytes() = runBlocking {
        val dao = FakeActiveGameDao()
        val repository = RoomGameSessionRepository(dao)
        val bytes = byteArrayOf(1, 2, 3)

        repository.save(
            GameSessionSnapshot(
                gameType = GameType.CHINESE_CHESS,
                mode = StoredGameMode.LOCAL_TWO_PLAYER,
                difficulty = null,
                engineState = bytes,
                updatedAtEpochMillis = 42L,
                sessionId = "match-42",
                acceptedMoveCount = 8,
                undoUseCount = 2,
                hintUseCount = 1,
                resultOverride = GameResult.SECOND_PLAYER_WIN,
                timeControlMinutes = 30,
                redRemainingMillis = 1_700_000L,
                blackRemainingMillis = 1_800_000L,
                turnStartedAtEpochMillis = 40L,
                pendingDrawOfferSide = ChineseChessSide.RED,
                autoPlayPaused = true,
                autoPlaySpeedPermille = 2_000,
                completedAutoGames = 4,
            ),
        )
        bytes[0] = 99

        val stored = requireNotNull(dao.entity)
        assertEquals(GameType.CHINESE_CHESS.code, stored.gameTypeCode)
        assertEquals(StoredGameMode.LOCAL_TWO_PLAYER.code, stored.modeCode)
        assertEquals(3, stored.envelopeVersion)
        assertEquals(2, stored.engineFormatVersion)
        assertEquals("match-42", stored.sessionId)
        assertEquals(8, stored.acceptedMoveCount)
        assertEquals(2, stored.undoUseCount)
        assertEquals(1, stored.hintUseCount)
        assertEquals(2, stored.resultOverrideCode)
        assertEquals(30, stored.timeControlMinutes)
        assertEquals(1_700_000L, stored.redRemainingMillis)
        assertEquals(ChineseChessSide.RED.code, stored.pendingDrawOfferSideCode)
        assertEquals(true, stored.autoPlayPaused)
        assertEquals(2_000, stored.autoPlaySpeedPermille)
        assertEquals(4, stored.completedAutoGames)
        assertArrayEquals(byteArrayOf(1, 2, 3), stored.engineState)
    }

    @Test
    fun loadReturnsDefensiveCopyAndStableTypes() = runBlocking {
        val storedBytes = byteArrayOf(7, 8, 9)
        val dao = FakeActiveGameDao(
            ActiveGameEntity(
                gameTypeCode = GameType.CHINESE_CHESS.code,
                modeCode = StoredGameMode.LOCAL_TWO_PLAYER.code,
                difficultyCode = null,
                envelopeVersion = 3,
                engineFormatVersion = 2,
                engineState = storedBytes,
                updatedAtEpochMillis = 84L,
                sessionId = "match-84",
                acceptedMoveCount = 4,
                undoUseCount = 1,
                hintUseCount = 3,
                resultOverrideCode = 3,
                timeControlMinutes = 5,
                redRemainingMillis = 200_000L,
                blackRemainingMillis = 300_000L,
                turnStartedAtEpochMillis = 80L,
                pendingDrawOfferSideCode = ChineseChessSide.BLACK.code,
                autoPlayPaused = false,
                autoPlaySpeedPermille = 500,
                completedAutoGames = 2,
            ),
        )

        val result = RoomGameSessionRepository(dao).load(GameType.CHINESE_CHESS)

        val loaded = (result as LoadGameSessionResult.Loaded).snapshot
        assertEquals(StoredGameMode.LOCAL_TWO_PLAYER, loaded.mode)
        assertEquals("match-84", loaded.sessionId)
        assertEquals(4, loaded.acceptedMoveCount)
        assertEquals(1, loaded.undoUseCount)
        assertEquals(3, loaded.hintUseCount)
        assertEquals(GameResult.DRAW, loaded.resultOverride)
        assertEquals(5, loaded.timeControlMinutes)
        assertEquals(200_000L, loaded.redRemainingMillis)
        assertEquals(ChineseChessSide.BLACK, loaded.pendingDrawOfferSide)
        assertEquals(500, loaded.autoPlaySpeedPermille)
        assertEquals(2, loaded.completedAutoGames)
        assertArrayEquals(storedBytes, loaded.engineState)
        assertNotSame(storedBytes, loaded.engineState)
    }

    @Test
    fun unknownEnvelopeOrModeIsRejected() = runBlocking {
        val dao = FakeActiveGameDao(
            ActiveGameEntity(
                gameTypeCode = GameType.CHINESE_CHESS.code,
                modeCode = 99,
                difficultyCode = null,
                envelopeVersion = 2,
                engineFormatVersion = 1,
                engineState = byteArrayOf(1),
                updatedAtEpochMillis = 0L,
            ),
        )

        val result = RoomGameSessionRepository(dao).load(GameType.CHINESE_CHESS)

        assertSame(LoadGameSessionResult.Incompatible, result)
    }

    private class FakeActiveGameDao(
        var entity: ActiveGameEntity? = null,
    ) : ActiveGameDao {
        override suspend fun find(gameTypeCode: Int): ActiveGameEntity? =
            entity?.takeIf { it.gameTypeCode == gameTypeCode }

        override suspend fun upsert(entity: ActiveGameEntity) {
            this.entity = entity
        }

        override suspend fun delete(gameTypeCode: Int) {
            if (entity?.gameTypeCode == gameTypeCode) entity = null
        }
    }
}
