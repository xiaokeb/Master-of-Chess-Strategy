package com.masterofchessstrategy.games.go.data

import com.masterofchessstrategy.engine.GoPhase
import com.masterofchessstrategy.games.go.GoAiMatchFileCodec
import com.masterofchessstrategy.games.go.GoAiMatchRepository
import com.masterofchessstrategy.games.go.GoAiSavedMatch
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Transactional AI checkpoint/source prerequisite; intentionally has no reward or record-list writer. */
internal class GoAiCheckpointRepository(
    private val store: GoAiCheckpointStore,
    private val legacy: GoAiLegacySource? = null,
    private val admit: (GoAiSavedMatch) -> GoAdmittedAiCheckpoint = ::admitGoAiCheckpoint,
    private val worker: CoroutineDispatcher = Dispatchers.Default,
    private val clock: () -> Long = System::currentTimeMillis,
) : GoAiMatchRepository {
    override suspend fun load(): GoAiSavedMatch? = store.transaction { current()?.first?.match() }

    override suspend fun save(match: GoAiSavedMatch) {
        val next = prepare(match)
        store.transaction {
            val previous = current()
            val lineage = if (previous?.first?.match()?.matchId == next.match().matchId) {
                validateSameMatch(previous.first.match(), next.match())
                if (previous.first.phase == GoPhase.FINISHED)
                    validateTerminalControls(previous.first.match(), next.match())
                previous.second
            } else if (previous?.first?.phase == GoPhase.FINISHED && next.eventCount > next.openingEventCount) {
                // Conservative replacement context, not proof that the new history is a parent prefix.
                GoAiCheckpointLineage(GoAiCheckpointOrigin.TERMINAL_CONTINUATION, previous.first.recordId)
            } else GoAiCheckpointLineage(GoAiCheckpointOrigin.UNVERIFIED_SESSION)
            commit(next, lineage)
        }
    }

    suspend fun clear() = store.transaction {
        clearActive()
        markLegacyHandled() // Never resurrect an old file after clear/restore.
    }

    private suspend fun prepare(match: GoAiSavedMatch) = withContext(worker) { admit(match) }

    private suspend fun GoAiCheckpointTransaction.current(): Pair<GoAdmittedAiCheckpoint, GoAiCheckpointLineage>? {
        readActive()?.let { entry ->
            val admitted = prepare(decode(entry))
            validateArchive(admitted, entry.lineage, allowInsert = false)
            return admitted to entry.lineage
        }
        if (legacyHandled()) return null
        val old = legacy?.readCommitted()
        if (old == null) { markLegacyHandled(); return null }
        val admitted = prepare(old)
        val lineage = GoAiCheckpointLineage(GoAiCheckpointOrigin.LEGACY_UNVERIFIED)
        commit(admitted, lineage)
        return admitted to lineage
    }

    private suspend fun GoAiCheckpointTransaction.commit(admitted: GoAdmittedAiCheckpoint, lineage: GoAiCheckpointLineage) {
        validateArchive(admitted, lineage, allowInsert = true)
        val now = clock().also { require(it >= 0) }
        writeActive(GoAiCheckpointEntry(GoAiMatchFileCodec.encode(admitted.match()), lineage), now)
        markLegacyHandled()
    }

    private suspend fun GoAiCheckpointTransaction.validateArchive(
        admitted: GoAdmittedAiCheckpoint,
        lineage: GoAiCheckpointLineage,
        allowInsert: Boolean,
    ) {
        lineage.replacedTerminalRecordId?.let { parentId ->
            require(parentId != admitted.recordId) { "围棋人机来源不能以自身为父谱" }
            val parent = requireNotNull(findArchive(parentId)) { "围棋人机延续来源缺少父终局" }
            val parentGame = prepare(decode(parent.checkpoint))
            require(parent.recordId == parentId && parentGame.recordId == parentId &&
                parentGame.phase == GoPhase.FINISHED &&
                parent.checkpoint.lineage.replacedTerminalRecordId != admitted.recordId) {
                "围棋人机延续来源的父终局无效"
            }
        }
        val archived = findArchive(admitted.recordId)
        if (admitted.phase != GoPhase.FINISHED) {
            require(archived == null) { "已归档的围棋人机身份不能改回进行中" }
            return
        }
        if (archived == null) {
            require(allowInsert) { "围棋人机终局缺少对应归档" }
            val now = clock().also { require(it >= 0) }
            insertArchive(GoAiCheckpointArchive(admitted.recordId,
                GoAiCheckpointEntry(GoAiMatchFileCodec.encode(admitted.match()), lineage), now))
        } else {
            val previous = prepare(decode(archived.checkpoint))
            validateTerminalControls(previous.match(), admitted.match())
            require(previous.recordId == archived.recordId && previous.phase == GoPhase.FINISHED &&
                lineage == archived.checkpoint.lineage &&
                terminalContent(previous.match()).contentEquals(terminalContent(admitted.match()))) {
                "围棋人机终局身份或历史冲突"
            }
        }
    }

    private fun decode(entry: GoAiCheckpointEntry): GoAiSavedMatch {
        val bytes = entry.copyBytes()
        require(bytes.size > 4 && bytes[4].toInt() == 6) { "围棋人机事务快照版本无效" }
        return requireNotNull(GoAiMatchFileCodec.decode(bytes)) { "围棋人机事务快照损坏" }
    }

    private fun validateSameMatch(previous: GoAiSavedMatch, next: GoAiSavedMatch) {
        require(previous.config.boardSize == next.config.boardSize &&
            previous.config.handicapStones == next.config.handicapStones &&
            previous.config.difficulty == next.config.difficulty &&
            previous.copyOpeningBytes().sameBytes(next.copyOpeningBytes()) &&
            (!previous.automaticUsed || next.automaticUsed) &&
            (!previous.completionReserved || next.completionReserved) &&
            next.undoUsed >= previous.undoUsed && next.hintUsed >= previous.hintUsed) {
            "围棋人机身份不能改变开局或回退辅助与来源标记"
        }
    }

    private fun validateTerminalControls(previous: GoAiSavedMatch, next: GoAiSavedMatch) {
        val unchanged = previous.autoContinueEnabled == next.autoContinueEnabled &&
            previous.autoContinueMaxGames == next.autoContinueMaxGames &&
            previous.autoContinueGameNumber == next.autoContinueGameNumber
        val ended = previous.autoContinueEnabled && !next.autoContinueEnabled &&
            next.autoContinueMaxGames == 1 && next.autoContinueGameNumber == 1
        require(unchanged || ended) { "已归档围棋终局只能结束原系列，不能重开或改写系列" }
    }

    // Reserving an existing result or ending its continuation series does not rewrite the first archive.
    private fun terminalContent(match: GoAiSavedMatch): ByteArray = GoAiMatchFileCodec.encode(
        GoAiSavedMatch(match.matchId, match.config, match.copyBytes(), match.undoUsed, match.hintUsed,
            false, match.rememberedHuman, match.automaticUsed, match.copyOpeningBytes()))

    private fun ByteArray?.sameBytes(other: ByteArray?) =
        if (this == null) other == null else other != null && contentEquals(other)
}
