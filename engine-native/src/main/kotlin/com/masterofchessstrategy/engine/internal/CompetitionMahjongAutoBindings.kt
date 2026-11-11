package com.masterofchessstrategy.engine.internal

/** Distinct entry points and handle type; human MCMS calls cannot operate on MCMA sessions. */
internal object CompetitionMahjongAutoBindings {
    init { System.loadLibrary("mocs_engine_native") }
    external fun create(seed: Long, dealer: Int, wind: Int, viewer: Int, levels: IntArray, decisionSeed: Long): Long
    external fun release(handle: Long)
    external fun snapshot(handle: Long): IntArray
    /** Version 1, accepted event count, terminal boolean. No private bot action is returned. */
    external fun advance(handle: Long, budget: Int): IntArray
    external fun undo(handle: Long): Boolean
    external fun serialize(handle: Long): ByteArray
    external fun restore(handle: Long, data: ByteArray): Boolean
}
