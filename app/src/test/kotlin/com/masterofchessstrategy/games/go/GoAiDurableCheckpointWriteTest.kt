package com.masterofchessstrategy.games.go

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class GoAiDurableCheckpointWriteTest {
    private val previous = GoAiSavedMatch("id", GoAiMatchConfig(9), byteArrayOf(1))
    private val next = GoAiSavedMatch("id", GoAiMatchConfig(9), byteArrayOf(2), hintUsed = 1)

    @Test fun anAcknowledgedWriteDoesNotAddAnUnnecessaryRead() = runTest {
        val repository = Repository(previous)
        saveGoAiCheckpoint(repository, previous, next)
        assertSame(next, repository.value)
        assertEquals(0, repository.reads)
        assertEquals(1, repository.writes)
    }

    @Test fun exactRereadResolvesALostAcknowledgementWithoutASecondWrite() = runTest {
        val repository = Repository(previous).apply { afterCommit = IOException("lost ack") }
        saveGoAiCheckpoint(repository, previous, next)
        assertSame(next, repository.value)
        assertEquals(1, repository.reads); assertEquals(1, repository.writes)
    }

    @Test fun aConfirmedPreviousEnvelopeRethrowsTheOriginalWriteFailure() = runTest {
        val failure = IOException("before write")
        val repository = Repository(previous).apply { beforeCommit = failure }
        try { saveGoAiCheckpoint(repository, previous, next); fail("Rejected write accepted") }
        catch (error: IOException) { assertSame(failure, error) }
        assertSame(previous, repository.value)
    }

    @Test fun aKnownEmptyInitialStoreAlsoKeepsItsOriginalFailure() = runTest {
        val failure = IOException("initial write")
        val repository = Repository(null).apply { beforeCommit = failure }
        try { saveGoAiCheckpoint(repository, null, next); fail("Empty-store failure accepted") }
        catch (error: IOException) { assertSame(failure, error) }
        assertNull(repository.value)
    }

    @Test fun equalityIncludesAuxiliaryFieldsNotOnlyIdentityAndNativeBytes() = runTest {
        val conflicting = GoAiSavedMatch(next.matchId, next.config, next.copyBytes(), hintUsed = 2)
        val repository = Repository(conflicting).apply { beforeCommit = IOException("write refused") }
        try { saveGoAiCheckpoint(repository, previous, next); fail("Different hint quota accepted") }
        catch (_: GoAiCheckpointWriteUncertainException) { }
        assertSame(conflicting, repository.value)
        assertEquals(1, repository.writes)
    }

    @Test fun anUnreadableResultPreservesBothWriteAndReadCauses() = runTest {
        val writeFailure = IOException("lost ack")
        val readFailure = IOException("read failed")
        val repository = Repository(previous).apply { afterCommit = writeFailure; this.readFailure = readFailure }
        try { saveGoAiCheckpoint(repository, previous, next); fail("Unknown commit accepted") }
        catch (error: GoAiCheckpointWriteUncertainException) {
            assertSame(writeFailure, error.cause)
            assertSame(readFailure, error.suppressed.single())
        }
    }

    @Test fun cancellationIsPropagatedWithoutIssuingAnotherStorageOperation() = runTest {
        val cancellation = CancellationException("cancelled")
        val repository = Repository(previous).apply { beforeCommit = cancellation }
        try { saveGoAiCheckpoint(repository, previous, next); fail("Cancellation swallowed") }
        catch (error: CancellationException) { assertSame(cancellation, error) }
        assertEquals(0, repository.reads)
    }

    private class Repository(var value: GoAiSavedMatch?) : GoAiMatchRepository {
        var beforeCommit: Exception? = null; var afterCommit: Exception? = null; var readFailure: Exception? = null
        var reads = 0; var writes = 0
        override suspend fun load(): GoAiSavedMatch? { reads++; readFailure?.let { throw it }; return value }
        override suspend fun save(match: GoAiSavedMatch) {
            writes++; beforeCommit?.let { throw it }; value = match; afterCommit?.let { throw it }
        }
    }
}
