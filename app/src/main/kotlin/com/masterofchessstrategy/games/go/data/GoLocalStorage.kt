package com.masterofchessstrategy.games.go.data

import android.content.Context
import com.masterofchessstrategy.data.GameBackupAdapter
import com.masterofchessstrategy.data.LocalDataBackupRepository
import com.masterofchessstrategy.data.MocsDatabase
import com.masterofchessstrategy.engine.GameType
import com.masterofchessstrategy.games.go.*
import com.masterofchessstrategy.progress.EndgameRewardTotals
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** Production factories switch all local sources together; callers register each VM as a writer. */
internal class GoLocalStorage(private val database: MocsDatabase, context: Context) {
    private val application = context.applicationContext
    private val complete = GoCompleteStorageBundle(database, application)
    val gate = complete.gate

    fun sessionRepository() = GatedGoSessionRepository(
        RoomGoSessionRepository(database, GoLocalSessionDiskSource(application)), gate)
    fun learningRepository() = GatedGoLearningRepository(
        RoomGoLearningProgressRepository(database, GoLearningPreferencesDiskSource(application)), gate)
    fun difficultyEligibilityRepository() = RoomGoDifficultyEligibilityRepository(
        database, GoLearningPreferencesDiskSource(application), gate)
    fun endgameRepository() = GatedGoEndgameRepository(
        RoomGoEndgameProgressRepository(database, GoEndgameDiskSource(application)), gate)
    fun observeEndgameRewards() = database.goProgressDao().observe(GameType.GO.code, GoProgressKind.ENDGAME.code)
        .map { row ->
            row?.takeIf { it.matches(GoProgressKind.ENDGAME) }?.payload?.let(GoEndgameProgressCodec::decode)
                ?.let { GoEndgameLibrary.rewards(it.completedIds) } ?: EndgameRewardTotals()
        }.distinctUntilChanged()
    fun aiRepository() = complete.aiRepository()
    fun completedMatchSink() = complete.completedMatchSink()
    fun rankedMatchCreator() = complete.rankedMatchCreator()
    fun formalAiRepository() = complete.formalAiRepository()
    fun aiEntryRepository() = complete.aiEntryRepository()

    // The delegate receives all five Go slots from the same bundle as the AI repository.
    fun backupRepository(createDelegate: (List<GameBackupAdapter>) -> LocalDataBackupRepository): LocalDataBackupRepository =
        complete.backupRepository(createDelegate)
}
