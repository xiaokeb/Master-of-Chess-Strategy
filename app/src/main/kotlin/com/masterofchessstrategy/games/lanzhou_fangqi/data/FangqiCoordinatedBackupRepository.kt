package com.masterofchessstrategy.games.lanzhou_fangqi.data

import com.masterofchessstrategy.data.LocalDataBackupRepository

/** Wrap the existing Go-gated repository: the only nested order is Fangqi, then Go, then Room. */
internal class FangqiCoordinatedBackupRepository(
    private val goCoordinatedDelegate: LocalDataBackupRepository,
    private val gate: FangqiStorageEpochGate,
) : LocalDataBackupRepository {
    override suspend fun export(createdAtEpochMillis: Long): ByteArray = gate.exclusive(replacing = false) {
        goCoordinatedDelegate.export(createdAtEpochMillis)
    }

    override suspend fun restore(bytes: ByteArray): com.masterofchessstrategy.data.LocalDataRestoreSummary {
        // Own input before waiting for either gate; the document picker may release its buffer.
        val owned = bytes.copyOf()
        return gate.exclusive(replacing = true) {
            goCoordinatedDelegate.restore(owned)
        }
    }
}
