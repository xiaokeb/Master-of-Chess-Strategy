package com.masterofchessstrategy.data

import com.masterofchessstrategy.endgame.ChineseChessEndgamePackParser
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

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
    fun oldCompletionsRemainValidWithVersionFivePack() = runBlocking {
        for (contentVersion in 1..4) {
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

    @Test
    fun expandedBundledPackKeepsOldProgressAndAwardsNewLevelsOnce() = runBlocking {
        val pack = ChineseChessEndgamePackParser.parse(
            File("src/main/assets/endgames/chinese_chess/endgames-v5.txt").readText(Charsets.UTF_8),
        )
        val oldLevel = pack.levels.single { it.id == "xq-easy-001" }
        val appended = pack.levels.filter { it.id in setOf(
            "xq-medium-010", "xq-medium-011", "xq-medium-012", "xq-medium-013",
        ) }
        assertEquals(4, appended.size)
        val dao = FakeEndgameProgressDao().apply {
            entities[oldLevel.id] = EndgameProgressEntity(
                levelId = oldLevel.id,
                contentVersion = 4,
                difficultyCode = oldLevel.difficulty.code,
                bestPlayerMoves = 1,
                starsAwarded = oldLevel.starReward,
                scoreAwarded = oldLevel.scoreReward,
                completedAtEpochMillis = 1L,
            )
        }
        val repository = RoomEndgameProgressRepository(dao, pack)

        appended.forEachIndexed { index, level ->
            assertTrue(repository.complete(level, 2, 2L + index).firstCompletion)
            // Repository recreation must retain the insert-once reward boundary.
            val reopened = RoomEndgameProgressRepository(dao, pack)
            val repeated = reopened.complete(level, 2, 10L + index)
            assertFalse(repeated.firstCompletion)
            assertEquals(0, repeated.awardedStars)
            assertEquals(0, repeated.awardedScore)
        }

        val reloaded = RoomEndgameProgressRepository(dao, pack).load()
        val progress = (reloaded as LoadEndgameProgressResult.Loaded).progress
        assertEquals(setOf(oldLevel.id) + appended.map { it.id }, progress.completedById.keys)
        assertEquals(oldLevel.starReward + appended.sumOf { it.starReward }, progress.totalStars)
        assertEquals(oldLevel.scoreReward + appended.sumOf { it.scoreReward }, progress.totalScore)
    }

    @Test
    fun trimmedLevelsRejectOverLimitCompletionsWithoutWritingRewards() = runBlocking {
        val pack = ChineseChessEndgamePackParser.parse(
            File("src/main/assets/endgames/chinese_chess/endgames-v5.txt").readText(Charsets.UTF_8),
        )
        val dao = FakeEndgameProgressDao()
        val repository = RoomEndgameProgressRepository(dao, pack)
        listOf("xq-medium-012", "xq-medium-013").forEach { levelId ->
            val level = pack.levels.single { it.id == levelId }
            assertEquals(2, level.maxPlayerMoves)
            for (invalidMoves in listOf(0, 3)) {
                try {
                    repository.complete(level, invalidMoves, 1L)
                    org.junit.Assert.fail("$levelId must reject $invalidMoves moves")
                } catch (expected: IllegalArgumentException) {
                    assertEquals("Completion exceeds move limit", expected.message)
                }
            }
        }
        assertTrue(dao.entities.isEmpty())
        val progress = (repository.load() as LoadEndgameProgressResult.Loaded).progress
        assertEquals(0, progress.totalStars)
        assertEquals(0, progress.totalScore)
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
        const val PACK = """MOCS-XQ-ENDGAMES|5
LICENSE|GPL-3.0-or-later
AUTHOR|Test Author
LEVEL|xq-easy-001|0|1|训练|一步杀|RED|2|2|25|4|MAIN
PIECE|4|9|RED|GENERAL
PIECE|4|0|BLACK|GENERAL
PIECE|4|5|RED|SOLDIER
PIECE|3|1|RED|CHARIOT
MOVE|3|1|4|1
END
"""
    }
}
