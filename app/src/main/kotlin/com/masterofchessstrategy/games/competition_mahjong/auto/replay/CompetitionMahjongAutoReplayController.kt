package com.masterofchessstrategy.games.competition_mahjong.auto.replay

import com.masterofchessstrategy.engine.CompetitionMahjongAutoConfig
import com.masterofchessstrategy.engine.CompetitionMahjongAutoSession
import com.masterofchessstrategy.engine.CompetitionMahjongPublicView
import com.masterofchessstrategy.engine.NativeCompetitionMahjongAutoSession
import com.masterofchessstrategy.games.competition_mahjong.auto.CompetitionMahjongAutoAdmission
import com.masterofchessstrategy.games.competition_mahjong.auto.CompetitionMahjongAutoArchive
import com.masterofchessstrategy.games.competition_mahjong.auto.CompetitionMahjongAutoRecord
import com.masterofchessstrategy.games.competition_mahjong.auto.isAutoTerminal
import com.masterofchessstrategy.games.competition_mahjong.match.CompetitionMahjongMatchLedger
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class CompetitionMahjongAutoReplayState(
    val archiveKey: String? = null,
    val handIndex: Int = 0,
    val eventIndex: Int = 0,
    val eventCount: Int = 0,
    val frame: CompetitionMahjongPublicView? = null,
    val closed: Boolean = false,
    val error: String? = null,
) {
    val ready: Boolean get() = archiveKey != null && frame != null && !closed
    val atBeginning: Boolean get() = handIndex == 0 && eventIndex == 0
    val atEnd: Boolean get() = handIndex == 15 && eventIndex == eventCount
    val canBackward: Boolean get() = ready && !atBeginning
    val canForward: Boolean get() = ready && !atEnd
}

/** Exact recorded-event replay. Prefix bytes stay private; only Native public v2 frames are published. */
class CompetitionMahjongAutoReplayController(
    private val archive: CompetitionMahjongAutoArchive,
    sessionFactory: (CompetitionMahjongAutoConfig) -> CompetitionMahjongAutoSession =
        ::NativeCompetitionMahjongAutoSession,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private val mutex = Mutex()
    private val admission = CompetitionMahjongAutoAdmission(sessionFactory)
    private val mutableState = MutableStateFlow(CompetitionMahjongAutoReplayState())
    val state = mutableState.asStateFlow()
    private var session: CompetitionMahjongAutoSession? = null
    private var hand = 0
    private var event = 0
    private var total = 0
    private var frame: CompetitionMahjongPublicView? = null
    private var closed = false

    suspend fun open(): Boolean = serial {
        check(!closed)
        if (session != null) return@serial true
        var candidate: CompetitionMahjongAutoSession? = null
        try {
            admission.validateHistory(archive.checkpoint)
            val target = target(0)
            candidate = admission.create(target.config)
            admission.restore(candidate, target.record)
            val view = candidate.snapshot()
            validateFrame(0, 0, target.total, view)
            session = candidate
            candidate = null
            hand = 0
            event = 0
            total = target.total
            frame = view
            publish()
            true
        } catch (failure: Exception) {
            candidate?.close()
            publish(failure.message ?: "自动赛事棋谱无法恢复，原归档已保留")
            false
        }
    }

    suspend fun forward(expectedHand: Int, expectedEvent: Int): Boolean = serial {
        if (!canChange(expectedHand, expectedEvent) || state.value.atEnd) return@serial false
        if (event < total) seekWithin(event + 1) else seekHand(hand + 1, 0)
    }

    suspend fun backward(expectedHand: Int, expectedEvent: Int): Boolean = serial {
        if (!canChange(expectedHand, expectedEvent) || state.value.atBeginning) return@serial false
        if (event > 0) seekWithin(event - 1) else {
            val previous = target(hand - 1)
            seekHand(hand - 1, previous.total)
        }
    }

    suspend fun selectHand(index: Int): Boolean = serial {
        if (closed || session == null || index !in 0..15) return@serial false
        seekHand(index, 0)
    }

    suspend fun seek(index: Int, eventIndex: Int): Boolean = serial {
        if (closed || session == null || index !in 0..15) return@serial false
        val selected = target(index)
        if (eventIndex !in 0..selected.total) return@serial false
        if (index == hand) seekWithin(eventIndex) else seekHand(index, eventIndex)
    }

    suspend fun close() = serial {
        if (!closed) {
            closed = true
            session?.close()
            session = null
            frame = null
            publish()
        }
    }

    private fun seekWithin(next: Int): Boolean {
        return seekHand(hand, next)
    }

    private fun seekHand(nextHand: Int, nextEvent: Int): Boolean {
        val selected = target(nextHand, nextEvent)
        var candidate: CompetitionMahjongAutoSession? = null
        return try {
            candidate = admission.create(selected.config)
            admission.restore(candidate, selected.record)
            val view = candidate.snapshot()
            validateFrame(nextHand, nextEvent, selected.total, view)
            val old = checkNotNull(session)
            session = candidate
            candidate = null
            hand = nextHand
            event = nextEvent
            total = selected.total
            frame = view
            old.close()
            publish()
            true
        } catch (failure: Exception) {
            candidate?.close()
            publish(failure.message ?: "所选盘无法恢复，仍停留在原位置")
            false
        }
    }

    private data class Target(val config: CompetitionMahjongAutoConfig, val record: ByteArray, val total: Int)

    private fun target(index: Int, position: Int = 0): Target {
        require(index in 0..15)
        val checkpoint = archive.checkpoint
        val history = checkpoint.completedHands[index]
        val config = checkpoint.config.handConfig(checkpoint.seriesIndex, index, checkpoint.openingRecord != null)
        val count = CompetitionMahjongAutoRecord.eventsAfter(history.baseline, history.terminal, config)
        return Target(config,
            CompetitionMahjongAutoReplayRecord.prefix(history.baseline, history.terminal, position, config), count)
    }

    private fun validateFrame(index: Int, position: Int, last: Int, view: CompetitionMahjongPublicView) {
        require(view.publicFrameVersion == 2)
        val checkpoint = archive.checkpoint
        val series = checkpoint.config.seriesConfig(checkpoint.seriesIndex, checkpoint.openingRecord != null)
        CompetitionMahjongMatchLedger.validateFrame(series, index, view)
        require(view.isAutoTerminal() == (position == last))
        if (position == last) require(checkpoint.completedHands[index].result ==
            CompetitionMahjongMatchLedger.captureTerminal(series, index, view))
    }

    private fun canChange(expectedHand: Int, expectedEvent: Int): Boolean =
        !closed && session != null && hand == expectedHand && event == expectedEvent

    private fun publish(error: String? = null) {
        mutableState.value = CompetitionMahjongAutoReplayState(
            archive.key.takeIf { session != null }, hand, event, total, frame, closed, error)
    }

    private suspend fun <T> serial(block: suspend () -> T): T =
        withContext(NonCancellable + dispatcher) { mutex.withLock { block() } }
}
