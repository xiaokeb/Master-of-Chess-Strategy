package com.masterofchessstrategy.games.go.data

import android.content.Context
import androidx.room.withTransaction
import com.masterofchessstrategy.data.MocsDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

internal fun interface GoUnsupportedBackupSourceGuard {
    suspend fun check()
}

internal interface GoBackupPreparation {
    suspend fun beforeExport()
    suspend fun beforeRestore()
}

/** GAI1 is not in the local bundle; detect all its disk images rather than silently omit it. */
internal class GoAiDiskBackupGuard(private val file: File) : GoUnsupportedBackupSourceGuard {
    constructor(context: Context) : this(context.applicationContext.getFileStreamPath("go-ai-match-v1.bin"))
    override suspend fun check() = withContext(Dispatchers.IO) {
        if (listOf(file, File(file.path + ".bak"), File(file.path + ".new")).any { it.exists() }) {
            throw IOException("围棋 AI 活动数据尚未接入全量备份，原数据已保留")
        }
    }
}

/**
 * The host must stop producers and await all old writers before either operation.
 * Export imports all three old sources in one transaction; restore must not import replaced data.
 */
internal class GoLocalBackupPreparation(
    private val database: MocsDatabase,
    private val session: GoLocalLegacySource,
    private val learning: GoLearningLegacySource,
    private val endgame: GoEndgameLegacySource,
    private val unsupportedSources: GoUnsupportedBackupSourceGuard,
) : GoBackupPreparation {
    constructor(database: MocsDatabase, context: Context) : this(database,
        GoLocalSessionDiskSource(context), GoLearningPreferencesDiskSource(context), GoEndgameDiskSource(context),
        GoAiDiskBackupGuard(context))

    override suspend fun beforeExport() {
        unsupportedSources.check()
        database.withTransaction {
            // A bad third source must not leave a partially migrated session/tutorial behind.
            RoomGoSessionRepository(database, session).load()
            RoomGoLearningProgressRepository(database, learning).load()
            RoomGoEndgameProgressRepository(database, endgame).load()
        }
    }

    override suspend fun beforeRestore() {
        // The replacement transaction seals all three sources, including when no Go is incoming.
        // Do not inspect/migrate old local data here: restoring a valid backup can replace corrupt rows.
        unsupportedSources.check()
    }
}
