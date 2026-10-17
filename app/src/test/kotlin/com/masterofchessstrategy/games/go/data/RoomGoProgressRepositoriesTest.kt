package com.masterofchessstrategy.games.go.data

import com.masterofchessstrategy.games.go.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.time.LocalDate
import java.util.zip.CRC32

/** Pure transaction doubles, not SQL/SharedPreferences/Native execution. */
@OptIn(ExperimentalCoroutinesApi::class)
class RoomGoProgressRepositoriesTest {
    private val store = MemoryStore()
    private val lessons = GoLearningLibrary.lessons.map { it.id }
    private val puzzles = GoEndgameLibrary.puzzles.map { it.id }
    private var legacyReads = 0
    private var legacyProgress = GoLearningProgress()
    private val legacy = GoLearningLegacySource { legacyReads++; legacyProgress }
    private fun learning() = RoomGoLearningProgressRepository(store, legacy, clock = { 10 })

    @Test fun detailedLegacyMigrationRetainsAllIdentitiesAndLastLessonExactlyOnce() = runTest {
        legacyProgress = GoLearningProgress(setOf(lessons[0], lessons[7]), lessons[7])
        val repository = learning()
        assertEquals(legacyProgress, repository.load())
        assertTrue(GoProgressKind.LEARNING in store.handled)
        legacyProgress = GoLearningProgress(lessons.toSet(), lessons.last())
        assertEquals(setOf(lessons[0], lessons[7]), repository.load().completedLessonIds)
        assertEquals(lessons[7], repository.load().lastLessonId)
        assertEquals(1, legacyReads)
    }

    @Test fun staleSavesMergeCompletedIdsAndLastSelectionIsExplicit() = runTest {
        val first = learning()
        val second = learning()
        val stale = first.load()
        second.save(GoLearningProgress(setOf(lessons[5]), lessons[5]))
        first.save(stale.copy(completedLessonIds = setOf(lessons[0]), lastLessonId = lessons[0]))
        val loaded = first.load()
        assertEquals(setOf(lessons[0], lessons[5]), loaded.completedLessonIds)
        assertEquals(lessons[0], loaded.lastLessonId)
        assertFalse(loaded.isCompleted)
    }

    @Test fun tutorialIsCompleteOnlyWhenAllTenDistinctLessonsArePersisted() = runTest {
        val repository = learning()
        for ((index, id) in lessons.withIndex()) {
            repository.save(GoLearningProgress(setOf(id), id))
            assertEquals(index == lessons.lastIndex, repository.load().isCompleted)
        }
        assertEquals(10, repository.load().completedLessonIds.size)
        assertEquals(1, legacyReads)
    }

    @Test fun sourceFailureLeavesRowsAndFenceAbsentAndCanBeRecoveredLater() = runTest {
        val repository = RoomGoLearningProgressRepository(store,
            GoLearningLegacySource { throw IOException("invalid disk image") })
        expectFailure { repository.load() }
        assertTrue(store.rows.isEmpty())
        assertTrue(store.handled.isEmpty())
        assertEquals(GoLearningProgress(), learning().load())
    }

    @Test fun failedMigrationWriteOrFenceRollsBackAndRetainsTheSource() = runTest {
        legacyProgress = GoLearningProgress(lessons.take(4).toSet(), lessons[3])
        for (step in listOf("write", "marker")) {
            store.failAt = step
            expectFailure { learning().load() }
            assertTrue(store.rows.isEmpty())
            assertTrue(store.handled.isEmpty())
            assertEquals(lessons.take(4).toSet(), legacyProgress.completedLessonIds)
        }
        store.failAt = null
        assertEquals(legacyProgress, learning().load())
    }

    @Test fun clearPreventsLegacyResurrectionAndDoesNotResetOtherKind() = runTest {
        val learning = learning()
        learning.save(GoLearningProgress(lessons.toSet(), lessons.last()))
        val endgame = RoomGoEndgameProgressRepository(store)
        endgame.complete(puzzles[0])
        learning.clear()
        assertEquals(GoLearningProgress(), learning.load())
        assertEquals(setOf(puzzles[0]), endgame.load().completedIds)
        assertEquals(1, legacyReads)
    }

    @Test fun existingFenceWithNoRowNeverCallsEitherLegacySource() = runTest {
        store.handled.addAll(GoProgressKind.entries)
        val learning = RoomGoLearningProgressRepository(store,
            GoLearningLegacySource { error("sealed learning source was read") })
        val endgame = RoomGoEndgameProgressRepository(store, GoEndgameLegacySource { error("sealed GEP source was read") })
        assertFalse(learning.load().isCompleted)
        assertTrue(endgame.load().completedIds.isEmpty())
        assertTrue(store.rows.isEmpty())
    }

    @Test fun invalidStoredPayloadIsPreservedWithoutFallbackOrSealing() = runTest {
        val bad = byteArrayOf(1, 2, 3)
        store.rows[GoProgressKind.LEARNING] = bad.copyOf()
        expectFailure { learning().load() }
        expectFailure { learning().save(GoLearningProgress()) }
        assertArrayEquals(bad, store.rows[GoProgressKind.LEARNING])
        assertTrue(store.handled.isEmpty())
        assertEquals(0, legacyReads)
    }

