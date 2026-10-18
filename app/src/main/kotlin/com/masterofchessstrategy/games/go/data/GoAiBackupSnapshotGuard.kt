package com.masterofchessstrategy.games.go.data

import com.masterofchessstrategy.data.PreparedGameBackup
import com.masterofchessstrategy.engine.GameResult
import com.masterofchessstrategy.engine.GoPhase
import com.masterofchessstrategy.games.go.GoAiMatchFileCodec
import com.masterofchessstrategy.games.go.GoAiSavedMatch

/** Checks admitted immutable facts only; never opens Native engines or rewrites first archives. */
internal object GoAiBackupSnapshotGuard {
    fun validate(records: List<PreparedGameBackup>) {
        val active = records.filterIsInstance<PreparedGoAiSessionBackup>()
        val archives = records.filterIsInstance<PreparedGoAiArchiveBackup>()
        require(active.size <= 1) { "Duplicate Go AI active checkpoint" }
        require(archives.map { it.recordId }.distinct().size == archives.size) { "Duplicate Go AI archive identity" }
        val byId = archives.associateBy { it.recordId }
        archives.forEach { archive ->
            require(archive.admitted.phase == GoPhase.FINISHED && archive.admitted.result != GameResult.ONGOING) {
                "Go AI archive is not a confirmed terminal game"
            }
            requireParent(archive.recordId, archive.lineage, byId)
        }
        // Each node has at most one parent. Iterative coloring avoids a history-sized call stack.
        val finished = mutableSetOf<String>()
        byId.keys.forEach { start ->
            val path = mutableSetOf<String>()
            var cursor: String? = start
            while (cursor != null && cursor !in finished) {
                require(path.add(cursor)) { "Cyclic Go AI archive provenance" }
                cursor = byId.getValue(cursor).lineage.replacedTerminalRecordId
            }
            finished.addAll(path)
        }
        active.singleOrNull()?.let { current ->
            val admitted = current.admitted
            requireParent(admitted.recordId, current.lineage, byId)
            val archive = byId[admitted.recordId]
            if (admitted.phase != GoPhase.FINISHED) {
                require(archive == null && admitted.result == GameResult.ONGOING) { "Archived Go AI identity cannot be ongoing" }
            } else {
                require(admitted.result != GameResult.ONGOING)
                val first = requireNotNull(archive) { "Terminal Go AI checkpoint has no first archive" }
                require(first.lineage == current.lineage && first.admitted.result == admitted.result &&
                    first.admitted.moveCount == admitted.moveCount && first.admitted.eventCount == admitted.eventCount &&
                    first.admitted.openingEventCount == admitted.openingEventCount &&
                    terminalContent(first.admitted.match()).contentEquals(terminalContent(admitted.match()))) {
                    "Terminal Go AI checkpoint differs from its first archive"
                }
                val original = first.admitted.match()
                val next = admitted.match()
                require(!original.completionReserved || next.completionReserved) { "Go AI completion reservation cannot rewind" }
                val unchanged = original.autoContinueEnabled == next.autoContinueEnabled &&
                    original.autoContinueMaxGames == next.autoContinueMaxGames &&
                    original.autoContinueGameNumber == next.autoContinueGameNumber
                val ended = original.autoContinueEnabled && !next.autoContinueEnabled &&
                    next.autoContinueMaxGames == 1 && next.autoContinueGameNumber == 1
                require(unchanged || ended) { "Archived Go AI series can only remain unchanged or end" }
            }
        }
    }

    private fun requireParent(id: String, lineage: GoAiCheckpointLineage, archives: Map<String, PreparedGoAiArchiveBackup>) {
        lineage.replacedTerminalRecordId?.let { parent ->
            require(parent != id && parent in archives) { "Go AI continuation lacks a distinct terminal parent" }
        }
    }

    // Same pure definition as GoAiCheckpointRepository; consolidate in a later shared-policy window.
    private fun terminalContent(match: GoAiSavedMatch): ByteArray = GoAiMatchFileCodec.encode(GoAiSavedMatch(
        match.matchId, match.config, match.copyBytes(), match.undoUsed, match.hintUsed, false,
        match.rememberedHuman, match.automaticUsed, match.copyOpeningBytes()))
}
