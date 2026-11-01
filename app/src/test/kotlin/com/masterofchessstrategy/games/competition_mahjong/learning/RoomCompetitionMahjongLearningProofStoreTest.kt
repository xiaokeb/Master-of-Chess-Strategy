package com.masterofchessstrategy.games.competition_mahjong.learning

import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Real Room types, injected transactional DAO double; SQLite and generated SQL remain integration work. */
class RoomCompetitionMahjongLearningProofStoreTest {
    @Test fun missingRowIsReadyEmptyWithoutCreatingOrReadingAPayload() = runBlocking {
        val db = MemoryDatabase()
        assertEquals(0, ready(db.store().load()).completedChapterCount)
        assertEquals(0, db.payloadReads)
        assertEquals(0, db.writes)
        assertTrue(db.rows.isEmpty())
    }

    @Test fun completeProofLoadsInsideOneBoundedTransaction() = runBlocking {
        val db = MemoryDatabase()
        val proof = (0..3).fold(CompetitionMahjongLearningProof()) { current, index -> current.confirm(request(index)) }
        db.rows[0] = codec.encode(proof)
        assertTrue(ready(db.store().load()).isCompleted)
        assertEquals(1, db.transactions)
        assertEquals(1, db.payloadReads)
        assertEquals(0, db.writes)
    }

    @Test fun unknownKeysAndMultipleRowsArePreservedWithoutLoadingPayloads() = runBlocking {
        listOf(mapOf(7 to emptyBytes()), mapOf(0 to emptyBytes(), 1 to emptyBytes())).forEach { rows ->
            val db = MemoryDatabase().apply { this.rows.putAll(rows) }
            assertSame(CompetitionMahjongLearningProofRead.Incompatible, db.store().load())
            expectIo { db.store().confirm(request(0)) }
            assertEquals(rows.keys, db.rows.keys)
            rows.forEach { (key, bytes) -> assertArrayEquals(bytes, db.rows[key]) }
            assertEquals(0, db.payloadReads)
            assertEquals(0, db.writes)
        }
    }

    @Test fun metadataOutsidePayloadLimitsIsRejectedBeforeBlobRead() = runBlocking {
        listOf(-1L, 0L, 7L, 4097L, Long.MAX_VALUE).forEach { size ->
            val db = MemoryDatabase().apply {
                rows[0] = emptyBytes()
                metadataOverride = listOf(CompetitionMahjongLearningProofMetadata(0, size))
            }
            assertSame(CompetitionMahjongLearningProofRead.Incompatible, db.store().load())
            expectIo { db.store().confirm(request(0)) }
            assertEquals(0, db.payloadReads)
            assertEquals(0, db.writes)
        }
    }

    @Test fun missingMismatchedMalformedAndUnsupportedPayloadsAreNotOverwritten() = runBlocking {
        val bytes = emptyBytes()
        val badVersion = bytes.copyOf().also { it[4] = 2 }
        listOf<ByteArray?>(null, bytes + byteArrayOf(0), ByteArray(8), badVersion).forEach { payload ->
            val db = MemoryDatabase().apply {
                rows[0] = bytes.copyOf()
                payloadReader = { payload }
            }
            assertSame(CompetitionMahjongLearningProofRead.Incompatible, db.store().load())
            expectIo { db.store().confirm(request(0)) }
            assertArrayEquals(bytes, db.rows[0])
            assertEquals(0, db.writes)
        }
    }

    @Test fun daoFailureIsAnErrorRatherThanAnEmptyProgressResult() = runBlocking {
        val db = MemoryDatabase().apply { failRead = true }
        expectIo { db.store().load() }
        expectIo { db.store().confirm(request(0)) }
        assertTrue(db.rows.isEmpty())
    }

    @Test fun confirmationReReadsCurrentFactsInsteadOfUsingAnEarlierLoad() = runBlocking {
        val db = MemoryDatabase()
        val store = db.store()
        assertEquals(0, ready(store.load()).completedChapterCount)
        db.store().confirm(request(0))
        val result = store.confirm(request(1))
        assertEquals(listOf(request(0), request(1)), result.receipts.map { it.request })
        assertEquals(2, ready(store.load()).completedChapterCount)
        assertEquals(2, db.writes)
    }

    @Test fun exactRetriesKeepTheOriginalRequestAndDoNotWriteAgain() = runBlocking {
        val db = MemoryDatabase()
        val first = db.store().confirm(request(0))
        val bytes = checkNotNull(db.rows[0]).copyOf()
        val retry = db.store().confirm(request(0))
        assertEquals(first.receipts.single().request, retry.receipts.single().request)
        assertArrayEquals(bytes, db.rows[0])
        assertEquals(1, db.writes)
    }

    @Test fun changedRequestsAndSkippedChaptersFailWithoutChangingTheStoredFacts() = runBlocking {
        val db = MemoryDatabase()
        expectIo { db.store().confirm(request(1)) }
        assertTrue(db.rows.isEmpty())
        db.store().confirm(request(0))
        val bytes = checkNotNull(db.rows[0]).copyOf()
        expectIo { db.store().confirm(request(0, timestamp = 2)) }
        expectIo { db.store().confirm(request(0, identity = uuid(9))) }
        assertArrayEquals(bytes, db.rows[0])
        assertEquals(1, db.writes)
    }

    @Test fun commitFailureRollsBackAndTheOriginalRequestCanBeRetried() = runBlocking {
        val db = MemoryDatabase().apply { failBeforeCommit = true }
        val pending = request(0)
        expectIo { db.store().confirm(pending) }
        assertTrue(db.rows.isEmpty())
        assertEquals(pending, db.store().confirm(pending).receipts.single().request)
    }

