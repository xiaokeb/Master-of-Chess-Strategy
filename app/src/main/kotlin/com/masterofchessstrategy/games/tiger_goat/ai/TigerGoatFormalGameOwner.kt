package com.masterofchessstrategy.games.tiger_goat.ai

import com.masterofchessstrategy.games.tiger_goat.TigerGoatBoardSpec
import com.masterofchessstrategy.games.tiger_goat.TigerGoatDisplayState
import com.masterofchessstrategy.games.tiger_goat.TigerGoatGameController
import com.masterofchessstrategy.games.tiger_goat.TigerGoatPhase
import com.masterofchessstrategy.games.tiger_goat.TigerGoatRulesSession
import com.masterofchessstrategy.games.tiger_goat.TigerGoatWinner
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal data class TigerGoatFormalPageState(
    val boardSpec: TigerGoatBoardSpec? = null,
    val game: TigerGoatDisplayState? = null,
    val config: TigerGoatFormalConfig? = null,
    val busy: Boolean = true,
    val thinking: Boolean = false,
    val error: String? = null,
    val canRetrySave: Boolean = false,
    val canRetryAi: Boolean = false,
    val recoveryBlocked: Boolean = false,
    val retired: Boolean = false,
)

/**
 * Host calls controls/advanceAi/beginRetirement on one control dispatcher (Android Main).
 * Persistence is serialized here. Host must join retire() outside a cancelled page scope,
 * before releasing the fixed writer or navigating away. No worker mutates the live board.
 */
