package com.masterofchessstrategy.games.go.data

import android.content.Context
import com.masterofchessstrategy.data.GameBackupAdapter
import com.masterofchessstrategy.data.LocalDataBackupRepository
import com.masterofchessstrategy.data.MocsDatabase
import com.masterofchessstrategy.games.go.*

/** Production factories switch all local sources together; callers register each VM as a writer. */
internal class GoLocalStorage(private val database: MocsDatabase, context: Context) {
    private val application = context.applicationContext
    private val complete = GoCompleteStorageBundle(database, application)
    val gate = complete.gate

    fun sessionRepository() = GatedGoSessionRepository(
        RoomGoSessionRepository(database, GoLocalSessionDiskSource(application)), gate)
    fun learningRepository() = GatedGoLearningRepository(
        RoomGoLearningProgressRepository(database, GoLearningPreferencesDiskSource(application)), gate)
    fun endgameRepository() = GatedGoEndgameRepository(
        RoomGoEndgameProgressRepository(database, GoEndgameDiskSource(application)), gate)
    fun aiRepository() = complete.aiRepository()
    fun completedMatchSink() = complete.completedMatchSink()
    fun rankedMatchCreator() = complete.rankedMatchCreator()

    // The delegate receives all five Go slots from the same bundle as the AI repository.
    fun backupRepository(createDelegate: (List<GameBackupAdapter>) -> LocalDataBackupRepository): LocalDataBackupRepository =
        complete.backupRepository(createDelegate)
}
