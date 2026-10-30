package com.masterofchessstrategy.games.lanzhou_fangqi.data

import com.masterofchessstrategy.data.ActiveGameEntity
import com.masterofchessstrategy.data.GameRecordEntity
import com.masterofchessstrategy.data.MatchOutcomeEntity
import com.masterofchessstrategy.engine.Difficulty
import com.masterofchessstrategy.engine.GameResult
import com.masterofchessstrategy.engine.GameType
import com.masterofchessstrategy.games.lanzhou_fangqi.*
import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

/** Explicit replay/admission doubles verify growth selection, not Native legality or Room writes. */
class FangqiQualifiedGrowthTest {
    @Test fun sourceFreeFangqiOutcomesAreExcludedAndEveryOtherGameIsUnchanged() {
        val others = GameType.entries.filter { it != GameType.LANZHOU_SQUARE_CHESS }
            .map { outcome().copy(matchId = "other-${it.code}", gameTypeCode = it.code) }
        val input = empty().copy(outcomes = listOf(outcome().copy(matchId = "legacy-win")) + others +
            outcome().copy(matchId = "legacy-loss", resultCode = 2))
        val result = qualifiedFangqiGrowthRows(input, validator())
        assertEquals(others, result)
        others.forEachIndexed { index, row -> assertSame(row, result[index]) }
        assertEquals(others.size + 2, input.outcomes.size)
    }

    @Test fun completeSourceKeepsOnlyItsExactOutcomeAndPreservesInputOrder() {
        val formal = outcome()
        val other = formal.copy(matchId = "xiangqi", gameTypeCode = 0)
        val legacy = formal.copy(matchId = "legacy")
        val input = complete().copy(outcomes = listOf(other, legacy, formal))
        val result = qualifiedFangqiGrowthRows(input, validator())
        assertEquals(listOf(other, formal), result)
        assertSame(formal, result[1])
    }

    @Test fun verifiedFormalLossRemainsAnOutcomeRatherThanBeingFilteredAsNoWin() {
        val terminal = receipt(GameResult.SECOND_PLAYER_WIN)
        val expected = outcome(terminal)
        val result = qualifiedFangqiGrowthRows(complete(terminal), validator(GameResult.SECOND_PLAYER_WIN))
        assertEquals(listOf(expected), result)
        assertEquals(2, result.single().resultCode)
        assertEquals(30L, result.single().settledAtEpochMillis)
    }

    @Test fun explicitDeletedRecordPreservesItsVerifiedGrowthContributionWithoutRecreation() {
        val input = complete().copy(settlements = listOf(settlement(deletedAt = 40)), records = emptyList())
        assertEquals(listOf(outcome()), qualifiedFangqiGrowthRows(input, validator()))
        assertTrue(input.records.isEmpty())
        assertEquals(40L, input.settlements.single().recordDeletedAtEpochMillis)
    }

    @Test fun missingCreationReceiptOutcomeOrUnmarkedRecordFailsInsteadOfReturningZero() {
        val input = complete()
        for (bad in listOf(input.copy(starts = emptyList()), input.copy(settlements = emptyList()),
            input.copy(outcomes = emptyList()), input.copy(records = emptyList()))) rejected(bad)
    }

    @Test fun outcomeIdentityAndImmutableMetadataMustMatchTheReceipt() {
        for (bad in listOf(outcome().copy(gameTypeCode = 0), outcome().copy(modeCode = 0),
            outcome().copy(difficultyCode = Difficulty.HARD.code), outcome().copy(playerIndex = 1),
            outcome().copy(resultCode = 2), outcome().copy(settledAtEpochMillis = 31))) {
            rejected(complete().copy(outcomes = listOf(bad)))
        }
    }

    @Test fun conflictingRecordBytesOrMetadataAndDeletedRecordResurrectionFail() {
        for (bad in listOf(record().copy(engineState = root().bytes), record().copy(moveCount = 0),
            record().copy(resultCode = 2), record().copy(completedAtEpochMillis = 20))) {
            rejected(complete().copy(records = listOf(bad)))
        }
        rejected(complete().copy(settlements = listOf(settlement(deletedAt = 40))))
        rejected(complete().copy(starts = listOf(start().copy(creationSha256 = "0".repeat(64)))))
    }

    @Test fun duplicateSourceOrOutcomeCannotDuplicateOrHideGrowth() {
        val input = complete()
        rejected(input.copy(starts = input.starts + input.starts))
        rejected(input.copy(settlements = input.settlements + input.settlements))
        rejected(input.copy(outcomes = input.outcomes + input.outcomes))
        rejected(input.copy(records = input.records + input.records))
    }

    @Test fun creationAndOngoingActivityAloneDoNotProduceAnOutcome() {
        var admitted = false
        val checked = FangqiRankedSnapshotValidator(FangqiRankedLedger(::replay),
            FangqiSessionAdmission { admitted = true; it })
        val input = empty().copy(starts = listOf(start()), active = active())
        assertTrue(qualifiedFangqiGrowthRows(input, checked).isEmpty())
        assertTrue(admitted)
        assertTrue(input.outcomes.isEmpty())
        assertTrue(input.settlements.isEmpty())
    }

    @Test fun standaloneFormatOneRecordAndGenericOutcomeNeverSynthesizeFormalSource() {
        val input = empty().copy(outcomes = listOf(outcome()), records = listOf(record()))
        assertTrue(qualifiedFangqiGrowthRows(input, validator()).isEmpty())
        assertTrue(input.starts.isEmpty())
        assertTrue(input.settlements.isEmpty())
        assertEquals(1, input.records.single().engineFormatVersion)
    }

