package com.masterofchessstrategy.games.go.data

import com.masterofchessstrategy.data.*
import com.masterofchessstrategy.engine.*
import com.masterofchessstrategy.games.go.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/** Envelope-shaped rules fixture, not Native legal-history evidence. */
class GoAiRecordProjectionTest {
    private val owners = mutableListOf<Rules>()
    private val factory: (Int, Int, Int) -> GoRecordEngine = { size, _, handicap -> Rules(size, handicap).also(owners::add) }

    @Test fun allBoardSizesAndHandicapsPreserveCompleteHistoryAndActualMoveCount() {
        for (size in listOf(9, 13, 19)) for (handicap in listOf(0, 2, 5, 9)) {
            val match = match(GoAiMatchConfig(size, handicap))
            val record = projectGoAiRecord(archive(match), factory)
            assertArrayEquals(match.copyBytes(), record.engineState)
            assertEquals(3, record.engineFormatVersion)
            assertEquals(3, record.moveCount) // One placement and two passes, not two confirmations.
            assertEquals(GameResult.FIRST_PLAYER_WIN, record.result)
            assertEquals(17L, record.completedAtEpochMillis)
            assertEquals(StoredGameMode.HUMAN_VS_AI, record.mode)
        }
        assertTrue(owners.all { it.closed })
    }

    @Test fun mapsEveryDifficultyExplicitlyAndPreservesHumanBlackAndWhite() {
        val pairs = listOf(GoAiDifficulty.SIMPLE to Difficulty.EASY, GoAiDifficulty.MEDIUM to Difficulty.MEDIUM,
            GoAiDifficulty.HARD to Difficulty.HARD, GoAiDifficulty.MASTER to Difficulty.MASTER)
        for ((level, expected) in pairs) for (human in GoPlayer.entries) {
            val record = projectGoAiRecord(archive(match(GoAiMatchConfig(9, humanPlayer = human, difficulty = level))), factory)
            assertEquals(expected, record.difficulty)
            assertEquals(human.code, record.playerIndex)
            assertEquals(StoredGameMode.HUMAN_VS_AI, record.mode)
        }
    }

    @Test fun fullActionBudgetAboveLegacy64KiBLimitIsNeverTruncated() {
        // Repeated placements are an envelope-size fixture, not a legal Native-game claim.
        val history = List(4094) { GoAction.Place(BoardPosition(0, 0)) } + listOf(GoAction.Pass, GoAction.Pass,
            GoAction.ConfirmScore(GoPlayer.BLACK, 1), GoAction.ConfirmScore(GoPlayer.WHITE, 1))
        val saved = match(events = history)
        val record = projectGoAiRecord(archive(saved), factory)
        assertTrue(record.engineState.size > 64 * 1024)
        assertEquals(4096, record.moveCount)
        assertEquals(16 + 21 * 4098, record.engineState.size)
        assertArrayEquals(saved.copyBytes(), record.engineState)
    }

    @Test fun mixedHumanGamesAreNotRelabelledPureAutomatic() {
        val original = match(GoAiMatchConfig(13, 5, GoPlayer.WHITE), automaticUsed = true)
        val source = archive(original)
        val record = projectGoAiRecord(source, factory)
        assertEquals(StoredGameMode.HUMAN_VS_AI, record.mode)
        assertEquals(1, record.playerIndex)
        assertTrue(GoAiMatchFileCodec.decode(source.checkpoint.copyBytes())!!.automaticUsed)
        assertArrayEquals(original.copyBytes(), record.engineState)
    }

    @Test fun automaticAndOpeningAutomaticUseExistingModesWithoutInventingHumanOwnership() {
        for (opening in listOf(false, true)) {
            val saved = match(GoAiMatchConfig(19, 5, null), opening = opening)
            val record = projectGoAiRecord(archive(saved), factory)
            assertEquals(if (opening) StoredGameMode.OPENING_AUTO_PLAY else StoredGameMode.AI_AUTO_PLAY, record.mode)
            assertEquals(0, record.playerIndex)
            assertArrayEquals(saved.copyBytes(), record.engineState)
        }
    }

