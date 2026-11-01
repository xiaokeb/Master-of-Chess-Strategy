package com.masterofchessstrategy.games.competition_mahjong.learning

import java.util.Collections
import java.util.UUID

/** Dedicated curriculum identity; shared tutorial counts are never a source of confirmations. */
internal object CompetitionMahjongLearningProofCatalog {
    const val CONTENT_VERSION = 1

    val chapterIds: List<String> = immutable(CompetitionMahjongLearningLibrary.chapters.map { it.id })
    private val cards = CompetitionMahjongLearningLibrary.chapters.associate { chapter ->
        chapter.id to immutable(chapter.cards.map { it.id })
    }

    init {
        check(CompetitionMahjongLearningLibrary.CONTENT_VERSION == CONTENT_VERSION)
        check(chapterIds.size == CompetitionMahjongLearningLibrary.CHAPTER_COUNT)
    }

    fun cardIds(chapterId: String): List<String> = requireNotNull(cards[chapterId]) { "Unknown chapter" }
}

/** Created after the existing controller has passed every card and explicitly advanced past the chapter. */
internal class CompetitionMahjongLearningProofRequest(
    val requestId: String,
    val chapterId: String,
    passedCardIds: List<String>,
    val confirmedAtEpochMillis: Long,
) {
    val passedCardIds: List<String> = immutable(passedCardIds)

    init {
        require(UUID.fromString(requestId).toString() == requestId) { "Canonical UUID required" }
        require(confirmedAtEpochMillis >= 0)
        require(this.passedCardIds == CompetitionMahjongLearningProofCatalog.cardIds(chapterId)) {
            "The complete chapter card sequence is required"
        }
    }

    override fun equals(other: Any?): Boolean = other is CompetitionMahjongLearningProofRequest &&
        requestId == other.requestId && chapterId == other.chapterId &&
        passedCardIds == other.passedCardIds && confirmedAtEpochMillis == other.confirmedAtEpochMillis

    override fun hashCode(): Int = listOf(requestId, chapterId, passedCardIds, confirmedAtEpochMillis).hashCode()
}

/** A persisted chapter fact, not an authentication or anti-tampering credential. */
internal data class CompetitionMahjongLearningProofReceipt(val request: CompetitionMahjongLearningProofRequest)

internal class CompetitionMahjongLearningProof(
    receipts: List<CompetitionMahjongLearningProofReceipt> = emptyList(),
) {
    val receipts: List<CompetitionMahjongLearningProofReceipt> = immutable(receipts)
    val completedChapterCount: Int get() = receipts.size
    val isCompleted: Boolean get() = completedChapterCount == CompetitionMahjongLearningProofCatalog.chapterIds.size

    init {
        val chapters = this.receipts.map { it.request.chapterId }
        require(chapters == CompetitionMahjongLearningProofCatalog.chapterIds.take(chapters.size)) {
            "Confirmed chapters must be an ordered curriculum prefix"
        }
        require(this.receipts.map { it.request.requestId }.distinct().size == this.receipts.size) {
            "A request identity cannot confirm multiple chapters"
        }
    }

    /** Pure proposal; callers must commit it transactionally before exposing completion. */
    fun confirm(request: CompetitionMahjongLearningProofRequest): CompetitionMahjongLearningProof {
        val existing = receipts.firstOrNull { it.request.chapterId == request.chapterId }
        if (existing != null) {
            require(existing.request == request) { "A completed chapter cannot be replaced" }
            return this // A lost acknowledgement retries the original request and receipt.
        }
        require(receipts.none { it.request.requestId == request.requestId }) { "Request identity already used" }
        require(CompetitionMahjongLearningProofCatalog.chapterIds.getOrNull(receipts.size) == request.chapterId) {
            "Only the next chapter can be confirmed"
        }
        return CompetitionMahjongLearningProof(receipts + CompetitionMahjongLearningProofReceipt(request))
    }
}

internal sealed interface CompetitionMahjongLearningProofRead {
    /** A missing dedicated row is Ready(empty); unrelated legacy rows have no effect. */
    data class Ready(val proof: CompetitionMahjongLearningProof) : CompetitionMahjongLearningProofRead

    /** Preserve unreadable or unsupported data; do not silently replace it with an empty proof. */
    data object Incompatible : CompetitionMahjongLearningProofRead
}

/** Future dedicated persistence contract; no database schema or implementation is introduced here. */
internal interface CompetitionMahjongLearningProofStore {
    suspend fun load(): CompetitionMahjongLearningProofRead

    /** Re-read, confirm, and persist atomically; failures retain the same caller request for retry. */
    suspend fun confirm(request: CompetitionMahjongLearningProofRequest): CompetitionMahjongLearningProof
}

private fun <T> immutable(values: List<T>): List<T> = Collections.unmodifiableList(ArrayList(values))
