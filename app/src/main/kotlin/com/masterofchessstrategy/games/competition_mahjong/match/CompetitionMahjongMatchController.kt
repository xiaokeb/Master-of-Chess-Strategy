package com.masterofchessstrategy.games.competition_mahjong.match

import com.masterofchessstrategy.engine.CompetitionMahjongAction
import com.masterofchessstrategy.engine.CompetitionMahjongApplyResult
import com.masterofchessstrategy.engine.CompetitionMahjongPublicView
import com.masterofchessstrategy.engine.CompetitionMahjongRoundPhase
import com.masterofchessstrategy.engine.CompetitionMahjongRuleSession
import com.masterofchessstrategy.engine.NativeCompetitionMahjongSession
import com.masterofchessstrategy.engine.RestoreResult
import com.masterofchessstrategy.games.competition_mahjong.CompetitionMahjongMatchConfig
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException

data class CompetitionMahjongCompetitionState(
    val matchId: String? = null,
    val viewerPlayerId: Int = 0,
    val activeHandIndex: Int = 0,
    val completedHandCount: Int = 0,
    val cumulativeScores: CompetitionMahjongPlayerScores = CompetitionMahjongPlayerScores.ZERO,
    val table: CompetitionMahjongPublicView? = null,
    val legalActions: List<CompetitionMahjongAction> = emptyList(),
    val standings: List<CompetitionMahjongMatchStanding> = emptyList(),
    val needsSave: Boolean = false,
    val error: String? = null,
) {
    val canStartNextHand: Boolean get() = table != null && !needsSave &&
        completedHandCount == activeHandIndex + 1 && completedHandCount < 16
}

