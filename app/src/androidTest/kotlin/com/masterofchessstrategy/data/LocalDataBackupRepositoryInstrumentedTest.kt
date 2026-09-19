package com.masterofchessstrategy.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.masterofchessstrategy.endgame.ChineseChessEndgamePackParser
import com.masterofchessstrategy.engine.Difficulty
import com.masterofchessstrategy.engine.GameResult
import com.masterofchessstrategy.engine.GameType
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalDataBackupRepositoryInstrumentedTest {
    private lateinit var database: MocsDatabase
    private lateinit var repository: RoomLocalDataBackupRepository

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        database = Room.inMemoryDatabaseBuilder(context, MocsDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val pack = ChineseChessEndgamePackParser.loadBundled(context.assets)
        repository = RoomLocalDataBackupRepository(
            database = database,
            endgamePack = pack,
            validateEngineState = { state -> state.contentEquals(VALID_ENGINE_STATE) },
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun exportAndRestoreReplaceEveryLocalDataCategory() = runBlocking {
        val pack = ChineseChessEndgamePackParser.loadBundled(
            InstrumentationRegistry.getInstrumentation().targetContext.assets,
        )
        val level = pack.levels.first()
        val sessionRepository = RoomGameSessionRepository(database.activeGameDao())
        val selectionRepository = RoomLastSelectionRepository(database.lastSelectionDao())
        val settingsRepository = RoomAppSettingsRepository(database.appSettingsDao())
        val tutorialRepository = RoomTutorialProgressRepository(database.tutorialProgressDao())
        val statisticsRepository = RoomMatchStatisticsRepository(database.matchOutcomeDao())
        val recordRepository = RoomGameRecordRepository(database.gameRecordDao())
        val endgameRepository = RoomEndgameProgressRepository(
            database.endgameProgressDao(),
            pack,
        )
        sessionRepository.save(
            GameSessionSnapshot(
                gameType = GameType.CHINESE_CHESS,
                mode = StoredGameMode.LOCAL_TWO_PLAYER,
                difficulty = null,
                engineState = VALID_ENGINE_STATE,
                updatedAtEpochMillis = 10L,
                sessionId = "session-1",
            ),
        )
        selectionRepository.save(
            LastGameSelection(
                gameType = GameType.CHINESE_CHESS,
                mode = StoredGameMode.LOCAL_TWO_PLAYER,
                difficulty = null,
                updatedAtEpochMillis = 11L,
            ),
        )
        val expectedSettings = AppSettings(
            defaultDifficulty = Difficulty.HARD,
            autoContinueEnabled = true,
            soundEnabled = false,
            gameDurationMinutes = 30,
            updatedAtEpochMillis = 12L,
        )
        settingsRepository.save(expectedSettings)
        tutorialRepository.save(
            TutorialProgress(
                gameType = GameType.CHINESE_CHESS,
                contentVersion = TutorialProgress.CURRENT_CONTENT_VERSION,
                completedStepCount = TutorialProgress.TOTAL_STEP_COUNT,
                isCompleted = true,
                updatedAtEpochMillis = 13L,
            ),
        )
        statisticsRepository.record(
            MatchOutcome(
                matchId = "match-1",
                gameType = GameType.CHINESE_CHESS,
                mode = StoredGameMode.HUMAN_VS_AI,
                difficulty = Difficulty.EASY,
                playerIndex = 0,
                result = GameResult.FIRST_PLAYER_WIN,
                settledAtEpochMillis = 14L,
            ),
        )
        recordRepository.saveCompleted(
            GameRecord(
                recordId = "record-1",
                gameType = GameType.CHINESE_CHESS,
                mode = StoredGameMode.LOCAL_TWO_PLAYER,
                difficulty = null,
                result = GameResult.DRAW,
                engineState = VALID_ENGINE_STATE,
                moveCount = 4,
                isFavorite = true,
                completedAtEpochMillis = 15L,
            ),
        )
        endgameRepository.complete(level, playerMoves = 1, completedAtEpochMillis = 16L)

        val backup = repository.export(createdAtEpochMillis = 20L)
        database.activeGameDao().deleteAll()
        database.lastSelectionDao().deleteAll()
        database.appSettingsDao().deleteAll()
        database.tutorialProgressDao().deleteAll()
        database.matchOutcomeDao().deleteAll()
        database.gameRecordDao().deleteAll()
        database.endgameProgressDao().deleteAll()

        val summary = repository.restore(backup)

        assertEquals(1, summary.activeSessionCount)
        assertEquals(1, summary.matchOutcomeCount)
        assertEquals(1, summary.gameRecordCount)
        assertEquals(1, summary.completedEndgameCount)
        val restoredSession =
            sessionRepository.load(GameType.CHINESE_CHESS) as LoadGameSessionResult.Loaded
        assertArrayEquals(VALID_ENGINE_STATE, restoredSession.snapshot.engineState)
        assertEquals(
            expectedSettings,
            (settingsRepository.load() as LoadAppSettingsResult.Loaded).settings,
        )
        assertTrue(
            (tutorialRepository.load(GameType.CHINESE_CHESS)
                as LoadTutorialProgressResult.Loaded).progress.isCompleted,
        )
        assertEquals(
            1,
            (statisticsRepository.load() as LoadMatchStatisticsResult.Loaded)
                .statistics.completedMatches,
        )
        assertTrue(
            (recordRepository.list(GameRecordCategory.ALL) as LoadGameRecordsResult.Loaded)
                .records.single().isFavorite,
        )
        assertEquals(
            level.id,
            (endgameRepository.load() as LoadEndgameProgressResult.Loaded)
                .progress.completedById.keys.single(),
        )
    }

    @Test
    fun corruptBackupIsRejectedBeforeCurrentDataChanges() = runBlocking {
        val settingsRepository = RoomAppSettingsRepository(database.appSettingsDao())
        settingsRepository.save(AppSettings.DEFAULT.copy(updatedAtEpochMillis = 1L))
        val backup = repository.export(createdAtEpochMillis = 2L)
        val current = AppSettings.DEFAULT.copy(
            defaultDifficulty = Difficulty.MASTER,
            updatedAtEpochMillis = 3L,
        )
        settingsRepository.save(current)
        backup[backup.lastIndex / 2] = (backup[backup.lastIndex / 2].toInt() xor 1).toByte()

        var rejected = false
        try {
            repository.restore(backup)
        } catch (_: IllegalArgumentException) {
            rejected = true
        }

        assertTrue(rejected)
        assertEquals(
            current,
            (settingsRepository.load() as LoadAppSettingsResult.Loaded).settings,
        )
    }

    private companion object {
        val VALID_ENGINE_STATE = byteArrayOf(1, 2, 3)
    }
}
