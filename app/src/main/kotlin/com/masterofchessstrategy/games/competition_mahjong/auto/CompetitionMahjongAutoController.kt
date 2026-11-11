package com.masterofchessstrategy.games.competition_mahjong.auto

import com.masterofchessstrategy.engine.CompetitionMahjongAutoConfig
import com.masterofchessstrategy.engine.CompetitionMahjongAutoSession
import com.masterofchessstrategy.engine.CompetitionMahjongPublicView
import com.masterofchessstrategy.engine.NativeCompetitionMahjongAutoSession
import com.masterofchessstrategy.games.competition_mahjong.match.CompetitionMahjongMatchLedger
import com.masterofchessstrategy.games.competition_mahjong.match.CompetitionMahjongPlayerScores
import com.masterofchessstrategy.games.competition_mahjong.match.policy.CompetitionMahjongPlayPolicy
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class CompetitionMahjongAutoState(
    val runId: String? = null,
    val seriesIndex: Int = 0,
    val handIndex: Int = 0,
    val completedHands: Int = 0,
    val scores: CompetitionMahjongPlayerScores = CompetitionMahjongPlayerScores.ZERO,
    val settings: CompetitionMahjongAutoSettings = CompetitionMahjongAutoSettings(),
    val frame: CompetitionMahjongPublicView? = null,
    val eventsAfterOpening: Int = 0,
    val elapsedMillis: Long = 0,
    val needsSave: Boolean = false,
    val executionActive: Boolean = false,
    val closed: Boolean = false,
    val error: String? = null,
) {
    val seriesComplete: Boolean get() = completedHands == 16 && !needsSave
    val finished: Boolean get() = seriesComplete && (!settings.autoContinue || seriesIndex + 1 >= settings.maxSeries)
    val canUndo: Boolean get() = settings.paused && !needsSave && !seriesComplete && eventsAfterOpening > 0 && !closed
}

