package com.masterofchessstrategy.games.go

import com.masterofchessstrategy.engine.*
import org.junit.Assert.*
import org.junit.Test

class GoOpeningStudyLibraryTest {
    @Test fun authoredCatalogHasUniqueStableMetadataAcrossAllSupportedSizes() {
        val lines = GoOpeningStudyLibrary.lines
        assertEquals(6, lines.size); assertEquals(lines.size, lines.map { it.id }.toSet().size)
        assertEquals(setOf(9, 13, 19), lines.map { it.boardSize }.toSet())
        assertTrue(lines.all { it.steps.size >= 4 && it.title.isNotBlank() && it.focus.isNotBlank() })
    }

    @Test fun everyLineIsAppliedThroughRulesAndCreatesExactTrainingEndpoint() {
        for (line in GoOpeningStudyLibrary.lines) {
            val plan = GoOpeningStudyLibrary.open(line, factory)
            assertEquals(line.id, plan.line.id); assertEquals(line.steps.size, plan.opening.eventCount)
            assertArrayEquals(plan.copySnapshotBytes(), plan.opening.copyBytes())
            assertEquals(GoAiMatchConfig(line.boardSize, humanPlayer = null, difficulty = GoAiDifficulty.MASTER),
                plan.automaticConfig(GoAiDifficulty.MASTER))
            plan.opening.createEngine(factory).use { engine ->
                assertEquals(line.steps.map { GoAction.Place(it.point) }, engine.eventHistory())
                assertEquals(GoPhase.PLAYING, engine.phase); assertEquals(GameResult.ONGOING, engine.gameResult())
            }
        }
    }

    @Test fun returnedSnapshotAndOpeningBytesAreDefensiveCopies() {
        val plan = GoOpeningStudyLibrary.open(GoOpeningStudyLibrary.lines.first(), factory)
        val expected = plan.copySnapshotBytes(); val mutated = plan.copySnapshotBytes(); mutated[0] = 99
        val opening = plan.opening.copyBytes(); opening[0] = 98
        assertArrayEquals(expected, plan.copySnapshotBytes()); assertArrayEquals(expected, plan.opening.copyBytes())
    }

    @Test fun lineCopiesCallerOwnedStepList() {
        val mutable = validSteps().toMutableList()
        val line = GoOpeningStudyLine("copied", "复制", "不可变内容", 9, mutable)
        mutable.clear()
        assertEquals(4, line.steps.size)
        assertEquals(4, GoOpeningStudyLibrary.open(line, factory).opening.eventCount)
    }

    @Test fun lookupReturnsOnlyKnownStableIdentifiers() {
        val first = GoOpeningStudyLibrary.lines.first()
        assertSame(first, GoOpeningStudyLibrary.line(first.id)); assertNull(GoOpeningStudyLibrary.line("missing"))
    }

    @Test fun invalidMetadataAndCoordinatesAreRejectedBeforeCreatingRulesEngine() {
        assertInvalid { GoOpeningStudyLine("Bad ID", "题", "重点", 9, validSteps()) }
        assertInvalid { GoOpeningStudyLine("bad-size", "题", "重点", 11, validSteps()) }
        assertInvalid { GoOpeningStudyLine("bad-point", "题", "重点", 9,
            validSteps().dropLast(1) + GoOpeningStudyStep(BoardPosition(9, 0), "越界")) }
    }

    @Test fun rejectedAuthoredMoveClosesValidatorAndPublishesNoPlan() {
        val engines = mutableListOf<GoAiMatchSessionTest.Rules>()
        val line = GoOpeningStudyLine("occupied", "重复落点", "验证非法内容", 9,
            listOf(0 to 0, 1 to 0, 0 to 0, 2 to 0).map { GoOpeningStudyStep(BoardPosition(it.first, it.second), "说明") })
        assertInvalid { GoOpeningStudyLibrary.open(line) { size, _, handicap ->
            GoAiMatchSessionTest.Rules(size, handicap).also(engines::add)
        } }
        assertEquals(1, engines.size); assertTrue(engines.single().closed)
    }

