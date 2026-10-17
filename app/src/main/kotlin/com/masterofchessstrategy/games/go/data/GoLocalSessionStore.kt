package com.masterofchessstrategy.games.go.data

import androidx.room.withTransaction
import com.masterofchessstrategy.data.GameRecord
import com.masterofchessstrategy.data.GoLegacyImportScopes
import com.masterofchessstrategy.data.LocalDataMigrationEntity
import com.masterofchessstrategy.data.MocsDatabase
import com.masterofchessstrategy.data.toGameRecord
import com.masterofchessstrategy.data.toGameRecordEntity
import com.masterofchessstrategy.engine.GameType
import com.masterofchessstrategy.games.go.GoSessionIdentity
import com.masterofchessstrategy.games.go.GoSessionOrigin

/** Implementations must roll back every operation when the callback fails or is cancelled. */
internal interface GoLocalSessionStore {
    suspend fun <T> transaction(block: suspend GoLocalSessionTransaction.() -> T): T
}

internal interface GoLocalSessionTransaction {
    suspend fun readActive(): ByteArray?
    suspend fun writeActive(envelope: ByteArray, updatedAtEpochMillis: Long)
    suspend fun clearActive()
    suspend fun legacyHandled(): Boolean
    suspend fun markLegacyHandled()
    suspend fun findRecord(recordId: String): GameRecord?
    suspend fun findSource(recordId: String): GoSessionIdentity?
    suspend fun insertRecord(record: GameRecord, source: GoSessionIdentity)
}

internal class RoomGoLocalSessionStore(private val database: MocsDatabase) : GoLocalSessionStore {
    override suspend fun <T> transaction(block: suspend GoLocalSessionTransaction.() -> T): T =
        database.withTransaction { block(Access()) }

    private inner class Access : GoLocalSessionTransaction {
        override suspend fun readActive(): ByteArray? {
            val row = database.goLocalSessionDao().find(GameType.GO.code) ?: return null
            require(row.gameTypeCode == GameType.GO.code && row.envelopeVersion == 3 && row.updatedAtEpochMillis >= 0)
            return row.checkpoint.copyOf()
        }

        override suspend fun writeActive(envelope: ByteArray, updatedAtEpochMillis: Long) {
            database.goLocalSessionDao().save(GoLocalSessionEntity(
                GameType.GO.code, 3, envelope.copyOf(), updatedAtEpochMillis,
            ))
        }

        override suspend fun clearActive() = database.goLocalSessionDao().clear(GameType.GO.code)

        override suspend fun legacyHandled(): Boolean {
            val marker = database.localDataMigrationDao().find(GoLegacyImportScopes.SESSION) ?: return false
            require(marker.handledVersion >= 1) { "Invalid Go legacy migration marker" }
            return true
        }

        override suspend fun markLegacyHandled() {
            database.localDataMigrationDao().insert(LocalDataMigrationEntity(GoLegacyImportScopes.SESSION, 1))
            check(legacyHandled())
        }

        override suspend fun findRecord(recordId: String): GameRecord? {
            val row = database.gameRecordDao().find(recordId) ?: return null
            return requireNotNull(row.toGameRecord()) { "Incompatible archived Go record" }
        }

        override suspend fun findSource(recordId: String): GoSessionIdentity? {
            val row = database.goRecordSourceDao().find(recordId) ?: return null
            require(row.gameTypeCode == GameType.GO.code)
            val origin = requireNotNull(GoSessionOrigin.entries.singleOrNull { it.code == row.originCode })
            return GoSessionIdentity(row.sessionId, origin, row.archiveRevision).also {
                require(it.recordId == recordId) { "Go record provenance has a different identity" }
            }
        }

        override suspend fun insertRecord(record: GameRecord, source: GoSessionIdentity) {
            require(record.gameType == GameType.GO && record.recordId == source.recordId)
            check(database.gameRecordDao().insert(record.toGameRecordEntity()) != -1L)
            check(database.goRecordSourceDao().insert(GoRecordSourceEntity(
                record.recordId, GameType.GO.code, source.sessionId, source.origin.code, source.archiveRevision,
            )) != -1L)
        }
    }
}
