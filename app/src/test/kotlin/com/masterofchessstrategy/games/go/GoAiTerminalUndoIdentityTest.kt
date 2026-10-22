package com.masterofchessstrategy.games.go

import com.masterofchessstrategy.engine.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class GoAiTerminalUndoIdentityTest {
    private val terminal = byteArrayOf(9, 0, 0, 1, 81, 81, 82, 83)
    private fun saved(reserved: Boolean = false) = GoAiSavedMatch("finished-parent",
        GoAiMatchConfig(9, humanPlayer = GoPlayer.WHITE), terminal, undoUsed = 2, hintUsed = 3,
        completionReserved = reserved, rememberedHuman = GoPlayer.WHITE, automaticUsed = true,
        openingBytes = byteArrayOf(9, 0, 0, 1))

    @Test fun terminalUndoForksOnlyTheIdentityAndPreservesPolicyAndOpening() = runTest {
        val repository = Repository(saved())
        val session = session(repository, this)
        try {
            runCurrent()
            assertTrue(session.undo()); runCurrent()
            val child = repository.value
            assertNotEquals("finished-parent", child.matchId)
            assertEquals(child.matchId, session.state.value.matchId)
            assertEquals(GoPhase.SCORING, session.state.value.game.phase)
            assertEquals(3, child.undoUsed); assertEquals(3, child.hintUsed)
            assertTrue(child.automaticUsed); assertEquals(GoPlayer.WHITE, child.rememberedHuman)
            assertEquals(saved().config, child.config)
            assertArrayEquals(saved().copyOpeningBytes(), child.copyOpeningBytes())
            assertFalse(child.completionReserved)
        } finally { session.closeAndJoin() }
    }

    @Test fun failedForkWriteKeepsParentIdentityAndQuotaUntilSuccessfulRetry() = runTest {
        val repository = Repository(saved()).apply { fail = true }
        val session = session(repository, this)
        try {
            runCurrent(); assertTrue(session.undo()); runCurrent()
            assertEquals("finished-parent", repository.value.matchId)
            assertEquals("finished-parent", session.state.value.matchId)
            assertArrayEquals(terminal, repository.value.copyBytes())
            assertEquals(2, session.state.value.undoUsed)
            assertEquals(GoPhase.FINISHED, session.state.value.game.phase)
            repository.fail = false
            assertTrue(session.undo()); runCurrent()
            assertNotEquals("finished-parent", repository.value.matchId)
            assertEquals(3, session.state.value.undoUsed)
        } finally { session.closeAndJoin() }
    }

    @Test fun ordinaryUndoDoesNotForkAnUnfinishedMatch() = runTest {
        val repository = Repository(GoAiSavedMatch("ongoing", GoAiMatchConfig(9), byteArrayOf(9, 0, 0, 1)))
        val session = session(repository, this)
        try {
            runCurrent(); assertTrue(session.undo()); runCurrent()
            assertEquals("ongoing", repository.value.matchId)
            assertEquals("ongoing", session.state.value.matchId)
            assertEquals(1, repository.value.undoUsed)
        } finally { session.closeAndJoin() }
    }

    @Test fun reservedCompletedResultRemainsImmutableAndCannotForkViaUndo() = runTest {
        val repository = Repository(saved(reserved = true))
        val session = session(repository, this)
        try {
            runCurrent(); assertFalse(session.undo()); runCurrent()
            assertEquals("finished-parent", repository.value.matchId)
            assertEquals(0, repository.writes)
        } finally { session.closeAndJoin() }
    }

    @Test fun aLostAcknowledgementAfterCommitPublishesTheDurableChild() = runTest {
        val repository = Repository(saved()).apply { throwAfterCommit = true }
        val session = session(repository, this)
        try {
            runCurrent(); assertTrue(session.undo()); runCurrent()
            assertNotEquals("finished-parent", repository.value.matchId)
            assertEquals(repository.value.matchId, session.state.value.matchId)
            assertEquals(3, session.state.value.undoUsed)
            assertNull(session.state.value.game.error)
            assertFalse(session.state.value.closed)
        } finally { session.closeAndJoin() }
    }

    @Test fun unreadableAcknowledgementRetiresTheOldPageAndFreshLoadRecoversTheActualChild() = runTest {
        val repository = Repository(saved()).apply { throwAfterCommit = true; failReadAfterCommit = true }
        val session = session(repository, this)
        try {
            runCurrent(); assertTrue(session.undo()); runCurrent()
            assertTrue(session.state.value.closed)
            assertFalse(session.state.value.game.sessionReady)
            assertTrue(session.state.value.game.error!!.contains("重新打开"))
            assertFalse(session.undo()); assertFalse(session.restart())
            assertEquals(1, repository.writes)
            assertNotEquals("finished-parent", repository.value.matchId)
        } finally { session.closeAndJoin() }
        repository.failReadAfterCommit = false
        val restored = session(repository, this)
        try {
            runCurrent()
            assertEquals(repository.value.matchId, restored.state.value.matchId)
            assertEquals(3, restored.state.value.undoUsed)
            assertEquals(GoPhase.SCORING, restored.state.value.game.phase)
        } finally { restored.closeAndJoin() }
    }

    private fun session(repository: Repository, scope: TestScope) = GoAiMatchSession(scope, repository,
        GoAiMatchConfig(9), GoAiMatchSessionTest.Search(), worker = StandardTestDispatcher(scope.testScheduler),
        engineFactory = { size, _, handicap -> GoAiMatchSessionTest.Rules(size, handicap) })

    private class Repository(var value: GoAiSavedMatch) : GoAiMatchRepository {
        var fail = false; var writes = 0; var throwAfterCommit = false; var failReadAfterCommit = false
        override suspend fun load(): GoAiSavedMatch {
            if (writes > 0 && failReadAfterCommit) throw IOException("read failed")
            return value
        }
        override suspend fun save(match: GoAiSavedMatch) {
            writes++; if (fail) throw IOException("write failed")
            value = match
            if (throwAfterCommit) throw IOException("lost acknowledgement")
        }
    }
}
