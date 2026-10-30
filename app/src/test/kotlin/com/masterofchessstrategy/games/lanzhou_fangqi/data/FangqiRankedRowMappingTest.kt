package com.masterofchessstrategy.games.lanzhou_fangqi.data

import com.masterofchessstrategy.data.MatchOutcomeEntity
import com.masterofchessstrategy.engine.Difficulty
import com.masterofchessstrategy.engine.GameResult
import com.masterofchessstrategy.engine.GameType
import com.masterofchessstrategy.games.lanzhou_fangqi.FangqiRankedCreation
import com.masterofchessstrategy.games.lanzhou_fangqi.FangqiRankedHistory
import com.masterofchessstrategy.games.lanzhou_fangqi.FangqiRankedIdentity
import com.masterofchessstrategy.games.lanzhou_fangqi.FangqiRankedLedger
import com.masterofchessstrategy.games.lanzhou_fangqi.FangqiRankedPosition
import com.masterofchessstrategy.games.lanzhou_fangqi.FangqiRankedReplayResult
import com.masterofchessstrategy.games.lanzhou_fangqi.FangqiRankedStartOrigin
import com.masterofchessstrategy.games.lanzhou_fangqi.FangqiRankedTerminalReceipt
import com.masterofchessstrategy.games.lanzhou_fangqi.FangqiStoredMode
import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

/** Raw structural fixtures only. No Room-generated DAO, SQLite, JNI or trusted source is exercised. */
class FangqiRankedRowMappingTest {
    private val mapping = FangqiRankedRowMapping

    @Test fun everyCreationFieldRoundTripsForAllFourDifficulties() {
        for (difficulty in Difficulty.entries) {
            val creation = creation(difficulty)
            val row = mapping.encodeStartUnvalidated(creation)
            assertEquals(creation, mapping.decodeStartUnvalidated(row))
            assertEquals(creation.sha256, row.creationSha256)
            assertEquals(creation.origin.name, row.startOrigin)
            assertNotSame(creation.initial.history, row.initialHistory)
        }
    }

    @Test fun everyReceiptFieldRoundTripsIncludingIndependentAssistCountersAndOriginalTimes() {
        for (difficulty in Difficulty.entries) {
            val creation = creation(difficulty)
            val limit = when (difficulty) { Difficulty.MEDIUM -> 3; Difficulty.HARD -> 1; Difficulty.MASTER -> 0; else -> 9 }
            val receipt = receipt(creation).let { it.copy(terminal = it.terminal.copy(undoUseCount = limit, hintUseCount = limit)) }
            val row = mapping.encodeSettlementUnvalidated(creation, receipt, null)
            val decoded = mapping.decodeSettlementUnvalidated(row, mapping.encodeStartUnvalidated(creation), outcome(receipt))
            assertEquals(receipt, decoded.receipt)
            assertTrue(decoded.requiresCompletedRecord)
            assertNull(decoded.recordDeletedAtEpochMillis)
            assertNotSame(receipt.terminal.history, row.terminalHistory)
            assertNotSame(row.terminalHistory, decoded.receipt.terminal.history)
        }
    }

    @Test fun tombstoneIsOrthogonalAndNeverChangesTheReceiptOrDropsItsHistory() {
        val creation = creation()
        val receipt = receipt(creation)
        val start = mapping.encodeStartUnvalidated(creation)
        for (deletedAt in listOf(null, receipt.settledAtEpochMillis, Long.MAX_VALUE)) {
            val row = mapping.encodeSettlementUnvalidated(creation, receipt, deletedAt)
            val decoded = mapping.decodeSettlementUnvalidated(row, start, outcome(receipt))
            assertEquals(receipt, decoded.receipt)
            assertEquals(deletedAt, decoded.recordDeletedAtEpochMillis)
            assertEquals(deletedAt == null, decoded.requiresCompletedRecord)
            assertEquals(receipt.terminal.history, row.terminalHistory)
            assertEquals(receipt.settledAtEpochMillis, row.settledAtEpochMillis)
        }
    }

    @Test fun deletionBeforeFirstSettlementIsRejectedInBothDirections() {
        val creation = creation()
        val receipt = receipt(creation)
        val row = mapping.encodeSettlementUnvalidated(creation, receipt, null)
        for (deletedAt in listOf(-1L, 0L, receipt.settledAtEpochMillis - 1)) {
            rejected { mapping.encodeSettlementUnvalidated(creation, receipt, deletedAt) }
            rejected { decode(row.copy(recordDeletedAtEpochMillis = deletedAt)) }
        }
    }

