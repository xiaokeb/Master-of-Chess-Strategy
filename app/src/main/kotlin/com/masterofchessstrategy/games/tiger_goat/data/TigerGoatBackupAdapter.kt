package com.masterofchessstrategy.games.tiger_goat.data

import com.masterofchessstrategy.data.ActiveGameDao
import com.masterofchessstrategy.data.BackupStorageSlot
import com.masterofchessstrategy.data.BackupStorageTable
import com.masterofchessstrategy.data.GameBackupAdapter
import com.masterofchessstrategy.data.GameBackupDescriptor
import com.masterofchessstrategy.data.GameBackupRecord
import com.masterofchessstrategy.data.MocsDatabase
import com.masterofchessstrategy.data.PreparedGameBackup
import com.masterofchessstrategy.data.TigerGoatActiveArchive
import com.masterofchessstrategy.data.validateTigerGoatActiveArchive
import com.masterofchessstrategy.engine.GameType

/** Keeps the existing MOCT wire envelope independent of Xiangqi and other game adapters. */
internal class TigerGoatBackupAdapter(
    private val nativeAdmission: (TigerGoatActiveArchive) -> Boolean = ::validateTigerGoatActiveArchive,
) : GameBackupAdapter {
    override val descriptor = GameBackupDescriptor(
        kind = KIND,
        minimumContainerVersion = 5,
        storageSlot = SLOT,
        maximumRecords = 1,
        canonicalOrder = 0,
        maximumRecordBytes = MAX_CANONICAL_RECORD_BYTES,
    )

    override suspend fun read(database: MocsDatabase): List<GameBackupRecord> = read(database.activeGameDao())

    /** The repository, not this adapter, supplies the complete snapshot transaction. */
    internal suspend fun read(dao: ActiveGameDao): List<GameBackupRecord> =
        dao.find(GameType.TIGER_AND_GOAT.code)?.let { listOf(encodeArchive(TigerGoatActiveArchive.fromEntity(it))) }
            ?: emptyList()

    override fun validate(record: GameBackupRecord, containerVersion: Int) {
        require(containerVersion == 5) { "Tiger-goat backups require container v5" }
        decodeRecord(record)
    }

    override fun normalize(record: GameBackupRecord, containerVersion: Int): GameBackupRecord {
        require(containerVersion == 5) { "Tiger-goat backups require container v5" }
        // Legacy numeric spellings and uppercase hex are accepted before canonical byte limits.
        return encodeArchive(decodeRecord(record))
    }

    override fun prepareRestore(record: GameBackupRecord): PreparedTigerGoatBackup {
        val archive = decodeRecord(record)
        // The default validator opens, replays in, and closes its own actual Native session.
        require(nativeAdmission(archive)) { "Native rejected the tiger-goat history" }
        return PreparedTigerGoatBackup(archive)
    }

    internal class PreparedTigerGoatBackup(private val archive: TigerGoatActiveArchive) : PreparedGameBackup {
        override val storageSlot = SLOT
        override suspend fun write(database: MocsDatabase) = write(database.activeGameDao())

        /** No clear or nested transaction: the public restore owns atomic replacement. */
        internal suspend fun write(dao: ActiveGameDao) {
            dao.upsert(archive.toEntity())
        }
    }

    /** Envelope conversion only; callers must separately finish actual Native admission. */
    internal fun decodeRecord(record: GameBackupRecord): TigerGoatActiveArchive {
        require(record.kind == KIND && record.fields.size == 7) { "Invalid tiger-goat record shape" }
        val fields = record.fields
        require(fields[0].toIntOrNull() == GameType.TIGER_AND_GOAT.code)
        require(fields[1].toIntOrNull() == TigerGoatActiveArchive.FORMAT_VERSION)
        val nativeVersion = fields[2].toIntOrNull()?.also { require(it in 1..2) }
            ?: throw IllegalArgumentException("Invalid tiger-goat native version")
        val id = if (fields[3] == "-") "" else decodeHex(fields[3], MAX_ID_BYTES).toString(Charsets.UTF_8).also {
            require(it.toByteArray(Charsets.UTF_8).toHex() == fields[3].lowercase()) { "Invalid session ID UTF-8" }
        }
        val actions = fields[4].toIntOrNull()?.also { require(it in 0..65_535) }
            ?: throw IllegalArgumentException("Invalid tiger-goat action count")
        val updated = fields[5].toLongOrNull()?.also { require(it >= 0) }
            ?: throw IllegalArgumentException("Invalid tiger-goat timestamp")
        return TigerGoatActiveArchive(id, decodeHex(fields[6], TigerGoatActiveArchive.MAX_STATE_BYTES), actions, updated)
            .also { require(it.engineFormatVersion == nativeVersion) }
    }

    /** Canonical fields belong to this game module, including migrated backup fixtures. */
    internal fun encodeArchive(archive: TigerGoatActiveArchive) = GameBackupRecord(KIND, listOf(
        GameType.TIGER_AND_GOAT.code.toString(),
        TigerGoatActiveArchive.FORMAT_VERSION.toString(),
        archive.engineFormatVersion.toString(),
        archive.sessionId.toByteArray(Charsets.UTF_8).toHex(),
        archive.actionCount.toString(),
        archive.updatedAtEpochMillis.toString(),
        archive.engineState.toHex(),
    )).also { require(it.encodedSizeBytes <= MAX_CANONICAL_RECORD_BYTES) }

    private fun decodeHex(value: String, maximumBytes: Int): ByteArray {
        require(value.length % 2 == 0 && value.length / 2 in 1..maximumBytes)
        return ByteArray(value.length / 2) { index ->
            val high = value[index * 2].digitToIntOrNull(16)
                ?: throw IllegalArgumentException("Invalid hex")
            val low = value[index * 2 + 1].digitToIntOrNull(16)
                ?: throw IllegalArgumentException("Invalid hex")
            ((high shl 4) or low).toByte()
        }
    }

    private fun ByteArray.toHex(): String = buildString(size * 2) {
        for (byte in this@toHex) {
            append(HEX[(byte.toInt() ushr 4) and 15])
            append(HEX[byte.toInt() and 15])
        }
    }

    private companion object {
        const val KIND = "TIGER_GOAT_ACTIVE"
        const val MAX_ID_BYTES = 256 // Existing wire limit; Archive also requires <= 64 UTF-16 units.
        const val HEX = "0123456789abcdef"
        // kind + seven separators + game/archive/native digits + ID hex + count + time + MOCT hex.
        const val MAX_CANONICAL_RECORD_BYTES = 17 + 7 + 3 + MAX_ID_BYTES * 2 + 5 + 19 +
            TigerGoatActiveArchive.MAX_STATE_BYTES * 2
        val SLOT = BackupStorageSlot(BackupStorageTable.ACTIVE_GAME, GameType.TIGER_AND_GOAT)
    }
}
