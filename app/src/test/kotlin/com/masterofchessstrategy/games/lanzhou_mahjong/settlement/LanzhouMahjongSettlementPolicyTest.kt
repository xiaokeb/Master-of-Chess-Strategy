package com.masterofchessstrategy.games.lanzhou_mahjong.settlement

import com.masterofchessstrategy.engine.Difficulty
import com.masterofchessstrategy.engine.ActionResult
import com.masterofchessstrategy.engine.EngineError
import com.masterofchessstrategy.engine.LanzhouMahjongAction
import com.masterofchessstrategy.engine.LanzhouMahjongOutcomeView
import com.masterofchessstrategy.engine.LanzhouMahjongPhase
import com.masterofchessstrategy.engine.LanzhouMahjongPublicView
import com.masterofchessstrategy.engine.LanzhouMahjongRuleSession
import com.masterofchessstrategy.engine.RestoreResult
import com.masterofchessstrategy.games.lanzhou_mahjong.LanzhouMahjongSessionRecord
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Test

/** Structural LZMJ fixtures and a replay double validate policy, not actual Mahjong legality. */
class LanzhouMahjongSettlementPolicyTest {
    @Test fun legitimateSelfDrawAwardsOnlyTheNativeWinner() {
        val fixture = Fixture()
        val receipt = fixture.settle()
        assertEquals(LanzhouMahjongQualifiedOutcome.WIN, receipt.outcome)
        assertTrue(receipt.isQualifiedWin)
        assertEquals(listOf(6L, -2L, -2L, -2L), receipt.netScores)
        assertNull(receipt.payerSeat)
    }

    @Test fun discardWinnerControlsQualificationEvenWhenAnotherSeatHasPositiveKongBalance() {
        val fixture = Fixture()
        fixture.replay.terminalView = view(winner = 1, payer = 2, scores = listOf(5, 3, -4, -4))
        val receipt = fixture.settle()
        assertEquals(LanzhouMahjongQualifiedOutcome.LOSS, receipt.outcome)
        assertEquals(1, receipt.winnerSeat)
        assertFalse(receipt.isQualifiedWin)
        assertEquals(5L, receipt.netScores[0])
    }

    @Test fun aLegitimateWinnerWithNegativeFinalBalanceStillCountsAsWinner() {
        val fixture = Fixture()
        fixture.replay.terminalView = view(scores = listOf(-1, 5, -2, -2))
        assertTrue(fixture.settle().isQualifiedWin)
    }

    @Test fun exhaustiveDrawDoesNotAwardWinsAndPreservesAllFourZeroAmounts() {
        val fixture = Fixture()
        fixture.replay.terminalView = view(
            phase = LanzhouMahjongPhase.EXHAUSTIVE_DRAW, winner = null, scores = List(4) { 0 },
        )
        val receipt = fixture.settle()
        assertEquals(LanzhouMahjongQualifiedOutcome.EXHAUSTIVE_DRAW, receipt.outcome)
        assertFalse(receipt.isQualifiedWin)
        assertEquals(List(4) { 0L }, receipt.netScores)
    }

    @Test fun falseDeclarationDoesNotTurnOtherSeatsPenaltyIncomeIntoWins() {
        val fixture = Fixture()
        fixture.replay.terminalView = view(
            winner = null, payer = 1, falseWin = true, scores = listOf(24, -72, 24, 24),
        )
        val receipt = fixture.settle()
        assertEquals(LanzhouMahjongQualifiedOutcome.FALSE_DECLARATION, receipt.outcome)
        assertFalse(receipt.isQualifiedWin)
        assertEquals(listOf(24L, -72L, 24L, 24L), receipt.netScores)
    }

    @Test fun legalReplayWithoutCommittedCreationEvidenceCannotCreateQualification() {
        val fixture = Fixture()
        assertThrows(IOException::class.java) { fixture.policy.firstTerminal(null, fixture.terminal) }
        assertEquals(0, fixture.replay.replayCalls)
    }

