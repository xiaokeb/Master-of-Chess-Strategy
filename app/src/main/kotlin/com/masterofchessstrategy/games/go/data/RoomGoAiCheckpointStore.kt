package com.masterofchessstrategy.games.go.data

import androidx.room.withTransaction
import com.masterofchessstrategy.data.GoLegacyImportScopes
import com.masterofchessstrategy.data.LocalDataMigrationEntity
import com.masterofchessstrategy.data.MocsDatabase
import com.masterofchessstrategy.engine.GameType

/** Atomic raw checkpoint/archive/seal only: no GameRecord projection or reward writer. */
internal class RoomGoAiCheckpointStore(private val database: MocsDatabase) : GoAiCheckpointStore {
    override suspend fun <T> transaction(block: suspend GoAiCheckpointTransaction.() -> T): T =
        database.withTransaction {
            val result = block(Access())
            database.requireGoAiArchiveParents()
            result
        }

    private inner class Access : GoAiCheckpointTransaction {
        override suspend fun readActive(): GoAiCheckpointEntry? =
            database.goAiCheckpointDao().findActive(GameType.GO.code)?.let(GoAiCheckpointRows::entry)

        override suspend fun writeActive(entry: GoAiCheckpointEntry, updatedAtEpochMillis: Long) =
            database.goAiCheckpointDao().saveActive(GoAiCheckpointRows.active(entry, updatedAtEpochMillis))

        override suspend fun clearActive() = database.goAiCheckpointDao().clearActive(GameType.GO.code)

        override suspend fun legacyHandled(): Boolean {
            val marker = database.localDataMigrationDao().find(GoLegacyImportScopes.AI_MATCH) ?: return false
            require(marker.handledVersion >= 1) { "Invalid Go AI legacy migration marker" }
            return true
        }

        override suspend fun markLegacyHandled() {
            database.localDataMigrationDao().insert(LocalDataMigrationEntity(GoLegacyImportScopes.AI_MATCH, 1))
            check(legacyHandled())
        }

        override suspend fun findArchive(recordId: String): GoAiCheckpointArchive? {
            require(recordId.matches(Regex("[0-9a-f]{64}")))
            return database.goAiCheckpointDao().findArchive(recordId)?.let { row ->
                require(row.recordId == recordId)
                GoAiCheckpointRows.archive(row)
            }
        }

        override suspend fun insertArchive(archive: GoAiCheckpointArchive) =
            database.goAiCheckpointDao().insertArchive(GoAiCheckpointRows.archive(archive))
    }
}
