package com.masterofchessstrategy.games.go

import com.masterofchessstrategy.engine.*
import org.junit.Assert.*
import org.junit.Test

class GoAiOpeningTest {
    @Test fun canonicalOpeningKeepsFullHistorySideSetupAndIndependentCopies() {
        for (size in listOf(9, 13, 19)) for (handicap in listOf(0, 5)) {
            val engines = mutableListOf<GoAiMatchSessionTest.Rules>()
            val factory = factory(engines)
            val bytes = byteArrayOf(size.toByte(), handicap.toByte(), 0, 1, 2)
            val opening = GoAiOpening.fromSnapshot(bytes, factory)
            val expected = bytes.copyOf(); bytes[2] = 4
            assertEquals(size, opening.boardSize); assertEquals(handicap, opening.handicapStones)
            assertEquals(3, opening.eventCount)
            assertEquals(if (handicap == 0) GoPlayer.WHITE else GoPlayer.BLACK, opening.currentPlayer)
            assertEquals(if (handicap == 0) 15 else 0, opening.blackKomiQuarters)
            assertArrayEquals(expected, opening.copyBytes())
            val detached = opening.copyBytes(); detached[3] = 4
            assertArrayEquals(expected, opening.copyBytes())
            assertTrue(engines.all { it.closed })
            assertEquals(GoAiMatchConfig(size, handicap, null, GoAiDifficulty.MASTER), opening.config(null, GoAiDifficulty.MASTER))
        }
    }

    @Test fun eachRestartCandidateIsIndependentAndReturnsToExactlyTheEndpoint() {
        val engines = mutableListOf<GoAiMatchSessionTest.Rules>(); val factory = factory(engines)
        val original = byteArrayOf(13, 5, 0, 1)
        val opening = GoAiOpening.fromSnapshot(original, factory)
        opening.createEngine(factory).use {
            assertEquals(ActionResult.Accepted, it.apply(GoAction.Place(BoardPosition(2, 0))))
            opening.checkContinuation(it)
            assertEquals(3, it.eventHistory().size)
        }
        opening.createEngine(factory).use {
            assertArrayEquals(original, it.serialize()); assertEquals(2, it.eventHistory().size)
            assertEquals(GoStone.EMPTY, it.stoneAt(BoardPosition(2, 0)))
        }
        assertTrue(engines.all { it.closed })
    }

    @Test fun alteredPrefixShortHistoryAndChangedRulesCannotClaimTheSameOpening() {
        val opening = GoAiOpening.fromSnapshot(byteArrayOf(9, 0, 0, 1), factory())
        for (bytes in listOf(byteArrayOf(9, 0, 0), byteArrayOf(9, 0, 0, 2), byteArrayOf(9, 5, 0, 1))) {
            GoAiMatchSessionTest.Rules(9, 0).use {
                it.restore(bytes)
                expectInvalid { opening.checkContinuation(it) }
            }
        }
        GoAiMatchSessionTest.Rules(13, 0).use { expectInvalid { opening.checkContinuation(it) } }
    }

    @Test fun agreementAndTerminalEndpointsAreRejectedAndDisposed() {
        for (bytes in listOf(byteArrayOf(9, 0, 81, 81), byteArrayOf(9, 0, 81, 81, 82, 83))) {
            val engines = mutableListOf<GoAiMatchSessionTest.Rules>()
            expectInvalid { GoAiOpening.fromSnapshot(bytes, factory(engines)) }
            assertTrue(engines.all { it.closed })
        }
    }

    @Test fun invalidOrOversizedSnapshotsNeverLeaveCandidatesOpen() {
        val engines = mutableListOf<GoAiMatchSessionTest.Rules>(); val factory = factory(engines)
        expectInvalid { GoAiOpening.fromSnapshot(byteArrayOf(), factory) }
        expectInvalid { GoAiOpening.fromSnapshot(ByteArray(1_000_001), factory) }
        assertTrue(engines.isEmpty())
        expectInvalid { GoAiOpening.fromSnapshot(byteArrayOf(11, 0), factory) }
        assertEquals(3, engines.size); assertTrue(engines.all { it.closed })
        expectInvalid { GoAiOpening.fromSnapshot(byteArrayOf(9, 1), factory) }
        assertTrue(engines.all { it.closed })
    }