    @Test fun silentlyDroppedOrChangedWritesFailReadBackAndRollBack() = runBlocking {
        listOf(false, true).forEach { change ->
            val db = MemoryDatabase().apply {
                if (change) replacementOnWrite = codec.encode(CompetitionMahjongLearningProof().confirm(request(0, timestamp = 99)))
                else dropWrites = true
            }
            expectIo { db.store().confirm(request(0)) }
            assertTrue(db.rows.isEmpty())
        }
    }

    @Test fun acknowledgementLossAfterCommitRetriesWithoutDuplicatingFacts() = runBlocking {
        val db = MemoryDatabase().apply { failAfterCommit = true }
        val pending = request(0)
        expectIo { db.store().confirm(pending) }
        assertEquals(1, codec.decode(checkNotNull(db.rows[0]))!!.completedChapterCount)
        val retry = db.store().confirm(pending)
        assertEquals(pending, retry.receipts.single().request)
        assertEquals(1, db.writes)
    }

    @Test fun cancellationDuringAWriteRollsBackBeforePublishingCompletion() = runBlocking {
        val db = MemoryDatabase().apply { cancelAfterWrite = true }
        val operation = launch { db.store().confirm(request(0)) }
        operation.join()
        assertTrue(operation.isCancelled)
        assertTrue(db.rows.isEmpty())
        db.cancelAfterWrite = false
        assertEquals(1, db.store().confirm(request(0)).completedChapterCount)
    }

    @Test fun concurrentIdenticalRequestsCommitOnceAndConflictingRequestsDoNotReplaceIt() = runBlocking {
        val db = MemoryDatabase()
        val results = List(2) { async(Dispatchers.Default) { db.store().confirm(request(0)) } }.map { it.await() }
        assertTrue(results.all { it.completedChapterCount == 1 })
        assertEquals(1, db.writes)
        val attempts = List(2) { index -> async(Dispatchers.Default) {
            try { db.store().confirm(request(1, identity = uuid(index + 10))); true } catch (_: IOException) { false }
        } }.map { it.await() }
        assertEquals(1, attempts.count { it })
        assertEquals(2, ready(db.store().load()).completedChapterCount)
        assertEquals(2, db.writes)
    }

    private class MemoryDatabase : CompetitionMahjongLearningProofDao {
        val rows = linkedMapOf<Int, ByteArray>()
        var metadataOverride: List<CompetitionMahjongLearningProofMetadata>? = null
        var payloadReader: (() -> ByteArray?)? = null
        var replacementOnWrite: ByteArray? = null
        var failRead = false
        var failBeforeCommit = false
        var failAfterCommit = false
        var cancelAfterWrite = false
        var dropWrites = false
        var writes = 0
        var payloadReads = 0
        var transactions = 0
        private var active = false
        private val mutex = Mutex()

        fun store() = RoomCompetitionMahjongLearningProofStore(this, Dispatchers.Unconfined, ::transaction)

        private suspend fun transaction(operation: suspend () -> Unit) = mutex.withLock {
            val before = rows.mapValues { it.value.copyOf() }
            transactions++
            active = true
            try {
                operation()
                if (failBeforeCommit) {
                    failBeforeCommit = false
                    throw IOException("Injected commit failure")
                }
            } catch (failure: Throwable) {
                rows.clear()
                rows.putAll(before)
                throw failure
            } finally { active = false }
            if (failAfterCommit) {
                failAfterCommit = false
                throw IOException("Injected acknowledgement loss")
            }
        }

        override suspend fun readBoundedMetadata(): List<CompetitionMahjongLearningProofMetadata> {
            check(active)
            if (failRead) throw IOException("Injected read failure")
            return metadataOverride ?: rows.toSortedMap().entries.take(2).map { (key, bytes) ->
                CompetitionMahjongLearningProofMetadata(key, bytes.size.toLong())
            }
        }

        override suspend fun readBoundedPayload(minimumBytes: Int, maximumBytes: Int): ByteArray? {
            check(active)
            assertEquals(codec.MIN_BYTES, minimumBytes)
            assertEquals(codec.MAX_BYTES, maximumBytes)
            payloadReads++
            payloadReader?.let { return it() }
            return rows[0]?.takeIf { it.size in minimumBytes..maximumBytes }?.copyOf()
        }

        override suspend fun upsert(row: CompetitionMahjongLearningProofRow) {
            check(active)
            assertEquals(0, row.singletonId)
            writes++
            if (!dropWrites) rows[row.singletonId] = (replacementOnWrite ?: row.proof).copyOf()
            if (cancelAfterWrite) currentCoroutineContext().cancel()
        }

        override suspend fun deleteAll() { check(active); rows.clear() }
    }

    companion object {
        private val codec get() = CompetitionMahjongLearningProofCodec
        private fun emptyBytes() = codec.encode(CompetitionMahjongLearningProof())
        private fun uuid(index: Int) = "00000000-0000-0000-0000-${(index + 1).toString().padStart(12, '0')}"
        private fun request(index: Int, identity: String = uuid(index), timestamp: Long = index.toLong()) =
            CompetitionMahjongLearningProofRequest(identity, CompetitionMahjongLearningProofCatalog.chapterIds[index],
                CompetitionMahjongLearningProofCatalog.cardIds(CompetitionMahjongLearningProofCatalog.chapterIds[index]), timestamp)
        private fun ready(read: CompetitionMahjongLearningProofRead) = (read as CompetitionMahjongLearningProofRead.Ready).proof
        private suspend fun expectIo(operation: suspend () -> Unit): IOException {
            try { operation() } catch (failure: IOException) { return failure }
            throw AssertionError("Expected IOException")
        }
    }
}
