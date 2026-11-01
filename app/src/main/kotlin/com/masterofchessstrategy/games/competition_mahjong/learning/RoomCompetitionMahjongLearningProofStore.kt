package com.masterofchessstrategy.games.competition_mahjong.learning

import androidx.room.RoomDatabase
import androidx.room.withTransaction
import java.io.IOException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Transactional storage only. The caller supplies the DAO from this same RoomDatabase and must
 * retire/drain writers before replacing the whole database. This class introduces no restore gate.
 */
internal class RoomCompetitionMahjongLearningProofStore internal constructor(
    private val dao: CompetitionMahjongLearningProofDao,
    private val dispatcher: CoroutineDispatcher,
    private val transaction: suspend (suspend () -> Unit) -> Unit,
) : CompetitionMahjongLearningProofStore {
    constructor(database: RoomDatabase, dao: CompetitionMahjongLearningProofDao) :
        this(dao, Dispatchers.IO, { operation -> database.withTransaction { operation() } })

    override suspend fun load(): CompetitionMahjongLearningProofRead = withContext(dispatcher) {
        var result: CompetitionMahjongLearningProofRead? = null
        transaction {
            currentCoroutineContext().ensureActive()
            result = readInTransaction()
            currentCoroutineContext().ensureActive()
        }
        checkNotNull(result)
    }

    override suspend fun confirm(request: CompetitionMahjongLearningProofRequest): CompetitionMahjongLearningProof =
        withContext(dispatcher) {
            var result: CompetitionMahjongLearningProof? = null
            transaction {
                currentCoroutineContext().ensureActive()
                val current = requireReady(readInTransaction())
                val proposed = try {
                    current.confirm(request)
                } catch (failure: IllegalArgumentException) {
                    throw IOException("国标麻将章确认与已保存进度冲突，原数据已保留", failure)
                }
                val expected = CompetitionMahjongLearningProofCodec.encode(proposed)
                if (proposed !== current) dao.upsert(CompetitionMahjongLearningProofRow(0, expected.copyOf()))
                currentCoroutineContext().ensureActive()
                val persisted = requireReady(readInTransaction())
                if (!CompetitionMahjongLearningProofCodec.encode(persisted).contentEquals(expected)) {
                    throw IOException("国标麻将教程确认读回不一致，保存已回滚")
                }
                currentCoroutineContext().ensureActive()
                result = persisted
            }
            checkNotNull(result)
        }

    private suspend fun readInTransaction(): CompetitionMahjongLearningProofRead {
        val rows = dao.readBoundedMetadata()
        if (rows.isEmpty()) return CompetitionMahjongLearningProofRead.Ready(CompetitionMahjongLearningProof())
        if (rows.size != 1 || rows.single().singletonId != 0 ||
            rows.single().proofBytes !in CompetitionMahjongLearningProofCodec.MIN_BYTES.toLong()..
                CompetitionMahjongLearningProofCodec.MAX_BYTES.toLong()) return CompetitionMahjongLearningProofRead.Incompatible
        val bytes = dao.readBoundedPayload(
            CompetitionMahjongLearningProofCodec.MIN_BYTES, CompetitionMahjongLearningProofCodec.MAX_BYTES,
        ) ?: return CompetitionMahjongLearningProofRead.Incompatible
        if (bytes.size.toLong() != rows.single().proofBytes) return CompetitionMahjongLearningProofRead.Incompatible
        val proof = CompetitionMahjongLearningProofCodec.decode(bytes) ?: return CompetitionMahjongLearningProofRead.Incompatible
        return CompetitionMahjongLearningProofRead.Ready(proof)
    }

    private fun requireReady(read: CompetitionMahjongLearningProofRead): CompetitionMahjongLearningProof = when (read) {
        is CompetitionMahjongLearningProofRead.Ready -> read.proof
        CompetitionMahjongLearningProofRead.Incompatible -> throw IOException("国标麻将教程证明不兼容，原数据已保留")
    }
}