    @Test fun legacyPracticeResumeAndImportCannotInventANewCreation() {
        val fixture = Fixture()
        for (origin in LanzhouMahjongCreationOrigin.entries.filter {
            it != LanzhouMahjongCreationOrigin.AUTHORIZED_STANDARD_HUMAN
        }) {
            val creation = LanzhouMahjongFormalCreation(fixture.id, origin, fixture.initial, 100)
            assertThrows(IOException::class.java) { fixture.policy.firstTerminal(creation, fixture.terminal) }
        }
        val legacy = record(bytes = nativeBytes(version = 1))
        assertThrows(IOException::class.java) {
            fixture.policy.validateCreationCandidate(LanzhouMahjongFormalCreation(
                fixture.replay.sessionId(legacy), LanzhouMahjongCreationOrigin.AUTHORIZED_STANDARD_HUMAN,
                legacy, 100,
            ))
        }
    }

    @Test fun wrongSessionSeatDifficultySeedAndBaseAreRejected() {
        val fixture = Fixture()
        assertThrows(IOException::class.java) {
            fixture.policy.firstTerminal(fixture.creation, LanzhouMahjongTerminalCandidate(
                "lzmj-" + "0".repeat(64), fixture.finished, 200,
            ))
        }
        val mismatches = listOf(
            record(viewer = 1, bytes = nativeBytes(actions = 1)),
            record(difficulty = Difficulty.MEDIUM, bytes = nativeBytes(actions = 1)),
            record(seed = 8, bytes = nativeBytes(actions = 1)),
            record(base = 2, bytes = nativeBytes(actions = 1, base = 2)),
        )
        for (record in mismatches) {
            assertThrows(IOException::class.java) {
                fixture.policy.firstTerminal(fixture.creation,
                    LanzhouMahjongTerminalCandidate(fixture.id, record, 200))
            }
        }
    }

    @Test fun aSeedThatDoesNotGenerateTheRecordedInitialWallCannotProveCreation() {
        val fixture = Fixture()
        fixture.replay.wrongFreshInitial = true
        assertThrows(IOException::class.java) { fixture.policy.validateCreationCandidate(fixture.creation) }
    }

    @Test fun aNonterminalReplayIsRejectedRegardlessOfCandidateMetadata() {
        val fixture = Fixture()
        fixture.replay.terminalView = view(phase = LanzhouMahjongPhase.DISCARD)
        assertThrows(IOException::class.java) { fixture.settle() }
    }

    @Test fun rewritingTheChecksumDoesNotMakeAFabricatedHistoryIntoLegalReplay() {
        val fixture = Fixture()
        val forged = nativeBytes(actions = 1).apply {
            this[128] = 25
            refreshChecksum(this)
        }
        val structurallyAccepted = record(bytes = forged)
        assertThrows(IOException::class.java) {
            fixture.policy.firstTerminal(fixture.creation,
                LanzhouMahjongTerminalCandidate(fixture.id, structurallyAccepted, 200))
        }
        assertEquals(2, fixture.replay.replayCalls) // Creation succeeds; terminal reaches replay and fails.
    }

    @Test fun retriesWithANewTimestampReturnTheOriginalReceiptWithoutASecondInsert() {
        val fixture = Fixture()
        val first = fixture.settle()
        val retry = fixture.policy.firstTerminal(fixture.creation,
            LanzhouMahjongTerminalCandidate(fixture.id, fixture.finished, 999), first)
        assertTrue(retry is LanzhouMahjongFirstSettlement.AlreadySettled)
        assertSame(first, (retry as LanzhouMahjongFirstSettlement.AlreadySettled).receipt)
        assertEquals(200L, retry.receipt.terminal.capturedAt)
    }

    @Test fun aDifferentTerminalForTheSameIdentityIsAConflict() {
        val fixture = Fixture()
        val first = fixture.settle()
        fixture.replay.allowedTerminal = nativeBytes(actions = 2)
        assertThrows(IOException::class.java) {
            fixture.policy.firstTerminal(fixture.creation,
                LanzhouMahjongTerminalCandidate(fixture.id, record(bytes = nativeBytes(actions = 2)), 300), first)
        }
    }

    @Test fun incorrectFourSeatTotalsAreRejectedWithoutLongOverflow() {
        val fixture = Fixture()
        for (scores in listOf(listOf(1L, 2L, 3L), listOf(Long.MAX_VALUE, Long.MAX_VALUE, 2L, 0L))) {
            fixture.replay.terminalView = view().copy(netScores = scores)
            assertThrows(IOException::class.java) { fixture.settle() }
        }
    }

