package com.masterofchessstrategy.games.go

import androidx.test.platform.app.InstrumentationRegistry
import com.masterofchessstrategy.engine.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException
import java.util.UUID

/** Only creates/removes this class's UUID checkpoint, never existing matches or app data. */
class GoAiMatchFileRepositoryInstrumentedTest {
    @Test fun actualCompletionReservationAndVersionTwoMigrationPreserveNativeResult() = runBlocking {
        withFile { file ->
            val config = GoAiMatchConfig(9, difficulty = GoAiDifficulty.MEDIUM)
            val bytes = NativeGoEngine(9).use {
                assertEquals(ActionResult.Accepted, it.apply(GoAction.Pass))
                assertEquals(ActionResult.Accepted, it.apply(GoAction.Pass))
                val revision = it.scoringState().proposalRevision
                for (actor in GoPlayer.entries) assertEquals(ActionResult.Accepted, it.apply(GoAction.ConfirmScore(actor, revision)))
                it.serialize()
            }
            val repository = GoAiMatchFileRepository(file)
            repository.save(GoAiSavedMatch("reserved-qa", config, bytes, hintUsed = 1, completionReserved = true))
            val reserved = checkNotNull(GoAiMatchFileRepository(file).load())
            assertTrue(reserved.completionReserved)
            assertEquals(1, reserved.hintUsed)
            NativeGoEngine(9).use {
                assertEquals(RestoreResult.Restored, it.restore(reserved.copyBytes()))
                assertEquals(GoPhase.FINISHED, it.phase)
                assertEquals(GameResult.SECOND_PLAYER_WIN, it.gameResult())
            }
            val current = file.readBytes()
            val legacy = java.nio.ByteBuffer.allocate(32 + current.size - 36).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                .put(current, 0, 28).putInt(0).put(current, 36, current.size - 36).array()
            legacy[4] = 2; legacy[9] = 0; legacy[10] = 0
            val crc = java.util.zip.CRC32().apply { update(legacy, 0, 28); update(legacy, 32, legacy.size - 32) }.value.toInt()
            java.nio.ByteBuffer.wrap(legacy).order(java.nio.ByteOrder.LITTLE_ENDIAN).putInt(28, crc)
            file.writeBytes(legacy) // Only this UUID test file.
            val migrated = checkNotNull(repository.load())
            assertFalse(migrated.completionReserved)
            assertEquals(1, migrated.hintUsed)
            assertArrayEquals(bytes, migrated.copyBytes())
            repository.save(migrated)
            assertEquals(6, file.readBytes()[4].toInt())
        }
    }
    @Test fun actualFileKeepsOriginalWhiteAndAutomaticParticipationAfterReturningToHuman() = runBlocking {
        withFile { file ->
            val bytes = NativeGoEngine(13, 0, 5).use {
                assertEquals(ActionResult.Accepted, it.apply(GoAction.Place(BoardPosition(0, 0))))
                it.serialize()
            }
            for (human in listOf(null, GoPlayer.WHITE)) {
                val config = GoAiMatchConfig(13, 5, human, GoAiDifficulty.MEDIUM)
                GoAiMatchFileRepository(file).save(GoAiSavedMatch("mixed-qa", config, bytes, 1, 2,
                    rememberedHuman = GoPlayer.WHITE, automaticUsed = true))
                val restored = checkNotNull(GoAiMatchFileRepository(file).load())
                assertEquals(6, file.readBytes()[4].toInt())
                assertEquals(config, restored.config); assertEquals(GoPlayer.WHITE, restored.rememberedHuman)
                assertTrue(restored.automaticUsed); assertEquals(1, restored.undoUsed); assertEquals(2, restored.hintUsed)
                assertArrayEquals(bytes, restored.copyBytes())
                NativeGoEngine(13).use {
                    assertEquals(RestoreResult.Restored, it.restore(restored.copyBytes()))
                    assertEquals(5, it.handicapStones); assertEquals(1, it.eventHistory().size)
                }
            }
        }
    }
    @Test fun actualAtomicFileRestoresNativeMetadataAndUndoUseAcrossInstances() = runBlocking {
        withFile { file ->
            for (size in listOf(9, 13, 19)) for (handicap in listOf(0, 5)) {
                val config = GoAiMatchConfig(size, handicap, GoPlayer.WHITE, GoAiDifficulty.HARD)
                val bytes = NativeGoEngine(size, if (handicap == 0) 15 else 0, handicap).use {
                    assertEquals(ActionResult.Accepted, it.apply(GoAction.Place(BoardPosition(0, 0))))
                    it.serialize()
                }
                GoAiMatchFileRepository(file).save(GoAiSavedMatch("qa-$size-$handicap", config, bytes, 1, 1))
                val restored = checkNotNull(GoAiMatchFileRepository(file).load())
                assertEquals(config, restored.config)
                assertEquals(1, restored.undoUsed)
                assertEquals(1, restored.hintUsed)
                assertArrayEquals(bytes, restored.copyBytes())
                NativeGoEngine(size).use {
                    assertEquals(RestoreResult.Restored, it.restore(restored.copyBytes()))
                    assertEquals(handicap, it.handicapStones)
                }
            }
        }
    }

