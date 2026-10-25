package com.masterofchessstrategy.games.lanzhou_fangqi.data

import com.masterofchessstrategy.engine.Difficulty
import com.masterofchessstrategy.engine.GameResult
import com.masterofchessstrategy.engine.games.lanzhou_fangqi.*
import com.masterofchessstrategy.games.lanzhou_fangqi.LanzhouFangqiLearningContent
import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

/** Fake Native transport only. Real canonical replay/terminal legality still needs Root's JNI tests. */
class FangqiLearningPracticeAdmissionTest {
    @Test fun eachAuthoredSeedAndRedTurnIsPassedToNativeAndWinningHistoryIsRequired() {
        LanzhouFangqiLearningContent.practices.forEach { practice ->
            val history = history(practice.id)
            val session = FakeSession(practice.board, history)
            val admission = FangqiLearningPracticeAdmission { board, side ->
                assertEquals(practice.board, board)
                assertEquals(FangqiSide.RED, side)
                session
            }
            admission.verify(practice.id, history)
            assertTrue(session.closed)
            assertEquals(1, session.restores)
            assertArrayEquals(history, session.received)
        }
    }

    @Test fun otherPracticeSeedOrBlackStartIsRejectedBeforeNativeCreation() {
        val practices = LanzhouFangqiLearningContent.practices
        var creates = 0
        val admission = FangqiLearningPracticeAdmission { _, _ -> creates++; error("must not create") }
        rejected { admission.verify(practices[0].id, history(practices[1].id)) }
        val black = history(practices[0].id).toString(Charsets.US_ASCII).replace("MOCS-LFQ|1|1|", "MOCS-LFQ|1|2|")
        rejected { admission.verify(practices[0].id, black.toByteArray()) }
        assertEquals(0, creates)
    }

    @Test fun wrongFormatBadCountsEmptyAndOversizedDataFailBeforeNative() {
        val id = LanzhouFangqiLearningContent.practices.first().id
        val good = history(id).toString(Charsets.US_ASCII)
        val samples = listOf(byteArrayOf(), byteArrayOf(-1), ByteArray(1_048_577),
            good.replace("MOCS-LFQ|1|", "MOCS-LFQ|2|").toByteArray(),
            good.replace("|2|m,", "|0|m,").toByteArray(),
            good.replace("|2|m,", "|02|m,").toByteArray(),
            good.replace("|2|m,", "|16385|m,").toByteArray())
        val admission = FangqiLearningPracticeAdmission { _, _ -> error("must not create") }
        samples.forEach { rejected { admission.verify(id, it) } }
    }

    @Test fun nativeRestoreOrCanonicalMismatchRejectsAndAlwaysClosesHandle() {
        val practice = LanzhouFangqiLearningContent.practices.first()
        val history = history(practice.id)
        for (fault in listOf("restore", "canonical", "seed")) {
            val session = FakeSession(practice.board, history, fault = fault)
            rejected { FangqiLearningPracticeAdmission { _, _ -> session }.verify(practice.id, history) }
            assertTrue(fault, session.closed)
        }
    }

    @Test fun ongoingBlackWinAndPendingCapturesCannotCompletePractice() {
        val practice = LanzhouFangqiLearningContent.practices.first()
        val history = history(practice.id)
        for ((result, pending) in listOf(GameResult.ONGOING to 0, GameResult.SECOND_PLAYER_WIN to 0,
            GameResult.FIRST_PLAYER_WIN to 1)) {
            val session = FakeSession(practice.board, history, result = result, pending = pending)
            rejected { FangqiLearningPracticeAdmission { _, _ -> session }.verify(practice.id, history) }
            assertTrue(session.closed)
        }
    }

    @Test fun callerBytesRemainOwnedEvenWhenTransportMutatesItsRestoreArgument() {
        val practice = LanzhouFangqiLearningContent.practices.first()
        val history = history(practice.id)
        val before = history.copyOf()
        val session = FakeSession(practice.board, history, fault = "mutate-input")
        FangqiLearningPracticeAdmission { _, _ -> session }.verify(practice.id, history)
        assertArrayEquals(before, history)
        assertTrue(session.closed)
    }

    private fun rejected(block: () -> Unit) {
        try { block(); fail("expected admission rejection") } catch (_: IOException) {}
    }

    private class FakeSession(
        private val board: List<Int>,
        private val finalHistory: ByteArray,
        private val fault: String = "",
        private val result: GameResult = GameResult.FIRST_PLAYER_WIN,
        private val pending: Int = 0,
    ) : FangqiSession {
        var closed = false
        var restores = 0
        var received: ByteArray? = null
        override fun serialize(): ByteArray = if (restores == 0) {
            if (fault == "seed") byteArrayOf(1) else seed(board)
        } else if (fault == "canonical") byteArrayOf(2) else finalHistory.copyOf()
        override fun restore(data: ByteArray): Boolean {
            restores++; received = data.copyOf()
            if (fault == "mutate-input") data.fill(0)
            return fault != "restore"
        }
        override fun snapshot() = FangqiSnapshot(board, FangqiSide.RED, pending, result, true)
        override fun close() { closed = true }
        override fun apply(action: FangqiAction): FangqiMoveResult = error("no fake play")
        override fun legalActions(): List<FangqiAction> = error("no fake root search")
        override fun undo(): Boolean = error("no fake undo")
        override fun reset() = error("no fake reset")
        override fun chooseAction(difficulty: Difficulty): FangqiAction? = error("no AI allowed")
    }

    companion object {
        private fun seed(board: List<Int>) = "MOCS-LFQ|1|1|${board.joinToString("")}|0|".toByteArray()
        private fun history(id: String): ByteArray {
            val practice = LanzhouFangqiLearningContent.practices.single { it.id == id }
            val actions = practice.exampleLine.map { action -> when (action) {
                is FangqiAction.Move -> "m,1,${action.from.index},${action.to.index}"
                is FangqiAction.Capture -> "c,1,${action.point.index},${action.point.index}"
            } }
            return "MOCS-LFQ|1|1|${practice.board.joinToString("")}|${actions.size}|${actions.joinToString(";")}".toByteArray()
        }
    }
}
