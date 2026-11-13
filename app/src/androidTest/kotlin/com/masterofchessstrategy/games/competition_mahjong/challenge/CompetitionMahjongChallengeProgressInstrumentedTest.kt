package com.masterofchessstrategy.games.competition_mahjong.challenge

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.time.LocalDate
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class CompetitionMahjongChallengeProgressInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val files = mutableListOf<File>()

    @After fun cleanFiles() {
        files.forEach { file -> file.delete(); File(file.path + ".bak").delete(); File(file.path + ".new").delete() }
    }

    @Test fun twoRepositoryInstancesMergeSequentialCompletionsWithoutLostProgress() = runBlocking {
        val file = freshFile()
        val first = CompetitionMahjongChallengeFileRepository(file)
        val stale = CompetitionMahjongChallengeFileRepository(file)
        val values = CompetitionMahjongChallengeLibrary.challenges

        assertTrue(first.load().completedIds.isEmpty())
        first.complete(values[0].id)
        stale.complete(values[1].id)

        val restored = CompetitionMahjongChallengeFileRepository(file).load()
        assertEquals(setOf(values[0].id, values[1].id), restored.completedIds)
        assertTrue(restored.isUnlocked(values[2].id))
    }

    @Test fun dailySelectionAndReturnPositionSurviveRepositoryReopen() = runBlocking {
        val file = freshFile()
        val repository = CompetitionMahjongChallengeFileRepository(file)
        val date = LocalDate.of(2026, 10, 15)
        val daily = CompetitionMahjongChallenges.daily(repository.load(), date)
        repository.selectDaily(date, daily.id)

        val restored = CompetitionMahjongChallengeFileRepository(file).load()
        assertEquals(CompetitionMahjongChallengeMode.DAILY, restored.lastMode)
        assertEquals(date, restored.dailyDate)
        assertEquals(daily.id, restored.dailyChallengeId)
        assertEquals(daily.id, restored.lastChallengeId)
    }

    @Test fun corruptedProgressIsRejectedWithoutOverwritingEvidence() = runBlocking {
        val file = freshFile()
        val repository = CompetitionMahjongChallengeFileRepository(file)
        repository.load()
        repository.complete(CompetitionMahjongChallengeLibrary.challenges.first().id)
        val corrupt = file.readBytes().also { it[it.lastIndex - 2] = (it[it.lastIndex - 2].toInt() xor 1).toByte() }
        file.writeBytes(corrupt)

        assertThrows(IOException::class.java) {
            runBlocking { CompetitionMahjongChallengeFileRepository(file).load() }
        }
        assertArrayEquals(corrupt, file.readBytes())
    }

    private fun freshFile(): File = File(context.cacheDir,
        "competition-mahjong-challenge-${UUID.randomUUID()}.bin").also {
        files += it
        it.delete()
        File(it.path + ".bak").delete()
        File(it.path + ".new").delete()
    }
}
