package com.masterofchessstrategy.games.go.data

import com.masterofchessstrategy.engine.*
import com.masterofchessstrategy.games.go.*
import org.junit.Assert.*
import org.junit.Test

/** Transaction fixtures only; actual native rule/history admission has separate device cases. */
class GoAiCheckpointAdmissionTest {
    private val owners = mutableListOf<GoAiCheckpointAdmissionRulesFixture>()
    private val factory: (Int, Int, Int) -> GoRecordEngine = { size, _, handicap ->
        val rules = GoAiCheckpointAdmissionRulesFixture(size, handicap).also(owners::add)
        object : GoRecordEngine by rules {
            override fun finalScore(): GoAreaScore? = if (phase == GoPhase.FINISHED)
                GoAreaScore(size * size * 4, 0, blackKomiQuarters, GameResult.FIRST_PLAYER_WIN, handicapStones * 4)
                else null
        }
    }
    private fun match(bytes: ByteArray = byteArrayOf(9, 0, 0), opening: ByteArray? = null,
        reserved: Boolean = false, config: GoAiMatchConfig = GoAiMatchConfig(9)) =
        GoAiSavedMatch("match/一", config, bytes, completionReserved = reserved, openingBytes = opening)

    @Test fun ongoingAdmissionOwnsBytesAndPreservesEveryPolicyField() {
        val current = byteArrayOf(9, 5, 0, 1)
        val opening = byteArrayOf(9, 5, 0)
        val source = GoAiSavedMatch("auto", GoAiMatchConfig(9, 5, null, GoAiDifficulty.HARD), current,
            undoUsed = 1, hintUsed = 1, rememberedHuman = GoPlayer.WHITE, automaticUsed = true,
            openingBytes = opening, autoContinueEnabled = true, autoContinueMaxGames = 7, autoContinueGameNumber = 3)
        current.fill(127); opening.fill(127)
        val admitted = admitGoAiCheckpoint(source, factory)
        val saved = admitted.match()
        assertEquals(GoPhase.PLAYING, admitted.phase)
        assertEquals(GameResult.ONGOING, admitted.result)
        assertEquals(2, admitted.moveCount)
        assertEquals(1, admitted.openingEventCount)
        assertEquals(source.config, saved.config)
        assertEquals(1, saved.undoUsed); assertEquals(1, saved.hintUsed)
        assertEquals(GoPlayer.WHITE, saved.rememberedHuman); assertTrue(saved.automaticUsed)
        assertTrue(saved.autoContinueEnabled); assertEquals(7, saved.autoContinueMaxGames); assertEquals(3, saved.autoContinueGameNumber)
        val copy = saved.copyBytes(); copy.fill(127)
        assertArrayEquals(byteArrayOf(9, 5, 0, 1), admitted.match().copyBytes())
        assertArrayEquals(byteArrayOf(9, 5, 0), saved.copyOpeningBytes())
        assertTrue(owners.all { it.closed })
    }

    @Test fun pendingScoreAndOneConfirmationAreNotFinishedAndCannotBeReserved() {
        for (bytes in listOf(byteArrayOf(9, 0, 81, 81), byteArrayOf(9, 0, 81, 81, 82))) {
            val admitted = admitGoAiCheckpoint(match(bytes), factory)
            assertEquals(GoPhase.SCORING, admitted.phase)
            assertEquals(GameResult.ONGOING, admitted.result)
            rejected { admitGoAiCheckpoint(match(bytes, reserved = true), factory) }
        }
        assertTrue(owners.all { it.closed })
    }

    @Test fun agreedTerminalHistoryCanReserveButDoesNotCountConfirmationsAsMoves() {
        val admitted = admitGoAiCheckpoint(match(byteArrayOf(9, 0, 0, 1, 81, 81, 82, 83), reserved = true), factory)
        assertEquals(GoPhase.FINISHED, admitted.phase)
        assertEquals(GameResult.FIRST_PLAYER_WIN, admitted.result)
        assertEquals(4, admitted.moveCount)
        assertEquals(6, admitted.eventCount)
        assertTrue(admitted.match().completionReserved)
        assertTrue(owners.all { it.closed })
    }

