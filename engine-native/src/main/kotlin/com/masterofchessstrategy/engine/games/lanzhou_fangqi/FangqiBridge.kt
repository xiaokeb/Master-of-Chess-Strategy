package com.masterofchessstrategy.engine.games.lanzhou_fangqi

/** Raw values stay inside the facade; every snapshot is obtained in one native call. */
internal interface FangqiBridge {
    fun create(): Long
    fun createFromPosition(board: IntArray, sideCode: Int): Long
    fun release(handle: Long)
    fun snapshot(handle: Long): IntArray
    fun apply(handle: Long, kind: Int, x: Int, y: Int, toX: Int, toY: Int): Int
    fun legalActions(handle: Long): IntArray
    fun undo(handle: Long): Boolean
    fun reset(handle: Long)
    fun serialize(handle: Long): ByteArray
    fun restore(handle: Long, data: ByteArray): Boolean
    fun chooseAction(handle: Long, difficultyCode: Int): IntArray
}

internal object FangqiBindings {
    init {
        System.loadLibrary("mocs_engine_native")
    }

    external fun create(): Long
    external fun createFromPosition(board: IntArray, sideCode: Int): Long
    external fun release(handle: Long)
    external fun snapshot(handle: Long): IntArray
    external fun apply(handle: Long, kind: Int, x: Int, y: Int, toX: Int, toY: Int): Int
    external fun legalActions(handle: Long): IntArray
    external fun undo(handle: Long): Boolean
    external fun reset(handle: Long)
    external fun serialize(handle: Long): ByteArray
    external fun restore(handle: Long, data: ByteArray): Boolean
    external fun chooseAction(handle: Long, difficultyCode: Int): IntArray
}

internal object JniFangqiBridge : FangqiBridge {
    override fun create() = FangqiBindings.create()
    override fun createFromPosition(board: IntArray, sideCode: Int) =
        FangqiBindings.createFromPosition(board, sideCode)
    override fun release(handle: Long) = FangqiBindings.release(handle)
    override fun snapshot(handle: Long) = FangqiBindings.snapshot(handle)
    override fun apply(handle: Long, kind: Int, x: Int, y: Int, toX: Int, toY: Int) =
        FangqiBindings.apply(handle, kind, x, y, toX, toY)
    override fun legalActions(handle: Long) = FangqiBindings.legalActions(handle)
    override fun undo(handle: Long) = FangqiBindings.undo(handle)
    override fun reset(handle: Long) = FangqiBindings.reset(handle)
    override fun serialize(handle: Long) = FangqiBindings.serialize(handle)
    override fun restore(handle: Long, data: ByteArray) = FangqiBindings.restore(handle, data)
    override fun chooseAction(handle: Long, difficultyCode: Int) =
        FangqiBindings.chooseAction(handle, difficultyCode)
}
