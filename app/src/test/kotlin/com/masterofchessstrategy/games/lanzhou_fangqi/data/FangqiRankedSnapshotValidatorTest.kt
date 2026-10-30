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

/** Explicit replay doubles exercise provenance policy, not legal Native wins or SQLite. */
class FangqiRankedSnapshotValidatorTest {
    @Test fun completeCompanionsProduceOneQualifiedHumanWin() {
        val value = validator().validate(complete())
        assertEquals(creation(), value.creations.getValue(ID))
        assertEquals(receipt(), value.settlements.getValue(ID).receipt)
        assertEquals(1, value.wins.winsByDifficulty.getValue(Difficulty.EASY))
        assertEquals(0, value.wins.winsByDifficulty.getValue(Difficulty.MASTER))
        assertNull(value.activeLink)
    }

    @Test fun losingHumanMatchIsVerifiedButDoesNotBecomeAWin() {
        val r = receipt().copy(result = GameResult.SECOND_PLAYER_WIN)
        val value = validator(result = GameResult.SECOND_PLAYER_WIN).validate(complete(r))
        assertTrue(value.wins.winsByDifficulty.values.all { it == 0 })
        assertEquals(r, value.settlements.getValue(ID).receipt)
    }

    @Test fun genericOldOutcomesAndStandaloneFormatOneRecordsNeverCreateRankedSources() {
        val value = validator().validate(empty().copy(outcomes = listOf(outcome()), records = listOf(record())))
        assertTrue(value.creations.isEmpty())
        assertTrue(value.settlements.isEmpty())
        assertTrue(value.wins.winsByDifficulty.values.all { it == 0 })
    }

    @Test fun receiptWithoutEitherActualParentIsRejected() {
        rejected(complete().copy(starts = emptyList()))
        rejected(complete().copy(outcomes = emptyList()))
    }

    @Test fun sourceDigestAndOutcomeMetadataMustMatchNotOnlyTheirIds() {
        rejected(complete().copy(starts = listOf(start().copy(creationSha256 = "0".repeat(64)))))
        for (bad in listOf(outcome().copy(gameTypeCode = 0), outcome().copy(modeCode = 0),
            outcome().copy(difficultyCode = Difficulty.HARD.code), outcome().copy(playerIndex = 1),
            outcome().copy(resultCode = 2), outcome().copy(settledAtEpochMillis = 31))) {
            rejected(complete().copy(outcomes = listOf(bad)))
        }
    }

    @Test fun duplicatePrimaryKeysAreRejectedInAllSnapshotFamilies() {
        val s = complete()
        for (bad in listOf(s.copy(starts = s.starts + s.starts), s.copy(settlements = s.settlements + s.settlements),
            s.copy(outcomes = s.outcomes + s.outcomes), s.copy(records = s.records + s.records))) rejected(bad)
    }

    @Test fun unmarkedMissingRecordIsNotInferredToBeAUserDeletion() {
        rejected(complete().copy(records = emptyList()))
    }

    @Test fun explicitDeletionPreservesReceiptOutcomeAndQualifiedWinWithoutRecreatingRecord() {
        val s = complete().copy(settlements = listOf(settlement(deletedAt = 40)), records = emptyList())
        val value = validator().validate(s)
        assertEquals(40L, value.settlements.getValue(ID).recordDeletedAtEpochMillis)
        assertEquals(receipt(), value.settlements.getValue(ID).receipt)
        assertEquals(1, value.wins.winsByDifficulty.getValue(Difficulty.EASY))
        assertTrue(s.records.isEmpty())
    }

    @Test fun deletedRecordCannotStillExistOrPredateFirstSettlement() {
        rejected(complete().copy(settlements = listOf(settlement(deletedAt = 40))))
        rejected(complete().copy(settlements = listOf(settlement().copy(recordDeletedAtEpochMillis = 29)), records = emptyList()))
    }

    @Test fun recordCompanionshipChecksEveryImmutableFieldAndCompleteHistory() {
        val r = record()
        for (bad in listOf(r.copy(gameTypeCode = 0), r.copy(modeCode = 0),
            r.copy(difficultyCode = Difficulty.HARD.code), r.copy(playerIndex = 1), r.copy(resultCode = 2),
            r.copy(engineFormatVersion = 2), r.copy(isEndgame = true), r.copy(moveCount = 0),
            r.copy(completedAtEpochMillis = 20), r.copy(engineState = root().bytes))) {
            rejected(complete().copy(records = listOf(bad)))
        }
    }

