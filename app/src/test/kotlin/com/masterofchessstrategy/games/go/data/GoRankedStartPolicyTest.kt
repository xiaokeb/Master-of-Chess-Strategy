package com.masterofchessstrategy.games.go.data

import com.masterofchessstrategy.engine.*
import com.masterofchessstrategy.games.go.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Envelope-shaped disposable fixture; these tests are not Native rules or strength evidence. */
class GoRankedStartPolicyTest {
    private val id = "9d18308c-c5fe-4660-8118-b2d740d7519a"
    private val factory: (Int, Int, Int) -> GoRecordEngine = { size, _, handicap -> Rules(size, handicap) }
    private val finish = listOf(GoAction.Place(BoardPosition(0, 0)), GoAction.Pass, GoAction.Pass,
        GoAction.ConfirmScore(GoPlayer.BLACK, 1), GoAction.ConfirmScore(GoPlayer.WHITE, 1))

    @Test fun allSizesSidesAndDifficultiesQualifyWithExactRecordAndFirstTime() {
        for (size in listOf(9, 13, 19)) for (side in GoPlayer.entries) for (level in GoAiDifficulty.entries) {
            val config = GoAiMatchConfig(size, humanPlayer = side, difficulty = level)
            val start = admitGoRankedStart(match(config), 0, factory)
            val outcome = qualifiedGoOutcome(start, archive(match(config, finish)), factory)!!
            assertEquals(start.recordId, outcome.matchId)
            assertEquals(GameType.GO, outcome.gameType)
            assertEquals(side.code, outcome.playerIndex)
            assertEquals(Difficulty.entries[level.ordinal], outcome.difficulty)
            assertEquals(7L, outcome.settledAtEpochMillis)
        }
    }

    @Test fun allowedAssistanceDoesNotEraseEligibilityButCannotAppearAtCreation() {
        for (level in GoAiDifficulty.entries) {
            val config = GoAiMatchConfig(9, difficulty = level)
            val start = admitGoRankedStart(match(config), 7, factory)
            val used = minOf(level.undoLimit, 3)
            assertNotNull(qualifiedGoOutcome(start, archive(match(config, finish, undo = used, hint = used)), factory))
            if (used > 0) {
                rejected { admitGoRankedStart(match(config, undo = 1), 7, factory) }
                rejected { admitGoRankedStart(match(config, hint = 1), 7, factory) }
            }
        }
    }

    @Test fun nonUuidAliasesAndNoncanonicalUuidAreRejected() {
        for (bad in listOf("legacy", "1-1-1-1-1", id.uppercase(), " $id")) {
            rejected { admitGoRankedStart(match(identity = bad), 0, factory) }
        }
    }

    @Test fun initialHistoryTerminalAndMixedParticipationCannotCreateOrigin() {
        for (events in listOf(finish.take(1), finish.take(3), finish)) {
            rejected { admitGoRankedStart(match(events = events), 0, factory) }
        }
        rejected { admitGoRankedStart(match(automaticUsed = true), 0, factory) }
        rejected { admitGoRankedStart(match(GoAiMatchConfig(9, humanPlayer = null)), 0, factory) }
        rejected { admitGoRankedStart(match(GoAiMatchConfig(9, 2)), 0, factory) }
        rejected { admitGoRankedStart(match(opening = true), 0, factory) }
        rejected { admitGoRankedStart(match(events = finish, reserved = true), 0, factory) }
        rejected { admitGoRankedStart(match(GoAiMatchConfig(9, humanPlayer = null), series = true), 0, factory) }
    }

    @Test fun creationAndReturnedBytesAreDefensivelyOwned() {
        val original = admitGoRankedStart(match(), 0, factory)
        val bytes = original.copyBytes()
        val copy = GoRankedStart(original.recordId, bytes, 0)
        bytes.fill(0); copy.copyBytes().fill(0)
        assertArrayEquals(original.copyBytes(), copy.copyBytes())
        assertNotNull(qualifiedGoOutcome(copy, archive(match(events = finish)), factory))
    }

    @Test fun rollbackClockPreservesOrdinaryArchiveWithoutOutcome() {
        val source = archive(match(events = finish), time = 6)
        val before = source.checkpoint.copyBytes()
        assertNull(qualifiedGoOutcome(admitGoRankedStart(match(), 7, factory), source, factory))
        assertArrayEquals(before, source.checkpoint.copyBytes())
        assertNotNull(qualifiedGoOutcome(admitGoRankedStart(match(), 6, factory), source, factory))
        assertNotNull(qualifiedGoOutcome(admitGoRankedStart(match(), Long.MAX_VALUE, factory),
            archive(match(events = finish), time = Long.MAX_VALUE), factory))
        rejected { admitGoRankedStart(match(), -1, factory) }
        rejected { GoRankedStart("0".repeat(64), byteArrayOf(1), -1) }
        rejected { GoRankedStart("0".repeat(64), byteArrayOf(1), 0, 2) }
    }

