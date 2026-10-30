package com.masterofchessstrategy.games.lanzhou_fangqi

import com.masterofchessstrategy.engine.Difficulty
import com.masterofchessstrategy.engine.GameResult
import com.masterofchessstrategy.engine.GameType
import com.masterofchessstrategy.games.lanzhou_fangqi.data.FangqiLearningProgressEntity
import com.masterofchessstrategy.games.lanzhou_fangqi.data.FangqiLearningSnapshot
import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

/** Synthetic replay adapter tests the pure boundary. No test here claims Native or Room execution. */
class FangqiRankedLedgerTest {
    private val initialHistory = FangqiRankedLedger.standardInitialHistory()
    private val terminalHistory = history("m,1,35,28")
    private val ledger = FangqiRankedLedger { input ->
        FangqiRankedReplayResult(input,
            if (input == initialHistory) GameResult.ONGOING else GameResult.FIRST_PLAYER_WIN, 0)
    }

    @Test fun newCreationRequiresSixLessonsAndTheRequestedDifficulty() {
        assertEquals(creation(), ledger.admitNewCreation(creation(), eligibility(true)))
        rejected { ledger.admitNewCreation(creation(), eligibility(false)) }
        rejected { ledger.admitNewCreation(creation(Difficulty.MEDIUM), eligibility(true)) }
    }

    @Test fun newCreationRejectsEveryResumeImportLegacyAndNonstandardOrigin() {
        for (origin in FangqiRankedStartOrigin.entries.filter { it != FangqiRankedStartOrigin.NEW_STANDARD_HUMAN_GAME }) {
            rejected { ledger.admitNewCreation(creation().copy(origin = origin), eligibility(true)) }
        }
    }

    @Test fun identityRejectsForeignGameModesHumanSideAndUnknownVersions() {
        val base = creation()
        val identity = base.initial.identity
        val identities = GameType.entries.filter { it != GameType.LANZHOU_SQUARE_CHESS }.map { identity.copy(gameType = it) } +
            listOf(identity.copy(mode = FangqiStoredMode.LOCAL_TWO_PLAYER),
                identity.copy(mode = FangqiStoredMode.AI_AUTO_PLAY), identity.copy(humanPlayerIndex = 1),
                identity.copy(humanPlayerIndex = -1), identity.copy(rulesVersion = "old"),
                identity.copy(engineFormatVersion = 2), identity.copy(sessionId = ""),
                identity.copy(sessionId = "x".repeat(65)), identity.copy(sessionId = "line\nbreak"))
        for (bad in identities) rejected { ledger.validateCreation(base.copy(initial = base.initial.copy(identity = bad))) }
        rejected { ledger.validateCreation(base.copy(formatVersion = 2)) }
    }

    @Test fun initialPositionMustBeExactStandardRedFirstWithZeroCounters() {
        val base = creation()
        val position = base.initial
        val altered = initialHistory.bytes.toString(Charsets.US_ASCII).replace("|1|1|", "|1|2|")
        for (bad in listOf(position.copy(history = FangqiRankedHistory(altered.toByteArray())),
            position.copy(history = terminalHistory, acceptedActionCount = 1),
            position.copy(undoUseCount = 1), position.copy(hintUseCount = 1), position.copy(updatedAtEpochMillis = -1))) {
            rejected { ledger.validateCreation(base.copy(initial = bad)) }
        }
    }

    @Test fun nativeFailureIsPropagatedWithoutIssuingCreationOrReceipt() {
        val broken = FangqiRankedLedger { throw IOException("real restore failed") }
        rejected { broken.admitNewCreation(creation(), eligibility(true)) }
        rejected { broken.firstTerminal(creation(), terminal(), 30, null) }
    }

    @Test fun canonicalReplayMustReturnTheSameOwnedBytes() {
        val altered = FangqiRankedLedger { FangqiRankedReplayResult(terminalHistory, GameResult.ONGOING, 0) }
        rejected { altered.validateCreation(creation()) }
    }

