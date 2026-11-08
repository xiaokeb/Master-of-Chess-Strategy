package com.masterofchessstrategy.games.competition_mahjong.learning

import java.io.IOException
import java.lang.ref.ReferenceQueue
import java.lang.ref.WeakReference
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Serializes competition mahjong storage with full backup operations for one database identity. */
internal class CompetitionMahjongStorageEpochGate {
    private val mutex = Mutex()
    private val identity = Any()
    private val mutableEpoch = MutableStateFlow(0L)
    val epoch: StateFlow<Long> = mutableEpoch.asStateFlow()

    internal class Owner internal constructor(private val identity: Any, val epoch: Long) {
        internal fun belongsTo(candidate: Any) = identity === candidate
    }

    /** Capture once per page/writer lifetime. An old writer must never renew this identity. */
    fun captureOwner(): Owner = Owner(identity, mutableEpoch.value)

    fun isCurrent(owner: Owner): Boolean = owner.belongsTo(identity) && owner.epoch == mutableEpoch.value

    suspend fun <T> access(owner: Owner, block: suspend () -> T): T = locked {
        if (!isCurrent(owner)) {
            throw IOException("本地数据已替换，请重新打开国标麻将页面")
        }
        block()
    }

    /** Must surround the complete delegate operation, outside its Room transaction. */
    suspend fun <T> exclusive(replacing: Boolean, block: suspend () -> T): T = locked {
        if (replacing) {
            check(mutableEpoch.value < Long.MAX_VALUE) { "国标麻将数据代次已达上限" }
            // Retire memory even if restore fails; transaction rollback belongs to the delegate.
            mutableEpoch.value += 1
        }
        block()
    }

    private suspend fun <T> locked(block: suspend () -> T): T {
        val held = currentCoroutineContext()[HeldGates]
        check(held?.contains(identity) != true) { "国标麻将存储门禁不能重入" }
        return mutex.withLock {
            withContext(HeldGates(identity, held)) { block() }
        }
    }

    // Context propagation also rejects a nested child waiting for its parent's held gate.
    private class HeldGates(val identity: Any, val previous: HeldGates?) :
        AbstractCoroutineContextElement(Key) {
        fun contains(candidate: Any): Boolean = identity === candidate || previous?.contains(candidate) == true
        companion object Key : CoroutineContext.Key<HeldGates>
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
        private val gates = mutableMapOf<DatabaseReference, CompetitionMahjongStorageEpochGate>()

        /** Equality-overridden database objects still receive distinct gates. */
        @Synchronized fun forDatabase(database: Any): CompetitionMahjongStorageEpochGate {
            while (true) gates.remove(collected.poll() ?: break)
            return gates[DatabaseReference(database)] ?: CompetitionMahjongStorageEpochGate().also {
                gates[DatabaseReference(database, collected)] = it
            }
        }
    }
}
