package com.masterofchessstrategy.engine.internal

/** JNI declarations remain internal so callers cannot bypass the safe facade. */
internal object NativeBindings {
    init {
        System.loadLibrary("mocs_engine_native")
    }

    external fun healthCheck(): String
}