    @Test fun engineCannotDeclareTheOriginalOpeningTerminalOrPendingCapture() {
        for ((result, pending) in listOf(GameResult.FIRST_PLAYER_WIN to 0, GameResult.ONGOING to 1)) {
            rejected { FangqiRankedLedger { FangqiRankedReplayResult(it, result, pending) }.validateCreation(creation()) }
        }
    }

    @Test fun historyAndHashOwnBytesAgainstCallerMutation() {
        val bytes = initialHistory.bytes
        val owned = FangqiRankedHistory(bytes)
        val before = owned.sha256
        bytes.fill(0)
        owned.bytes.fill(0)
        assertEquals(initialHistory, owned)
        assertEquals(initialHistory.hashCode(), owned.hashCode())
        assertEquals(before, owned.sha256)
    }

    @Test fun creationHashBindsSessionDifficultyVersionAndStartTime() {
        val base = creation()
        val variants = listOf(base,
            base.copy(initial = base.initial.copy(identity = base.initial.identity.copy(sessionId = "another"))),
            base.copy(initial = base.initial.copy(identity = base.initial.identity.copy(difficulty = Difficulty.HARD))),
            base.copy(initial = base.initial.copy(updatedAtEpochMillis = 11)),
            base.copy(formatVersion = 2), base.copy(origin = FangqiRankedStartOrigin.BACKUP_IMPORT))
        assertEquals(variants.size, variants.map { it.sha256 }.toSet().size)
        assertEquals(base.sha256, base.copy().sha256)
    }

    @Test fun firstTerminalIsAProposalBoundToCreationAndActualEngineResult() {
        val first = ledger.firstTerminal(creation(), terminal(), 30, null) as FangqiRankedFirstTerminal.Insert
        assertEquals(creation().sha256, first.receipt.creationSha256)
        assertEquals(GameResult.FIRST_PLAYER_WIN, first.receipt.result)
        assertEquals(terminal(), first.receipt.terminal)
        assertEquals(30L, first.receipt.settledAtEpochMillis)
    }

    @Test fun exactRetryReturnsTheOriginalReceiptAndOriginalSettlementTime() {
        val receipt = receipt()
        val retry = ledger.firstTerminal(creation(), terminal(), 999, receipt) as FangqiRankedFirstTerminal.AlreadySettled
        assertSame(receipt, retry.receipt)
        assertEquals(30L, retry.receipt.settledAtEpochMillis)
    }

    @Test fun secondTerminalCannotChangeHistoryCountersTimestampOrDifficulty() {
        val receipt = receipt()
        val changed = listOf(terminal().copy(history = history("m,1,36,29")),
            terminal().copy(undoUseCount = 1), terminal().copy(hintUseCount = 1),
            terminal().copy(updatedAtEpochMillis = 21),
            terminal().copy(identity = terminal().identity.copy(difficulty = Difficulty.HARD)))
        for (position in changed) rejected { ledger.firstTerminal(creation(), position, 99, receipt) }
    }

    @Test fun terminalIdentityMustMatchEveryCreationField() {
        val position = terminal()
        for (identity in listOf(position.identity.copy(sessionId = "other"),
            position.identity.copy(difficulty = Difficulty.MEDIUM), position.identity.copy(humanPlayerIndex = 1),
            position.identity.copy(rulesVersion = "different"))) {
            rejected { ledger.firstTerminal(creation(), position.copy(identity = identity), 30, null) }
        }
    }

    @Test fun terminalRequiresAnActionAndNoPendingCaptureOrUnsupportedDrawOverride() {
        rejected { ledger.firstTerminal(creation(), creation().initial, 30, null) }
        for ((result, pending) in listOf(GameResult.ONGOING to 0, GameResult.DRAW to 0,
            GameResult.FIRST_PLAYER_WIN to 1, GameResult.FIRST_PLAYER_WIN to -1, GameResult.FIRST_PLAYER_WIN to 15)) {
            val other = FangqiRankedLedger { input ->
                FangqiRankedReplayResult(input, if (input == initialHistory) GameResult.ONGOING else result,
                    if (input == initialHistory) 0 else pending)
            }
            rejected { other.firstTerminal(creation(), terminal(), 30, null) }
        }
    }