    @Test fun specifiedHumanOpeningKeepsWhiteSideAndCompletePrefixMetadata() {
        val saved = match(GoAiMatchConfig(13, humanPlayer = GoPlayer.WHITE), opening = true)
        val source = archive(saved)
        val before = source.checkpoint.copyBytes()
        val record = projectGoAiRecord(source, factory)
        assertEquals(StoredGameMode.HUMAN_VS_AI, record.mode)
        assertEquals(1, record.playerIndex)
        assertArrayEquals(saved.copyBytes(), record.engineState)
        assertArrayEquals(saved.copyOpeningBytes(), GoAiMatchFileCodec.decode(source.checkpoint.copyBytes())!!.copyOpeningBytes())
        assertArrayEquals(before, source.checkpoint.copyBytes())
    }

    @Test fun archiveIdentityIsBoundToCanonicalGaiAndAiDomainNotLocalDomain() {
        val saved = match()
        val valid = archive(saved)
        val record = projectGoAiRecord(valid, factory)
        assertEquals(key(saved.matchId), record.recordId)
        assertNotEquals(GoSessionIdentity(saved.matchId, GoSessionOrigin.LOCAL).recordId, record.recordId)
        rejected { projectGoAiRecord(GoAiCheckpointArchive("f".repeat(64), valid.checkpoint, 17), factory) }
        val other = archive(match(id = "another"))
        rejected { projectGoAiRecord(GoAiCheckpointArchive(valid.recordId, other.checkpoint, 17), factory) }
        val self = GoAiCheckpointEntry(valid.checkpoint.copyBytes(),
            GoAiCheckpointLineage(GoAiCheckpointOrigin.TERMINAL_CONTINUATION, valid.recordId))
        rejected { projectGoAiRecord(GoAiCheckpointArchive(valid.recordId, self, 17), factory) }
    }

    @Test fun onlyTwoConfirmedTerminalEventsCanProjectNotPendingOrOneConfirmation() {
        for (count in 0..4) {
            val saved = match(events = terminalEvents.take(count))
            rejected { projectGoAiRecord(archive(saved), factory) }
        }
        assertTrue(owners.all { it.closed })
    }

    @Test fun badOuterChecksumAndUnknownVersionAreRejectedBeforeOpeningAnEngine() {
        val valid = archive(match())
        for (offset in listOf(4, 40)) {
            val bytes = valid.checkpoint.copyBytes().also { it[offset] = (it[offset].toInt() xor 127).toByte() }
            rejected { projectGoAiRecord(GoAiCheckpointArchive(valid.recordId,
                GoAiCheckpointEntry(bytes, valid.checkpoint.lineage), 17), factory) }
        }
        assertTrue(owners.isEmpty())
    }

    @Test fun admittedCanonicalBytesCannotBeSilentlySubstitutedOrRebound() {
        val source = archive(match())
        rejected {
            projectGoAiRecord(source) { size, komi, handicap ->
                val rules = factory(size, komi, handicap)
                object : GoRecordEngine by rules {
                    override fun serialize() = rules.serialize().also { it[8] = 1 }
                }
            }
        }
        assertTrue(owners.all { it.closed })
    }

    @Test fun sameRecordRetryPreservesFavoriteButRejectsChangedFirstTimeOrImmutableContent() {
        val record = projectGoAiRecord(archive(match()), factory)
        val favorite = record.copy(isFavorite = true)
        requireSameGoAiRecord(favorite, record.defensiveCopy())
        assertTrue(favorite.isFavorite)
        for (conflict in listOf(record.copy(completedAtEpochMillis = 18), record.copy(result = GameResult.DRAW),
            record.copy(playerIndex = 1), record.copy(difficulty = Difficulty.HARD),
            record.copy(moveCount = 2), record.copy(recordId = "other"),
            record.copy(mode = StoredGameMode.AI_AUTO_PLAY),
            record.copy(engineState = record.engineState.copyOf().also { it[8] = 1 }))) {
            rejected { requireSameGoAiRecord(favorite, conflict) }
        }
    }

