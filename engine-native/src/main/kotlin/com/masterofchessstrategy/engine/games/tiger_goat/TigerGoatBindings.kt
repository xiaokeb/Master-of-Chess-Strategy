package com.masterofchessstrategy.engine.games.tiger_goat

/** Production JNI bridge; the session validates wire values before exposing them. */
internal object TigerGoatBindings : TigerGoatBridge {
    init { System.loadLibrary("mocs_engine_native") }

    external override fun create(): Long
    external override fun release(handle: Long)
    external override fun geometry(): IntArray
    external override fun snapshot(handle: Long): IntArray
    external override fun legalActions(handle: Long): IntArray
    external override fun apply(handle: Long, kind: Int, from: Int, to: Int): Int
    external override fun undo(handle: Long): Boolean
    external override fun reset(handle: Long)
    external override fun serialize(handle: Long): ByteArray
    external override fun restore(handle: Long, bytes: ByteArray): Boolean
}
