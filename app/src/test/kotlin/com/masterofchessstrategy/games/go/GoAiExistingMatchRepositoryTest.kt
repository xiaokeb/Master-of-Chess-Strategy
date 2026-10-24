package com.masterofchessstrategy.games.go

import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class GoAiExistingMatchRepositoryTest {
    private fun match(id: String) = GoAiSavedMatch(id, GoAiMatchConfig(9), byteArrayOf(1))
    private class Source(var active: GoAiSavedMatch? = null) : GoAiFormalMatchRepository {
        var saves = 0
        var creates = 0
        var restarts = 0
        override suspend fun load() = active
        override suspend fun save(match: GoAiSavedMatch) { saves++; active = match }
        override suspend fun createStandardMatch(match: GoAiSavedMatch) { creates++; active = match }
        override suspend fun restartStandardMatch(match: GoAiSavedMatch, expectedActive: GoAiSavedMatch) {
            check(active === expectedActive)
            restarts++; active = match
        }
        override suspend fun confirmsStandardCreation(match: GoAiSavedMatch) = active === match
    }

    @Test fun missingActiveIsAnErrorAndDoesNotCreateOrSave() = runTest {
        val source = Source()
        val resumed = GoAiExistingMatchRepository(source, "expected")
        try { resumed.load(); fail("Missing active must not become a default new game") }
        catch (_: IOException) { }
        assertEquals(0, source.saves)
        assertEquals(0, source.creates)
    }

    @Test fun differentInitialIdentityIsRejectedWithoutChangingTheActiveMatch() = runTest {
        val source = Source(match("other"))
        val resumed = GoAiExistingMatchRepository(source, "expected")
        try { resumed.load(); fail("Changed active identity must be selected again") }
        catch (_: IOException) { }
        assertEquals("other", source.active?.matchId)
        assertEquals(0, source.saves)
    }

    @Test fun successfulResumeKeepsRealConfigAndExplicitRestartStillUsesFormalCreation() = runTest {
        val initial = match("expected")
        val source = Source(initial)
        val resumed = GoAiExistingMatchRepository(source, initial.matchId)
        assertSame(initial, resumed.load())
        val restarted = match("restart")
        resumed.createStandardMatch(restarted)
        assertTrue(resumed.confirmsStandardCreation(restarted))
        assertSame(restarted, resumed.load())
        assertEquals(1, source.creates)
        resumed.save(restarted)
        assertEquals(1, source.saves)
        val second = match("second-restart")
        resumed.restartStandardMatch(second, restarted)
        assertEquals(1, source.restarts)
        assertSame(second, resumed.load())
        source.active = null
        try { resumed.load(); fail("Absence must remain an error after a prior resume") }
        catch (_: IOException) { }
    }
}