    @Test fun identicalIdentityKeepsItsArchiveKeyAcrossCheckpointAndModeChanges() {
        val first = admitGoAiCheckpoint(match(), factory)
        val second = admitGoAiCheckpoint(GoAiSavedMatch("match/一", GoAiMatchConfig(9, humanPlayer = null),
            byteArrayOf(9, 0, 0, 1), automaticUsed = true), factory)
        assertEquals(first.recordId, second.recordId)
        assertTrue(first.recordId.matches(Regex("[0-9a-f]{64}")))
        assertNotEquals(first.recordId, admitGoAiCheckpoint(GoAiSavedMatch("other", GoAiMatchConfig(9), byteArrayOf(9, 0)), factory).recordId)
        assertNotEquals(admitGoAiCheckpoint(GoAiSavedMatch("match", GoAiMatchConfig(9), byteArrayOf(9, 0)), factory).recordId,
            GoSessionIdentity("match", GoSessionOrigin.LOCAL).recordId)
    }

    @Test fun alteredOpeningHistoryAndNonPlayingOpeningAreRejected() {
        rejected { admitGoAiCheckpoint(match(byteArrayOf(9, 0, 0, 2), byteArrayOf(9, 0, 0, 1)), factory) }
        rejected { admitGoAiCheckpoint(match(byteArrayOf(9, 0, 0), byteArrayOf(9, 0, 0, 1)), factory) }
        rejected { admitGoAiCheckpoint(match(byteArrayOf(9, 0, 81, 81), byteArrayOf(9, 0, 81, 81)), factory) }
        assertTrue(owners.all { it.closed })
    }

    @Test fun mismatchedBoardAndHandicapAreRejectedAndOwnersClosed() {
        rejected { admitGoAiCheckpoint(match(byteArrayOf(13, 0)), factory) }
        rejected { admitGoAiCheckpoint(match(byteArrayOf(9, 5)), factory) }
        assertTrue(owners.all { it.closed })
    }

    @Test fun terminalWithoutAFinalScoreOrWithAConflictingResultIsRejected() {
        val terminal = match(byteArrayOf(9, 0, 81, 81, 82, 83), reserved = true)
        rejected { admitGoAiCheckpoint(terminal) { size, _, handicap ->
            GoAiCheckpointAdmissionRulesFixture(size, handicap).also(owners::add)
        } }
        rejected { admitGoAiCheckpoint(terminal) { size, komi, handicap ->
            val delegate = factory(size, komi, handicap)
            object : GoRecordEngine by delegate {
                override fun finalScore() = GoAreaScore(0, 324, 15, GameResult.SECOND_PLAYER_WIN, 0)
            }
        } }
        assertTrue(owners.all { it.closed })
    }

    @Test fun invalidUnicodeIsRejectedBeforeCreatingAnEngine() {
        try {
            admitGoAiCheckpoint(GoAiSavedMatch("\ud800", GoAiMatchConfig(9), byteArrayOf(9, 0)), factory)
            fail("Invalid Unicode accepted")
        } catch (_: java.nio.charset.CharacterCodingException) { }
        assertTrue(owners.isEmpty())
    }

    @Test fun restoreExceptionStillClosesTheCandidate() {
        try {
            admitGoAiCheckpoint(match()) { size, komi, handicap ->
                val delegate = factory(size, komi, handicap)
                object : GoRecordEngine by delegate {
                    override fun restore(data: ByteArray): RestoreResult = error("restore fault")
                }
            }
            fail("Restore fault swallowed")
        } catch (_: IllegalStateException) { }
        assertEquals(1, owners.size)
        assertTrue(owners.single().closed)
    }

    private fun rejected(block: () -> Unit) {
        try { block(); fail("Invalid AI checkpoint accepted") } catch (_: IllegalArgumentException) { }
    }
}
