package com.masterofchessstrategy.games.go

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class GoReplayUiState(
    val frame: GoReplayFrame? = null,
    val loading: Boolean = true,
    val seeking: Boolean = false,
    val playing: Boolean = false,
    val speed: Float = 1f,
    val error: String? = null,
    val closed: Boolean = false,
) {
    val ready get() = frame != null && !loading && !closed
}

/** Owns a single read-only record. Native work and disposal are serialized on a worker. */
class GoReplayController(
    parentScope: CoroutineScope,
    private val opener: () -> GoReplayReader,
    private val worker: CoroutineDispatcher = Dispatchers.Default,
) {
    private val monitor = Any()
    private val nativeMutex = Mutex()
    private val owner = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + owner)
    private val mutableState = MutableStateFlow(GoReplayUiState())
    val state: StateFlow<GoReplayUiState> = mutableState.asStateFlow()
    private var reader: GoReplayReader? = null // Access only under nativeMutex on the worker.
    private var generation = 0L
    private var operation: Job? = null

    init {
        scope.launch {
            val request = currentCoroutineContext()[Job]!!
            withContext(worker + NonCancellable) {
                nativeMutex.withLock {
                    var candidate: GoReplayReader? = null
                    try {
                        candidate = opener()
                        val initial = candidate.frame()
                        synchronized(monitor) {
                            if (request.isActive && !mutableState.value.closed) {
                                reader = candidate
                                candidate = null
                                mutableState.value = mutableState.value.copy(frame = initial, loading = false)
                            }
                        }
                    } catch (failure: Exception) {
                        synchronized(monitor) {
                            if (request.isActive && !mutableState.value.closed) {
                                mutableState.value = mutableState.value.copy(loading = false, error = "棋谱加载失败，请返回后重试。")
                            }
                        }
                    } finally { candidate?.close() }
                }
            }
        }
    }

    fun seek(index: Int) = synchronized(monitor) {
        val current = mutableState.value
        if (!current.ready) return@synchronized
        require(index in 0..current.frame!!.eventCount)
        val version = stopLocked()
        mutableState.value = mutableState.value.copy(seeking = true, error = null)
        operation = scope.launch { advance(version, index) }
    }

    fun previous() { state.value.frame?.let { seek((it.eventIndex - 1).coerceAtLeast(0)) } }
    fun next() { state.value.frame?.let { seek((it.eventIndex + 1).coerceAtMost(it.eventCount)) } }
    fun first() = seek(0)
    fun last() { state.value.frame?.let { seek(it.eventCount) } }

    fun setSpeed(speed: Float) = synchronized(monitor) {
        require(speed.isFinite() && speed in 0.5f..4f)
        if (!mutableState.value.closed) mutableState.value = mutableState.value.copy(speed = speed)
    }

    fun pause() = synchronized(monitor) { stopLocked(); Unit }

    fun play() = synchronized(monitor) {
        val current = mutableState.value
        if (!current.ready || current.seeking || current.playing || !current.frame!!.canNext) return@synchronized
        val version = stopLocked()
        mutableState.value = mutableState.value.copy(playing = true, error = null)
        operation = scope.launch {
            while (isActive) {
                delay((1_000 / state.value.speed).toLong())
                val index = synchronized(monitor) {
                    if (version != generation || mutableState.value.closed) return@launch
                    val frame = mutableState.value.frame!!
                    if (!frame.canNext) { mutableState.value = mutableState.value.copy(playing = false); return@launch }
                    mutableState.value = mutableState.value.copy(seeking = true)
                    frame.eventIndex + 1
                }
                if (!advance(version, index)) return@launch
            }
        }
    }

    private suspend fun advance(version: Long, index: Int): Boolean {
        val request = currentCoroutineContext()[Job]!!
        var accepted = false
        withContext(worker + NonCancellable) {
            nativeMutex.withLock {
                fun stale() = !request.isActive || synchronized(monitor) { version != generation || mutableState.value.closed }
                if (stale()) return@withLock
                try {
                    val frame = checkNotNull(reader).seek(index, ::stale)
                    synchronized(monitor) {
                        if (!stale()) {
                            val current = mutableState.value
                            mutableState.value = current.copy(frame = frame, seeking = false, playing = current.playing && frame.canNext)
                            accepted = true
                        }
                    }
                } catch (_: CancellationException) {
                    // Newer commands or disposal own the displayed state.
                } catch (_: Exception) {
                    synchronized(monitor) {
                        if (!stale()) mutableState.value = mutableState.value.copy(seeking = false, playing = false, error = "该步骤无法加载；已保留当前棋盘。")
                    }
                }
            }
        }
        return accepted
    }

    /** Call from an independent cleanup scope; joins in-flight Native work before closing. */
    suspend fun closeAndJoin() {
        synchronized(monitor) {
            if (!mutableState.value.closed) {
                stopLocked()
                mutableState.value = mutableState.value.copy(closed = true, loading = false)
            }
        }
        withContext(NonCancellable) {
            owner.cancelAndJoin()
            withContext(worker) { nativeMutex.withLock { reader?.close(); reader = null } }
        }
    }

    private fun stopLocked(): Long {
        generation++
        operation?.cancel()
        operation = null
        mutableState.value = mutableState.value.copy(playing = false, seeking = false)
        return generation
    }
}
