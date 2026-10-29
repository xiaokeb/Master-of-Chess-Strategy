package com.masterofchessstrategy.games.lanzhou_mahjong.data

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RoomLanzhouMahjongLearningRepositoryTest {
    @Test fun missingProgressIsNotAnAssumedCompletedTutorial() = runTest {
        val f = Fixture()
        assertNull(f.repository(StandardTestDispatcher(testScheduler)).load())
        assertFalse(snapshot(4).isCompleted)
        assertTrue(snapshot(10).isCompleted)
    }

    @Test fun acknowledgedRetryKeepsOriginalSnapshotAndDoesNotWriteTwice() = runTest {
        val f = Fixture()
        val repo = f.repository(StandardTestDispatcher(testScheduler))
        val request = LanzhouMahjongLearningAdvance(null, snapshot(1))
        assertEquals(request.after, repo.advance(request))
        assertEquals(request.after, repo.advance(request))
        assertEquals(1, f.dao.writes)
        assertEquals(request.after, repo.load())
    }

    @Test fun staleReaderCannotReplaceMoreRecentProgress() = runTest {
        val f = Fixture(snapshot(3).toLearningEntity())
        val repo = f.repository(StandardTestDispatcher(testScheduler))
        try {
            repo.advance(LanzhouMahjongLearningAdvance(snapshot(1), snapshot(2)))
            fail("Stale progress overwrote another writer")
        } catch (_: LanzhouMahjongLearningConflictException) { }
        assertEquals(snapshot(3), repo.load())
        assertEquals(0, f.dao.writes)
    }

    @Test fun sameCountWithDifferentTimestampIsNotAnIdenticalRetry() = runTest {
        val f = Fixture(snapshot(1, 10).toLearningEntity())
        try {
            f.repository(StandardTestDispatcher(testScheduler))
                .advance(LanzhouMahjongLearningAdvance(null, snapshot(1, 11)))
            fail("A conflicting first completion was replaced")
        } catch (_: LanzhouMahjongLearningConflictException) { }
        assertEquals(snapshot(1, 10).toLearningEntity(), f.dao.rows.single())
    }

    @Test fun readBackFailureRollsBackAndExactRequestCanBeRetried() = runTest {
        val original = snapshot(1).toLearningEntity()
        val f = Fixture(original)
        val repo = f.repository(StandardTestDispatcher(testScheduler))
        val request = LanzhouMahjongLearningAdvance(snapshot(1), snapshot(2))
        f.dao.failAfterWrite = true
        try { repo.advance(request); fail("Read-back failure was ignored") } catch (_: IOException) { }
        assertEquals(listOf(original), f.dao.rows)
        f.dao.failAfterWrite = false
        assertEquals(request.after, repo.advance(request))
        assertEquals(request.after, repo.load())
    }

    @Test fun cancellationAfterUpsertRollsBackAndPropagates() = runTest {
        val f = Fixture()
        f.dao.cancelAfterWrite = true
        try {
            f.repository(StandardTestDispatcher(testScheduler)).advance(LanzhouMahjongLearningAdvance(null, snapshot(1)))
            fail("Cancellation was swallowed")
        } catch (_: CancellationException) { }
        assertTrue(f.dao.rows.isEmpty())
    }

    @Test fun differentReadBackIsNotAcknowledgedAndRollsBackTheWrite() = runTest {
        val original = snapshot(1).toLearningEntity()
        val f = Fixture(original)
        f.dao.corruptWrittenTime = true
        try {
            f.repository(StandardTestDispatcher(testScheduler))
                .advance(LanzhouMahjongLearningAdvance(snapshot(1), snapshot(2)))
            fail("A different stored timestamp was acknowledged")
        } catch (_: IOException) { }
        assertEquals(listOf(original), f.dao.rows)
    }

    @Test fun damagedVersionCountTimeOrSingletonNeverGetsReplaced() = runTest {
        for (bad in listOf(
            LanzhouMahjongLearningProgressEntity(0, 1, 4, 10),
            LanzhouMahjongLearningProgressEntity(0, 2, 11, 10),
            LanzhouMahjongLearningProgressEntity(0, 2, 1, -1),
            LanzhouMahjongLearningProgressEntity(1, 2, 1, 10),
        )) {
            val f = Fixture(bad)
            val repo = f.repository(StandardTestDispatcher(testScheduler))
            try { repo.load(); fail("Bad progress accepted") } catch (_: IOException) { }
            try { repo.advance(LanzhouMahjongLearningAdvance(null, snapshot(1))); fail("Bad row overwritten") }
            catch (_: IOException) { }
            assertEquals(listOf(bad), f.dao.rows)
            assertEquals(0, f.dao.writes)
        }
    }

    @Test fun extraSingletonRowsAreNotSilentlyHidden() = runTest {
        val f = Fixture(snapshot(1).toLearningEntity())
        f.dao.rows += LanzhouMahjongLearningProgressEntity(1, 2, 2, 10)
        try { f.repository(StandardTestDispatcher(testScheduler)).load(); fail("Foreign singleton hidden") }
        catch (_: IOException) { }
        assertEquals(2, f.dao.rows.size)
    }

    @Test fun failedReplacementStillRetiresOldWriterWithoutRefillingProgress() = runTest {
        val f = Fixture(snapshot(1).toLearningEntity())
        val repo = f.repository(StandardTestDispatcher(testScheduler))
        try { f.gate.exclusive(replacing = true) { throw IOException("replacement failed") } } catch (_: IOException) { }
        try { repo.advance(LanzhouMahjongLearningAdvance(snapshot(1), snapshot(2))); fail("Retired writer saved") }
        catch (_: IOException) { }
        assertEquals(0, f.dao.writes)
        assertEquals(snapshot(1), f.repository(StandardTestDispatcher(testScheduler)).load())
    }

    @Test fun advanceCannotSkipLessonsRegressTimeOrWriteAfterTen() {
        for (operation in listOf<() -> Unit>(
            { LanzhouMahjongLearningAdvance(null, snapshot(2)) },
            { LanzhouMahjongLearningAdvance(snapshot(2), snapshot(1)) },
            { LanzhouMahjongLearningAdvance(snapshot(1, 11), snapshot(2, 10)) },
            { LanzhouMahjongLearningAdvance(snapshot(10), snapshot(10)) },
        )) assertThrows(IllegalArgumentException::class.java, operation)
    }

    private class Fixture(vararg initial: LanzhouMahjongLearningProgressEntity) {
        val dao = Rows(initial.toList())
        val gate = LanzhouMahjongStorageEpochGate()
        fun repository(dispatcher: kotlinx.coroutines.CoroutineDispatcher): RoomLanzhouMahjongLearningRepository =
            RoomLanzhouMahjongLearningRepository(dao, gate, gate.captureOwner(), dispatcher) { operation ->
                val before = dao.rows.toList()
                try { operation() } catch (failure: Throwable) { dao.rows = before; throw failure }
            }
    }

    private class Rows(var rows: List<LanzhouMahjongLearningProgressEntity>) : LanzhouMahjongLearningProgressDao {
        var writes = 0
        var failAfterWrite = false
        var cancelAfterWrite = false
        var corruptWrittenTime = false
        override suspend fun readBoundedRows(): List<LanzhouMahjongLearningProgressEntity> {
            if (writes > 0 && failAfterWrite) throw IOException("read back")
            if (writes > 0 && cancelAfterWrite) throw CancellationException("read back cancelled")
            return rows.toList()
        }
        override suspend fun upsert(row: LanzhouMahjongLearningProgressEntity) {
            writes++
            rows = listOf(if (corruptWrittenTime) row.copy(updatedAtEpochMillis = row.updatedAtEpochMillis + 1) else row)
        }
        override suspend fun deleteAll() { rows = emptyList() }
    }

    private fun snapshot(count: Int, time: Long = 42) = LanzhouMahjongLearningSnapshot(2, count, time)
}
