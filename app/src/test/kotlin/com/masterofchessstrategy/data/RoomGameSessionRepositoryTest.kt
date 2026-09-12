package com.masterofchessstrategy.data

import com.masterofchessstrategy.engine.GameType
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
            ),
        )
        bytes[0] = 99

        val stored = requireNotNull(dao.entity)
        assertEquals(GameType.CHINESE_CHESS.code, stored.gameTypeCode)
        assertEquals(StoredGameMode.LOCAL_TWO_PLAYER.code, stored.modeCode)
        assertEquals(1, stored.envelopeVersion)
        assertEquals(2, stored.engineFormatVersion)
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
                envelopeVersion = 1,
                engineFormatVersion = 2,
                engineState = storedBytes,
                updatedAtEpochMillis = 84L,
            ),
        )

        val result = RoomGameSessionRepository(dao).load(GameType.CHINESE_CHESS)

        val loaded = (result as LoadGameSessionResult.Loaded).snapshot
        assertEquals(StoredGameMode.LOCAL_TWO_PLAYER, loaded.mode)
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