    @Test fun creationCandidateReceiptAndReplayResultsOwnTheirMutableInputs() {
        val fixture = Fixture()
        val bytes = fixture.finished.nativeState
        val record = record(bytes = bytes)
        val terminal = LanzhouMahjongTerminalCandidate(fixture.id, record, 200)
        bytes[14] = 20
        terminal.record.nativeState[14] = 20
        terminal.copyRecordBytes()[0] = 0
        assertArrayEquals(fixture.finished.nativeState, terminal.record.nativeState)
        val receipt = fixture.settle()
        (receipt.netScores as MutableList<Long>)[0] = 999
        assertEquals(6L, receipt.netScores[0])
        fixture.creation.initial.nativeState[14] = 20
        assertArrayEquals(fixture.initial.nativeState, fixture.creation.initial.nativeState)
        val replayed = fixture.replay.replay(fixture.finished)
        replayed.copyInitialState()[0] = 0
        replayed.copyCanonicalState()[0] = 0
        assertArrayEquals(fixture.finished.nativeState, replayed.copyCanonicalState())
    }

    @Test fun anExistingReceiptWithForgedAmountsCannotPassAsAnIdempotentRetry() {
        val fixture = Fixture()
        val correct = fixture.settle()
        val forged = LanzhouMahjongTerminalReceipt(
            correct.creationSha256, correct.terminal, correct.difficulty, correct.viewerSeat,
            correct.nativeRuleVersion, correct.baseScore, correct.outcome, correct.winnerSeat,
            correct.payerSeat, listOf(9, -3, -3, -3),
        )
        assertThrows(IOException::class.java) {
            fixture.policy.firstTerminal(fixture.creation, fixture.terminal, forged)
        }
    }

    @Test fun replayCanonicalBytesMustMatchTheWholeHistory() {
        val fixture = Fixture()
        fixture.replay.wrongCanonical = true
        assertThrows(IOException::class.java) { fixture.settle() }
    }

    @Test fun injectedNativeAdapterReusesAdmissionAndClosesItsFreshReplaySession() {
        val sessions = mutableListOf<InjectedNativeSession>()
        val native = LanzhouMahjongNativeSettlementReplay { record ->
            InjectedNativeSession(record).also { sessions += it }
        }
        val fixture = Fixture()
        val policy = LanzhouMahjongSettlementPolicy(native)
        val receipt = (policy.firstTerminal(fixture.creation, fixture.terminal) as
            LanzhouMahjongFirstSettlement.Insert).receipt
        assertTrue(receipt.isQualifiedWin)
        assertEquals(2, sessions.size)
        assertTrue(sessions.all { it.closed })
    }

    @Test fun injectedNativeAdapterRejectsCorrectChecksumWhenNativeRestoreRejectsItsActions() {
        val sessions = mutableListOf<InjectedNativeSession>()
        val native = LanzhouMahjongNativeSettlementReplay { record ->
            InjectedNativeSession(record).also { sessions += it }
        }
        val fixture = Fixture()
        val forged = nativeBytes(actions = 1).apply { this[128] = 25; refreshChecksum(this) }
        val terminal = LanzhouMahjongTerminalCandidate(fixture.id, record(bytes = forged), 200)
        assertThrows(IOException::class.java) {
            LanzhouMahjongSettlementPolicy(native).firstTerminal(fixture.creation, terminal)
        }
        assertEquals(2, sessions.size)
        assertTrue(sessions.all { it.closed })
    }

    @Test fun injectedNativeAdapterCannotTreatRestoredWallAsProofOfTheSeedGeneratedWall() {
        val fixture = Fixture()
        val native = LanzhouMahjongNativeSettlementReplay { record ->
            InjectedNativeSession(record, wrongInitial = true)
        }
        assertThrows(IOException::class.java) {
            LanzhouMahjongSettlementPolicy(native).validateCreationCandidate(fixture.creation)
        }
    }

    private class Fixture {
        val replay = ReplayDouble()
        val policy = LanzhouMahjongSettlementPolicy(replay)
        val initial = record()
        val finished = record(bytes = nativeBytes(actions = 1))
        val id = replay.sessionId(initial)
        val creation = LanzhouMahjongFormalCreation(
            id, LanzhouMahjongCreationOrigin.AUTHORIZED_STANDARD_HUMAN, initial, 100,
        )
        val terminal = LanzhouMahjongTerminalCandidate(id, finished, 200)
        fun settle() = (policy.firstTerminal(creation, terminal) as LanzhouMahjongFirstSettlement.Insert).receipt
    }

