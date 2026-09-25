package com.masterofchessstrategy.data

import com.masterofchessstrategy.endgame.ChineseChessEndgamePackParser
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class RoomEndgameProgressRepositoryTest {
    @Test
    fun firstCompletionAwardsOnceAndReplayOnlyImprovesBestMoves() = runBlocking {
        val dao = FakeEndgameProgressDao()
        val pack = ChineseChessEndgamePackParser.parse(PACK)
        val repository = RoomEndgameProgressRepository(dao, pack)
        val level = pack.levels.single()

        val first = repository.complete(level, playerMoves = 2, completedAtEpochMillis = 100L)
        val improved = repository.complete(level, playerMoves = 1, completedAtEpochMillis = 200L)
        val repeated = repository.complete(level, playerMoves = 1, completedAtEpochMillis = 300L)

        assertTrue(first.firstCompletion)
        assertEquals(2, first.awardedStars)
        assertEquals(25, first.awardedScore)
        assertFalse(improved.firstCompletion)
        assertTrue(improved.bestMovesImproved)
        assertEquals(0, improved.awardedStars)
        assertFalse(repeated.bestMovesImproved)
        val progress = (repository.load() as LoadEndgameProgressResult.Loaded).progress
        assertEquals(1, progress.completedById.getValue(level.id).bestPlayerMoves)
        assertEquals(2, progress.totalStars)
        assertEquals(25, progress.totalScore)
    }

    @Test
    fun unknownLevelInDatabaseIsRejected() = runBlocking {
        val dao = FakeEndgameProgressDao().apply {
            entities["removed"] = EndgameProgressEntity(
                levelId = "removed",
                contentVersion = 1,
                difficultyCode = 0,
                bestPlayerMoves = 1,
                starsAwarded = 1,
                scoreAwarded = 10,
                completedAtEpochMillis = 1L,
            )
        }
        val repository = RoomEndgameProgressRepository(
            dao,
            ChineseChessEndgamePackParser.parse(PACK),
        )

        assertSame(LoadEndgameProgressResult.Incompatible, repository.load())
    }

    @Test
    fun oldCompletionsRemainValidWithVersionThreePack() = runBlocking {
        for (contentVersion in 1..2) {
            val dao = FakeEndgameProgressDao().apply {
                entities["xq-easy-001"] = EndgameProgressEntity(
                    levelId = "xq-easy-001",
                    contentVersion = contentVersion,
                    difficultyCode = 0,
                    bestPlayerMoves = 1,
                    starsAwarded = 2,
                    scoreAwarded = 25,
                    completedAtEpochMillis = 42L,
                )
            }
            val repository = RoomEndgameProgressRepository(
                dao,
                ChineseChessEndgamePackParser.parse(PACK),
            )

            val loaded = repository.load() as LoadEndgameProgressResult.Loaded

            assertEquals(1, loaded.progress.completedById.size)
            assertEquals(2, loaded.progress.totalStars)
            assertEquals(25, loaded.progress.totalScore)
        }
    }

    private class FakeEndgameProgressDao : EndgameProgressDao {
        val entities = linkedMapOf<String, EndgameProgressEntity>()

        override suspend fun insert(entity: EndgameProgressEntity): Long {
            if (entities.containsKey(entity.levelId)) return -1L
            entities[entity.levelId] = entity
            return entities.size.toLong()
        }

        override suspend fun listAll(): List<EndgameProgressEntity> = entities.values.toList()

        override suspend fun updateBest(levelId: String, moves: Int): Int {
            val current = entities[levelId] ?: return 0
            if (current.bestPlayerMoves <= moves) return 0
            entities[levelId] = current.copy(bestPlayerMoves = moves)
            return 1
        }

        override suspend fun deleteAll() {
            entities.clear()
        }
    }

    private companion object {
        const val PACK = """MOCS-XQ-ENDGAMES|3
LICENSE|GPL-3.0-or-later
AUTHOR|Test Author
LEVEL|xq-easy-001|0|1|训练|一步杀|RED|2|2|25|3|MAIN
PIECE|4|9|RED|GENERAL
PIECE|4|0|BLACK|GENERAL
PIECE|3|1|RED|CHARIOT
MOVE|3|1|4|1
END
"""
    }
}