    @Test fun roomBlobConverterOwnsReadsAndWritesWithoutClaimingNativeValidation() {
        val converter = FangqiRankedHistoryConverters()
        val bytes = FangqiRankedLedger.standardInitialHistory().bytes
        val expected = bytes.copyOf()
        val history = converter.historyFromBytesUnvalidated(bytes)
        bytes.fill(0)
        val output = converter.historyToBytesUnvalidated(history)
        output.fill(0)
        assertArrayEquals(expected, converter.historyToBytesUnvalidated(history))
        assertArrayEquals(expected, history.bytes)
    }

    @Test fun mappingCopiesHistoryAtEveryBoundaryAndNoByteGetterExposesTheStoredPayload() {
        val creation = creation()
        val start = mapping.encodeStartUnvalidated(creation)
        val decodedStart = mapping.decodeStartUnvalidated(start)
        assertNotSame(start.initialHistory, decodedStart.initial.history)
        start.initialHistory.bytes.fill(0)
        decodedStart.initial.history.bytes.fill(0)
        assertEquals(creation.initial.history, start.initialHistory)
        assertEquals(creation, decodedStart)
        val row = mapping.encodeSettlementUnvalidated(creation, receipt(creation), null)
        val decoded = decode(row)
        row.terminalHistory.bytes.fill(0)
        decoded.receipt.terminal.history.bytes.fill(0)
        assertEquals(receipt(creation), decoded.receipt)
    }

    @Test fun creationDigestRejectsChangedSessionDifficultyTimeOrSpelling() {
        val row = start()
        for (bad in listOf(row.copy(sessionId = "other-id"), row.copy(difficultyCode = Difficulty.MEDIUM.code),
            row.copy(startedAtEpochMillis = 11), row.copy(creationSha256 = row.creationSha256.uppercase()),
            row.copy(creationSha256 = "0".repeat(64)))) {
            rejected { mapping.decodeStartUnvalidated(bad) }
        }
    }

    @Test fun startRejectsForeignUnknownAndUnsupportedIdentityMetadata() {
        val row = start()
        for (bad in listOf(row.copy(formatVersion = 2), row.copy(gameTypeCode = 1), row.copy(gameTypeCode = 99),
            row.copy(modeCode = 0), row.copy(modeCode = 2), row.copy(modeCode = 99), row.copy(difficultyCode = -1),
            row.copy(difficultyCode = 4), row.copy(humanPlayerIndex = 1), row.copy(rulesVersion = "old"),
            row.copy(engineFormatVersion = 2), row.copy(sessionId = "x".repeat(65)), row.copy(sessionId = "has space"))) {
            rejected { mapping.decodeStartUnvalidated(bad) }
        }
    }

    @Test fun everyNonNewOriginAndNonzeroInitialCounterIsRejected() {
        val row = start()
        for (origin in FangqiRankedStartOrigin.entries.filter { it != FangqiRankedStartOrigin.NEW_STANDARD_HUMAN_GAME }) {
            rejected { mapping.decodeStartUnvalidated(row.copy(startOrigin = origin.name)) }
            rejected { mapping.encodeStartUnvalidated(creation().copy(origin = origin)) }
        }
        for (bad in listOf(row.copy(startOrigin = "future-source"), row.copy(acceptedActionCount = 1),
            row.copy(undoUseCount = 1), row.copy(hintUseCount = 1), row.copy(startedAtEpochMillis = -1))) {
            rejected { mapping.decodeStartUnvalidated(bad) }
        }
    }

    @Test fun startCannotBeAnExistingActionHistoryOrAnAlternativeInitialBoard() {
        val row = start()
        val alternative = row.initialHistory.bytes.toString(Charsets.US_ASCII).replaceFirst("222", "122")
        rejected { mapping.decodeStartUnvalidated(row.copy(initialHistory = FangqiRankedHistory(alternative.toByteArray()))) }
        rejected { mapping.decodeStartUnvalidated(row.copy(initialHistory = history(1), acceptedActionCount = 1)) }
    }

