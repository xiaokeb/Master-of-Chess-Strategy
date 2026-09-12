package com.masterofchessstrategy.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.masterofchessstrategy.engine.GameType
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ActiveGameDaoInstrumentedTest {
    private lateinit var database: MocsDatabase

    @Before
    fun createDatabase() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            MocsDatabase::class.java,
        ).build()
    }

    @After
    fun closeDatabase() {
        database.close()
    }

    @Test
    fun upsertAndReadPreserveVersionedBlob() = runBlocking {
        val expected = ActiveGameEntity(
            gameTypeCode = GameType.CHINESE_CHESS.code,
            modeCode = StoredGameMode.LOCAL_TWO_PLAYER.code,
            difficultyCode = null,
            envelopeVersion = 1,
            engineFormatVersion = 1,
            engineState = byteArrayOf(1, 3, 5),
            updatedAtEpochMillis = 99L,
        )

        database.activeGameDao().upsert(expected)
        val actual = requireNotNull(
            database.activeGameDao().find(GameType.CHINESE_CHESS.code),
        )

        assertEquals(expected.copy(engineState = byteArrayOf()), actual.copy(engineState = byteArrayOf()))
        assertArrayEquals(expected.engineState, actual.engineState)
    }
}