    @Test fun corruptionIsReportedAndExplicitSaveCanRepairOwnedCheckpoint() = runBlocking {
        withFile { file ->
            val repository = GoAiMatchFileRepository(file)
            val match = NativeGoEngine(9).use { GoAiSavedMatch("qa", GoAiMatchConfig(9), it.serialize()) }
            repository.save(match)
            val damaged = file.readBytes(); damaged[damaged.lastIndex] = (damaged.last().toInt() xor 1).toByte()
            file.writeBytes(damaged)
            try { repository.load(); fail("Corrupt match accepted") } catch (_: IOException) { }
            assertArrayEquals(damaged, file.readBytes())
            repository.save(match)
            assertArrayEquals(match.copyBytes(), checkNotNull(repository.load()).copyBytes())
        }
    }

    @Test fun concurrentRepositoryInstancesNeverExposePartialCheckpoints() = runBlocking {
        withFile { file ->
            val matches = (0..7).map { index -> NativeGoEngine(9).use {
                assertEquals(ActionResult.Accepted, it.apply(GoAction.Place(BoardPosition(index, 0))))
                GoAiSavedMatch("qa-$index", GoAiMatchConfig(9), it.serialize())
            } }
            GoAiMatchFileRepository(file).save(matches.first())
            coroutineScope {
                matches.map { match -> async(Dispatchers.Default) {
                    val repository = GoAiMatchFileRepository(file)
                    repository.save(match)
                    val observed = checkNotNull(repository.load())
                    val reference = matches.first { it.matchId == observed.matchId }
                    assertArrayEquals(reference.copyBytes(), observed.copyBytes())
                } }.awaitAll()
            }
        }
    }

    @Test fun actualV6FileKeepsIndependentCurrentAndOpeningNativeHistories() = runBlocking {
        withFile { file ->
            val opening = NativeGoEngine(13, 0, 5).use {
                assertEquals(ActionResult.Accepted, it.apply(GoAction.Place(BoardPosition(0, 0))))
                it.serialize()
            }
            val current = NativeGoEngine(13).use {
                assertEquals(RestoreResult.Restored, it.restore(opening))
                assertEquals(ActionResult.Accepted, it.apply(GoAction.Place(BoardPosition(1, 0))))
                it.serialize()
            }
            val match = GoAiSavedMatch("opening-v5", GoAiMatchConfig(13, 5, GoPlayer.WHITE, GoAiDifficulty.HARD),
                current, 1, 1, rememberedHuman = GoPlayer.WHITE, openingBytes = opening)
            GoAiMatchFileRepository(file).save(match)
            assertEquals(6, file.readBytes()[4].toInt())
            val restored = checkNotNull(GoAiMatchFileRepository(file).load())
            assertArrayEquals(current, restored.copyBytes()); assertArrayEquals(opening, restored.copyOpeningBytes())
            NativeGoEngine(13).use {
                assertEquals(RestoreResult.Restored, it.restore(checkNotNull(restored.copyOpeningBytes())))
                assertEquals(1, it.eventHistory().size); assertEquals(5, it.handicapStones)
            }
            NativeGoEngine(13).use {
                assertEquals(RestoreResult.Restored, it.restore(restored.copyBytes()))
                assertEquals(2, it.eventHistory().size); assertEquals(5, it.handicapStones)
            }
        }
    }

    @Test fun actualV6FileKeepsAutomaticContinuationAcrossRepositoryInstances() = runBlocking {
        withFile { file ->
            val bytes = NativeGoEngine(9).use { it.serialize() }
            val match = GoAiSavedMatch("automatic-series", GoAiMatchConfig(9, humanPlayer = null), bytes,
                rememberedHuman = GoPlayer.WHITE, autoContinueEnabled = true,
                autoContinueMaxGames = 10, autoContinueGameNumber = 4)
            GoAiMatchFileRepository(file).save(match)
            val restored = checkNotNull(GoAiMatchFileRepository(file).load())
            assertTrue(restored.autoContinueEnabled); assertEquals(10, restored.autoContinueMaxGames)
            assertEquals(4, restored.autoContinueGameNumber); assertEquals(GoPlayer.WHITE, restored.rememberedHuman)
            assertArrayEquals(bytes, restored.copyBytes()); assertEquals(6, file.readBytes()[4].toInt())
        }
    }

    private suspend fun withFile(block: suspend (File) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "go-ai-match-qa-${UUID.randomUUID()}")
        assertTrue(directory.mkdirs())
        val file = File(directory, "match.bin")
        try { block(file) } finally {
            for (owned in listOf(file, File(directory, "match.bin.new"), File(directory, "match.bin.bak"))) {
                if (owned.exists()) assertTrue(owned.delete())
            }
            assertTrue(directory.delete())
        }
    }
}
