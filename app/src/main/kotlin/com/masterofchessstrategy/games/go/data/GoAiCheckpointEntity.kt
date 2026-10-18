package com.masterofchessstrategy.games.go.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import com.masterofchessstrategy.engine.GameType
import com.masterofchessstrategy.games.go.GoAiMatchFileCodec

/** Full canonical GAI envelope. Nullable lineage is context, not ranked eligibility. */
@Entity(tableName = "go_ai_sessions", indices = [Index("replacedTerminalRecordId")], foreignKeys = [ForeignKey(
    entity = GoAiArchiveEntity::class, parentColumns = ["recordId"], childColumns = ["replacedTerminalRecordId"],
    onDelete = ForeignKey.NO_ACTION, onUpdate = ForeignKey.NO_ACTION, deferred = true,
)])
internal data class GoAiSessionEntity(
    @PrimaryKey val gameTypeCode: Int,
    val envelopeVersion: Int,
    val checkpoint: ByteArray,
    val originCode: Int,
    val replacedTerminalRecordId: String?,
    val updatedAtEpochMillis: Long,
)

/** First terminal envelope/time are insert-only; mutable reservation stays in the active row. */
@Entity(tableName = "go_ai_archives", indices = [Index("replacedTerminalRecordId")], foreignKeys = [ForeignKey(
    entity = GoAiArchiveEntity::class, parentColumns = ["recordId"], childColumns = ["replacedTerminalRecordId"],
    onDelete = ForeignKey.NO_ACTION, onUpdate = ForeignKey.NO_ACTION, deferred = true,
)])
internal data class GoAiArchiveEntity(
    @PrimaryKey val recordId: String,
    val gameTypeCode: Int,
    val envelopeVersion: Int,
    val checkpoint: ByteArray,
    val originCode: Int,
    val replacedTerminalRecordId: String?,
    val completedAtEpochMillis: Long,
)

@Dao
internal interface GoAiCheckpointDao {
    @Query("SELECT * FROM go_ai_sessions ORDER BY gameTypeCode")
    suspend fun listActive(): List<GoAiSessionEntity>

    @Query("SELECT * FROM go_ai_sessions WHERE gameTypeCode = :gameTypeCode")
    suspend fun findActive(gameTypeCode: Int): GoAiSessionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveActive(entity: GoAiSessionEntity)

    @Query("DELETE FROM go_ai_sessions WHERE gameTypeCode = :gameTypeCode")
    suspend fun clearActive(gameTypeCode: Int)

    @Query("DELETE FROM go_ai_sessions")
    suspend fun deleteAllActive()

    @Query("SELECT * FROM go_ai_archives WHERE recordId = :recordId")
    suspend fun findArchive(recordId: String): GoAiArchiveEntity?

    @Query("SELECT * FROM go_ai_archives ORDER BY completedAtEpochMillis,recordId")
    suspend fun listArchives(): List<GoAiArchiveEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertArchive(entity: GoAiArchiveEntity)

    // Full replacement only, inside its transaction; never cascade-delete a parent on clearActive.
    @Query("DELETE FROM go_ai_archives")
    suspend fun deleteAllArchives()

    // Do not let a deferred violation reach COMMIT: framework SQLite can leave
    // that failed transaction open after Room has released its logical scope.
    @Query("SELECT EXISTS(SELECT 1 FROM go_ai_sessions AS child " +
        "LEFT JOIN go_ai_archives AS parent ON parent.recordId = child.replacedTerminalRecordId " +
        "WHERE child.replacedTerminalRecordId IS NOT NULL AND parent.recordId IS NULL) " +
        "OR EXISTS(SELECT 1 FROM go_ai_archives AS child " +
        "LEFT JOIN go_ai_archives AS parent ON parent.recordId = child.replacedTerminalRecordId " +
        "WHERE child.replacedTerminalRecordId IS NOT NULL AND parent.recordId IS NULL)")
    suspend fun hasMissingArchiveParent(): Boolean
}

/** Structural row boundary only. Native terminal/history admission stays in the repository. */
internal object GoAiCheckpointRows {
    fun active(entry: GoAiCheckpointEntry, time: Long): GoAiSessionEntity {
        require(time >= 0)
        val bytes = canonical(entry.copyBytes())
        return GoAiSessionEntity(GameType.GO.code, 6, bytes, code(entry.lineage.origin),
            entry.lineage.replacedTerminalRecordId, time)
    }

    fun entry(row: GoAiSessionEntity): GoAiCheckpointEntry {
        require(row.gameTypeCode == GameType.GO.code && row.envelopeVersion == 6 && row.updatedAtEpochMillis >= 0)
        return GoAiCheckpointEntry(canonical(row.checkpoint.copyOf()), lineage(row.originCode, row.replacedTerminalRecordId))
    }

    fun archive(archive: GoAiCheckpointArchive): GoAiArchiveEntity {
        require(archive.checkpoint.lineage.replacedTerminalRecordId != archive.recordId) { "AI archive cannot be its own parent" }
        return GoAiArchiveEntity(archive.recordId, GameType.GO.code, 6, canonical(archive.checkpoint.copyBytes()),
            code(archive.checkpoint.lineage.origin), archive.checkpoint.lineage.replacedTerminalRecordId,
            archive.completedAtEpochMillis)
    }

    fun archive(row: GoAiArchiveEntity): GoAiCheckpointArchive {
        require(row.gameTypeCode == GameType.GO.code && row.envelopeVersion == 6)
        require(row.replacedTerminalRecordId != row.recordId) { "AI archive cannot be its own parent" }
        return GoAiCheckpointArchive(row.recordId, GoAiCheckpointEntry(canonical(row.checkpoint.copyOf()),
            lineage(row.originCode, row.replacedTerminalRecordId)), row.completedAtEpochMillis)
    }

    private fun canonical(bytes: ByteArray): ByteArray {
        require(bytes.size > 4 && bytes[4] == 6.toByte()) { "AI Room checkpoint requires GAI1 v6" }
        val saved = requireNotNull(GoAiMatchFileCodec.decode(bytes)) { "Invalid AI Room checkpoint" }
        require(GoAiMatchFileCodec.encode(saved).contentEquals(bytes)) { "Noncanonical AI Room checkpoint" }
        return bytes
    }

    private fun code(origin: GoAiCheckpointOrigin) = when (origin) {
        GoAiCheckpointOrigin.LEGACY_UNVERIFIED -> 1
        GoAiCheckpointOrigin.UNVERIFIED_SESSION -> 2
        GoAiCheckpointOrigin.TERMINAL_CONTINUATION -> 3
    }

    private fun lineage(code: Int, parent: String?) = GoAiCheckpointLineage(when (code) {
        1 -> GoAiCheckpointOrigin.LEGACY_UNVERIFIED
        2 -> GoAiCheckpointOrigin.UNVERIFIED_SESSION
        3 -> GoAiCheckpointOrigin.TERMINAL_CONTINUATION
        else -> throw IllegalArgumentException("Unknown AI checkpoint provenance")
    }, parent)
}
