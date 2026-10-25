package com.masterofchessstrategy.games.lanzhou_fangqi.data

import java.io.IOException
import java.lang.ref.ReferenceQueue
import java.lang.ref.WeakReference
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One gate per database identity. Restore coordination must use this same gate instance. */
internal class FangqiStorageEpochGate {
    private val mutex = Mutex()
    private val identity = Any()
    private val mutableEpoch = MutableStateFlow(0L)
    val epoch: StateFlow<Long> = mutableEpoch.asStateFlow()

    internal class Owner internal constructor(private val identity: Any, val epoch: Long) {
        internal fun belongsTo(candidate: Any) = identity === candidate
    }

    /** Capture once per writer lifetime; an old writer must never refresh its own owner. */
    fun captureOwner(): Owner = Owner(identity, mutableEpoch.value)

    suspend fun <T> access(owner: Owner, block: suspend () -> T): T = mutex.withLock {
        if (!owner.belongsTo(identity) || owner.epoch != mutableEpoch.value) {
            throw IOException("本地数据已替换，请重新打开方棋页面")
        }
        block()
    }

    /** Root wraps complete backup/restore operations, outside their Room transaction, in fixed gate order. */
    suspend fun <T> exclusive(replacing: Boolean, block: suspend () -> T): T = mutex.withLock {
        if (replacing) {
            check(mutableEpoch.value < Long.MAX_VALUE) { "方棋数据代次已达上限" }
            // Failure still retires old in-memory owners; the Room transaction preserves disk data.
            mutableEpoch.value += 1
        }
        block()
    }

    companion object {
        private class DatabaseReference(database: Any, queue: ReferenceQueue<Any>? = null) :
            WeakReference<Any>(database, queue) {
            private val identityHash = System.identityHashCode(database)
            override fun hashCode() = identityHash
            override fun equals(other: Any?): Boolean = this === other ||
                (other is DatabaseReference && get() != null && get() === other.get())
        }

        private val collected = ReferenceQueue<Any>()
        private val gates = mutableMapOf<DatabaseReference, FangqiStorageEpochGate>()

        @Synchronized fun forDatabase(database: Any): FangqiStorageEpochGate {
            while (true) gates.remove(collected.poll() ?: break)
            return gates[DatabaseReference(database)] ?: FangqiStorageEpochGate().also {
                gates[DatabaseReference(database, collected)] = it
            }
        }
    }
}
