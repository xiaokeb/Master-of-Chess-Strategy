package com.masterofchessstrategy.games.lanzhou_fangqi.data

import com.masterofchessstrategy.data.*
import com.masterofchessstrategy.engine.Difficulty
import com.masterofchessstrategy.engine.GameResult
import com.masterofchessstrategy.engine.GameType
import com.masterofchessstrategy.games.lanzhou_fangqi.*
import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

/** Wire/complete-guard tests use explicit replay doubles, never a purported real Native win. */
class FangqiRankedBackupAdaptersTest {
    @Test fun startAndSettlementUseSixteenAndEighteenCanonicalFields() {
        val a = FangqiRankedBackupWire.encodeStart(start())
        val b = FangqiRankedBackupWire.encodeSettlement(settlement())
        assertEquals(16, a.fields.size); assertEquals(18, b.fields.size)
        assertEquals(listOf("4", "1"), a.fields.take(2))
        assertEquals("-", b.fields.last())
        assertEquals(start(), FangqiRankedBackupWire.decodeStart(a))
        assertEquals(settlement(), FangqiRankedBackupWire.decodeSettlement(b))
        assertEquals(5, startAdapter().descriptor.minimumContainerVersion)
        assertEquals(32, startAdapter().descriptor.canonicalOrder)
        assertEquals(33, FangqiRankedSettlementBackupAdapter().descriptor.canonicalOrder)
    }

    @Test fun printableSessionIdIncludingWireSeparatorRoundTripsThroughLowercaseHex() {
        val id = "A|~"
        val wire = FangqiRankedBackupWire.encodeStart(start(id))
        assertEquals("417c7e", wire.fields[2])
        assertEquals(id, FangqiRankedBackupWire.decodeStart(wire).sessionId)
        for (bad in listOf("", "has space", "x\n", "\u007f", "汉", "a".repeat(65))) {
            rejected { FangqiRankedBackupWire.encodeStart(start().copy(sessionId = bad)) }
        }
        rejected { FangqiRankedBackupWire.decodeStart(wire.with(2, "417C7E")) }
        rejected { FangqiRankedBackupWire.decodeStart(wire.with(2, "20")) }
        rejected { FangqiRankedBackupWire.decodeStart(wire.with(2, "ff")) }
    }

    @Test fun nonCanonicalNumbersOverflowAndSignsAreRejected() {
        val a = FangqiRankedBackupWire.encodeStart(start())
        val b = FangqiRankedBackupWire.encodeSettlement(settlement())
        for (bad in listOf("", "00", "01", "+1", "-1", " 1", "1 ", "1.0", "9223372036854775808")) {
            rejected { FangqiRankedBackupWire.decodeStart(a.with(11, bad)) }
            rejected { FangqiRankedBackupWire.decodeSettlement(b.with(16, bad)) }
        }
        rejected { FangqiRankedBackupWire.decodeSettlement(b.with(12, "2147483648")) }
    }

    @Test fun containerWireContentVersionsOwnersAndFieldCountsCannotBeGuessed() {
        val a = FangqiRankedBackupWire.encodeStart(start())
        val b = FangqiRankedBackupWire.encodeSettlement(settlement())
        rejected { startAdapter().validate(a, 4) }
        rejected { FangqiRankedSettlementBackupAdapter().validate(b, 4) }
        for (wire in listOf(a.with(0, "3"), a.with(1, "2"), a.with(3, "2"), a.with(9, "2"),
            GameBackupRecord(a.kind, a.fields.dropLast(1)))) rejected { startAdapter().validate(wire, 5) }
        for (wire in listOf(b.with(0, "3"), b.with(1, "2"), b.with(3, "2"), b.with(9, "2"),
            GameBackupRecord(b.kind, b.fields + "extra"))) rejected { FangqiRankedSettlementBackupAdapter().validate(wire, 5) }
    }

    @Test fun malformedHexAndDigestAreRejectedWithoutCanonicalizingThem() {
        val a = FangqiRankedBackupWire.encodeStart(start())
        val b = FangqiRankedBackupWire.encodeSettlement(settlement())
        for (bad in listOf("", "0", "gg", a.fields[10].uppercase())) rejected { startAdapter().validate(a.with(10, bad), 5) }
        for (bad in listOf("", "a".repeat(63), "A".repeat(64), "g".repeat(64))) {
            rejected { startAdapter().validate(a.with(15, bad), 5) }
            rejected { FangqiRankedSettlementBackupAdapter().validate(b.with(4, bad), 5) }
        }
    }

