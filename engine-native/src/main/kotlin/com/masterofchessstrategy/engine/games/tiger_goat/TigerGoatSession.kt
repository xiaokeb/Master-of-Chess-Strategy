package com.masterofchessstrategy.engine.games.tiger_goat

enum class TigerGoatPhase { OPENING_REMOVAL, PLAY, CAPTURE_CHAIN, FINISHED }
enum class TigerGoatOutcome { ONGOING, TIGER_WINS, GOAT_WINS }
enum class TigerGoatActionKind(val code: Int) {
    REMOVE_GOAT(0), PLACE_GOAT(1), STEP(2), CAPTURE(3), END_CHAIN(4), RESIGN(5), PASS_GOAT(6),
}

data class TigerGoatAction(val kind: TigerGoatActionKind, val from: Int = -1, val to: Int = -1)
data class TigerGoatPoint(val x: Int, val y: Int)
data class TigerGoatEdge(val from: Int, val to: Int)
data class TigerGoatGeometry(val points: List<TigerGoatPoint>, val edges: List<TigerGoatEdge>)
data class TigerGoatSnapshot(
    val board: List<Int>,
    val phase: TigerGoatPhase,
    val side: Int,
    val reserve: Int,
    val captured: Int,
    val outcome: TigerGoatOutcome,
    val chainTiger: Int,
    val actionCount: Int,
    val visited: Set<Int>,
)

interface TigerGoatSession : AutoCloseable {
    val geometry: TigerGoatGeometry
    fun snapshot(): TigerGoatSnapshot
    fun legalActions(): List<TigerGoatAction>
    fun apply(action: TigerGoatAction): Int
    fun undo(): Boolean
    fun reset()
    fun serialize(): ByteArray
    fun restore(bytes: ByteArray): Boolean
}

/** Integration supplies the JNI implementation after Registry/CMake coordination. */
interface TigerGoatBridge {
    fun create(): Long
    fun release(handle: Long)
    fun geometry(): IntArray
    fun snapshot(handle: Long): IntArray
    fun legalActions(handle: Long): IntArray
    fun apply(handle: Long, kind: Int, from: Int, to: Int): Int
    fun undo(handle: Long): Boolean
    fun reset(handle: Long)
    fun serialize(handle: Long): ByteArray
    fun restore(handle: Long, bytes: ByteArray): Boolean
}
