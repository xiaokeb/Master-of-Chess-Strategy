package com.masterofchessstrategy.games.lanzhou_mahjong.data

import androidx.room.RoomDatabase
import androidx.room.withTransaction
import com.masterofchessstrategy.data.ActiveGameDao
import com.masterofchessstrategy.data.ActiveGameEntity
import com.masterofchessstrategy.engine.GameType
import com.masterofchessstrategy.games.lanzhou_mahjong.LanzhouMahjongSessionRecord
import java.io.IOException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** The record already owns its native bytes/decisions; caller fixes time once and reuses it on retry. */
internal data class LanzhouMahjongStoredSession(
    val record: LanzhouMahjongSessionRecord,
    val updatedAtEpochMillis: Long,
) {
    init { require(updatedAtEpochMillis >= 0) }
}

internal interface LanzhouMahjongAdmittedSessionStore {
    /** Only a missing game3 row returns null; malformed data and storage errors remain errors. */
    suspend fun load(): LanzhouMahjongStoredSession?
    suspend fun save(snapshot: LanzhouMahjongStoredSession)
}

/** One durable full-history row, guarded by a fixed owner and an exact transactional read-back. */
internal class RoomLanzhouMahjongAdmittedSessionStore internal constructor(
    private val dao: ActiveGameDao,
    private val gate: LanzhouMahjongStorageEpochGate,
    private val owner: LanzhouMahjongStorageEpochGate.Owner,
    private val admission: LanzhouMahjongSessionAdmission,
    private val dispatcher: CoroutineDispatcher,
    // Explicit JVM fault-injection seam. Production always uses Room's real transaction below.
    private val transaction: suspend (suspend () -> Unit) -> Unit,
) : LanzhouMahjongAdmittedSessionStore {
    /** Root must obtain dao from this exact database; RoomDatabase cannot introspect DAO ownership. */
    constructor(
        database: RoomDatabase,
        dao: ActiveGameDao,
        gate: LanzhouMahjongStorageEpochGate = LanzhouMahjongStorageEpochGate.forDatabase(database),
        owner: LanzhouMahjongStorageEpochGate.Owner = gate.captureOwner(),
        admission: LanzhouMahjongSessionAdmission = LanzhouMahjongSessionAdmission(),
    ) : this(dao, gate, owner, admission, Dispatchers.IO,
        { operation -> database.withTransaction { operation() } }) {
        require(gate === LanzhouMahjongStorageEpochGate.forDatabase(database)) {
            "兰州麻将存储必须使用该数据库的统一门禁"
        }
    }

    override suspend fun load(): LanzhouMahjongStoredSession? = gate.access(owner) {
        withContext(dispatcher) {
            currentCoroutineContext().ensureActive()
            val row = dao.find(GameType.LANZHOU_MAHJONG.code) ?: return@withContext null
            // Admission rejects an oversized row before any byte-array copy or Native call.
            val record = admission.admitRow(row)
            currentCoroutineContext().ensureActive()
            LanzhouMahjongStoredSession(record, row.updatedAtEpochMillis)
        }
    }

    override suspend fun save(snapshot: LanzhouMahjongStoredSession) = gate.access(owner) {
        withContext(dispatcher) {
            currentCoroutineContext().ensureActive()
            transaction {
                currentCoroutineContext().ensureActive()
                val candidate = admission.createRow(snapshot.record, snapshot.updatedAtEpochMillis)
                currentCoroutineContext().ensureActive()
                dao.upsert(candidate.owned())
                currentCoroutineContext().ensureActive()
                val readBack = dao.find(GameType.LANZHOU_MAHJONG.code)
                if (readBack != candidate) throw IOException("兰州麻将写入读回不一致，保存已回滚")
                currentCoroutineContext().ensureActive()
            }
            // Successful transaction return is the acknowledgement; no new timestamp/ID is generated.
        }
    }

    private fun ActiveGameEntity.owned(): ActiveGameEntity = copy(engineState = engineState.copyOf())
}