    @Test fun favoriteFlagIsAUserPreferenceAndDoesNotConflictWithImmutableReceipt() {
        val value = validator().validate(complete().copy(records = listOf(record().copy(isFavorite = true))))
        assertEquals(1, value.wins.winsByDifficulty.getValue(Difficulty.EASY))
    }

    @Test fun creationWithOutcomeOrRecordButNoFirstReceiptFailsClosed() {
        rejected(complete().copy(settlements = emptyList(), records = emptyList()))
        rejected(complete().copy(settlements = emptyList(), outcomes = emptyList()))
    }

    @Test fun nativeRefusalAndNativeResultContradictionCannotBecomeQualifiedFacts() {
        val refuses = FangqiRankedSnapshotValidator(FangqiRankedLedger { input ->
            if (input == root()) FangqiRankedReplayResult(input, GameResult.ONGOING, 0)
            else throw IOException("explicit replay rejection")
        }, FangqiSessionAdmission { it })
        rejected(complete(), refuses)
        rejected(complete(), validator(result = GameResult.SECOND_PLAYER_WIN))
        rejected(complete(), validator(pending = 1))
    }

    @Test fun formalOngoingActiveGameLinksOnlyToItsExactCreation() {
        val value = validator().validate(empty().copy(starts = listOf(start()), active = active()))
        assertEquals(FangqiRankedResume.Linked(creation()), value.activeLink)
        assertEquals(ID, value.active!!.sessionId)
        assertTrue(value.wins.winsByDifficulty.values.all { it == 0 })
        rejected(empty().copy(starts = listOf(start()), active = active().copy(difficultyCode = Difficulty.MEDIUM.code)))
    }

    @Test fun settledActiveGameMustRemainTheExactFirstTerminalSnapshot() {
        val terminal = receipt().terminal
        val row = active().copy(engineState = terminal.history.bytes, acceptedMoveCount = 1, updatedAtEpochMillis = 20)
        val value = validator().validate(complete().copy(active = row))
        assertEquals(FangqiRankedResume.Settled(receipt()), value.activeLink)
        rejected(complete().copy(active = active()))
        rejected(complete().copy(active = row.copy(updatedAtEpochMillis = 21)))
    }

    @Test fun sourceFreeLegacyLocalGameCanResumeButNeverReceivesRankedCredit() {
        val row = active().copy(sessionId = "legacy-local", modeCode = 0, difficultyCode = null)
        val value = validator().validate(empty().copy(active = row, outcomes = listOf(outcome())))
        assertEquals(FangqiRankedResume.UnrankedLegacy, value.activeLink)
        assertEquals(FangqiStoredMode.LOCAL_TWO_PLAYER, value.active!!.mode)
        assertTrue(value.wins.winsByDifficulty.values.all { it == 0 })
    }

    @Test fun activeEnvelopeCannotSmuggleOtherModesOverridesTimersOrAssistCounts() {
        val row = active()
        val badRows = listOf(row.copy(gameTypeCode = 0), row.copy(modeCode = 99), row.copy(difficultyCode = 99),
            row.copy(envelopeVersion = 4), row.copy(engineFormatVersion = 2), row.copy(playerIndex = 1),
            row.copy(resultOverrideCode = 1), row.copy(timeControlMinutes = 1), row.copy(redRemainingMillis = 1),
            row.copy(blackRemainingMillis = 1), row.copy(turnStartedAtEpochMillis = 1), row.copy(pendingDrawOfferSideCode = 0),
            row.copy(autoPlayPaused = true), row.copy(autoPlaySpeedPermille = 999), row.copy(completedAutoGames = 1),
            row.copy(sessionVariantId = "practice"), row.copy(acceptedMoveCount = 1), row.copy(updatedAtEpochMillis = -1),
            row.copy(undoUseCount = -1), row.copy(hintUseCount = -1))
        for (bad in badRows) rejected(empty().copy(active = bad))
    }

