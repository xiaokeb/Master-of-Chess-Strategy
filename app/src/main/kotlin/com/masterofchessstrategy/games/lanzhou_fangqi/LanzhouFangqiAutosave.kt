package com.masterofchessstrategy.games.lanzhou_fangqi

import com.masterofchessstrategy.engine.Difficulty
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal enum class FangqiStoredMode(val code: Int) {
    LOCAL_TWO_PLAYER(0), HUMAN_VS_AI(1), AI_AUTO_PLAY(2),
}

/** Metadata is separate from MOCS-LFQ. Native restore remains the rule/history validator. */
internal class FangqiStoredSession(
    val mode: FangqiStoredMode,
    val difficulty: Difficulty?,
    engineState: ByteArray,
    val sessionId: String,
    val updatedAtEpochMillis: Long,
    val acceptedActionCount: Int = 0,
    val undoUseCount: Int = 0,
    val hintUseCount: Int = 0,
) {
    private val bytes = engineState.copyOf()
    val engineState: ByteArray get() = bytes.copyOf()

    init {
        require(bytes.size in 1..MAX_ENGINE_BYTES)
        require(sessionId.isNotBlank() && sessionId.length <= 64)
        require(updatedAtEpochMillis >= 0)
        require(acceptedActionCount in 0..16_384 && undoUseCount >= 0 && hintUseCount >= 0)
        require((mode == FangqiStoredMode.LOCAL_TWO_PLAYER) == (difficulty == null))
    }

    companion object {
        const val ENGINE_FORMAT_VERSION = 1
        const val MAX_ENGINE_BYTES = 1024 * 1024
    }
}

/** Implemented by the shared Room integration, retaining one active Fangqi session. */
internal interface FangqiSessionStore {
    suspend fun load(): FangqiStoredSession?
    suspend fun save(snapshot: FangqiStoredSession)
}

internal data class FangqiSaveProgress(
    val savedRevision: Long = 0,
    val failedRevision: Long? = null,
    val closed: Boolean = false,
    val writerStopped: Boolean = false,
    val failure: Exception? = null,
)

/** One application-owned writer per game slot; route disposal must not close this queue. */
internal class LanzhouFangqiAutosave(
    private val store: FangqiSessionStore,
    scope: CoroutineScope,
) : AutoCloseable {
    private data class Request(val revision: Long, val snapshot: FangqiStoredSession)
    private val mailbox = Channel<Request>(Channel.CONFLATED)
    private val submissionLock = Any()
    private val storageLock = Mutex()
    private var nextRevision = 0L
    private var latest: Request? = null
    private var closed = false
    private val mutableProgress = MutableStateFlow(FangqiSaveProgress())
    val progress = mutableProgress.asStateFlow()

    private val worker = scope.launch {
        for (request in mailbox) {
            if (request.revision <= mutableProgress.value.savedRevision) continue
            try {
                storageLock.withLock { store.save(request.snapshot) }
                mutableProgress.update { it.copy(savedRevision = request.revision, failedRevision = null, failure = null) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                mutableProgress.update { it.copy(failedRevision = request.revision, failure = failure) }
            }
        }
    }

    init {
        worker.invokeOnCompletion {
            synchronized(submissionLock) {
                closed = true
                mailbox.close()
                mutableProgress.update { it.copy(closed = true, writerStopped = true) }
            }
        }
    }

    fun enqueue(snapshot: FangqiStoredSession): Long = synchronized(submissionLock) {
        check(!closed) { "Fangqi save queue is closed" }
        val request = Request(++nextRevision, snapshot)
        latest = request
        check(mailbox.trySend(request).isSuccess)
        request.revision
    }

    /** Returns false after a failed relevant write; the original stored row is retained. */
    suspend fun awaitSaved(revision: Long): Boolean {
        require(revision in 1..synchronized(submissionLock) { nextRevision })
        val finished = progress.first {
            it.savedRevision >= revision || (it.failedRevision ?: 0L) >= revision || it.closed
        }
        return finished.savedRevision >= revision
    }

    fun retryLatest(): Long? = synchronized(submissionLock) {
        check(!closed) { "Fangqi save queue is closed" }
        val request = latest ?: return null
        mutableProgress.update { it.copy(failedRevision = null, failure = null) }
        check(mailbox.trySend(request).isSuccess)
        request.revision
    }

    /** Drain the current writer before a new route reads the active game. */
    suspend fun loadAfterPendingWrites(): FangqiStoredSession? {
        check(!synchronized(submissionLock) { closed }) { "Fangqi save queue is closed" }
        val revision = synchronized(submissionLock) { latest?.revision }
        if (revision != null && !awaitSaved(revision)) {
            error("Pending Fangqi save failed; retained state must be recovered before loading")
        }
        return storageLock.withLock { store.load() }
    }

    override fun close() = synchronized(submissionLock) {
        if (!closed) {
            closed = true
            mutableProgress.update { it.copy(closed = true) }
            mailbox.close()
            worker.cancel()
        }
    }

    /** The owner must await this before replacing storage or disposing its dependencies. */
    suspend fun closeAndJoin() {
        close()
        worker.join()
    }
}
