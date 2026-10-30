package com.masterofchessstrategy.games.lanzhou_fangqi

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.masterofchessstrategy.engine.Difficulty
import com.masterofchessstrategy.engine.GameResult
import com.masterofchessstrategy.engine.GameType
import com.masterofchessstrategy.engine.games.lanzhou_fangqi.FangqiAction
import com.masterofchessstrategy.engine.games.lanzhou_fangqi.FangqiPoint
import com.masterofchessstrategy.engine.games.lanzhou_fangqi.FangqiSide
import com.masterofchessstrategy.engine.games.lanzhou_fangqi.NativeLanzhouFangqiSession
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Actual JNI only. Compiling this source does not mean these methods have run on a device. */
@RunWith(AndroidJUnit4::class)
class NativeFangqiRankedReplayInstrumentedTest {
    @Test fun actualStandardOpeningReplaysAndPassesTheRankedCreationValidator() = runBlocking(Dispatchers.Default) {
        val history = NativeLanzhouFangqiSession().use { native ->
            assertEquals(FangqiSide.RED, native.snapshot().currentPlayer)
            assertEquals(14, native.snapshot().board.count { it == 1 })
            assertEquals(14, native.snapshot().board.count { it == 2 })
            FangqiRankedHistory(native.serialize())
        }
        assertEquals(FangqiRankedLedger.standardInitialHistory(), history)
        val replay = NativeFangqiRankedReplay()
        val actual = replay.replay(history)
        assertEquals(history, actual.canonicalHistory)
        assertEquals(GameResult.ONGOING, actual.result)
        assertEquals(0, actual.pendingCaptures)
        FangqiRankedLedger(replay).validateCreation(creation(history))
    }

    @Test fun actualStandardLegalMoveRemainsOngoingAndCannotIssueATerminalReceipt() = runBlocking(Dispatchers.Default) {
        val history = NativeLanzhouFangqiSession().use { native ->
            val move = FangqiAction.Move(FangqiPoint(0, 5), FangqiPoint(0, 4))
            assertTrue(move in native.legalActions())
            assertTrue(native.apply(move).accepted)
            FangqiRankedHistory(native.serialize())
        }
        val replay = NativeFangqiRankedReplay()
        assertEquals(GameResult.ONGOING, replay.replay(history).result)
        val creation = creation(FangqiRankedLedger.standardInitialHistory())
        rejects { FangqiRankedLedger(replay).firstTerminal(creation,
            creation.initial.copy(history = history, acceptedActionCount = 1, updatedAtEpochMillis = 20), 30, null) }
    }

    @Test fun actualMultiCaptureHistoryReturnsPendingThenRealWinButPracticeRootNeverRanks() = runBlocking(Dispatchers.Default) {
        // Existing authored/Native-verified fixture: one move plus TWO selected captures, no invented win.
        val practice = LanzhouFangqiLearningContent.practices.single { it.id == "lfq-practice-double-01" }
        val replay = NativeFangqiRankedReplay()
        val history = NativeLanzhouFangqiSession.fromPosition(practice.board, FangqiSide.RED).use { native ->
            assertTrue(native.apply(practice.exampleLine.first()).accepted)
            val pending = replay.replay(FangqiRankedHistory(native.serialize()))
            assertEquals(2, pending.pendingCaptures)
            assertEquals(GameResult.ONGOING, pending.result)
            practice.exampleLine.drop(1).forEach { assertTrue(native.apply(it).accepted) }
            assertEquals(GameResult.FIRST_PLAYER_WIN, native.snapshot().result)
            assertEquals(0, native.snapshot().pendingCaptures)
            assertTrue(native.legalActions().isEmpty())
            FangqiRankedHistory(native.serialize())
        }
        val actual = replay.replay(history)
        assertEquals(history, actual.canonicalHistory)
        assertEquals(GameResult.FIRST_PLAYER_WIN, actual.result)
        assertEquals(0, actual.pendingCaptures)
        assertEquals("3", history.bytes.toString(Charsets.US_ASCII).split('|')[4])
        val creation = creation(FangqiRankedLedger.standardInitialHistory())
        rejects { FangqiRankedLedger(replay).firstTerminal(creation,
            creation.initial.copy(history = history, acceptedActionCount = 3, updatedAtEpochMillis = 20), 30, null) }
        val afterTerminal = history.bytes.toString(Charsets.US_ASCII).replace("|3|", "|4|") + ";m,1,0,7"
        rejects { replay.replay(FangqiRankedHistory(afterTerminal.toByteArray(Charsets.US_ASCII))) }
    }

    @Test fun actualNativeRejectsMalformedIllegalAndOtherRuleHistories() = runBlocking(Dispatchers.Default) {
        val replay = NativeFangqiRankedReplay()
        val initial = FangqiRankedLedger.standardInitialHistory().bytes.toString(Charsets.US_ASCII)
        val root = initial.removeSuffix("|0|")
        for (bad in listOf("not-a-history", initial.replace("MOCS-LFQ|1|", "MOCS-LFQ|2|"),
            initial.replace("MOCS-LFQ", "MOCS-GO"), "$root|1|m,1,35,0", "$root|01|m,1,35,28")) {
            rejects { replay.replay(FangqiRankedHistory(bad.toByteArray(Charsets.US_ASCII))) }
        }
    }

    @Test fun productionAdapterRefusesToCreateNativeWorkOnMainThread() = runBlocking {
        withContext(Dispatchers.Main) {
            try { NativeFangqiRankedReplay().replay(FangqiRankedLedger.standardInitialHistory()); fail("main accepted") }
            catch (_: IllegalStateException) { }
        }
    }

    private fun creation(history: FangqiRankedHistory) = FangqiRankedCreation(1,
        FangqiRankedStartOrigin.NEW_STANDARD_HUMAN_GAME, FangqiRankedPosition(FangqiRankedIdentity(
            "native-ranked-replay-test", GameType.LANZHOU_SQUARE_CHESS, FangqiStoredMode.HUMAN_VS_AI,
            Difficulty.EASY, 0, FangqiRankedLedger.RULES_VERSION, 1), history, 10, 0, 0, 0))

    private fun rejects(action: () -> Unit) {
        try { action(); fail("invalid native source accepted") } catch (_: IOException) { }
    }
}
