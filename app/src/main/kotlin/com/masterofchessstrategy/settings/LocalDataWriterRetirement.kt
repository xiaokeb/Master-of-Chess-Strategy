package com.masterofchessstrategy.settings

import androidx.annotation.MainThread
import kotlinx.coroutines.Deferred

/** A writer remains registered until its separate resource cleanup has succeeded. */
interface LocalDataWriterRetirement {
    /** Stable across repeated calls, including after the ViewModel scope has ended. */
    val retirementCompletion: Deferred<Unit>

    /** Synchronously rejects new work; the returned completion also covers resource release. */
    @MainThread
    fun beginRetirement(): Deferred<Unit>
}

/** Carries every cleanup error without mutating coroutine-recovered exception copies. */
class LocalDataWriterFailure private constructor(errors: List<Throwable>) :
    RuntimeException("Multiple local-data cleanup failures (${errors.size})", errors.first()) {
    // A dedicated immutable payload survives suspension; suppressed is only a diagnostic view.
    val failures: List<Throwable> = java.util.Collections.unmodifiableList(ArrayList(errors))

    init { failures.drop(1).forEach(::addSuppressed) }

    companion object {
        /** Preserve single-error compatibility and deduplicate only identical occurrences. */
        fun combine(errors: List<Throwable>): Throwable? {
            val distinct = errors.fold(mutableListOf<Throwable>()) { result, error ->
                if (result.none { it === error }) result.add(error)
                result
            }
            return when (distinct.size) {
                0 -> null
                1 -> distinct.single()
                else -> LocalDataWriterFailure(distinct)
            }
        }
    }
}