    @Test fun sourceAndProjectionBytesAreOwnedAndRepeatedProjectionUnaffectedByCallerMutation() {
        val saved = match()
        val source = archive(saved)
        val original = source.checkpoint.copyBytes()
        val first = projectGoAiRecord(source, factory)
        first.engineState.fill(0)
        saved.copyBytes().fill(0)
        source.checkpoint.copyBytes().fill(0)
        val next = projectGoAiRecord(source, factory)
        assertArrayEquals(saved.copyBytes(), next.engineState)
        assertArrayEquals(original, source.checkpoint.copyBytes())
    }

    @Test fun everyExistingProvenanceProjectsWithoutAwardOrSourceUpgrade() {
        val saved = match()
        for (origin in GoAiCheckpointOrigin.entries) {
            val lineage = GoAiCheckpointLineage(origin,
                if (origin == GoAiCheckpointOrigin.TERMINAL_CONTINUATION) "f".repeat(64) else null)
            val source = archive(saved, lineage)
            val record = projectGoAiRecord(source, factory)
            assertEquals(lineage, source.checkpoint.lineage)
            assertEquals(key(saved.matchId), record.recordId)
            assertFalse(record.isFavorite)
            assertFalse(record.isEndgame)
        }
    }

    private val terminalEvents = listOf(GoAction.Place(BoardPosition(0, 0)), GoAction.Pass, GoAction.Pass,
        GoAction.ConfirmScore(GoPlayer.BLACK, 1), GoAction.ConfirmScore(GoPlayer.WHITE, 1))

    private fun match(config: GoAiMatchConfig = GoAiMatchConfig(9), opening: Boolean = false,
        automaticUsed: Boolean = config.automatic, id: String = "projection-match", events: List<GoAction> = terminalEvents): GoAiSavedMatch {
        val rules = Rules(config.boardSize, config.handicapStones)
        events.forEach { rules.apply(it) }
        val initial = if (opening) Rules(config.boardSize, config.handicapStones).also { it.apply(terminalEvents.first()) }.serialize() else null
        return GoAiSavedMatch(id, config, rules.serialize(), automaticUsed = automaticUsed, openingBytes = initial)
    }

    private fun key(id: String) = MessageDigest.getInstance("SHA-256").digest(
        "MasterofChessStrategy:go-ai-record:v1\u0000$id".toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }

    private fun archive(match: GoAiSavedMatch,
        lineage: GoAiCheckpointLineage = GoAiCheckpointLineage(GoAiCheckpointOrigin.UNVERIFIED_SESSION)) =
        GoAiCheckpointArchive(key(match.matchId), GoAiCheckpointEntry(GoAiMatchFileCodec.encode(match), lineage), 17)

    private fun rejected(block: () -> Unit) {
        try { block(); fail("Invalid projection accepted") } catch (_: IllegalArgumentException) { }
    }

    private class Rules(override val boardSize: Int, override var handicapStones: Int) : GoRecordEngine {
        private val events = mutableListOf<GoAction>()
        var closed = false
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
            data.drop(16).chunked(21).forEach { image ->
                events += when (image[0].toInt()) {
                    0 -> GoAction.Place(BoardPosition(image[1].toInt(), image[2].toInt()))
                    1 -> GoAction.Pass
                    2 -> GoAction.ConfirmScore(GoPlayer.entries[image[1].toInt()], 1)
                    else -> return RestoreResult.Rejected(EngineError.CORRUPTED_DATA)
                }
            }
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
        override fun close() { check(!closed); closed = true }
    }
}
