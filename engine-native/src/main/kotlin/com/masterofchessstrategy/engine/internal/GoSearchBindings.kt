package com.masterofchessstrategy.engine.internal

/** Distinct runtime/request handle types; all native lookups retain shared ownership. */
internal object GoSearchBindings {
    init { System.loadLibrary("mocs_engine_native") }
    external fun create(modelPath: String): Long
    external fun prepare(runtime: Long, snapshot: ByteArray, visits: Int, timeMillis: Long): Long
    external fun run(request: Long): DoubleArray
    external fun cancel(request: Long)
    external fun cancelAll(runtime: Long)
    external fun processedRows(runtime: Long): Long
    external fun closeRuntime(runtime: Long)
    external fun closeRequest(request: Long)
}
