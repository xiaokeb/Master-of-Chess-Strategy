package com.masterofchessstrategy.games.competition_mahjong.learning

import com.masterofchessstrategy.data.LocalDataBackupRepository
import com.masterofchessstrategy.data.LocalDataRestoreSummary

/** Surround the complete backup composition, outside every per-game lock and Room transaction. */
internal class CompetitionMahjongCoordinatedBackupRepository(
    private val delegate: LocalDataBackupRepository,
    private val gate: CompetitionMahjongStorageEpochGate,
) : LocalDataBackupRepository {
    override suspend fun export(createdAtEpochMillis: Long): ByteArray = gate.exclusive(replacing = false) {
        delegate.export(createdAtEpochMillis)
    }

    override suspend fun restore(bytes: ByteArray): LocalDataRestoreSummary {
        val owned = bytes.copyOf()
        return gate.exclusive(replacing = true) { delegate.restore(owned) }
    }
}