    @Test fun replayRefusalOrContradictoryResultPropagatesBeforeGrowthIsReturned() {
        val refuses = FangqiRankedSnapshotValidator(FangqiRankedLedger { history ->
            if (history == root()) replay(history) else throw IOException("explicit replay refusal")
        }, FangqiSessionAdmission { it })
        rejected(complete(), refuses)
        rejected(complete(), validator(GameResult.SECOND_PLAYER_WIN))
    }

    @Test fun activeAdmissionFailureIsNotSilentlyConvertedToEmptyGrowth() {
        val refuses = FangqiRankedSnapshotValidator(FangqiRankedLedger(::replay),
            FangqiSessionAdmission { throw IOException("explicit active admission refusal") })
        rejected(empty().copy(starts = listOf(start()), active = active()), refuses)
    }

    @Test fun repeatedReadsKeepRowsAndInputBytesIndependentOfReplayAndCallerCopies() {
        val rows = mutableListOf(outcome())
        val terminal = receipt().terminal
        val input = complete().copy(outcomes = rows, active = active().copy(engineState = terminal.history.bytes,
            acceptedMoveCount = terminal.acceptedActionCount, updatedAtEpochMillis = terminal.updatedAtEpochMillis))
        val expectedRecordBytes = input.records.single().engineState.copyOf()
        val expectedActiveBytes = input.active!!.engineState.copyOf()
        val checked = FangqiRankedSnapshotValidator(FangqiRankedLedger { history ->
            history.bytes.fill(0) // The wrapper returns a copy; never mutate the caller's snapshot.
            replay(history)
        }, FangqiSessionAdmission { bytes -> bytes.copyOf().also { bytes.fill(0) } })
        val first = qualifiedFangqiGrowthRows(input, checked)
        val second = qualifiedFangqiGrowthRows(input, checked)
        assertEquals(listOf(outcome()), first)
        assertEquals(first, second)
        assertArrayEquals(expectedRecordBytes, input.records.single().engineState)
        assertArrayEquals(expectedActiveBytes, requireNotNull(input.active).engineState)
        rows.clear()
        assertEquals(listOf(outcome()), first)
        assertEquals(listOf(outcome()), second)
    }

    private fun validator(result: GameResult = GameResult.FIRST_PLAYER_WIN) = FangqiRankedSnapshotValidator(
        FangqiRankedLedger { history -> if (history == root()) replay(history) else FangqiRankedReplayResult(history, result, 0) },
        FangqiSessionAdmission { it })
    private fun replay(history: FangqiRankedHistory) = FangqiRankedReplayResult(history,
        if (history == root()) GameResult.ONGOING else GameResult.FIRST_PLAYER_WIN, 0)
    private fun root() = FangqiRankedLedger.standardInitialHistory()
    private fun creation() = FangqiRankedCreation(1, FangqiRankedStartOrigin.NEW_STANDARD_HUMAN_GAME,
        FangqiRankedPosition(FangqiRankedIdentity(ID, GameType.LANZHOU_SQUARE_CHESS, FangqiStoredMode.HUMAN_VS_AI,
            Difficulty.EASY, 0, FangqiRankedLedger.RULES_VERSION, 1), root(), 10, 0, 0, 0))
    private fun receipt(result: GameResult = GameResult.FIRST_PLAYER_WIN) = FangqiRankedTerminalReceipt(1,
        creation().sha256, creation().initial.copy(history = FangqiRankedHistory(
            (root().bytes.toString(Charsets.US_ASCII).removeSuffix("|0|") + "|1|m,1,35,28").toByteArray(Charsets.US_ASCII)),
            updatedAtEpochMillis = 20, acceptedActionCount = 1), result, 30)
    private fun start() = FangqiRankedRowMapping.encodeStartUnvalidated(creation())
    private fun settlement(terminal: FangqiRankedTerminalReceipt = receipt(), deletedAt: Long? = null) =
        FangqiRankedRowMapping.encodeSettlementUnvalidated(creation(), terminal, deletedAt)
    private fun outcome(terminal: FangqiRankedTerminalReceipt = receipt()) = MatchOutcomeEntity(ID, 4, 1,
        Difficulty.EASY.code, 0, if (terminal.result == GameResult.FIRST_PLAYER_WIN) 1 else 2, terminal.settledAtEpochMillis)
    private fun record(terminal: FangqiRankedTerminalReceipt = receipt()) = GameRecordEntity(ID, 4, 1,
        Difficulty.EASY.code, if (terminal.result == GameResult.FIRST_PLAYER_WIN) 1 else 2, 1,
        terminal.terminal.history.bytes, terminal.terminal.acceptedActionCount, completedAtEpochMillis = terminal.settledAtEpochMillis)
    private fun active() = ActiveGameEntity(4, 1, Difficulty.EASY.code, 5, 1, root().bytes, 10, sessionId = ID)
    private fun empty() = FangqiRankedStorageSnapshot(emptyList(), emptyList(), null, emptyList(), emptyList())
    private fun complete(terminal: FangqiRankedTerminalReceipt = receipt()) = FangqiRankedStorageSnapshot(
        listOf(start()), listOf(settlement(terminal)), null, listOf(outcome(terminal)), listOf(record(terminal)))
    private fun rejected(snapshot: FangqiRankedStorageSnapshot, checked: FangqiRankedSnapshotValidator = validator()) {
        try { qualifiedFangqiGrowthRows(snapshot, checked); fail("Unverified Fangqi growth accepted") }
        catch (_: IOException) { }
    }
    companion object { private const val ID = "qualified-growth-source" }
}