/** Owns Native sessions; every accepted change and series advance is saved under the same lock. */
class CompetitionMahjongMatchController(
    private val initialConfig: CompetitionMahjongCompetitionConfig,
    private val store: CompetitionMahjongMatchStore,
    private val sessionFactory: (CompetitionMahjongMatchConfig) -> CompetitionMahjongRuleSession = {
        NativeCompetitionMahjongSession(it.seed, it.dealerSeat, it.roundWind, it.viewerSeat)
    },
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private val transaction = Mutex()
    private val mutableState = MutableStateFlow(CompetitionMahjongCompetitionState(viewerPlayerId = initialConfig.viewerPlayerId))
    val state: StateFlow<CompetitionMahjongCompetitionState> = mutableState.asStateFlow()
    private var config = initialConfig
    private var ledger = CompetitionMahjongMatchLedger.empty()
    private var activeHand = 0
    private var session: CompetitionMahjongRuleSession? = null
    private var unsavedBase: CompetitionMahjongMatchLedger? = null
    private var closed = false

    suspend fun open(): Boolean = transaction {
        check(!closed)
        if (session != null) return@transaction true
        var candidate: CompetitionMahjongRuleSession? = null
        try {
            val saved = store.load(initialConfig.viewerPlayerId)
            val candidateConfig = saved?.config ?: initialConfig
            require(candidateConfig.viewerPlayerId == initialConfig.viewerPlayerId)
            val index = saved?.activeHandIndex ?: 0
            candidate = sessionFactory(candidateConfig.handConfig(index))
            check(candidate.viewerSeat == candidateConfig.handConfig(index).viewerSeat)
            val checkpoint = if (saved == null) {
                requireFresh(candidateConfig, index, candidate.snapshot())
                CompetitionMahjongMatchCheckpoint(candidateConfig, CompetitionMahjongMatchLedger.empty(), index, candidate.serialize())
            } else {
                if (candidate.restore(saved.engineRecord) != RestoreResult.Restored) throw IOException("赛事存档无法恢复，原记录已保留")
                saved.validateRestoredFrame(candidate.snapshot(), candidate.legalActions())
                saved
            }
            if (saved == null) store.save(checkpoint)
            config = candidateConfig
            activeHand = index
            ledger = checkpoint.ledger
            session = candidate
            candidate = null
            publish()
            true
        } catch (failure: Exception) {
            candidate?.close()
            mutableState.value = mutableState.value.copy(error = failure.message ?: "读取赛事失败")
            false
        }
    }

    /** expectedHandIndex prevents an old table event from changing a newly created hand. */
    suspend fun apply(action: CompetitionMahjongAction, expectedHandIndex: Int): CompetitionMahjongApplyResult? = transaction {
        if (!canChange(expectedHandIndex)) return@transaction null
        val active = checkNotNull(session)
        try {
            val result = active.apply(action)
            if (result.accepted) persist(ledger) else publish()
            result
        } catch (failure: Exception) {
            blockUnsaved(ledger, failure)
            null
        }
    }

    suspend fun advanceOtherSeats(expectedHandIndex: Int, randomSeed: Long, maxActions: Int = 32): Int = transaction {
        require(randomSeed >= 0 && maxActions in 1..32)
        if (!canChange(expectedHandIndex)) return@transaction 0
        val active = checkNotNull(session)
        try {
            val count = when (config.botStrategy) {
                CompetitionMahjongBotStrategy.LEGACY_BATCH -> active.advanceBots(config.botLevel, randomSeed, maxActions)
                CompetitionMahjongBotStrategy.POSITION_SEEDED_V1 -> {
                    var moved = 0
                    while (moved < maxActions && active.advancePositionSeededBot(config.botLevel) == 1) moved++
                    moved
                }
            }
            if (count > 0) persist(ledger) else publish()
            count
        } catch (failure: Exception) {
            blockUnsaved(ledger, failure)
            0
        }
    }

    /** Undo stays in the current hand. Its provisional series result is replaced atomically. */
    suspend fun undo(expectedHandIndex: Int): Boolean = transaction {
        if (closed || session == null || unsavedBase != null || expectedHandIndex != activeHand) return@transaction false
        val base = ledger.beforeUndoCurrent(activeHand)
        try {
            if (!checkNotNull(session).undo()) return@transaction false
            persist(base)
        } catch (failure: Exception) {
            blockUnsaved(base, failure)
            false
        }
    }

    suspend fun retrySave(): Boolean = transaction {
        val base = unsavedBase ?: return@transaction false
        check(!closed && session != null)
        persist(base)
    }

    /** Saves metadata changes through the same Native/ledger transaction, without an event. */
    internal suspend fun saveCurrent(): Boolean = transaction {
        if (closed || session == null || unsavedBase != null) return@transaction false
        persist(ledger)
    }

    suspend fun nextHand(expectedHandIndex: Int): Boolean = transaction {
        if (closed || session == null || unsavedBase != null || expectedHandIndex != activeHand ||
            ledger.completedHandCount != activeHand + 1 || ledger.isComplete) return@transaction false
        val index = activeHand + 1
        var candidate: CompetitionMahjongRuleSession? = null
        try {
            candidate = sessionFactory(config.handConfig(index))
            check(candidate.viewerSeat == config.handConfig(index).viewerSeat)
            requireFresh(config, index, candidate.snapshot())
            val checkpoint = CompetitionMahjongMatchCheckpoint(config, ledger, index, candidate.serialize())
            // Old session and ledger remain active until the entire candidate checkpoint commits.
            store.save(checkpoint)
            val old = checkNotNull(session)
            session = candidate
            candidate = null
            activeHand = index
            publish()
            old.close()
            true
        } catch (failure: Exception) {
            candidate?.close()
            publish(error = failure.message ?: "下一盘保存失败，请重试")
            false
        }
    }

    suspend fun close() = transaction {
        if (!closed) {
            closed = true
            session?.close()
            session = null
            mutableState.value = mutableState.value.copy(table = null, legalActions = emptyList())
        }
    }

    private fun canChange(index: Int): Boolean = !closed && session != null && unsavedBase == null &&
        index == activeHand && ledger.completedHandCount == activeHand

    private suspend fun persist(base: CompetitionMahjongMatchLedger): Boolean {
        unsavedBase = base
        return try {
            val active = checkNotNull(session)
            val view = active.snapshot()
            CompetitionMahjongMatchLedger.validateFrame(config, activeHand, view)
            val updated = if (isTerminal(view)) base.recordTerminal(config, activeHand, view) else base
            val checkpoint = CompetitionMahjongMatchCheckpoint(config, updated, activeHand, active.serialize())
            checkpoint.validateRestoredFrame(view)
            store.save(checkpoint)
            ledger = updated
            unsavedBase = null
            publish()
            true
        } catch (failure: Exception) {
            blockUnsaved(base, failure)
            false
        }
    }

    private fun blockUnsaved(base: CompetitionMahjongMatchLedger, failure: Exception) {
        unsavedBase = base
        publish(error = failure.message ?: "赛事保存失败，请重试")
    }

    private fun publish(error: String? = null) {
        val active = session
        mutableState.value = CompetitionMahjongCompetitionState(
            config.matchId, config.viewerPlayerId, activeHand, ledger.completedHandCount, ledger.cumulativeScores,
            active?.snapshot(), if (active != null && canChange(activeHand)) active.legalActions().toList() else emptyList(),
            if (ledger.isComplete && unsavedBase == null) ledger.finalStandings() else emptyList(),
            unsavedBase != null, error,
        )
    }

    private fun requireFresh(config: CompetitionMahjongCompetitionConfig, index: Int, view: CompetitionMahjongPublicView) {
        CompetitionMahjongMatchLedger.validateFrame(config, index, view)
        require(!isTerminal(view) && view.netScores.all { it == 0 })
    }

    private fun isTerminal(view: CompetitionMahjongPublicView): Boolean =
        view.phase == CompetitionMahjongRoundPhase.WON || view.phase == CompetitionMahjongRoundPhase.EXHAUSTIVE_DRAW

    private suspend fun <T> transaction(block: suspend () -> T): T =
        withContext(NonCancellable + dispatcher) { transaction.withLock { block() } }
}
