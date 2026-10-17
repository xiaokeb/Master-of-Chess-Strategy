package com.masterofchessstrategy.games.go.data

import android.content.Context
import com.masterofchessstrategy.data.GameBackupAdapter
import com.masterofchessstrategy.data.LocalDataBackupRepository
import com.masterofchessstrategy.data.MocsDatabase
import com.masterofchessstrategy.games.go.*

/** Production factories switch all local sources together; callers register each VM as a writer. */
internal class GoLocalStorage(private val database: MocsDatabase, context: Context) {
    private val application = context.applicationContext
    val gate = GoStorageEpochGate.forDatabase(database)

    fun sessionRepository() = GatedGoSessionRepository(
        RoomGoSessionRepository(database, GoLocalSessionDiskSource(application)), gate)
    fun learningRepository() = GatedGoLearningRepository(
        RoomGoLearningProgressRepository(database, GoLearningPreferencesDiskSource(application)), gate)
    fun endgameRepository() = GatedGoEndgameRepository(
        RoomGoEndgameProgressRepository(database, GoEndgameDiskSource(application)), gate)
    fun aiRepository() = GatedGoAiMatchRepository(GoAiMatchFileRepository(application), gate)

    fun backupAdapters(): List<GameBackupAdapter> = listOf(GoLocalSessionBackupAdapter(),
        GoLocalRecordBackupAdapter(), GoProgressBackupAdapter())
    fun backupRepository(delegate: LocalDataBackupRepository): LocalDataBackupRepository =
        GatedGoBackupRepository(delegate, gate, GoLocalBackupPreparation(database, application))
}