    @Test fun everyUntrustedOrContinuationOriginRetainsRecordWithoutQualification() {
        val start = admitGoRankedStart(match(), 0, factory)
        for (origin in listOf(GoAiCheckpointOrigin.LEGACY_UNVERIFIED, GoAiCheckpointOrigin.TERMINAL_CONTINUATION)) {
            val lineage = GoAiCheckpointLineage(origin,
                if (origin == GoAiCheckpointOrigin.TERMINAL_CONTINUATION) "f".repeat(64) else null)
            assertNull(qualifiedGoOutcome(start, archive(match(events = finish), lineage = lineage), factory))
        }
    }

    @Test fun validChangedConfigurationMixedOpeningAndAutomaticSeriesDoNotQualify() {
        val start = admitGoRankedStart(match(), 0, factory)
        val changed = listOf(match(GoAiMatchConfig(13), finish), match(GoAiMatchConfig(9, 2), finish),
            match(GoAiMatchConfig(9, humanPlayer = GoPlayer.WHITE), finish),
            match(GoAiMatchConfig(9, difficulty = GoAiDifficulty.HARD), finish),
            match(events = finish, automaticUsed = true), match(events = finish, opening = true),
            match(GoAiMatchConfig(9, humanPlayer = null), finish, series = true))
        for (final in changed) assertNull(qualifiedGoOutcome(start, archive(final), factory))
    }

    @Test fun pendingAndOneConfirmationCannotGrantOutcome() {
        val start = admitGoRankedStart(match(), 0, factory)
        for (count in 0..4) assertNull(qualifiedGoOutcome(start, archive(match(events = finish.take(count))), factory))
    }

    @Test fun identityCorruptionAndCanonicalSubstitutionAreRejectedNotSilentlyDowngraded() {
        val start = admitGoRankedStart(match(), 0, factory)
        val valid = archive(match(events = finish))
        rejected { qualifiedGoOutcome(start, GoAiCheckpointArchive("f".repeat(64), valid.checkpoint, 7), factory) }
        rejected { qualifiedGoOutcome(start, archive(match(events = finish,
            identity = "c59eced6-6334-47c1-a528-f5143eca67c8")), factory) }
        rejected { qualifiedGoOutcome(GoRankedStart("f".repeat(64), start.copyBytes(), 0), valid, factory) }
        val broken = valid.checkpoint.copyBytes().also { it[it.lastIndex] = 127 }
        rejected { qualifiedGoOutcome(start, GoAiCheckpointArchive(start.recordId,
            GoAiCheckpointEntry(broken, valid.checkpoint.lineage), 7), factory) }
        rejected { qualifiedGoOutcome(GoRankedStart(start.recordId, broken, 0), valid, factory) }
        val changing: (Int, Int, Int) -> GoRecordEngine = { size, komi, handicap ->
            val rules = factory(size, komi, handicap)
            object : GoRecordEngine by rules { override fun serialize() = rules.serialize().also { it[8] = 1 } }
        }
        rejected { admitGoRankedStart(match(), 0, changing) }
        rejected { qualifiedGoOutcome(start, valid, changing) }
    }

    @Test fun independentlyAdmittedInitialMustStillBeFreshAndRuleCompatible() {
        val valid = archive(match(events = finish))
        for (bad in listOf(match(events = finish.take(1)), match(automaticUsed = true))) {
            rejected { qualifiedGoOutcome(GoRankedStart(valid.recordId, GoAiMatchFileCodec.encode(bad), 0), valid, factory) }
        }
        val wrongRules: (Int, Int, Int) -> GoRecordEngine = { _, _, _ -> Rules(19, 0) }
        rejected { admitGoRankedStart(match(), 0, wrongRules) }
    }

    @Test fun terminalCanonicalSubstitutionAndNativeRejectionCannotGrantOutcome() {
        val start = admitGoRankedStart(match(), 0, factory)
        val source = archive(match(events = finish))
        val changedFinal: (Int, Int, Int) -> GoRecordEngine = { size, komi, handicap ->
            val rules = factory(size, komi, handicap)
            object : GoRecordEngine by rules {
                override fun serialize() = rules.serialize().also { if (rules.phase == GoPhase.FINISHED) it[8] = 1 }
            }
        }
        rejected { qualifiedGoOutcome(start, source, changedFinal) }
        val rejecting: (Int, Int, Int) -> GoRecordEngine = { size, komi, handicap ->
            val rules = factory(size, komi, handicap)
            object : GoRecordEngine by rules {
                override fun restore(data: ByteArray) = RestoreResult.Rejected(EngineError.CORRUPTED_DATA)
            }
        }
        rejected { qualifiedGoOutcome(start, source, rejecting) }
    }

    @Test fun reservedTerminalAndEveryResultRetainActualOutcomeWithoutAwardingAnything() {
        for (result in listOf(GameResult.FIRST_PLAYER_WIN, GameResult.SECOND_PLAYER_WIN, GameResult.DRAW)) {
            val resultFactory: (Int, Int, Int) -> GoRecordEngine = { size, komi, handicap ->
                val rules = factory(size, komi, handicap)
                object : GoRecordEngine by rules {
                    override fun gameResult() = if (rules.phase == GoPhase.FINISHED) result else GameResult.ONGOING
                    override fun finalScore() = if (rules.phase == GoPhase.FINISHED)
                        GoAreaScore(0, 0, komi, result, 0) else null
                }
            }
            val start = admitGoRankedStart(match(), 0, resultFactory)
            val source = archive(match(events = finish, reserved = true))
            assertEquals(result, qualifiedGoOutcome(start, source, resultFactory)!!.result)
        }
    }