    @Test fun terminalAndSettlementCannotPredateTheirSource() {
        rejected { ledger.firstTerminal(creation(), terminal().copy(updatedAtEpochMillis = 9), 30, null) }
        rejected { ledger.firstTerminal(creation(), terminal(), 19, null) }
        rejected { ledger.validateReceipt(creation(), receipt().copy(settledAtEpochMillis = -1)) }
    }

    @Test fun currentHistoryLengthMustMatchMetadataAndCanonicalEnvelope() {
        for (position in listOf(terminal().copy(acceptedActionCount = 0), terminal().copy(acceptedActionCount = 2),
            terminal().copy(acceptedActionCount = -1), terminal().copy(acceptedActionCount = 16_385),
            terminal().copy(history = history("m,1,35,28", "01")),
            terminal().copy(history = history("m,1,35,28;", "1")),
            terminal().copy(history = history(";m,1,35,28", "1")),
            terminal().copy(history = FangqiRankedHistory("broken".toByteArray())),
            terminal().copy(history = FangqiRankedHistory(byteArrayOf(0))))) {
            rejected { ledger.firstTerminal(creation(), position, 30, null) }
        }
    }

    @Test fun terminalHistoryCannotSwapItsOriginalBoardOrFirstPlayer() {
        val original = terminalHistory.bytes.toString(Charsets.US_ASCII)
        for (bad in listOf(original.replace("|1|1|", "|1|2|"), original.replaceFirst("222", "122"),
            original.replaceFirst("LFQ|1", "LFQ|2"))) {
            rejected { ledger.firstTerminal(creation(), terminal().copy(history = FangqiRankedHistory(bad.toByteArray())), 30, null) }
        }
    }

    @Test fun eachDifficultyEnforcesBothAssistLimitsIndependently() {
        for ((difficulty, limit) in listOf(Difficulty.MEDIUM to 3, Difficulty.HARD to 1, Difficulty.MASTER to 0)) {
            val start = creation(difficulty)
            val position = terminal(start)
            ledger.firstTerminal(start, position.copy(undoUseCount = limit, hintUseCount = limit), 30, null)
            rejected { ledger.firstTerminal(start, position.copy(undoUseCount = limit + 1), 30, null) }
            rejected { ledger.firstTerminal(start, position.copy(hintUseCount = limit + 1), 30, null) }
        }
        ledger.firstTerminal(creation(), terminal().copy(undoUseCount = Int.MAX_VALUE, hintUseCount = Int.MAX_VALUE), 30, null)
        rejected { ledger.firstTerminal(creation(), terminal().copy(undoUseCount = -1), 30, null) }
    }

    @Test fun receiptRejectsWrongLinkVersionResultAndOrphanSource() {
        val receipt = receipt()
        for (bad in listOf(receipt.copy(creationSha256 = "0".repeat(64)), receipt.copy(formatVersion = 2),
            receipt.copy(result = GameResult.SECOND_PLAYER_WIN))) {
            rejected { ledger.validateReceipt(creation(), bad) }
        }
        rejected { ledger.qualifiedWins(emptyList(), listOf(receipt)) }
    }

    @Test fun duplicateCreationOrReceiptRowsFailEvenWhenIdentical() {
        rejected { ledger.qualifiedWins(listOf(creation(), creation()), listOf(receipt())) }
        rejected { ledger.qualifiedWins(listOf(creation()), listOf(receipt(), receipt())) }
    }

    @Test fun emptyAndOpenLedgersDoNotManufactureWins() {
        assertTrue(ledger.qualifiedWins(emptyList(), emptyList()).winsByDifficulty.values.all { it == 0 })
        assertTrue(ledger.qualifiedWins(listOf(creation()), emptyList()).winsByDifficulty.values.all { it == 0 })
    }

