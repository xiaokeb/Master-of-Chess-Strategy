package com.masterofchessstrategy.engine

/** Optional record capability; existing rule-only sessions need not implement it. */
interface GoRecordEngine : GoRuleEngine {
    /** SGF contains setup and moves, not death agreement, final results or rewards. */
    fun exportSgf(): String

    /**
     * Atomically replaces this fixed-size session; rejection leaves it unchanged.
     * Import into a fresh candidate, persist it, then replace the active session.
     * Pending UI actions from the previous session must not be reused.
     */
    fun restoreSgf(text: String): RestoreResult

    /** Full accepted events, including agreement; undo removes the last event. */
    fun eventHistory(): List<GoAction>
}

sealed interface GoSgfImportResult {
    /** Caller owns the candidate and must close it if persistence fails. */
    data class Imported(val engine: GoRecordEngine) : GoSgfImportResult
    data class Rejected(val error: EngineError) : GoSgfImportResult
}
