package com.masterofchessstrategy.engine.games.tiger_goat

import java.util.concurrent.atomic.AtomicBoolean

/** Search budgets, not calibrated difficulty or a hard real-time guarantee. */
data class TigerGoatSearchLimits(
    val turnDepth: Int = 3,
    val nodeLimit: Long = 10_000,
    val timeLimitMillis: Long = 1_500,
) {
    init {
        require(turnDepth in 1..16) { "Tiger-goat turn depth must be in 1..16" }
        require(nodeLimit >= 0) { "Tiger-goat node limit must not be negative" }
        require(timeLimitMillis in 0..60_000) { "Tiger-goat time limit must be in 0..60000 ms" }
    }
}

enum class TigerGoatSearchStop(val code: Int) {
    DEPTH_COMPLETED(0),
    NO_LEGAL_ACTION(1),
    NODE_LIMIT(2),
    TIME_LIMIT(3),
    CANCELLED(4),
    INVALID_LIMITS(5),
    RULE_REJECTED(6),
}

/** The bridge may only read [cancelled]; the request owns that flag and its lifetime. */
fun interface TigerGoatSearchBridge {
    fun search(
        snapshot: ByteArray,
        turnDepth: Int,
        nodeLimit: Long,
        timeLimitMillis: Long,
        cancelled: AtomicBoolean,
    ): LongArray
}

/**
 * A revocable suggestion, not authority to mutate a live game. After request cancellation,
 * stop/action/score report CANCELLED/null/null, including results already returned to a caller.
 * An action previously read by a caller cannot be recalled: its owner must recheck cancellation,
 * session identity, data epoch, generation and exact source bytes immediately before applying it.
 */
class TigerGoatSearchResult internal constructor(
    sourceSnapshot: ByteArray,
    private val nativeStop: TigerGoatSearchStop,
    private val nativeAction: TigerGoatAction?,
    private val nativeScore: Int?,
    val completedTurnDepth: Int,
    val visitedNodes: Long,
    val elapsedMillis: Long,
    private val cancelled: AtomicBoolean,
) {
    private val source = sourceSnapshot.copyOf()
    val sourceSnapshot: ByteArray get() = source.copyOf()
    val stop: TigerGoatSearchStop
        get() = if (cancelled.get()) TigerGoatSearchStop.CANCELLED else nativeStop
    val action: TigerGoatAction?
        get() = if (stop == TigerGoatSearchStop.CANCELLED) null else nativeAction
    val score: Int?
        get() = if (stop == TigerGoatSearchStop.CANCELLED) null else nativeScore
}

/**
 * Owns an immutable MOCT copy and one cancellation flag, never a live Native session handle.
 * executeBlocking must run on a worker. cancel is non-blocking and does not release or join JNI;
 * the owner must wait for that worker before declaring its lifecycle/backup drain complete.
 */
class TigerGoatSearchRequest private constructor(
    private val source: ByteArray,
    val limits: TigerGoatSearchLimits,
    private val bridge: TigerGoatSearchBridge?,
) {
    private val executed = AtomicBoolean(false)
    private val cancelled = AtomicBoolean(false)
    val sourceSnapshot: ByteArray get() = source.copyOf()
    val isCancellationRequested: Boolean get() = cancelled.get()

    fun cancel() { cancelled.set(true) }

    /** Single use, including cancellation before execution and bridge failures. */
    fun executeBlocking(): TigerGoatSearchResult {
        check(executed.compareAndSet(false, true)) { "Tiger-goat search request was already executed" }
        if (cancelled.get()) return result(TigerGoatSearchStop.CANCELLED)
        // Resolve the production bridge lazily: prepare/cancel never needs to load JNI.
        val fields = (bridge ?: TigerGoatSearchBindings).search(
            source.copyOf(), limits.turnDepth, limits.nodeLimit, limits.timeLimitMillis, cancelled,
        ).copyOf()
        return decode(fields)
    }

    private fun result(
        stop: TigerGoatSearchStop,
        action: TigerGoatAction? = null,
        score: Int? = null,
        depth: Int = 0,
        nodes: Long = 0,
        elapsed: Long = 0,
    ) = TigerGoatSearchResult(source, stop, action, score, depth, nodes, elapsed, cancelled)

    private fun decode(fields: LongArray): TigerGoatSearchResult {
        check(fields.size == 11 && fields[0] == 1L) { "Invalid tiger-goat search frame" }
        val stop = TigerGoatSearchStop.entries.firstOrNull { it.code.toLong() == fields[1] }
            ?: error("Unknown tiger-goat search stop code")
        check(fields[2] in 0L..1L && fields[6] in 0L..1L) { "Invalid search presence flags" }
        check(fields[8] in 0L..limits.turnDepth.toLong() && fields[9] in 0L..limits.nodeLimit &&
            fields[10] >= 0
        ) { "Invalid tiger-goat search metrics" }
        val depth = fields[8].toInt()
        val action = if (fields[2] == 0L) {
            check(fields[3] == -1L && fields[4] == -1L && fields[5] == -1L) {
                "Unexpected absent search action payload"
            }
            null
        } else {
            val kind = TigerGoatActionKind.entries.firstOrNull { it.code.toLong() == fields[3] }
                ?: error("Unknown search action kind")
            check(kind != TigerGoatActionKind.RESIGN && fields[4] in -1L..36L &&
                fields[5] in -1L..36L
            ) { "Invalid search action points" }
            TigerGoatAction(kind, fields[4].toInt(), fields[5].toInt()).also(TigerGoatWire::validateAction)
        }
        val score = if (fields[6] == 0L) {
            check(fields[7] == 0L) { "Unexpected absent search score payload" }
            null
        } else {
            check(fields[7] in -100_000L..100_000L && depth > 0 && action != null) {
                "Invalid tiger-goat search score"
            }
            fields[7].toInt()
        }
        when (stop) {
            TigerGoatSearchStop.DEPTH_COMPLETED -> check(
                depth == limits.turnDepth && action != null && score != null,
            ) { "Incomplete search reported as depth completed" }
            TigerGoatSearchStop.NODE_LIMIT, TigerGoatSearchStop.TIME_LIMIT -> check(
                if (depth == 0) score == null else action != null && score != null,
            ) { "Invalid interrupted search iteration" }
            TigerGoatSearchStop.NO_LEGAL_ACTION, TigerGoatSearchStop.CANCELLED,
            TigerGoatSearchStop.INVALID_LIMITS, TigerGoatSearchStop.RULE_REJECTED -> check(
                action == null && score == null,
            ) { "Failed search must not expose a suggestion" }
        }
        return result(stop, action, score, depth, fields[9], fields[10])
    }

    companion object {
        /** Shape checks only; actual Native replay remains the legality/security boundary. */
        fun prepare(
            snapshot: ByteArray,
            limits: TigerGoatSearchLimits = TigerGoatSearchLimits(),
            bridge: TigerGoatSearchBridge? = null,
        ): TigerGoatSearchRequest {
            require(snapshot.size in 53..196_658) { "Invalid tiger-goat snapshot size" }
            val owned = snapshot.copyOf()
            require(owned[0] == 77.toByte() && owned[1] == 79.toByte() &&
                owned[2] == 67.toByte() && owned[3] == 84.toByte() &&
                owned[4].toInt() in 1..2 && owned[5] == 1.toByte()
            ) { "Invalid MOCT snapshot header" }
            val count = (owned[47].toInt() and 255) or ((owned[48].toInt() and 255) shl 8)
            require(owned.size == 53 + count * 3) { "Invalid MOCT snapshot action count" }
            return TigerGoatSearchRequest(owned, limits, bridge)
        }
    }
}