    private fun match(config: GoAiMatchConfig = GoAiMatchConfig(9), events: List<GoAction> = emptyList(),
        automaticUsed: Boolean = config.automatic, identity: String = id, undo: Int = 0, hint: Int = 0,
        opening: Boolean = false, reserved: Boolean = false, series: Boolean = false): GoAiSavedMatch {
        val rules = Rules(config.boardSize, config.handicapStones)
        events.forEach { rules.apply(it) }
        return GoAiSavedMatch(identity, config, rules.serialize(), undo, hint, reserved,
            automaticUsed = automaticUsed, openingBytes = if (opening) Rules(config.boardSize,
                config.handicapStones).also { it.apply(finish.first()) }.serialize() else null,
            autoContinueEnabled = series, autoContinueMaxGames = if (series) 2 else 1)
    }
    private fun archive(match: GoAiSavedMatch, time: Long = 7,
        lineage: GoAiCheckpointLineage = GoAiCheckpointLineage(GoAiCheckpointOrigin.UNVERIFIED_SESSION)) =
        GoAiCheckpointArchive(admitGoAiCheckpoint(match, factory).recordId,
            GoAiCheckpointEntry(GoAiMatchFileCodec.encode(match), lineage), time)
    private fun rejected(block: () -> Unit) {
        try { block(); fail("Invalid ranked source accepted") } catch (_: IllegalArgumentException) { }
    }

    private class Rules(override val boardSize: Int, override var handicapStones: Int) : GoRecordEngine {
        private val events = mutableListOf<GoAction>()
        override val gameType = GameType.GO
        override val blackKomiQuarters get() = if (handicapStones == 0) 15 else 0
        override val currentPlayer get() = PlayerId(events.count { it is GoAction.Place || it == GoAction.Pass } % 2)
        override val phase get() = when {
            events.count { it is GoAction.ConfirmScore } == 2 -> GoPhase.FINISHED
            events.takeLast(2) == listOf(GoAction.Pass, GoAction.Pass) || events.lastOrNull() is GoAction.ConfirmScore -> GoPhase.SCORING
            else -> GoPhase.PLAYING
        }
        override fun apply(action: GoAction): ActionResult { events += action; return ActionResult.Accepted }
        override fun undo() = if (events.isNotEmpty()) { events.removeAt(events.lastIndex); true } else false
        override fun serialize(): ByteArray = ByteBuffer.allocate(16 + 21 * events.size).order(ByteOrder.LITTLE_ENDIAN).apply {
            put(byteArrayOf(77, 79, 67, 71, 3, boardSize.toByte(), blackKomiQuarters.toByte(), handicapStones.toByte()))
            putInt(0); putInt(events.size)
            events.forEach { event ->
                val image = ByteArray(21)
                when (event) {
                    is GoAction.Place -> { image[0] = 0; image[1] = event.point.x.toByte(); image[2] = event.point.y.toByte() }
                    GoAction.Pass -> image[0] = 1
                    is GoAction.ConfirmScore -> { image[0] = 2; image[1] = event.actor.code.toByte() }
                    else -> error("Unsupported fixture event")
                }
                put(image)
            }
        }.array()
        override fun restore(data: ByteArray): RestoreResult {
            if (data.size < 16 || data[5].toInt() != boardSize) return RestoreResult.Rejected(EngineError.CORRUPTED_DATA)
            handicapStones = data[7].toInt(); events.clear()
            data.drop(16).chunked(21).forEach { image -> events += when (image[0].toInt()) {
                0 -> GoAction.Place(BoardPosition(image[1].toInt(), image[2].toInt()))
                1 -> GoAction.Pass
                2 -> GoAction.ConfirmScore(GoPlayer.entries[image[1].toInt()], 1)
                else -> return RestoreResult.Rejected(EngineError.CORRUPTED_DATA)
            } }
            return RestoreResult.Restored
        }
        override fun gameResult() = if (phase == GoPhase.FINISHED) GameResult.FIRST_PLAYER_WIN else GameResult.ONGOING
        override fun finalScore() = if (phase == GoPhase.FINISHED)
            GoAreaScore(boardSize * boardSize * 4, 0, blackKomiQuarters, gameResult(), handicapStones * 4) else null
        override fun eventHistory() = events.toList()
        override fun scoringState() = GoScoringState(phase, 1, false, false, emptyList())
        override fun reset() { events.clear() }
        override fun stoneAt(point: BoardPosition) = GoStone.EMPTY
        override fun legalActions() = emptyList<GoAction>()
        override fun previewScore(): GoAreaScore? = null
        override fun exportSgf() = "(;GM[1])"
        override fun restoreSgf(text: String) = RestoreResult.Rejected(EngineError.UNSUPPORTED)
        override fun close() = Unit
    }
}
