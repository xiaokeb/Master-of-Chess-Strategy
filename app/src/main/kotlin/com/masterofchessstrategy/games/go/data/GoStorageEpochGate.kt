package com.masterofchessstrategy.games.go.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.util.WeakHashMap

/** One gate per Room owner: disk migration and snapshots cannot interleave with any Go writer. */
internal class GoStorageEpochGate {
    private val mutex = Mutex()
    private val mutableEpoch = MutableStateFlow(0L)
    val epoch: StateFlow<Long> = mutableEpoch.asStateFlow()

    suspend fun <T> access(ownerEpoch: Long, block: suspend () -> T): T = mutex.withLock {
        if (ownerEpoch != mutableEpoch.value) throw IOException("本地数据已替换，请重新打开围棋页面")
        block()
    }

    suspend fun <T> backup(replacing: Boolean, block: suspend () -> T): T = mutex.withLock {
        if (replacing) {
            check(mutableEpoch.value < Long.MAX_VALUE) { "围棋数据代次已达上限" }
            // Even a failed replacement retires old in-memory pages; persisted data still rolls back.
            mutableEpoch.value += 1
        }
        block()
    }

    companion object {
        // Values contain no database reference; activity recreation shares a gate without leaking DBs.
        private val databases = WeakHashMap<Any, GoStorageEpochGate>()
        @Synchronized fun forDatabase(database: Any): GoStorageEpochGate =
            databases.getOrPut(database) { GoStorageEpochGate() }
    }
}
