package com.masterofchessstrategy.games.competition_mahjong.auto

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class CompetitionMahjongAutoPlaybackState(
    val activity: CompetitionMahjongAutoState = CompetitionMahjongAutoState(),
    val busy: Boolean = false,
    val foreground: Boolean = false,
    val backgroundAuthorized: Boolean = false,
    val halted: Boolean = false,
    val error: String? = null,
) {
    val hostPermitsExecution: Boolean get() = foreground || (backgroundAuthorized && activity.settings.backgroundEnabled)
    val running: Boolean get() = activity.frame != null && !activity.closed && !activity.needsSave &&
        !activity.finished && !activity.settings.paused && hostPermitsExecution && !halted
    val canStep: Boolean get() = activity.frame != null && !busy && !activity.closed && !activity.needsSave &&
        !activity.finished && (activity.settings.paused || halted)
    val canUndo: Boolean get() = canStep && !activity.seriesComplete && activity.eventsAfterOpening > 0
}

/** One owner for commands and timers. Android lifecycle/service permission is supplied by the host. */
class CompetitionMahjongAutoPlayback(
    private val controller: CompetitionMahjongAutoController,
    parentScope: CoroutineScope,
    private val waitMillis: suspend (Long) -> Unit = { delay(it) },
) {
    private sealed interface Event {
        data object Changed : Event
        class Tick(val revision: Long, val expected: CompetitionMahjongAutoState) : Event
        class Request(val operation: Operation, val reply: CompletableDeferred<Boolean>) : Event
    }
    private sealed interface Operation {
        data object Open : Operation
        data class Host(val foreground: Boolean, val backgroundAuthorized: Boolean) : Operation
        data class Settings(val value: CompetitionMahjongAutoSettings) : Operation
        data object Step : Operation
        data object Undo : Operation
        data object Retry : Operation
        class NewRun(val config: CompetitionMahjongAutoRunConfig, val settings: CompetitionMahjongAutoSettings,
                     val opening: ByteArray?) : Operation
        data object Close : Operation
    }
    private val ownerJob = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + ownerJob)
    private val events = Channel<Event>(Channel.UNLIMITED)
    private val mutableState = MutableStateFlow(CompetitionMahjongAutoPlaybackState(activity = controller.state.value))
    val state = mutableState.asStateFlow()
    private var foreground = false
    private var backgroundAuthorized = false
    private var halted = false
    private var busy = false
    private var failure: String? = null
    private var revision = 0L
    private var timer: Job? = null
    private val collector = scope.launch { controller.state.collect { events.send(Event.Changed) } }
    private val owner = scope.launch {
        var closing: Event.Request? = null
        var activeRequest: Event.Request? = null
        try {
            for (event in events) {
                cancelTimer()
                when (event) {
                    Event.Changed -> Unit
                    is Event.Tick -> {
                        // cancelTimer advances revision, so a live tick belongs to the immediately prior revision.
                        if (event.revision == revision - 1 && snapshot().running) {
                            busy = true
                            publish()
                            try {
                                if (!controller.scheduledStep(event.expected)) stopAfterFailure()
                            } catch (error: Exception) {
                                if (error is CancellationException) throw error
                                stopAfterFailure(error)
                            } finally { busy = false }
                        }
                    }
                    is Event.Request -> {
                        activeRequest = event
                        if (event.operation == Operation.Close) { closing = event; break }
                        busy = true
                        publish()
                        val accepted = try {
                            execute(event.operation)
                        } catch (error: Exception) {
                            if (error is CancellationException) throw error
                            stopAfterFailure(error)
                            false
                        }
                        busy = false
                        if (!accepted && event.operation !is Operation.Host) stopAfterFailure()
                        reconcileExecution()
                        publish()
                        schedule()
                        event.reply.complete(accepted && !controller.state.value.needsSave)
                        activeRequest = null
                        continue
                    }
                }
                reconcileExecution()
                publish()
                schedule()
            }
        } finally {
            cancelTimer()
            collector.cancel()
            withContext(NonCancellable) {
                try { controller.setExecutionActive(false) } finally { controller.close() }
            }
            busy = false
            publish()
            events.close()
            closing?.reply?.complete(true)
            activeRequest?.reply?.complete(false)
            while (true) {
                val pending = events.tryReceive().getOrNull() ?: break
                if (pending is Event.Request) pending.reply.complete(false)
            }
        }
    }.also { job -> job.invokeOnCompletion { ownerJob.cancel() } }

    suspend fun open(): Boolean = submit(Operation.Open)
    suspend fun setHost(foreground: Boolean, backgroundAuthorized: Boolean = false): Boolean =
        submit(Operation.Host(foreground, backgroundAuthorized))
    suspend fun updateSettings(value: CompetitionMahjongAutoSettings): Boolean = submit(Operation.Settings(value))
    suspend fun step(): Boolean = submit(Operation.Step)
    suspend fun undo(): Boolean = submit(Operation.Undo)
    suspend fun retrySave(): Boolean = submit(Operation.Retry)
    suspend fun newRun(config: CompetitionMahjongAutoRunConfig, settings: CompetitionMahjongAutoSettings,
                       openingRecord: ByteArray? = null): Boolean =
        submit(Operation.NewRun(config, settings, openingRecord?.copyOf()))
    suspend fun close(): Boolean {
        if (owner.isCompleted) return true
        return submit(Operation.Close)
    }

    private suspend fun submit(operation: Operation): Boolean {
        val reply = CompletableDeferred<Boolean>()
        if (!events.trySend(Event.Request(operation, reply)).isSuccess) return false
        return reply.await()
    }
    private suspend fun execute(operation: Operation): Boolean = when (operation) {
        Operation.Open -> controller.open().also { if (it) { halted = false; failure = null } }
        is Operation.Host -> { foreground = operation.foreground; backgroundAuthorized = operation.backgroundAuthorized; true }
        is Operation.Settings -> controller.updateSettings(operation.value).also { if (it) { halted = false; failure = null } }
        Operation.Step -> {
            (if (!pauseForManual()) false else controller.state.value.let { controller.step(it.seriesIndex, it.handIndex) })
                .also { if (it) { halted = false; failure = null } }
        }
        Operation.Undo -> {
            (if (!pauseForManual()) false else controller.state.value.let { controller.undo(it.seriesIndex, it.handIndex) })
                .also { if (it) { halted = false; failure = null } }
        }
        Operation.Retry -> controller.retrySave().also { if (it) { halted = false; failure = null } }
        is Operation.NewRun -> controller.newRun(operation.config, operation.settings, operation.opening).also {
            if (it) { halted = false; failure = null }
        }
        Operation.Close -> error("Close is handled by the owner")
    }
    private suspend fun pauseForManual(): Boolean {
        val current = controller.state.value
        return current.settings.paused || controller.updateSettings(current.settings.copy(paused = true))
    }
    private suspend fun reconcileExecution() {
        val current = controller.state.value
        val permitted = current.frame != null && !current.closed && !halted &&
            (foreground || (backgroundAuthorized && current.settings.backgroundEnabled))
        if (current.executionActive != permitted && !controller.setExecutionActive(permitted)) stopAfterFailure()
    }
    private fun snapshot() = CompetitionMahjongAutoPlaybackState(controller.state.value, busy, foreground,
        backgroundAuthorized, halted, failure ?: controller.state.value.error)
    private fun publish() { mutableState.value = snapshot() }
    private fun stopAfterFailure(error: Exception? = null) {
        halted = true
        failure = error?.message ?: controller.state.value.error ?: "自动演局未推进，请检查后重试"
    }
    private fun cancelTimer() { timer?.cancel(); timer = null; revision++ }
    private fun schedule() {
        if (!snapshot().running) return
        val expected = controller.state.value
        val token = revision
        timer = scope.launch {
            waitMillis(expected.settings.delayMillis(expected.frame?.isAutoTerminal() == true, foreground))
            events.send(Event.Tick(token, expected))
        }
    }
}