internal class TigerGoatFormalGameOwner(
    private val store: TigerGoatFormalSessionStore,
    private val rules: TigerGoatRulesSession,
    private val search: TigerGoatFormalSearch,
    private val initialConfig: TigerGoatFormalConfig,
    private val isQualified: (TigerGoatFormalConfig) -> Boolean,
    private val now: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    private val controlThread = Thread.currentThread()
    private val operations = Mutex()
    private val mutableState = MutableStateFlow(TigerGoatFormalPageState())
    val uiState: StateFlow<TigerGoatFormalPageState> = mutableState
    private var controller = TigerGoatGameController(rules)
    private var config = initialConfig
    private var sessionId = ""
    private var revision = 0L
    private var pendingSave: TigerGoatFormalCheckpoint? = null
    private var loaded = false
    private var retiring = false
    private var closed = false
    private var retirementFailure: Throwable? = null

    suspend fun load() = operations.withLock {
        control()
        if (!acceptsWork() || loaded || pendingSave != null) return@withLock
        search.cancel()
        publish(busy = true)
        try {
            val result = store.load()
            if (!acceptsWork()) return@withLock
            when (result) {
                TigerGoatFormalLoadResult.Missing -> {
                    if (!isQualified(initialConfig)) return@withLock qualificationError()
                    config = initialConfig
                    rules.reset()
                    controller = TigerGoatGameController(rules)
                    sessionId = newId()
                    revision = 0
                    loaded = true
                    checkpointAndSave()
                }
                TigerGoatFormalLoadResult.LocalGame ->
                    publish(error = "已有本地双人棋局，请从本地对局入口继续", recoveryBlocked = true)
                TigerGoatFormalLoadResult.Incompatible -> recoveryError()
                is TigerGoatFormalLoadResult.Loaded -> {
                    if (!acceptsWork()) return@withLock
                    val saved = result.checkpoint
                    if (!isQualified(saved.config)) return@withLock qualificationError()
                    if (!rules.restore(saved.copyBytes()) || rules.actionCount != saved.actionCount) {
                        recoveryError()
                    } else {
                        config = saved.config
                        sessionId = saved.sessionId
                        revision = saved.revision
                        loaded = true
                        pendingSave = null
                        controller = TigerGoatGameController(rules)
                        armSearch()
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            if (acceptsWork()) recoveryError()
        }
    }

    suspend fun onPointTap(point: Int) = human { it.onPointTap(point) }
    suspend fun endChain() = human { it.endChain() }
    suspend fun passGoat() = human { it.passGoat() }
    suspend fun resign() = human { it.resign() }

    private suspend fun human(action: (TigerGoatGameController) -> Unit) = operations.withLock {
        control()
        if (!ready() || isAiTurn()) return@withLock
        val before = rules.serialize()
        action(controller)
        if (before.contentEquals(rules.serialize())) publish() else checkpointAndSave()
    }

    /** Rewind to the start of the human turn, including both sides' complete jump chains. */
    suspend fun undo() = operations.withLock {
        control()
        if (!ready(allowAiFailure = true)) return@withLock
        search.cancel()
        if (rules.undo()) {
            while (rules.actionCount > 0 && rules.snapshot().let {
                it.toMove != config.humanSide || it.phase == TigerGoatPhase.CAPTURE_CHAIN
            }) {
                if (!rules.undo()) break
            }
            controller = TigerGoatGameController(rules)
            checkpointAndSave()
        } else armSearch()
    }

    /** Host obtains explicit confirmation before replacing a recovery-blocked checkpoint. */
    suspend fun restart() = operations.withLock {
        control()
        if (!acceptsWork() || pendingSave != null) return@withLock
        if (!isQualified(config)) return@withLock qualificationError()
        search.cancel()
        rules.reset()
        controller = TigerGoatGameController(rules)
        sessionId = newId()
        revision = 0
        loaded = true
        checkpointAndSave()
    }

    /** Host polls only while this page is active. Each accepted AI action is saved first. */
    suspend fun advanceAi() = operations.withLock {
        control()
        if (!ready() || !isAiTurn()) return@withLock
        try {
            if (search.failed) return@withLock aiError()
            val action = search.consume(checkpoint())
            if (action == null) {
                search.start() // A cancelled Native request may still be draining; retry next tick.
                return@withLock
            }
            if (action !in rules.legalActions()) return@withLock aiError()
            search.cancel()
            if (rules.apply(action) != 0) return@withLock aiError()
            controller = TigerGoatGameController(rules)
            checkpointAndSave() // A tiger chain keeps the same AI side and searches again.
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            aiError()
        }
    }

    suspend fun retryAi() = operations.withLock {
        control()
        if (ready(allowAiFailure = true) && mutableState.value.canRetryAi) armSearch()
    }

    suspend fun retrySave() = operations.withLock {
        control()
        if (acceptsWork() && pendingSave != null) savePending()
    }

    /** Non-blocking admission fence. Call synchronously on leave or storage-epoch change. */
    fun beginRetirement() {
        control()
        if (retiring || closed) return
        retiring = true
        search.seal()
        mutableState.value = mutableState.value.copy(
            busy = true, thinking = false, retired = true, canRetryAi = false,
            canRetrySave = false, game = mutableState.value.game?.copy(busy = true),
        )
    }

    suspend fun retire() {
        beginRetirement()
        withContext(NonCancellable) {
            operations.withLock {
                retirementFailure?.let { throw it }
                if (closed) return@withLock
                try {
                    withContext(Dispatchers.IO) { search.closeAndJoinBlocking() }
                    rules.close()
                    closed = true
                    pendingSave = null
                    mutableState.value = mutableState.value.copy(busy = false)
                } catch (failure: Throwable) {
                    retirementFailure = failure
                    mutableState.value = mutableState.value.copy(
                        busy = false, error = "对局资源释放失败，暂时无法安全返回",
                    )
                    throw failure
                }
            }
        }
    }

    private suspend fun checkpointAndSave() {
        check(revision < Long.MAX_VALUE) { "虎羊棋对局版本已达上限" }
        revision += 1
        pendingSave = checkpoint()
        savePending()
    }

    private suspend fun savePending() {
        val saved = pendingSave ?: return
        publish(busy = true)
        try {
            store.save(saved.copyOwned())
            if (!acceptsWork()) return
            pendingSave = null
            armSearch()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            if (acceptsWork()) publish(error = "存档保存失败，请重试后继续", canRetrySave = true)
        }
    }

    private fun checkpoint() = TigerGoatFormalCheckpoint(
        sessionId, revision, config, rules.actionCount, now(), rules.serialize(),
    )

    private fun armSearch() {
        if (!acceptsWork()) return
        try {
            search.replace(checkpoint())
            if (isAiTurn()) search.start()
            publish()
        } catch (_: Exception) {
            aiError()
        }
    }

    private fun isAiTurn() = rules.snapshot().let {
        it.winner == TigerGoatWinner.NONE && it.toMove != config.humanSide
    }

    private fun ready(allowAiFailure: Boolean = false): Boolean = acceptsWork() && loaded &&
        pendingSave == null && !mutableState.value.recoveryBlocked &&
        (mutableState.value.error == null || (allowAiFailure && mutableState.value.canRetryAi))

    private fun acceptsWork(): Boolean {
        if (retiring || closed) return false
        if (!store.isCurrent) {
            beginRetirement()
            mutableState.value = mutableState.value.copy(error = "本地数据已替换，请重新打开虎羊棋")
            return false
        }
        return true
    }

    private fun control() = check(Thread.currentThread() === controlThread) {
        "虎羊棋对局必须在同一控制线程操作"
    }

    private fun qualificationError() = publish(error = "请先完成老虎吃羊棋教程", recoveryBlocked = true)
    private fun recoveryError() = publish(error = "存档无法恢复，请重试或确认重开", recoveryBlocked = true)
    private fun aiError() {
        search.cancel()
        publish(error = "AI 暂不可用，请重试或悔棋", canRetryAi = true)
    }

    private fun publish(
        busy: Boolean = false,
        error: String? = null,
        canRetrySave: Boolean = false,
        canRetryAi: Boolean = false,
        recoveryBlocked: Boolean = false,
    ) {
        if (!acceptsWork()) return
        val thinking = loaded && pendingSave == null && error == null && isAiTurn()
        mutableState.value = TigerGoatFormalPageState(
            rules.spec, controller.uiState.value.copy(busy = busy || error != null || thinking),
            config, busy, thinking, error, canRetrySave, canRetryAi, recoveryBlocked,
        )
    }
}
