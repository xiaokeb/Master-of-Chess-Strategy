package com.masterofchessstrategy.games.go.data

/** None of these migration/storage facts establishes eligibility for ranked rewards. */
internal enum class GoAiCheckpointOrigin { LEGACY_UNVERIFIED, UNVERIFIED_SESSION, TERMINAL_CONTINUATION }

internal data class GoAiCheckpointLineage(
    val origin: GoAiCheckpointOrigin,
    val replacedTerminalRecordId: String? = null,
) {
    init {
        require((origin == GoAiCheckpointOrigin.TERMINAL_CONTINUATION) == (replacedTerminalRecordId != null))
        require(replacedTerminalRecordId == null || replacedTerminalRecordId.matches(Regex("[0-9a-f]{64}")))
    }
}

/** Complete canonical GAI envelope, not just the board; no mutable array escapes storage. */
internal class GoAiCheckpointEntry(bytes: ByteArray, val lineage: GoAiCheckpointLineage) {
    private val owned = bytes.copyOf()
    fun copyBytes() = owned.copyOf()
}

/** Lossless terminal source. The shared record-list projection and ledger are a later transaction step. */
internal class GoAiCheckpointArchive(
    val recordId: String,
    val checkpoint: GoAiCheckpointEntry,
    val completedAtEpochMillis: Long,
) {
    init { require(recordId.matches(Regex("[0-9a-f]{64}")) && completedAtEpochMillis >= 0) }
}

/** Every write, including the permanent legacy seal, must roll back on failure or cancellation. */
internal interface GoAiCheckpointStore {
    suspend fun <T> transaction(block: suspend GoAiCheckpointTransaction.() -> T): T
}

internal interface GoAiCheckpointTransaction {
    suspend fun readActive(): GoAiCheckpointEntry?
    suspend fun writeActive(entry: GoAiCheckpointEntry, updatedAtEpochMillis: Long)
    suspend fun clearActive()
    suspend fun legacyHandled(): Boolean
    suspend fun markLegacyHandled()
    suspend fun findArchive(recordId: String): GoAiCheckpointArchive?
    suspend fun insertArchive(archive: GoAiCheckpointArchive)
}