    private class ReplayDouble : LanzhouMahjongSettlementReplay {
        var terminalView = view()
        var wrongFreshInitial = false
        var wrongCanonical = false
        var replayCalls = 0
        var allowedTerminal = nativeBytes(actions = 1)
        override fun sessionId(record: LanzhouMahjongSessionRecord): String {
            val digest = MessageDigest.getInstance("SHA-256")
            digest.update(record.nativeState, 0, 122)
            digest.update(record.viewerSeat.toByte())
            digest.update(record.difficulty.code.toByte())
            return "lzmj-" + digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
        }
        override fun replay(record: LanzhouMahjongSessionRecord): LanzhouMahjongSettlementReplayResult {
            replayCalls += 1
            val bytes = record.nativeState
            val isInitial = bytes.size == 130
            if (!isInitial && !bytes.contentEquals(allowedTerminal)) throw IOException("scripted replay rejection")
            val initial = nativeBytes().apply { if (wrongFreshInitial) this[14] = 20 }
            val canonical = bytes.copyOf().apply {
                if (wrongCanonical && !isInitial) this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte()
            }
            return LanzhouMahjongSettlementReplayResult(initial, canonical,
                if (isInitial) view(phase = LanzhouMahjongPhase.DISCARD, winner = null, scores = List(4) { 0 })
                else terminalView)
        }
    }

    private class InjectedNativeSession(
        record: LanzhouMahjongSessionRecord,
        wrongInitial: Boolean = false,
    ) : LanzhouMahjongRuleSession {
        override val viewerSeat = record.viewerSeat
        private var bytes = nativeBytes().apply {
            if (wrongInitial) { this[14] = 20; refreshChecksum(this) }
        }
        var closed = false
        override fun snapshot() = if (bytes.size == 130)
            view(phase = LanzhouMahjongPhase.DISCARD, winner = null, scores = List(4) { 0 }) else view()
        override fun legalActions(): List<LanzhouMahjongAction> = emptyList()
        override fun apply(action: LanzhouMahjongAction): ActionResult = ActionResult.Rejected(EngineError.UNSUPPORTED)
        override fun undo() = false
        override fun serialize() = bytes.copyOf()
        override fun restore(data: ByteArray): RestoreResult {
            if (!data.contentEquals(nativeBytes()) && !data.contentEquals(nativeBytes(actions = 1))) {
                return RestoreResult.Rejected(EngineError.ILLEGAL_ACTION)
            }
            bytes = data.copyOf()
            return RestoreResult.Restored
        }
        override fun close() { closed = true }
    }

    companion object {
        private fun record(
            difficulty: Difficulty = Difficulty.EASY,
            viewer: Int = 0,
            seed: Long = 7,
            base: Long = 1,
            bytes: ByteArray = nativeBytes(),
        ) = LanzhouMahjongSessionRecord(difficulty, viewer, seed, 0, base, seed, bytes)

        private fun nativeBytes(actions: Int = 0, version: Int = 2, base: Long = 1): ByteArray {
            val buffer = ByteBuffer.allocate(130 + actions * 3).order(ByteOrder.LITTLE_ENDIAN)
            buffer.put(byteArrayOf(76, 90, 77, 74, version.toByte(), 0))
            buffer.putLong(base)
            repeat(108) { buffer.put((it / 4).toByte()) }
            buffer.putInt(actions)
            repeat(actions) { buffer.put(byteArrayOf(1, 0, 0)) }
            return buffer.array().also(::refreshChecksum)
        }

        private fun refreshChecksum(bytes: ByteArray) {
            var checksum = 0x811c9dc5.toInt()
            for (index in 0 until bytes.size - 4) {
                checksum = (checksum xor (bytes[index].toInt() and 255)) * 16777619
            }
            ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(bytes.size - 4, checksum)
        }

        private fun view(
            phase: LanzhouMahjongPhase = LanzhouMahjongPhase.WON,
            winner: Int? = 0,
            payer: Int? = null,
            falseWin: Boolean = false,
            scores: List<Long> = listOf(6, -2, -2, -2),
        ) = LanzhouMahjongPublicView(
            phase, 0, 0, 55, null, null,
            LanzhouMahjongOutcomeView(winner, payer, 0, falseWin, false, false, false, false),
            emptyList(), scores,
        )
    }
}
