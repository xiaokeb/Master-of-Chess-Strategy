package com.masterofchessstrategy.games.tiger_goat.data

import com.masterofchessstrategy.data.LocalDataBackupRepository
import com.masterofchessstrategy.data.LocalDataRestoreSummary

/** Wraps the complete existing backup repository; shared factory chooses cross-game lock order. */
internal class TigerGoatCoordinatedBackupRepository(
    private val delegate: LocalDataBackupRepository,
    private val gate: TigerGoatStorageEpochGate,
) : LocalDataBackupRepository {
    override suspend fun export(createdAtEpochMillis: Long): ByteArray = gate.exclusive(replacing = false) {
        delegate.export(createdAtEpochMillis)
    }

    override suspend fun restore(bytes: ByteArray): LocalDataRestoreSummary {
        val owned = bytes.copyOf()
        return gate.exclusive(replacing = true) { delegate.restore(owned) }
    }
}
