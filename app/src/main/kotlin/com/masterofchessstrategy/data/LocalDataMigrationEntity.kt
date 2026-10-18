package com.masterofchessstrategy.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

/** Installation-owned tombstones: user-data restore must neither erase nor import these rows. */
@Entity(tableName = "local_data_migrations")
internal data class LocalDataMigrationEntity(@PrimaryKey val scope: String, val handledVersion: Int)

@Dao
internal interface LocalDataMigrationDao {
    @Query("SELECT * FROM local_data_migrations WHERE scope = :scope")
    suspend fun find(scope: String): LocalDataMigrationEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entity: LocalDataMigrationEntity): Long
}

/** These names identify legacy sources, not the current codec/schema version. Never rename them. */
internal object GoLegacyImportScopes {
    const val SESSION = "go-legacy-local-session"
    const val LEARNING = "go-legacy-learning-preferences"
    const val ENDGAME = "go-legacy-endgame-progress"
    const val AI_MATCH = "go-legacy-ai-match"
    val ALL = listOf(SESSION, LEARNING, ENDGAME, AI_MATCH)
}

/** Call inside the replacement transaction, even when the incoming backup contains no Go data. */
internal suspend fun LocalDataMigrationDao.sealGoLegacySourcesForRestore() {
    for (scope in GoLegacyImportScopes.ALL) {
        // Keep installation-owned higher versions; neither a backup nor a restore may downgrade them.
        insert(LocalDataMigrationEntity(scope, 1))
        check((find(scope)?.handledVersion ?: 0) >= 1) { "Invalid Go legacy restore fence" }
    }
}
