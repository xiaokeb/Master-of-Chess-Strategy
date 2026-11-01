package com.masterofchessstrategy.games.competition_mahjong.learning

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

class CompetitionMahjongLearningProofTest {
    @Test
    fun catalogUsesTheExistingFourChaptersAndTwentyNineCards() {
        assertEquals(listOf("tiles", "claims", "fans", "results"), catalog.chapterIds)
        assertEquals(listOf(6, 8, 7, 8), catalog.chapterIds.map { catalog.cardIds(it).size })
        assertEquals(29, catalog.chapterIds.sumOf { catalog.cardIds(it).size })
        assertFalse(CompetitionMahjongLearningProof().isCompleted)
    }

    @Test
    fun onlyTheFourthCommittedChapterCompletesTheCurriculum() {
        var proof = CompetitionMahjongLearningProof()
        repeat(4) { index ->
            proof = proof.confirm(request(index))
            assertEquals(index + 1, proof.completedChapterCount)
            assertEquals(index == 3, proof.isCompleted)
        }
    }

    @Test
    fun partialReorderedForeignAndDuplicateCardSequencesAreRejected() {
        val cards = catalog.cardIds("tiles")
        listOf(cards.dropLast(1), cards.reversed(), cards + "unknown", cards + cards.first()).forEach { wrong ->
            assertThrows(IllegalArgumentException::class.java) {
                CompetitionMahjongLearningProofRequest(uuid(0), "tiles", wrong, 0)
            }
        }
    }

