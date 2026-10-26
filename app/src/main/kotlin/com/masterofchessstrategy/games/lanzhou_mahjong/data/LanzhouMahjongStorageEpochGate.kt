package com.masterofchessstrategy.games.lanzhou_mahjong.data

import java.io.IOException
import java.lang.ref.ReferenceQueue
import java.lang.ref.WeakReference
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One identity-based gate per database. The surrounding backup factory owns the global lock order. */
internal class LanzhouMahjongStorageEpochGate {
    private val mutex = Mutex()
    private val identity = Any()
    private val mutableEpoch = MutableStateFlow(0L)
    val epoch: StateFlow<Long> = mutableEpoch.asStateFlow()

    internal class Owner internal constructor(private val identity: Any, val epoch: Long) {
        internal fun belongsTo(candidate: Any): Boolean = identity === candidate
    }

    /** Capture once per writer lifetime; a retired writer must not refresh its own identity. */
    fun captureOwner(): Owner = Owner(identity, mutableEpoch.value)

    suspend fun <T> access(owner: Owner, block: suspend () -> T): T = mutex.withLock {
        if (!owner.belongsTo(identity) || owner.epoch != mutableEpoch.value) {
            throw IOException("本地数据已替换，请重新打开兰州麻将页面")
        }
        block()
    }

    /** Wait for active access, then retire all previous owners before replacement, even on failure. */
    suspend fun <T> exclusive(replacing: Boolean, block: suspend () -> T): T = mutex.withLock {
        if (replacing) {
            check(mutableEpoch.value < Long.MAX_VALUE) { "兰州麻将数据代次已达上限" }
            mutableEpoch.value += 1
        }
        block()
    }

    companion object {
        private class DatabaseReference(database: Any, queue: ReferenceQueue<Any>? = null) :
            WeakReference<Any>(database, queue) {
            private val identityHash = System.identityHashCode(database)
            override fun hashCode(): Int = identityHash
            override fun equals(other: Any?): Boolean = this === other ||
                (other is DatabaseReference && get() != null && get() === other.get())
        }

        private val collected = ReferenceQueue<Any>()
        private val gates = mutableMapOf<DatabaseReference, LanzhouMahjongStorageEpochGate>()

        @Synchronized fun forDatabase(database: Any): LanzhouMahjongStorageEpochGate {
            while (true) gates.remove(collected.poll() ?: break)
            return gates[DatabaseReference(database)] ?: LanzhouMahjongStorageEpochGate().also {
                gates[DatabaseReference(database, collected)] = it
            }
        }
    }
}
