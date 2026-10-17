package com.masterofchessstrategy.games.competition_mahjong.data

import com.masterofchessstrategy.data.BackupStorageSlot
import com.masterofchessstrategy.data.BackupStorageTable
import com.masterofchessstrategy.data.CompetitionMahjongMatchArchive
import com.masterofchessstrategy.data.CompetitionMahjongMatchDao
import com.masterofchessstrategy.data.GameBackupAdapter
import com.masterofchessstrategy.data.GameBackupDescriptor
import com.masterofchessstrategy.data.GameBackupRecord
import com.masterofchessstrategy.data.MocsDatabase
import com.masterofchessstrategy.data.PreparedGameBackup
import com.masterofchessstrategy.data.validateCompetitionMahjongMatchArchive
import com.masterofchessstrategy.engine.GameType

/** Private MCMT persistence only: this is not a public replay or a four-player table view. */
internal class CompetitionMahjongBackupAdapter(
    private val admit: (CompetitionMahjongMatchArchive) -> Boolean = ::validateCompetitionMahjongMatchArchive,
) : GameBackupAdapter {
    override val descriptor = GameBackupDescriptor(
        kind = KIND,
        minimumContainerVersion = 4,
        storageSlot = SLOT,
        maximumRecords = 1,
        canonicalOrder = 1,
        // Preserve the old container's textual limit, including parseable legacy numeric spellings.
        maximumRecordBytes = GameBackupRecord.MAX_BYTES,
    )

    override suspend fun read(database: MocsDatabase) = readFrom(database.competitionMahjongMatchDao())

    /** The repository supplies the surrounding read transaction; metadata is never silently repaired. */
    internal suspend fun readFrom(dao: CompetitionMahjongMatchDao): List<GameBackupRecord> {
        val rows = dao.listAll()
        require(rows.size <= descriptor.maximumRecords) { "Too many competition match rows" }
        return rows.map { encodeArchive(CompetitionMahjongMatchArchive.fromEntity(it)) }
    }

    override fun validate(record: GameBackupRecord, containerVersion: Int) {
        require(containerVersion in 4..5) { "Competition matches require backup version 4 or 5" }
        decodeRecord(record) // Envelope/configuration checks only; no Native call or write.
    }

    override fun normalize(record: GameBackupRecord, containerVersion: Int): GameBackupRecord {
        require(containerVersion in 4..5) { "Competition matches require backup version 4 or 5" }
        return encodeArchive(decodeRecord(record))
    }

    override fun prepareRestore(record: GameBackupRecord): PreparedGameBackup {
        val archive = decodeRecord(record)
        // Default admission restores real Native state, checks the terminal ledger and closes it.
        require(admit(archive)) { "Backup contains an invalid competition hand or terminal ledger" }
        return PreparedCompetitionMahjongBackup(archive)
    }

    /** Decode only the owned private envelope; callers must still perform actual Native admission. */
    internal fun decodeRecord(record: GameBackupRecord): CompetitionMahjongMatchArchive {
        require(record.kind == KIND && record.fields.size == 6) { "Invalid competition backup fields" }
        val fields = record.fields
        require(fields[0].toIntOrNull() == GameType.STANDARD_MAHJONG.code) { "Foreign competition owner" }
        require(fields[1].toIntOrNull() == CompetitionMahjongMatchArchive.FORMAT_VERSION) { "Unsupported competition archive version" }
        val viewer = requireNotNull(fields[2].toIntOrNull()).also { require(it in 0..3) }
        val id = if (fields[3] == "-") "" else decodeHex(fields[3], MAX_ID_BYTES).let { bytes ->
            bytes.toString(Charsets.UTF_8).also { require(it.toByteArray(Charsets.UTF_8).toHex() == fields[3].lowercase()) }
        }
        val updatedAt = requireNotNull(fields[4].toLongOrNull()).also { require(it >= 0) }
        // Bound decoded size before allocating; Archive validates MCMT/MCPK/MCMS, not replay legality.
        return CompetitionMahjongMatchArchive(viewer, id, updatedAt,
            decodeHex(fields[5], CompetitionMahjongMatchArchive.MAX_CHECKPOINT_BYTES))
    }

    /** Keep canonical wire construction in the game module, including tests migrating old snapshots. */
    internal fun encodeArchive(archive: CompetitionMahjongMatchArchive) = GameBackupRecord(KIND, listOf(
        GameType.STANDARD_MAHJONG.code.toString(), CompetitionMahjongMatchArchive.FORMAT_VERSION.toString(),
        archive.viewerPlayerId.toString(), archive.matchId.toByteArray(Charsets.UTF_8).toHex(),
        archive.updatedAtEpochMillis.toString(), archive.encodedCheckpoint.toHex(),
    ))

    private fun decodeHex(text: String, maximumBytes: Int): ByteArray {
        require(text.length % 2 == 0 && text.length / 2 in 1..maximumBytes) { "Invalid competition hex size" }
        return ByteArray(text.length / 2) { index ->
            val high = requireNotNull(text[index * 2].digitToIntOrNull(16)) { "Invalid competition hex" }
            val low = requireNotNull(text[index * 2 + 1].digitToIntOrNull(16)) { "Invalid competition hex" }
            ((high shl 4) or low).toByte()
        }
    }

    private fun ByteArray.toHex(): String {
        val output = CharArray(size * 2)
        forEachIndexed { index, byte ->
            output[index * 2] = HEX[(byte.toInt() ushr 4) and 15]
            output[index * 2 + 1] = HEX[byte.toInt() and 15]
        }
        return String(output)
    }

    private companion object {
        const val KIND = "COMPETITION_MATCH"
        const val MAX_ID_BYTES = 256
        const val HEX = "0123456789abcdef"
        val SLOT = BackupStorageSlot(BackupStorageTable.COMPETITION_MATCH, GameType.STANDARD_MAHJONG)
    }
}

/** Only owns admitted bytes and writes its own slot; clearing/transaction ownership stays public. */
internal class PreparedCompetitionMahjongBackup internal constructor(
    private val archive: CompetitionMahjongMatchArchive,
) : PreparedGameBackup {
    override val storageSlot = BackupStorageSlot(BackupStorageTable.COMPETITION_MATCH, GameType.STANDARD_MAHJONG)
    override suspend fun write(database: MocsDatabase) = writeTo(database.competitionMahjongMatchDao())

    internal suspend fun writeTo(dao: CompetitionMahjongMatchDao) {
        dao.upsert(archive.toEntity())
    }
}
