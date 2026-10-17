package com.masterofchessstrategy.games.go

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException
import java.util.UUID

class GoEndgameProgressInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val file = File(context.cacheDir, "go-endgame-progress-test-${UUID.randomUUID()}.bin")
    private val repository = GoEndgameFileRepository(file)
    private val ids = GoEndgameLibrary.puzzles.map { it.id }

    @After fun cleanupOwnedFiles() {
        listOf(file, File(file.path + ".bak"), File(file.path + ".new")).forEach {
            if (it.exists()) assertTrue(it.delete())
        }
    }

    @Test fun realAtomicFileRestoresCompletionAndSelectedPuzzleAcrossRepositoryInstances() = runBlocking {
        assertTrue(repository.load().completedIds.isEmpty())
        repository.complete(ids[0]); repository.select(ids[1])
        val restored = GoEndgameFileRepository(file).load()
        assertEquals(setOf(ids[0]), restored.completedIds); assertEquals(ids[1], restored.lastPuzzleId)
        assertTrue(restored.isUnlocked(ids[1])); assertFalse(restored.isUnlocked(ids[2]))
        repository.complete(ids[0]); assertEquals(setOf(ids[0]), repository.load().completedIds)
    }

    @Test fun concurrentReadMergeWritesNeverEraseAnotherInstancesCompletion() = runBlocking {
        repository.complete(ids[0])
        val another = GoEndgameFileRepository(file)
        listOf(async(Dispatchers.Default) { repository.complete(ids[1]) },
            async(Dispatchers.Default) { another.complete(ids[0]) }).awaitAll()
        val progress = another.load()
        assertEquals(ids.take(2).toSet(), progress.completedIds)
        assertTrue(progress.isUnlocked(ids[2])); assertFalse(progress.isUnlocked(ids[3]))
    }

    @Test fun corruptedBytesAndLockedSelectionsArePreservedWithoutReset() = runBlocking {
        repository.complete(ids[0])
        val good = file.readBytes()
        try { repository.select(ids[2]); fail("Locked puzzle selected") } catch (_: IllegalArgumentException) { }
        assertArrayEquals(good, file.readBytes())
        val bad = good.copyOf().apply { this[0] = 0 }
        file.writeBytes(bad)
        try { repository.load(); fail("Corrupt progress loaded") } catch (_: IOException) { }
        try { repository.complete(ids[0]); fail("Corrupt progress overwritten") } catch (_: IOException) { }
        assertArrayEquals(bad, file.readBytes())
    }

    @Test fun dailyReservationSurvivesCrossInstanceProgressGrowthAndOtherModes() = runBlocking {
        val date = java.time.LocalDate.of(2026, 10, 14)
        repository.selectDaily(date, ids[0]); repository.complete(ids[0])
        val another = GoEndgameFileRepository(file)
        another.select(ids[1], GoEndgameChallengeMode.RANDOM)
        val restored = repository.load()
        assertEquals(date, restored.dailyDate); assertEquals(ids[0], restored.dailyPuzzleId)
        assertEquals(GoEndgameChallengeMode.RANDOM, restored.lastMode)
        another.selectDaily(date, ids[0])
        assertEquals(ids[0], repository.load().lastPuzzleId)
        val bytes = file.readBytes()
        try { another.selectDaily(date, ids[1]); fail("Same-day reservation replaced") }
        catch (_: IllegalArgumentException) { }
        assertArrayEquals(bytes, file.readBytes())
    }

    @Test fun realV1FileMigratesToV2OnlyAfterSuccessfulSelection() = runBlocking {
        val body = "GEP1\n1\n${ids[1]}\n${ids[0]}\n"
        val crc = java.util.zip.CRC32().apply { update(body.toByteArray()) }.value.toString(16).padStart(8, '0')
        val old = "$body$crc\n".toByteArray(); file.writeBytes(old)
        val loaded = repository.load()
        assertEquals(setOf(ids[0]), loaded.completedIds); assertEquals(ids[1], loaded.lastPuzzleId)
        assertEquals(GoEndgameChallengeMode.MAINLINE, loaded.lastMode); assertNull(loaded.dailyDate)
        assertArrayEquals(old, file.readBytes())
        repository.select(ids[1], GoEndgameChallengeMode.THEME)
        assertTrue(file.readText().startsWith("GEP1\n2\n1\n"))
        assertEquals(GoEndgameChallengeMode.THEME, GoEndgameFileRepository(file).load().lastMode)
    }
}