    @Test
    fun unknownChapterBadRequestIdentityAndNegativeTimeAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            CompetitionMahjongLearningProofRequest(uuid(0), "unknown", emptyList(), 0)
        }
        listOf("not-a-uuid", "0-0-0-0-1", "ABCDEF00-0000-0000-0000-000000000001").forEach { candidate ->
            assertThrows(IllegalArgumentException::class.java) {
                CompetitionMahjongLearningProofRequest(candidate, "tiles", catalog.cardIds("tiles"), 0)
            }
        }
        assertThrows(IllegalArgumentException::class.java) { request(0, timestamp = -1) }
    }

    @Test
    fun skippedReorderedDuplicatedAndExtraChapterReceiptsAreRejected() {
        val first = CompetitionMahjongLearningProofReceipt(request(0))
        val second = CompetitionMahjongLearningProofReceipt(request(1))
        listOf(listOf(second), listOf(second, first), listOf(first, first), complete().receipts + first).forEach {
            assertThrows(IllegalArgumentException::class.java) { CompetitionMahjongLearningProof(it) }
        }
        assertThrows(IllegalArgumentException::class.java) { CompetitionMahjongLearningProof().confirm(request(1)) }
    }

    @Test
    fun requestIdentityCannotBeReusedForAnotherChapter() {
        val proof = CompetitionMahjongLearningProof().confirm(request(0))
        assertThrows(IllegalArgumentException::class.java) { proof.confirm(request(1, identity = uuid(0))) }
        assertThrows(IllegalArgumentException::class.java) {
            CompetitionMahjongLearningProof(proof.receipts + CompetitionMahjongLearningProofReceipt(request(1, identity = uuid(0))))
        }
    }

    @Test
    fun acknowledgementLossRetriesTheExactRequestAndOriginalReceipt() {
        val original = request(0, timestamp = 1234)
        val persisted = CompetitionMahjongLearningProof().confirm(original)
        val retry = request(0, timestamp = 1234)
        assertSame(persisted, persisted.confirm(retry))
        assertSame(original, persisted.confirm(retry).receipts.single().request)
        assertEquals(1234L, persisted.receipts.single().request.confirmedAtEpochMillis)
    }

    @Test
    fun aCompletedChapterCannotBeOverwrittenWithANewRequestOrTimestamp() {
        val proof = CompetitionMahjongLearningProof().confirm(request(0, timestamp = 100))
        assertThrows(IllegalArgumentException::class.java) { proof.confirm(request(0, timestamp = 101)) }
        assertThrows(IllegalArgumentException::class.java) { proof.confirm(request(0, identity = uuid(8), timestamp = 100)) }
        assertEquals(100L, proof.receipts.single().request.confirmedAtEpochMillis)
    }

    @Test
    fun proposalsDoNotMutatePreviouslyLoadedProgress() {
        val initial = CompetitionMahjongLearningProof()
        val proposed = initial.confirm(request(0))
        assertEquals(0, initial.completedChapterCount)
        assertEquals(1, proposed.completedChapterCount)
        // A failed transaction keeps initial; the same request still produces the same fact on retry.
        assertArrayEquals(codec.encode(proposed), codec.encode(initial.confirm(request(0))))
    }

    @Test
    fun wallClockRollbackDoesNotInventANewTeachingRequirement() {
        val first = CompetitionMahjongLearningProof().confirm(request(0, timestamp = 1000))
        val second = first.confirm(request(1, timestamp = 1))
        assertEquals(2, second.completedChapterCount)
    }

    @Test
    fun mutableInputsAndExposedCollectionsCannotAlterConfirmedFacts() {
        val cards = catalog.cardIds("tiles").toMutableList()
        val original = CompetitionMahjongLearningProofRequest(uuid(0), "tiles", cards, 0)
        cards.clear()
        assertEquals(6, original.passedCardIds.size)
        val receipts = mutableListOf(CompetitionMahjongLearningProofReceipt(original))
        val proof = CompetitionMahjongLearningProof(receipts)
        receipts.clear()
        assertEquals(1, proof.completedChapterCount)
        assertThrows(UnsupportedOperationException::class.java) { (original.passedCardIds as MutableList).clear() }
        assertThrows(UnsupportedOperationException::class.java) { (proof.receipts as MutableList).clear() }
        assertThrows(UnsupportedOperationException::class.java) { (catalog.chapterIds as MutableList).clear() }
    }

    @Test
    fun emptyPartialAndCompleteSnapshotsRoundTripDeterministically() {
        listOf(CompetitionMahjongLearningProof(), CompetitionMahjongLearningProof().confirm(request(0)), complete()).forEach { proof ->
            val bytes = codec.encode(proof)
            val decoded = codec.decode(bytes)
            assertNotNull(decoded)
            assertArrayEquals(bytes, codec.encode(decoded!!))
            assertEquals(proof.isCompleted, decoded.isCompleted)
            assertEquals(proof.receipts.map { it.request }, decoded.receipts.map { it.request })
            assertTrue(bytes.size in codec.MIN_BYTES..codec.MAX_BYTES)
        }
    }

    @Test
    fun everyTruncationAndTrailingBytesAreRejected() {
        val bytes = codec.encode(complete())
        bytes.indices.forEach { end -> assertNull("Truncated at $end", codec.decode(bytes.copyOf(end))) }
        assertNull(codec.decode(bytes + byteArrayOf(0)))
        assertNull(codec.decode(ByteArray(codec.MAX_BYTES + 1)))
    }

    @Test
    fun unsupportedVersionsWrongDomainReservedAndCountsAreRejected() {
        val original = codec.encode(complete())
        listOf(0 to 0, 4 to 2, 5 to 2, 6 to 5, 6 to 255, 7 to 1).forEach { (offset, value) ->
            assertNull(codec.decode(original.copyOf().also { it[offset] = value.toByte() }))
        }
        assertNull(codec.decode("tutorial_progress:STANDARD_MAHJONG:1:4:true".toByteArray()))
        assertNull(codec.decode("MCTX".toByteArray() + ByteArray(64)))
    }

    @Test
    fun malformedChapterCardIdentitiesLengthsCountsAndTimeAreRejected() {
        val original = codec.encode(CompetitionMahjongLearningProof().confirm(request(0)))
        val chapterLength = original[8].toInt()
        val timeOffset = 9 + chapterLength + 16
        val cardCountOffset = timeOffset + 8
        val firstCardLengthOffset = cardCountOffset + 1
        val badTime = original.copyOf().also { ByteBuffer.wrap(it).putLong(timeOffset, -1) }
        listOf(
            original.copyOf().also { it[8] = 0 },
            original.copyOf().also { it[9] = 'x'.code.toByte() },
            original.copyOf().also { it[9] = 0xff.toByte() },
            original.copyOf().also { it[cardCountOffset] = 127 },
            original.copyOf().also { it[firstCardLengthOffset] = 65 },
            original.copyOf().also { it[firstCardLengthOffset + 1] = 'x'.code.toByte() },
            badTime,
        ).forEach { assertNull(codec.decode(it)) }
    }

    @Test
    fun roundTripRetainsRetryIdentityAndWillNotTurnLegacyCountsIntoProof() {
        val proof = CompetitionMahjongLearningProof().confirm(request(0))
        val restored = codec.decode(codec.encode(proof))!!
        assertSame(restored, restored.confirm(request(0)))
        val read: CompetitionMahjongLearningProofRead = CompetitionMahjongLearningProofRead.Ready(restored)
        assertFalse((read as CompetitionMahjongLearningProofRead.Ready).proof.isCompleted)
        val incompatible: CompetitionMahjongLearningProofRead = CompetitionMahjongLearningProofRead.Incompatible
        assertFalse(incompatible is CompetitionMahjongLearningProofRead.Ready)
    }

    private val catalog get() = CompetitionMahjongLearningProofCatalog
    private val codec get() = CompetitionMahjongLearningProofCodec

    private fun uuid(index: Int) = "00000000-0000-0000-0000-${(index + 1).toString().padStart(12, '0')}"

    private fun request(index: Int, identity: String = uuid(index), timestamp: Long = index.toLong()) =
        CompetitionMahjongLearningProofRequest(identity, catalog.chapterIds[index], catalog.cardIds(catalog.chapterIds[index]), timestamp)

    private fun complete(): CompetitionMahjongLearningProof =
        (0..3).fold(CompetitionMahjongLearningProof()) { proof, index -> proof.confirm(request(index)) }
}