    @Test fun canonicalRestoreFailureClosesEveryCandidate() {
        val engines = mutableListOf<GoAiMatchSessionTest.Rules>()
        var calls = 0
        assertInvalid { GoOpeningStudyLibrary.open(GoOpeningStudyLibrary.lines.first()) { size, _, handicap ->
            calls++
            GoAiMatchSessionTest.Rules(if (calls == 1) size else 13, handicap).also(engines::add)
        } }
        assertTrue(engines.isNotEmpty()); assertTrue(engines.all { it.closed })
    }

    private val factory: (Int, Int, Int) -> GoRecordEngine = { size, _, handicap -> StudyRules(size, handicap) }
    private fun validSteps() = listOf(0 to 0, 1 to 0, 2 to 0, 3 to 0).map {
        GoOpeningStudyStep(BoardPosition(it.first, it.second), "说明")
    }
    private fun assertInvalid(block: () -> Unit) {
        try { block(); fail("Invalid authored opening accepted") } catch (_: IllegalArgumentException) { }
    }

    /** Two-byte point fixture keeps all 19x19 intersections distinct. */
    private class StudyRules(override val boardSize: Int, override var handicapStones: Int) : GoRecordEngine {
        private val events = mutableListOf<GoAction>()
        override val gameType = GameType.GO
        override val blackKomiQuarters get() = if (handicapStones == 0) 15 else 0
        override val currentPlayer get() = PlayerId(events.size % 2)
        override val phase get() = GoPhase.PLAYING
        override fun apply(action: GoAction): ActionResult {
            if (action !is GoAction.Place || stoneAt(action.point) != GoStone.EMPTY)
                return ActionResult.Rejected(EngineError.ILLEGAL_ACTION)
            events += action; return ActionResult.Accepted
        }
        override fun undo() = if (events.isEmpty()) false else { events.removeAt(events.lastIndex); true }
        override fun stoneAt(point: BoardPosition): GoStone {
            val index = events.indexOfFirst { (it as GoAction.Place).point == point }
            return when { index < 0 -> GoStone.EMPTY; index % 2 == 0 -> GoStone.BLACK; else -> GoStone.WHITE }
        }
        override fun serialize(): ByteArray {
            val output = ByteArray(2 + events.size * 2); output[0] = boardSize.toByte(); output[1] = handicapStones.toByte()
            events.forEachIndexed { index, action ->
                val point = (action as GoAction.Place).point.y * boardSize + action.point.x
                output[2 + index * 2] = (point and 0xff).toByte(); output[3 + index * 2] = (point ushr 8).toByte()
            }
            return output
        }
        override fun restore(data: ByteArray): RestoreResult {
            if (data.size < 2 || data.size % 2 != 0 || data[0].toInt() != boardSize)
                return RestoreResult.Rejected(EngineError.CORRUPTED_DATA)
            handicapStones = data[1].toInt(); events.clear()
            for (offset in 2 until data.size step 2) {
                val point = (data[offset].toInt() and 0xff) or ((data[offset + 1].toInt() and 0xff) shl 8)
                if (point !in 0 until boardSize * boardSize) return RestoreResult.Rejected(EngineError.CORRUPTED_DATA)
                events += GoAction.Place(BoardPosition(point % boardSize, point / boardSize))
            }
            return RestoreResult.Restored
        }
        override fun scoringState() = GoScoringState(GoPhase.PLAYING, 0, false, false, emptyList())
        override fun eventHistory() = events.toList()
        override fun gameResult() = GameResult.ONGOING
        override fun reset() = events.clear()
        override fun legalActions() = emptyList<GoAction>()
        override fun previewScore(): GoAreaScore? = null
        override fun finalScore(): GoAreaScore? = null
        override fun exportSgf() = "(;GM[1]SZ[$boardSize])"
        override fun restoreSgf(text: String) = RestoreResult.Rejected(EngineError.UNSUPPORTED)
        override fun close() = Unit
    }
}
