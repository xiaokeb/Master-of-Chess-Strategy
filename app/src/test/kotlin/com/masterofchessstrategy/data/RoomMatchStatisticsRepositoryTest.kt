package com.masterofchessstrategy.data

import com.masterofchessstrategy.engine.Difficulty
import com.masterofchessstrategy.engine.GameResult
import com.masterofchessstrategy.engine.GameType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class RoomMatchStatisticsRepositoryTest {
    @Test
    fun duplicateMatchIsIgnoredAndDifficultyScoringIsDerivedFromLedger() = runBlocking {
        val repository = RoomMatchStatisticsRepository(FakeMatchOutcomeDao())
        val win = outcome(
            matchId = "easy-win",
            difficulty = Difficulty.EASY,
            result = GameResult.FIRST_PLAYER_WIN,
        )

        val first = repository.record(win)
        val duplicate = repository.record(win)
        val afterLoss = repository.record(
            outcome(
                matchId = "medium-loss",
                difficulty = Difficulty.MEDIUM,
                result = GameResult.SECOND_PLAYER_WIN,
            ),
        )

        assertTrue(first.wasRecorded)
        assertFalse(duplicate.wasRecorded)
        assertEquals(1, duplicate.statistics.totalWins)
        assertEquals(
            1,
            duplicate.statistics.winsAt(GameType.CHINESE_CHESS, Difficulty.EASY),
        )
        assertEquals(1, duplicate.statistics.stars)
        assertEquals(2, afterLoss.statistics.completedMatches)
        assertEquals(1, afterLoss.statistics.totalLosses)
        assertEquals(0, afterLoss.statistics.stars)
        assertEquals(-30, afterLoss.statistics.score)
    }

    @Test
    fun incompatibleLedgerCodesDoNotLeakIntoStatistics() = runBlocking {
        val dao = FakeMatchOutcomeDao().apply {
            entities["broken"] = MatchOutcomeEntity(
                matchId = "broken",
                gameTypeCode = GameType.CHINESE_CHESS.code,
                modeCode = StoredGameMode.HUMAN_VS_AI.code,
                difficultyCode = 99,
                playerIndex = 0,
                resultCode = 1,
                settledAtEpochMillis = 1L,
            )
        }

        assertSame(
            LoadMatchStatisticsResult.Incompatible,
            RoomMatchStatisticsRepository(dao).load(),
        )
    }

    private fun outcome(
        matchId: String,
        difficulty: Difficulty,
        result: GameResult,
    ) = MatchOutcome(
        matchId = matchId,
        gameType = GameType.CHINESE_CHESS,
        mode = StoredGameMode.HUMAN_VS_AI,
        difficulty = difficulty,
        playerIndex = 0,
        result = result,
        settledAtEpochMillis = 1L,
    )

    private class FakeMatchOutcomeDao : MatchOutcomeDao {
        val entities = linkedMapOf<String, MatchOutcomeEntity>()

        override suspend fun find(matchId: String): MatchOutcomeEntity? = entities[matchId]

        override suspend fun insert(entity: MatchOutcomeEntity): Long {
            if (entities.containsKey(entity.matchId)) return -1L
            entities[entity.matchId] = entity
            return entities.size.toLong()
        }

        override suspend fun listAll(): List<MatchOutcomeEntity> =
            entities.values.toList()

        override suspend fun insertAll(entities: List<MatchOutcomeEntity>) {
            entities.forEach { entity ->
                check(this.entities.putIfAbsent(entity.matchId, entity) == null)
            }
        }

        override suspend fun deleteAll() {
            entities.clear()
        }
    }
}