    @Test fun receiptRejectsVersionsCreationDigestAndIdentityChanges() {
        val row = settlement()
        for (bad in listOf(row.copy(formatVersion = 2), row.copy(creationSha256 = "f".repeat(64)),
            row.copy(sessionId = "other"), row.copy(gameTypeCode = 0), row.copy(modeCode = 0),
            row.copy(difficultyCode = Difficulty.HARD.code), row.copy(difficultyCode = 4),
            row.copy(humanPlayerIndex = 1), row.copy(rulesVersion = "future"), row.copy(engineFormatVersion = 2))) {
            rejected { decode(bad) }
        }
    }

    @Test fun receiptClockOrderAndNegativeClockAreRejectedRatherThanClamped() {
        val row = settlement()
        for (bad in listOf(row.copy(updatedAtEpochMillis = -1), row.copy(updatedAtEpochMillis = 9),
            row.copy(settledAtEpochMillis = 19), row.copy(settledAtEpochMillis = -1))) {
            rejected { decode(bad) }
        }
    }

    @Test fun onlyRedAndBlackWinsHaveAnExplicitResultCode() {
        val row = settlement()
        for (code in listOf(-1, 0, 3, 99)) rejected { decode(row.copy(resultCode = code)) }
        for ((result, code) in listOf(GameResult.FIRST_PLAYER_WIN to 1, GameResult.SECOND_PLAYER_WIN to 2)) {
            val receipt = receipt().copy(result = result)
            val encoded = mapping.encodeSettlementUnvalidated(creation(), receipt, null)
            assertEquals(code, encoded.resultCode)
            assertEquals(result, mapping.decodeSettlementUnvalidated(encoded, start(), outcome(receipt)).receipt.result)
        }
        for (result in listOf(GameResult.ONGOING, GameResult.DRAW)) {
            rejected { mapping.encodeSettlementUnvalidated(creation(), receipt().copy(result = result), null) }
        }
    }

    @Test fun difficultyAssistLimitsAndNonnegativeCountsStayStrict() {
        for ((difficulty, allowed) in listOf(Difficulty.MEDIUM to 3, Difficulty.HARD to 1, Difficulty.MASTER to 0)) {
            val creation = creation(difficulty)
            val row = mapping.encodeSettlementUnvalidated(creation, receipt(creation), null)
            val start = mapping.encodeStartUnvalidated(creation)
            for (bad in listOf(row.copy(undoUseCount = allowed + 1), row.copy(hintUseCount = allowed + 1),
                row.copy(undoUseCount = -1), row.copy(hintUseCount = -1))) {
                rejected { mapping.decodeSettlementUnvalidated(bad, start, outcome(receipt(creation))) }
            }
        }
    }

    @Test fun historyEnvelopeCountsAndCanonicalFramingAreRequiredButNotRepaired() {
        val row = settlement()
        for (bad in listOf(row.copy(acceptedActionCount = 0), row.copy(acceptedActionCount = 2),
            row.copy(acceptedActionCount = -1), row.copy(acceptedActionCount = 16_385),
            row.copy(terminalHistory = FangqiRankedHistory("broken".toByteArray())),
            row.copy(terminalHistory = FangqiRankedHistory(history(1).bytes.toString(Charsets.US_ASCII).replace("|1|m", "|01|m").toByteArray())),
            row.copy(terminalHistory = FangqiRankedHistory((history(1).bytes.toString(Charsets.US_ASCII) + ";").toByteArray())),
            row.copy(terminalHistory = FangqiRankedHistory(byteArrayOf(0))))) {
            rejected { decode(bad) }
        }
    }

    @Test fun missingCreationOrOutcomeParentsAreRejectedEvenWithValidSourceFields() {
        rejected { mapping.decodeSettlementUnvalidated(settlement(), null, outcome()) }
        rejected { mapping.decodeSettlementUnvalidated(settlement(), start(), null) }
    }

    @Test fun staleCreationParentWithTheSameIdStillFailsItsDigestAndOriginalDifficultyBinding() {
        val otherStart = mapping.encodeStartUnvalidated(creation(Difficulty.MEDIUM))
        rejected { mapping.decodeSettlementUnvalidated(settlement(), otherStart, outcome()) }
        rejected { mapping.decodeSettlementUnvalidated(settlement(), start().copy(sessionId = "other"), outcome()) }
    }