    @Test fun tenThousandOneAndSixteenThousandThreeHundredEightyFourActionsAreNotTruncated() {
        for (count in listOf(10_001, 16_384)) {
            val row = settlement().copy(terminalHistory = history(count), acceptedActionCount = count)
            val wire = FangqiRankedBackupWire.encodeSettlement(row)
            val read = FangqiRankedBackupWire.decodeSettlement(wire)
            assertEquals(row, read)
            assertArrayEquals(row.terminalHistory.bytes, read.terminalHistory.bytes)
            assertEquals(count, read.acceptedActionCount)
        }
        val tooMany = settlement().copy(terminalHistory = history(16_385), acceptedActionCount = 16_385)
        rejected { FangqiRankedBackupWire.decodeSettlement(FangqiRankedBackupWire.encodeSettlement(tooMany)) }
    }

    @Test fun oversizedHistoryPayloadIsRejectedBeforeAnyNativeAdmission() {
        val bytes = "a".repeat(1_048_577).toByteArray(Charsets.US_ASCII)
        val hex = bytes.joinToString("") { "61" }
        val wire = FangqiRankedBackupWire.encodeSettlement(settlement()).with(10, hex)
        rejected { FangqiRankedBackupWire.decodeSettlement(wire) }
    }

    @Test fun explicitTombstoneAndMaximumTimesRoundTripExactly() {
        val row = settlement().copy(updatedAtEpochMillis = Long.MAX_VALUE, settledAtEpochMillis = Long.MAX_VALUE,
            recordDeletedAtEpochMillis = Long.MAX_VALUE)
        assertEquals(row, FangqiRankedBackupWire.decodeSettlement(FangqiRankedBackupWire.encodeSettlement(row)))
        assertEquals(null, FangqiRankedBackupWire.decodeSettlement(FangqiRankedBackupWire.encodeSettlement(settlement())).recordDeletedAtEpochMillis)
        rejected { FangqiRankedBackupWire.decodeSettlement(FangqiRankedBackupWire.encodeSettlement(settlement()).with(17, "29")) }
    }

    @Test fun validateAndPrepareRemainStructuralWhileCompleteGuardActuallyCallsReplay() {
        var calls = 0
        val adapter = startAdapter { input -> calls++; replay(input) }
        val a = FangqiRankedBackupWire.encodeStart(start())
        val b = FangqiRankedBackupWire.encodeSettlement(settlement())
        adapter.validate(a, 5); adapter.prepareRestore(a)
        FangqiRankedSettlementBackupAdapter().validate(b, 5)
        assertEquals(0, calls)
        registry(adapter).prepareRestore(listOf(b, a), 5, setOf(ID), listOf(outcome()), listOf(record()))
        assertTrue(calls > 0)
    }

    @Test fun completeGuardRequiresActualCoreRecordPayloadNotOnlyItsDeclaredId() {
        rejected { registry().prepareRestore(wires(), 5, setOf(ID), listOf(outcome())) }
        val prepared = registry().prepareRestore(wires(), 5, setOf(ID), listOf(outcome()), listOf(record()))
        assertEquals(2, prepared.size)
        assertTrue(prepared[0] is PreparedFangqiRankedStart)
        assertTrue(prepared[1] is PreparedFangqiRankedSettlement)
    }

    @Test fun coreRecordAndOutcomeFieldsMustMatchTheOriginalReceipt() {
        val r = record()
        val otherHistory = history(1, "m,1,35,21").bytes
        for (bad in listOf(r.copy(difficulty = Difficulty.HARD), r.copy(result = GameResult.SECOND_PLAYER_WIN),
            r.copy(completedAtEpochMillis = 31), r.copy(playerIndex = 1), r.copy(engineFormatVersion = 2),
            r.copy(engineState = otherHistory), r.copy(mode = StoredGameMode.ENDGAME, isEndgame = true))) {
            rejected { registry().prepareRestore(wires(), 5, setOf(ID), listOf(outcome()), listOf(bad)) }
        }
        for (bad in listOf(outcome().copy(difficulty = Difficulty.HARD), outcome().copy(playerIndex = 1),
            outcome().copy(result = GameResult.SECOND_PLAYER_WIN), outcome().copy(settledAtEpochMillis = 31))) {
            rejected { registry().prepareRestore(wires(), 5, setOf(ID), listOf(bad), listOf(record())) }
        }
        registry().prepareRestore(wires(), 5, setOf(ID), listOf(outcome()), listOf(r.copy(isFavorite = true)))
    }

    @Test fun deletedRecordMustStayAbsentAndCannotBeRestoredByCorePayload() {
        val wires = listOf(FangqiRankedBackupWire.encodeStart(start()),
            FangqiRankedBackupWire.encodeSettlement(settlement().copy(recordDeletedAtEpochMillis = 40)))
        val prepared = registry().prepareRestore(wires, 5, emptySet(), listOf(outcome()), emptyList())
        assertEquals(40L, (prepared[1] as PreparedFangqiRankedSettlement).row.recordDeletedAtEpochMillis)
        rejected { registry().prepareRestore(wires, 5, setOf(ID), listOf(outcome()), listOf(record())) }
        rejected { registry().prepareRestore(wires(), 5, emptySet(), listOf(outcome()), emptyList()) }
    }

