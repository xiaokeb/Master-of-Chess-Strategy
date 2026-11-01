package com.masterofchessstrategy.games.competition_mahjong.learning

import com.masterofchessstrategy.data.BackupStorageSlot
import com.masterofchessstrategy.data.BackupStorageTable
import com.masterofchessstrategy.data.GameBackupAdapter
import com.masterofchessstrategy.data.GameBackupDescriptor
import com.masterofchessstrategy.data.GameBackupRecord
import com.masterofchessstrategy.data.MocsDatabase
import com.masterofchessstrategy.data.PreparedGameBackup
import com.masterofchessstrategy.engine.GameType

/** Dedicated chapter receipts; the shared historical TUTORIAL record never converts to this kind. */
internal class CompetitionMahjongLearningBackupAdapter : GameBackupAdapter {
    override val descriptor = GameBackupDescriptor(KIND,5,SLOT,1,42,32+2*CompetitionMahjongLearningProofCodec.MAX_BYTES)

    override suspend fun read(database:MocsDatabase):List<GameBackupRecord> {
        check(database.inTransaction()) { "Learning backup requires the enclosing snapshot transaction" }
        return readBytes(database.competitionMahjongLearningProofDao())?.let { listOf(encode(it)) } ?: emptyList()
    }

    override fun validate(record:GameBackupRecord,containerVersion:Int) {
        require(containerVersion == 5)
        decode(record)
    }

    override fun prepareRestore(record:GameBackupRecord):PreparedGameBackup = Prepared(decode(record))

    internal fun encode(bytes:ByteArray):GameBackupRecord {
        require(CompetitionMahjongLearningProofCodec.decode(bytes) != null)
        return GameBackupRecord(KIND,listOf("2",bytes.joinToString("") { "%02x".format(it.toInt() and 255) }))
    }

    internal fun decode(record:GameBackupRecord):ByteArray {
        require(record.kind == KIND && record.fields.size == 2 && record.fields[0] == "2" &&
            record.encodedSizeBytes <= descriptor.maximumRecordBytes)
        val hex = record.fields[1]
        require(hex.length in 2*CompetitionMahjongLearningProofCodec.MIN_BYTES..2*CompetitionMahjongLearningProofCodec.MAX_BYTES &&
            hex.length % 2 == 0 && hex.all { it in '0'..'9' || it in 'a'..'f' })
        return ByteArray(hex.length/2) { hex.substring(it*2,it*2+2).toInt(16).toByte() }.also {
            require(CompetitionMahjongLearningProofCodec.decode(it) != null) { "Incompatible chapter proof" }
        }
    }

    private class Prepared(bytes:ByteArray) : PreparedGameBackup {
        private val owned = bytes.copyOf()
        override val storageSlot = SLOT
        override suspend fun write(database:MocsDatabase) {
            check(database.inTransaction()) { "Learning installation requires the replacement transaction" }
            database.competitionMahjongLearningProofDao().upsert(CompetitionMahjongLearningProofRow(0,owned.copyOf()))
        }
    }

    companion object {
        const val KIND = "COMPETITION_LEARNING"
        val SLOT = BackupStorageSlot(BackupStorageTable.COMPETITION_LEARNING,GameType.STANDARD_MAHJONG)

        /** Validate every actual target key/payload before clearAll, even when no incoming proof exists. */
        suspend fun requireStoredProof(database:MocsDatabase) {
            check(database.inTransaction())
            readBytes(database.competitionMahjongLearningProofDao())
        }

        internal suspend fun readBytes(dao:CompetitionMahjongLearningProofDao):ByteArray? {
            val rows = dao.readBoundedMetadata()
            if(rows.isEmpty()) return null
            require(rows.size == 1 && rows.single().singletonId == 0 &&
                rows.single().proofBytes in CompetitionMahjongLearningProofCodec.MIN_BYTES.toLong()..
                    CompetitionMahjongLearningProofCodec.MAX_BYTES.toLong()) { "Unknown or incompatible chapter proof row" }
            val bytes = requireNotNull(dao.readBoundedPayload(CompetitionMahjongLearningProofCodec.MIN_BYTES,
                CompetitionMahjongLearningProofCodec.MAX_BYTES))
            require(bytes.size.toLong() == rows.single().proofBytes && CompetitionMahjongLearningProofCodec.decode(bytes) != null)
            return bytes.copyOf()
        }
    }
}
