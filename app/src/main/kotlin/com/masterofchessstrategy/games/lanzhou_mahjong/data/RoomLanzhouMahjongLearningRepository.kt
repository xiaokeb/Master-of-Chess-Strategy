package com.masterofchessstrategy.games.lanzhou_mahjong.data

import androidx.room.withTransaction
import com.masterofchessstrategy.data.MocsDatabase
import java.io.IOException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Same-DB fixed owner, compare-and-set progress and exact read-back in one Room transaction. */
internal class RoomLanzhouMahjongLearningRepository internal constructor(
    private val dao: LanzhouMahjongLearningProgressDao,
    private val gate: LanzhouMahjongStorageEpochGate,
    private val owner: LanzhouMahjongStorageEpochGate.Owner,
    private val dispatcher: CoroutineDispatcher,
    private val transaction: suspend (suspend () -> Unit) -> Unit,
) : LanzhouMahjongLearningRepository {
    constructor(
        database: MocsDatabase,
        gate: LanzhouMahjongStorageEpochGate = LanzhouMahjongStorageEpochGate.forDatabase(database),
        owner: LanzhouMahjongStorageEpochGate.Owner = gate.captureOwner(),
    ) : this(database.lanzhouMahjongLearningProgressDao(), gate, owner, Dispatchers.IO,
        { operation -> database.withTransaction { operation() } }) {
        require(gate === LanzhouMahjongStorageEpochGate.forDatabase(database))
    }

    override val ownerEpoch: Long = owner.epoch
    override val epoch get() = gate.epoch

    override suspend fun load(): LanzhouMahjongLearningSnapshot? = gate.access(owner) {
        withContext(dispatcher) {
            currentCoroutineContext().ensureActive()
            decodeLanzhouLearningRows(dao.readBoundedRows()).also { currentCoroutineContext().ensureActive() }
        }
    }

    override suspend fun advance(request: LanzhouMahjongLearningAdvance): LanzhouMahjongLearningSnapshot =
        gate.access(owner) {
            withContext(dispatcher) {
                transaction {
                    currentCoroutineContext().ensureActive()
                    val current = decodeLanzhouLearningRows(dao.readBoundedRows())
                    if (current != request.after) {
                        if (current != request.before) throw LanzhouMahjongLearningConflictException()
                        dao.upsert(request.after.toLearningEntity())
                    }
                    currentCoroutineContext().ensureActive()
                    if (decodeLanzhouLearningRows(dao.readBoundedRows()) != request.after) {
                        throw IOException("教程写入读回不一致，保存已回滚")
                    }
                    currentCoroutineContext().ensureActive()
                }
                // Lost acknowledgements retry the same values; never regenerate time or refresh the owner.
                request.after
            }
        }
}

internal fun LanzhouMahjongLearningSnapshot.toLearningEntity() = LanzhouMahjongLearningProgressEntity(
    0, contentVersion, completedCount, updatedAtEpochMillis,
)

internal fun decodeLanzhouLearningRows(
    rows: List<LanzhouMahjongLearningProgressEntity>,
): LanzhouMahjongLearningSnapshot? {
    if (rows.isEmpty()) return null
    if (rows.size != 1 || rows.single().singletonId != 0) throw IOException("兰州麻将教程进度不兼容，原数据已保留")
    val row = rows.single()
    return try { LanzhouMahjongLearningSnapshot(row.contentVersion, row.completedCount, row.updatedAtEpochMillis) }
    catch (failure: IllegalArgumentException) { throw IOException("兰州麻将教程进度不兼容，原数据已保留", failure) }
}