    @Test fun sgfUsesRuleParserAndCanonicalNativeHistoryRatherThanDiagramOnly() {
        val closed = mutableListOf<GoAiMatchSessionTest.Rules>()
        val sgfFactory: (Int, Int, Int) -> GoRecordEngine = { size, _, handicap ->
            val rules = GoAiMatchSessionTest.Rules(size, handicap).also(closed::add)
            object : GoRecordEngine by rules {
                override fun restoreSgf(text: String) = if (size == 13 && text == "source-sgf")
                    rules.restore(byteArrayOf(13, 5, 0, 1)) else RestoreResult.Rejected(EngineError.CORRUPTED_DATA)
            }
        }
        val opening = GoAiOpening.fromSgf("source-sgf", sgfFactory)
        assertArrayEquals(byteArrayOf(13, 5, 0, 1), opening.copyBytes())
        assertEquals(2, opening.eventCount); assertTrue(closed.all { it.closed })
    }

    @Test fun malformedUnicodeAndOversizedSgfAreRejectedBeforeAnyNativeConstruction() {
        var calls = 0
        val factory: (Int, Int, Int) -> GoRecordEngine = { _, _, _ -> calls++; error("Should not create Native") }
        expectInvalid { GoAiOpening.fromSgf("  ", factory) }
        expectInvalid { GoAiOpening.fromSgf("围".repeat(333_334), factory) }
        try { GoAiOpening.fromSgf("\uD800", factory); fail("Malformed Unicode accepted") }
        catch (_: java.nio.charset.CharacterCodingException) { }
        assertEquals(0, calls)
    }

    @Test fun failedRestartRestoreClosesCandidateAndKeepsImmutableOpening() {
        val opening = GoAiOpening.fromSnapshot(byteArrayOf(9, 0, 0), factory())
        val broken = GoAiMatchSessionTest.Rules(13, 0)
        try { opening.createEngine { _, _, _ -> broken }; fail("Wrong-size candidate accepted") }
        catch (_: IllegalStateException) { }
        assertTrue(broken.closed); assertArrayEquals(byteArrayOf(9, 0, 0), opening.copyBytes())
    }

    @Test fun continuationCanFinishWithoutChangingTheOpeningHistory() {
        val opening = GoAiOpening.fromSnapshot(byteArrayOf(9, 0, 0, 1), factory())
        opening.createEngine(factory()).use {
            repeat(2) { _ -> it.apply(GoAction.Pass) }
            for (actor in GoPlayer.entries) it.apply(GoAction.ConfirmScore(actor, 1))
            assertEquals(GoPhase.FINISHED, it.phase)
            opening.checkContinuation(it)
        }
        assertEquals(2, opening.eventCount)
    }

    @Test fun nonstandardKomiCannotEnterOrContinueAStandardOpening() {
        val rules = GoAiMatchSessionTest.Rules(9, 0)
        val zeroKomi = object : GoRecordEngine by rules { override val blackKomiQuarters = 0 }
        expectInvalid { GoAiOpening.fromSnapshot(byteArrayOf(9, 0), { _, _, _ -> zeroKomi }) }
        assertTrue(rules.closed)
        val opening = GoAiOpening.fromSnapshot(byteArrayOf(9, 0, 0), factory())
        val current = GoAiMatchSessionTest.Rules(9, 0)
        current.restore(byteArrayOf(9, 0, 0))
        try {
            expectInvalid { opening.checkContinuation(object : GoRecordEngine by current { override val blackKomiQuarters = 0 }) }
        } finally { current.close() }
    }

    @Test fun ruleRestoreExceptionClosesCandidateWithoutCreatingAnOpening() {
        val rules = GoAiMatchSessionTest.Rules(9, 0)
        val failing = object : GoRecordEngine by rules {
            override fun restore(data: ByteArray): RestoreResult = throw java.io.IOException("Native data unavailable")
        }
        try { GoAiOpening.fromSnapshot(byteArrayOf(9, 0), { _, _, _ -> failing }); fail("Restore failure ignored") }
        catch (_: java.io.IOException) { }
        assertTrue(rules.closed)
    }

    private fun factory(engines: MutableList<GoAiMatchSessionTest.Rules> = mutableListOf()): (Int, Int, Int) -> GoRecordEngine =
        { size, _, handicap -> GoAiMatchSessionTest.Rules(size, handicap).also(engines::add) }
    private fun expectInvalid(block: () -> Unit) {
        try { block(); fail("Invalid opening accepted") } catch (_: IllegalArgumentException) { }
    }
}