/** Serial Native + record + settings transactions. Playback scheduling belongs to the host. */
class CompetitionMahjongAutoController(
    private val initialConfig: CompetitionMahjongAutoRunConfig,
    private val store: CompetitionMahjongAutoStore,
    private val playPolicy: CompetitionMahjongPlayPolicy?,
    private val initialSettings: CompetitionMahjongAutoSettings = CompetitionMahjongAutoSettings(),
    openingRecord: ByteArray? = null,
    sessionFactory: (CompetitionMahjongAutoConfig) -> CompetitionMahjongAutoSession = ::NativeCompetitionMahjongAutoSession,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val clockMillis: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private val transaction = Mutex()
    private val admission = CompetitionMahjongAutoAdmission(sessionFactory)
    private val initialOpening = openingRecord?.copyOf()
    private val mutableState = MutableStateFlow(CompetitionMahjongAutoState(settings = initialSettings))
    val state = mutableState.asStateFlow()
    private var config = initialConfig
    private var settings = initialSettings
    private var opening = initialOpening
    private var series = 0
    private var hand = 0
    private var history = emptyList<CompetitionMahjongAutoHandRecord>()
    private var baseline = byteArrayOf()
    private var lastRecord = byteArrayOf()
    private var frame: CompetitionMahjongPublicView? = null
    private var session: CompetitionMahjongAutoSession? = null
    private var elapsed = 0L
    private var needsSave = false
    private var closed = false
    private var executionActive = false
    private var clockMark: Long? = null

    suspend fun open(): Boolean = serial {
        check(!closed)
        if (session != null) return@serial true
        var candidate: CompetitionMahjongAutoSession? = null
        try {
            requirePolicy()
            val saved = store.load(initialConfig.viewerPlayerId)
            val target = saved?.config ?: initialConfig
            require(target.viewerPlayerId == initialConfig.viewerPlayerId)
            authorize(target)
            if (saved != null) admission.validateHistory(saved)
            val index = saved?.seriesIndex ?: 0
            val handIndex = saved?.activeHandIndex ?: 0
            val start = if (saved != null) saved.openingRecord else initialOpening
            candidate = admission.create(target.handConfig(index, handIndex, start != null))
            var candidateFrame: CompetitionMahjongPublicView
            val checkpoint = if (saved != null) {
                candidateFrame = admission.restoreCurrent(candidate, saved)
                saved
            } else {
                if (start != null) admission.restore(candidate, start)
                candidateFrame = admission.requireBaseline(candidate)
                val record = candidate.serialize()
                val fresh = CompetitionMahjongAutoCheckpoint(target, initialSettings, 0, 0, emptyList(), record, record, start)
                fresh.validateFrame(candidateFrame)
                store.save(fresh)
                fresh
            }
            install(candidate, checkpoint, candidateFrame)
            candidate = null
            publish()
            true
        } catch (failure: Exception) {
            candidate?.close()
            publish(failure.message ?: "自动演局读取失败，原记录已保留")
            false
        }
    }

    /** One accepted AI event, or one saved hand/series transition. No manual-seat operation. */
    suspend fun step(expectedSeries: Int, expectedHand: Int): Boolean = serial { advanceOrNext(expectedSeries, expectedHand) }

    /** A delayed host tick cannot advance after any intervening pause, save, or activity change. */
    suspend fun scheduledStep(expected: CompetitionMahjongAutoState): Boolean = serial {
        if (state.value !== expected || !executionActive || settings.paused) return@serial false
        advanceOrNext(expected.seriesIndex, expected.handIndex)
    }

    private suspend fun advanceOrNext(expectedSeries: Int, expectedHand: Int): Boolean {
        if (!canChange(expectedSeries, expectedHand)) return false
        if (checkNotNull(frame).isAutoTerminal()) {
            if (hand < 15) return replaceHand(series, hand + 1)
            if (settings.autoContinue && series + 1 < settings.maxSeries) return replaceHand(series + 1, 0)
            return false
        }
        return try {
            accrueTime()
            val result = checkNotNull(session).advance(1)
            frame = checkNotNull(session).snapshot()
            check(result.acceptedActions == 1 && result.terminal == checkNotNull(frame).isAutoTerminal())
            accrueTime()
            persist()
        } catch (failure: Exception) {
            failUnsaved(failure)
            false
        }
    }

    /** Playback back-step remains within the current hand's immutable starting record. */
    suspend fun undo(expectedSeries: Int, expectedHand: Int): Boolean = serial {
        if (!canChange(expectedSeries, expectedHand) || !state.value.canUndo) return@serial false
        try {
            check(checkNotNull(session).undo())
            frame = checkNotNull(session).snapshot()
            persist()
        } catch (failure: Exception) { failUnsaved(failure); false }
    }

    suspend fun updateSettings(value: CompetitionMahjongAutoSettings): Boolean = serial {
        if (closed || session == null || needsSave) return@serial false
        require(value.maxSeries > series) { "续局上限不能小于当前赛事序号" }
        accrueTime()
        settings = value
        updateClock()
        persist()
    }

    /** Android host separately grants background execution; stored preference alone is insufficient. */
    suspend fun setExecutionActive(active: Boolean): Boolean = serial {
        if (closed) return@serial false
        accrueTime()
        executionActive = active
        updateClock()
        if (session != null && !needsSave) persist() else { publish(); true }
    }

    suspend fun retrySave(): Boolean = serial {
        if (closed || session == null || !needsSave) return@serial false
        persist()
    }

    /** Only a confirmed replacement may call this; the old activity survives candidate-save failure. */
    suspend fun newRun(target: CompetitionMahjongAutoRunConfig, preferences: CompetitionMahjongAutoSettings,
                       specifiedOpening: ByteArray? = null): Boolean = serial {
        if (closed || needsSave) return@serial false
        require(target.viewerPlayerId == initialConfig.viewerPlayerId && target.runId != config.runId)
        var candidate: CompetitionMahjongAutoSession? = null
        try {
            authorize(target)
            val start = specifiedOpening?.copyOf()
            candidate = admission.create(target.handConfig(0, 0, start != null))
            if (start != null) admission.restore(candidate, start)
            val candidateFrame = admission.requireBaseline(candidate)
            val record = candidate.serialize()
            val checkpoint = CompetitionMahjongAutoCheckpoint(target, preferences, 0, 0, emptyList(), record, record, start)
            checkpoint.validateFrame(candidateFrame)
            store.save(checkpoint)
            val old = session
            install(candidate, checkpoint, candidateFrame)
            candidate = null
            old?.close()
            publish()
            true
        } catch (failure: Exception) {
            candidate?.close()
            publish(failure.message ?: "新自动演局未保存，原活动已保留")
            false
        }
    }

    suspend fun close() = serial {
        if (!closed) {
            clockMark = null
            closed = true
            session?.close()
            session = null
            frame = null
            publish()
        }
    }

    private suspend fun replaceHand(nextSeries: Int, nextHand: Int): Boolean {
        var candidate: CompetitionMahjongAutoSession? = null
        return try {
            authorize(config)
            accrueTime()
            candidate = admission.create(config.handConfig(nextSeries, nextHand, opening != null))
            if (nextHand == 0 && opening != null) admission.restore(candidate, checkNotNull(opening))
            val candidateFrame = admission.requireBaseline(candidate)
            val record = candidate.serialize()
            val checkpoint = CompetitionMahjongAutoCheckpoint(config, settings, nextSeries, nextHand,
                if (nextSeries == series) history else emptyList(), record, record, opening,
                if (nextSeries == series) elapsed else 0)
            checkpoint.validateFrame(candidateFrame)
            store.save(checkpoint)
            val old = checkNotNull(session)
            install(candidate, checkpoint, candidateFrame)
            candidate = null
            old.close()
            publish()
            true
        } catch (failure: Exception) {
            candidate?.close()
            publish(failure.message ?: "下一盘未保存，原牌局已保留")
            false
        }
    }

    private suspend fun persist(): Boolean {
        needsSave = true
        clockMark = null
        return try {
            val active = checkNotNull(session)
            val currentFrame = active.snapshot()
            val record = active.serialize()
            val updated = history.filter { it.result.handIndex < hand }.toMutableList()
            if (currentFrame.isAutoTerminal()) updated += CompetitionMahjongAutoHandRecord(
                CompetitionMahjongMatchLedger.captureTerminal(config.seriesConfig(series, opening != null), hand, currentFrame), baseline, record)
            val checkpoint = CompetitionMahjongAutoCheckpoint(config, settings, series, hand, updated, baseline, record, opening, elapsed)
            checkpoint.validateFrame(currentFrame)
            // A setting change on a completed series must not recreate a deleted archive.
            val archive = if (checkpoint.ledger.isComplete && history.size < updated.size) {
                CompetitionMahjongAutoArchive(checkpoint).takeIf { it.selected(requirePolicy()) }
            } else null
            store.save(checkpoint, archive)
            history = updated
            lastRecord = record
            frame = currentFrame
            needsSave = false
            updateClock()
            publish()
            true
        } catch (failure: Exception) { failUnsaved(failure); false }
    }

    private fun install(active: CompetitionMahjongAutoSession, checkpoint: CompetitionMahjongAutoCheckpoint,
                        candidateFrame: CompetitionMahjongPublicView) {
        config = checkpoint.config
        settings = checkpoint.settings
        opening = checkpoint.openingRecord
        series = checkpoint.seriesIndex
        hand = checkpoint.activeHandIndex
        history = checkpoint.completedHands
        baseline = checkpoint.baseline
        lastRecord = checkpoint.engineRecord
        elapsed = checkpoint.elapsedMillis
        session = active
        frame = candidateFrame
        needsSave = false
        updateClock()
    }

    private fun requirePolicy(): CompetitionMahjongPlayPolicy = checkNotNull(playPolicy) { "自动演局难度权限尚未接入，未创建或替换活动" }
    private fun authorize(target: CompetitionMahjongAutoRunConfig) { target.playerBotLevels.forEach { requirePolicy().freshUsage(it) } }
    private fun canChange(expectedSeries: Int, expectedHand: Int): Boolean =
        !closed && session != null && !needsSave && series == expectedSeries && hand == expectedHand

    private fun accrueTime() {
        val mark = clockMark ?: return
        val now = clockMillis()
        elapsed = Math.addExact(elapsed, (now - mark).coerceAtLeast(0))
        clockMark = now
    }
    private fun updateClock() {
        clockMark = if (executionActive && !settings.paused && !needsSave && history.size < 16) clockMillis() else null
    }
    private fun failUnsaved(failure: Exception) {
        needsSave = true
        clockMark = null
        publish(failure.message ?: "自动演局尚未保存，请重试")
    }
    private fun publish(error: String? = null) {
        val ledger = CompetitionMahjongMatchLedger.restored(history.map { it.result })
        mutableState.value = CompetitionMahjongAutoState(if (session != null) config.runId else null, series, hand,
            ledger.completedHandCount, ledger.cumulativeScores, settings, frame,
            if (lastRecord.isEmpty()) 0 else CompetitionMahjongAutoRecord.eventsAfter(baseline, lastRecord,
                config.handConfig(series, hand, opening != null)), elapsed, needsSave, executionActive, closed, error)
    }
    private suspend fun <T> serial(block: suspend () -> T): T =
        withContext(NonCancellable + dispatcher) { transaction.withLock { block() } }
}