    @Test fun activeNativeAdmissionFailureIsNotConvertedToMissingGame() {
        val refuses = FangqiRankedSnapshotValidator(FangqiRankedLedger(::replay),
            FangqiSessionAdmission { throw IOException("explicit active rejection") })
        rejected(empty().copy(active = active()), refuses)
    }

    @Test fun returnedMapsAndOwnedActiveBytesCannotBeMutatedThroughTheResult() {
        val input = active().copy(sessionId = "old-local", modeCode = 0, difficultyCode = null)
        val value = validator().validate(complete().copy(active = input))
        try { (value.creations as MutableMap).clear(); fail("Mutable creation map") }
        catch (_: UnsupportedOperationException) { }
        try { (value.settlements as MutableMap).clear(); fail("Mutable settlement map") }
        catch (_: UnsupportedOperationException) { }
        input.engineState.fill(0)
        value.active!!.engineState.fill(0)
        assertArrayEquals(root().bytes, value.active.engineState)
        assertEquals(creation(), value.creations.getValue(ID))
    }

    private fun validator(result: GameResult = GameResult.FIRST_PLAYER_WIN, pending: Int = 0) =
        FangqiRankedSnapshotValidator(FangqiRankedLedger { input ->
            if (input == root()) replay(input) else FangqiRankedReplayResult(input, result, pending)
        }, FangqiSessionAdmission { it })

    private fun replay(input: FangqiRankedHistory) = FangqiRankedReplayResult(input,
        if (input == root()) GameResult.ONGOING else GameResult.FIRST_PLAYER_WIN, 0)
    private fun root() = FangqiRankedLedger.standardInitialHistory()
    private fun creation() = FangqiRankedCreation(1, FangqiRankedStartOrigin.NEW_STANDARD_HUMAN_GAME,
        FangqiRankedPosition(FangqiRankedIdentity(ID, GameType.LANZHOU_SQUARE_CHESS,
            FangqiStoredMode.HUMAN_VS_AI, Difficulty.EASY, 0, FangqiRankedLedger.RULES_VERSION, 1), root(), 10, 0, 0, 0))
    private fun receipt() = FangqiRankedTerminalReceipt(1, creation().sha256, creation().initial.copy(
        history = FangqiRankedHistory((root().bytes.toString(Charsets.US_ASCII).removeSuffix("|0|") + "|1|m,1,35,28").toByteArray(Charsets.US_ASCII)),
        updatedAtEpochMillis = 20, acceptedActionCount = 1), GameResult.FIRST_PLAYER_WIN, 30)
    private fun start() = FangqiRankedRowMapping.encodeStartUnvalidated(creation())
    private fun settlement(r: FangqiRankedTerminalReceipt = receipt(), deletedAt: Long? = null) =
        FangqiRankedRowMapping.encodeSettlementUnvalidated(creation(), r, deletedAt)
    private fun outcome(r: FangqiRankedTerminalReceipt = receipt()) = MatchOutcomeEntity(ID, 4, 1,
        Difficulty.EASY.code, 0, if (r.result == GameResult.FIRST_PLAYER_WIN) 1 else 2, r.settledAtEpochMillis)
    private fun record(r: FangqiRankedTerminalReceipt = receipt()) = GameRecordEntity(ID, 4, 1,
        Difficulty.EASY.code, if (r.result == GameResult.FIRST_PLAYER_WIN) 1 else 2, 1, r.terminal.history.bytes,
        r.terminal.acceptedActionCount, completedAtEpochMillis = r.settledAtEpochMillis)
    private fun active() = ActiveGameEntity(4, 1, Difficulty.EASY.code, 5, 1, root().bytes, 10, sessionId = ID)
    private fun empty() = FangqiRankedStorageSnapshot(emptyList(), emptyList(), null, emptyList(), emptyList())
    private fun complete(r: FangqiRankedTerminalReceipt = receipt()) = FangqiRankedStorageSnapshot(
        listOf(start()), listOf(settlement(r)), null, listOf(outcome(r)), listOf(record(r)))
    private fun rejected(snapshot: FangqiRankedStorageSnapshot, validator: FangqiRankedSnapshotValidator = validator()) {
        try { validator.validate(snapshot); fail("Invalid provenance snapshot accepted") } catch (_: IOException) { }
    }

    companion object { private const val ID = "ranked-snapshot-session" }
}
