package com.masterofchessstrategy.engine

/** CPU search limits. Time is a soft deadline; an in-flight inference may finish after it. */
data class GoSearchBudget(val maxVisits: Int, val maxTimeMillis: Long) {
    init {
        require(maxVisits in 1..512) { "Visits must be in 1..512" }
        require(maxTimeMillis in 1L..20_000L) { "Search time must be in 1..20000 ms" }
    }
}

/** White-perspective search diagnostics, never a score agreement or adjudicated result. */
data class GoSearchMetrics(
    val visits: Int,
    val elapsedMillis: Double,
    val whiteWinProbability: Double,
    val whiteMinusBlackPoints: Double,
) {
    init {
        require(visits in 1..512) { "Completed visits must be in 1..512" }
        require(elapsedMillis.isFinite() && elapsedMillis >= 0.0) { "Invalid elapsed time" }
        require(whiteWinProbability.isFinite() && whiteWinProbability in 0.0..1.0) {
            "Invalid White win probability"
        }
        require(whiteMinusBlackPoints.isFinite()) { "Invalid score estimate" }
    }
}

/** Only Move carries an action. Search cannot mark dead stones, settle, resume, or resign. */
sealed interface GoSearchResult {
    data class Move(val action: GoAction, val metrics: GoSearchMetrics) : GoSearchResult {
        init {
            require(action is GoAction.Place || action == GoAction.Pass) {
                "Search may suggest only a placement or pass"
            }
        }
    }

    data object Cancelled : GoSearchResult
    data object Busy : GoSearchResult
    data object BudgetExhausted : GoSearchResult
}

/** An owned, one-shot request. Implementations must retain native handles during every call. */
interface GoSearchRequest : AutoCloseable {
    /** Synchronous worker call; a second run or a run after request close throws. */
    fun run(): GoSearchResult

    /** Idempotent, non-blocking cancellation signal; does not wait for the search lock or NN. */
    fun cancel()

    /** Cancels, waits for any run to exit, then releases the request. Idempotent; worker only. */
    override fun close()
}

/** A loaded model owner. Loading and close belong on a worker, never on the UI dispatcher. */
interface GoSearchRuntime : AutoCloseable {
    /**
     * Copies the complete MOCG snapshot before returning; caller mutation cannot affect run.
     * Only PLAYING snapshots are searchable. Malformed or unsupported records must be rejected
     * by prepare or run, without modifying the active rules engine. Closed admission throws.
     */
    fun prepare(snapshot: ByteArray, budget: GoSearchBudget): GoSearchRequest

    /** Signals existing requests only. Subsequent prepare calls receive fresh cancellation flags. */
    fun cancelAll()

    /** Closes admission, cancels all requests, joins runs, then releases the network. Idempotent. */
    override fun close()

    companion object {
        const val MAX_SNAPSHOT_BYTES = 1_000_000
        const val MAX_PREPARED_REQUESTS = 16
    }
}
