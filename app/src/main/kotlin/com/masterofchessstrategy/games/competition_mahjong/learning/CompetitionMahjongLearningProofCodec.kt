package com.masterofchessstrategy.games.competition_mahjong.learning

import java.nio.BufferUnderflowException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

/** Bounded MCLP v1 data. Versions are independent of shared TutorialProgress and public database schema. */
internal object CompetitionMahjongLearningProofCodec {
    private const val MAGIC = 0x4d434c50 // MCLP
    const val FORMAT_VERSION = 1
    const val MIN_BYTES = 8
    const val MAX_BYTES = 4096

    fun encode(proof: CompetitionMahjongLearningProof): ByteArray {
        val output = ByteBuffer.allocate(MAX_BYTES).order(ByteOrder.BIG_ENDIAN)
        output.putInt(MAGIC).put(FORMAT_VERSION.toByte())
            .put(CompetitionMahjongLearningProofCatalog.CONTENT_VERSION.toByte())
            .put(proof.completedChapterCount.toByte()).put(0)
        proof.receipts.forEach { receipt ->
            val request = receipt.request
            putId(output, request.chapterId)
            val uuid = UUID.fromString(request.requestId)
            output.putLong(uuid.mostSignificantBits).putLong(uuid.leastSignificantBits)
                .putLong(request.confirmedAtEpochMillis).put(request.passedCardIds.size.toByte())
            request.passedCardIds.forEach { putId(output, it) }
        }
        return output.array().copyOf(output.position())
    }

    /** Null means incompatible; the persistence adapter must preserve the original row. */
    fun decode(bytes: ByteArray): CompetitionMahjongLearningProof? {
        if (bytes.size !in MIN_BYTES..MAX_BYTES) return null
        val input = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        if (input.int != MAGIC || input.get().toInt() != FORMAT_VERSION ||
            input.get().toInt() != CompetitionMahjongLearningProofCatalog.CONTENT_VERSION) return null
        val count = input.get().toInt()
        if (count !in 0..CompetitionMahjongLearningProofCatalog.chapterIds.size || input.get().toInt() != 0) return null
        return try {
            val receipts = List(count) {
                val chapterId = readId(input)
                val requestId = UUID(input.long, input.long).toString()
                val timestamp = input.long
                val cardCount = input.get().toInt()
                require(cardCount == CompetitionMahjongLearningProofCatalog.cardIds(chapterId).size)
                val cardIds = List(cardCount) { readId(input) }
                CompetitionMahjongLearningProofReceipt(
                    CompetitionMahjongLearningProofRequest(requestId, chapterId, cardIds, timestamp),
                )
            }
            CompetitionMahjongLearningProof(receipts).takeIf { !input.hasRemaining() }
        } catch (_: IllegalArgumentException) {
            null
        } catch (_: BufferUnderflowException) {
            null
        }
    }

    private fun putId(output: ByteBuffer, id: String) {
        val bytes = id.toByteArray(Charsets.US_ASCII)
        require(bytes.size in 1..64 && bytes.toString(Charsets.US_ASCII) == id)
        output.put(bytes.size.toByte()).put(bytes)
    }

    private fun readId(input: ByteBuffer): String {
        val length = input.get().toInt()
        require(length in 1..64 && input.remaining() >= length)
        val bytes = ByteArray(length).also(input::get)
        require(bytes.all { it.toInt() in 0x21..0x7e })
        return bytes.toString(Charsets.US_ASCII)
    }
}