    @Test fun missingStartReceiptOrOutcomeNeverDowngradesIntoAValidLedger() {
        rejected { registry().prepareRestore(wires().drop(1), 5, setOf(ID), listOf(outcome()), listOf(record())) }
        rejected { registry().prepareRestore(wires().take(1), 5, setOf(ID), listOf(outcome()), listOf(record())) }
        rejected { registry().prepareRestore(wires(), 5, setOf(ID), emptyList(), listOf(record())) }
        rejected { registry().prepareRestore(wires() + wires().take(1), 5, setOf(ID), listOf(outcome()), listOf(record())) }
    }

    @Test fun completeAdmissionRejectsNativeFailureAndForgedWinnerEvenAfterWireValidation() {
        val refuses = startAdapter { input -> if (input == root()) replay(input) else throw IOException("explicit replay rejection") }
        rejected { registry(refuses).prepareRestore(wires(), 5, setOf(ID), listOf(outcome()), listOf(record())) }
        val loses = startAdapter { input -> if (input == root()) replay(input)
            else FangqiRankedReplayResult(input, GameResult.SECOND_PLAYER_WIN, 0) }
        rejected { registry(loses).prepareRestore(wires(), 5, setOf(ID), listOf(outcome()), listOf(record())) }
    }

    private fun startAdapter(replay: FangqiRankedNativeReplay = FangqiRankedNativeReplay(::replay)) =
        FangqiRankedStartBackupAdapter(FangqiRankedSnapshotValidator(FangqiRankedLedger(replay), FangqiSessionAdmission { it }))
    private fun registry(adapter: FangqiRankedStartBackupAdapter = startAdapter()) =
        GameBackupRegistry(listOf(adapter, FangqiRankedSettlementBackupAdapter()))
    private fun replay(input: FangqiRankedHistory) = FangqiRankedReplayResult(input,
        if (input == root()) GameResult.ONGOING else GameResult.FIRST_PLAYER_WIN, 0)
    private fun root() = FangqiRankedLedger.standardInitialHistory()
    private fun creation(id: String = ID) = FangqiRankedCreation(1, FangqiRankedStartOrigin.NEW_STANDARD_HUMAN_GAME,
        FangqiRankedPosition(FangqiRankedIdentity(id, GameType.LANZHOU_SQUARE_CHESS, FangqiStoredMode.HUMAN_VS_AI,
            Difficulty.EASY, 0, FangqiRankedLedger.RULES_VERSION, 1), root(), 10, 0, 0, 0))
    private fun receipt() = FangqiRankedTerminalReceipt(1, creation().sha256,
        creation().initial.copy(history = history(1), updatedAtEpochMillis = 20, acceptedActionCount = 1), GameResult.FIRST_PLAYER_WIN, 30)
    private fun start(id: String = ID) = FangqiRankedRowMapping.encodeStartUnvalidated(creation(id))
    private fun settlement() = FangqiRankedRowMapping.encodeSettlementUnvalidated(creation(), receipt(), null)
    private fun wires() = listOf(FangqiRankedBackupWire.encodeStart(start()), FangqiRankedBackupWire.encodeSettlement(settlement()))
    private fun outcome() = MatchOutcome(ID, GameType.LANZHOU_SQUARE_CHESS, StoredGameMode.HUMAN_VS_AI,
        Difficulty.EASY, 0, GameResult.FIRST_PLAYER_WIN, 30)
    private fun record() = GameRecord(ID, GameType.LANZHOU_SQUARE_CHESS, StoredGameMode.HUMAN_VS_AI,
        Difficulty.EASY, GameResult.FIRST_PLAYER_WIN, history(1).bytes, 1, completedAtEpochMillis = 30, engineFormatVersion = 1)
    private fun history(count: Int, action: String = "m,1,35,28") = FangqiRankedHistory(
        (root().bytes.toString(Charsets.US_ASCII).removeSuffix("|0|") + "|$count|" + List(count) { action }.joinToString(";")).toByteArray(Charsets.US_ASCII))
    private fun GameBackupRecord.with(index: Int, value: String) = GameBackupRecord(kind,
        fields.mapIndexed { i, s -> if (i == index) value else s })
    private fun rejected(action: () -> Unit) {
        try { action(); fail("Invalid ranked backup accepted") }
        catch (_: IllegalArgumentException) { } catch (_: IOException) { }
    }
    companion object { private const val ID = "backup-ranked-session" }
}
