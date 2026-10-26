package com.masterofchessstrategy.games.lanzhou_mahjong.data

import com.masterofchessstrategy.data.LocalDataBackupRepository
import com.masterofchessstrategy.data.LocalDataRestoreSummary

/** Root composes the fixed order Lanzhou Mahjong, then Fangqi, then Go, then Room. */
internal class LanzhouMahjongCoordinatedBackupRepository(
    private val innerCoordinatedDelegate: LocalDataBackupRepository,
    private val gate: LanzhouMahjongStorageEpochGate,
) : LocalDataBackupRepository {
    override suspend fun export(createdAtEpochMillis: Long): ByteArray = gate.exclusive(replacing = false) {
        innerCoordinatedDelegate.export(createdAtEpochMillis)
    }

    override suspend fun restore(bytes: ByteArray): LocalDataRestoreSummary {
        // Own the picker buffer before waiting for this gate or any inner owner.
        val owned = bytes.copyOf()
        return gate.exclusive(replacing = true) {
            innerCoordinatedDelegate.restore(owned)
        }
    }
}
