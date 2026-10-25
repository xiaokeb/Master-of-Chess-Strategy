package com.masterofchessstrategy.games.lanzhou_fangqi.data

import androidx.room.RoomDatabase
import androidx.room.withTransaction
import com.masterofchessstrategy.games.lanzhou_fangqi.FangqiStoredSession
import java.io.IOException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Root must supply the DAO of this exact database when schema 19 is registered. */
internal class RoomFangqiLearningStore internal constructor(
    private val dao: FangqiLearningProgressDao,
    private val gate: FangqiStorageEpochGate,
    private val owner: FangqiStorageEpochGate.Owner,
    private val verifier: FangqiLearningCompletionVerifier,
    private val dispatcher: CoroutineDispatcher,
    private val clock: () -> Long,
    // Explicit transaction model seam; production always delegates to RoomDatabase.withTransaction.
    private val transaction: suspend (suspend () -> Unit) -> Unit,
) : FangqiLearningStore {
    constructor(
        database: RoomDatabase,
        dao: FangqiLearningProgressDao,
        gate: FangqiStorageEpochGate = FangqiStorageEpochGate.forDatabase(database),
        owner: FangqiStorageEpochGate.Owner = gate.captureOwner(),
    ) : this(dao, gate, owner, FangqiLearningPracticeAdmission(), Dispatchers.IO,
        System::currentTimeMillis, { operation -> database.withTransaction { operation() } }) {
        require(gate === FangqiStorageEpochGate.forDatabase(database)) { "方棋学习必须使用该数据库的统一门禁" }
    }

    override suspend fun load(): FangqiLearningSnapshot = gate.access(owner) {
        withContext(dispatcher) {
            currentCoroutineContext().ensureActive()
            FangqiLearningSnapshot(dao.readAll()).also { currentCoroutineContext().ensureActive() }
        }
    }

    override suspend fun acknowledgeLesson(id: String): FangqiLearningSnapshot =
        record(FangqiLearningCatalog.lessonKey(id))

    override suspend fun completePractice(id: String, completeHistory: ByteArray): FangqiLearningSnapshot {
        val key = FangqiLearningCatalog.practiceKey(id)
        if (completeHistory.size > FangqiStoredSession.MAX_ENGINE_BYTES) {
            throw IOException("方棋练习完整历史超限")
        }
        // Own the evidence before waiting for gate/dispatcher; a caller cannot replace it in flight.
        val owned = completeHistory.copyOf()
        return record(key) { verifier.verify(id, owned) }
    }

    private suspend fun record(key: String, admit: () -> Unit = {}): FangqiLearningSnapshot = gate.access(owner) {
        withContext(dispatcher) {
            currentCoroutineContext().ensureActive()
            admit()
            currentCoroutineContext().ensureActive()
            var committed: FangqiLearningSnapshot? = null
            transaction {
                currentCoroutineContext().ensureActive()
                val before = FangqiLearningSnapshot(dao.readAll())
                val expected = if (before.rows.any { it.contentKey == key }) before else {
                    val row = FangqiLearningProgressEntity(key, FangqiLearningCatalog.CONTENT_VERSION, clock())
                    FangqiLearningCatalog.validate(row)
                    dao.insert(row)
                    FangqiLearningSnapshot(before.rows + row)
                }
                currentCoroutineContext().ensureActive()
                val readBack = FangqiLearningSnapshot(dao.readAll())
                if (readBack.rows != expected.rows) throw IOException("方棋学习写入读回不一致，保存已回滚")
                currentCoroutineContext().ensureActive()
                committed = readBack
            }
            checkNotNull(committed) { "方棋学习事务未执行" }
        }
    }
}