    @Test fun callerMutationWhileWriteWaitsCannotAlterTheSnapshot() = runTest {
        val set = mutableSetOf(lessons[0])
        val progress = GoLearningProgress(set, lessons[0])
        val gate = CompletableDeferred<Unit>()
        store.writeGate = gate
        val repository = learning()
        val pending = launch { repository.save(progress) }
        runCurrent()
        set.addAll(lessons)
        gate.complete(Unit)
        pending.join()
        assertEquals(setOf(lessons[0]), repository.load().completedLessonIds)
    }

    @Test fun cancellationDoesNotSealOrPublishPartiallyWrittenProgress() = runTest {
        store.writeGate = CompletableDeferred()
        val pending = launch { learning().save(GoLearningProgress(lessons.toSet())) }
        runCurrent()
        pending.cancel(); pending.join()
        assertTrue(store.rows.isEmpty())
        assertTrue(store.handled.isEmpty())
    }

    @Test fun endgameUsesCurrentTransactionAndKeepsContiguousUnlocksAndDailySelection() = runTest {
        val first = RoomGoEndgameProgressRepository(store)
        val second = RoomGoEndgameProgressRepository(store)
        first.complete(puzzles[0])
        second.complete(puzzles[1])
        first.complete(puzzles[0])
        assertEquals(puzzles.take(2).toSet(), first.load().completedIds)
        expectFailure { first.complete(puzzles[4]) }
        val date = LocalDate.of(2026, 10, 16)
        val daily = GoEndgameChallenges.daily(first.load(), date)
        val selected = first.selectDaily(date, daily.id)
        assertEquals(date, selected.dailyDate)
        assertEquals(daily.id, selected.dailyPuzzleId)
        assertEquals(GoEndgameChallengeMode.DAILY, second.load().lastMode)
    }

    @Test fun historicalGepV1NormalizesWithoutLosingPrefixOrReturnPosition() = runTest {
        val payload = "GEP1\n1\n${puzzles[1]}\n${puzzles[0]}\n"
        val crc = CRC32().apply { update(payload.toByteArray()) }.value.toString(16).padStart(8, '0')
        val historical = "$payload$crc\n".toByteArray()
        val normalized = GoProgressKind.ENDGAME.normalize(historical)
        val progress = GoEndgameProgressCodec.decode(normalized)!!
        assertEquals(setOf(puzzles[0]), progress.completedIds)
        assertEquals(puzzles[1], progress.lastPuzzleId)
        assertEquals("2", normalized.toString(Charsets.UTF_8).split('\n')[1])
    }

    @Test fun failedEndgameSelectionDoesNotOverwriteItsLastValidSnapshot() = runTest {
        val repository = RoomGoEndgameProgressRepository(store)
        repository.complete(puzzles[0])
        val before = store.rows.getValue(GoProgressKind.ENDGAME).copyOf()
        expectFailure { repository.select(puzzles.last()) }
        assertArrayEquals(before, store.rows[GoProgressKind.ENDGAME])
    }

    @Test fun kindPayloadsCannotBeSwappedOrUsedWithAnInvalidClock() = runTest {
        expectFailure { GoProgressKind.LEARNING.normalize(GoProgressKind.ENDGAME.emptyBytes()) }
        expectFailure { GoProgressKind.ENDGAME.normalize(GoProgressKind.LEARNING.emptyBytes()) }
        val repository = RoomGoLearningProgressRepository(store, clock = { -1 })
        expectFailure { repository.save(GoLearningProgress()) }
        assertTrue(store.rows.isEmpty())
        assertTrue(store.handled.isEmpty())
    }

    private suspend fun expectFailure(block: suspend () -> Unit) {
        try { block(); fail("expected rejection") } catch (_: IllegalArgumentException) { } catch (_: IOException) { }
    }

    private class MemoryStore : GoProgressStore {
        private val mutex = Mutex()
        val rows = mutableMapOf<GoProgressKind, ByteArray>()
        val handled = mutableSetOf<GoProgressKind>()
        var failAt: String? = null
        var writeGate: CompletableDeferred<Unit>? = null
        override suspend fun <T> transaction(block: suspend GoProgressTransaction.() -> T): T = mutex.withLock {
            val oldRows = rows.mapValues { it.value.copyOf() }
            val oldHandled = handled.toSet()
            try { block(object : GoProgressTransaction {
                override suspend fun read(kind: GoProgressKind) = rows[kind]?.copyOf()
                override suspend fun write(kind: GoProgressKind, bytes: ByteArray, updatedAtEpochMillis: Long) {
                    writeGate?.await(); fault("write"); rows[kind] = bytes.copyOf()
                }
                override suspend fun clear(kind: GoProgressKind) { rows.remove(kind) }
                override suspend fun legacyHandled(kind: GoProgressKind) = kind in handled
                override suspend fun markLegacyHandled(kind: GoProgressKind) { fault("marker"); handled.add(kind) }
            }) } catch (failure: Throwable) {
                rows.clear(); rows.putAll(oldRows)
                handled.clear(); handled.addAll(oldHandled)
                throw failure
            }
        }
        private fun fault(step: String) { if (failAt == step) throw IOException("injected $step failure") }
    }
}
