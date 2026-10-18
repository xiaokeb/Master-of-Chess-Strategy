package com.masterofchessstrategy.engine.games.tiger_goat

import java.util.concurrent.atomic.AtomicBoolean

/** Synchronous immutable-snapshot JNI; no Registry/session pointer or background Native thread. */
internal object TigerGoatSearchBindings : TigerGoatSearchBridge {
    init { System.loadLibrary("mocs_engine_native") }

    external override fun search(
        snapshot: ByteArray,
        turnDepth: Int,
        nodeLimit: Long,
        timeLimitMillis: Long,
        cancelled: AtomicBoolean,
    ): LongArray
}
