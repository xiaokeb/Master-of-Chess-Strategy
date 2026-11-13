package com.masterofchessstrategy.games.competition_mahjong.challenge

import com.masterofchessstrategy.data.BackupStorageSlot
import com.masterofchessstrategy.data.BackupStorageTable
import com.masterofchessstrategy.data.GameBackupAdapter
import com.masterofchessstrategy.data.GameBackupDescriptor
import com.masterofchessstrategy.data.GameBackupRecord
import com.masterofchessstrategy.data.MocsDatabase
import com.masterofchessstrategy.data.PreparedGameBackup
import com.masterofchessstrategy.engine.GameType

internal class CompetitionMahjongChallengeBackupAdapter : GameBackupAdapter {
    override val descriptor = GameBackupDescriptor(KIND, 5, SLOT, 1, 44,
        64 + 2 * CompetitionMahjongChallengeProgressCodec.MAX_BYTES)

    override suspend fun read(database: MocsDatabase): List<GameBackupRecord> {
        check(database.inTransaction())
        val progress = readCompetitionChallengeProgress(database.competitionMahjongChallengeProgressDao()) ?: return emptyList()
        return listOf(GameBackupRecord(KIND, listOf(GAME_CODE, CompetitionMahjongChallengeProgressCodec.encode(progress).toHexString())))
    }

    override fun validate(record: GameBackupRecord, containerVersion: Int) {
        require(containerVersion == 5)
        decode(record)
    }

    override fun prepareRestore(record: GameBackupRecord): PreparedGameBackup {
        val payload = decode(record)
        return object : PreparedGameBackup {
            override val storageSlot = SLOT
            override suspend fun write(database: MocsDatabase) {
                check(database.inTransaction())
                database.competitionMahjongChallengeProgressDao().save(CompetitionMahjongChallengeProgressRow(0, payload.copyOf()))
            }
        }
    }

    private fun decode(record: GameBackupRecord): ByteArray {
        require(record.kind == KIND && record.fields.size == 2 && record.fields[0] == GAME_CODE &&
            record.encodedSizeBytes <= descriptor.maximumRecordBytes)
        val hex = record.fields[1]
        require(hex.length in 2..2 * CompetitionMahjongChallengeProgressCodec.MAX_BYTES && hex.length % 2 == 0 &&
            hex.all { it in '0'..'9' || it in 'a'..'f' })
        return hex.hexToByteArray().also { require(CompetitionMahjongChallengeProgressCodec.decode(it) != null) }
    }

    companion object {
        const val KIND = "COMPETITION_CHALLENGE"
        private val GAME_CODE = GameType.STANDARD_MAHJONG.code.toString()
        val SLOT = BackupStorageSlot(BackupStorageTable.COMPETITION_CHALLENGE, GameType.STANDARD_MAHJONG)
    }
}
