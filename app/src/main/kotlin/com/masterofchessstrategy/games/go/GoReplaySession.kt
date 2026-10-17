package com.masterofchessstrategy.games.go

import com.masterofchessstrategy.engine.*
import kotlinx.coroutines.CancellationException

data class GoReplayFrame(
    val boardSize: Int,
    val handicapStones: Int,
    val blackKomiQuarters: Int,
    val stones: List<Int>,
    val currentPlayer: GoPlayer,
    val phase: GoPhase,
    val result: GameResult,
    val eventIndex: Int,
    val eventCount: Int,
    val moveIndex: Int,
    val moveCount: Int,
    val lastMoveIndex: Int?,
    val lastEvent: GoAction?,
    val deadStoneIndices: Set<Int>,
    val blackConfirmed: Boolean,
    val whiteConfirmed: Boolean,
    val previewScore: GoAreaScore?,
    val finalScore: GoAreaScore?,
) {
    val canPrevious get() = eventIndex > 0
    val canNext get() = eventIndex < eventCount
}

/** An isolated read-only viewer. Seek/export can block on Native and belong on a worker.
 * Full MOCG retains agreement events; SGF contains only its validated main line.
 * Never writes the active game, Room, growth, or rewards.
 */
interface GoReplayReader : AutoCloseable {
    fun frame(): GoReplayFrame
    fun seek(eventIndex: Int, cancelled: () -> Boolean = { false }): GoReplayFrame
    fun exportSgf(): String
}

class GoReplaySession private constructor(
    accepted: GoRecordEngine,
    private val engineFactory: (Int) -> GoRecordEngine,
) : GoReplayReader {
    private val lock = Any()
    private var engine = accepted
    private val recordBytes = accepted.serialize().copyOf()
    private val events = accepted.eventHistory().toList()
    private val size = accepted.boardSize
    private val moveIndices = IntArray(events.size + 1)
    private val lastMoves = IntArray(events.size + 1) { -1 }
    private var cursor = 0
    private var closed = false

    init {
        require(size in setOf(9, 13, 19) && events.size <= 16_386)
        for ((index, action) in events.withIndex()) {
            moveIndices[index + 1] = moveIndices[index] + if (action is GoAction.Place || action == GoAction.Pass) 1 else 0
            lastMoves[index + 1] = if (action is GoAction.Place) action.point.y * size + action.point.x else lastMoves[index]
        }
        repeat(events.size) { check(accepted.undo()) { "围棋棋谱无法回到初始布点。" } }
    }

    override fun frame(): GoReplayFrame = synchronized(lock) { requireOpen(); snapshot(engine, cursor) }
    fun copyRecordBytes(): ByteArray = synchronized(lock) { requireOpen(); recordBytes.copyOf() }

    /** Build a candidate before swapping. Rejection/cancellation keeps the previous displayed frame. */
    override fun seek(eventIndex: Int, cancelled: () -> Boolean): GoReplayFrame = synchronized(lock) {
        requireOpen()
        require(eventIndex in 0..events.size)
        checkCancelled(cancelled)
        if (eventIndex == cursor) return@synchronized snapshot(engine, cursor)
        val candidate = engineFactory(size)
        var transferred = false
        try {
            check(candidate.restore(recordBytes.copyOf()) == RestoreResult.Restored) { "围棋棋谱恢复失败。" }
            repeat(events.size - eventIndex) {
                checkCancelled(cancelled)
                check(candidate.undo()) { "围棋棋谱事件不完整。" }
            }
            val frame = snapshot(candidate, eventIndex)
            checkCancelled(cancelled)
            val old = engine
            engine = candidate
            cursor = eventIndex
            transferred = true
            old.close()
            frame
        } finally { if (!transferred) candidate.close() }
    }

    /** Export the entire immutable record, independent of the current playback cursor. */
    override fun exportSgf(): String = synchronized(lock) {
        requireOpen()
        engineFactory(size).use { candidate ->
            check(candidate.restore(recordBytes.copyOf()) == RestoreResult.Restored) { "围棋棋谱恢复失败。" }
            candidate.exportSgf()
        }
    }

    override fun close() = synchronized(lock) {
        if (!closed) { closed = true; engine.close() }
    }

    private fun snapshot(source: GoRecordEngine, index: Int): GoReplayFrame {
        val agreement = source.scoringState()
        return GoReplayFrame(
            boardSize = size, handicapStones = source.handicapStones, blackKomiQuarters = source.blackKomiQuarters,
            stones = List(size * size) { source.stoneAt(BoardPosition(it % size, it / size)).code },
            currentPlayer = GoPlayer.entries.first { it.code == source.currentPlayer.value },
            phase = agreement.phase, result = source.gameResult(),
            eventIndex = index, eventCount = events.size, moveIndex = moveIndices[index], moveCount = moveIndices.last(),
            lastMoveIndex = lastMoves[index].takeIf { it >= 0 }, lastEvent = events.getOrNull(index - 1),
            deadStoneIndices = agreement.deadStones.map { it.y * size + it.x }.toSet(),
            blackConfirmed = agreement.blackConfirmed, whiteConfirmed = agreement.whiteConfirmed,
            previewScore = source.previewScore(), finalScore = source.finalScore(),
        )
    }

    private fun requireOpen() { check(!closed) { "Go replay is closed" } }
    private fun checkCancelled(cancelled: () -> Boolean) {
        if (cancelled() || Thread.currentThread().isInterrupted) throw CancellationException("Go replay seek cancelled")
    }

    companion object {
        fun openSnapshot(bytes: ByteArray, engineFactory: (Int) -> GoRecordEngine = { NativeGoEngine(it) }): GoReplaySession {
            val saved = bytes.copyOf()
            require(saved.isNotEmpty() && saved.size <= 1_000_000)
            return open(engineFactory) { it.restore(saved.copyOf()) }
        }

        fun openSgf(text: String, engineFactory: (Int) -> GoRecordEngine = { NativeGoEngine(it) }): GoReplaySession =
            open(engineFactory) { it.restoreSgf(text) }

        private fun open(factory: (Int) -> GoRecordEngine, restore: (GoRecordEngine) -> RestoreResult): GoReplaySession {
            for (size in listOf(9, 13, 19)) {
                val candidate = factory(size)
                var transferred = false
                try {
                    if (restore(candidate) == RestoreResult.Restored) {
                        val session = GoReplaySession(candidate, factory)
                        transferred = true
                        return session
                    }
                } finally { if (!transferred) candidate.close() }
            }
            throw IllegalArgumentException("围棋棋谱数据无效或不受支持。")
        }
    }
}
