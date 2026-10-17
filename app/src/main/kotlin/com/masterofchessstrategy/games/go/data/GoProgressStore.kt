package com.masterofchessstrategy.games.go.data

import androidx.room.withTransaction
import com.masterofchessstrategy.data.LocalDataMigrationEntity
import com.masterofchessstrategy.data.MocsDatabase
import com.masterofchessstrategy.engine.GameType

internal interface GoProgressStore {
    suspend fun <T> transaction(block: suspend GoProgressTransaction.() -> T): T
}

internal interface GoProgressTransaction {
    suspend fun read(kind: GoProgressKind): ByteArray?
    suspend fun write(kind: GoProgressKind, bytes: ByteArray, updatedAtEpochMillis: Long)
    suspend fun clear(kind: GoProgressKind)
    suspend fun legacyHandled(kind: GoProgressKind): Boolean
    suspend fun markLegacyHandled(kind: GoProgressKind)
}

internal class RoomGoProgressStore(private val database: MocsDatabase) : GoProgressStore {
    override suspend fun <T> transaction(block: suspend GoProgressTransaction.() -> T): T =
        database.withTransaction { block(Access()) }

    private inner class Access : GoProgressTransaction {
        override suspend fun read(kind: GoProgressKind): ByteArray? {
            val row = database.goProgressDao().find(GameType.GO.code, kind.code) ?: return null
            require(row.gameTypeCode == GameType.GO.code && row.kindCode == kind.code &&
                row.formatVersion == 1 && row.contentVersion == kind.contentVersion && row.updatedAtEpochMillis >= 0) {
                "Incompatible Go progress row"
            }
            return kind.normalize(row.payload.copyOf())
        }

        override suspend fun write(kind: GoProgressKind, bytes: ByteArray, updatedAtEpochMillis: Long) {
            require(updatedAtEpochMillis >= 0)
            database.goProgressDao().save(GoProgressEntity(GameType.GO.code, kind.code, 1,
                kind.contentVersion, kind.normalize(bytes.copyOf()), updatedAtEpochMillis))
        }

        override suspend fun clear(kind: GoProgressKind) = database.goProgressDao().clear(GameType.GO.code, kind.code)

        override suspend fun legacyHandled(kind: GoProgressKind): Boolean {
            val row = database.localDataMigrationDao().find(kind.legacyScope) ?: return false
            require(row.handledVersion >= 1) { "Invalid Go progress migration marker" }
            return true
        }

        override suspend fun markLegacyHandled(kind: GoProgressKind) {
            database.localDataMigrationDao().insert(LocalDataMigrationEntity(kind.legacyScope, 1))
            check(legacyHandled(kind))
        }
    }
}

/** The source is read only after taking the same transaction that commits its permanent fence. */
internal class GoProgressAccess(
    private val store: GoProgressStore,
    private val kind: GoProgressKind,
    private val legacyBytes: suspend () -> ByteArray = { kind.emptyBytes() },
    private val clock: () -> Long = System::currentTimeMillis,
) {
    suspend fun load(): ByteArray = store.transaction {
        val (current, needsSeal) = current()
        if (needsSeal) persist(current)
        current.copyOf()
    }

    suspend fun update(transform: (ByteArray) -> ByteArray): ByteArray = store.transaction {
        val (current, _) = current()
        val next = kind.normalize(transform(current.copyOf()))
        persist(next)
        next.copyOf()
    }

    suspend fun clear() = store.transaction {
        clear(kind)
        markLegacyHandled(kind)
    }

    private suspend fun GoProgressTransaction.current(): Pair<ByteArray, Boolean> {
        read(kind)?.let { return kind.normalize(it) to false }
        if (legacyHandled(kind)) return kind.emptyBytes() to false
        return kind.normalize(legacyBytes()) to true
    }

    private suspend fun GoProgressTransaction.persist(bytes: ByteArray) {
        val now = clock()
        require(now >= 0)
        write(kind, bytes.copyOf(), now)
        markLegacyHandled(kind)
    }
}
