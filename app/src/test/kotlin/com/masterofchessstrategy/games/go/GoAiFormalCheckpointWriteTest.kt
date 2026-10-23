package com.masterofchessstrategy.games.go

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GoAiFormalCheckpointWriteTest {
    private val next = GoAiSavedMatch("2f4727ba-991c-4a19-9d08-4a37bfb44b5f", GoAiMatchConfig(9), byteArrayOf(1))

    @Test fun retirementDuringInitialLoadNeverStartsANewFormalCreation() = runTest {
        val release = CompletableDeferred<Unit>()
        val repo = Formal().apply { loadGate = release }
        var engines = 0
        val session = GoAiMatchSession(this, repo, GoAiMatchConfig(9), GoAiMatchSessionTest.Search(),
            worker = StandardTestDispatcher(testScheduler), engineFactory = { size, _, handicap ->
                engines++; GoAiMatchSessionTest.Rules(size, handicap)
            })
        runCurrent()
        assertEquals(1, repo.reads)
        session.beginRetirement()
        release.complete(Unit)
        session.closeAndJoin()
        assertTrue(session.state.value.closed)
        assertEquals(0, repo.creates)
        assertEquals(0, repo.saves)
        assertEquals(0, engines)
        assertNull(repo.value)
    }

    @Test fun explicitCreationDoesNotUseOrdinarySaveOrAddUnnecessaryRead() = runTest {
        val repo = Formal()
        saveGoAiCheckpoint(repo, null, next, freshStandard = true)
        assertEquals(1, repo.creates); assertEquals(0, repo.saves)
        assertEquals(0, repo.reads); assertEquals(0, repo.confirmations)
        assertTrue(repo.hasOrigin)
    }

    @Test fun lostCreationAcknowledgementRequiresBothCheckpointAndOrigin() = runTest {
        val repo = Formal().apply { afterCommit = IOException("lost ack") }
        saveGoAiCheckpoint(repo, null, next, freshStandard = true)
        assertEquals(1, repo.creates); assertEquals(1, repo.reads); assertEquals(1, repo.confirmations)
    }

    @Test fun identicalCheckpointWithoutOriginIsAnUncertainCreationNotSuccess() = runTest {
        val repo = Formal().apply { mintOrigin = false; afterCommit = IOException("lost source") }
        try { saveGoAiCheckpoint(repo, null, next, freshStandard = true); fail("missing origin accepted") }
        catch (_: GoAiCheckpointWriteUncertainException) { }
        assertEquals(1, repo.creates); assertEquals(0, repo.saves)
    }

    @Test fun unreadableCreationFactRetainsBothFailures() = runTest {
        val write = IOException("write acknowledgement")
        val read = IOException("source unavailable")
        val repo = Formal().apply { afterCommit = write; factFailure = read }
        try { saveGoAiCheckpoint(repo, null, next, freshStandard = true); fail("unknown source accepted") }
        catch (error: GoAiCheckpointWriteUncertainException) {
            assertSame(write, error.cause); assertSame(read, error.suppressed.single())
        }
    }

    @Test fun cancelledCreationDoesNotIssueASecondStorageOperation() = runTest {
        val cancellation = CancellationException("cancelled creation")
        val repo = Formal().apply { beforeCommit = cancellation }
        try { saveGoAiCheckpoint(repo, null, next, freshStandard = true); fail("cancellation hidden") }
        catch (error: CancellationException) { assertSame(cancellation, error) }
        assertEquals(1, repo.creates); assertEquals(0, repo.reads); assertEquals(0, repo.confirmations)
    }

    @Test fun ordinarySaveNeverMintsAnOriginEvenThroughTheFormalFacade() = runTest {
        val repo = Formal()
        saveGoAiCheckpoint(repo, null, next)
        assertEquals(0, repo.creates); assertEquals(1, repo.saves)
        assertFalse(repo.hasOrigin)
    }

    private class Formal : GoAiFormalMatchRepository {
        var value: GoAiSavedMatch? = null
        var hasOrigin = false; var mintOrigin = true
        var creates = 0; var saves = 0; var reads = 0; var confirmations = 0
        var beforeCommit: Exception? = null; var afterCommit: Exception? = null; var factFailure: Exception? = null
        var loadGate: CompletableDeferred<Unit>? = null
        override suspend fun load(): GoAiSavedMatch? { reads++; loadGate?.await(); return value }
        override suspend fun save(match: GoAiSavedMatch) { saves++; value = match }
        override suspend fun createStandardMatch(match: GoAiSavedMatch) {
            creates++; beforeCommit?.let { throw it }
            value = match; hasOrigin = mintOrigin
            afterCommit?.let { throw it }
        }
        override suspend fun confirmsStandardCreation(match: GoAiSavedMatch): Boolean {
            confirmations++; factFailure?.let { throw it }
            return hasOrigin
        }
    }
}