    @Test fun winningReceiptsCountOnlyTheirOwnDifficultyAndLossesDoNotCount() {
        val starts = Difficulty.entries.map { creation(it, "session-${it.code}") }
        val receipts = starts.map { receipt(it) }
        val facts = ledger.qualifiedWins(starts, receipts)
        assertTrue(facts.winsByDifficulty.values.all { it == 1 })
        val losing = FangqiRankedLedger { input -> FangqiRankedReplayResult(input,
            if (input == initialHistory) GameResult.ONGOING else GameResult.SECOND_PLAYER_WIN, 0) }
        val loss = (losing.firstTerminal(creation(), terminal(), 30, null) as FangqiRankedFirstTerminal.Insert).receipt
        assertTrue(losing.qualifiedWins(listOf(creation()), listOf(loss)).winsByDifficulty.values.all { it == 0 })
    }

    @Test fun resumeWithoutCreationCannotMintRankedSource() {
        assertSame(FangqiRankedResume.UnrankedLegacy, ledger.resume(null, terminal(), null))
        val linked = ledger.resume(creation(), terminal(), null) as FangqiRankedResume.Linked
        assertSame(creation().initial.identity.difficulty, linked.creation.initial.identity.difficulty)
        rejected { ledger.resume(creation(), terminal().copy(identity = terminal().identity.copy(sessionId = "legacy")), null) }
    }

    @Test fun settledResumeReturnsTheFirstReceiptAndRejectsReopenedOrOrphanedActiveGames() {
        val receipt = receipt()
        val settled = ledger.resume(creation(), terminal(), receipt) as FangqiRankedResume.Settled
        assertSame(receipt, settled.receipt)
        rejected { ledger.resume(null, terminal(), receipt) }
        rejected { ledger.resume(creation(), creation().initial, receipt) }
        rejected { ledger.resume(creation(), terminal().copy(hintUseCount = 1), receipt) }
    }

    @Test fun completeRestoredRowsPreserveOneWinButPartialOrRetaggedRowsCannotPromoteLegacy() {
        val original = receipt()
        assertEquals(1, ledger.qualifiedWins(listOf(creation()), listOf(original)).winsByDifficulty[Difficulty.EASY])
        rejected { ledger.qualifiedWins(emptyList(), listOf(original)) }
        rejected { ledger.qualifiedWins(listOf(creation().copy(origin = FangqiRankedStartOrigin.BACKUP_IMPORT)), listOf(original)) }
    }

    private fun eligibility(complete: Boolean): FangqiAiEligibility {
        val rows = if (complete) LanzhouFangqiLearningContent.lessons.map {
            FangqiLearningProgressEntity("lesson:${it.id}", 1, 0)
        } else emptyList()
        return FangqiAiAdmission.eligibility(FangqiLearningRead.Ready(FangqiLearningSnapshot(rows)),
            FangqiQualifiedWinsRead.Verified(FangqiQualifiedWinFacts.fromVerifiedLedgerCounts(
                GameType.LANZHOU_SQUARE_CHESS, emptyMap())))
    }

    private fun creation(difficulty: Difficulty = Difficulty.EASY, id: String = "ranked-session") = FangqiRankedCreation(
        1, FangqiRankedStartOrigin.NEW_STANDARD_HUMAN_GAME,
        FangqiRankedPosition(FangqiRankedIdentity(id, GameType.LANZHOU_SQUARE_CHESS, FangqiStoredMode.HUMAN_VS_AI,
            difficulty, 0, FangqiRankedLedger.RULES_VERSION, 1), initialHistory, 10, 0, 0, 0),
    )

    private fun terminal(start: FangqiRankedCreation = creation()) =
        start.initial.copy(history = terminalHistory, updatedAtEpochMillis = 20, acceptedActionCount = 1)

    private fun receipt(start: FangqiRankedCreation = creation()) =
        (ledger.firstTerminal(start, terminal(start), 30, null) as FangqiRankedFirstTerminal.Insert).receipt

    private fun history(actions: String, count: String = "1") = FangqiRankedHistory(
        (FangqiRankedLedger.standardInitialHistory().bytes.toString(Charsets.US_ASCII).substringBeforeLast("|0|") +
            "|$count|$actions").toByteArray(Charsets.US_ASCII),
    )

    private fun rejected(action: () -> Unit) {
        try { action(); fail("incompatible ranked source accepted") } catch (_: IOException) { }
    }
}
