package com.masterofchessstrategy.games.lanzhou_fangqi.data

import com.masterofchessstrategy.data.MocsDatabase
import com.masterofchessstrategy.games.lanzhou_fangqi.FangqiSessionStore
import com.masterofchessstrategy.games.lanzhou_fangqi.LanzhouFangqiAutosave
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** The application retains this owner; page disposal never owns the idle save queue. */
internal class FangqiLocalStorage internal constructor(
    val gate: FangqiStorageEpochGate,
    private val createStore: (FangqiStorageEpochGate.Owner) -> FangqiSessionStore,
    private val writerDispatcher: CoroutineDispatcher,
) {
    val epoch: StateFlow<Long> get() = gate.epoch
    private val opening = Mutex()
    private var writer: Writer? = null
    private var previousLease: FangqiStorageLease? = null

    private class Writer(
        val epoch: Long,
        store: FangqiSessionStore,
        dispatcher: CoroutineDispatcher,
    ) {
        private val job = SupervisorJob()
        val queue = LanzhouFangqiAutosave(store, CoroutineScope(job + dispatcher))
        suspend fun closeAndJoin() {
            queue.closeAndJoin()
            job.cancelAndJoin()
        }
    }

    /** One Native owner at a time. Old cleanup failure remains a real acquisition error. */
    suspend fun acquire(): FangqiStorageLease = opening.withLock {
        previousLease?.released?.await()
        val currentEpoch = gate.epoch.value
        var current = writer
        if (current != null && (current.epoch != currentEpoch || current.queue.progress.value.closed)) {
            current.closeAndJoin()
            writer = null
            current = null
        }
        if (current == null) {
            val owner = gate.captureOwner()
            current = Writer(owner.epoch, createStore(owner), writerDispatcher)
            writer = current
        }
        FangqiStorageLease(current.queue, current.epoch, current::closeAndJoin).also { previousLease = it }
    }

    companion object {
        // Weak values avoid retaining a closed test/database owner forever. App remember + VM own it while used.
        private val owners = WeakHashMap<FangqiStorageEpochGate, WeakReference<FangqiLocalStorage>>()
        @Synchronized fun forDatabase(database: MocsDatabase): FangqiLocalStorage {
            val gate = FangqiStorageEpochGate.forDatabase(database)
            return owners[gate]?.get() ?: FangqiLocalStorage(gate,
                { owner -> RoomFangqiSessionStore(database, gate, owner) }, Dispatchers.IO).also {
                owners[gate] = WeakReference(it)
            }
        }
    }
}

/** Release only after the VM has stopped Native work and actually closed its session. */
internal class FangqiStorageLease internal constructor(
    val autosave: LanzhouFangqiAutosave,
    val epoch: Long,
    private val retire: suspend () -> Unit,
) {
    internal val released = CompletableDeferred<Unit>()
    suspend fun retireWriter() = retire()
    fun release(failure: Throwable? = null) {
        if (failure == null) released.complete(Unit) else released.completeExceptionally(failure)
    }
}
