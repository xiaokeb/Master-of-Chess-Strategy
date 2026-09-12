package com.masterofchessstrategy.engine

/**
 * Game-independent contract implemented by native-backed rule engines.
 *
 * Implementations own any native resources and callers must invoke [close] when the
 * session ends. Serialized bytes are copied across the boundary and remain caller-owned.
 */
interface RuleEngine<A : GameAction> : AutoCloseable {
    val gameType: GameType
    val currentPlayer: PlayerId

    fun reset()

    fun apply(action: A): ActionResult

    fun undo(): Boolean

    fun legalActions(): List<A>

    fun gameResult(): GameResult

    fun serialize(): ByteArray

    fun restore(data: ByteArray): RestoreResult
}
