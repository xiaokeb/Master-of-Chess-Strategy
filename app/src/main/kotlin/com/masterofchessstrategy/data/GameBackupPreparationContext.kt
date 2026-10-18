package com.masterofchessstrategy.data

/** Identity key shared by related adapters, never a process-wide cache of user data. */
internal class GameBackupPreparationKey<T : Any>(internal val create: () -> T)

/** One synchronous prepareRestore invocation owns this cache; no concurrent reuse.
 * Values must be owned admission facts, not live Native engines or database transactions.
 * Prepared records must not retain this context after preparation returns.
 */
internal class GameBackupPreparationContext {
    private val values = mutableMapOf<GameBackupPreparationKey<*>, Any>()

    @Suppress("UNCHECKED_CAST") // A final identity key has exactly one declared value type.
    fun <T : Any> get(key: GameBackupPreparationKey<T>): T {
        values[key]?.let { return it as T }
        return key.create().also { values[key] = it }
    }
}

/** Optional batch-local admission sharing; legacy adapters keep their existing contract. */
internal interface GameBackupContextualPreparation {
    fun prepareRestore(record: GameBackupRecord, context: GameBackupPreparationContext): PreparedGameBackup
}