    @Test fun outcomeParentMustMatchEveryGrowthFieldNotOnlyTheForeignKeyId() {
        val parent = outcome()
        for (bad in listOf(parent.copy(matchId = "other"), parent.copy(gameTypeCode = 0), parent.copy(modeCode = 0),
            parent.copy(difficultyCode = Difficulty.MEDIUM.code), parent.copy(playerIndex = 1),
            parent.copy(resultCode = 2), parent.copy(settledAtEpochMillis = 31))) {
            rejected { mapping.decodeSettlementUnvalidated(settlement(), start(), bad) }
        }
    }

    @Test fun maximumHistoryAndLongClockValuesRoundTripWithoutTruncationOrOverflow() {
        val creation = creation()
        for (count in listOf(10_001, 16_384)) {
            val receipt = receipt(creation).let { it.copy(terminal = it.terminal.copy(history = history(count),
                acceptedActionCount = count, updatedAtEpochMillis = Long.MAX_VALUE), settledAtEpochMillis = Long.MAX_VALUE) }
            val row = mapping.encodeSettlementUnvalidated(creation, receipt, Long.MAX_VALUE)
            val decoded = mapping.decodeSettlementUnvalidated(row, start(), outcome(receipt))
            assertEquals(count, decoded.receipt.terminal.acceptedActionCount)
            assertEquals(receipt, decoded.receipt)
            assertEquals(Long.MAX_VALUE, decoded.recordDeletedAtEpochMillis)
        }
    }

    @Test fun structurallyMappedHistoryRemainsUnvalidatedUntilNativeLedgerAcceptsIt() {
        // A syntactically bounded, illegal move deliberately demonstrates the Unvalidated boundary.
        val receipt = receipt().let { it.copy(terminal = it.terminal.copy(history = history(1, "m,1,35,0"))) }
        val row = mapping.encodeSettlementUnvalidated(creation(), receipt, null)
        val decoded = mapping.decodeSettlementUnvalidated(row, start(), outcome(receipt))
        assertEquals(receipt, decoded.receipt)
        var called = false
        val refusingLedger = FangqiRankedLedger { input ->
            if (input == FangqiRankedLedger.standardInitialHistory()) FangqiRankedReplayResult(input, GameResult.ONGOING, 0)
            else { called = true; throw IOException("Native admission refused") }
        }
        rejected { refusingLedger.validateReceipt(creation(), decoded.receipt) }
        assertTrue(called)
    }

    private fun creation(difficulty: Difficulty = Difficulty.EASY) = FangqiRankedCreation(1,
        FangqiRankedStartOrigin.NEW_STANDARD_HUMAN_GAME, FangqiRankedPosition(FangqiRankedIdentity(
            "ranked-mapping-session", GameType.LANZHOU_SQUARE_CHESS, FangqiStoredMode.HUMAN_VS_AI,
            difficulty, 0, FangqiRankedLedger.RULES_VERSION, 1), FangqiRankedLedger.standardInitialHistory(), 10, 0, 0, 0))

    private fun receipt(creation: FangqiRankedCreation = creation()) = FangqiRankedTerminalReceipt(1, creation.sha256,
        creation.initial.copy(history = history(1), updatedAtEpochMillis = 20, acceptedActionCount = 1),
        GameResult.FIRST_PLAYER_WIN, 30)

    private fun start() = mapping.encodeStartUnvalidated(creation())
    private fun settlement() = mapping.encodeSettlementUnvalidated(creation(), receipt(), null)
    private fun decode(row: FangqiRankedSettlementEntity) = mapping.decodeSettlementUnvalidated(row, start(), outcome())
    private fun outcome(receipt: FangqiRankedTerminalReceipt = receipt()) = MatchOutcomeEntity(
        receipt.terminal.identity.sessionId, 4, 1, receipt.terminal.identity.difficulty.code, 0,
        if (receipt.result == GameResult.FIRST_PLAYER_WIN) 1 else 2, receipt.settledAtEpochMillis)

    private fun history(count: Int, action: String = "m,1,35,28") = FangqiRankedHistory(
        (FangqiRankedLedger.standardInitialHistory().bytes.toString(Charsets.US_ASCII).removeSuffix("|0|") +
            "|$count|" + List(count) { action }.joinToString(";")).toByteArray(Charsets.US_ASCII))

    private fun rejected(action: () -> Unit) {
        try { action(); fail("incompatible raw row accepted") } catch (_: IOException) { }
    }
}
